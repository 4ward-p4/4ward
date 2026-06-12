---
description: "4ward gRPC API reference: P4Runtime and Dataplane service RPCs, connection setup, and proto message formats."
---

# gRPC API Reference

4ward exposes two gRPC services on the same port (default: **9559**).

## Server

```sh
bazel run //grpc:fourward_server -- [flags]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--port` | 9559 | gRPC listen port (use `0` to let the kernel assign an ephemeral port; pair with `--port-file` to discover it) |
| `--device-id` | 1 | P4Runtime device ID |
| `--drop-port` | `2^N - 1` | Override drop port (e.g., 511 for 9-bit ports) |
| `--cpu-port` | `2^N - 2` | Override CPU port (e.g., 510 for 9-bit ports; auto-enabled when `@controller_header` is present) |
| `--port-file` | — | After binding, atomically write the listening port to this path. File-exists ≡ ready to serve. Intended for embedders — see [Embedding in C++](embedding-cc.md). |

## P4Runtime service

Standard [P4Runtime](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html) gRPC API (reports version
**1.5.0**). All six RPCs are implemented:

| RPC | Description |
|-----|-------------|
| `SetForwardingPipelineConfig` | Load or replace a P4 pipeline |
| `GetForwardingPipelineConfig` | Retrieve the loaded pipeline |
| `Write` | Insert, modify, or delete entities (table entries, action profiles, clone sessions, multicast groups) |
| `Read` | Query entities (streaming response) |
| `StreamChannel` | Bidirectional stream for master arbitration, PacketOut, and PacketIn |
| `Capabilities` | Report P4Runtime protocol version |

### Arbitration

Multi-controller arbitration is fully supported with role-based access
control. Open a `StreamChannel` and send a `MasterArbitrationUpdate` to
become primary for a role. The highest `election_id` wins.

### Write atomicity

| Mode | Behavior |
|------|----------|
| `CONTINUE_ON_ERROR` | Attempt all updates; report per-update status |
| `ROLLBACK_ON_ERROR` | All-or-none (snapshot-based rollback) |
| `DATAPLANE_ATOMIC` | Same as rollback (the write lock ensures atomicity) |

## Dataplane service

Defined in [`dataplane.proto`](https://github.com/smolkaj/4ward/blob/main/grpc/dataplane.proto).
For packet injection and result observation — not part of the P4Runtime spec.

### `InjectPacket`

Inject a single packet and get the result inline.

**Request:**

```protobuf
message InjectPacketRequest {
  oneof ingress_port {
    uint32 dataplane_ingress_port = 1;  // e.g., 0
    bytes p4rt_ingress_port = 2;        // e.g., "Ethernet0"
  }
  bytes payload = 3;
}
```

The `p4rt_ingress_port` variant requires a loaded pipeline with
[`@p4runtime_translation`](../concepts/type-translation.md) on the port type.

**Response:**

```protobuf
message InjectPacketResponse {
  repeated PacketSet possible_outcomes = 3;   // one entry per possible real execution
  TraceTree trace = 2;                        // P4RT-enriched when translation is available
}

message PacketSet {
  repeated OutputPacket packets = 1;
}
```

The `possible_outcomes` field captures the distinction between
[continuations and choices](../concepts/traces.md#continuations-and-choices).
Each `PacketSet` is one possible set of output packets from a single real
execution. Programs with only continuations (clone, multicast) have exactly
one entry. Programs with action selectors have one entry per alternative.

### `InjectPackets`

Client-streaming RPC for bulk packet injection. Packets are processed
concurrently as they arrive from the stream. Results are **not** returned
in the response — use [`SubscribeResults`](#subscriberesults) to collect them.

```protobuf
rpc InjectPackets(stream InjectPacketRequest) returns (InjectPacketsResponse);
message InjectPacketsResponse {}  // empty — results via SubscribeResults
```

**Recommended pattern for DVaaS / bulk workloads:**

1. Open a `SubscribeResults` stream.
2. Wait for the `SubscriptionActive` message — this confirms the
   subscription is registered and no results will be missed.
3. Send all packets via `InjectPackets`.
4. Collect results from the subscription (exactly one per injected
   packet).

Packets process concurrently across available cores, with trace tree
branches (WCMP groups, multicast, clones) also parallelized within each
packet.

### `SubscribeResults`

Server-streaming RPC that delivers results from all packet sources
(InjectPacket, InjectPackets, PacketOut, etc.).

```protobuf
// First message confirms the subscription.
SubscribeResultsResponse { active: {} }
// Subsequent messages carry results.
SubscribeResultsResponse {
  result: {
    input_packet: { ... }
    trace: { ... }
    possible_outcomes: [ { packets: [ ... ] } ]
  }
}
```

### Matching results to injected packets

Each `ProcessPacketResult` in the `SubscribeResults` stream includes the
full `InputPacket` (ingress port + payload). Match results to injected
packets by comparing the payload bytes.

!!! tip
    For DVaaS workloads, embed a unique tag in each test packet (e.g., in
    an unused header field or the payload body) to make matching
    unambiguous.

**Ordering:** With concurrent processing (`InjectPackets`), results may
arrive in any order. Do not assume the result stream matches the
injection order.

**Completeness:** You will receive exactly one `ProcessPacketResult` per
injected packet. Count results to know when you're done.

**Relationship to P4Runtime PacketIn:** When a packet triggers
copy-to-CPU (e.g., SAI P4's `acl_copy` or `acl_trap`), two things
happen:

- The CPU-bound clone appears as a **PacketIn** on the P4Runtime
  `StreamChannel`.
- The complete result (all outputs including the clone, plus the trace
  tree) appears in **`SubscribeResults`**.

`SubscribeResults` gives the full picture for every packet.
`StreamChannel` PacketIn only carries the CPU-port copies — it's the
standard P4Runtime mechanism for packets punted to the controller.

!!! note "SubscribeResults vs PacketIn"
    **`SubscribeResults`** delivers exactly N results for N injected
    packets, so you always know when you're done.

    **`PacketIn`** on `StreamChannel` is convenient because it carries
    `@controller_header` metadata already parsed — but there's no
    end-of-batch marker. To know when you've seen all PacketIns, count
    CPU-port outputs in `SubscribeResults` — that tells you exactly how
    many PacketIns to expect.

### Dual port encoding

Output packets carry both port representations when translation is available:

```protobuf
message OutputPacket {
  uint32 dataplane_egress_port = 1;  // always present
  bytes p4rt_egress_port = 3;        // only when translated
  bytes payload = 2;
}
```

## Data plane performance

The numbers below cover **data plane** throughput (packet processing).
Control plane operations (table writes, pipeline loading) have not been
optimized — 4ward targets use cases where the control plane sets up
state once, then the data plane processes many packets.

While 4ward optimizes for correctness and observability over raw speed,
it is fast enough for production test workloads like DVaaS. The
following numbers were measured on SAI P4 middleblock with 10k table
entries and 500 ternary ACL entries, on an AMD Ryzen 9 7950X3D (16
cores, 128 MB L3) running OpenJDK 21.

| Workload | Sequential, 1 core | Sequential, 16 cores | Batch, 1 core | Batch, 16 cores |
|----------|--------------------|----------------------|---------------|-----------------|
| L3 forwarding | 2,500 | 2,600 | 2,600 | 29,000 |
| WCMP ×16 members | 2,000 | 2,300 | 1,700 | 13,000 |
| WCMP ×16 + mirror | 1,400 | 1,700 | 1,100 | 9,000 |

"Sequential" means one `InjectPacket` call at a time — send a packet,
wait for the result, repeat. "Batch" uses the `InjectPackets` streaming
RPC to send 1,000 packets concurrently. The "16 cores" columns show the
effect of parallelism: even sequential calls benefit from multi-core
because trace tree branches (WCMP members, clones) within a single packet are
processed in parallel. Batch mode adds a second level of parallelism by
processing multiple packets at once.

The three workloads exercise increasingly complex trace trees. **L3
forwarding** is a straight-line pipeline (VRF, LPM, nexthop, MAC
rewrite) with no branching. **WCMP ×16** adds a 16-member action selector,
producing 16 trace tree branches per packet. **WCMP ×16 + mirror** adds
an ingress clone on top, doubling to 32 branches.

### BMv2 comparison

We ran a head-to-head benchmark against BMv2's `simple_switch` on the
same SAI P4 program with the same table entries (10k LPM routes, 500
ternary ACL entries). BMv2 was compiled with `-O2` and per-packet trace
logging enabled — its analog of 4ward's trace trees.

| Workload | BMv2 | 4ward, 1 core | 4ward, 16 cores |
|----------|------|---------------|-----------------|
| L3 forwarding | 4,500 | 2,500 | 29,000 |
| WCMP ×16 | 4,400 | 2,000 | 13,000 |

BMv2 is faster on single-core sequential throughput — it's a mature C++
codebase and doesn't build trace trees. With concurrent processing,
4ward pulls well ahead. The WCMP ×16 sequential number is additionally
lower because the two simulators do different amounts of work per packet:
BMv2 hashes to one action selector member, while 4ward explores all 16
to build the complete trace tree — that's the whole point.

For full details on the benchmark methodology, build flags, and caveats,
see [PERFORMANCE.md](https://github.com/smolkaj/4ward/blob/main/docs/PERFORMANCE.md).

## Error codes

| Situation | gRPC status |
|-----------|-------------|
| No pipeline loaded | `FAILED_PRECONDITION` |
| P4RT port requested without port translation | `FAILED_PRECONDITION` |
| Invalid request | `INVALID_ARGUMENT` |
| Entity already exists (INSERT) | `ALREADY_EXISTS` |
| Entity not found (MODIFY/DELETE) | `NOT_FOUND` |
| Not primary for role | `PERMISSION_DENIED` |
