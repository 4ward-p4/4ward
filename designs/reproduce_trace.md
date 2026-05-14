# Reproduce Trace Design

**Status: implemented.**

## Goal

When a 4ward user sees unexpected behavior, give them a one-click way to
capture everything needed to reproduce the trace — the P4 program, the
relevant table entries, and the input packet — as a single proto they can
attach to a bug report.

## Why

When a 4ward user hits unexpected behavior — wrong output packet, wrong
drop, a trace that doesn't match the spec — the first thing we ask is
"can you reproduce it?" Today that means assembling the P4 program, the
right table entries, and the input packet by hand. In practice, bug
reports end up as prose descriptions or screenshots of traces, and the
first round of triage is spent reconstructing state instead of
investigating the bug.

The reproducer eliminates that friction. The user sets a flag on their
`InjectPacket` call, and the response includes everything needed to
reproduce the trace. They attach it to a GitHub issue; we load it into a
local 4ward instance and see the exact same behavior. No back-and-forth,
no "what entries did you have installed?"

Once the bug is confirmed, the same proto becomes a regression test —
serialize it to textproto, add it to the corpus, fix the bug, and the
test stays as a guard.

## Design

### Proto

An opt-in flag on `InjectPacketRequest` and a new `Reproducer`
message, both in `grpc/dataplane.proto`:

```proto
message InjectPacketRequest {
  oneof ingress_port {
    uint32 dataplane_ingress_port = 1;
    bytes p4rt_ingress_port = 2;
  }
  bytes payload = 3;

  // When true, the response includes a Reproducer with the
  // pipeline config and entities needed to reproduce this trace.
  bool include_reproducer = 4;
}

message InjectPacketResponse {
  TraceTree trace = 1;
  repeated PacketSet possible_outcomes = 2;

  // Populated when include_reproducer is set on the request.
  Reproducer reproducer = 3;
}

// Everything needed to reproduce a trace, minus the input packet and
// trace itself (which are already in the enclosing response).
message Reproducer {
  // The compiled P4 program (IR + p4info).
  PipelineConfig pipeline_config = 1;

  // P4Runtime entities needed to reproduce the trace: matched table
  // entries, clone sessions, multicast groups, action profile
  // members/groups.
  repeated p4.v1.Entity entities = 2;
}
```

Why a flag on `InjectPacket` rather than a separate RPC: the user
discovers the bug *during* an `InjectPacket` call. A separate
`ReproduceTrace` RPC would require re-injecting the same packet — an
extra step, and a TOCTOU risk if state changed between calls. The flag
lets the user get the reproducer in the same call where the bug was
observed.

`Reproducer` doesn't duplicate the input packet or trace — those
are already in `InjectPacketResponse`. Together, the response fields
form a complete, self-contained reproduction case.

### Entity extraction

Walk the `TraceTree` recursively and collect entities referenced by trace
events:

| Trace event | Entity extracted |
|---|---|
| `TableLookupEvent` with `hit = true` | `matched_entry` → `Entity.table_entry` |
| `CloneSessionLookupEvent` with `session_found = true` | Clone session from table store → `Entity.clone_session_entry` |
| `Fork` with reason `MULTICAST` | Multicast group from table store → `Entity.multicast_group_entry` |
| Action profile references in matched entries | Members/groups from table store → `Entity.action_profile_member` / `Entity.action_profile_group` |

The extraction is a pure function over the trace tree and a
`ForwardingSnapshot` — no mutation, no side effects. Entities that
appear multiple times in the trace (e.g., a recirculated packet hitting
the same table entry twice) are deduplicated.

**Static vs. runtime entries.** `const entries` from the P4 source live
in `PipelineConfig.device.static_entries` and get installed
automatically at pipeline load time — they never appear in `entities`.
All other matched entries go in `entities`, including non-const initial
entries (`entries = { ... }` without `const`) that may have been
modified or deleted at runtime. The extraction always uses the entry
from the trace (which reflects current runtime state) and only filters
out entries present in `static_entries`.

### Scope

The reproducer captures **what happened**, not what was *expected* to
happen. If a table missed because the right entry wasn't installed, the
reproducer faithfully reproduces that miss — it doesn't include entries
from other tables that might have been relevant. This is the right
tradeoff: the reproducer is a recording, not a diagnosis.

### What changes where

| Component | Change |
|---|---|
| `grpc/dataplane.proto` | Add `include_reproducer` flag, `Reproducer` message, and `reproducer` field on `InjectPacketResponse` |
| `grpc/DataplaneService.kt` | When flag is set: extract entities from trace, bundle with pipeline config |
| `simulator/` (new file) | Entity extraction logic: walk trace tree, collect referenced entities from `ForwardingSnapshot` |
| `fourward_cc/dataplane_client.h` | Add `include_reproducer` to `InjectPacketArgs` |

### Future work

- **CLI integration.** `4ward reproduce` could serialize a reproducer to
  text proto for easy sharing.
- **Replay from proto.** A counterpart that loads a reproducer and
  replays it, verifying the trace matches.
