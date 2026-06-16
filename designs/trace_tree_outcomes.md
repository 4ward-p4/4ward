# Trace Tree Outcomes Redesign

**Status: proposed.**

## Goal

Redesign the trace tree's branch model so a single proto describes the
behavior of *any* P4 architecture — v1model, PSA, PNA, TNA, and ones not
yet written — while being honest about the three fundamentally different
ways a packet's execution can branch. Replace today's `Fork { reason,
branches }` with a small, closed set of **outcomes** whose *structure*
encodes the architecture-independent semantics and whose *annotations*
carry the architecture-specific detail.

## Why

The trace tree is 4ward's output format: a node is a list of `events`
followed by either a `Fork` or a terminal packet outcome. Today every
branch point is a `Fork` with a `reason` enum
(`ACTION_SELECTOR`, `CLONE`, `MULTICAST`, `RESUBMIT`, `RECIRCULATE`) and a
flat list of branches. That one shape is asked to mean too many different
things, and three problems follow.

**1. It conflates replication, nondeterminism, and continuation.**
A multicast (make N copies, all of which happen) and an action selector
(one member is chosen at runtime) have *opposite* output semantics, yet
both are a `Fork`; the difference is recovered by a derived
`forkModeOf(reason)` table. Worse, resubmit and recirculate are modeled as
"parallel forks" — but they are not forks at all. A resubmitted packet is
the *same* packet going around again, not a copy. Representing it as a
one-branch parallel fork is a category error wearing a fork's clothes; it
only type-checks because a Cartesian product over one branch is the
identity.

**2. It cannot represent concurrent effects without lying.** A boundary
can trigger several effects at once (v1model end-of-ingress: clone *and*
multicast; end-of-egress: clone *and* recirculate). With one `reason` per
fork, the architectures diverge: PSA nests cleanly, but PNA jams mirror
branches and a recirculate branch into a single fork and *guesses*
`reason = if (mirror) CLONE else RECIRCULATE`. The recirculate branch's
true cause survives only in a `label` string. The `reason` field becomes
lossy at exactly the point it matters.

**3. The `reason` enum is architecture-locked.** `CLONE`, `MULTICAST`,
`RESUBMIT`, `RECIRCULATE` are v1model/PSA vocabulary. A new architecture
with a new replication or continuation primitive forces a change to the
core proto enum *and* breaks every consumer's exhaustive `when`. The
format meant to describe "any architecture" has the architectures baked
into its type system.

## Design

### The two axes

A trace branches along two independent axes, and the whole design is
keeping them apart:

- **How outputs combine** — *closed, universal, architecture-independent.*
  Packet processing can only ever copy a packet (all copies live), send it
  down one of several runtime-chosen paths (one lives, as a possible
  world), continue the same packet into another pass, or end. There is no
  fifth way; richer behaviors compose from these. This axis is what a
  consumer must dispatch on to compute outputs, so it lives in the proto
  *structure*.

- **Why it branched, and with what detail** — *open, architecture-specific,
  unbounded.* "clone" vs "multicast" vs some future primitive; session ids,
  multicast members, preserved metadata. This axis is carried as *open
  annotation* — a `reason` string (names, not IDs) plus ordinary trace
  events — never as proto structure.

### The five outcomes

A `TraceTree` node is a list of `events` and exactly one `outcome`:

```proto
message TraceTree {
  repeated TraceEvent events = 1;
  oneof outcome {
    Replication  replication  = 2;  // all branches happen; outputs union
    Choice       choice       = 3;  // exactly one branch happens (possible worlds)
    Continuation continuation = 4;  // the same packet continues as another pass
    Output       output       = 5;  // terminate, emitting a packet
    Drop         drop         = 6;  // terminate, no packet
  }
}

message Replication  { string reason = 1; repeated TraceTree branches = 2; }
message Choice       { string reason = 1; repeated TraceTree branches = 2; }
message Continuation { string reason = 1; TraceTree next = 2; }
message Output       { PortRef port = 1; bytes packet = 2; }
message Drop         { string reason = 1; }
```

| Outcome | Meaning | Output semantics |
|---|---|---|
| `Replication` | clone, multicast — copies are made | all branches happen; packets are the **union** across branches (Cartesian product when collecting possible worlds) |
| `Choice` | action selector, random — one path chosen at runtime | exactly one branch happens; each branch is a distinct **possible world** |
| `Continuation` | resubmit, recirculate, forward stage transitions | the **same packet** continues; single successor; pass-through |
| `Output` | the packet leaves on a port | leaf |
| `Drop` | the packet is discarded | leaf |

The outcome *type* is the combine semantics — `collectPossibleOutcomes`
dispatches on it directly. No `forkModeOf` table, and a new architecture
cannot add a new combine semantics, so it cannot break the dispatch.

### Vocabulary

Consistent across proto, Kotlin, and docs:

- **outcome** — how a node ends: a fork, a continuation, or a leaf.
- **fork** — a `Replication` or a `Choice` (a node with ≥2 branches). A
  `Continuation` is **not** a fork; `Output`/`Drop` are **leaves**.
- **branch** — one subtree under a fork.
- **pass** — one traversal of a pipeline stage; passes are linked by
  `Continuation`s.
- **reason** — the string naming the specific cause on a fork or
  continuation (`"multicast"`, `"clone"`, `"action_selector"`,
  `"recirculate"`, `"resubmit"`, `"to_egress"`).

This **retires** the `parallel` / `alternative` terminology and the
`ForkMode` / `forkModeOf` machinery. "Parallel" was never a clean antonym
of "alternative" and actively misled by lumping continuations under it.

### One reason per fork; nest for multiplicity

A fork carries a single `reason`; all its branches share that cause.
Concurrent effects at one boundary are modeled as **nested forks**, each
homogeneous, not as one fork with heterogeneous per-branch reasons.

Replication operations compose on "the original": clone splits off a copy
and the original continues; multicast then fans the original out. So
clone + multicast at end-of-ingress is:

```
Replication reason="clone"
├─ original  ── Replication reason="multicast"
│               ├─ r0 → egress → Output
│               ├─ r1 → egress → Output
│               └─ r2 → egress → Output
└─ mirror-copy ── egress (clone session port) → Output
```

Because the parallel combine is associative and commutative, nesting order
does not change the output set — pick the order matching the
architecture's real sequencing for readability. This makes PNA's lossy
single-reason guess **unrepresentable**: recirculate is the *continuation
of the original branch*, never a sibling of the mirror copies:

```
Replication reason="clone"
├─ original  ── Continuation reason="recirculate" → next pass …
└─ mirror-copy ── Output
```

### Continuation handles all stage transitions, not just loopback

A `Continuation` means "the same packet continues as another pass." It is
**not** specialized to looping back. A forward stage transition
(ingress→egress, pipe0→pipe1) is a `Continuation` with a forward `reason`;
recirculate/resubmit are `Continuation`s with a backward `reason`. The
multiplicity at a transition picks the outcome:

| transition | outcome |
|---|---|
| forward, single packet (unicast ingress→egress) | `Continuation` |
| forward, replicated (multicast at the TM) | `Replication` |
| forward, nondeterministic routing | `Choice` |
| backward (recirculate, resubmit) | `Continuation`, backward reason |

A pass is one pipeline-stage traversal; the proto never needs to know how
many stages an architecture has or their names — that is all in `reason`
strings. The combine basis `{Replication, Choice, Continuation, Output,
Drop}` is unchanged by multi-pipe or N-stage architectures.

The trace tree is rooted at a *single input packet*. It composes by
`Replication` / `Choice` / `Continuation` (all 1→1 or 1→N). It has no
**merge** (N→1): reassembly spans multiple input packets, i.e. multiple
trace trees, and lives above this abstraction by construction.

### State changes are events, not branch annotations

Everything a fork's branches differ by — `egress_port`, replication
instance (`rid`), `mcast_grp`, clone session id, preserved metadata — is
**state**, written by the program or the architecture, and therefore a
`TraceEvent` in the relevant node's `events` stream, exactly like every
other field write. A reader telling multicast replicas apart reads the
head events of each branch (`rid=0 → port 5`).

There is **no** per-branch metadata bag (`info`, `map<string, Value>`,
etc.). An earlier draft proposed one; it was redundant with `events` —
re-recording, at the fork, state the subtree already carries. The only
thing genuinely attached to a fork is its `reason`. Load-bearing result
data (the egress port and bytes at an `Output`) stays typed on the leaf,
because that is the simulation's product, not annotation.

### PRE lookups are first-class entries

Match-action is fully transparent in the trace (table, matched entry,
action). Replication should be too: a developer should see *which rule*
produced a fanout, not just the resulting branches. Replication config —
multicast groups, clone sessions — is PRE (Packet Replication Engine)
state the simulator owns, and the architecture consults it to decide a
`Replication`'s branches. That consultation is a **lookup event**,
recorded just before the `Replication`, the exact analog of a table-apply
event preceding its action.

`CloneSessionLookupEvent` already exists. The redesign adds the symmetric
`MulticastGroupLookupEvent` (today multicast is implied only by the fork
`reason`), and both record **hits and misses**:

```
events: [ set meta.mcast_grp = 7,
          MulticastGroupLookup group=7 → members [(port 5, rid 0), (port 68, rid 1)] ]
Replication reason="multicast"
 ├─ rid0  events:[ set egress_port=5,  … ] Output(5)
 └─ rid1  events:[ set egress_port=68, … ] Continuation reason="recirculate" …
```

A miss — multicast to an unconfigured group, a common bring-up bug —
becomes visible instead of a silently vanished packet:

```
events: [ set meta.mcast_grp = 9,
          MulticastGroupLookup group=9 → MISS (no entry) → 0 replicas ]
Drop reason="multicast_no_replicas"
```

These are standard P4Runtime PRE concepts, so typed lookup events belong
in the shared proto; an architecture without a PRE simply never emits
them, just as it never emits a `Replication` with `reason="multicast"`.

### One format; architecture-specific typing via shared schemas

There is **one** serialized trace format: the architecture-neutral tree
above. It is **lossless** — events capture all state, `reason` captures
all causes, PRE lookups capture all replication config — so any
architecture-specific, strongly-typed trace is a *derivable projection*,
never an independent source of truth. We never build a second serialized
format that competes with it.

The hard requirement is that this format be consumed by **many tools, in
many languages, owned by many teams**. So architecture-specific typing
must be a *shared, code-generated schema* — never something each consumer
hand-writes, or every team reimplements the same string-parsing and
validation in every language and they drift. Three layers, all generated,
none hand-parsed:

1. **Universal structure** — the five outcomes, in the core proto.
   Codegen'd everywhere.

2. **State and lookups — typed events.** Most "architecture-specific"
   detail is already typed in the `TraceEvent` vocabulary
   (`TableLookupEvent`, `CloneSessionLookupEvent`,
   `MulticastGroupLookupEvent`, field writes). These are protoc-generated
   structs in every language; a consumer reads `lookup.members[0].port` as
   a typed field. This is the primary channel — push architecture detail
   here first.

3. **Residual structured detail + typed cause — proto extensions.** What
   remains (the `reason` and any structured payload not naturally an event)
   is carried by **`extend`ing** the core node from each architecture's own
   proto:

   ```proto
   // core trace proto (edition 2024) — declares the point, then never changes:
   message TraceNode { /* … */ extensions 1000 to max; }

   // v1model_trace.proto, owned by 4ward, allocated 1000:
   extend TraceNode { V1ModelInfo v1model = 1000; }
   // tna_trace.proto, allocated 1001 — core proto untouched:
   extend TraceNode { TnaInfo tna = 1001; }
   ```

   This is a good fit **because 4ward is the central authority for
   architecture schemas.** Extensions need globally-unique field numbers;
   4ward allocates them, in one repo, so there is no collision risk. The
   many consumers are *consumers* of these schemas, not authors of new
   ones. And the mechanism preserves neutrality: the core `TraceNode`
   message definition never changes — a new architecture is purely
   additive, a new proto file extending the existing range. Consumers in
   any language run protoc on the architecture protos they care about and
   get **typed, generated accessors** — no hand-written lens, no
   string-parsing. A generic consumer that knows no architecture preserves
   unknown extensions and ignores them.

`google.protobuf.Any` was considered for layer 3 and rejected *here*: it
trades typed accessors for opaque pack/unpack and a descriptor dependency,
buying only decentralized (package-namespaced) identity allocation — which
matters when schema authors are *external and uncoordinated*. They are
not; they are 4ward. `Any` is the right tool only if architectures could
ever be authored outside 4ward's coordination, which is not the model.

For a **single small codebase** with one or two consumers, a typed lens in
code (a reader over the generic tree, validated, fails loud) is simpler
than wiring up extensions and is a fine fallback. The dial is breadth of
ecosystem: one codebase → lens; many languages/teams → shared extension
schemas. Either way the generic tree stays the one canonical source of
truth, and the typed layer is always a generated projection over it.

## Alternatives considered

- **Keep `Fork { reason, branches }`, split `reason` into typed messages
  (`CloneFork`, `MulticastFork`, …).** Rejected: bakes architecture
  vocabulary into closed proto structure — the opposite of
  architecture-neutral. The correct split is on the *combine semantics*
  (closed), with `reason` as open annotation.

- **Inline a continuation's events into the current node instead of a
  `Continuation` node.** Rejected for future-proofing. Structure → flat is
  a projection you can always compute; flat → structure is reconstruction
  you may not be able to. The structural form is the lossless superset, so
  it is the safe default under unknown future consumers; flattening a
  linear continuation spine for human reading is a *renderer* concern.

- **Per-branch `reason` / one heterogeneous fork for concurrent effects.**
  Rejected: loosens `fork` from "one operation" into "any parallel spawn"
  and reintroduces the heterogeneous bag. Nesting homogeneous forks is
  output-equivalent and keeps every node one operation with a faithful
  cause.

- **A per-branch `info` metadata bag (`map`/`Any`/`repeated NamedValue`).**
  Rejected as redundant with `events`. State writes are events; the only
  fork-level datum is `reason`.

- **A second, architecture-specific serialized trace format.** Rejected:
  the generic format is lossless, so a typed format is a projection, not a
  source of truth — a parallel wire format would only drift.

- **`google.protobuf.Any` for the architecture-specific payload.**
  Rejected in favor of proto extensions. `Any` buys decentralized,
  package-namespaced identity allocation, which only matters when schema
  authors are external and uncoordinated. 4ward is the single authority for
  architecture schemas, so it allocates extension field numbers without
  collision, and extensions give typed generated accessors (no
  pack/unpack, no descriptor dependency) while keeping the core message
  stable. `Any` remains correct only if architectures could be authored
  outside 4ward's coordination.

## Migration note

This is not a find-and-replace of `parallel`→`Replication`. Recirculate
and resubmit are reclassified from "parallel fork" to **`Continuation` —
not a fork at all**, which resolves the original question that motivated
this work ("why is recirculate a replication fork?" — it should not be).
`docs/ARCHITECTURE.md` must redefine "fork" as `Replication`|`Choice`, add
"Continuation", and carry a revision note explaining the reclassification
rather than silently relabeling.
