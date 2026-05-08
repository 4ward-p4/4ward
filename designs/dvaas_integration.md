# DVaaS Integration Design

**Status: in progress**

## Goal

4ward replaces BMv2 as the reference simulator in
[DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas). The
integration is upstreamed into sonic-pins. DVaaS gains a better reference
model; sonic-pins gains a hermetic `bazel_dep` that just works — no Docker, no
system packages, no BMv2 build headaches.

## Context: how DVaaS works today

DVaaS validates switch data-plane behavior by comparing a switch under test
(SUT) against a reference P4 simulator:

```
                 synthesized       ┌─────────────┐
                 packets ──────────▶  SUT switch  ├──▶ actual output
                    │               └─────────────┘
  P4 program +      │               ┌─────────────┐
  table entries ────┤               │  reference   │
                    └──────────────▶│  simulator   ├──▶ expected output
                                    │  (BMv2)      │
                                    └─────────────┘
                                            │
                                     compare ──▶ pass/fail
```

DVaaS uses the reference simulator for two things:

1. **Output prediction.** Inject a packet, observe what comes out. The
   simulator's output becomes the expected output for the SUT.
2. **Packet traces.** Record which tables were hit, which actions fired, where
   the packet was dropped or transmitted. Used for failure analysis and test
   minimization.

Both of these happen through a
[`DataplaneValidationBackend`](https://github.com/sonic-net/sonic-pins/blob/main/dvaas/dataplane_validation.h)
abstraction. This abstraction exists because BMv2 is hard to build — it shields
DVaaS from that complexity. The non-upstream backend wraps BMv2 behind this
interface.

## Context: what 4ward provides

4ward already exposes everything DVaaS needs via gRPC:

- **Output prediction:** `InjectPacket` RPC on the Dataplane service. Send a
  packet in, get output packets back.
- **Packet traces:** `InjectPacketResponse` includes a `TraceTree` — a
  structured proto recording every table lookup, action execution, fork, and
  packet fate.
- **P4Runtime:** standard P4Runtime gRPC for pipeline config, table entries,
  reads, and PacketIn/PacketOut.

4ward's build is fully hermetic (Bazel). No system dependencies, no Docker. It
runs on Linux and macOS, x86_64 and ARM64.

DVaaS also needs gNMI for port discovery, but that's a sonic-pins concern — see
[gNMI](#gnmi-port-discovery) below.

## Design: frontend integration

Since 4ward has a hermetic build, we don't need the `DataplaneValidationBackend`
indirection. Output prediction and trace prediction move into the DVaaS
**frontend** (`dataplane_validation.cc`) directly:

- DVaaS checks whether a 4ward pipeline config is available.
- If yes, DVaaS spawns a 4ward subprocess and uses it for output prediction and
  traces — no backend needed.
- If no, the existing backend path (BMv2) works unchanged.

This makes the integration **opt-in and backward compatible**. The existing
`DataplaneValidationBackend` stays untouched — BMv2 users keep using it. The
4ward code path lives entirely in the frontend, gated on `fourward_config`
presence. The change is purely additive — it can be upstreamed without touching
any existing backend, test, or workflow.

### P4Specification gains a 4ward config

Today, `P4Specification` carries two compiled configs:

```cpp
struct P4Specification {
  ForwardingPipelineConfig p4_symbolic_config;  // for packet synthesis
  ForwardingPipelineConfig bmv2_config;         // for BMv2 output prediction
};
```

We add a third:

```cpp
struct P4Specification {
  ForwardingPipelineConfig p4_symbolic_config;  // for packet synthesis
  ForwardingPipelineConfig bmv2_config;         // for BMv2 output prediction
  std::optional<ForwardingPipelineConfig> fourward_config;  // for 4ward
};
```

Same type as the other two — `ForwardingPipelineConfig`. The `p4info` field
carries the P4Info (shared across all compilers for the same P4 program), and
`p4_device_config` carries 4ward's compiled `Pipeline` proto as serialized
bytes, just as it carries BMv2 JSON for `bmv2_config`. This keeps 4ward
dependencies out of the core DVaaS types.

When `fourward_config` is present, DVaaS uses 4ward instead of BMv2 for output
prediction and traces.

All three configs are produced at **build time** by their respective compilers.
No runtime compilation.

### Subprocess lifecycle

`FourwardServer` manages the 4ward subprocess:

1. **Spawn.** Fork/exec the 4ward P4Runtime server binary (resolved from
   Bazel runfiles) with `--port=0`.
2. **Detect readiness.** Parse the actual port from the server's startup
   banner on stdout.
3. **Tear down.** SIGTERM on destruction (SIGKILL after 5s).

`FourwardOracle` builds on top of a `FourwardServer`:

1. **Start server.** Creates a `FourwardServer` instance.
2. **Establish session.** Opens a P4Runtime session (arbitration).
3. **Load pipeline.** `SetForwardingPipelineConfig` with the pre-compiled
   4ward pipeline config (from `P4Specification.fourward_config`).
4. **Predict.** Install table entries via P4Runtime Write. Predict outputs
   via the streaming `InjectPackets` + `SubscribeResults` RPCs. Traces are
   included in each result.

The `FourwardMirrorTestbed` (development vehicle, see below) uses *two*
`FourwardServer` instances connected by a `PacketBridge` — a separate
concern from the oracle.

### Output prediction

Inject tagged packets via the streaming `InjectPackets` RPC, collect output
packets and traces from `SubscribeResults`. The `dataplane_validation.cc`
frontend gets a 4ward code path that talks directly to the `FourwardOracle`
— no backend involved for prediction. Ports use dual encoding (dataplane
`uint32` + P4RT `bytes`), which 4ward supports natively (see
[dataplane_port_encoding.md](dataplane_port_encoding.md)).

### Traces: TraceTree → PacketTrace conversion

4ward returns a `TraceTree` (recursive, with forks for non-deterministic choice
points). DVaaS consumes `PacketTrace` (`dvaas/packet_trace.proto` — flat list
of events). We convert:

| 4ward `TraceEvent`          | DVaaS `Event`          | Notes |
|-----------------------------|------------------------|-------|
| `TableLookupEvent` (hit)    | `TableApply` → `Hit`   | PI `TableEntry` → IR `IrTableEntry` via pdpi |
| `TableLookupEvent` (miss)   | `TableApply` → `Miss`  | |
| `MarkToDropEvent`           | `MarkToDrop`           | Source location from `source_info` |
| `CloneEvent` / `Fork(CLONE)`| `PacketReplication`    | |
| `Fork(MULTICAST)`           | `PacketReplication`    | |
| `PacketOutcome::Drop`       | `Drop`                 | Pipeline name from enclosing `PipelineStageEvent` |
| `PacketOutcome::Output`     | `Transmit`             | Port + packet size |

The `bmv2_textual_log` field in `PacketTrace` is left empty — it's
BMv2-specific.

For forks (non-deterministic traces), we flatten the tree: follow the branch
whose output matches the actual SUT output, and emit events from that path.
This is sufficient for DVaaS's current trace consumers (failure analysis, test
minimization, Arriba test vectors). In the future, DVaaS could consume the full
`TraceTree` directly for richer analysis.

This conversion lives in the DVaaS frontend (`dataplane_validation.cc`),
alongside the output prediction code path.

### Packet synthesis

Unchanged. p4-symbolic handles packet synthesis today and continues to do so.
4ward is only the reference model (output prediction + traces), not the packet
generator.

## 4ward deliverables as a `bazel_dep`

sonic-pins declares 4ward as a Bazel module dependency via `git_override`
(BCR registration to follow):

```starlark
# sonic-pins MODULE.bazel
bazel_dep(name = "fourward", version = "0.0.0")
git_override(
    module_name = "fourward",
    remote = "https://github.com/smolkaj/4ward.git",
    commit = "...",
)
```

4ward provides three things:

### 1. p4c backend plugin

Compiles P4 source → 4ward `Pipeline` proto. Runs at **build time**.

```starlark
# Example: compile SAI P4 middleblock for 4ward
fourward_compile(
    name = "sai_middleblock_fourward",
    src = "//sai_p4/instantiations/google:middleblock.p4",
)
```

This produces the `fourward_config` that gets bundled into `P4Specification`.
Analogous to how sonic-pins already compiles P4 to BMv2 JSON at build time.

### 2. P4Runtime server binary

A `java_binary` target that serves P4Runtime + Dataplane over gRPC. Used at
**test time** as a subprocess.

```starlark
cc_test(
    name = "dvaas_test",
    data = [
        ":sai_middleblock_fourward",        # compiled pipeline
        "@fourward//grpc:fourward_server",      # server binary
    ],
    ...
)
```

### 3. Proto definitions

`ir.proto`, `simulator.proto`, `dataplane.proto` — the wire format for the
compiled pipeline and gRPC services. Needed for C++ code in sonic-pins to parse
4ward's `InjectPacketResponse` (which contains `TraceTree`).

## gNMI and port translation

DVaaS uses gNMI (`SwitchApi.gnmi`) for two things:

1. **Port discovery.** Enumerate interfaces and their admin/oper state.
2. **Port translation configuration.** gNMI Set assigns P4RT port IDs to
   interfaces. These IDs are the values that appear in P4Runtime table entries
   for `@p4runtime_translation`-annotated port types.

The gNMI service itself belongs in sonic-pins, not 4ward:

- sonic-pins **owns the `SwitchApi` interface** and knows exactly which gNMI
  paths DVaaS queries. Keeping the stub there makes it easier to evolve.
- Keeping gNMI out of 4ward avoids a **proto dependency on openconfig/gnmi**.
- 4ward should not be aware of sonic-pins/gNMI semantics.

However, the port translation mappings established via gNMI **must reach
4ward's `TypeTranslator`** so that `@p4runtime_translation` for port types
works correctly. This is a P4Runtime concern (spec §16.3), so 4ward exposes
it through the P4Runtime service — a generic mechanism for configuring
translation mappings without knowing they came from gNMI. The
`FourwardPinsSwitch` (sonic-pins side) bridges the two: it receives gNMI Set
requests and pushes the resulting mappings into 4ward via P4Runtime.

```
  DVaaS
    │
    ▼
  FourwardPinsSwitch (sonic-pins)
    ├─ .p4rt ──────────▶ 4ward P4Runtime service
    │                        ▲
    ├─ .gnmi ──────────▶ FakeGnmiService (sonic-pins)
    │                        │
    │                        │ on port ID change
    │                        ▼
    └─ push mapping ───▶ 4ward P4Runtime (translation config)
```

## The FourwardMirrorTestbed: development vehicle

The
[`FourwardMirrorTestbed`](https://github.com/smolkaj/sonic-pins/blob/fourward-dvaas-integration/fourward/fourward_switch.h)
(two 4ward instances connected by a `PacketBridge`) is a development and
testing vehicle for the integration. It exercises the same gRPC interfaces that
the upstream integration will use, but runs entirely in-process without needing
a real switch.

It is not the upstream integration itself, but it is valuable long-term:

- **Fast iteration.** Test 4ward changes against DVaaS without touching
  sonic-pins. If the mirror testbed passes, the upstream integration should too.
- **CI regression.** Run as part of 4ward's own CI to catch breakages early.
- **Living integration spec.** The testbed code documents exactly how DVaaS
  talks to 4ward — it's executable documentation.

## Current state

### What's done (4ward side)

- **Dual port encoding** — fully implemented. `InjectPacket` accepts both
  dataplane `uint32` and P4RT `bytes` ports; responses include both encodings.
  See [dataplane_port_encoding.md](dataplane_port_encoding.md).
- **v1model fork for SAI P4** — merged
  ([sonic-pins PR #11](https://github.com/smolkaj/sonic-pins/pull/11)).
  `v1model_sai.p4` defines typed `port_id_t` with `@p4runtime_translation`,
  eliminating cast hacks.
- **SAI P4 E2E** — works end-to-end through 4ward's P4Runtime stack.
- **Performance** — 1k pps target met (127× improvement). See
  [PERFORMANCE.md](../docs/PERFORMANCE.md).

### What's done (sonic-pins side)

All integration code lives on
[`smolkaj/sonic-pins`](https://github.com/smolkaj/sonic-pins) `main`
(Bazel 8.6, Bzlmod, C++20):

1. **`bazel_dep` packaging** — 4ward consumed via `git_override`.
   `fourward_pipeline` rule compiles SAI P4 for 4ward at build time.
2. **`FourwardOracle`** — manages a 4ward subprocess, loads pipeline,
   installs entities, predicts outputs via streaming `InjectPackets` +
   `SubscribeResults`.
3. **Trace conversion** — `FourwardTraceTreeToDvaasPacketTrace` flattens
   4ward's recursive `TraceTree` into DVaaS's flat `PacketTrace`.
4. **DVaaS frontend wiring** — `fourward_config` in `P4Specification`
   gates the 4ward code path in `dataplane_validation.cc`.
5. **`FourwardMirrorTestbed`** — two 4ward instances + `FakeGnmiService` +
   `PacketBridge`. Transparent `thinkit::MirrorTestbed` for testing.
6. **Portable PINS backend** — open-source `DataplaneValidationBackend`
   using 4ward for prediction and SAI P4 helpers for punt/auxiliary entries.
7. **Trace summary** — human-readable trace summary for debugging (table
   hits/misses, drop reasons, packet fate).

### What's left

1. **Packet synthesis via p4-symbolic** — currently uses
   `packet_test_vector_override` for manual test vectors.
2. **Full `ValidateDataplane` E2E** — requires richer `FakeGnmiService`
   (port discovery, counters) and careful memory management (multiple JVMs).
3. **Upstream PR** — submit to `sonic-net/sonic-pins`.

## Three uses of 4ward in sonic-pins

| Use | Where | Purpose |
|-----|-------|---------|
| `FourwardMirrorTestbed` | `fourward/` | Dev vehicle — two 4ward instances + `PacketBridge`, transparent `thinkit::MirrorTestbed` |
| Output prediction | `dataplane_validation.cc` | `FourwardOracle.PredictAll` replaces BMv2 when `fourward_config` is set |
| Trace prediction | `dataplane_validation.cc` | Traces attached during prediction, converted via `FourwardTraceTreeToDvaasPacketTrace` |
