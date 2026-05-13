// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

// gtest matchers for Dataplane RPC responses — see
// userdocs/reference/dataplane-matchers.md for the full guide.

#ifndef FOURWARD_CC_DATAPLANE_MATCHERS_H_
#define FOURWARD_CC_DATAPLANE_MATCHERS_H_

#include <cstdint>
#include <map>
#include <ostream>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

#include "fourward_cc/dataplane_client.h"
#include "gmock/gmock.h"
#include "grpc/dataplane.pb.h"
#include "simulator/simulator.pb.h"

namespace fourward {
namespace internal {

using PacketList = std::vector<fourward::OutputPacket>;

template <typename T>
std::vector<PacketList> ExtractOutcomes(const T& result) {
  std::vector<PacketList> outcomes;
  for (const auto& ps : result.possible_outcomes()) {
    outcomes.emplace_back(ps.packets().begin(), ps.packets().end());
  }
  return outcomes;
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
  bool MatchAndExplain(const T& result,
                       ::testing::MatchResultListener* listener) const {
    auto outcomes = ExtractOutcomes(result);
    return inner_.MatchAndExplain(outcomes, listener);
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

// Port key for OnPorts grouping.
using PortKey = std::variant<DataplanePort, P4RuntimePort>;

// All entries must use the same port type (DataplanePort or P4RuntimePort).
// Mixing types in a single OnPorts call groups all packets by the first
// entry's type, which silently ignores entries of the other type.
inline PortKey PacketPortKey(const fourward::OutputPacket& pkt,
                             const PortKey& example_key) {
  if (std::holds_alternative<P4RuntimePort>(example_key)) {
    return P4RuntimePort{pkt.p4rt_egress_port()};
  }
  return DataplanePort{pkt.dataplane_egress_port()};
}

struct PortKeyLess {
  bool operator()(const PortKey& a, const PortKey& b) const {
    if (a.index() != b.index()) return a.index() < b.index();
    if (std::holds_alternative<DataplanePort>(a)) {
      return std::get<DataplanePort>(a).port < std::get<DataplanePort>(b).port;
    }
    return std::get<P4RuntimePort>(a).port < std::get<P4RuntimePort>(b).port;
  }
};

inline void PrintPortKey(std::ostream* os, const PortKey& key) {
  if (std::holds_alternative<DataplanePort>(key)) {
    *os << std::get<DataplanePort>(key).port;
  } else {
    *os << "\"" << std::get<P4RuntimePort>(key).port << "\"";
  }
}

}  // namespace internal

// ---------------------------------------------------------------------------
// Packet-level matchers (match on OutputPacket)
// ---------------------------------------------------------------------------

inline auto OnPort(DataplanePort expected) {
  return ::testing::ResultOf(
      [](const fourward::OutputPacket& p) { return p.dataplane_egress_port(); },
      ::testing::Eq(expected.port));
}
inline auto OnPort(P4RuntimePort expected) {
  return ::testing::ResultOf(
      [](const fourward::OutputPacket& p) -> const std::string& {
        return p.p4rt_egress_port();
      },
      ::testing::Eq(expected.port));
}
inline auto OnPort(uint32_t port) { return OnPort(DataplanePort{port}); }
inline auto OnPort(std::string port) {
  return OnPort(P4RuntimePort{std::move(port)});
}
inline auto OnPort(std::string_view port) {
  return OnPort(P4RuntimePort{std::string(port)});
}

inline auto HasPayload(::testing::Matcher<const std::string&> m) {
  return ::testing::ResultOf(
      [](const fourward::OutputPacket& p) -> const std::string& {
        return p.payload();
      },
      std::move(m));
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
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::ElementsAre(::testing::IsEmpty()), "drop the packet"));
}

template <typename... Ms>
auto OutcomeIs(Ms... ms) {
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::ElementsAre(::testing::UnorderedElementsAre(std::move(ms)...)),
      ""));
}

template <typename M>
auto OutcomeIs(internal::PacketsTag<M> tagged) {
  return ::testing::MakePolymorphicMatcher(internal::OutcomesMatcherBase(
      ::testing::ElementsAre(std::move(tagged.matcher)), ""));
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
  return OutcomeIs(OnPort(std::move(ports))...);
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
// All entries must use the same port type (DataplanePort or P4RuntimePort).
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
    std::map<internal::PortKey, internal::PacketList, internal::PortKeyLess>
        groups;
    // Port type is inferred from the first entry — all entries must use the
    // same type (DataplanePort or P4RuntimePort).
    for (const auto& p : packets) {
      groups[internal::PacketPortKey(p, expected_.front().first)].push_back(p);
    }
    static const internal::PacketList kEmpty;
    for (const auto& [port, matcher] : expected_) {
      auto it = groups.find(port);
      const internal::PacketList& group =
          it != groups.end() ? it->second : kEmpty;
      if (!matcher.Matches(group)) {
        *listener << "on port ";
        internal::PrintPortKey(listener->stream(), port);
        *listener << ": ";
        matcher.DescribeNegationTo(listener->stream());
        return false;
      }
    }
    return true;
  }

  void DescribeTo(std::ostream* os) const {
    *os << "has packets grouped by port matching: ";
    for (size_t i = 0; i < expected_.size(); ++i) {
      if (i > 0) *os << ", ";
      *os << "port ";
      internal::PrintPortKey(os, expected_[i].first);
      *os << " ";
      expected_[i].second.DescribeTo(os);
    }
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

}  // namespace fourward

#endif  // FOURWARD_CC_DATAPLANE_MATCHERS_H_
