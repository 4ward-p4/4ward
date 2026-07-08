// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "simulator/trace_summary.h"

#include <sstream>
#include <string>

#include "bazel/golden_test.h"
#include "gtest/gtest.h"
#include "p4/v1/p4runtime.pb.h"
#include "simulator/ir.pb.h"
#include "simulator/simulator.pb.h"

#ifndef HUMAN_TRACE_SUMMARY_GOLDEN_RLOCATION
#error "HUMAN_TRACE_SUMMARY_GOLDEN_RLOCATION must be set by BUILD.bazel"
#endif
#ifndef MULTICAST_TRACE_SUMMARY_GOLDEN_RLOCATION
#error "MULTICAST_TRACE_SUMMARY_GOLDEN_RLOCATION must be set by BUILD.bazel"
#endif

namespace fourward {
namespace {

std::string RenderHumanReadableTrace(const TraceTree& trace) {
  std::ostringstream out;
  PrintHumanTraceSummary(&out, trace);
  return out.str();
}

fourward::bazel::GoldenFile GoldenFileFor(
    const std::string& golden_file_name) {
  if (golden_file_name == "human_trace_summary.golden.txt") {
    return {golden_file_name, HUMAN_TRACE_SUMMARY_GOLDEN_RLOCATION};
  }
  if (golden_file_name == "multicast_trace_summary.golden.txt") {
    return {golden_file_name, MULTICAST_TRACE_SUMMARY_GOLDEN_RLOCATION};
  }
  ADD_FAILURE() << "unknown golden: " << golden_file_name;
  return {golden_file_name, ""};
}

void ExpectMatchesGolden(const std::string& golden_file_name,
                         const std::string& actual) {
  fourward::bazel::ExpectMatchesGolden(
      GoldenFileFor(golden_file_name), "simulator/golden_trace_summaries",
      "//simulator:trace_summary_test", actual);
}

std::string Bytes(int size) {
  std::string bytes;
  for (int i = 0; i < size; ++i) {
    bytes.push_back(static_cast<char>(i));
  }
  return bytes;
}

TEST(TraceSummaryTest, PrintsHumanTraceSummary) {
  TraceTree trace;
  auto* ingress = trace.add_events();
  ingress->mutable_packet_ingress()->set_dataplane_ingress_port(2);
  ingress->mutable_packet_ingress()->set_p4rt_ingress_port("4");

  auto* stage = trace.add_events();
  stage->mutable_pipeline_stage()->set_stage_name("ingress");
  stage->mutable_pipeline_stage()->set_stage_kind(CONTROL);
  stage->mutable_pipeline_stage()->set_direction(PipelineStageEvent::ENTER);

  auto* branch = trace.add_events();
  branch->mutable_branch()->set_control_name("ingress");
  branch->mutable_branch()->set_taken(false);

  auto* parser_start = trace.add_events()->mutable_parser_transition();
  parser_start->set_from_state("start");
  parser_start->set_to_state("parse_ethernet");
  auto* parser_ipv4 = trace.add_events()->mutable_parser_transition();
  parser_ipv4->set_from_state("parse_ethernet");
  parser_ipv4->set_to_state("parse_ipv4");
  auto* parser_accept = trace.add_events()->mutable_parser_transition();
  parser_accept->set_from_state("parse_ipv4");
  parser_accept->set_to_state("accept");

  for (int i = 0; i < 5; ++i) {
    auto* assignment = trace.add_events();
    assignment->mutable_assignment()->set_target(std::string(1, 'a' + i));
    assignment->set_result_value(std::to_string(i + 1));
  }

  auto* table = trace.add_events();
  table->mutable_table_lookup()->set_table_name("ipv4");
  table->mutable_table_lookup()->set_hit(true);
  table->mutable_table_lookup()->set_action_name("punt");
  auto* raw_action = table->mutable_table_lookup()
                         ->mutable_matched_entry()
                         ->mutable_action()
                         ->mutable_action();
  raw_action->add_params()->set_value("raw_port");
  raw_action->add_params()->set_value("raw_queue");
  auto* match =
      table->mutable_table_lookup()->mutable_p4rt_matched_entry()->add_match();
  match->mutable_exact()->set_value("vrf");
  auto* p4rt_action =
      table->mutable_table_lookup()
          ->mutable_p4rt_matched_entry()
          ->mutable_action()
          ->mutable_action();
  p4rt_action->add_params()->set_value("6");
  p4rt_action->add_params()->set_value("CPU");

  auto* action = trace.add_events();
  action->mutable_action_execution()->set_action_name("punt");
  (*action->mutable_action_execution()->mutable_params())["port"] = "raw_port";
  (*action->mutable_action_execution()->mutable_params())["queue"] =
      "raw_queue";

  auto* ambiguous_table = trace.add_events();
  ambiguous_table->mutable_table_lookup()->set_table_name("ambiguous");
  ambiguous_table->mutable_table_lookup()->set_hit(true);
  ambiguous_table->mutable_table_lookup()->set_action_name("duplicate_params");
  auto* ambiguous_raw_action = ambiguous_table->mutable_table_lookup()
                                   ->mutable_matched_entry()
                                   ->mutable_action()
                                   ->mutable_action();
  ambiguous_raw_action->add_params()->set_value("raw_duplicate");
  ambiguous_raw_action->add_params()->set_value("raw_duplicate");
  auto* ambiguous_p4rt_action =
      ambiguous_table->mutable_table_lookup()
          ->mutable_p4rt_matched_entry()
          ->mutable_action()
          ->mutable_action();
  ambiguous_p4rt_action->add_params()->set_value("first");
  ambiguous_p4rt_action->add_params()->set_value("second");

  auto* ambiguous_action = trace.add_events();
  ambiguous_action->mutable_action_execution()->set_action_name(
      "duplicate_params");
  (*ambiguous_action->mutable_action_execution()->mutable_params())["a"] =
      "raw_duplicate";
  (*ambiguous_action->mutable_action_execution()->mutable_params())["b"] =
      "raw_duplicate";

  trace.mutable_output()->set_dataplane_egress_port(6);
  trace.mutable_output()->set_p4rt_egress_port("6");
  trace.mutable_output()->set_payload(Bytes(32));

  ExpectMatchesGolden("human_trace_summary.golden.txt",
                      RenderHumanReadableTrace(trace));
}

TEST(TraceSummaryTest, PrintsMulticastLookupBeforeReplicationBranches) {
  TraceTree trace;
  auto* lookup = trace.add_events();
  lookup->set_id(7);
  lookup->mutable_multicast_group_lookup()->set_multicast_group_id(42);
  lookup->mutable_multicast_group_lookup()->set_group_found(true);
  lookup->mutable_multicast_group_lookup()->set_replica_count(2);

  auto* replication = trace.mutable_replication();
  replication->set_cause_id(7);
  replication->add_branches()->mutable_output()->set_dataplane_egress_port(1);
  replication->add_branches()->mutable_output()->set_dataplane_egress_port(2);

  ExpectMatchesGolden("multicast_trace_summary.golden.txt",
                      RenderHumanReadableTrace(trace));
}

}  // namespace
}  // namespace fourward
