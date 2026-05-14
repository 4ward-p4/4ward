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

A new `ReproduceTrace` RPC on the `Dataplane` service, defined in
`grpc/dataplane.proto`:

```proto
service Dataplane {
  // ...existing RPCs...
  rpc ReproduceTrace(InjectPacketRequest) returns (Reproducer);
}

message Reproducer {
  PipelineConfig pipeline_config = 1;
  repeated p4.v1.Entity entities = 2;
  InputPacket input_packet = 3;
  TraceTree trace = 4;
  repeated PacketSet possible_outcomes = 5;
}
```

The RPC reuses `InjectPacketRequest` — same input as `InjectPacket` —
and returns a self-contained `Reproducer`. Serialize it to a file and
hand it to someone — they have everything needed to reproduce the
trace and see what happened.

### Entity extraction

Walk the `TraceTree` recursively and collect entities referenced by trace
events:

| Trace event | Entity extracted |
|---|---|
| `TableLookupEvent` with `hit = true` | `matched_entry` → `Entity.table_entry` |
| `TableLookupEvent` with `hit = false` and modified default action | Default action from table store → `Entity.table_entry` (with `is_default_action`) |
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
| `grpc/dataplane.proto` | Add `ReproduceTrace` RPC and `Reproducer` message |
| `grpc/DataplaneService.kt` | Implement `reproduceTrace`: inject packet, extract entities, bundle with pipeline config |
| `simulator/ReproducerExtractor.kt` | Entity extraction: walk trace tree, collect referenced entities from `TableStore` |
| `fourward_cc/dataplane_client.h` | Add `ReproduceTrace` method to `DataplaneClient` |

### Future work

- **CLI integration.** `4ward reproduce` could serialize a reproducer to
  text proto for easy sharing.
- **Replay from proto.** A counterpart that loads a reproducer and
  replays it, verifying the trace matches.
