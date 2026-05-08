# Packet I/O Design

**Status: implemented** — later revised: `InputPacket`/`OutputPacket` were
unified into `simulator.proto` and all proto packages were flattened to
`package fourward` (#620).

## Assumptions

- There is a designated **CPU port** — see
  [intrinsic_ports.md](intrinsic_ports.md) for details.

## Requirements

1. **The data plane (simulator) processes packets.** A packet enters on a port,
   goes through the pipeline, and may produce zero or more output packets on
   various ports.

2. **The CPU port connects data plane (simulator) to control plane (P4Runtime).**
   Packets exiting on the CPU port become PacketIn. PacketOut produces packets
   entering on the CPU port.

3. **No output packets are lost.** All output packets are deliverable/observable,
   regardless of egress port or how the input packet was injected.

4. **All output packets from PacketOut are observable.** When a PacketOut
   produces output on data-plane ports, those packets must be visible to
   data-plane observers — not silently dropped.

5. **Completion is observable.** After injecting a packet — whether directly on
   the data plane or indirectly via PacketOut — the injector can determine
   when it has received all output packets produced as a result of that input
   packet.
   - *Motivation*: BMv2 does not have this property — the question "which
     outputs were produced as a result of my input?" is subject to race
     conditions, making deterministic testing difficult. As a testing and
     development tool, 4ward must do better.

6. **Strictly P4Runtime compliant.** The StreamChannel PacketIn/PacketOut
   behavior must conform to the
   [P4Runtime spec](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html).
   Non-standard extensions (DataplaneService) are additive — they must not
   alter the spec-defined behavior.

## Design

### Dataplane gRPC service

The Dataplane service is redesigned around two independent RPCs — one for
injection, one for observation:

Defined in `p4runtime/dataplane.proto`; `InputPacket`, `OutputPacket`, and
`TraceTree` are shared types from `simulator/simulator.proto`.

```proto
service Dataplane {
  // Inject a single packet into the data plane. Returns the result inline.
  rpc InjectPacket(InjectPacketRequest) returns (InjectPacketResponse);

  // Observe results from ALL injection sources (InjectPacket, PacketOut,
  // other callers). Each result bundles the input with all its outputs.
  rpc SubscribeResults(SubscribeResultsRequest)
      returns (stream SubscribeResultsResponse);
}

message InputPacket {
  uint32 ingress_port = 1;
  bytes payload = 2;
}

message OutputPacket {
  uint32 egress_port = 1;
  bytes payload = 2;
}

message InjectPacketRequest {
  InputPacket packet = 1;
}

message InjectPacketResponse {
  repeated OutputPacket output_packets = 1;
  TraceTree trace = 2;
}

message ProcessPacketResult {
  InputPacket input = 1;
  repeated OutputPacket output_packets = 2;
  TraceTree trace = 3;
}

message SubscribeResultsRequest {}

message SubscribeResultsResponse {
  oneof event {
    // Sent exactly once as the first message. Confirms the subscription
    // is registered and no results will be missed. The client must wait
    // for this before injecting packets.
    SubscriptionActive active = 1;
    ProcessPacketResult result = 2;
  }
}

message SubscriptionActive {}
```

`InjectPacket` is a simple unary RPC that returns the result inline. The
simulator is synchronous, so the result is available before the RPC returns.
This makes the simple case — inject a packet, see what happened — trivial,
with no need for a `SubscribeResults` subscription. The same result is also
delivered to `SubscribeResults` subscribers for fan-out.

There are two result shapes: `InjectPacketResponse` omits the input (the
caller already has it), while `ProcessPacketResult` includes it (subscribers
need to know which input produced the result).

Key properties:

- **Input-output correlation**: each `ProcessPacketResult` bundles the input
  packet with all its outputs and the trace tree. No ambiguity about which
  outputs belong to which input.
- **Injection and observation are decoupled**: inject via `InjectPacket`,
  observe via `SubscribeResults`. They are independent — you can subscribe
  without injecting, or inject without subscribing.
- **Fan-out**: multiple independent subscribers can observe results
  concurrently (e.g., a test harness and the web UI).
- **All sources visible**: `SubscribeResults` delivers results from all
  injection sources — `InjectPacket`, StreamChannel PacketOut, and any
  future sources.

#### Subscription handshake

The `SubscriptionActive` message solves a race condition: the client must
know the subscription is registered before injecting packets (via any source),
otherwise results could be missed. The protocol contract is: exactly one
`SubscriptionActive` first, then zero or more `ProcessPacketResult`. This
ordering is documented but cannot be expressed in the proto type system.

### PacketBroker

Fan-out layer between packet sources and the simulator. All callers —
`DataplaneService.injectPacket`, `P4RuntimeService` PacketOut — go through
`broker.processPacket()`. The broker delegates to the simulator and delivers
results to all subscribers.

The broker has no knowledge of CPU ports, PacketIn, or P4Runtime concepts —
it is a generic fan-out mechanism. Each consumer subscribes and decides what
to do with the results:

- `DataplaneService` subscribes to power `SubscribeResults`.
- Each active `streamChannel` subscribes to filter CPU-port outputs into
  PacketIn (using pipeline state that only `P4RuntimeService` has).

```
  P4Runtime StreamChannel                   Dataplane gRPC
  (control plane)                           (data plane)
        │                                         │
        │ PacketOut                  InjectPacket │
        ▼                                         ▼
  ┌─────────────────────────────────────────────────────┐
  │                    PacketBroker                     │
  │                                                     │
  │  1. Call simulator.processPacket()                  │
  │  2. Deliver result to all subscribers               │
  │  3. Return result to caller                         │
  └─────────────────────┬───────────────────────────────┘
                        │
              simulator.processPacket()
```

`broker.processPacket(ingressPort, payload)` does:

1. Call `simulator.processPacket(ingressPort, payload)`.
2. Deliver the result to all subscribers (synchronously, during this call).
3. Return the outputs and trace to the caller.

### PacketOut

1. Controller sends PacketOut on the StreamChannel.
2. `handlePacketOut` translates metadata and serializes the packet header.
3. `handlePacketOut` calls `broker.processPacket(cpuPort, payload)`.
4. During the broker call, all subscribers fire synchronously:
   - The `streamChannel` subscriber filters CPU-port outputs into PacketIn
     and delivers them on the stream (requirement 6 — P4Runtime spec §16.1).
   - The `DataplaneService` subscriber delivers the result to
     `SubscribeResults` clients (requirement 4 — PacketOut outputs visible
     to data-plane observers).
5. The broker call returns. All outputs have been dispatched.

### PacketIn

PacketIn is not tied to PacketOut. It is a side effect of *any*
`broker.processPacket()` call that produces output on the CPU port:

- A data-plane injection produces a punt to CPU port → controller receives
  PacketIn on StreamChannel.
- A PacketOut produces a clone to CPU → same path.

When no StreamChannel is active, CPU-port outputs are silently dropped.

Implementation: each active `streamChannel` subscribes to the broker. The
subscription callback filters CPU-port outputs, builds PacketIn metadata via
`PacketHeaderCodec`, translates via `TypeTranslator`, and delivers PacketIn
on the stream. Per P4Runtime spec §16.1, PacketIn is sent to all controllers
with an open stream.

### Completion

The simulator is synchronous: `processPacket()` runs the full pipeline and
returns all output packets atomically.

- **`SubscribeResults` subscriber**: receives a `ProcessPacketResult` with
  all outputs bundled. Receiving the message IS the completion signal.
- **StreamChannel PacketOut**: PacketIn messages are delivered on the stream.
  Data-plane outputs are delivered to `SubscribeResults` subscribers.
  The controller itself has no completion signal for data-plane outputs —
  but a test harness subscribed via `SubscribeResults` does.
- **Direct injection via `InjectPacket`**: the RPC response contains the
  outputs and trace directly. Completion is implicit — when the call
  returns, all outputs have been produced.

### What changes where

| Component | Today | After |
|---|---|---|
| `Dataplane` service | Two unary RPCs (`ProcessPacket`, `ProcessPacketWithTraceTree`) | Unary `InjectPacket` + server-streaming `SubscribeResults` |
| `P4RuntimeService` | Calls simulator directly; wraps all outputs as PacketIn | Calls broker; filters CPU-port outputs into PacketIn |
| `PacketBroker` | Does not exist | New: wraps simulator, fan-out to SubscribeResults subscribers |
| `PacketHeaderCodec` | Owned by P4RuntimeService | No change — stays in P4RuntimeService |
| `TypeTranslator` | Owned by P4RuntimeService | No change — stays in P4RuntimeService |
| `Simulator` | No changes | No changes |
