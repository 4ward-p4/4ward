# Network-Wide Simulation

**Status: implemented**

## Problem

Simulate a packet through a network of independent 4ward switches and return a
network-level trace, without making single-switch simulation more complex.

The existing multi-device server gives us independent switch instances inside
one 4ward process. Network-wide simulation should build on that: a network layer
owns topology, injects packets into existing devices, follows linked output
ports to downstream devices, and records the full path.

## Principles

1. **Keep switches independent.** A switch should not know about links,
   neighbors, or network topology.

2. **Prefer P4Runtime ports at the topology boundary.** Controllers reason
   about device ports through P4Runtime, so links should usually use the same
   representation. Raw dataplane ports are still useful for simple programs
   without port translation.

3. **Preserve switch trace semantics.** Replication, choice, continuation,
   output, and drop already have precise meanings in `TraceTree`. The network
   layer should compose those trees, not reinterpret switch internals.

4. **Be strict.** Invalid links, unknown devices, missing port translation, and
   forwarding loops should fail loudly with actionable errors.

5. **Keep it simple.** This is an orchestration layer over already-correct
   switch simulation. Do not introduce state sharing or network-level caching
   until measurements prove it is needed.

## Requirements

1. **Topology management.** Add, remove, and list links between switch ports.

2. **Dual port endpoints.** A link endpoint is a `device_id` plus exactly one
   port representation: either P4Runtime port ID bytes or a raw dataplane port.

3. **Network packet injection.** Inject a packet at one endpoint and follow
   linked switch outputs until packets drop, leave the modeled topology, or hit
   a hop limit.

4. **Network trace.** Return the per-hop switch traces and the exact link
   traversals between them.

5. **Unambiguous repeated traffic.** If multiple packets traverse the same
   link, the result must say which concrete switch output produced which
   downstream hop.

6. **No impact on single-device use.** Existing P4Runtime and Dataplane APIs
   remain valid and simple for the common one-switch case.

## Design

Add a new 4ward-native gRPC service:

```proto
package fourward;

service Network {
  rpc AddLinks(AddLinksRequest) returns (AddLinksResponse);
  rpc RemoveLinks(RemoveLinksRequest) returns (RemoveLinksResponse);
  rpc ListLinks(ListLinksRequest) returns (ListLinksResponse);
  rpc InjectPacket(InjectNetworkPacketRequest)
      returns (InjectNetworkPacketResponse);
}
```

`NetworkService` owns topology and uses the existing `DeviceRegistry` to run
packets through devices. `DeviceContext`, `P4RuntimeService`, and
`DataplaneService` stay per-switch.

```
gRPC server
  |- P4RuntimeService ------|
  |- DataplaneService ------|-- DeviceRegistry -- device contexts
  |- ManagementService -----|
  `- NetworkService --------|
          |
          `-- NetworkTopology
```

The topology is outside the simulator. It is not P4Runtime state and should not
be installed through P4Runtime.

## Topology API

Links are bidirectional in the first design. A packet output on either endpoint
enters the other endpoint.

```proto
message NetworkPort {
  uint64 device_id = 1;
  oneof port {
    // Preferred for controller-facing tests. Requires port translation when a
    // packet enters through this endpoint or when an output is matched by it.
    bytes p4rt_port = 2;

    // Raw simulator port. Useful for simple programs without P4Runtime port
    // translation.
    uint32 dataplane_port = 3;
  }
}

message Link {
  NetworkPort a = 1;
  NetworkPort b = 2;
}

message AddLinksRequest {
  repeated Link links = 1;
}

message AddLinksResponse {}

message RemoveLinksRequest {
  repeated Link links = 1;
}

message RemoveLinksResponse {}

message ListLinksRequest {}

message ListLinksResponse {
  repeated Link links = 1;
}
```

Rules:

- `device_id` must be nonzero and must name a live 4ward device.
- Exactly one port representation must be set.
- `p4rt_port` must be nonempty when selected.
- An endpoint may have at most one link.
- `AddLinks` and `RemoveLinks` are atomic: if any link is invalid, the topology
  is unchanged.
- `AddLinks` does not require the target devices to have loaded pipelines.
  Topology can be configured before P4Runtime programming. P4Runtime port
  translation is checked when a packet actually traverses a P4Runtime endpoint.

## Packet Injection API

```proto
message InjectNetworkPacketRequest {
  NetworkPort ingress = 1;
  bytes payload = 2;

  // Required safety bound for loops in the modeled topology.
  uint32 max_hops = 3;

  // Opaque caller-provided tag, copied into the first switch injection.
  int64 tag = 4;
}

message InjectNetworkPacketResponse {
  NetworkTrace trace = 1;
  repeated NetworkOutcome possible_outcomes = 2;
}

message NetworkOutcome {
  repeated NetworkEgressPacket packets = 1;
}
```

`max_hops` is part of correctness, not performance tuning. Real networks can
loop. The simulator must stop deterministically instead of recursing forever.

## Network Trace

Each network hop contains the exact switch trace produced by one device. Link
traversals attach downstream work to concrete output leaves of that switch
trace.

```proto
message NetworkTrace {
  NetworkHop root = 1;
}

message NetworkHop {
  uint64 device_id = 1;
  NetworkPort ingress = 2;
  fourward.TraceTree switch_trace = 3;

  // One record per terminal Output leaf in switch_trace.
  repeated LinkTraversal traversals = 4;
}

message LinkTraversal {
  // Deterministic, hop-local ID assigned while walking switch_trace output
  // leaves in depth-first order.
  uint64 source_output_id = 1;

  fourward.OutputPacket source_output = 2;

  oneof result {
    NetworkHop next_hop = 3;
    NetworkEgressPacket network_egress = 4;
    HopLimitExceeded hop_limit_exceeded = 5;
  }
}

message NetworkEgressPacket {
  NetworkPort egress = 1;
  bytes payload = 2;
}

message HopLimitExceeded {
  NetworkPort next_ingress = 1;
}
```

This makes repeated traffic explicit. If two packets exit the same switch on the
same link, the response has two `LinkTraversal` records. Each record carries
the concrete output packet and, if the link is connected, the downstream hop
caused by that packet.

`source_output_id` does not require changing `TraceTree`. It is assigned by the
network layer using a deterministic walk of the returned switch trace. A UI or
client that wants to highlight the exact leaf can reproduce the same walk.

## Trace Semantics

The network layer follows only terminal `Output` leaves:

| Switch trace leaf | Network behavior |
|-------------------|------------------|
| `Drop` | Packet path terminates. |
| `Output` with exactly one matching configured link | Inject the output payload into the peer endpoint. |
| `Output` without a matching configured link | Record a network egress packet. |
| `Output` with multiple matching configured links | Fail with an ambiguous-topology error. |
| `Replication` | Follow every output leaf in every branch. |
| `Choice` | Preserve each branch as a possible outcome; do not combine downstream paths across alternatives. |
| `Continuation` | Continue walking the nested trace until terminal leaves are reached. |

The response mirrors the existing Dataplane API: `trace` is the structured
record, and `possible_outcomes` is a convenience projection for callers that
only need final packets leaving the modeled network.

## Possible Outcomes

`possible_outcomes` is derived from the network trace:

- `Replication` combines packets in the same possible outcome.
- `Choice` creates alternative possible outcomes.
- Downstream `Choice` nodes multiply with upstream choices.
- Only packets that leave the modeled topology appear in the final outcomes.

This matches the existing Dataplane meaning, lifted from one switch to the
whole network: the outer collection is a set of distinct outcomes, while each
outcome is a multiset of packets.

Possible outcomes can converge. For example, two upstream choice branches may
emit the same packet into the same downstream endpoint, producing the same
network suffix. The first design intentionally does not memoize those suffixes.
Memoization is only correct if the suffix is pure with respect to the topology,
device state, hooks, counters, registers, and remaining hop budget. Recomputing
keeps the execution model obvious; suffix sharing can be a later optimization
if a measured workload needs it and the purity contract is explicit.

## Port Resolution

Topology endpoints may use either P4Runtime or dataplane ports. The network
service resolves ports at packet processing time:

1. Resolve the ingress `NetworkPort` to the device's dataplane ingress port.
   Dataplane endpoints are already resolved. P4Runtime endpoints use the
   device's loaded pipeline and port translation.
2. Inject into that device through the existing dataplane path.
3. Read each enriched output's dataplane egress port and, if available,
   P4Runtime egress port.
4. Look up all topology endpoints that match the output:
   `(device_id, dataplane_egress_port)` always, plus
   `(device_id, p4rt_egress_port)` when the output has a P4Runtime encoding.
5. If exactly one link matches, resolve the peer endpoint to that device's
   dataplane ingress port before the next hop.

If a required translation is unavailable, the RPC fails with
`FAILED_PRECONDITION` and names the device and P4Runtime port. Typical causes:
no pipeline is loaded, the port type has no `@p4runtime_translation`, or the
specific P4Runtime port value has no mapping.

If an output matches both a dataplane-port link and a P4Runtime-port link, the
RPC fails with `FAILED_PRECONDITION` and names the matching endpoints. This is
stricter than choosing an arbitrary winner and avoids topology bugs that only
appear after a pipeline starts enriching outputs with P4Runtime ports.

## Algorithm

Conceptually:

```text
simulate(ingress, payload, remaining_hops):
  if remaining_hops == 0:
    return HopLimitExceeded(next_ingress)

  resolve ingress to a dataplane port
  inject packet into device through existing dataplane code
  create NetworkHop with the returned switch TraceTree

  for each Output leaf in the switch TraceTree:
    assign the next source_output_id
    find topology links matching the output's dataplane and P4Runtime ports
    if no topology link matches:
      attach NetworkEgressPacket
    else if more than one topology link matches:
      fail with ambiguous topology
    else:
      attach simulate(peer_endpoint, output.payload, remaining_hops - 1)

  return NetworkHop
```

The implementation should call in-process services or a small extracted helper,
not loop back through gRPC. The important boundary is semantic: network
simulation reuses the same dataplane behavior as `InjectPacket`.

## Error Model

| Case | Status |
|------|--------|
| `device_id = 0` in `NetworkPort` | `INVALID_ARGUMENT` |
| unknown device ID | `NOT_FOUND` |
| no port set in `NetworkPort` | `INVALID_ARGUMENT` |
| empty selected `p4rt_port` | `INVALID_ARGUMENT` |
| endpoint already linked | `ALREADY_EXISTS` |
| removing an absent link | `NOT_FOUND` |
| malformed asymmetric remove request | `INVALID_ARGUMENT` |
| duplicate endpoint in a bulk add/remove request | `INVALID_ARGUMENT` |
| missing pipeline or port translation during injection | `FAILED_PRECONDITION` |
| output matches both a dataplane-port link and a P4Runtime-port link | `FAILED_PRECONDITION` |
| `max_hops = 0` in request | `INVALID_ARGUMENT` |

Messages should name the operation, device ID, and selected port encoding.

## Concurrency

`NetworkTopology` needs its own small lock. Packet injection should read a
stable topology snapshot at the start of the RPC and use that snapshot for the
whole network trace. That avoids a packet seeing half of a topology update.

Per-switch packet processing continues to use each device's existing
`PacketBroker` and write mutex. Independent devices do not need a network-wide
packet lock.

## C++ API

As with the Dataplane and Management services, the C++ embedding API should
provide a thin client wrapper over the network gRPC service. The wrapper should
expose the same link management and packet injection concepts while using C++
types for `NetworkPort`, `Link`, and `NetworkTrace`.

The wrapper should not reimplement topology or packet traversal locally. The
server remains the source of truth.

## Testing

Tests should prove the contracts that are easy to break:

- link add/remove/list validation and atomicity
- injection through one link between two devices
- no-link output becomes network egress
- multiple packets on the same link produce distinct `LinkTraversal` records
- dataplane-port links work without a loaded pipeline
- P4Runtime-port links use the loaded pipeline's port translation
- outputs matching both a dataplane-port link and a P4Runtime-port link fail
  loudly
- multicast/clone output follows all linked output leaves
- action-selector `Choice` preserves separate possible outcomes, and identical
  outcomes collapse
- hop limit stops loops deterministically
- missing P4Runtime port translation produces an actionable error
- existing Dataplane and P4Runtime single-device tests remain unchanged

## Future Work

- Unidirectional links, if a real workflow needs them.
- Sparse bulk topology updates for very large lab topologies.
- Network-level reproducers that bundle topology plus the per-device P4Runtime
  state needed to replay a network trace.
