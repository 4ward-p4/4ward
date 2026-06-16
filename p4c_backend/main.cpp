/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// p4c-4ward: the 4ward p4c backend.
//
// Compiles a P4 program into one or more proto files for the 4ward simulator
// and P4Runtime control plane. Each output is opt-in; at least one must be
// requested:
//
//   -o <file>                       Combined pipeline config
//                                   (--format selects native or p4runtime)
//   --out-p4info <file>             Standalone p4.config.v1.P4Info
//   --out-p4-device-config <file>   Standalone fourward.DeviceConfig
//
// All output paths must end in .txtpb (text proto) or .binpb (binary proto).
//
// Example:
//
//   p4c-4ward --arch v1model -o pipeline.binpb --format p4runtime input.p4

#include <cstdlib>
#include <exception>
#include <iostream>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "control-plane/p4RuntimeSerializer.h"
#include "frontends/common/applyOptionsPragmas.h"
#include "frontends/common/parseInput.h"
#include "frontends/common/parser_options.h"
#include "frontends/p4/evaluator/evaluator.h"
#include "frontends/p4/frontend.h"
#include "frontends/p4/parseAnnotations.h"
#include "ir/ir.h"
#include "lib/compile_context.h"
#include "lib/crash.h"
#include "lib/error.h"
#include "lib/gc.h"
#include "lib/log.h"
#include "lib/nullstream.h"
#include "p4c_backend/backend.h"
#include "p4c_backend/midend.h"
#include "p4c_backend/options.h"

namespace IR = P4::IR;
using P4::literals::operator""_cs;

// Validates that every requested output path uses a .txtpb or .binpb
// extension and that at least one output flag was supplied. Run upfront so a
// typo or missing flag fails before any compilation work happens.
static void checkOutputExtension(const std::optional<std::string>& path,
                                 const char* flag) {
  if (!path.has_value()) return;
  if (!path->ends_with(".txtpb") && !path->ends_with(".binpb")) {
    ::P4::error(
        "4ward: %1% '%2%' has unsupported extension; "
        "use .txtpb (text) or .binpb (binary)",
        flag, *path);
  }
}

static bool validateOutputs(const P4::FourWard::FourWardOptions& options) {
  checkOutputExtension(options.outputFile, "-o");
  checkOutputExtension(options.outP4Info, "--out-p4info");
  checkOutputExtension(options.outP4DeviceConfig, "--out-p4-device-config");
  if (!options.outputFile.has_value() && !options.outP4Info.has_value() &&
      !options.outP4DeviceConfig.has_value()) {
    ::P4::error(
        "4ward: at least one output flag is required: -o, --out-p4info, or "
        "--out-p4-device-config");
  }
  return ::P4::errorCount() == 0;
}

// Walks the post-frontend IR to extract @p4runtime_translation_mappings
// annotations from Type_Newtype declarations.  These annotations specify
// explicit SDN ↔ data-plane value mappings for @p4runtime_translation types.
// The annotations must be parsed (via ParseAnnotations::parseExpressionList)
// before calling this function.
//
// Returns one TypeTranslation per annotated type, with auto_allocate=true
// (hybrid mode: explicit pins + auto-allocate unknown values).
static std::vector<fourward::TypeTranslation> extractTypeTranslations(
    const IR::P4Program* program, const p4::config::v1::P4Info& p4Info) {
  std::vector<fourward::TypeTranslation> result;
  const auto& newTypes = p4Info.type_info().new_types();

  P4::forAllMatching<IR::Type_Newtype>(
      program, [&](const IR::Type_Newtype* nt) {
        const auto* ann =
            nt->getAnnotation("p4runtime_translation_mappings"_cs);
        if (ann == nullptr) return;

        // Look up the URI from p4info's type_info.new_types map.
        std::string typeName(nt->name.name.c_str());
        auto it = newTypes.find(typeName);
        if (it == newTypes.end() || !it->second.has_translated_type()) {
          ::P4::warning(P4::ErrorType::WARN_MISSING,
                        "%1%: @p4runtime_translation_mappings on type without "
                        "matching p4info translated type; ignoring",
                        nt);
          return;
        }
        const auto& translatedType = it->second.translated_type();
        bool isSdnString = translatedType.has_sdn_string();

        fourward::TypeTranslation translation;
        // Use type_name (not type_uri): the TypeTranslator keys translation
        // tables by type name, and SAI P4 leaves the URI empty (unset) for
        // all translated types, relying on type names for disambiguation.
        translation.set_type_name(typeName);
        translation.set_auto_allocate(true);

        int bitWidth = nt->type->width_bits();
        int byteWidth = (bitWidth + 7) / 8;

        // Annotation body: { {sdnValue, dpValue}, ... }
        const auto& exprs = ann->getExpr();
        const auto* outerList = exprs.at(0)->checkedTo<IR::ListExpression>();
        for (const auto* component : outerList->components) {
          const auto* tuple = component->checkedTo<IR::ListExpression>();
          auto* entry = translation.add_entries();

          // SDN value.
          if (isSdnString) {
            const auto* sdnStr =
                tuple->components.at(0)->checkedTo<IR::StringLiteral>();
            entry->set_sdn_str(std::string(sdnStr->value.c_str()));
          } else {
            const auto* sdnConst =
                tuple->components.at(0)->checkedTo<IR::Constant>();
            int sdnByteWidth = (translatedType.sdn_bitwidth() + 7) / 8;
            std::string sdnBytes(sdnByteWidth, '\0');
            auto sv = sdnConst->value.convert_to<uint64_t>();
            for (int i = sdnByteWidth - 1; i >= 0; --i) {
              sdnBytes[i] = static_cast<char>(sv & 0xFF);
              sv >>= 8;
            }
            entry->set_sdn_bitstring(sdnBytes);
          }

          // Dataplane value (big-endian bytes matching the underlying bit
          // width).
          const auto* dpConst =
              tuple->components.at(1)->checkedTo<IR::Constant>();
          std::string dpBytes(byteWidth, '\0');
          auto dv = dpConst->value.convert_to<uint64_t>();
          for (int i = byteWidth - 1; i >= 0; --i) {
            dpBytes[i] = static_cast<char>(dv & 0xFF);
            dv >>= 8;
          }
          entry->set_dataplane_value(dpBytes);
        }

        result.push_back(std::move(translation));
      });

  return result;
}

// Derives the port type name from the architecture's standard_metadata struct.
// Returns the type name of ingress_port if it uses a P4 newtype (§7.2.10),
// or empty if it uses a bare bit<N>.
//
// This requires the architecture definition (e.g. v1model.p4) to declare
// ingress_port with the appropriate newtype. Programs that need port
// translation (like SAI P4) should use a forked architecture where
// standard_metadata.ingress_port has the translated type.
static std::string derivePortTypeName(const IR::P4Program* program) {
  std::string result;
  P4::forAllMatching<IR::Type_Struct>(program, [&](const IR::Type_Struct* st) {
    // v1model: standard_metadata_t
    // PSA: psa_ingress_input_metadata_t
    if (st->name != "standard_metadata_t" &&
        st->name != "psa_ingress_input_metadata_t")
      return;
    for (const auto* field : st->fields) {
      if (field->name != "ingress_port") continue;
      if (const auto* nt = field->type->to<IR::Type_Newtype>()) {
        result = nt->name.name.c_str();
      } else if (const auto* tn = field->type->to<IR::Type_Name>()) {
        // After type resolution, the field type may be a Type_Name
        // referencing the newtype by name. This also matches typedefs,
        // but TypeTranslator.create() filters to translated types only.
        result = tn->path->name.name.c_str();
      }
    }
  });
  return result;
}

// p4c's P4Runtime serializer auto-assigns descending priorities (N, N-1, …, 1)
// to const table entries, ignoring @priority annotations.  BMv2's JSON backend
// respects those annotations, so the STF corpus expects @priority(1) = highest
// precedence.  Fix up by inverting the auto-assigned priorities for any const
// table that carries @priority annotations.
static void fixConstEntryPriorities(const IR::P4Program* program,
                                    const p4::config::v1::P4Info& p4Info,
                                    p4::v1::WriteRequest* entries) {
  // 1. Walk IR to find tables whose const entries have @priority.
  std::set<std::string> tablesWithPriority;
  P4::forAllMatching<IR::P4Table>(program, [&](const IR::P4Table* table) {
    const auto* el = table->getEntries();
    if (el == nullptr) return;
    for (const auto* e : el->entries) {
      if (e->getAnnotation("priority"_cs) != nullptr) {
        auto name = std::string(table->externalName().c_str());
        if (!name.empty() && name[0] == '.') name = name.substr(1);
        tablesWithPriority.insert(name);
        return;
      }
    }
  });
  if (tablesWithPriority.empty()) return;

  // 2. Resolve affected table names to p4info table IDs.
  std::set<uint32_t> affectedIds;
  for (const auto& t : p4Info.tables()) {
    if (tablesWithPriority.contains(t.preamble().name())) {
      affectedIds.insert(t.preamble().id());
    }
  }

  // 4. Count entries per affected table, then invert priorities.
  std::map<uint32_t, int32_t> entryCount;
  for (const auto& u : entries->updates()) {
    if (!u.entity().has_table_entry()) continue;
    auto tid = u.entity().table_entry().table_id();
    if (affectedIds.contains(tid)) entryCount[tid]++;
  }
  for (auto& u : *entries->mutable_updates()) {
    if (!u.entity().has_table_entry()) continue;
    auto* te = u.mutable_entity()->mutable_table_entry();
    auto it = entryCount.find(te->table_id());
    if (it == entryCount.end()) continue;
    te->set_priority(it->second + 1 - te->priority());
  }
}

int main(int argc, char* const argv[]) {
  setup_gc_logging();
  P4::setup_signals();

  try {
    P4::AutoCompileContext autoContext(
        new P4::P4CContextWithOptions<P4::FourWard::FourWardOptions>);
    auto& options =
        P4::P4CContextWithOptions<P4::FourWard::FourWardOptions>::get()
            .options();
    options.langVersion = P4::CompilerOptions::FrontendVersion::P4_16;

    if (options.process(argc, argv) != nullptr) {
      options.setInputFile();
    }
    if (::P4::errorCount() > 0) return EXIT_FAILURE;
    if (!validateOutputs(options)) return EXIT_FAILURE;

    const IR::P4Program* program = P4::parseP4File(options);
    if (program == nullptr || ::P4::errorCount() > 0) return EXIT_FAILURE;

    P4::FrontEnd frontend;
    program = frontend.run(options, program);
    if (program == nullptr || ::P4::errorCount() > 0) return EXIT_FAILURE;

    // Generate p4info from the post-frontend program (before midend
    // simplifications strip out information needed for the control-plane API).
    auto p4Runtime = P4::generateP4Runtime(program, "v1model"_cs);
    if (::P4::errorCount() > 0) return EXIT_FAILURE;

    auto entries = *p4Runtime.entries;
    fixConstEntryPriorities(program, *p4Runtime.p4Info, &entries);

    // Parse annotations the backend reads directly. Must happen before the
    // midend, which eliminates Type_Newtype and preserves header declarations
    // for behavioral IR emission.
    P4::ParseAnnotations parseTranslationMappings(
        "FourWard", false,
        {PARSE("controller_header"_cs, StringLiteral),
         {"p4runtime_translation_mappings"_cs,
          &P4::ParseAnnotations::parseExpressionList}});
    program = program->apply(parseTranslationMappings);

    auto translations = extractTypeTranslations(program, *p4Runtime.p4Info);

    P4::FourWard::MidEnd midend(options);
    const IR::ToplevelBlock* toplevel = midend.process(program);
    if (toplevel == nullptr || ::P4::errorCount() > 0) return EXIT_FAILURE;

    P4::FourWard::FourWardBackend backend(options, midend.refMap,
                                          midend.typeMap);
    // setP4Info must come before process so emitTable can look up match field
    // IDs.
    backend.setP4Info(*p4Runtime.p4Info);
    backend.setStaticEntries(entries);
    backend.setTypeTranslations(std::move(translations));
    backend.process(toplevel);
    backend.setPortTypeName(derivePortTypeName(program));

    if (!backend.writeOutputs()) return EXIT_FAILURE;
    return ::P4::errorCount() > 0 ? EXIT_FAILURE : EXIT_SUCCESS;
  } catch (const std::exception& e) {
    std::cerr << "p4c-4ward: " << e.what() << '\n';
    return EXIT_FAILURE;
  }
}
