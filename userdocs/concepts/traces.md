---
description: "How 4ward trace trees capture every possible packet outcome — parser transitions, table hits, clones, and multicast — in a single structured proto."
---

# Trace Trees

Every packet 4ward processes produces a **trace tree**
([`TraceTree`]({{ config.repo_url }}/blob/main/simulator/simulator.proto)
in `simulator.proto`) — a complete record of every decision the simulator
made. Most packets take a single path through the
pipeline and produce a linear trace. At non-deterministic choice points (action
selectors, clone, multicast), the trace **forks** into branches, one per
possible outcome.

No other P4 tool gives you this. [BMv2](https://github.com/p4lang/behavioral-model) picks one path. Hardware picks one path.
4ward shows you *all* paths in a single pass.

## Structure

A trace tree is a recursive structure:

```
TraceTree
├── events[]      — chronologically ordered
└── outcome (one of)
    ├── Output        — terminal: packet transmitted on a port
    ├── Drop          — terminal: packet discarded
    ├── Replication   — parallel branches (clone, multicast)
    ├── Choice        — alternative branches (action selector)
    └── Continuation  — same packet, another pass (resubmit, recirculate)
```

Events at the parent level are **shared** across all branches. Per-branch
events live in the subtrees. A program with no non-determinism produces a
linear trace — a `TraceTree` with a single `Output` or `Drop` outcome.

## A simple trace

A packet matching a forwarding table and exiting on port 1:

```
packet_ingress (port 0)
├─ parser: start → accept
├─ table port_table: hit → forward
├─ action forward(port=1)
├─ deparser: ethernet_t (14 bytes)
└─ output port 1
```

In proto text format, the same trace:

```textproto
# proto-file: @fourward//simulator/simulator.proto
# proto-message: fourward.TraceTree
events { packet_ingress { dataplane_ingress_port: 0 } }
events { parser_transition {
  parser_name: "MyParser"
  from_state: "start"
  to_state: "accept"
} }
events { table_lookup {
  table_name: "port_table"
  hit: true
  matched_entry { ... }
  action_name: "forward"
} }
events { action_execution {
  action_name: "forward"
  params { key: "port" value: "\001" }
} }
events { deparser_emit { header_type: "ethernet_t" byte_length: 14 } }
output { dataplane_egress_port: 1 payload: "..." }
```

## Forks

When execution reaches a non-deterministic choice point, the trace forks.
Each branch gets its own subtree with the remaining pipeline events.

There are two kinds of forks, and the distinction is important for
understanding what the output packets mean:

- **Parallel forks** (clone, multicast, resubmit, recirculate) — all branches
  happen simultaneously in a single real execution. A clone creates both the
  original *and* the clone; multicast creates all replicas at once.
- **Alternative forks** (action selector) — exactly one branch happens at
  runtime, determined by a hash function. Each branch represents one *possible
  world* — a forwarding outcome that *could* happen, depending on the hash.

For parallel forks, the output packets are the combined outputs from all
branches. For alternative forks, each branch produces its own independent set
of outputs — a "possible outcome." The `possible_outcomes` field in the gRPC
response (and `possibleOutcomes` in the Kotlin API) gives you the full picture:
each entry is one set of output packets that could result from a single real
execution.

Programs with only parallel forks have exactly one possible outcome. Programs
with action selectors have one possible outcome per member — and when
alternative forks are nested inside parallel forks (e.g., a clone where both
original and clone hit an action selector), the outcomes multiply
(Cartesian product).

### Clone

A clone creates two branches — the **original** packet continues on its
normal path, and the **clone** goes to the clone session's egress port:

```
packet_ingress (port 0)
├─ parser: start → accept
├─ table routing: hit → forward
├─ action forward(port=2)
├─ clone session 1
├─ clone_session_lookup: session 1 → port 3
└─ fork (clone)
   ├─ branch: original
   │  ├─ egress pipeline...
   │  └─ output port 2
   └─ branch: clone
      ├─ egress pipeline...
      └─ output port 3
```

### Multicast

Multicast creates one branch per replica in the multicast group:

```
├─ table routing: hit → multicast_forward
├─ action multicast_forward(group=1)
└─ fork (multicast)
   ├─ branch: replica_0_port_1
   │  └─ output port 1
   ├─ branch: replica_0_port_2
   │  └─ output port 2
   └─ branch: replica_0_port_3
      └─ output port 3
```

### Action selector

An action selector with multiple members forks into one branch per member —
showing every possible forwarding decision. This is an **alternative fork**:
real hardware picks exactly one member (via hashing), but 4ward shows all
possibilities so you can verify that every path is correct.

```
├─ table ecmp: hit → set_port
└─ fork (action selector)       ← alternative: one of these happens
   ├─ branch: member_0
   │  ├─ action set_port(port=1)
   │  └─ output port 1
   ├─ branch: member_1
   │  ├─ action set_port(port=2)
   │  └─ output port 2
   └─ branch: member_2
      ├─ action set_port(port=3)
      └─ output port 3
```

This trace has three **possible outcomes**: `{port 1}`, `{port 2}`, or
`{port 3}`. Compare this with a clone fork, which has one possible outcome
containing *all* branch outputs.

### Resubmit and recirculate

Both create a fork where one branch is the resubmitted/recirculated packet
re-entering the pipeline. The branch label is `resubmit` or `recirculate`.

## Event catalog

Every trace event carries optional **source info** (file, line, column,
source fragment) linking back to the P4 source.

| Event | Fields | When it fires |
|-------|--------|---------------|
| **PacketIngress** | `dataplane_ingress_port`, `p4rt_ingress_port` | First event in every trace |
| **PipelineStage** | `stage_name`, `stage_kind`, `direction` (ENTER/EXIT) | Entering or exiting a pipeline stage |
| **ParserTransition** | `from_state`, `to_state`, `select_value`, `select_expression` | Every parser state transition, including accept/reject |
| **TableLookup** | `table_name`, `hit`, `matched_entry`, `action_name` | Every `table.apply()` call |
| **ActionExecution** | `action_name`, `params` (name → bytes) | When an action begins executing |
| **Branch** | `control_name`, `taken` (true=then, false=else) | Every if/else branch |
| **ExternCall** | `extern_instance_name`, `method` | Every extern method call |
| **MarkToDrop** | `reason` | `mark_to_drop()` called |
| **Clone** | `session_id` | `clone()` / `clone3()` called |
| **CloneSessionLookup** | `session_id`, `session_found`, `dataplane_egress_port` | Traffic manager resolves a clone session |
| **LogMessage** | `message` | `log_msg()` called |
| **Assertion** | `passed` | `assert()` or `assume()` called |
| **DeparserEmit** | `header_type`, `byte_length` | Deparser emits a header |

## Packet outcomes

Every trace path terminates with one of two outcomes:

- **Output** — packet transmitted: `dataplane_egress_port` + `payload`.
- **Drop** — packet discarded. When there is a triggering event (e.g.
  `mark_to_drop()`, a failed assertion, a missing multicast group), the
  `Drop.cause` field points to its id in the same node's `events` list.
  Parser-reject drops have no cause.

## P4RT enrichment

When the loaded pipeline uses
[`@p4runtime_translation`](type-translation.md), trace events carry
**P4Runtime representations** alongside raw dataplane values. This happens
automatically when packets are injected through the
[DataplaneService gRPC API](../reference/grpc.md).

```textproto
# proto-file: @fourward//simulator/simulator.proto
# proto-message: fourward.TraceTree
events { packet_ingress {
  dataplane_ingress_port: 1
  p4rt_ingress_port: "Ethernet0"
} }
events { table_lookup {
  table_name: "forwarding"
  hit: true
  matched_entry {
    # dataplane: action param value "\000"
  }
  p4rt_matched_entry {
    # P4Runtime: action param value "Ethernet1"
  }
} }
output {
  dataplane_egress_port: 0
  p4rt_egress_port: "Ethernet1"
}
```

Enrichment only applies to the DataplaneService gRPC path. The CLI and web
playground inject packets directly through the simulator and do not produce
enriched traces.

## Output formats

The CLI supports three trace output formats via `--format`:

**`--format=human`** (default) — indented text:

```
parse: start -> accept
table port_table: hit -> forward
action forward(port=1)
output port 1, 18 bytes
```

**`--format=textproto`** — proto text format, suitable for programmatic
consumption, golden file diffing, and proto tooling.

**`--format=json`** — JSON serialization of the proto, suitable for
programmatic consumption (jq, scripts, dashboards).

The web playground shows traces as an interactive visual tree, with optional
JSON and proto text views.
