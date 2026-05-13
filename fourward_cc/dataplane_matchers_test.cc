// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/dataplane_matchers.h"

#include <sstream>
#include <string>
#include <string_view>

#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "grpc/dataplane.pb.h"

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
  EXPECT_THAT(os.str(), ::testing::HasSubstr("outcomes"));
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
  EXPECT_THAT(ExplainMatch(Drops(), resp),
              ::testing::HasSubstr("my_table"));
}

TEST(ErrorMessageTest, FailureIncludesTraceOnProcessPacketResult) {
  fourward::ProcessPacketResult result;
  result.add_possible_outcomes()->add_packets()->set_dataplane_egress_port(1);
  result.mutable_trace()->add_events()->mutable_table_lookup()->set_table_name(
      "ingress_table");
  EXPECT_THAT(ExplainMatch(Drops(), result),
              ::testing::HasSubstr("ingress_table"));
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

// --- Composition ---

TEST(CompositionTest, ForwardsToWithIngress) {
  EXPECT_THAT(MakeResult(5, 1), AllOf(ForwardsTo(1), HasIngress(5)));
}

}  // namespace
}  // namespace fourward
