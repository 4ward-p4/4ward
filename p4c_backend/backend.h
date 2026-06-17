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

#ifndef P4C_BACKEND_BACKEND_H_
#define P4C_BACKEND_BACKEND_H_

#include <string>
#include <vector>

#include "frontends/common/resolveReferences/referenceMap.h"
#include "frontends/p4/typeMap.h"
#include "ir/ir.h"
#include "ir/visitor.h"
#include "p4/v1/p4runtime.pb.h"
#include "p4c_backend/options.h"
#include "simulator/ir.pb.h"

namespace P4::FourWard {

// -----------------------------------------------------------------------
// FourWardBackend
//
// The top-level backend pass. Walks the p4c ToplevelBlock after all midend
// passes and populates a PipelineConfig proto.
// -----------------------------------------------------------------------
class FourWardBackend : public Inspector {
 public:
  FourWardBackend(const FourWardOptions& options, const ReferenceMap& refMap,
                  const TypeMap& typeMap);

  // Called by the pass manager to trigger IR traversal.
  void process(const IR::ToplevelBlock* toplevel);

  // Injects the p4info produced by the P4Runtime serialiser into the config.
  // Must be called before process() so emitTable() can look up field IDs.
  void setP4Info(p4::config::v1::P4Info p4info);

  // Injects static table entries (const entries) from the P4Runtime serialiser.
  void setStaticEntries(p4::v1::WriteRequest entries);

  // Injects type translation mappings extracted from
  // @p4runtime_translation_mappings annotations.
  void setTypeTranslations(std::vector<fourward::TypeTranslation> translations);

  // Sets the port type name on the Architecture proto.
  // Must be called after process() (which builds the Architecture).
  void setPortTypeName(std::string portTypeName);

  // Writes each requested output file (combined `-o`, `--out-p4info`,
  // `--out-p4-device-config`). Returns true on success.
  bool writeOutputs() const;

 private:
  const FourWardOptions& options_;
  const ReferenceMap& refMap_;
  const TypeMap& typeMap_;

  fourward::PipelineConfig pipelineConfig_;
  fourward::BehavioralConfig* behavioral_;

  // Set by emitControl so nested emitters can use the enclosing control name.
  std::string controlName_;

  void emitTypeDecls(const IR::P4Program* program);
  void emitParser(const IR::P4Parser* parser);
  void emitControl(const IR::P4Control* control);
  void emitAction(const IR::P4Action* action, fourward::ActionDecl* out);
  void emitTable(const IR::P4Table* table);
  void emitArchitecture(const IR::ToplevelBlock* toplevel);
  const p4::config::v1::Table* findP4InfoTable(const IR::P4Table* table) const;
  const p4::config::v1::Action* findP4InfoAction(
      const IR::P4Action& action) const;
  const IR::P4Action* resolveActionListElement(
      const IR::ActionListElement* actionListElement) const;
  const p4::config::v1::Action* findP4InfoActionRef(
      const p4::config::v1::Table& table, const IR::P4Action& action) const;

  // IR-to-proto converters. These are member functions so they can access
  // refMap_ (needed to resolve PathExpression declarations for table apply
  // detection) and typeMap_ directly.
  fourward::Type emitType(const IR::Type* type);
  fourward::Expr emitExpr(const IR::Expression* expr);
  fourward::Stmt emitStmt(const IR::StatOrDecl* node);
  fourward::BlockStmt emitBlock(const IR::BlockStatement* block);
  static fourward::SourceInfo emitSourceInfo(const IR::Node* node);
};

}  // namespace P4::FourWard

#endif  // P4C_BACKEND_BACKEND_H_
