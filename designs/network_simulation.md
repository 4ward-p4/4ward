# Network Simulation Design

**Status: proposal**

## Motivation

4ward simulates a single P4 switch. But P4 programs don't run in isolation —
they run in networks where packets traverse multiple switches, each running
its own P4 pipeline. Bugs in multi-hop forwarding, TTL handling, tunnel
encap/decap across switches, and ECMP convergence are invisible to a
single-switch simulator.

A native network simulation composes multiple `Simulator` instances with a
topology graph, preserving 4ward's core strengths — determinism,
reproducibility, and full trace trees — across switch boundaries. This is
something no existing tool offers: mininet gives you real network stacks but
sacrifices determinism and observability; BMv2 has no cross-switch trace
support at all.

## Goals

1. **Deterministic.** Same topology + same programs + same table entries + same
   input packets = same output packets, same trace, every time.

2. **Inspectable.** Full trace trees across switch boundaries — follow a packet
   from ingress on switch A through egress, across a link, through switch B's
   pipeline, and out the other side.

3. **Composable.** Each switch is an unmodified `Simulator` instance. The
   network layer calls `processPacket` — it doesn't reach into simulator
   internals. Individual switches can still be tested in isolation.

4. **Simple.** Static point-to-point links, instant delivery, FIFO per link.
   No timing model, no link failures, no dynamic topology changes. These are
   future extensions, not v1 requirements.

## Non-goals

- **Control plane simulation.** 4ward simulates the data plane. Each switch is
  programmed independently (tables, PRE, action profiles) before simulation
  begins. Controller orchestration is out of scope.

- **Mininet integration.** 4ward already speaks P4Runtime — it can be used as a
  switch backend in mininet today. That's a deployment mode, not a feature that
  needs design work here.

- **Performance.** This is a correctness tool. The network simulator
  prioritizes clarity and determinism over throughput.

## Core abstractions

### Topology

A topology is a set of **switches** and **links**. Each switch has an ID and a
set of ports. Each link connects exactly one port on one switch to exactly one
port on another switch (point-to-point, full-duplex). Ports not connected to a
link are **edge ports** — packets egressing on them leave the network and are
observable as test output.

```
message NetworkTopology {
  repeated Switch switches = 1;
  repeated Link links = 2;

  message Switch {
    string id = 1;
    // Ports are implicit — any port number used in a link or in a
    // table entry is valid. No need to declare them up front.
  }

  message Link {
    Endpoint a = 1;
    Endpoint b = 2;
  }

  message Endpoint {
    string switch_id = 1;
    uint32 port = 2;
  }
}
```

Links are bidirectional: a packet egressing on endpoint A's port arrives at
endpoint B's port, and vice versa.

### Per-switch configuration

There is no bundled "network config" proto. Each switch is configured the same
way a single switch is today — pipeline loaded via `SetForwardingPipelineConfig`,
entries written via `Write`, or via STF-style directives in test files. The
network simulator only adds the topology; per-switch configuration uses existing
mechanisms. Switches may run different P4 programs.

### Packet processing

The network simulator processes one input packet at a time, following it
through the network until it either exits on an edge port, is dropped, or
exceeds the hop limit:

```
processPacket(switch_id, ingress_port, payload) -> NetworkHop:
    return processHop(switch_id, ingress_port, payload, hop_count=0)

processHop(switch_id, ingress_port, payload, hop_count) -> NetworkHop:
    if hop_count >= MAX_HOP_COUNT:
        error("routing loop")

    result = simulators[switch_id].processPacket(ingress_port, payload)

    edge_outputs = []
    next_hops = []
    for output in result.outputs:
        link = topology.lookup(switch_id, output.port)
        if link exists:
            // Internal link — follow the packet recursively.
            next_hops.append(
                processHop(link.dst_switch, link.dst_port,
                           output.payload, hop_count + 1))
        else:
            // Edge port — packet leaves the network.
            edge_outputs.append(EdgeOutput(switch_id, output.port,
                                           output.payload))

    return NetworkHop(switch_id, ingress_port, payload,
                      result.trace, edge_outputs, next_hops)
```

Key properties:

- **Recursive depth-first traversal.** Each output that crosses an internal
  link is followed immediately, building the `NetworkHop` tree naturally. Since
  delivery is instant and switches have no shared mutable state, traversal
  order does not affect results — any deterministic policy produces the same
  output. DFS is the natural fit for building a tree.

- **Hop limit.** `MAX_HOP_COUNT` (e.g., 64) prevents infinite loops from
  routing misconfigurations. When exceeded, simulation fails with an error.
  This is a simulator safety net, not a P4 TTL — the P4 program's own TTL
  handling is orthogonal.

### Cross-switch trace trees

A `NetworkTrace` chains per-switch trace trees into a full path:

```
message NetworkTrace {
  Hop root = 1;

  message Hop {
    string switch_id = 1;
    uint32 ingress_port = 2;
    bytes ingress_payload = 3;
    TraceTree trace = 4;              // Existing per-switch trace.
    repeated EdgeOutput edge_outputs = 5;  // Leaves: packets exiting the network.
    repeated Hop next_hops = 6;            // Children: packets forwarded via internal links.
  }

  message EdgeOutput {
    string switch_id = 1;
    uint32 egress_port = 2;
    bytes payload = 3;
  }
}
```

The trace is a tree, not a list — a packet can fan out at any switch
(multicast, clone), and each copy may traverse further switches. Each `Hop`
records one switch processing one packet: what it received (`ingress_payload`),
the full per-switch trace tree, and the results. Outputs on internal links
become child hops; outputs on edge ports are leaves (`EdgeOutput`).

## Test format

Network tests extend the existing STF-style format with topology and
multi-switch awareness. Strawman syntax:

```
# Define topology.
switch s1
switch s2
link s1:1 <-> s2:1

# Load programs.
pipeline s1 my_router.p4.pb.txt
pipeline s2 my_router.p4.pb.txt

# Program tables.
s1: add my_table 10.0.1.0/24 => forward(2)
s1: add my_table 10.0.2.0/24 => forward(1)    # toward s2
s2: add my_table 10.0.1.0/24 => forward(1)    # toward s1
s2: add my_table 10.0.2.0/24 => forward(2)

# Inject and expect.
packet s1:2 <hex payload>           # inject at s1 port 2
expect s2:2 <hex payload>           # expect at s2 port 2 (edge)
```

This is intentionally minimal. The details of the test format deserve their
own design iteration once the core simulation works.

## P4Runtime

Each switch gets its own `FourwardServer` instance, listening on a separate
port. This matches real deployments and requires no changes to the existing
P4Runtime layer. A network-level CLI command starts all servers:

```sh
4ward network run --topology network.pb.txt
# Starts P4Runtime servers:
#   s1 -> localhost:9559
#   s2 -> localhost:9560
#   ...
```

The Dataplane gRPC service extends naturally: `InjectPacket` on one switch may
produce `SubscribeResults` events on other switches (via internal links). The
network simulator sits between the per-switch `PacketBroker` instances,
forwarding outputs across links.

## What changes, what doesn't

| Component | Changes? | Notes |
|-----------|----------|-------|
| `Simulator` | No | Called via existing `processPacket` API |
| `Architecture` | No | Per-switch concern |
| `ir.proto` | No | Per-switch IR, unchanged |
| `simulator.proto` | Extend | Add `NetworkTrace`, `NetworkTopology` messages |
| `PacketBroker` | Extend | Route cross-link packets to destination switch's broker |
| `FourwardServer` | No | One instance per switch, unchanged |
| `DataplaneService` | Extend | Cross-switch packet delivery |
| STF runner | Extend | Parse network topology + multi-switch commands |
| CLI | Extend | `4ward network` subcommand |

## Scale target

**~100 switches** in a single JVM. This covers leaf-spine fabrics, small
campus networks, and most topologies people would realistically test P4
programs against.

Each `Simulator` is lightweight — a tree-walking interpreter holding table
entries and counters in memory, with no per-switch thread or background
processing. The main scaling concern is P4Runtime: each switch gets its own
gRPC server (Netty event loop, file descriptors). At 100 switches this is
fine; at 1000+ it would need attention (lazy server startup, connection
pooling, or a multiplexed server).

Scaling to ~1,000 and ~10,000 switches is future work, not precluded by the
design but not a v1 target.

## Future extensions

These are explicitly out of scope for v1 but should not be precluded by the
design:

- **Link properties.** Latency, bandwidth limits, packet loss. Would replace
  "instant delivery" with a priority queue keyed by delivery time.
- **Dynamic topology.** Link up/down events during simulation, for testing
  failover behavior.
- **LAG / port channels.** Multiple physical links bundled into one logical
  link. Requires a hashing model.
- **Traffic generators.** Synthetic host traffic (ARP, LLDP, background flows)
  injected at edge ports, for more realistic scenarios.

## Open questions

1. **Proto vs. text for topology.** The strawman above uses proto messages for
   the topology. Alternatively, a simple text format (like DOT or a custom DSL)
   might be more ergonomic for small topologies. The test format sketch above
   leans toward inline text. Worth prototyping both.

2. **Fork semantics across switches.** A single-switch `processPacket` can
   return multiple "possible worlds" (alternative forks from action selectors).
   When a packet traverses multiple switches, each with action selectors, the
   number of possible worlds multiplies. Is the Cartesian product the right
   semantics, or should the network simulator pick one world per switch? The
   Cartesian product is more faithful but may be expensive for large networks.

3. **Scope of the first implementation.** A minimal walking skeleton could be:
   two switches, one link, one shared P4 program, hardcoded topology in a unit
   test. No CLI, no proto, no test format — just the core
   `NetworkSimulator.processPacket` loop with a cross-switch trace tree. Ship
   that, then iterate.
