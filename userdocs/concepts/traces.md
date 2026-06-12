---
description: "How 4ward trace trees capture every possible packet outcome — parser transitions, table hits, clones, and multicast — in a single structured proto."
---

# Trace Trees

Every packet 4ward processes produces a **trace tree**
([`TraceTree`](https://github.com/smolkaj/4ward/blob/main/simulator/simulator.proto)
in `simulator.proto`) — a complete record of every decision the simulator
made. Most packets take a single path through the
pipeline and produce a linear trace. When a packet multiplies (clone,
multicast) or re-enters the pipeline (resubmit, recirculate), the trace
**continues** as several subtrees; at non-deterministic choice points (action
selectors), it branches into one subtree per possible outcome.

No other P4 tool gives you this. [BMv2](https://github.com/p4lang/behavioral-model) picks one path. Hardware picks one path.
4ward shows you *all* paths in a single pass.

## Structure

A trace tree is a recursive structure:

```
TraceTree
├── events[]          — chronologically ordered
└── outcome
    ├── PacketOutcome — terminal: output on a port, or drop
    ├── Continuations — non-terminal: ALL subtrees happen (clone, multicast,
    │                   resubmit, recirculate)
    └── Choice        — non-terminal: exactly ONE subtree happens
                        (action selector)
```

Events at the parent level are **shared** across all subtrees. Per-subtree
events live below the branching point. A program that never multiplies,
re-enters, or branches produces a single-node tree — structurally equivalent
to a flat trace.

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
packet_outcome {
  output { dataplane_egress_port: 1 payload: "..." }
}
```

## Continuations and choices

A trace tree branches for two very different reasons, and the distinction is
important for understanding what the output packets mean:

- **Continuations** (clone, multicast, resubmit, recirculate) — execution
  continued in *all* of the listed ways within a single real execution. A
  clone creates both the original *and* the clone; multicast creates all
  replicas at once; a recirculated packet continues as one new pipeline pass.
  Each continuation is typed: `ORIGINAL`, `CLONE`, `MIRROR`,
  `MULTICAST_REPLICA`, `RESUBMIT`, or `RECIRCULATE`, with the replica's egress
  port and instance id where applicable.
- **Choices** (action selector) — exactly *one* alternative happens at
  runtime, determined by a hash function. Each alternative represents one
  *possible world* — a forwarding outcome that *could* happen, depending on
  the hash. 4ward explores all of them in a single pass.

For continuations, the output packets are the combined outputs from all
subtrees. For choices, each alternative produces its own independent set of
outputs — a "possible outcome." The `possible_outcomes` field in the gRPC
response (and `possibleOutcomes` in the Kotlin API) gives you the full picture:
each entry is one set of output packets that could result from a single real
execution.

Programs with no action selectors have exactly one possible outcome. Programs
with action selectors have one possible outcome per member — and when choices
are nested inside continuations (e.g., a clone where both original and clone
hit an action selector), the outcomes multiply (Cartesian product).

### Clone

A clone creates two continuations — the **original** packet continues on its
normal path, and the **clone** goes to the clone session's egress port:

```
packet_ingress (port 0)
├─ parser: start → accept
├─ table routing: hit → forward
├─ action forward(port=2)
├─ clone session 1
├─ clone_session_lookup: session 1 → port 3
└─ continues as:
   ├─ original:
   │  ├─ egress pipeline...
   │  └─ output port 2
   └─ clone port 3:
      ├─ egress pipeline...
      └─ output port 3
```

### Multicast

Multicast creates one continuation per replica in the multicast group:

```
├─ table routing: hit → multicast_forward
├─ action multicast_forward(group=1)
└─ continues as:
   ├─ replica port 1 instance 0:
   │  └─ output port 1
   ├─ replica port 2 instance 0:
   │  └─ output port 2
   └─ replica port 3 instance 0:
      └─ output port 3
```

### Action selector

An action selector with multiple members produces a **choice** with one
alternative per member — showing every possible forwarding decision. Real
hardware picks exactly one member (via hashing), but 4ward shows all
possibilities so you can verify that every path is correct.

```
├─ table ecmp: hit → set_port
└─ one of (action selector):    ← exactly one of these happens
   ├─ member 0:
   │  ├─ action set_port(port=1)
   │  └─ output port 1
   ├─ member 1:
   │  ├─ action set_port(port=2)
   │  └─ output port 2
   └─ member 2:
      ├─ action set_port(port=3)
      └─ output port 3
```

This trace has three **possible outcomes**: `{port 1}`, `{port 2}`, or
`{port 3}`. Compare this with the clone above, which has one possible outcome
containing *all* its outputs.

### Resubmit and recirculate

Both end the current pipeline pass with a single continuation — the
resubmitted/recirculated packet re-entering the pipeline as a new pass, with
kind `RESUBMIT` or `RECIRCULATE`. A single continuation is the common case
here: nothing is copied, the packet simply continues.

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

Every trace path terminates with a **PacketOutcome**:

- **Output** — packet transmitted: `dataplane_egress_port` + `payload`.
- **Drop** — packet dropped, with a reason:
    - `MARK_TO_DROP` — explicit `mark_to_drop()` call.
    - `PARSER_REJECT` — parser transitioned to the reject state.
    - `PIPELINE_EXECUTION_LIMIT_REACHED` — the trace tree grew too large
      (exponential blowup guard).
    - `ASSERTION_FAILURE` — `assert()` or `assume()` failed.

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
packet_outcome { output {
  dataplane_egress_port: 0
  p4rt_egress_port: "Ethernet1"
} }
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
