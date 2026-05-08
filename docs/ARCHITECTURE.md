# 4ward Architecture

This is the "how does this thing actually work?" document. It walks through the
components, how they talk to each other, and why we made the choices we did.

If you just want to use 4ward, the [README](../README.md) is all you need. If you
want to hack on it, keep reading.

## Design goal: spec-compliant reference implementation

4ward's primary goal is to be a faithful implementation of the
[P4₁₆ language specification](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html).
Every language feature should behave exactly as the spec describes. When the
spec is ambiguous, we follow p4c's reference compiler behavior and document
the ambiguity. When the spec is clear, we follow it — even if BMv2 or other
implementations do something different.

This means correctness always wins over convenience, performance, or
compatibility with non-standard behavior. The simulator is meant to be the
implementation you trust when you need to know what a P4 program *should* do.

## Design philosophy: do it right

We always favor the cleanest solution, even when it requires disruptive
refactoring. Expedient hacks accumulate into architectural debt that slows
everything down. Doing things the right way — clean abstractions, proper
separation of concerns, no shortcuts — is what gives us an edge over
codebases that have grown organically over many years.

Concretely:
- **Clean abstractions over expedient hacks.** If the right solution requires
  touching 20 files, touch 20 files. Don't add a special case to avoid a
  refactoring.
- **Correctness over performance.** Always. This is a reference implementation.
- **Readability over cleverness.** Code should be obvious to the next person
  (or agent) who reads it. If it needs a comment explaining *what* it does,
  it should be rewritten.
- **Test-driven confidence.** The failing-test list *is* the feature backlog.
  `bazel test //...` is the definition of done.

The north star is to replace BMv2 as the reference simulator in
[DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas). See
[ROADMAP.md](ROADMAP.md) for the full picture.

## The big picture

```
┌─────────────────────────────────────────────────────────────────┐
│ compile time                                                    │
│                                                                 │
│  program.p4 ──▶ p4c + 4ward backend ──▶ PipelineConfig.txtpb  │
│                        (C++)               (proto text format)  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│ runtime                                                         │
│                                                                 │
│  Controller ──P4Runtime──▶ P4Runtime server                     │
│                                  │                              │
│                                  ▼                              │
│  packet ──────────────────▶ 4ward sim ──▶ output packets       │
│                               (Kotlin)  └▶ execution trace      │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### p4c backend (`p4c_backend/`) — the translator

A C++ p4c backend plugin that turns your P4 source into something the simulator
can understand. It runs after p4c's midend simplification passes and emits a
`PipelineConfig` proto. By this point the program is fully elaborated:
generics instantiated, constants folded, header stacks concretized, no abstract
types. Nice and clean for the simulator to interpret.

### Proto IR (`simulator/ir.proto`) — the contract

This is the heart of the project: the intermediate representation that the
backend produces and the simulator consumes. Two design choices worth
highlighting:

**Names, not IDs.** Everything is referenced by human-readable string names.
Numeric IDs only show up in p4info (for P4Runtime). This means you can actually
read a `PipelineConfig` without a lookup table — just open the textproto and
it makes sense.

**Type-complete expressions.** Every `Expr` node carries a `Type` annotation.
The simulator never guesses bit widths; p4c already figured that out.

### Simulator shared types (`simulator/simulator.proto`)

Proto definitions shared between the simulator library and its callers (P4Runtime
server, STF runner, trace tree tests). Defines `InjectPacketRequest`/`Response`,
output packets, trace trees, and the Dataplane gRPC service.

### Simulator (`simulator/`) — where the magic happens

A Kotlin/JVM library that walks the proto IR and actually *runs* your P4
program, one packet at a time. Callers instantiate `Simulator` directly and
use its typed API (`loadPipeline`, `processPacket`, `writeEntry`, `readEntries`).

```
Simulator.kt             Top-level state: pipeline config, table entries
Interpreter.kt           The big one: IR tree-walker for parsers, controls, actions
Environment.kt           Variable bindings, packet state (headers + metadata)
Values.kt                Runtime value types (BitVector, BoolVal, HeaderVal, ...)
BitVector.kt             Bit-precise integer arithmetic (backed by BigInteger)
Architecture.kt          Interface for architecture-specific behavior
V1ModelArchitecture.kt   v1model specifics: recirculate, clone, resubmit, drop
PSAArchitecture.kt       PSA specifics: two-pipeline orchestration, PSA externs
ExternHandler.kt         Pluggable extern dispatch (architecture-provided)
```

We use `BigInteger` for all `bit<N>` arithmetic because life is too short for
overflow bugs at arbitrary bit widths.

## Architecture genericity

P4 has several architectures (v1model, PSA, PNA, TNA) and they all do things a
little differently:

1. **Pipeline structure**: which parsers/controls run and in what order.
2. **Standard metadata**: the metadata struct passed between stages.
3. **Extern semantics**: what `clone3()`, `resubmit()`, etc. actually *do*.

4ward handles this through the `Architecture` interface in `Architecture.kt`.
Point 1 is captured structurally in `Architecture.stages` in the IR proto.
Points 2 and 3 live in per-architecture Kotlin code — each architecture gets
its own implementation.

The interpreter (`Interpreter.kt`) is designed to be a pure IR tree-walker —
evaluating expressions, walking control flow, performing table lookups, and
managing variable scopes. Architecture-specific extern dispatch, fork
semantics, and pipeline orchestration belong in the architecture layer.

**Current status:** v1model, PSA, and PNA are all implemented. The interpreter is
a pure IR tree-walker with no architecture-specific code — extern dispatch,
fork semantics, and pipeline orchestration all live in the architecture layer
(`V1ModelArchitecture.kt`, `PSAArchitecture.kt`). See [ROADMAP.md](ROADMAP.md)
Track 6 for the multi-architecture plan.

## Testing strategy

See [TESTING_STRATEGY.md](TESTING_STRATEGY.md).

## Trace trees

4ward's output format is a **trace tree** (`TraceTree` in `simulator.proto`) —
a recursive structure where each node contains a sequence of events and an
optional fork. At non-deterministic choice points (action selectors, clone,
multicast), execution forks into branches, one per possible outcome. A program
with no non-determinism produces a zero-fork tree that is structurally
equivalent to a flat trace — there is no separate "flat trace" format.

The key insight: even when choices like action selector hashing are technically
deterministic, it's often more useful to reason about them as
non-deterministic — "what *could* happen to my packet?" rather than "what
happens with this specific hash seed?"

### Parallel vs alternative forks

Not all forks are created equal. The simulator distinguishes two kinds of
nondeterminism (see `ForkMode` and `forkModeOf` in `TraceHelpers.kt`):

- **Parallel forks** (clone, multicast, resubmit, recirculate) — all branches
  execute simultaneously in a single real execution. The output packets are the
  union of all branch outputs.
- **Alternative forks** (action selector) — exactly one branch executes at
  runtime (determined by a hash function). Each branch represents one *possible
  world*; the trace tree explores all of them.

This distinction matters when collecting output packets from the tree:
`collectPossibleOutcomes()` in `Simulator.kt` returns a `List<List<OutputPacket>>`
where each inner list is one possible set of outputs from a single real execution.
Parallel branches are combined (union), while alternative branches produce separate
possible worlds (Cartesian product when nested inside parallel forks).

**Status:** Complete. The simulator produces full trace trees with forking at
all non-deterministic choice points: action selectors, clone (I2E/E2E),
multicast replication, resubmit, and recirculate.

**Why this matters:**

No other P4 tool gives you this. BMv2 picks one path. Hardware picks one path.
4ward can show you *all* paths — making it a powerful tool for testing,
verification, and understanding complex P4 programs.

## gRPC services (`grpc/`)

The P4Runtime gRPC server is a thin translation layer between standard
P4Runtime RPCs and the simulator's typed API. All P4 logic stays in the
simulator — the server just speaks gRPC.

```
Controller ──gRPC──▶ P4RuntimeService ──▶ Simulator
                           │                    │
                     translates protos      runs your program
                     enforces preconditions  returns typed results
```

**Implemented RPCs:**

| RPC | Status |
|-----|--------|
| `SetForwardingPipelineConfig` | Working — parses `DeviceConfig` from `p4_device_config` bytes |
| `Write` | Working — forwards `Update` protos directly to the simulator |
| `Read` | Working — wildcard, per-table, and per-entry reads |
| `StreamChannel` | Working — arbitration + PacketOut→PacketIn via the simulator |
| `GetForwardingPipelineConfig` | Working — returns p4info and/or device config per response type |
| `Capabilities` | Working — returns P4Runtime API version |

**Key design decisions:**

- **In-process library, not subprocess.** The server calls `Simulator` methods
  directly — no serialization overhead, no process management.
- **grpc-kotlin with coroutine Flows.** `StreamChannel` is a bidirectional
  stream — Kotlin Flows map naturally to gRPC's streaming model.
- **`Mutex` for coroutine-safe serialization.** The simulator is
  single-threaded; the gRPC server serializes concurrent requests with a
  `kotlinx.coroutines.sync.Mutex` shared between P4RuntimeService and
  DataplaneService.
- **Full arbitration state machine (§5).** Election ID-based primary
  selection with backup notification. The highest `election_id` becomes
  primary and may write; all controllers may read.

## Why these languages?

- **Kotlin**: sealed classes and `when` expressions are *perfect* for
  interpreting a tree-structured IR. Plus, `BigInteger` handles arbitrary-width
  bit vectors without us having to write our own bignum library (no thank you).
- **C++**: p4c is C++, so the backend has to be C++. Simple as that.

## Why proto edition 2024?

Because we'd rather adopt the latest thing from day one than migrate later. Proto
editions replace the old proto2/proto3 split with fine-grained feature flags —
less baggage, more flexibility.
