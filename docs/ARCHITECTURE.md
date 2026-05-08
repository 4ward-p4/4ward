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
│                   P4Runtime writes ──▶ ┐                        │
│                   (table entries,      │                        │
│                    counters, etc.)     ▼                        │
│                              ┌───────────────┐                  │
│               packet ──────▶ │     4ward     │──▶ output pkts  │
│                              │   Simulator   │──▶ trace tree    │
│                              └───────────────┘                  │
└─────────────────────────────────────────────────────────────────┘
```

That's the core loop: compile a P4 program, load the config, push packets
through, get traces out. Everything else is plumbing around that loop.

Here's how all the pieces fit together:

```
                    program.p4
                        │
                        ▼
               p4c + 4ward backend ─────────────────────────── p4c_backend/
                        │
                        ▼
                 PipelineConfig.txtpb
                        │
        ┌───────────────┼───────────────────┐
        │               │                   │
        ▼               ▼                   ▼
   4ward CLI        STF runner         FourwardServer ──────── grpc/
   (compile,        (e2e tests)             │
    sim, run)           │          ┌────────┴────────┐
        │               │         │                  │
        │               │    P4RuntimeService   DataplaneService
        │               │         │                  │
        ▼               ▼         ▼                  ▼
       ┌─────────────────────────────────────────────────┐
       │                  Simulator                       │── simulator/
       │                  (Kotlin)                        │
       │                                                  │
       │  Interpreter ── Environment ── TableStore        │
       │       │                                          │
       │  V1Model / PSA / PNA Architecture                │
       └──────────────────────┬──────────────────────────┘
                              │
                     output packets + trace tree

                    ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─

        C++ consumers see only this:

        FourwardServer::Start() ──▶ DataplaneClient ────── fourward_cc/
              │                          │
              │                    InjectPacket()
              ▼                    InjectPackets()
         gRPC channel              SubscribeResults()
         (localhost)
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

Proto definitions shared between the simulator library and everyone who talks
to it: the gRPC services, the STF runner, the trace tree tests. This is
where `InputPacket`, `OutputPacket`, and the trace tree types live.

### Simulator (`simulator/`) — where the magic happens

A Kotlin/JVM library that walks the proto IR and actually *runs* your P4
program. Callers instantiate `Simulator` directly and use its typed API
(`loadPipeline`, `processPacket`, `writeEntry`, `readEntries`). Packet
processing is fully parallelized: fork branches within a single packet run
concurrently, and `InjectPackets` sends many packets through the pipeline at
once.

```
Simulator.kt             Top-level state: pipeline config, table entries
Interpreter.kt           The big one: IR tree-walker for parsers, controls, actions
Environment.kt           Variable bindings, packet state (headers + metadata)
Values.kt                Runtime value types (BitVal, BoolVal, HeaderVal, ...)
BitVector.kt             Bit-precise integer arithmetic (Long for ≤63 bits, BigInteger for wider)
Architecture.kt          Interface for architecture-specific behavior
V1ModelArchitecture.kt   v1model specifics: recirculate, clone, resubmit, drop
PSAArchitecture.kt       PSA specifics: two-pipeline orchestration, PSA externs
PNAArchitecture.kt       PNA specifics: main-control orchestration, PNA externs
ExternHandler.kt         Pluggable extern dispatch (architecture-provided)
```

Life is too short for overflow bugs at arbitrary bit widths — but it's also
too short to allocate `BigInteger` objects for every 16-bit field. `BitVector`
uses a primitive `Long` for fields up to 63 bits (the vast majority) and
falls back to `BigInteger` for the rare wide ones. Zero heap allocation on
the hot path; correct at any width.

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
(`V1ModelArchitecture.kt`, `PSAArchitecture.kt`, `PNAArchitecture.kt`). See
[ROADMAP.md](ROADMAP.md) Track 6 for the multi-architecture plan.

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

The `grpc/` directory hosts two gRPC services. All P4 logic stays in the
simulator — the services just speak gRPC, validate inputs, and translate
between P4Runtime's wire format and the simulator's typed API.

```
Controller ──P4Runtime──▶ P4RuntimeService ──▶ Simulator
                                                   ▲
packet ──Dataplane RPC──▶ DataplaneService ──▶ PacketBroker
                                                   │
                                            output packets + trace
```

### P4RuntimeService

The standard P4Runtime gRPC server. Before a write reaches the simulator it
passes through a gauntlet of validation: field widths and match kinds
(`WriteValidator`), `@refers_to` referential integrity
(`ReferenceValidator`), `@entry_restriction`/`@action_restriction`
constraints (`ConstraintValidator`), `@p4runtime_translation` type
translation (`TypeTranslator`), and role-based access control (`RoleMap`).

| RPC | What it does |
|-----|--------------|
| `SetForwardingPipelineConfig` | Parses `DeviceConfig` from `p4_device_config` bytes, loads pipeline |
| `Write` | Validates, translates, forwards to the simulator |
| `Read` | Wildcard, per-table, and per-entry reads |
| `StreamChannel` | Arbitration + PacketOut/PacketIn |
| `GetForwardingPipelineConfig` | Returns p4info and/or device config |
| `Capabilities` | Returns P4Runtime API version |

### DataplaneService

A second gRPC service for test tooling that wants to inject packets and
inspect outputs without going through P4Runtime's `PacketOut`/`PacketIn`
ceremony. Defined in `grpc/dataplane.proto`.

| RPC | What it does |
|-----|--------------|
| `InjectPacket` | Send one packet in, get outputs + trace back |
| `InjectPackets` | Stream many packets concurrently for throughput |
| `SubscribeResults` | Observe results from *all* injection sources |
| `RegisterPrePacketHook` | Get called before every packet — with the write lock held |

Behind both services sits `PacketBroker`, which coordinates everything:
holds the write lock (shared with P4RuntimeService), fires pre-packet
hooks, dispatches to the simulator, and fans out results to subscribers.

### Key design decisions

- **In-process library, not subprocess.** The server calls `Simulator` methods
  directly — no serialization overhead, no process management.
- **grpc-kotlin with coroutine Flows.** `StreamChannel` and
  `RegisterPrePacketHook` are bidirectional streams — Kotlin Flows map
  naturally to gRPC's streaming model.
- **`Mutex` for coroutine-safe serialization.** Control-plane writes go
  through a `kotlinx.coroutines.sync.Mutex` shared between P4RuntimeService
  and DataplaneService. Data-plane reads (packet processing) are lock-free —
  they read from a published snapshot, so packets never block on writes.
- **Full arbitration (§5).** Not a subset — the complete state machine,
  including demotion notifications and automatic promotion on disconnect.

## C++ embedding API (`fourward_cc/`)

Want to use 4ward from C++? `FourwardServer` is an RAII handle to a 4ward
gRPC server running as a child process — `Start()` spawns it, the
destructor kills it. `DataplaneClient` sits on top and gives you an
ergonomic C++ interface for injecting packets and reading results. Your
project sees a C++ API and a Bazel `deps` entry; the Kotlin server is an
implementation detail. See the
[embedding guide](../userdocs/reference/embedding-cc.md).

## Why these languages?

- **Kotlin**: sealed classes and `when` expressions are *perfect* for
  interpreting a tree-structured IR. The JVM gives us `BigInteger` for
  arbitrary-width bit vectors (no hand-rolled bignum library, thank you)
  and a mature concurrent runtime that makes parallelizing packet processing
  almost free.
- **C++**: p4c is C++, so the backend has to be C++. The embedding API
  (`fourward_cc/`) is C++ because that's what its consumers speak (PINS,
  DVaaS).

## Why proto edition 2024?

Because we'd rather adopt the latest thing from day one than migrate later. Proto
editions replace the old proto2/proto3 split with fine-grained feature flags —
less baggage, more flexibility.
