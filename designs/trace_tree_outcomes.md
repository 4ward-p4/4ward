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
  multicast members, preserved metadata. This axis never lives in proto
  structure: it is carried entirely by **typed trace events**, with each
  outcome referencing the event that *caused* it (detailed under *Causes*,
  below).

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

message TraceEvent {
  uint64 id = 1;                    // trace-global, referenceable (see "Causes")
  oneof event {
    TableLookup          table_lookup = 2;
    MulticastGroupLookup mcast_lookup = 3;
    CloneSessionLookup   clone_lookup = 4;
    FieldWrite           write        = 5;
    MarkToDrop           mark_to_drop = 6;
    // … per-architecture events, added via extension …
  }
}

// `cause` is the id of the typed event that triggered this branch.
message Replication  { uint64 cause = 1; repeated TraceTree branches = 2; }  // branches ≥ 1
message Choice       { uint64 cause = 1; repeated TraceTree branches = 2; }  // branches ≥ 2
message Continuation { optional uint64 cause = 1; TraceTree next = 2; }      // exactly one successor
message Output       { PortRef port = 1; bytes packet = 2; }                 // the result; no cause
message Drop         { optional uint64 trigger = 1; }                        // see "Drop" below
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

**Arity invariants** make meaningless shapes unrepresentable: `Choice` has
≥2 branches (a choice among one is not a choice), `Replication` has ≥1,
`Continuation` has exactly one successor, leaves have none.

### Vocabulary

Consistent across proto, Kotlin, and docs:

- **outcome** — how a node ends: a fork, a continuation, or a leaf.
- **fork** — a `Replication` or a `Choice` (a node with ≥2 branches). A
  `Continuation` is **not** a fork; `Output`/`Drop` are **leaves**.
- **branch** — one subtree under a fork.
- **pass** — one parser-to-deparser traversal (one re-parse of the packet);
  passes are linked by `Continuation`s.
- **cause** — the id of the typed event that triggered a fork or backward
  continuation. Replaces the old free-string `reason`.

This **retires** the `parallel` / `alternative` terminology and the
`ForkMode` / `forkModeOf` machinery. "Parallel" was never a clean antonym
of "alternative" and actively misled by lumping continuations under it.

### Causes: a reference, not a string

A fork does not carry *why* it happened as a string. The cause of a branch
is always a typed event that already exists in the trace — a `Replication`
follows a `MulticastGroupLookup` or `CloneSessionLookup`; a `Choice`
follows an action-selector apply; a backward `Continuation` follows a
recirculate/resubmit primitive. So the outcome simply **references** that
event by its `id`. A free-string `reason` would re-encode information the
event already holds — the same redundancy we reject for the `info` bag and
for per-branch state, applied consistently.

The reference is a **trace-global event id**, not a positional index,
because the triggering event may live in an *ancestor* node — e.g.
`mark_to_drop` fires mid-ingress, the packet is then cloned, and the
*original* drops at the boundary; the `Drop` (in a child subtree) must
reference the `mark_to_drop` (in the parent). A global id reaches across
the tree and survives reshaping (renderer flattening, pruning) that would
invalidate indices. Resolving a reference is a one-pass `id → event` walk,
and the referenced event is the *data-flow* cause — the last writer of the
forwarding decision, wherever it sits.

A forward `Continuation` (plain ingress→egress flow) has no triggering
primitive, so its `cause` is absent; the stage it enters is identified by a
typed entry event at the head of its `next` subtree.

Two invariants keep references sound. **Referential integrity:** every
`cause`/`trigger` must resolve to an event `id` that exists in the trace
(an ancestor's or this node's) — a validator checks this and fails loud, so
a dangling reference is a build error, never a silent mystery.
**Deterministic ids:** the producer assigns ids as a counter in emission
order, so the same input yields the same ids and golden traces are stable.
Ids are an internal handle, unique within one trace and meaningless across
traces; consumers that diff traces should compare *structure and resolved
events*, not raw id values.

### Drop

`Drop` is the one outcome that can occur by the **absence** of any
decision — the packet reaches the end of the pipeline with no forwarding
decision, so there is no event to reference. Every other outcome requires
an explicit trigger; drop is the default fate. So `Drop` carries an
**optional** trigger:

- **`trigger` present** — an explicit cause; the referenced event's *type*
  says which: `MarkToDrop`, a `FieldWrite` setting `egress_spec` to the
  drop value, or a `MulticastGroupLookup` miss (multicast to an empty
  group). No enum of drop reasons — the event already classifies it.
- **`trigger` absent** — the packet fell off the end with no decision. This
  is the only drop with no event behind it.

Note what is deliberately *not* a drop cause: a **table miss** is not a
drop. A miss runs the table's default action; whatever that action does
(possibly `mark_to_drop`, possibly nothing) determines the fate. The miss
itself is recorded as `hit:false` on the `TableLookup` event, not as a
drop category.

### Output and ports

`Output { PortRef port; bytes packet }` carries the egress port as a
**neutral port identity** — the value the program/architecture computed,
nothing more. The core proto holds no special-port enum. Special
destinations are handled without inflating it:

- **recirculation / resubmission** is a `Continuation`, not an `Output`.
- **drop** is a `Drop`, not an output to a "drop port".
- **to-CPU (packet-in), pipe-qualified ports, flood** are *architecture
  interpretations* of the port identity — applied by an architecture lens
  or read from a typed event — never core fields. A v1model port and a
  TNA pipe-qualified port are the same neutral `PortRef` to a generic
  consumer; only an architecture-aware one decodes the structure.

This keeps the one place that *is* load-bearing (the result packet and
where it left) typed and universal, while refusing to let architecture port
taxonomies leak into the core.

### One cause per fork; nest for multiplicity

A fork has a single `cause`; all its branches stem from that one
operation. Concurrent effects at one boundary are modeled as **nested
forks**, each with its own cause, not as one fork with mixed per-branch
causes.

Replication operations compose on "the original": clone splits off a copy
and the original continues; multicast then fans the original out. So
clone + multicast at end-of-ingress is (each fork's `cause` references the
clone-session / multicast-group lookup that produced it):

```
Replication (cause: clone-session lookup)
├─ original  ── Replication (cause: multicast-group lookup)
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
Replication (cause: clone-session lookup)
├─ original  ── Continuation (cause: recirculate primitive) → next pass …
└─ mirror-copy ── Output
```

### Continuation handles all stage transitions, not just loopback

A `Continuation` means "the same packet continues as another pass." It is
**not** specialized to looping back. The multiplicity at a transition picks
the outcome:

| transition | outcome |
|---|---|
| forward, single packet (one re-parse) | `Continuation`, no cause |
| forward, replicated (multicast at the TM) | `Replication` |
| forward, nondeterministic routing | `Choice` |
| backward (recirculate, resubmit) | `Continuation`, cause = the primitive |

**A pass is one parser-to-deparser traversal — one re-parse of the
packet — and a `Continuation` marks exactly a re-parse.** This makes
pass-cutting a fact about the architecture, not the trace author's taste:
an architecture emits a `Continuation` precisely where it hands the packet
to a parser again, and the `next` subtree begins with that parser/entry
event. So the cut points are verifiable, and the next pass's events are
cleanly scoped to their own parse.

This is deliberately *architecture-determined*, not uniform: PSA and TNA
have separate ingress and egress parsers, so ingress→egress re-parses and
is a forward `Continuation`; v1model parses once, so its ingress and egress
sit in **one pass** (one node, modulo forks) with no `Continuation` between
them. That difference is real — the architectures genuinely re-parse
different numbers of times — so the traces *should* differ here rather than
be forced into a common shape. The proto never needs to know how many
passes an architecture has; the combine basis `{Replication, Choice,
Continuation, Output, Drop}` is unchanged by multi-pipe or N-stage
architectures.

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
thing genuinely attached to a fork is its `cause` reference (and that, too,
points *into* the event stream rather than duplicating it). Load-bearing
result data (the egress port and bytes at an `Output`) stays typed on the
leaf, because that is the simulation's product, not annotation.

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
`reason`), and both record **hits and misses**. The `Replication`'s `cause`
references the lookup event directly:

```
events: [ #1 write meta.mcast_grp = 7,
          #2 mcast_lookup group=7 → members [(port 5, rid 0), (port 68, rid 1)] ]
Replication (cause: #2)
 ├─ rid0  events:[ write egress_port=5,  … ] Output(5)
 └─ rid1  events:[ write egress_port=68, … ] Continuation (cause: recirculate) …
```

A miss — multicast to an unconfigured group, a common bring-up bug —
becomes visible instead of a silently vanished packet; the `Drop` simply
points at the miss event:

```
events: [ #1 write meta.mcast_grp = 9,
          #2 mcast_lookup group=9 → MISS (no entry) → 0 replicas ]
Drop (trigger: #2)
```

These are standard P4Runtime PRE concepts, so typed lookup events belong
in the shared proto; an architecture without a PRE simply never emits
them — and never emits a `Replication` whose cause is a multicast lookup.

### One format; architecture-specific typing via shared schemas

There is **one** serialized trace format: the architecture-neutral tree
above. It is **lossless** — events capture all state, outcomes reference
their causes by id, PRE lookups capture all replication config — so any
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

3. **Architecture-specific event types — proto extensions.** New
   per-architecture events (and the rare node-level payload that is not
   naturally an event) are carried by **`extend`ing** the core message from
   each architecture's own proto, rather than editing the core oneof:

   ```proto
   // core trace proto (edition 2024) — declares the point, then never changes:
   message TraceEvent { /* id + universal events … */ extensions 1000 to max; }

   // v1model_trace.proto, owned by 4ward, allocated 1000:
   extend TraceEvent { V1ModelEvent v1model = 1000; }
   // tna_trace.proto, allocated 1001 — core proto untouched:
   extend TraceEvent { TnaEvent tna = 1001; }
   ```

   This is a good fit **because 4ward is the central authority for
   architecture schemas.** Extensions need globally-unique field numbers;
   4ward allocates them, in one repo, so there is no collision risk. The
   many consumers are *consumers* of these schemas, not authors of new
   ones. And the mechanism preserves neutrality: the core `TraceEvent`
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
  (closed), with causes carried by typed events.

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
  fork-level datum is the `cause` reference into the event stream.

- **A free-string `reason` on each fork/continuation.** Rejected for the
  same redundancy: a branch's cause is always a typed event already in the
  trace, so the outcome references it by id instead. `Drop` is the sole
  carrier of cause information not in an event — its by-absence case — and
  even that is an *optional missing trigger*, not a string.

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

The `reason` enum is *removed*, not renamed: causes become references to
typed events. A consumer that read `Fork.reason == MULTICAST` instead
resolves the outcome's `cause` event (the `MulticastGroupLookup`) — the
same pattern existing `TableLookupEvent` / `CloneSessionLookupEvent`
consumers already use, including the reproduce-trace entity extraction.
