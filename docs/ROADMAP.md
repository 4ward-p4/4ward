# 4ward — Roadmap

This document describes the main development tracks, their priorities, and how
they can be parallelized. See [STATUS.md](STATUS.md) for daily progress and
[ARCHITECTURE.md](ARCHITECTURE.md) for design details.

## North star

Two goals, mostly orthogonal:

1. **Replace BMv2 in [DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas).**
   DVaaS (Dataplane Validation as a Service) validates switch behavior by
   sending synthesized packets to both a switch under test and a reference P4
   simulator, then comparing outputs. Today that reference simulator is BMv2.

2. **Full [SAI P4](https://github.com/sonic-net/sonic-pins/tree/main/sai_p4) support.**
   SAI P4 is a 30-table v1model program that models the SONiC Switch
   Abstraction Interface. It makes heavy use of features that BMv2 handles
   poorly or not at all — `@p4runtime_translation`, `@entry_restriction` /
   `@action_restriction`
   ([p4-constraints](https://github.com/p4lang/p4-constraints)),
   `@refers_to` — requiring DVaaS to maintain a "BMv2-compatible config" that
   papers over the gaps.

4ward aims to handle all of this correctly, out of the box:

- **Spec compliance without workarounds.** Full support for
  `@p4runtime_translation` (including SAI P4's convention of string-translated
  port IDs), p4-constraints validation, and all v1model features SAI P4 uses.
- **Trace trees replace brittle hacks.** DVaaS currently uses hacks to
  extract all possible traces from BMv2 at non-deterministic choice points
  (e.g., action selectors), but this is brittle and inefficient. 4ward
  produces trace trees natively — all valid outputs in a single pass.
- **Richer traces.** DVaaS already augments test vectors with packet traces.
  4ward's structured proto traces are more powerful than BMv2's textual
  traces.
- **Easy to extend and evolve.** BMv2 is a large C++ codebase that has grown
  organically over many years — adding features or fixing spec compliance gaps
  requires deep familiarity with its internals. 4ward is designed from the
  ground up to be approachable for any contributor, human or AI.

### Why 4ward is easier to extend

- **AI-first development.** 4ward is written from the ground up by AI coding
  agents, using prompting only. The codebase, docs, and test infrastructure
  are designed so that an agent can pick up a failing test, implement the
  missing feature, and land a correct change — autonomously. If an AI agent
  can extend it, anyone can.
- **Kotlin's type system as a guardrail.** Sealed classes and exhaustive
  `when` expressions mean the compiler enforces completeness — add a new IR
  node and the compiler tells you every place that needs handling. No silent
  fallthrough, no missing cases. The type system catches what the agent
  misses, making it safe to move fast.
- **Testing from day one.** Fast CI on GitHub Actions, extensive e2e test
  coverage (STF corpus, p4testgen path coverage), and unit tests for every
  tricky invariant. Testing is not an afterthought — it's what
  drives development. The failing-test list *is* the feature backlog, and
  `bazel test //...` is the definition of done. See
  [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for the full philosophy.
- **Architecture pluggability.** Adding a new P4 architecture means
  implementing a Kotlin interface, not forking the codebase.

### Keeping it easy: the strategy

- **Readability over performance.** Always.
- **One contract, two codebases.** The proto IR is the only interface between
  backend and simulator — extend either side independently.
- **Outstanding CI, automation, and docs.** With AI, these are cheap to build
  and maintain — so there's no excuse not to have excellent ones.

This north star connects all the tracks: v1model completeness (track 1) makes
4ward a credible reference, trace trees (track 3) make it *better* than BMv2,
the P4Runtime server (track 4) is the integration point — with p4-constraints
support making it a P4Runtime reference implementation in its own right — and
architecture customization (track 5) is what makes SAI P4 work end-to-end
without the hardcoded hacks the ecosystem relies on today.

## Tracks

### Track 1: v1model spec compliance

**Priority: top | Parallelizable: yes (within and across subtracks)**

Complete the v1model reference implementation. This is the foundation —
without it, trace trees aren't useful, P4Runtime is limited, and the
"spec-compliant reference implementation" claim doesn't hold.

Three subtracks, each a different testing methodology:

- **1A: STF corpus** — make every v1model-capable p4c STF test pass.
  See [STATUS.md](STATUS.md) for current counts and remaining gaps.
- **1B: p4testgen** — unpin `max_tests = 1` limits, expand from 10 programs
  to the full passing corpus, fix new failures. Deeper path coverage than
  hand-written STFs.
- **1C: BMv2 diff testing** — run the same inputs through BMv2 and 4ward,
  compare outputs. Catches gaps that STF tests miss.

**Done when:** all v1model-capable corpus tests pass (1A), p4testgen runs
unpinned across the full corpus (1B), and BMv2 diff testing surfaces no
mismatches (1C).

**Current status:** 1A complete. 1B complete. 1C complete. See
[LIMITATIONS.md](LIMITATIONS.md) for remaining gaps.

### Track 2: infrastructure

**Priority: nice to have | Parallelizable: yes**

Build plumbing, CI improvements, cleanup. See [REFACTORING.md](REFACTORING.md)
for the full list.

**Current status:** p4testgen batching done. Remaining items tracked in
[REFACTORING.md](REFACTORING.md).

### Track 3: trace trees

**Priority: medium — start now | Parallelizable: yes**

The killer feature. Fork at non-deterministic choice points (action selectors,
profiles, replication) and return a tree of all possible executions. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the full design vision.

Work breakdown:

1. **Proto + golden tests.** `TraceTree` schema, golden test harness,
   failing tests covering all fork types.
2. **Zero-fork tree.** Simulator produces `TraceTree` instead of flat `Trace`.
   Existing tests pass unchanged; `no_fork` golden test passes.
3. **Action selector forking.** IR support, deep copy, `TraceTreeBuilder`,
   interpreter forks at selector tables.
4. **Clone + multicast forking.** Fork at clone/multicast points.

**Done when:** all golden trace tree tests pass and all existing corpus tests
still pass.

**Current status:** complete. All fork types (action selector, clone,
multicast) implemented and tested.

### Track 4: P4Runtime reference implementation

**Priority: medium — start now | Parallelizable: yes**

A gRPC server that speaks P4Runtime to controllers and forwards requests to
the simulator. Goals:

- **Correctness, simplicity, readability.** Not performance.
- **100% spec-compliant.** Follow the P4Runtime spec to the letter.
- **`@p4runtime_translation`.** Bidirectional translation between
  controller-facing and data-plane values. SAI P4 translates all ID types
  (including ports) to strings via `@p4runtime_translation("", string)`.
  The spec is underspecified here; fill gaps guided by SAI P4 conventions.
- **[p4-constraints](https://github.com/p4lang/p4-constraints).** Validate
  table entries and action parameters against `@entry_restriction` and
  `@action_restriction` annotations at write time.
- **Strict validation.** Enforce message validity rigorously. `--strict`
  mode additionally enforces recommended (not just required) properties.
- **Actionable error messages.** Not `INVALID_ARGUMENT`, but
  `table 'ipv4_table' entry violates constraint: vrf_id must be non-zero`.

Five subtracks:

- **4A: test strategy.** Three layers mirroring the simulator's approach:
  conformance tests (spec compliance), round-trip testing (simulator
  agreement), and fuzz testing (robustness). See
  [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for the philosophy and
  [P4RUNTIME_COMPLIANCE.md](P4RUNTIME_COMPLIANCE.md) for the spec
  requirement matrix.
- **4B: p4-constraints.** JNI binding to
  [p4-constraints](https://github.com/p4lang/p4-constraints), validate
  `@entry_restriction` / `@action_restriction` at write time, actionable
  error messages. Core north-star requirement — SAI P4 depends on it heavily.
- **4C: string translation.** Subsumed by track 5.
- **4D: write validation & RPCs.** Richer write validation (match field
  completeness, param bitwidths, per-entity reads), implement
  `GetForwardingPipelineConfig` + `Capabilities`. Spec compliance polish.
- **4E: SAI P4 E2E.** Subsumed by track 5.

**Done when:** SAI P4 works end-to-end through standard P4Runtime:
`SetForwardingPipelineConfig`, `Write` (with p4-constraints and
`@p4runtime_translation` validation), `Read`, and `StreamChannel` packet I/O.

**Current status:** 4A–4E complete, including `@refers_to` referential
integrity enforcement. See [P4RUNTIME_COMPLIANCE.md](P4RUNTIME_COMPLIANCE.md)
for the compliance matrix.

### Track 5: architecture customization

**Priority: medium — start now | Parallelizable: yes**

4ward is a spec-compliant v1model implementation — but real deployments don't
always use stock v1model. SAI P4, for example, needs wider port IDs (more than
2^9 ports) and string-translated port names via `@p4runtime_translation`. Today,
the ecosystem works around this with hardcoded hacks scattered across the stack.
4ward can do better: support lightly modified architecture definitions cleanly,
without special-casing.

The principle: **the simulator should derive architecture parameters from the IR,
not from hardcoded constants.** If someone changes `typedef bit<9> PortId_t` to
`bit<32>` in their v1model.p4, it should just work — the IR already carries the
correct types.

Three concrete goals drive this track:

1. **Dynamic standard_metadata.** Derive all field widths from the IR's struct
   definition at pipeline load time. No hardcoded `PORT_BITS = 9`. Semantic
   constants like `DROP_PORT` are derived (all-ones of the actual port width),
   not hardcoded to 511.

2. **P4Runtime type translation for match fields and packet metadata.**
   Complete the `TypeTranslator` integration so that `@p4runtime_translation`
   works end-to-end — not just action parameters (which work today), but also
   match fields and PacketIn/PacketOut metadata. This is what makes string port
   names work.

3. **SAI P4 end-to-end.** Compile SAI P4 against a modified v1model with wider
   `PortId_t` and string port names via `@p4runtime_translation`. Load it into
   4ward, install entries, send packets, verify outputs. The proof that it all
   comes together.

**Done when:** SAI P4 works end-to-end with a modified v1model through standard
P4Runtime.

### Track 6: multi-architecture support

**Priority: next | Parallelizable: yes (across phases)**

Supporting multiple P4 architectures isn't primarily about the architectures
themselves — it's about **proving the architecture boundary is real.** Today,
`Architecture.kt` defines a clean interface, but v1model-specific code bleeds
into the interpreter (extern dispatch, fork types, branch modes) and packet
context (clone/resubmit/recirculate flags). Adding a second architecture means
confronting every coupling point and fixing it properly — favoring the cleanest
solution even when it requires disruptive refactoring.

Three phases, each building on the last:

#### Phase 1: refactor the architecture boundary

Pure refactoring — no new functionality, all 186 v1model tests as safety net.

The interpreter today is 95% architecture-agnostic but has v1model baked into
three places that need to be cleaned up:

1. **Extern dispatch.** `Interpreter.execExternCall()` hardcodes 12 v1model
   extern functions (`mark_to_drop`, `clone`, `resubmit`, `verify_checksum`,
   etc.). Move extern dispatch to architecture-provided handlers. The
   interpreter becomes a pure IR walker with no architecture knowledge.

2. **Fork types and branch modes.** `ForkException` subclasses (`CloneFork`,
   `ResubmitFork`, etc.), `BranchMode` variants (`I2EClone`, `E2EClone`,
   `Replica`), and `ForkDecisions` fields (`instanceTypeOverride`,
   `branchMode`) are all v1model-specific but defined in `Interpreter.kt`.
   Move them to `V1ModelArchitecture.kt` where they belong.

3. **Packet context state.** `PacketContext` carries four v1model-specific
   fields (`pendingCloneSessionId`, `pendingEgressCloneSessionId`,
   `pendingResubmit`, `pendingRecirculate`). These are the handoff between
   extern calls and architecture boundary logic. With extern dispatch moving
   to the architecture, these become internal architecture state.

Shared infrastructure to extract:

- **Trace tree builder.** `buildTraceTree()` in `V1ModelArchitecture` is a
  generic iterative work-stack algorithm. Extract it as a shared utility
  parameterized by architecture-specific `runPipeline` and `forkSpecs`
  callbacks.

**Done when:** the interpreter has zero v1model imports, `PacketContext` has
zero architecture-specific fields, and all v1model tests still pass.

#### Phase 2: PSA (Portable Switch Architecture)

PSA is the P4.org-standardized successor to v1model. It has a fundamentally
different pipeline structure (separate ingress/egress parsers and deparsers,
four metadata structs instead of one) and different clone/multicast/resubmit
semantics. This is the architecture that makes us honest — if the refactored
boundary handles PSA cleanly, it handles anything.

Work:
- Implement `PSAArchitecture.kt` with PSA pipeline orchestration.
- Update the p4c backend to detect `PSA_Switch` and emit PSA stages.
- Add PSA extern handlers.
- Unit tests for PSA pipeline orchestration (like `V1ModelArchitectureTest`).

Testing: **26 corpus tests** (hand-crafted by p4c developers) as acceptance
criteria. p4testgen does not support PSA, so these are the primary safety net.
BMv2's `psa_switch` is less mature than `simple_switch`, so differential
testing may not be reliable.

**Done when:** 26 PSA corpus tests pass.

**Current status:** 26/26 corpus tests pass. Implemented: two-pipeline
orchestration, multicast replication, registers, counters, Hash, Meter (stub),
InternetChecksum, parser errors, metadata handling, drop-by-default, resubmit,
recirculate, cloning (I2E and E2E).

#### Phase 3: PNA (Portable NIC Architecture)

PNA validates that the architecture boundary is truly clean — adding a third
architecture should be straightforward. More importantly, PNA is the path to
deep confidence: **p4testgen supports PNA**, enabling automated symbolic path
exploration that generates hundreds of test cases.

**Current status:** Complete. 68/68 PNA programs covered (37 STF corpus tests,
31 compile-only). 411 p4testgen-generated symbolic tests across 22 programs.
All major externs implemented: `send_to_port`, `drop_packet`, `recirculate`,
`mirror_packet` (deparsed bytes, matching DPDK), `SelectByDirection`,
`add_entry` (data-plane table insertion), registers, Hash, Counter, Meter,
InternetChecksum, Digest, Random. Pipeline ordering validated against DPDK
SoftNIC reference (MainParser → PreControl → MainControl → MainDeparser).
Found and reported upstream p4testgen bug (p4lang/p4c#5569): PNA model
initialized `DROP=false` instead of `true`.

#### Why this ordering

PSA first because it has ready-made tests and forces the hardest design
decisions. PNA second because it validates the boundary (adding a third
architecture should be easy) and unlocks p4testgen for deep confidence. The
refactoring is the most important phase — it's where the design gets better.

### Track 7: standalone CLI

**Priority: not now | Depends on: tracks 1, 3**

A standalone CLI that makes 4ward accessible to anyone with a P4 program and
an STF test file — no Bazel knowledge required.

Three subcommands: `4ward compile` (P4 → pipeline config), `4ward sim`
(pipeline + STF → trace tree), and `4ward run` (compile + simulate in one
shot). Human-readable output by default, `--format=textproto` for tooling.
Example programs and a cram tutorial ship alongside.

**Done when:** a newcomer can `4ward run examples/passthrough.p4` and see a
trace tree without touching Bazel.

**Current status:** complete.

### Track 8: compelling interfaces

**Priority: next | Depends on: tracks 3, 7**

Three interfaces, from machine-friendly to human-friendly:

```
machine-friendly                                    human-friendly
     ◄────────────────────────────────────────────────────────►
     gRPC services          CLI                    web app
     (P4Runtime,         (compile,              (visual trace
      Dataplane)         sim, run)               explorer)
```

1. **gRPC services** (done). P4Runtime + Dataplane RPCs. The integration
   point for DVaaS and programmatic consumers.

2. **CLI** (done). `4ward compile / sim / run`. Copy-pastable, CI-scriptable,
   works with heredocs for quick experiments. The README quick-start
   experience.

3. **Web app — the "P4 Playground"** (v1 done). Edit P4, compile, install
   table entries, send packets, explore trace trees — all in one browser tab.
   See [PLAYGROUND_VISION.md](PLAYGROUND_VISION.md) for the next-level ideas:
   visual pipeline diagram, animated trace playback, packet dissection, and
   more.

**Done when:** the playground has visual pipeline diagrams and animated
trace playback.

**Current status:** complete. Visual pipeline diagrams (#271, #279) with
dagre-based graph layout, interactive zoom/pan, and full-screen mode. Animated
trace playback in the Trace tab. Keyboard shortcuts (#297) for common
operations.

### Track 9: P4Runtime hardening

**Priority: next | Parallelizable: yes (across phases)**

Track 4 built a working P4Runtime server with 132 tested requirements. Track 9
takes it from "works" to "unimpeachable." The goal is extremely high confidence
that 4ward's P4Runtime implementation is correct, complete, and robust — the
kind of confidence where you'd bet your production deployment on it.

Motivation: a [self-audit of the compliance matrix](P4RUNTIME_COMPLIANCE.md#honest-assessment-of-this-matrix)
found that the 132/132 number overstates coverage. 16 items are extensions (not
spec requirements), 3 are tested rejections, entire spec sections are
uncatalogued, tests are shallow (single-table fixtures, no concurrency, no
structured error verification), and `WriteValidator` doesn't cover all entity
types. This track systematically closes every gap.

Four phases, roughly ordered by effort and impact:

#### Phase 1: honest compliance matrix

Fix the compliance matrix so it's a trustworthy audit artifact, not a feel-good
scorecard.

- Fix section number references to match the actual P4Runtime spec (§13 for
  Read, §14 for SetForwardingPipelineConfig, etc.).
- Separate spec requirements from extensions in the summary table. Show two
  totals: core spec items and extensions.
- Catalogue missing spec sections: §6 (P4Info validation), §10 (error reporting
  format), §18 (PSA portability), §19 (versioning), §20 (non-PSA extensions).
  For each, decide: implement, reject with UNIMPLEMENTED, or mark N/A with
  justification.

**Done when:** the compliance matrix has correct spec references, separates
extensions from spec requirements, and every spec section with testable
requirements is either represented or explicitly scoped out.

#### Phase 2: close implementation gaps ✅

All five gaps found in the Phase 1 audit are fixed, each with unit + E2E tests:

- `WriteValidator` extended to validate `ActionProfileMember` (action_id,
  params, byte widths) and `ActionProfileGroup` (profile_id) writes.
- `action_profile.size` enforcement: total member+group count checked on INSERT.
- `UNRECOGNIZED` enum rejection: `Atomicity` and `ResponseType` return
  `INVALID_ARGUMENT` instead of falling through to defaults.
- Optional match type: test fixture and 4 unit tests (validation, width, priority,
  omission).
- Range match `low > high` semantic validation: rejected with `INVALID_ARGUMENT`.

#### Phase 3: deepen test coverage — DONE

Go beyond "does it work?" to "does it work in every corner?"

- **Multi-table test fixtures.** Add a ConformanceTest fixture with multiple
  tables using different match types (exact, ternary, LPM, range, optional).
  Exercise cross-table writes, wildcard reads spanning tables, and table-
  specific filtering.
- **Structured error detail verification.** Extend the test harness to verify
  `p4.v1.Error` protos in `grpc-status-details-bin` for all error paths, not
  just CONTINUE_ON_ERROR. Check `canonical_code`, `message`, and `space`.
- **Encoding edge cases.** Test `bit<1>`, `bit<7>` (non-byte-aligned),
  `bit<128>` (IPv6-width), values with leading zero bytes, empty bytestrings.
- **Batch edge cases.** Empty batch, all-failing batch, mixed
  INSERT/MODIFY/DELETE batch, duplicate entries within a batch.
- **Default entry edge cases.** `has_initial_default_action`, clearing the
  default, read-back of `is_default_action`.

**Done when:** each category above has at least 3 new tests.

#### Phase 4: adversarial testing

Shake the tree with inputs no reasonable controller would send.

- **Coverage-guided testing.** Use `tools/coverage.sh --html` to find untested
  branches in the P4Runtime server and write targeted tests for them.
- **Concurrency testing.** Two controllers writing simultaneously, read during
  pipeline reload, write during wildcard read. Verify the Mutex serialization
  holds under load.
- **Fuzz testing.** Feed malformed `WriteRequest` protos to the server (random
  field mutation, truncated bytestrings, enormous batches). The server must
  never crash — only return errors. Consider libprotobuf-mutator or a simple
  Kotlin property-based test.
- **External validation.** Investigate existing P4Runtime test suites
  (p4lang/PI, ONOS, Stratum) for tests we could run against 4ward. Any test
  written by someone else is worth more than three we wrote ourselves.

**Done when:** fuzz testing runs in CI without crashes, concurrency tests pass,
and at least one external test suite has been evaluated.

### Track 10: dataplane performance

**Priority: next | Parallelizable: yes**

The simulator is correct. Now it needs to be fast enough to be practical.

**Problem.** DVaaS validates switch behavior by replaying packets through the
simulator. Today's packet processing latency is unmeasured — there are no
benchmarks. As DVaaS scales to realistic table sizes (thousands of entries)
and production workloads (sustained packet streams), simulator latency becomes
the bottleneck. Without measurement, we can't tell whether we're minutes or
months away from acceptable performance.

**Goal.** 1k packets/sec on SAI P4 middleblock with ~10k table entries.
This is the bar for practical DVaaS use — fast enough that test suites
complete in seconds, not hours.

**Why it's hard.** Several design choices that serve correctness work against
throughput:

- **O(n) table lookup.** `TableStore.lookup()` linearly scans all entries and
  scores each one. With 10k entries across ~15 SAI tables, that's thousands of
  `BigInteger` comparisons per packet.
- **Trace construction.** Every packet produces a complete `TraceTree` with
  events for every parser state, table hit, and expression eval. This is
  required (DVaaS needs traces), but it's pure overhead relative to just
  computing output packets.
- **`BigInteger` arithmetic.** All `bit<N>` operations allocate on the heap
  via `BigInteger`, even for widths that fit in a `Long`.
- **Global mutex.** A single lock serializes all packets — no concurrent
  processing.
- **Per-packet gRPC overhead.** One RPC round-trip per packet, no batching.

**Approach: measure first, optimize second.**

#### Phase 1: benchmark and profile

Build a repeatable benchmark that measures end-to-end per-packet latency
through the gRPC Dataplane service on SAI P4 middleblock with configurable
table entry counts. Profile with async-profiler or JFR to identify actual
hotspots — not guesses.

Deliverables:
- A JMH or Kotlin-based benchmark: load SAI P4, install N table entries, send
  M packets, report p50/p95/p99 latency and throughput.
- A flame graph showing where time is spent at 10k entries.
- A baseline number we can track over time.

**Done when:** we have a reproducible latency number for SAI P4 at 10k
entries and know where the time goes.

**Current status: complete.** The 1k pps target is met across all
workloads. Eight optimizations delivered a **127× improvement** on the
hardest workload and **29× sequential single-core**. 4ward's concurrent
mode outperforms BMv2 by 6× on L3 forwarding.

See **[PERFORMANCE.md](PERFORMANCE.md)** for benchmark results, BMv2
comparison, methodology, and the full optimization log.

#### Phase 2: future optimizations (if needed)

- **Lock-free dataplane.** Replace the global `ReadWriteMutex` with
  immutable forwarding snapshots and a volatile pointer swap. Packet
  threads read a `@Volatile` reference (one pointer load, no lock) and
  execute against an immutable snapshot. Control-plane writes build a new
  snapshot via `deepCopy()` and swap the pointer. Direct counters and
  registers move to `ConcurrentHashMap` / `AtomicLongArray`. Eliminates
  all 9 lock acquisition sites and `ReadWriteMutex.kt` entirely. See
  [`designs/lock_free_dataplane.md`](../designs/lock_free_dataplane.md).
- **Hash index for exact-match tables.** O(1) lookup instead of O(n).
  Helps the direct path and post-fork tables that aren't cached.

### Track 11: error quality

**Priority: next | Parallelizable: yes**

Every error 4ward produces — whether from the simulator, the P4Runtime
server, or the CLI — should be clear, actionable, and specific. Today,
many errors surface as opaque gRPC `UNKNOWN` with no detail about what
went wrong or how to fix it. A user hitting a table size limit,
a malformed packet, or an unsupported feature should get a message that
tells them exactly what happened and what to do about it.

This isn't about P4Runtime spec compliance (Track 9 covers that). It's
about the overall developer experience: when something goes wrong, the
error message is the product.

#### Phase 1: audit and classify

Survey all error paths across the stack:
- **Simulator**: `error()`, `require()`, `check()` calls in
  `Interpreter.kt`, `TableStore.kt`, architecture implementations.
- **gRPC server**: `StatusException` throws in `P4RuntimeService`,
  `DataplaneService`. Which ones surface as `UNKNOWN` vs a proper
  status code with detail?
- **CLI**: error output from `4ward compile`, `4ward sim`, `4ward run`.

For each error, classify: (1) already clear and actionable,
(2) has the right info but poor formatting, (3) swallowed or opaque.

#### Phase 2: fix the worst offenders

Start with errors that users actually hit:
- Simulator exceptions that surface as gRPC `UNKNOWN` — catch at the
  service layer and translate to proper gRPC status codes with the
  original message.
- Table write failures — include the table name, the constraint that
  was violated, and the entry that caused it.
- Pipeline load failures — include the P4 program name and the
  specific IR validation error.

**Done when:** no error path produces a bare `UNKNOWN` or
`INTERNAL` without an actionable message.

## Sequencing

```
                     done                         next             later
              ┌───────────────────────────┐    ┌──────────┐    ┌──────────┐
  Track 1     │ v1model complete          │    │          │    │          │
              │                           │    │          │    │          │
  Track 2     │        · · · · · · · nice to have · · · · · · · · · · · │
              │                           │    │          │    │          │
  Track 3     │ trace trees               │    │          │    │          │
              │                           │    │          │    │          │
  Track 4     │ P4Runtime server          │    │          │    │          │
              │                           │    │          │    │          │
  Track 5     │ arch customization        │    │          │    │          │
              │                           │    │          │    │          │
  Track 6     │ refactor, PSA 26/26 ✓     │    │          │    │   PNA    │
              │                           │    │          │    │          │
  Track 7     │ standalone CLI            │    │          │    │          │
              │                           │    │          │    │          │
  Track 8     │ playground + vision       │    │          │    │          │
              │                           │    │          │    │          │
  Track 9     │                           │    │ P4Rt     │    │ fuzz +   │
              │                           │    │ hardening│    │ external │
              │                           │    │          │    │          │
  Track 10    │                           │    │ bench +  │    │ optimize │
              │                           │    │ profile  │    │          │
              │                           │    │          │    │          │
  Track 11    │                           │    │ error    │    │          │
              │                           │    │ quality  │    │          │
              │                           │    │          │    │          │
              └───────────────────────────┘    └──────────┘    └──────────┘
```

**Key dependencies:**
- Tracks 1, 3, 4, 5, 7, and 8 are complete.
- Track 5 subsumes Track 4C and 4E.
- Track 2 is picked up opportunistically.
- Track 6 is complete: refactoring, PSA (26/26), and PNA (68/68 + p4testgen).
- Track 8 (interfaces) is complete: gRPC services, CLI, and playground with
  visual pipeline diagrams and animated trace playback.
- Track 9 builds on Track 4. Phases 1–3 are complete. Phase 4
  (adversarial testing) is next.
- Track 10 (performance) has no blockers — SAI P4 already works E2E. Phase 1
  (benchmark + profile) informs all subsequent optimization work.
- Track 11 (error quality) has no blockers. Complements Track 9 (P4Runtime
  hardening) but covers the full stack, not just P4Runtime compliance.
