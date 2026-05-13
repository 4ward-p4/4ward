---
description: "gtest matchers for asserting on dataplane packet results — composable with gmock's standard vocabulary."
---

# Dataplane matchers

Asserting on packet results with raw proto accessors gets old fast:

```cpp
ASSERT_EQ(response.possible_outcomes_size(), 1);
ASSERT_EQ(response.possible_outcomes(0).packets_size(), 1);
EXPECT_EQ(response.possible_outcomes(0).packets(0).dataplane_egress_port(), 1u);
```

The dataplane matchers let you say what you mean instead:

```cpp
EXPECT_THAT(dataplane.InjectPacket({...}), IsOkAndHolds(ForwardsTo(1)));
```

They compose with gmock's standard vocabulary — `AllOf`, `ElementsAre`,
`Contains`, and friends all work. Here's the full set at a glance:

```
Shorthands   ForwardsTo  Forwards  Drops
Outcomes     OutcomeIs  OutcomesAre  EachOutcome  AnyOutcome  Outcome
Packets      OnPort  HasPayload  OnPorts  Packets
Input        HasIngress
Extraction   PacketsByPort  PacketsByP4RuntimePort
```

## The basics

Most tests only need the shorthands. They work on both
`InjectPacketResponse` (from `InjectPacket`) and `ProcessPacketResult`
(from `ResultStream::Next`):

```cpp
#include "fourward_cc/dataplane_matchers.h"
#include "fourward_cc/dataplane_client.h"

using ::fourward::Drops;
using ::fourward::ForwardsTo;
using ::fourward::HasIngress;

// Inject and assert in one shot:
EXPECT_THAT(dataplane.InjectPacket({...}), IsOkAndHolds(ForwardsTo(1)));
EXPECT_THAT(dataplane.InjectPacket({...}), IsOkAndHolds(ForwardsTo("Ethernet0")));

// Multicast:
EXPECT_THAT(dataplane.InjectPacket({...}), IsOkAndHolds(ForwardsTo(1, 2)));

// Same for stream results:
EXPECT_THAT(stream.Next(),
            IsOkAndHolds(AllOf(ForwardsTo(1), HasIngress(0))));

// Or assign first when you need the value for follow-up work:
ASSERT_OK_AND_ASSIGN(InjectPacketResponse response,
                     dataplane.InjectPacket({...}));
EXPECT_THAT(response, Drops());
```

Ports can be dataplane numbers or P4Runtime IDs — pass them bare for
convenience, or wrap them for explicitness:

```cpp
ForwardsTo(1)                            // dataplane port
ForwardsTo(DataplanePort{1})             // same, explicit
ForwardsTo("Ethernet0")                  // P4Runtime port
ForwardsTo(P4RuntimePort{"Ethernet0"})   // same, explicit
```

## Looking at individual packets

When you need more than "which port?", drop down to the packet-level
matchers. These match on individual `OutputPacket` protos — use them
inside `OutcomeIs` to assert on a single deterministic outcome:

```cpp
using ::fourward::OutcomeIs;
using ::fourward::OnPort;
using ::fourward::HasPayload;

// Port and payload:
EXPECT_THAT(response, OutcomeIs(OnPort(1, expected_bytes)));

// With a payload matcher:
EXPECT_THAT(response, OutcomeIs(OnPort(1, HasPayload(StartsWith("hello")))));

// Multicast — two output packets:
EXPECT_THAT(response, OutcomeIs(OnPort(1), OnPort(2)));
```

`HasPayload` takes any `Matcher<const std::string&>`, so it plays nicely
with `Eq`, `StartsWith`, `ResultOf`, and anything else gmock offers.

`ForwardsTo(port)` is just `OutcomeIs(OnPort(port))`, `Forwards()` is
"forwarded to some port" (without caring which), and `Drops()` is
`OutcomeIs()` (zero packets).

```cpp
// Don't care which port — just that the packet wasn't dropped:
EXPECT_THAT(dataplane.InjectPacket({...}), IsOkAndHolds(Forwards()));
```

## Handling multiple outcomes

P4 programs with action selectors can produce multiple possible outcomes
— each representing one "possible world."

**`OutcomesAre`** pins the exact set of outcomes (order-independent).
Each argument is a packet matcher for a single-packet outcome; use
`Outcome(...)` to spell out a multi-packet outcome:

```cpp
using ::fourward::OutcomesAre;
using ::fourward::Outcome;

// Action selector: forwards to port 1 or port 2.
EXPECT_THAT(response, OutcomesAre(OnPort(1), OnPort(2)));

// One outcome multicasts, the other drops:
EXPECT_THAT(response, OutcomesAre(
    Outcome(OnPort(1), OnPort(2)),
    Outcome()));
```

**`EachOutcome`** and **`AnyOutcome`** quantify when you don't need to
pin the exact set:

```cpp
using ::fourward::EachOutcome;
using ::fourward::AnyOutcome;

// No matter what the selector picks, the packet reaches port 1:
EXPECT_THAT(response, EachOutcome(OnPort(1)));

// At least one branch drops:
EXPECT_THAT(response, AnyOutcome(Packets(IsEmpty())));
```

## Container-level matching with Packets(...)

When you need to match properties of the packet *list* rather than
individual packets — size, port grouping, etc. — wrap the matcher in
`Packets(...)`:

```cpp
using ::fourward::Packets;

// Exactly 7 output packets:
EXPECT_THAT(response, OutcomeIs(Packets(SizeIs(7))));

// At least one output packet on port 1:
EXPECT_THAT(response, OutcomeIs(Packets(Contains(OnPort(1)))));
```

`Packets(...)` works inside `OutcomeIs`, `EachOutcome`, and `AnyOutcome`.

## Grouping by port

`OnPorts` groups packets by egress port and applies per-group matchers.
It doesn't need a `Packets(...)` wrapper — use it directly inside
`OutcomeIs`:

```cpp
using ::fourward::OnPorts;

// 7 copies to port 5, 25 to port 42:
EXPECT_THAT(response, OutcomeIs(OnPorts({
    {DataplanePort{5}, SizeIs(7)},
    {DataplanePort{42}, SizeIs(25)},
})));

// Every output packet carries the same payload:
EXPECT_THAT(response, OutcomeIs(Packets(Each(HasPayload(expected)))));

// Match individual packets per port:
EXPECT_THAT(response, OutcomeIs(OnPorts({
    {DataplanePort{1}, UnorderedElementsAreArray({
        HasPayload(packet_a),
        HasPayload(packet_b),
    })},
    {DataplanePort{2}, ElementsAre(HasPayload(packet_c))},
})));

// Works with P4Runtime ports too:
EXPECT_THAT(response, OutcomeIs(OnPorts({
    {P4RuntimePort{"Ethernet0"}, SizeIs(1)},
    {P4RuntimePort{"Ethernet1"}, SizeIs(1)},
})));
```

## Extracting packets by port

When you need packets in variables for follow-up work — parsing headers,
computing deltas, feeding into helpers — `PacketsByPort` groups the
single deterministic outcome into a `std::map` you can index directly:

```cpp
using ::fourward::PacketsByPort;

auto by_port = PacketsByPort(response);
auto& port1 = by_port[1];
auto& port2 = by_port[2];

EXPECT_THAT(port1, SizeIs(2));
EXPECT_THAT(port2, ElementsAre(HasPayload(expected_bytes)));

// Or do non-matcher work:
auto parsed = ParseHeader(port1[0].payload());
```

For P4Runtime ports, use `PacketsByP4RuntimePort`:

```cpp
using ::fourward::PacketsByP4RuntimePort;

auto by_port = PacketsByP4RuntimePort(response);
auto& eth0 = by_port["Ethernet0"];
auto& eth1 = by_port["Ethernet1"];
```

Both functions fail the test if the response has more than one possible
outcome. Indexing a port that received no packets returns an empty vector.

## Ingress port

`HasIngress` works on `ProcessPacketResult` (which carries the input
packet) and supports both port types:

```cpp
EXPECT_THAT(stream.Next(), IsOkAndHolds(HasIngress(0)));
EXPECT_THAT(stream.Next(), IsOkAndHolds(HasIngress("Ethernet0")));
```

## Composing with packetlib

If your project uses
[packetlib](https://github.com/google/p4-infra/tree/main/packetlib),
you can parse the raw output bytes into a structured `Packet` proto and
assert on header fields — all within the same `EXPECT_THAT`. The bridge
is gmock's `ResultOf`:

```cpp
#include "packetlib/packetlib.h"
#include "gutil/proto_matchers.h"

using ::packetlib::ParsePacket;
using ::testing::ResultOf;

EXPECT_THAT(response, OutcomeIs(
    AllOf(OnPort(1),
          HasPayload(ResultOf(ParsePacket, Partially(EqualsProto(R"pb(
              headers { ethernet_header { ethertype: "0x0800" } }
              headers { ipv4_header { destination_ip: "10.0.0.1" } }
          )pb")))))));
```

If you find yourself writing `HasPayload(ResultOf(ParsePacket, ...))` a
lot, a one-liner in your project saves the boilerplate:

```cpp
template <typename M>
auto HasParsedPayload(M m) {
  return HasPayload(ResultOf(packetlib::ParsePacket, std::move(m)));
}

// Then:
EXPECT_THAT(response, OutcomeIs(OnPort(1), HasParsedPayload(
    Partially(EqualsProto(R"pb(...)pb")))));
```

4ward doesn't depend on packetlib — `HasPayload` just hands its matcher
whatever `ResultOf` returns, so any `bytes → T` parser works the same
way.

## Trace on failure

When an outcome-level matcher fails (`ForwardsTo`, `Forwards`, `Drops`,
`OutcomeIs`, `OutcomesAre`, `EachOutcome`, `AnyOutcome`), the full
simulator trace is automatically appended to the failure message — if the
response carries one. This gives you immediate visibility into how the
packet was processed without rerunning the test:

```
Expected: drop the packet
  Actual: (has 1 possible outcomes),
full trace:
events {
  table_lookup {
    table_name: "ingress.acl"
    hit: true
    ...
  }
}
...
```

No opt-in needed — the trace shows up whenever the response has one.

## Bazel dependency

```starlark
cc_test(
    name = "my_test",
    srcs = ["my_test.cc"],
    deps = [
        "@fourward//fourward_cc:dataplane_matchers",
        # ...
    ],
)
```
