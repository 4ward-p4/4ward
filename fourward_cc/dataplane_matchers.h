// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

// gtest matchers for Dataplane RPC responses — see
// userdocs/reference/dataplane-matchers.md for the full guide.

#ifndef FOURWARD_CC_DATAPLANE_MATCHERS_H_
#define FOURWARD_CC_DATAPLANE_MATCHERS_H_

#include <concepts>
#include <cstdint>
#include <ostream>
#include <sstream>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

#include "absl/algorithm/container.h"
#include "absl/container/btree_map.h"
#include "absl/strings/escaping.h"
#include "absl/strings/string_view.h"
#include "fourward_cc/dataplane_client.h"
#include "gmock/gmock.h"
#include "grpc/dataplane.pb.h"
#include "simulator/simulator.pb.h"
#include "simulator/trace_summary.h"

namespace fourward {
namespace internal {

using PacketList = std::vector<fourward::OutputPacket>;

enum class MatcherTraceMode {
  kNone,
  kSummary,
  kFull,
};

bool AbslParseFlag(::absl::string_view text, MatcherTraceMode* mode,
                   std::string* error);
std::string AbslUnparseFlag(MatcherTraceMode mode);
MatcherTraceMode GetMatcherTraceMode();
void SetMatcherTraceModeForTest(MatcherTraceMode mode);

// Satisfied by InjectPacketResponse and ProcessPacketResult.
template <typename T>
concept HasPossibleOutcomes = requires(const T& r) {
  { r.possible_outcomes() };
};

template <typename T>
  requires(HasPossibleOutcomes<T>)
std::vector<PacketList> ExtractOutcomes(const T& result) {
  std::vector<PacketList> outcomes;
  for (const auto& ps : result.possible_outcomes()) {
    outcomes.emplace_back(ps.packets().begin(), ps.packets().end());
  }
  return outcomes;
}

void PrintTraceForMatcher(std::ostream* os, const fourward::TraceTree& trace);
void PrintActualOutputSummary(std::ostream* os, const PacketList& packets);
void PrintPacketPortGroups(std::ostream* os, const PacketList& packets);
void PrintPacketListSummary(std::ostream* os, const PacketList& packets);
void PrintPacketListDetails(std::ostream* os, const PacketList& packets);
void PrintOutcomesSummary(std::ostream* os,
                          const std::vector<PacketList>& outcomes);

template <typename T>
  requires(HasPossibleOutcomes<T>)
void PrintResultSummary(const T& result, std::ostream* os) {
  auto outcomes = ExtractOutcomes(result);
  if (outcomes.size() == 1) {
    *os << "1 outcome: ";
    PrintPacketListSummary(os, outcomes[0]);
    return;
  }
  *os << outcomes.size() << " possible outcomes: ";
  PrintOutcomesSummary(os, outcomes);
}

template <typename T>
  requires(HasPossibleOutcomes<T>)
void PrintResultFailureDetails(const T& result,
                               const std::vector<PacketList>& outcomes,
                               ::testing::MatchResultListener* listener,
                               bool include_outcomes = true) {
  if (!listener->IsInterested()) return;
  if (include_outcomes) {
    *listener << "\nactual outcomes: ";
    PrintOutcomesSummary(listener->stream(), outcomes);
  }
  if (result.has_trace()) {
    *listener << "\n";
    PrintTraceForMatcher(listener->stream(), result.trace());
  }
}

// Tag types for disambiguation.
template <typename M>
struct PacketsTag {
  M matcher;
};

template <typename M>
struct OutcomeTag {
  M matcher;
};

// Wraps an arg for OutcomesAre: tagged types pass through, everything else
// gets wrapped as a single-packet outcome.
template <typename M>
auto WrapForOutcomesAre(OutcomeTag<M> tagged) {
  return std::move(tagged.matcher);
}
template <typename M>
auto WrapForOutcomesAre(PacketsTag<M> tagged) {
  return std::move(tagged.matcher);
}
template <typename M>
auto WrapForOutcomesAre(M m) {
  return ::testing::UnorderedElementsAre(std::move(m));
}

// Base for result-level matchers. Extracts outcomes, checks count, and
// delegates to an inner matcher on the outcomes vector. Produces clear
// error messages ("has 3 possible outcomes (expected 1)") instead of
// gmock's ResultOf internals.
class OutcomesMatcherBase {
 public:
  OutcomesMatcherBase(::testing::Matcher<const std::vector<PacketList>&> inner,
                      std::string description)
      : inner_(std::move(inner)), description_(std::move(description)) {}

  template <typename T>
    requires(HasPossibleOutcomes<T>)
  bool MatchAndExplain(const T& result,
                       ::testing::MatchResultListener* listener) const {
    auto outcomes = ExtractOutcomes(result);
    if (inner_.MatchAndExplain(outcomes, listener)) return true;
    PrintResultFailureDetails(result, outcomes, listener);
    return false;
  }

  void DescribeTo(std::ostream* os) const {
    if (description_.empty()) {
      *os << "has outcomes that ";
      inner_.DescribeTo(os);
    } else {
      *os << description_;
    }
  }
  void DescribeNegationTo(std::ostream* os) const {
    if (description_.empty()) {
      *os << "has outcomes that ";
      inner_.DescribeNegationTo(os);
    } else {
      *os << "does not " << description_;
    }
  }

 protected:
  ::testing::Matcher<const std::vector<PacketList>&> inner_;
  std::string description_;
};

class SingleOutcomePacketsMatcher {
 public:
  SingleOutcomePacketsMatcher(
      ::testing::Matcher<const PacketList&> packet_matcher,
      std::string description, bool include_packet_explanation = false)
      : packet_matcher_(std::move(packet_matcher)),
        description_(std::move(description)),
        include_packet_explanation_(include_packet_explanation) {}

  template <typename T>
    requires(HasPossibleOutcomes<T>)
  bool MatchAndExplain(const T& result,
                       ::testing::MatchResultListener* listener) const {
    auto outcomes = ExtractOutcomes(result);
    ::testing::StringMatchResultListener inner_listener;
    if (outcomes.size() == 1 &&
        packet_matcher_.MatchAndExplain(outcomes[0], &inner_listener)) {
      return true;
    }
    if (listener->IsInterested()) {
      *listener << "expected one outcome whose packets match; ";
      if (outcomes.size() == 1) {
        PrintActualOutputSummary(listener->stream(), outcomes[0]);
      } else {
        *listener << "actual result had " << outcomes.size()
                  << " possible outcomes";
      }
      const std::string packet_explanation = inner_listener.str();
      if (include_packet_explanation_ && !packet_explanation.empty()) {
        *listener << "\n" << packet_explanation;
      }
    }
    PrintResultFailureDetails(result, outcomes, listener,
                              /*include_outcomes=*/outcomes.size() != 1);
    return false;
  }

  void DescribeTo(std::ostream* os) const {
    if (description_.empty()) {
      *os << "has exactly one outcome and that outcome ";
      packet_matcher_.DescribeTo(os);
    } else {
      *os << description_;
    }
  }
  void DescribeNegationTo(std::ostream* os) const {
    if (description_.empty()) {
      *os << "does not have exactly one outcome whose packets ";
      packet_matcher_.DescribeTo(os);
    } else {
      *os << "does not " << description_;
    }
  }

 private:
  ::testing::Matcher<const PacketList&> packet_matcher_;
  std::string description_;
  bool include_packet_explanation_;
};

// Port key for OnPorts expectations: either a dataplane or a P4Runtime port.
using PortKey = std::variant<DataplanePort, P4RuntimePort>;

// True if `pkt` egressed on `key`, comparing the packet's egress port in
// `key`'s own port type. OnPorts evaluates each expectation through this
// predicate, so a single call can mix DataplanePort and P4RuntimePort
// expectations freely — each selects packets by its own port type.
inline bool PacketIsOnPort(const fourward::OutputPacket& pkt,
                           const PortKey& key) {
  if (std::holds_alternative<DataplanePort>(key)) {
    return pkt.dataplane_egress_port() == std::get<DataplanePort>(key).port;
  }
  return pkt.p4rt_egress_port() == std::get<P4RuntimePort>(key).port;
}

inline void PrintPortKey(std::ostream* os, const PortKey& key) {
  if (std::holds_alternative<DataplanePort>(key)) {
    *os << std::get<DataplanePort>(key).port;
  } else {
    *os << "\"" << std::get<P4RuntimePort>(key).port << "\"";
  }
}

inline void PrintTypedPortKey(std::ostream* os, const PortKey& key) {
  if (std::holds_alternative<DataplanePort>(key)) {
    *os << "dataplane port " << std::get<DataplanePort>(key).port;
  } else {
    *os << "P4Runtime port \"" << std::get<P4RuntimePort>(key).port << "\"";
  }
}

inline void PrintPayloadPreview(std::ostream* os, std::string_view payload) {
  constexpr size_t kMaxPayloadPreviewBytes = 24;
  const std::string_view preview = payload.substr(0, kMaxPayloadPreviewBytes);
  *os << "0x" << absl::BytesToHexString(preview);
  if (payload.size() > kMaxPayloadPreviewBytes) *os << "...";
}

inline PortKey ToPortKey(DataplanePort port) { return PortKey{port}; }
inline PortKey ToPortKey(P4RuntimePort port) {
  return PortKey{std::move(port)};
}
inline PortKey ToPortKey(uint32_t port) { return PortKey{DataplanePort{port}}; }
inline PortKey ToPortKey(const std::string& port) {
  return PortKey{P4RuntimePort{port}};
}
inline PortKey ToPortKey(std::string_view port) {
  return PortKey{P4RuntimePort{std::string(port)}};
}
inline PortKey ToPortKey(const char* port) {
  return PortKey{P4RuntimePort{port}};
}

inline std::string ForwardDescription(const std::vector<PortKey>& ports) {
  std::ostringstream description;
  description << (ports.size() == 1 ? "forward to " : "forward to ports ");
  for (size_t i = 0; i < ports.size(); ++i) {
    if (i > 0) description << ", ";
    PrintTypedPortKey(&description, ports[i]);
  }
  return description.str();
}

inline void PrintPacketEgress(std::ostream* os,
                              const fourward::OutputPacket& packet) {
  *os << "dataplane port " << packet.dataplane_egress_port();
  if (!packet.p4rt_egress_port().empty()) {
    *os << " / P4Runtime port \"" << packet.p4rt_egress_port() << "\"";
  }
  if (!packet.payload().empty()) {
    *os << " with payload ";
    PrintPayloadPreview(os, packet.payload());
  }
}

template <typename T>
  requires(HasPossibleOutcomes<T>)
PacketList DeterministicPackets(const T& result) {
  auto outcomes = ExtractOutcomes(result);
  if (outcomes.size() != 1) {
    ADD_FAILURE() << "expected 1 outcome, got " << outcomes.size();
    return {};
  }
  return std::move(outcomes[0]);
}

class OnPortMatcher {
 public:
  explicit OnPortMatcher(PortKey expected) : expected_(std::move(expected)) {}

  bool MatchAndExplain(const fourward::OutputPacket& packet,
                       ::testing::MatchResultListener* listener) const {
    if (PacketIsOnPort(packet, expected_)) return true;

    if (std::holds_alternative<DataplanePort>(expected_)) {
      *listener << "egressed on dataplane port "
                << packet.dataplane_egress_port();
    } else {
      *listener << "egressed on P4Runtime port \"" << packet.p4rt_egress_port()
                << "\"";
    }
    return false;
  }

  void DescribeTo(std::ostream* os) const {
    *os << "is on ";
    PrintTypedPortKey(os, expected_);
  }

  void DescribeNegationTo(std::ostream* os) const {
    *os << "is not on ";
    PrintTypedPortKey(os, expected_);
  }

 private:
  PortKey expected_;
};

class HasPayloadMatcher {
 public:
  explicit HasPayloadMatcher(::testing::Matcher<const std::string&> expected)
      : expected_(std::move(expected)) {}

  bool MatchAndExplain(const fourward::OutputPacket& packet,
                       ::testing::MatchResultListener* listener) const {
    ::testing::StringMatchResultListener inner_listener;
    if (expected_.MatchAndExplain(packet.payload(), &inner_listener)) {
      return true;
    }

    *listener << "has payload " << ::testing::PrintToString(packet.payload());
    const std::string inner_explanation = inner_listener.str();
    if (!inner_explanation.empty()) {
      *listener << ", " << inner_explanation;
    }
    return false;
  }

  void DescribeTo(std::ostream* os) const {
    *os << "has payload that ";
    expected_.DescribeTo(os);
  }

  void DescribeNegationTo(std::ostream* os) const {
    *os << "has payload that ";
    expected_.DescribeNegationTo(os);
  }

 private:
  ::testing::Matcher<const std::string&> expected_;
};

class DropMatcher {
 public:
  DropMatcher() : inner_(::testing::ElementsAre(::testing::IsEmpty())) {}

  template <typename T>
    requires(HasPossibleOutcomes<T>)
  bool MatchAndExplain(const T& result,
                       ::testing::MatchResultListener* listener) const {
    auto outcomes = ExtractOutcomes(result);
    ::testing::StringMatchResultListener inner_listener;
    if (inner_.MatchAndExplain(outcomes, &inner_listener)) return true;

    if (listener->IsInterested()) {
      if (outcomes.size() == 1) {
        *listener << "expected drop; ";
        PrintActualOutputSummary(listener->stream(), outcomes[0]);
      } else {
        *listener << "expected drop; actual result had " << outcomes.size()
                  << " possible outcomes";
      }
      PrintResultFailureDetails(result, outcomes, listener,
                                /*include_outcomes=*/outcomes.size() != 1);
    }
    return false;
  }

  void DescribeTo(std::ostream* os) const { *os << "drop the packet"; }
  void DescribeNegationTo(std::ostream* os) const {
    *os << "does not drop the packet";
  }

 private:
  ::testing::Matcher<const std::vector<PacketList>&> inner_;
};

class ForwardsToMatcher {
 public:
  ForwardsToMatcher(::testing::Matcher<const std::vector<PacketList>&> inner,
                    std::vector<PortKey> expected)
      : inner_(std::move(inner)), expected_(std::move(expected)) {}

  template <typename T>
    requires(HasPossibleOutcomes<T>)
  bool MatchAndExplain(const T& result,
                       ::testing::MatchResultListener* listener) const {
    auto outcomes = ExtractOutcomes(result);
    ::testing::StringMatchResultListener inner_listener;
    if (inner_.MatchAndExplain(outcomes, &inner_listener)) return true;

    if (listener->IsInterested()) {
      *listener << "expected ";
      PrintExpectedOutput(listener->stream());
      *listener << "; ";
      if (outcomes.size() == 1) {
        PrintActualOutputSummary(listener->stream(), outcomes[0]);
      } else {
        *listener << "actual result had " << outcomes.size()
                  << " possible outcomes";
      }
      PrintResultFailureDetails(result, outcomes, listener,
                                /*include_outcomes=*/outcomes.size() != 1);
    }
    return false;
  }

  void DescribeTo(std::ostream* os) const {
    *os << ForwardDescription(expected_);
  }
  void DescribeNegationTo(std::ostream* os) const {
    *os << "does not " << ForwardDescription(expected_);
  }

 private:
  void PrintExpectedOutput(std::ostream* os) const {
    if (expected_.size() == 1) {
      *os << "one packet on ";
      PrintTypedPortKey(os, expected_[0]);
      return;
    }

    *os << expected_.size() << " packets on ";
    for (size_t i = 0; i < expected_.size(); ++i) {
      if (i > 0) *os << ", ";
      PrintTypedPortKey(os, expected_[i]);
    }
  }

  ::testing::Matcher<const std::vector<PacketList>&> inner_;
  std::vector<PortKey> expected_;
};

}  // namespace internal

// ADL hook for non-GoogleTest ostream contexts. GoogleTest printing goes
// through the UniversalPrinter specializations below.
template <typename T>
  requires(internal::HasPossibleOutcomes<T>)
void PrintTo(const T& result, std::ostream* os) {
  internal::PrintResultSummary(result, os);
}

// ---------------------------------------------------------------------------
// Packet-level matchers (match on OutputPacket)
// ---------------------------------------------------------------------------

inline auto OnPort(DataplanePort expected) {
  return ::testing::MakePolymorphicMatcher(
      internal::OnPortMatcher(internal::PortKey{expected}));
}
inline auto OnPort(P4RuntimePort expected) {
  return ::testing::MakePolymorphicMatcher(
      internal::OnPortMatcher(internal::PortKey{std::move(expected)}));
}
inline auto OnPort(uint32_t port) { return OnPort(DataplanePort{port}); }
inline auto OnPort(std::string port) {
  return OnPort(P4RuntimePort{std::move(port)});
}
inline auto OnPort(std::string_view port) {
  return OnPort(P4RuntimePort{std::string(port)});
}

inline auto HasPayload(::testing::Matcher<const std::string&> m) {
  return ::testing::MakePolymorphicMatcher(
      internal::HasPayloadMatcher(std::move(m)));
}

// OnPort with payload: "a packet on this port with this payload."
template <typename Port, typename Payload>
auto OnPort(Port port, Payload payload) {
  return ::testing::AllOf(OnPort(std::move(port)),
                          HasPayload(std::move(payload)));
}

// ---------------------------------------------------------------------------
// Packets — marks a matcher as operating on the packet list (container mode)
// ---------------------------------------------------------------------------

template <typename M>
internal::PacketsTag<M> Packets(M m) {
  return {std::move(m)};
}

// ---------------------------------------------------------------------------
// Outcome — marks a multi-packet outcome for use inside OutcomesAre
// ---------------------------------------------------------------------------

template <typename... Ms>
auto Outcome(Ms... ms) {
  return internal::OutcomeTag{
      ::testing::UnorderedElementsAre(std::move(ms)...)};
}

// ---------------------------------------------------------------------------
// OutcomeIs — single deterministic outcome
// ---------------------------------------------------------------------------

inline auto OutcomeIs() {
  return ::testing::MakePolymorphicMatcher(internal::DropMatcher());
}

namespace internal {

template <typename M>
std::string DescribePacketMatcher(const M& matcher_like) {
  ::testing::Matcher<const fourward::OutputPacket&> matcher = matcher_like;
  std::ostringstream description;
  matcher.DescribeTo(&description);
  return description.str();
}

template <typename... Ms>
std::string SingleOutcomeDescription(const Ms&... matchers) {
  std::vector<std::string> descriptions;
  (descriptions.push_back(DescribePacketMatcher(matchers)), ...);

  std::ostringstream description;
  description << "has exactly one outcome with ";
  if (descriptions.size() == 1) {
    description << "one packet that " << descriptions[0];
    return description.str();
  }

  description << descriptions.size() << " packets matching: ";
  for (size_t i = 0; i < descriptions.size(); ++i) {
    if (i > 0) description << ", ";
    description << descriptions[i];
  }
  return description.str();
}

}  // namespace internal

template <typename... Ms>
auto OutcomeIs(Ms... ms) {
  return ::testing::MakePolymorphicMatcher(
      internal::SingleOutcomePacketsMatcher(
          ::testing::UnorderedElementsAre(std::move(ms)...),
          internal::SingleOutcomeDescription(ms...)));
}

template <typename M>
auto OutcomeIs(internal::PacketsTag<M> tagged) {
  return ::testing::MakePolymorphicMatcher(
      internal::SingleOutcomePacketsMatcher(
          std::move(tagged.matcher), "",
          /*include_packet_explanation=*/true));
}

// ---------------------------------------------------------------------------
// OutcomesAre — exact set of outcomes (unordered)
// ---------------------------------------------------------------------------

template <typename... Ms>
auto OutcomesAre(Ms... ms) {
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::UnorderedElementsAre(
          internal::WrapForOutcomesAre(std::move(ms))...),
      ""));
}

// ---------------------------------------------------------------------------
// EachOutcome / AnyOutcome
// ---------------------------------------------------------------------------

template <typename M>
auto EachOutcome(M m) {
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::Each(::testing::UnorderedElementsAre(std::move(m))), ""));
}
template <typename M>
auto EachOutcome(internal::PacketsTag<M> tagged) {
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::Each(std::move(tagged.matcher)), ""));
}

template <typename M>
auto AnyOutcome(M m) {
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::Contains(::testing::UnorderedElementsAre(std::move(m))), ""));
}
template <typename M>
auto AnyOutcome(internal::PacketsTag<M> tagged) {
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::Contains(std::move(tagged.matcher)), ""));
}

// ---------------------------------------------------------------------------
// HasIngress
// ---------------------------------------------------------------------------

class HasIngressMatcher {
 public:
  explicit HasIngressMatcher(internal::PortKey expected)
      : expected_(std::move(expected)) {}

  template <typename T>
  bool MatchAndExplain(const T& result,
                       ::testing::MatchResultListener* listener) const {
    if (std::holds_alternative<P4RuntimePort>(expected_)) {
      const std::string& actual = result.input_packet().p4rt_ingress_port();
      const std::string& exp = std::get<P4RuntimePort>(expected_).port;
      if (actual != exp) {
        *listener << "has P4Runtime ingress port \"" << actual << "\"";
        return false;
      }
    } else {
      uint32_t actual = result.input_packet().dataplane_ingress_port();
      uint32_t exp = std::get<DataplanePort>(expected_).port;
      if (actual != exp) {
        *listener << "has dataplane ingress port " << actual;
        return false;
      }
    }
    return true;
  }

  void DescribeTo(std::ostream* os) const {
    *os << "has ingress port ";
    internal::PrintPortKey(os, expected_);
  }
  void DescribeNegationTo(std::ostream* os) const {
    *os << "does not have ingress port ";
    internal::PrintPortKey(os, expected_);
  }

 private:
  internal::PortKey expected_;
};

inline auto HasIngress(DataplanePort port) {
  return ::testing::MakePolymorphicMatcher(
      HasIngressMatcher(internal::PortKey{port}));
}
inline auto HasIngress(P4RuntimePort port) {
  return ::testing::MakePolymorphicMatcher(
      HasIngressMatcher(internal::PortKey{std::move(port)}));
}
inline auto HasIngress(uint32_t port) {
  return HasIngress(DataplanePort{port});
}
inline auto HasIngress(std::string port) {
  return HasIngress(P4RuntimePort{std::move(port)});
}
inline auto HasIngress(std::string_view port) {
  return HasIngress(P4RuntimePort{std::string(port)});
}

// ---------------------------------------------------------------------------
// Shorthands
// ---------------------------------------------------------------------------

template <typename... Ports>
auto ForwardsTo(Ports... ports) {
  return ::testing::MakePolymorphicMatcher(internal::ForwardsToMatcher(
      ::testing::ElementsAre(::testing::UnorderedElementsAre(OnPort(ports)...)),
      {internal::ToPortKey(ports)...}));
}

inline auto Forwards() {
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::ElementsAre(::testing::Not(::testing::IsEmpty())),
      "forward the packet"));
}

inline auto Drops() { return OutcomeIs(); }

// ---------------------------------------------------------------------------
// OnPorts — group by egress port
//
// Expectations may mix DataplanePort and P4RuntimePort; each is matched
// against the packets on that port in its own port type. Exhaustive: a
// packet egressing on a port no expectation lists fails the match.
// ---------------------------------------------------------------------------

class OnPortsMatcher {
 public:
  using PortExpectation =
      std::pair<internal::PortKey,
                ::testing::Matcher<const internal::PacketList&>>;

  explicit OnPortsMatcher(std::vector<PortExpectation> expected)
      : expected_(std::move(expected)) {}

  template <typename Container>
  bool MatchAndExplain(const Container& packets,
                       ::testing::MatchResultListener* listener) const {
    // Each expectation is evaluated independently against the full packet
    // list, selecting packets by its own port type. This lets one OnPorts
    // call mix DataplanePort and P4RuntimePort expectations.
    for (const auto& [port, matcher] : expected_) {
      internal::PacketList group;
      for (const auto& p : packets) {
        if (internal::PacketIsOnPort(p, port)) group.push_back(p);
      }
      ::testing::StringMatchResultListener inner_listener;
      if (!matcher.MatchAndExplain(group, &inner_listener)) {
        if (!listener->IsInterested()) return false;
        *listener << "on ";
        internal::PrintTypedPortKey(listener->stream(), port);
        *listener << ": found " << group.size() << " packet"
                  << (group.size() == 1 ? "" : "s") << "; expected: ";
        matcher.DescribeTo(listener->stream());
        const std::string inner_explanation = inner_listener.str();
        if (!inner_explanation.empty()) {
          *listener << "\n" << inner_explanation;
        }
        *listener << "\nactual port groups: ";
        internal::PrintPacketPortGroups(
            listener->stream(),
            internal::PacketList(packets.begin(), packets.end()));
        return false;
      }
    }
    // Exhaustive: a packet on a port no expectation lists is a failure, not
    // silently ignored. A packet is covered if it matches any listed port in
    // that port's own type, so mixed-type expectations cover it either way.
    for (const auto& p : packets) {
      const bool covered =
          absl::c_any_of(expected_, [&](const PortExpectation& e) {
            return internal::PacketIsOnPort(p, e.first);
          });
      if (!covered) {
        if (!listener->IsInterested()) return false;
        *listener << "has an unexpected packet egressing on dataplane port "
                  << p.dataplane_egress_port() << " (P4Runtime port \""
                  << p.p4rt_egress_port() << "\")";
        return false;
      }
    }
    return true;
  }

  void DescribeTo(std::ostream* os) const {
    *os << "has packets grouped by port matching: ";
    for (size_t i = 0; i < expected_.size(); ++i) {
      if (i > 0) *os << ", ";
      internal::PrintTypedPortKey(os, expected_[i].first);
      *os << " ";
      expected_[i].second.DescribeTo(os);
    }
    *os << ", and no packets on other ports";
  }
  void DescribeNegationTo(std::ostream* os) const {
    *os << "does not match port-grouped expectations";
  }

 private:
  std::vector<PortExpectation> expected_;
};

inline auto OnPorts(
    std::initializer_list<OnPortsMatcher::PortExpectation> expected) {
  return Packets(::testing::MakePolymorphicMatcher(
      OnPortsMatcher({expected.begin(), expected.end()})));
}

// ---------------------------------------------------------------------------
// PacketsByDataplanePort / PacketsByP4RuntimePort — extract packets grouped
// by port
//
// Returns a map from port to packets for the single deterministic outcome.
// Fails the test if the result has != 1 possible outcome. Uses btree_map
// for deterministic iteration order in test failure messages.
// ---------------------------------------------------------------------------

template <typename T>
  requires(internal::HasPossibleOutcomes<T>)
auto PacketsByDataplanePort(const T& result)
    -> ::absl::btree_map<uint32_t, std::vector<fourward::OutputPacket>> {
  ::absl::btree_map<uint32_t, std::vector<fourward::OutputPacket>> groups;
  auto packets = internal::DeterministicPackets(result);
  for (auto& pkt : packets) {
    groups[pkt.dataplane_egress_port()].push_back(std::move(pkt));
  }
  return groups;
}

template <typename T>
  requires(internal::HasPossibleOutcomes<T>)
auto PacketsByP4RuntimePort(const T& result)
    -> ::absl::btree_map<std::string, std::vector<fourward::OutputPacket>> {
  ::absl::btree_map<std::string, std::vector<fourward::OutputPacket>> groups;
  auto packets = internal::DeterministicPackets(result);
  for (auto& pkt : packets) {
    groups[pkt.p4rt_egress_port()].push_back(std::move(pkt));
  }
  return groups;
}

}  // namespace fourward

namespace testing {
namespace internal {

// UniversalPrinter<T>::Print is called before PrintTo overload resolution, so
// specializing it here is the most direct hook into GoogleTest printing.
template <typename T>
  requires(::fourward::internal::HasPossibleOutcomes<T>)
class UniversalPrinter<T> {
 public:
  static void Print(const T& value, ::std::ostream* os) {
    ::fourward::internal::PrintResultSummary(value, os);
  }
};

}  // namespace internal
}  // namespace testing

#endif  // FOURWARD_CC_DATAPLANE_MATCHERS_H_
