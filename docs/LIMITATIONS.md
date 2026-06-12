# 4ward — Known Limitations

> Living document. Add entries when you take a shortcut or discover a gap.
> Remove entries when they get resolved.

Intentional shortcuts, missing features, and known gaps in the current
implementation. This is the place to record trade-offs so they don't get
lost in commit messages or forgotten entirely.

Agents: when you take a shortcut or skip a corner case, add it here. No
guilt — just write it down so someone can find it later.

## Architecture support

- **PSA: 74 corpus STF tests pass + 73 compile-only tests.** The PSA
  two-pipeline architecture (ingress + egress) is implemented with support for
  `send_to_port`, `ingress_drop`, `egress_drop`, `multicast`, I2E/E2E cloning
  (via `ostd.clone` + `clone_session_id`), recirculate (`PSA_PORT_RECIRCULATE`),
  resubmit (`ostd.resubmit`), registers, `Hash.get_hash` (headers, structs,
  bare fields, 1-arg and 3-arg forms), `Meter.execute` (stub GREEN),
  `Random.read()`, `InternetChecksum` (clear/add/subtract/get/get_state/set_state),
  `Digest.pack` (stub no-op), counters (indirect + direct), action profiles,
  action selectors (including choice-based trace trees for group hits), parser
  `value_set`, header stacks, and top-level assignments. 73 additional PSA
  programs (BMv2 + DPDK targets) are verified to compile. TNA is not
  implemented.
- **PNA: fully implemented, 68/68 programs covered.** The PNA
  single-pipeline architecture (MainParser → PreControl → MainControl →
  MainDeparser) is implemented with support for `send_to_port`, `drop_packet`,
  `recirculate`, `mirror_packet`, `SelectByDirection`, registers, `Hash.get_hash`,
  `Meter.execute` (stub GREEN), `InternetChecksum`, `Digest.pack` (stub no-op),
  counters (indirect + direct), and `Random.read()`. Add-on-miss externs (`add_entry`,
  `allocate_flow_id`, `set_entry_expire_time`, `restart_expire_timer`) are stubs.
  37 STF corpus tests, 31 compile-only tests, 33 p4testgen symbolic tests.
## Externs

- **No user-defined extern functions.** Extern functions declared in P4
  without a body (e.g. `extern void f(out bit<32> d, bit<32> s)`) cannot be
  executed — their semantics exist only in architecture-specific libraries.
  Blocks 1 corpus test (`extern-funcs-bmv2`).
- **Meters always return GREEN by design.** `meter.execute_meter()` and
  `direct_meter.read()` always return GREEN (0). P4Runtime meter configs can be
  written and read back, but token buckets, refill timing, and rate-based color
  transitions are permanently out of scope: 4ward is a deterministic packet
  simulator with no wall-clock traffic-rate model.
- **`digest` is a no-op stub.** PSA `Digest.pack()` is accepted but doesn't
  deliver messages to the control plane. v1model `digest()` is not
  implemented and will crash at runtime.

## v1model gaps

- **`standard_metadata` queue depth fields are always zero.**
  `enq_qdepth`, `deq_qdepth`, and `deq_timedelta` are never populated.
  The simulator has no traffic manager queue model, so these fields stay
  at their default zero values.

## P4Runtime server

- **Full multi-controller arbitration with role-based access control.**
  Per-role primary election, demotion/promotion notifications, automatic
  promotion on disconnect, and `@p4runtime_role` enforcement for reads and
  writes (P4Runtime spec §5, §15). `Role.config` (target-specific policy)
  is rejected with UNIMPLEMENTED.
- **`@p4runtime_translation`: fully integrated for action params, match fields,
  and PacketIO metadata.** The `TypeTranslator` supports `sdn_bitwidth` and
  `sdn_string` with explicit, auto-allocate, and hybrid mapping modes.
  `PacketHeaderCodec` handles `packet_in`/`packet_out` header serialization
  per p4info `controller_packet_metadata`, tested E2E on SAI P4.
- **PRE replica ports require explicit port mappings or `Replica.port`.**
  Clone sessions and multicast groups using `Replica.egress_port` (int32,
  deprecated) bypass port translation. When port translation is active,
  every egress port must have a P4RT reverse mapping — outputs on unmapped
  ports will error. Controllers should use `Replica.port` (bytes, P4RT v1.4+)
  to forward-allocate mappings, and provide explicit `TypeTranslation` entries
  for architecture-defined ports (like the CPU port) that auto-allocation
  cannot assign correctly.
- **Direct meters always return GREEN by design.** Direct meter configs can be
  written and read via P4Runtime, but `direct_meter.read()` does not consume or
  refill buckets. It always returns the default color (GREEN).
- **No digests or idle timeouts (by design).** Both are inherently
  time-dependent features that have no meaningful semantics in a reference
  simulator: there are no real packet rates to trigger digests, and no
  wall-clock time to expire idle entries. These are explicitly out of scope.
## Simulator

- **Fork-point resume is v1model only.** When the trace tree branches (action
  selectors, clone, multicast), v1model continues each subtree from the fork
  point instead of replaying the pipeline. PSA and PNA still use the
  replay-based approach (`handleActionSelectorFork` in `ArchitectureHelpers.kt`).
  Other v1model-specific optimizations (Long-backed `BitVector`, `CompactFieldMap`,
  proto builder pooling) benefit all architectures.
- **Multicast: basic replication only.** Multicast group replication works
  for the trace tree (one continuation per replica). PRE entries are installed
  via
  P4Runtime `PacketReplicationEngineEntry`.

## Web playground

- **Single-replica clone sessions.** The UI creates clone sessions with one
  replica. Multi-replica cloning requires the gRPC API.
- **No multicast group UI.** The backend supports multicast groups, but the
  web UI only exposes clone sessions.
- **No MODIFY for non-default table entries.** The UI only supports INSERT and
  DELETE for regular entries. To change an entry, delete and re-add it.
- **No counters, meters, registers, or action profile UI.** These P4Runtime
  entities are supported by the backend but not exposed in the browser.
- **Read API only returns table entries.** `GET /api/read` hard-codes a
  `TableEntry` filter. Clone sessions, counters, and other entity types
  cannot be read back through the REST API.
- **Branching programs: output packets panel flattens possible worlds.** For
  programs with action selectors, the web playground's output panel shows
  outputs from *all* possible worlds. The trace tab shows the full tree
  structure. See [Trace Trees: Continuations and choices](../userdocs/concepts/traces.md#continuations-and-choices).
- **No `StreamChannel`.** The web UI uses REST APIs, not the P4Runtime
  bidirectional stream. This means no PacketIO (`packet_in`/`packet_out`),
  no digest notifications, and no arbitration updates. Packets are injected
  directly to the simulator; controller-bound packets are silently lost.
- **No packet history.** Only the most recent packet result is shown. Sending
  another packet overwrites the previous output.
- **No persistence.** Pipeline and table state are in-memory. Restarting the
  server clears everything. Editor content is not saved across page refreshes.
- **Single shared state.** All browser sessions share one simulator instance.
  Concurrent users will interfere with each other.
- **Monaco editor requires internet.** Loaded from `cdn.jsdelivr.net`.
  The playground does not work offline.
- **Three bundled examples only.** `basic_table`, `passthrough`, `mirror`.

## BMv2 differential testing

- **System libgmp/libpcap dependency.** The BMv2 build links against system
  `libgmp` and `libpcap` rather than building them from source. The build uses
  genrules to copy headers into the build tree, but runtime linking requires the
  libraries to be installed (e.g. via Homebrew on macOS).
- **All 186 corpus tests pass.**

## p4c backend

- **`gauntlet_various_ops-bmv2` compilation timeout.** p4c-4ward takes 10+
  minutes on this program. Performance issue, not a missing feature. Blocks
  1 corpus test.
- **`psa-subtract-inst1` OOM during compilation.** p4c-4ward is killed by the
  OS during compilation. Same class of issue as `gauntlet_various_ops-bmv2`.
