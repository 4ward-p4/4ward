// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "simulator/trace_summary.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <ostream>
#include <string>
#include <utility>
#include <vector>

#include "absl/strings/escaping.h"
#include "absl/strings/string_view.h"
#include "p4/v1/p4runtime.pb.h"
#include "simulator/ir.pb.h"
#include "simulator/simulator.pb.h"

namespace fourward {
namespace {

void PrintIndent(std::ostream* os, int indent) {
  for (int i = 0; i < indent; ++i) *os << "  ";
}

void PrintPayloadHexPreview(std::ostream* os, absl::string_view payload) {
  constexpr size_t kMaxBytes = 24;
  const absl::string_view preview = payload.substr(0, kMaxBytes);
  *os << "0x" << absl::BytesToHexString(preview);
  if (payload.size() > kMaxBytes) {
    *os << "...";
  }
}

bool IsPrintableBytes(absl::string_view bytes) {
  return std::all_of(bytes.begin(), bytes.end(), [](unsigned char c) {
    return c >= 0x20 && c <= 0x7e && c != '"' && c != '\\';
  });
}

void PrintBytesValue(std::ostream* os, absl::string_view bytes) {
  if (IsPrintableBytes(bytes)) {
    *os << "\"" << bytes << "\"";
    return;
  }
  PrintPayloadHexPreview(os, bytes);
}

void PrintOutputPacketSummary(std::ostream* os,
                              const fourward::OutputPacket& packet) {
  *os << "output dataplane port " << packet.dataplane_egress_port();
  if (!packet.p4rt_egress_port().empty()) {
    *os << " / P4Runtime port \"" << packet.p4rt_egress_port() << "\"";
  }
  if (!packet.payload().empty()) {
    *os << ": ";
    PrintPayloadHexPreview(os, packet.payload());
  }
}

void PrintSourceLocation(std::ostream* os, const fourward::SourceInfo& source) {
  if (source.file().empty()) return;
  *os << " at " << source.file();
  if (source.line() != 0) {
    *os << ":" << source.line();
    if (source.column() != 0) *os << ":" << source.column();
  }
}

void PrintFieldMatchValue(std::ostream* os, const p4::v1::FieldMatch& match) {
  switch (match.field_match_type_case()) {
    case p4::v1::FieldMatch::kExact:
      PrintBytesValue(os, match.exact().value());
      return;
    case p4::v1::FieldMatch::kOptional:
      PrintBytesValue(os, match.optional().value());
      return;
    case p4::v1::FieldMatch::kLpm:
      PrintBytesValue(os, match.lpm().value());
      *os << "/" << match.lpm().prefix_len();
      return;
    case p4::v1::FieldMatch::kTernary:
      PrintBytesValue(os, match.ternary().value());
      *os << " &&& ";
      PrintBytesValue(os, match.ternary().mask());
      return;
    case p4::v1::FieldMatch::kRange:
      PrintBytesValue(os, match.range().low());
      *os << "..";
      PrintBytesValue(os, match.range().high());
      return;
    case p4::v1::FieldMatch::kOther:
      *os << "<other>";
      return;
    case p4::v1::FieldMatch::FIELD_MATCH_TYPE_NOT_SET:
      *os << "<unset>";
      return;
  }
}

void PrintLookedUpValues(std::ostream* os, const p4::v1::TableEntry& entry) {
  if (entry.match().empty()) return;
  *os << " (looked up " << (entry.match_size() == 1 ? "value" : "values")
      << ": ";
  for (int i = 0; i < entry.match_size(); ++i) {
    if (i > 0) *os << ", ";
    PrintFieldMatchValue(os, entry.match(i));
  }
  *os << ")";
}

void PrintTableEntrySummary(std::ostream* os,
                            const fourward::TableLookupEvent& table) {
  if (table.has_p4rt_matched_entry()) {
    PrintLookedUpValues(os, table.p4rt_matched_entry());
  }
}

bool IsTableMiss(const fourward::TraceEvent& event) {
  return event.event_case() == fourward::TraceEvent::kTableLookup &&
         event.table_lookup().has_hit() && !event.table_lookup().hit();
}

std::vector<std::string> ParserPath(const fourward::TraceTree& trace) {
  std::vector<std::string> states;
  for (const fourward::TraceEvent& event : trace.events()) {
    if (event.event_case() != fourward::TraceEvent::kParserTransition) {
      continue;
    }
    const fourward::ParserTransitionEvent& transition =
        event.parser_transition();
    if (states.empty() && !transition.from_state().empty()) {
      states.push_back(transition.from_state());
    }
    if (!transition.to_state().empty() &&
        (states.empty() || states.back() != transition.to_state())) {
      states.push_back(transition.to_state());
    }
  }
  return states;
}

void PrintParserPath(std::ostream* os, const fourward::TraceTree& trace,
                     int indent) {
  const std::vector<std::string> states = ParserPath(trace);
  if (states.size() < 2) return;
  PrintIndent(os, indent);
  *os << "parser path: ";
  for (size_t i = 0; i < states.size(); ++i) {
    if (i > 0) *os << " -> ";
    *os << states[i];
  }
  *os << "\n";
}

bool OmitFromHumanSummary(const fourward::TraceEvent& event) {
  switch (event.event_case()) {
    case fourward::TraceEvent::kPacketIngress:
    case fourward::TraceEvent::kTableLookup:
    case fourward::TraceEvent::kActionExecution:
    case fourward::TraceEvent::kAssignment:
    case fourward::TraceEvent::kExternCall:
    case fourward::TraceEvent::kLogMessage:
    case fourward::TraceEvent::kClone:
    case fourward::TraceEvent::kCloneSessionLookup:
    case fourward::TraceEvent::kMulticastGroupLookup:
    case fourward::TraceEvent::kMarkToDrop:
      return false;
    case fourward::TraceEvent::kPipelineStage:
    case fourward::TraceEvent::kParserTransition:
    case fourward::TraceEvent::kBranch:
    case fourward::TraceEvent::kDeparserEmit:
    case fourward::TraceEvent::EVENT_NOT_SET:
      return true;
    case fourward::TraceEvent::kAssertion:
      return event.assertion().passed();
  }
}

void PrintTableMissRun(std::ostream* os,
                       const std::vector<const fourward::TraceEvent*>& events,
                       int indent) {
  PrintIndent(os, indent);
  if (events.size() == 1) {
    *os << "table " << events[0]->table_lookup().table_name() << ": miss\n";
    return;
  }
  *os << "tables missed: ";
  for (size_t i = 0; i < events.size(); ++i) {
    if (i > 0) *os << ", ";
    *os << events[i]->table_lookup().table_name();
  }
  *os << "\n";
}

void PrintAssignmentRun(
    std::ostream* os, const std::vector<const fourward::TraceEvent*>& events,
    int indent) {
  PrintIndent(os, indent);
  *os << (events.size() == 1 ? "assignment: " : "assignments: ");
  constexpr size_t kMaxAssignments = 4;
  const size_t limit = std::min(events.size(), kMaxAssignments);
  for (size_t i = 0; i < limit; ++i) {
    if (i > 0) *os << ", ";
    const auto& event = *events[i];
    *os << event.assignment().target() << " := " << event.result_value();
  }
  if (events.size() > limit) {
    *os << ", ... (+" << (events.size() - limit) << " more)";
  }
  *os << "\n";
}

std::vector<std::pair<std::string, absl::string_view>> ActionParamValues(
    const fourward::ActionExecutionEvent& action,
    const fourward::TableLookupEvent* table) {
  std::vector<std::pair<std::string, absl::string_view>> params;
  params.reserve(action.params().size());
  for (const auto& [name, value] : action.params()) {
    params.emplace_back(name, value);
  }

  if (table == nullptr || !table->has_p4rt_matched_entry()) return params;
  if (!table->has_matched_entry()) return params;

  const p4::v1::TableAction& raw_table_action = table->matched_entry().action();
  const p4::v1::TableAction& p4rt_table_action =
      table->p4rt_matched_entry().action();
  if (raw_table_action.type_case() != p4::v1::TableAction::kAction ||
      p4rt_table_action.type_case() != p4::v1::TableAction::kAction) {
    return params;
  }
  const p4::v1::Action& raw_action = raw_table_action.action();
  const p4::v1::Action& p4rt_action = p4rt_table_action.action();
  if (raw_action.params_size() != p4rt_action.params_size()) return params;
  if (p4rt_action.params_size() != static_cast<int>(params.size())) {
    return params;
  }

  for (int i = 0; i < raw_action.params_size(); ++i) {
    for (int j = i + 1; j < raw_action.params_size(); ++j) {
      if (raw_action.params(i).value() == raw_action.params(j).value()) {
        return params;
      }
    }
  }

  for (auto& [name, value] : params) {
    bool found = false;
    for (int i = 0; i < raw_action.params_size(); ++i) {
      if (raw_action.params(i).value() != value) continue;
      value = p4rt_action.params(i).value();
      found = true;
      break;
    }
    if (!found) return params;
  }
  return params;
}

void PrintActionParams(std::ostream* os,
                       const fourward::ActionExecutionEvent& action,
                       const fourward::TableLookupEvent* table = nullptr) {
  std::vector<std::pair<std::string, absl::string_view>> params =
      ActionParamValues(action, table);
  if (params.empty()) return;
  std::sort(params.begin(), params.end(),
            [](const auto& lhs, const auto& rhs) {
              return lhs.first < rhs.first;
            });

  *os << "(";
  for (size_t i = 0; i < params.size(); ++i) {
    if (i > 0) *os << ", ";
    *os << params[i].first << "=";
    PrintBytesValue(os, params[i].second);
  }
  *os << ")";
}

void PrintActionExecution(std::ostream* os,
                          const fourward::ActionExecutionEvent& action,
                          int indent) {
  PrintIndent(os, indent);
  *os << "action " << action.action_name();
  PrintActionParams(os, action);
  *os << "\n";
}

bool IsActionForTableHit(const fourward::TraceEvent& action_event,
                         const fourward::TableLookupEvent& table) {
  return action_event.event_case() == fourward::TraceEvent::kActionExecution &&
         action_event.action_execution().action_name() == table.action_name();
}

void PrintTableLookup(const fourward::TableLookupEvent& table,
                      const fourward::ActionExecutionEvent* action,
                      std::ostream* os, int indent) {
  PrintIndent(os, indent);
  *os << "table " << table.table_name() << ": ";
  if (table.has_hit() && table.hit()) {
    *os << "hit action " << table.action_name();
    if (action != nullptr) PrintActionParams(os, *action, &table);
    PrintTableEntrySummary(os, table);
  } else {
    *os << "miss";
  }
  *os << "\n";
}

void PrintEvent(const fourward::TraceEvent& event, std::ostream* os,
                int indent);

std::string ReplicationCauseName(const fourward::TraceTree& trace,
                                 const fourward::Replication& replication) {
  if (!replication.has_cause()) return "replication";
  for (const auto& event : trace.events()) {
    if (event.id() != replication.cause()) continue;
    switch (event.event_case()) {
      case fourward::TraceEvent::kCloneSessionLookup:
      case fourward::TraceEvent::kClone:
        return "clone";
      case fourward::TraceEvent::kMulticastGroupLookup:
        return "multicast";
      case fourward::TraceEvent::kPacketIngress:
      case fourward::TraceEvent::kPipelineStage:
      case fourward::TraceEvent::kParserTransition:
      case fourward::TraceEvent::kTableLookup:
      case fourward::TraceEvent::kActionExecution:
      case fourward::TraceEvent::kBranch:
      case fourward::TraceEvent::kAssignment:
      case fourward::TraceEvent::kExternCall:
      case fourward::TraceEvent::kLogMessage:
      case fourward::TraceEvent::kAssertion:
      case fourward::TraceEvent::kMarkToDrop:
      case fourward::TraceEvent::kDeparserEmit:
      case fourward::TraceEvent::EVENT_NOT_SET:
        return "replication";
    }
  }
  return "replication";
}

void PrintHumanTraceSummaryNode(const fourward::TraceTree& trace,
                                std::ostream* os, int indent) {
  bool parser_path_printed = false;
  for (int i = 0; i < trace.events_size();) {
    if (trace.events(i).event_case() ==
        fourward::TraceEvent::kParserTransition) {
      if (!parser_path_printed) {
        PrintParserPath(os, trace, indent);
        parser_path_printed = true;
      }
      ++i;
      continue;
    }
    if (OmitFromHumanSummary(trace.events(i))) {
      ++i;
      continue;
    }
    if (trace.events(i).event_case() == fourward::TraceEvent::kAssignment) {
      std::vector<const fourward::TraceEvent*> assignments;
      while (i < trace.events_size() &&
             trace.events(i).event_case() == fourward::TraceEvent::kAssignment) {
        assignments.push_back(&trace.events(i));
        ++i;
      }
      PrintAssignmentRun(os, assignments, indent);
      continue;
    }
    if (IsTableMiss(trace.events(i))) {
      std::vector<const fourward::TraceEvent*> misses;
      while (i < trace.events_size() && IsTableMiss(trace.events(i))) {
        misses.push_back(&trace.events(i));
        ++i;
      }
      PrintTableMissRun(os, misses, indent);
      continue;
    }
    if (trace.events(i).event_case() == fourward::TraceEvent::kTableLookup) {
      const fourward::TableLookupEvent& table = trace.events(i).table_lookup();
      const bool has_following_action =
          i + 1 < trace.events_size() &&
          IsActionForTableHit(trace.events(i + 1), table);
      PrintTableLookup(table,
                       has_following_action
                           ? &trace.events(i + 1).action_execution()
                           : nullptr,
                       os, indent);
      i += has_following_action ? 2 : 1;
      continue;
    }
    PrintEvent(trace.events(i), os, indent);
    ++i;
  }

  switch (trace.outcome_case()) {
    case fourward::TraceTree::kOutput:
      PrintIndent(os, indent);
      PrintOutputPacketSummary(os, trace.output());
      *os << "\n";
      return;
    case fourward::TraceTree::kDrop:
      PrintIndent(os, indent);
      *os << "drop\n";
      return;
    case fourward::TraceTree::kReplication: {
      const auto& replication = trace.replication();
      PrintIndent(os, indent);
      *os << ReplicationCauseName(trace, replication) << " replication with "
          << replication.branches_size() << " branch"
          << (replication.branches_size() == 1 ? "" : "es") << "\n";
      for (int i = 0; i < replication.branches_size(); ++i) {
        PrintIndent(os, indent + 1);
        *os << "branch " << (i + 1) << ":\n";
        PrintHumanTraceSummaryNode(replication.branches(i), os, indent + 2);
      }
      return;
    }
    case fourward::TraceTree::kChoice: {
      const auto& choice = trace.choice();
      PrintIndent(os, indent);
      *os << "choice with " << choice.branches_size() << " branches\n";
      for (int i = 0; i < choice.branches_size(); ++i) {
        PrintIndent(os, indent + 1);
        *os << "branch " << (i + 1) << ":\n";
        PrintHumanTraceSummaryNode(choice.branches(i), os, indent + 2);
      }
      return;
    }
    case fourward::TraceTree::kContinuation:
      PrintIndent(os, indent);
      *os << "continuation\n";
      PrintHumanTraceSummaryNode(trace.continuation().next(), os, indent + 1);
      return;
    case fourward::TraceTree::OUTCOME_NOT_SET:
      return;
  }
}

void PrintEvent(const fourward::TraceEvent& event, std::ostream* os,
                int indent) {
  switch (event.event_case()) {
    case fourward::TraceEvent::kPacketIngress:
      PrintIndent(os, indent);
      *os << "ingress dataplane port "
          << event.packet_ingress().dataplane_ingress_port();
      if (!event.packet_ingress().p4rt_ingress_port().empty()) {
        *os << " / P4Runtime port \""
            << event.packet_ingress().p4rt_ingress_port() << "\"";
      }
      *os << "\n";
      return;
    case fourward::TraceEvent::kTableLookup:
      PrintTableLookup(event.table_lookup(), nullptr, os, indent);
      return;
    case fourward::TraceEvent::kActionExecution:
      PrintActionExecution(os, event.action_execution(), indent);
      return;
    case fourward::TraceEvent::kCloneSessionLookup:
      PrintIndent(os, indent);
      *os << "clone session " << event.clone_session_lookup().session_id();
      if (event.clone_session_lookup().session_found()) {
        *os << ": found -> dataplane port "
            << event.clone_session_lookup().dataplane_egress_port()
            << " / P4Runtime port \""
            << event.clone_session_lookup().p4rt_egress_port() << "\"";
        if (event.clone_session_lookup().egress_rid() != 0) {
          *os << ", rid " << event.clone_session_lookup().egress_rid();
        }
        if (event.clone_session_lookup().replica_count() != 0) {
          *os << ", replicas " << event.clone_session_lookup().replica_count();
        }
      } else {
        *os << ": not found";
      }
      *os << "\n";
      return;
    case fourward::TraceEvent::kMulticastGroupLookup:
      PrintIndent(os, indent);
      *os << "multicast group "
          << event.multicast_group_lookup().multicast_group_id();
      if (event.multicast_group_lookup().group_found()) {
        *os << ": found, replicas "
            << event.multicast_group_lookup().replica_count();
      } else {
        *os << ": not found";
      }
      *os << "\n";
      return;
    case fourward::TraceEvent::kClone:
      PrintIndent(os, indent);
      *os << "clone session " << event.clone().session_id() << "\n";
      return;
    case fourward::TraceEvent::kMarkToDrop:
      PrintIndent(os, indent);
      *os << "mark_to_drop";
      PrintSourceLocation(os, event.source_info());
      *os << "\n";
      return;
    case fourward::TraceEvent::kPipelineStage:
    case fourward::TraceEvent::kParserTransition:
    case fourward::TraceEvent::kBranch:
      // Omitted from human summaries; keep the cases explicit so new trace
      // event kinds still stand out in this exhaustive switch.
      return;
    case fourward::TraceEvent::kExternCall:
      PrintIndent(os, indent);
      *os << "extern " << event.extern_call().extern_instance_name() << "."
          << event.extern_call().method() << "()\n";
      return;
    case fourward::TraceEvent::kLogMessage:
      PrintIndent(os, indent);
      *os << "log: " << event.log_message().message() << "\n";
      return;
    case fourward::TraceEvent::kAssertion:
      PrintIndent(os, indent);
      *os << "assertion "
          << (event.assertion().passed() ? "passed" : "failed");
      PrintSourceLocation(os, event.source_info());
      *os << "\n";
      return;
    case fourward::TraceEvent::kDeparserEmit:
      // Omitted from human summaries; keep the case explicit so new trace
      // event kinds still stand out in this exhaustive switch.
      return;
    case fourward::TraceEvent::kAssignment:
      PrintIndent(os, indent);
      *os << "assignment: " << event.assignment().target() << " := "
          << event.result_value() << "\n";
      return;
    case fourward::TraceEvent::EVENT_NOT_SET:
      PrintIndent(os, indent);
      *os << "unset event\n";
      return;
  }
}

}  // namespace

void PrintHumanTraceSummary(std::ostream* os, const fourward::TraceTree& trace) {
  *os << "trace:\n";
  PrintHumanTraceSummaryNode(trace, os, 0);
}

}  // namespace fourward
