// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/dataplane_matchers.h"

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <optional>
#include <ostream>
#include <string>
#include <vector>

#ifdef __APPLE__
#include <crt_externs.h>
#endif

#include "absl/base/call_once.h"
#include "absl/container/btree_map.h"
#include "absl/flags/flag.h"
#include "absl/strings/ascii.h"
#include "absl/strings/string_view.h"
#include "absl/strings/strip.h"

namespace fourward {
namespace internal {

bool AbslParseFlag(::absl::string_view text, MatcherTraceMode* mode,
                   std::string* error) {
  const std::string normalized = ::absl::AsciiStrToLower(text);
  if (normalized == "none") {
    *mode = MatcherTraceMode::kNone;
    return true;
  }
  if (normalized == "summary") {
    *mode = MatcherTraceMode::kSummary;
    return true;
  }
  if (normalized == "full") {
    *mode = MatcherTraceMode::kFull;
    return true;
  }
  *error = "expected one of: none, summary, full";
  return false;
}

std::string AbslUnparseFlag(MatcherTraceMode mode) {
  switch (mode) {
    case MatcherTraceMode::kNone:
      return "none";
    case MatcherTraceMode::kSummary:
      return "summary";
    case MatcherTraceMode::kFull:
      return "full";
  }
  return "unknown";
}

}  // namespace internal
}  // namespace fourward

ABSL_FLAG(::fourward::internal::MatcherTraceMode, fourward_matcher_trace,
          ::fourward::internal::MatcherTraceMode::kSummary,
          "Trace output in dataplane matcher failures: none, summary, full.");

namespace fourward {
namespace internal {

namespace {

std::vector<std::string> ReadProcessArgv() {
  std::vector<std::string> args;
#ifdef __linux__
  // Stock gtest_main leaves non-gtest flags unparsed, but Bazel's --test_arg
  // is still visible in the process command line. Import only this matcher flag
  // so users do not have to replace their test main to get useful diagnostics.
  std::ifstream cmdline("/proc/self/cmdline", std::ios::binary);
  std::string arg;
  while (std::getline(cmdline, arg, '\0')) {
    args.push_back(arg);
  }
#elif defined(__APPLE__)
  // Same issue as above, but macOS exposes argv through libSystem instead of
  // procfs.
  char** argv = *_NSGetArgv();
  const int argc = *_NSGetArgc();
  for (int i = 0; i < argc; ++i) {
    args.push_back(argv[i]);
  }
#endif
  return args;
}

std::optional<std::string> FindMatcherTraceFlagValue(
    const std::vector<std::string>& args) {
  constexpr absl::string_view kFlag = "--fourward_matcher_trace";
  std::optional<std::string> value;
  for (size_t i = 1; i < args.size(); ++i) {
    if (args[i] == "--") return value;

    absl::string_view arg = args[i];
    if (arg == kFlag) {
      if (i + 1 == args.size()) {
        std::cerr << kFlag << " needs a value; using the default trace mode\n";
        return std::nullopt;
      }
      value = args[++i];
      continue;
    }
    if (absl::ConsumePrefix(&arg, "--fourward_matcher_trace=")) {
      value = std::string(arg);
    }
  }
  return value;
}

void ImportMatcherTraceFlagFromUnparsedArgv() {
  std::optional<std::string> value = FindMatcherTraceFlagValue(ReadProcessArgv());
  if (!value.has_value()) return;

  MatcherTraceMode mode;
  std::string error;
  if (!AbslParseFlag(*value, &mode, &error)) {
    std::cerr << "Invalid --fourward_matcher_trace=" << *value << ": " << error
              << "; using the default trace mode\n";
    return;
  }
  absl::SetFlag(&::FLAGS_fourward_matcher_trace, mode);
}

}  // namespace

MatcherTraceMode GetMatcherTraceMode() {
  static absl::once_flag import_once;
  absl::call_once(import_once, ImportMatcherTraceFlagFromUnparsedArgv);
  return ::absl::GetFlag(::FLAGS_fourward_matcher_trace);
}

void SetMatcherTraceModeForTest(MatcherTraceMode mode) {
  ::absl::SetFlag(&::FLAGS_fourward_matcher_trace, mode);
}

namespace {

template <typename Key, typename Value>
void PrintCountMap(std::ostream* os, const ::absl::btree_map<Key, Value>& map) {
  *os << "{";
  bool first = true;
  for (const auto& [key, value] : map) {
    if (!first) *os << ", ";
    first = false;
    *os << key << ": " << value;
  }
  *os << "}";
}

template <typename Value>
void PrintCountMap(std::ostream* os,
                   const ::absl::btree_map<std::string, Value>& map) {
  *os << "{";
  bool first = true;
  for (const auto& [key, value] : map) {
    if (!first) *os << ", ";
    first = false;
    *os << "\"" << key << "\": " << value;
  }
  *os << "}";
}

void PrintTraceHint(std::ostream* os) {
  switch (GetMatcherTraceMode()) {
    case MatcherTraceMode::kNone:
      *os << "trace omitted; rerun with --fourward_matcher_trace=summary or "
             "--fourward_matcher_trace=full";
      return;
    case MatcherTraceMode::kSummary:
      *os << "full trace omitted; rerun with --fourward_matcher_trace=full";
      return;
    case MatcherTraceMode::kFull:
      return;
  }
}

}  // namespace

void PrintTraceForMatcher(std::ostream* os, const fourward::TraceTree& trace) {
  switch (GetMatcherTraceMode()) {
    case MatcherTraceMode::kNone:
      *os << "trace omitted\n";
      PrintTraceHint(os);
      return;
    case MatcherTraceMode::kSummary:
      PrintHumanTraceSummary(os, trace);
      PrintTraceHint(os);
      return;
    case MatcherTraceMode::kFull:
      *os << "trace proto:\n" << trace.DebugString();
      return;
  }
}

void PrintActualOutputSummary(std::ostream* os, const PacketList& packets) {
  if (packets.empty()) {
    *os << "actual output had no packets";
    return;
  }
  if (packets.size() == 1) {
    *os << "actual packet egressed on ";
    PrintPacketEgress(os, packets[0]);
    return;
  }

  *os << "actual output had " << packets.size() << " packets";
  for (size_t i = 0; i < packets.size(); ++i) {
    *os << (i == 0 ? ": " : ", ");
    PrintPacketEgress(os, packets[i]);
  }
}

void PrintPacketPortGroups(std::ostream* os, const PacketList& packets) {
  ::absl::btree_map<uint32_t, int> dataplane_ports;
  ::absl::btree_map<std::string, int> p4rt_ports;
  for (const auto& packet : packets) {
    ++dataplane_ports[packet.dataplane_egress_port()];
    if (!packet.p4rt_egress_port().empty()) {
      ++p4rt_ports[packet.p4rt_egress_port()];
    }
  }
  *os << "dataplane ports ";
  PrintCountMap(os, dataplane_ports);
  if (!p4rt_ports.empty()) {
    *os << ", P4Runtime ports ";
    PrintCountMap(os, p4rt_ports);
  }
}

void PrintPacketList(std::ostream* os, const PacketList& packets,
                     bool include_braces) {
  if (include_braces) *os << "{";
  *os << packets.size() << " packet"
      << (packets.size() == 1 ? "" : "s") << ", ";
  PrintPacketPortGroups(os, packets);
  if (include_braces) *os << "}";
}

void PrintPacketListSummary(std::ostream* os, const PacketList& packets) {
  PrintPacketList(os, packets, true);
}

void PrintPacketListDetails(std::ostream* os, const PacketList& packets) {
  PrintPacketList(os, packets, false);
}

void PrintOutcomesSummary(std::ostream* os,
                          const std::vector<PacketList>& outcomes) {
  *os << "[";
  for (size_t i = 0; i < outcomes.size(); ++i) {
    if (i > 0) *os << ", ";
    PrintPacketListSummary(os, outcomes[i]);
  }
  *os << "]";
}

}  // namespace internal
}  // namespace fourward
