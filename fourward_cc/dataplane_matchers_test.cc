// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/dataplane_matchers.h"

#include <cctype>
#include <sstream>
#include <string>
#include <string_view>

#include "absl/strings/str_cat.h"
#include "bazel/golden_test.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "grpc/dataplane.pb.h"

#ifndef COMPACT_TRACE_GOLDEN_RLOCATION
#error "COMPACT_TRACE_GOLDEN_RLOCATION must be set by BUILD.bazel"
#endif
#ifndef ON_PORTS_CPU_NAME_MISMATCH_GOLDEN_RLOCATION
#error "ON_PORTS_CPU_NAME_MISMATCH_GOLDEN_RLOCATION must be set by BUILD.bazel"
#endif
#ifndef PAYLOAD_MISMATCH_GOLDEN_RLOCATION
#error "PAYLOAD_MISMATCH_GOLDEN_RLOCATION must be set by BUILD.bazel"
#endif
#ifndef WRONG_PORT_GOLDEN_RLOCATION
#error "WRONG_PORT_GOLDEN_RLOCATION must be set by BUILD.bazel"
#endif

namespace fourward {
namespace {

using ::testing::AllOf;
using ::testing::Contains;
using ::testing::IsEmpty;
using ::testing::Not;
using ::testing::SizeIs;

// --- Test helpers ---

fourward::InjectPacketResponse Forward(uint32_t egress) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(egress);
  return resp;
}

fourward::InjectPacketResponse Drop() {
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes();
  return resp;
}

fourward::InjectPacketResponse Multicast(uint32_t p1, uint32_t p2) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(p1);
  ps->add_packets()->set_dataplane_egress_port(p2);
  return resp;
}

fourward::InjectPacketResponse NonDeterministic(uint32_t p1,
                                                           uint32_t p2) {
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(p1);
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(p2);
  return resp;
}

fourward::ProcessPacketResult MakeResult(uint32_t ingress,
                                                    uint32_t egress) {
  fourward::ProcessPacketResult result;
  result.mutable_input_packet()->set_dataplane_ingress_port(ingress);
  auto* ps = result.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(egress);
  return result;
}

template <typename M, typename T>
std::string ExplainMatch(M polymorphic_matcher, const T& value) {
  ::testing::Matcher<const T&> matcher = polymorphic_matcher;
  ::testing::StringMatchResultListener listener;
  matcher.MatchAndExplain(value, &listener);
  return listener.str();
}

template <typename M, typename T>
std::string FailureReport(M polymorphic_matcher, const T& value) {
  ::testing::Matcher<const T&> matcher = polymorphic_matcher;
  std::ostringstream description;
  matcher.DescribeTo(&description);
  std::string explanation = ExplainMatch(polymorphic_matcher, value);
  while (!explanation.empty() && explanation.front() == '\n') {
    explanation.erase(explanation.begin());
  }
  return absl::StrCat("Expected: ", description.str(), "\n",
                      "Actual: ", ::testing::PrintToString(value), "\n",
                      explanation, "\n");
}

fourward::bazel::GoldenFile GoldenFileFor(
    const std::string& golden_file_name) {
  if (golden_file_name == "compact_trace.golden.txt") {
    return {golden_file_name, COMPACT_TRACE_GOLDEN_RLOCATION};
  }
  if (golden_file_name == "on_ports_cpu_name_mismatch.golden.txt") {
    return {golden_file_name, ON_PORTS_CPU_NAME_MISMATCH_GOLDEN_RLOCATION};
  }
  if (golden_file_name == "payload_mismatch.golden.txt") {
    return {golden_file_name, PAYLOAD_MISMATCH_GOLDEN_RLOCATION};
  }
  if (golden_file_name == "wrong_port.golden.txt") {
    return {golden_file_name, WRONG_PORT_GOLDEN_RLOCATION};
  }
  ADD_FAILURE() << "unknown golden: " << golden_file_name;
  return {golden_file_name, ""};
}

std::string NormalizeGoldenOutput(std::string text) {
  constexpr std::string_view kDebugMarker = "goo.gle/debug";
  size_t pos = 0;
  while ((pos = text.find(kDebugMarker, pos)) != std::string::npos) {
    size_t end = pos + kDebugMarker.size();
    while (end < text.size() &&
           std::isalnum(static_cast<unsigned char>(text[end]))) {
      ++end;
    }
    while (end < text.size() && text[end] == ' ') {
      ++end;
    }
    const std::string replacement =
        end < text.size() && text[end] != '\n' ? "goo.gle/debug "
                                               : "goo.gle/debug";
    text.replace(pos, end - pos, replacement);
    pos += replacement.size();
  }
  return text;
}

void ExpectMatchesGolden(const std::string& golden_file_name,
                         const std::string& actual) {
  const std::string normalized_actual = NormalizeGoldenOutput(actual);
  fourward::bazel::ExpectMatchesGolden(
      GoldenFileFor(golden_file_name), "fourward_cc/golden_matcher_failures",
      "//fourward_cc:dataplane_matchers_test", normalized_actual);
}

class MatcherTraceFlagScope {
 public:
  explicit MatcherTraceFlagScope(internal::MatcherTraceMode mode)
      : previous_(internal::GetMatcherTraceMode()) {
    internal::SetMatcherTraceModeForTest(mode);
  }
  ~MatcherTraceFlagScope() {
    internal::SetMatcherTraceModeForTest(previous_);
  }

 private:
  internal::MatcherTraceMode previous_;
};

// --- ForwardsTo ---

TEST(ForwardsToTest, Matches) {
  EXPECT_THAT(Forward(1), ForwardsTo(1));
  EXPECT_THAT(Forward(42), ForwardsTo(DataplanePort{42}));
}
TEST(ForwardsToTest, WrongPort) {
  EXPECT_THAT(Forward(1), Not(ForwardsTo(2)));
}
TEST(ForwardsToTest, Drop) { EXPECT_THAT(Drop(), Not(ForwardsTo(1))); }
TEST(ForwardsToTest, SinglePortDoesNotMatchMulticast) {
  EXPECT_THAT(Multicast(1, 2), Not(ForwardsTo(1)));
}
TEST(ForwardsToTest, NonDeterministic) {
  EXPECT_THAT(NonDeterministic(1, 2), Not(ForwardsTo(1)));
}
TEST(ForwardsToTest, WorksOnProcessPacketResult) {
  EXPECT_THAT(MakeResult(0, 3), ForwardsTo(3));
}
TEST(ForwardsToTest, Multicast) {
  EXPECT_THAT(Multicast(1, 2), ForwardsTo(1, 2));
  EXPECT_THAT(Multicast(1, 2), ForwardsTo(2, 1));  // unordered
}
TEST(ForwardsToTest, MulticastWrongPorts) {
  EXPECT_THAT(Multicast(1, 2), Not(ForwardsTo(1, 3)));
}

// --- Forwards ---

TEST(ForwardsTest, Matches) { EXPECT_THAT(Forward(1), Forwards()); }
TEST(ForwardsTest, Multicast) { EXPECT_THAT(Multicast(1, 2), Forwards()); }
TEST(ForwardsTest, Drop) { EXPECT_THAT(Drop(), Not(Forwards())); }
TEST(ForwardsTest, NonDeterministic) {
  EXPECT_THAT(NonDeterministic(1, 2), Not(Forwards()));
}
TEST(ForwardsTest, WorksOnProcessPacketResult) {
  EXPECT_THAT(MakeResult(0, 3), Forwards());
}

// --- Drops ---

TEST(DropsTest, Matches) { EXPECT_THAT(Drop(), Drops()); }
TEST(DropsTest, Forward) { EXPECT_THAT(Forward(1), Not(Drops())); }

// --- OutcomeIs ---

TEST(OutcomeIsTest, SinglePacket) {
  EXPECT_THAT(Forward(1), OutcomeIs(OnPort(1)));
}
TEST(OutcomeIsTest, MultiplePackets) {
  EXPECT_THAT(Multicast(1, 2), OutcomeIs(OnPort(1), OnPort(2)));
}
TEST(OutcomeIsTest, MultiplePacketsUnordered) {
  EXPECT_THAT(Multicast(1, 2), OutcomeIs(OnPort(2), OnPort(1)));
}
TEST(OutcomeIsTest, ZeroArgs) { EXPECT_THAT(Drop(), OutcomeIs()); }
TEST(OutcomeIsTest, PacketsContainerMatcher) {
  EXPECT_THAT(Multicast(1, 2), OutcomeIs(Packets(SizeIs(2))));
}
TEST(OutcomeIsTest, PacketsOnPorts) {
  EXPECT_THAT(Multicast(1, 2),
              OutcomeIs(OnPorts({{DataplanePort{1}, SizeIs(1)}, {DataplanePort{2}, SizeIs(1)}})));
}
TEST(OutcomeIsTest, RejectsNonDeterministic) {
  EXPECT_THAT(NonDeterministic(1, 2), Not(OutcomeIs(OnPort(1))));
}

// --- OutcomesAre ---

TEST(OutcomesAreTest, SingleOutcome) {
  EXPECT_THAT(Forward(1), OutcomesAre(OnPort(1)));
}
TEST(OutcomesAreTest, MultipleOutcomes) {
  EXPECT_THAT(NonDeterministic(1, 2), OutcomesAre(OnPort(1), OnPort(2)));
}
TEST(OutcomesAreTest, Unordered) {
  EXPECT_THAT(NonDeterministic(1, 2), OutcomesAre(OnPort(2), OnPort(1)));
}
TEST(OutcomesAreTest, WithOutcome) {
  EXPECT_THAT(Multicast(1, 2),
              OutcomesAre(Outcome(OnPort(1), OnPort(2))));
}

// --- EachOutcome / AnyOutcome ---

TEST(EachOutcomeTest, AllMatch) {
  EXPECT_THAT(NonDeterministic(1, 1), EachOutcome(OnPort(1)));
}
TEST(EachOutcomeTest, NotAllMatch) {
  EXPECT_THAT(NonDeterministic(1, 2), Not(EachOutcome(OnPort(1))));
}
TEST(EachOutcomeTest, PacketsContainerMatcher) {
  EXPECT_THAT(NonDeterministic(1, 2), EachOutcome(Packets(SizeIs(1))));
}

TEST(AnyOutcomeTest, SomeMatch) {
  EXPECT_THAT(NonDeterministic(1, 2), AnyOutcome(OnPort(1)));
}
TEST(AnyOutcomeTest, NoneMatch) {
  EXPECT_THAT(NonDeterministic(1, 2), Not(AnyOutcome(OnPort(3))));
}

// --- HasIngress ---

TEST(HasIngressTest, Matches) {
  EXPECT_THAT(MakeResult(0, 1), HasIngress(0));
  EXPECT_THAT(MakeResult(7, 1), HasIngress(DataplanePort{7}));
}
TEST(HasIngressTest, DoesNotMatch) {
  EXPECT_THAT(MakeResult(0, 1), Not(HasIngress(5)));
}

// --- HasPayload ---

TEST(HasPayloadTest, ExactMatch) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* pkt = ps->add_packets();
  pkt->set_dataplane_egress_port(1);
  pkt->set_payload("hello");
  EXPECT_THAT(resp, OutcomeIs(AllOf(OnPort(1), HasPayload("hello"))));
}

TEST(HasPayloadTest, MatcherComposition) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* pkt = ps->add_packets();
  pkt->set_dataplane_egress_port(1);
  pkt->set_payload("hello world");
  EXPECT_THAT(resp,
              OutcomeIs(HasPayload(::testing::StartsWith("hello"))));
}

// --- OnPorts ---

TEST(OnPortsTest, GroupsByPort) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(1);
  ps->add_packets()->set_dataplane_egress_port(1);
  ps->add_packets()->set_dataplane_egress_port(2);

  EXPECT_THAT(resp,
              OutcomeIs(OnPorts({{DataplanePort{1}, SizeIs(2)}, {DataplanePort{2}, SizeIs(1)}})));
}

TEST(OnPortsTest, MatchesDoesNotCrashOnMismatch) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(1);

  ::testing::Matcher<const fourward::InjectPacketResponse&> matcher =
      OutcomeIs(OnPorts({{DataplanePort{2}, SizeIs(1)}}));

  EXPECT_FALSE(matcher.Matches(resp));
}

TEST(OnPortsTest, ExplainsMismatchWhenListenerIsInterested) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(1);

  EXPECT_THAT(ExplainMatch(OutcomeIs(OnPorts({{DataplanePort{2}, SizeIs(1)}})),
                           resp),
              ::testing::HasSubstr("on dataplane port 2"));
}

TEST(OnPortsTest, P4RuntimePortKeys) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_p4rt_egress_port("Eth0");
  ps->add_packets()->set_p4rt_egress_port("Eth1");

  EXPECT_THAT(resp, OutcomeIs(OnPorts({
                        {P4RuntimePort{"Eth0"}, SizeIs(1)},
                        {P4RuntimePort{"Eth1"}, SizeIs(1)},
                    })));
}

// A single OnPorts call may mix DataplanePort and P4RuntimePort expectations;
// each is evaluated against its own port type. Here the two CPU packets carry
// both a dataplane and a P4Runtime egress port, so they are matched either way.
TEST(OnPortsTest, MixedPortTypes) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* forwarded = ps->add_packets();
  forwarded->set_dataplane_egress_port(1);
  forwarded->set_p4rt_egress_port("6");
  for (int i = 0; i < 2; ++i) {
    auto* punted = ps->add_packets();
    punted->set_dataplane_egress_port(510);
    punted->set_p4rt_egress_port("CPU");
  }

  EXPECT_THAT(resp, OutcomeIs(OnPorts({
                        {P4RuntimePort{"6"}, SizeIs(1)},
                        {DataplanePort{510}, SizeIs(2)},
                    })));
  // The order of mixed expectations must not matter — the original bug keyed
  // grouping off the first entry's port type, so reversing the order is the
  // direct regression guard.
  EXPECT_THAT(resp, OutcomeIs(OnPorts({
                        {DataplanePort{510}, SizeIs(2)},
                        {P4RuntimePort{"6"}, SizeIs(1)},
                    })));
}

// OnPorts is exhaustive: a packet on a port no expectation lists fails the
// match rather than being silently ignored.
TEST(OnPortsTest, RejectsUnlistedPort) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(1);
  ps->add_packets()->set_dataplane_egress_port(2);

  EXPECT_THAT(resp, Not(OutcomeIs(OnPorts({{DataplanePort{1}, SizeIs(1)}}))));
  EXPECT_THAT(
      ExplainMatch(OutcomeIs(OnPorts({{DataplanePort{1}, SizeIs(1)}})), resp),
      ::testing::HasSubstr("unexpected packet egressing on dataplane port 2"));
}

TEST(OutcomesAreTest, MixedBareAndOutcome) {
  fourward::InjectPacketResponse resp;
  auto* multicast = resp.add_possible_outcomes();
  multicast->add_packets()->set_dataplane_egress_port(1);
  multicast->add_packets()->set_dataplane_egress_port(2);
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(3);

  EXPECT_THAT(resp, OutcomesAre(Outcome(OnPort(1), OnPort(2)), OnPort(3)));
}

// --- OutcomesAre + Packets ---

TEST(OutcomesAreTest, WithPackets) {
  EXPECT_THAT(Forward(1), OutcomesAre(Packets(SizeIs(1))));
}

// --- OutcomesAre + Outcome (empty = drop) ---

TEST(OutcomesAreTest, OutcomeWithDrop) {
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  resp.add_possible_outcomes();  // drop
  EXPECT_THAT(resp, OutcomesAre(OnPort(1), Outcome()));
}

// --- AnyOutcome + Packets ---

TEST(AnyOutcomeTest, WithPackets) {
  EXPECT_THAT(NonDeterministic(1, 2),
              AnyOutcome(Packets(Contains(OnPort(1)))));
}

// --- String port overloads ---

TEST(ForwardsToTest, StringPort) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_p4rt_egress_port("Eth0");
  EXPECT_THAT(resp, ForwardsTo(std::string("Eth0")));
}

TEST(OnPortTest, StringPort) {
  fourward::OutputPacket pkt;
  pkt.set_p4rt_egress_port("Eth0");
  EXPECT_THAT(pkt, OnPort(std::string("Eth0")));
}

TEST(HasIngressTest, StringPort) {
  fourward::ProcessPacketResult result;
  result.mutable_input_packet()->set_p4rt_ingress_port("Eth0");
  result.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  EXPECT_THAT(result, HasIngress(std::string("Eth0")));
}

// --- string_view overloads ---

TEST(OnPortTest, StringView) {
  fourward::OutputPacket pkt;
  pkt.set_p4rt_egress_port("Eth0");
  std::string_view sv = "Eth0";
  EXPECT_THAT(pkt, OnPort(sv));
}

TEST(HasIngressTest, StringView) {
  fourward::ProcessPacketResult result;
  result.mutable_input_packet()->set_p4rt_ingress_port("Eth0");
  result.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  std::string_view sv = "Eth0";
  EXPECT_THAT(result, HasIngress(sv));
}

TEST(ForwardsToTest, StringView) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_p4rt_egress_port("Eth0");
  std::string_view sv = "Eth0";
  EXPECT_THAT(resp, ForwardsTo(sv));
}

// --- OnPort with payload ---

TEST(OnPortTest, WithPayload) {
  fourward::OutputPacket pkt;
  pkt.set_dataplane_egress_port(1);
  pkt.set_payload("hello");
  EXPECT_THAT(pkt, OnPort(1, "hello"));
}

TEST(OnPortTest, WithPayloadMatcher) {
  fourward::OutputPacket pkt;
  pkt.set_dataplane_egress_port(1);
  pkt.set_payload("hello world");
  EXPECT_THAT(pkt, OnPort(1, ::testing::StartsWith("hello")));
}

TEST(OnPortTest, WithPayloadInOutcomeIs) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* pkt = ps->add_packets();
  pkt->set_dataplane_egress_port(1);
  pkt->set_payload("data");
  EXPECT_THAT(resp, OutcomeIs(OnPort(1, "data")));
}

// --- Error message quality ---

TEST(ErrorMessageTest, ForwardsToDescribes) {
  ::testing::Matcher<const fourward::InjectPacketResponse&> m =
      ForwardsTo(1);
  std::ostringstream os;
  m.DescribeTo(&os);
  EXPECT_EQ(os.str(), "forward to dataplane port 1");
}

TEST(ErrorMessageTest, ForwardsDescribes) {
  ::testing::Matcher<const fourward::InjectPacketResponse&> m =
      Forwards();
  std::ostringstream os;
  m.DescribeTo(&os);
  EXPECT_THAT(os.str(), ::testing::HasSubstr("forward"));
}

TEST(ErrorMessageTest, DropsDescribes) {
  ::testing::Matcher<const fourward::InjectPacketResponse&> m =
      Drops();
  std::ostringstream os;
  m.DescribeTo(&os);
  EXPECT_THAT(os.str(), ::testing::HasSubstr("drop"));
}

TEST(ErrorMessageTest, HasIngressDescribes) {
  ::testing::Matcher<const fourward::ProcessPacketResult&> m =
      HasIngress(5);
  std::ostringstream os;
  m.DescribeTo(&os);
  EXPECT_THAT(os.str(), ::testing::HasSubstr("5"));
}

TEST(ErrorMessageTest, FailureIncludesTrace) {
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  resp.mutable_trace()->add_events()->mutable_table_lookup()->set_table_name(
      "my_table");
  const std::string explanation = ExplainMatch(Drops(), resp);
  if (internal::GetMatcherTraceMode() == internal::MatcherTraceMode::kFull) {
    EXPECT_THAT(explanation,
                AllOf(::testing::HasSubstr("trace proto:"),
                      ::testing::HasSubstr("table_name: \"my_table\""),
                      Not(::testing::HasSubstr("trace:\n"))));
  } else {
    EXPECT_THAT(explanation,
                AllOf(::testing::HasSubstr("trace:\n"),
                      ::testing::HasSubstr("table my_table: miss")));
  }
}

TEST(ErrorMessageTest, FailureIncludesTraceOnProcessPacketResult) {
  MatcherTraceFlagScope flag(internal::MatcherTraceMode::kSummary);
  fourward::ProcessPacketResult result;
  result.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  result.mutable_trace()->add_events()->mutable_table_lookup()->set_table_name(
      "ingress_table");
  EXPECT_THAT(ExplainMatch(Drops(), result),
              AllOf(::testing::HasSubstr("trace:\n"),
                    ::testing::HasSubstr("table ingress_table: miss")));
}

TEST(ErrorMessageTest, CommandLineTraceFlagCanRequestFullTrace) {
  if (internal::GetMatcherTraceMode() != internal::MatcherTraceMode::kFull) {
    GTEST_SKIP() << "run with --fourward_matcher_trace=full";
  }
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  resp.mutable_trace()->add_events()->mutable_table_lookup()->set_table_name(
      "my_table");

  EXPECT_THAT(ExplainMatch(Drops(), resp),
              AllOf(::testing::HasSubstr("trace proto:"),
                    ::testing::HasSubstr("table_name: \"my_table\""),
                    Not(::testing::HasSubstr("trace:\n"))));
}

TEST(ErrorMessageTest, TraceFlagNoneOmitsTraceWithRerunHint) {
  MatcherTraceFlagScope flag(internal::MatcherTraceMode::kNone);
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  resp.mutable_trace()->add_events()->mutable_table_lookup()->set_table_name(
      "my_table");

  EXPECT_THAT(ExplainMatch(Drops(), resp),
              AllOf(::testing::HasSubstr("trace omitted"),
                    ::testing::HasSubstr("--fourward_matcher_trace=full"),
                    Not(::testing::HasSubstr("table my_table"))));
}

TEST(ErrorMessageTest, TraceFlagFullIncludesRawTrace) {
  MatcherTraceFlagScope flag(internal::MatcherTraceMode::kFull);
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  resp.mutable_trace()->add_events()->mutable_table_lookup()->set_table_name(
      "my_table");

  EXPECT_THAT(ExplainMatch(Drops(), resp),
              AllOf(::testing::HasSubstr("trace proto:"),
                    ::testing::HasSubstr("table_name: \"my_table\"")));
}

TEST(ErrorMessageTest, TraceOutputPayloadHexPreviewIsBounded) {
  MatcherTraceFlagScope flag(internal::MatcherTraceMode::kSummary);
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  auto* output = resp.mutable_trace()->mutable_output();
  output->set_dataplane_egress_port(1);
  std::string payload;
  for (char byte = 0; byte < 32; ++byte) {
    payload.push_back(byte);
  }
  output->set_payload(payload);

  EXPECT_THAT(ExplainMatch(Drops(), resp),
              AllOf(::testing::HasSubstr(
                        "output dataplane port 1: "
                        "0x000102030405060708090a0b0c0d0e0f1011121314151617"
                        "..."),
                    Not(::testing::HasSubstr("24 bytes")),
                    Not(::testing::HasSubstr("more bytes")),
                    Not(::testing::HasSubstr("18191a1b"))));
}

TEST(ErrorMessageTest, FailureOmitsTraceWhenAbsent) {
  EXPECT_THAT(ExplainMatch(Drops(), Forward(1)),
              Not(::testing::HasSubstr("trace")));
}

TEST(ErrorMessageTest, NoTraceWhenMatches) {
  fourward::InjectPacketResponse resp;
  resp.add_possible_outcomes();
  resp.mutable_trace()->add_events()->mutable_table_lookup()->set_table_name(
      "my_table");
  EXPECT_THAT(ExplainMatch(Drops(), resp),
              Not(::testing::HasSubstr("my_table")));
}

TEST(ErrorMessageTest, PrintToSummarizesInjectPacketResponse) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(1);
  auto* cpu = ps->add_packets();
  cpu->set_dataplane_egress_port(510);
  cpu->set_p4rt_egress_port("CPU");
  cpu->set_payload("payload bytes should not be printed");
  resp.mutable_trace()->add_events()->mutable_table_lookup()->set_table_name(
      "my_table");

  EXPECT_THAT(
      ::testing::PrintToString(resp),
      AllOf(::testing::HasSubstr("1 outcome:"),
            ::testing::HasSubstr("dataplane ports {1: 1, 510: 1}"),
            ::testing::HasSubstr("P4Runtime ports {\"CPU\": 1}"),
            Not(::testing::HasSubstr("trace")),
            Not(::testing::HasSubstr("payload bytes should not be printed")),
            Not(::testing::HasSubstr("my_table"))));
}

TEST(ErrorMessageTest, FailureSummarizesActualOutcomes) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_dataplane_egress_port(6);
  ps->add_packets()->set_dataplane_egress_port(510);
  ps->add_packets()->set_dataplane_egress_port(510);

  EXPECT_THAT(ExplainMatch(Drops(), resp),
              ::testing::HasSubstr(
                  "actual output had 3 packets: dataplane port 6, dataplane "
                  "port 510, dataplane port 510"));
}

TEST(ErrorMessageTest, OnPortsFailureShowsActualPortGroups) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* data = ps->add_packets();
  data->set_dataplane_egress_port(1);
  data->set_p4rt_egress_port("6");
  auto* cpu1 = ps->add_packets();
  cpu1->set_dataplane_egress_port(510);
  cpu1->set_p4rt_egress_port("CPU");
  auto* cpu2 = ps->add_packets();
  cpu2->set_dataplane_egress_port(510);
  cpu2->set_p4rt_egress_port("CPU");

  EXPECT_THAT(
      ExplainMatch(OutcomeIs(OnPorts({
                       {P4RuntimePort{"6"}, SizeIs(1)},
                       {P4RuntimePort{"510"}, SizeIs(2)},
                   })),
                   resp),
      AllOf(::testing::HasSubstr("on P4Runtime port \"510\""),
            ::testing::HasSubstr("actual port groups:"),
            ::testing::HasSubstr("dataplane ports {1: 1, 510: 2}"),
            ::testing::HasSubstr("P4Runtime ports {\"6\": 1, \"CPU\": 2}")));
}

TEST(ErrorMessageTest, WrongPortFailureMatchesGolden) {
  ExpectMatchesGolden("wrong_port.golden.txt",
                      FailureReport(ForwardsTo(2), Forward(1)));
}

TEST(ErrorMessageTest, CompactTraceFailureMatchesGolden) {
  MatcherTraceFlagScope flag(internal::MatcherTraceMode::kSummary);
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* packet = ps->add_packets();
  packet->set_dataplane_egress_port(6);
  packet->set_p4rt_egress_port("6");
  packet->set_payload("abc");
  auto* cpu_packet = ps->add_packets();
  cpu_packet->set_dataplane_egress_port(510);
  cpu_packet->set_p4rt_egress_port("CPU");
  cpu_packet->set_payload("abcdef");

  auto* ingress = resp.mutable_trace()->add_events()->mutable_packet_ingress();
  ingress->set_dataplane_ingress_port(2);
  ingress->set_p4rt_ingress_port("4");
  auto* parser_start =
      resp.mutable_trace()->add_events()->mutable_parser_transition();
  parser_start->set_parser_name("packet_parser");
  parser_start->set_from_state("start");
  parser_start->set_to_state("parse_ethernet");
  auto* parser_ipv4 =
      resp.mutable_trace()->add_events()->mutable_parser_transition();
  parser_ipv4->set_parser_name("packet_parser");
  parser_ipv4->set_from_state("parse_ethernet");
  parser_ipv4->set_to_state("parse_ipv4");
  auto* parser_accept =
      resp.mutable_trace()->add_events()->mutable_parser_transition();
  parser_accept->set_parser_name("packet_parser");
  parser_accept->set_from_state("parse_ipv4");
  parser_accept->set_to_state("accept");
  auto* assignment_1 = resp.mutable_trace()->add_events();
  assignment_1->mutable_assignment()->set_target("local_metadata.vrf_id");
  assignment_1->set_result_value("1");
  auto* assignment_2 = resp.mutable_trace()->add_events();
  assignment_2->mutable_assignment()->set_target(
      "local_metadata.nexthop_id_valid");
  assignment_2->set_result_value("true");
  auto* assignment_3 = resp.mutable_trace()->add_events();
  assignment_3->mutable_assignment()->set_target("local_metadata.nexthop_id");
  assignment_3->set_result_value("5");
  auto* assignment_4 = resp.mutable_trace()->add_events();
  assignment_4->mutable_assignment()->set_target("standard_metadata.egress_spec");
  assignment_4->set_result_value("6");
  auto* assignment_5 = resp.mutable_trace()->add_events();
  assignment_5->mutable_assignment()->set_target(
      "local_metadata.packet_in_target_egress_port");
  assignment_5->set_result_value("6");
  auto* vlan_miss = resp.mutable_trace()->add_events()->mutable_table_lookup();
  vlan_miss->set_table_name("acl_pre_ingress_vlan_table");
  vlan_miss->set_hit(false);
  vlan_miss->set_action_name("NoAction");
  auto* metadata_miss =
      resp.mutable_trace()->add_events()->mutable_table_lookup();
  metadata_miss->set_table_name("acl_pre_ingress_metadata_table");
  metadata_miss->set_hit(false);
  metadata_miss->set_action_name("NoAction");
  auto* acl = resp.mutable_trace()->add_events()->mutable_table_lookup();
  acl->set_table_name("acl_ingress_table");
  acl->set_hit(true);
  acl->set_action_name("acl_copy");
  auto* route = resp.mutable_trace()->add_events()->mutable_table_lookup();
  route->set_table_name("ipv4_table");
  route->set_hit(true);
  route->set_action_name("set_nexthop_id");
  route->mutable_p4rt_matched_entry()->set_table_id(33554500);
  auto* route_match = route->mutable_p4rt_matched_entry()->add_match();
  route_match->set_field_id(1);
  route_match->mutable_exact()->set_value("vrf");
  auto* route_action =
      route->mutable_p4rt_matched_entry()->mutable_action()->mutable_action();
  route_action->add_params()->set_param_id(1);
  route_action->mutable_params(0)->set_value("nexthop(5, vrf)");
  auto* route_action_execution =
      resp.mutable_trace()->add_events()->mutable_action_execution();
  route_action_execution->set_action_name("set_nexthop_id");
  (*route_action_execution->mutable_params())["nexthop_id"] =
      "nexthop(5, vrf)";
  auto* clone_event = resp.mutable_trace()->add_events();
  clone_event->set_id(265);
  auto* clone = clone_event->mutable_clone_session_lookup();
  clone->set_session_id(1);
  clone->set_session_found(true);
  clone->set_dataplane_egress_port(510);
  clone->set_p4rt_egress_port("CPU");
  clone->set_egress_rid(1);
  clone->set_replica_count(1);
  resp.mutable_trace()->add_events()->mutable_clone()->set_session_id(1);

  auto* replication = resp.mutable_trace()->mutable_replication();
  replication->set_cause_id(265);
  auto* egress = replication->add_branches();
  auto* egress_assignment = egress->add_events();
  egress_assignment->mutable_assignment()->set_target("headers.ipv4.ttl");
  egress_assignment->set_result_value("31 (0x1f)");
  auto* output = egress->mutable_output();
  output->set_dataplane_egress_port(6);
  output->set_p4rt_egress_port("6");
  output->set_payload("abc");
  auto* cpu = replication->add_branches();
  auto* cpu_output = cpu->mutable_output();
  cpu_output->set_dataplane_egress_port(510);
  cpu_output->set_p4rt_egress_port("CPU");
  cpu_output->set_payload("abcdef");

  ExpectMatchesGolden("compact_trace.golden.txt",
                      FailureReport(Drops(), resp));
}

TEST(ErrorMessageTest, OnPortsFailureMatchesGolden) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* data = ps->add_packets();
  data->set_dataplane_egress_port(1);
  data->set_p4rt_egress_port("6");
  auto* cpu1 = ps->add_packets();
  cpu1->set_dataplane_egress_port(510);
  cpu1->set_p4rt_egress_port("CPU");
  auto* cpu2 = ps->add_packets();
  cpu2->set_dataplane_egress_port(510);
  cpu2->set_p4rt_egress_port("CPU");

  ExpectMatchesGolden(
      "on_ports_cpu_name_mismatch.golden.txt",
      FailureReport(OutcomeIs(OnPorts({
                        {P4RuntimePort{"6"}, SizeIs(1)},
                        {P4RuntimePort{"510"}, SizeIs(2)},
                    })),
                    resp));
}

TEST(ErrorMessageTest, PayloadMismatchFailureMatchesGolden) {
  fourward::InjectPacketResponse resp;
  auto* packet = resp.add_possible_outcomes()->add_packets();
  packet->set_dataplane_egress_port(1);
  packet->set_payload("actual");

  ExpectMatchesGolden("payload_mismatch.golden.txt",
                      FailureReport(OutcomeIs(OnPort(1, "expected")), resp));
}

// --- Composition ---

TEST(CompositionTest, ForwardsToWithIngress) {
  EXPECT_THAT(MakeResult(5, 1), AllOf(ForwardsTo(1), HasIngress(5)));
}

// --- PacketsByDataplanePort ---

TEST(PacketsByDataplanePortTest, GroupsByDataplanePort) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* p1a = ps->add_packets();
  p1a->set_dataplane_egress_port(1);
  p1a->set_payload("a");
  auto* p1b = ps->add_packets();
  p1b->set_dataplane_egress_port(1);
  p1b->set_payload("b");
  ps->add_packets()->set_dataplane_egress_port(2);

  auto by_port = PacketsByDataplanePort(resp);
  EXPECT_THAT(by_port, SizeIs(2));
  EXPECT_THAT(by_port[1], SizeIs(2));
  EXPECT_THAT(by_port[2], SizeIs(1));
}

TEST(PacketsByDataplanePortTest, MissingPortReturnsEmpty) {
  auto by_port = PacketsByDataplanePort(Forward(1));
  EXPECT_THAT(by_port[99], IsEmpty());
}

TEST(PacketsByDataplanePortTest, WorksOnProcessPacketResult) {
  auto by_port = PacketsByDataplanePort(MakeResult(0, 3));
  EXPECT_THAT(by_port[3], SizeIs(1));
}

TEST(PacketsByDataplanePortTest, PreservesPayloads) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* pkt = ps->add_packets();
  pkt->set_dataplane_egress_port(1);
  pkt->set_payload("hello");

  auto by_port = PacketsByDataplanePort(resp);
  ASSERT_THAT(by_port[1], SizeIs(1));
  EXPECT_EQ(by_port[1][0].payload(), "hello");
}

TEST(PacketsByDataplanePortTest, DropReturnsEmptyMap) {
  auto by_port = PacketsByDataplanePort(Drop());
  EXPECT_THAT(by_port, IsEmpty());
}

// --- PacketsByP4RuntimePort ---

TEST(PacketsByP4RuntimePortTest, GroupsByP4RuntimePort) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_p4rt_egress_port("Eth0");
  ps->add_packets()->set_p4rt_egress_port("Eth0");
  ps->add_packets()->set_p4rt_egress_port("Eth1");

  auto by_port = PacketsByP4RuntimePort(resp);
  EXPECT_THAT(by_port, SizeIs(2));
  EXPECT_THAT(by_port["Eth0"], SizeIs(2));
  EXPECT_THAT(by_port["Eth1"], SizeIs(1));
}

TEST(PacketsByP4RuntimePortTest, MissingPortReturnsEmpty) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  ps->add_packets()->set_p4rt_egress_port("Eth0");

  auto by_port = PacketsByP4RuntimePort(resp);
  EXPECT_THAT(by_port["Eth99"], IsEmpty());
}

TEST(PacketsByP4RuntimePortTest, PreservesPayloads) {
  fourward::InjectPacketResponse resp;
  auto* ps = resp.add_possible_outcomes();
  auto* pkt = ps->add_packets();
  pkt->set_p4rt_egress_port("Eth0");
  pkt->set_payload("hello");

  auto by_port = PacketsByP4RuntimePort(resp);
  ASSERT_THAT(by_port["Eth0"], SizeIs(1));
  EXPECT_EQ(by_port["Eth0"][0].payload(), "hello");
}

TEST(PacketsByP4RuntimePortTest, DropReturnsEmptyMap) {
  auto by_port = PacketsByP4RuntimePort(Drop());
  EXPECT_THAT(by_port, IsEmpty());
}

}  // namespace
}  // namespace fourward
