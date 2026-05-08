<p align="center">
  <img src="docs/logo.svg" alt="4ward logo" width="200">
  <br><br>
  <strong>Your P4 programs, finally explained.</strong>
  <br><br>
  <a href="https://github.com/smolkaj/4ward/actions/workflows/ci.yml"><img src="https://github.com/smolkaj/4ward/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://smolkaj.github.io/4ward/"><img src="https://img.shields.io/badge/docs-4ward-blue" alt="Docs"></a>
  <a href="https://smolkaj.github.io/4ward/main/"><img src="https://img.shields.io/badge/coverage-report-blue" alt="Coverage"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License"></a>
</p>

# 4ward

Ever stare at a packet leaving a switch and wonder *what just happened in
there?* 4ward is a glass-box P4 simulator that tells you exactly what happened
to your packet — every parser transition, every table lookup, every action, every
branch — delivered as a structured trace tree you can actually read.

**Want to try it right now?** `bazel run //web:playground` opens an
[interactive playground](#web-playground) in your browser — write P4, inject
packets, explore trace trees. No setup beyond Bazel.

```
             p4c + 4ward backend
                     │
                     ▼
              PipelineConfig
           (proto IR + p4info)
                     │
                     ▼
            ┌───────────────┐
 packet ──> │     4ward     │──▶ output packets
            │   Simulator   │──▶ trace tree  (the good stuff)
            └───────────────┘
                     ▲
             P4Runtime writes
             (table entries,
              counters, etc.)
```

## Why 4ward?

4ward is a **spec-compliant reference implementation** of the
[P4₁₆ language](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html)
and [P4Runtime](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html),
built for **correctness, observability, and extensibility** — yet fast
enough for production test workloads.

| | BMv2 | 4ward |
|---|---|---|
| P4Runtime support | outdated | [**100% spec-compliant**](docs/P4RUNTIME_COMPLIANCE.md) — 144 spec + 10 extension requirements |
| Trace format | text | **text / JSON / [proto](e2e_tests/trace_tree/clone_with_egress.golden.txtpb)** |
| All possible traces | not natively | [**trace trees**](#trace-trees) — every path through the program |
| `@p4runtime_translation` | no | [**built-in translation engine**](#p4runtime_translation-done-right) |
| Architectures | v1model only | **v1model + PSA + PNA**, [extensible by design](docs/ROADMAP.md#track-6-multi-architecture-support) |
| Architecture customization | no | [**first-class support**](docs/ROADMAP.md#track-5-architecture-customization) |
| Interactive playground | no | [**browser-based IDE**](#web-playground) with trace playback & packet decoding |
| Error messages | opaque | [**actionable, with valid options**](docs/ROADMAP.md#track-11-error-quality) — [75 golden-tested](p4runtime/golden_errors/) |
| Data plane throughput (16-way selector) | [~4,500 pps ÷ 16 paths](docs/PERFORMANCE.md#bmv2-comparison) | [**~2,000 pps, all 16 paths**](docs/PERFORMANCE.md) ([head-to-head on SAI P4](docs/PERFORMANCE.md#bmv2-comparison)) |
| Data plane parallelism (16-way selector) | single-threaded | [**13,000 pps on 16 cores**](docs/PERFORMANCE.md) — parallel across packets and forks |
| Extensibility | limited | [**AI-friendly codebase**](docs/ROADMAP.md#why-4ward-is-easier-to-extend) — if AI can extend it, anyone can |
| CI | slow | **[~2 min](https://4ward.buildbuddy.io/trends/)**, rigorous |
| Development pace | slow | **[AI-fast](docs/AI_WORKFLOW.md)** |

## Where we're headed

The core vision is realized: spec-compliant v1model/PSA/PNA, trace trees,
full P4Runtime, and
[SAI P4 end-to-end](docs/SAI_P4_CONFIDENCE.md) through the full P4Runtime
stack. The **[roadmap](docs/ROADMAP.md)** tracks what's next: adversarial
testing, network simulation, and
**[DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas)**
integration — making 4ward a drop-in replacement for BMv2 in SONiC's
dataplane validation service.

4ward is pre-1.0 and moving fast. See **[STATUS.md](docs/STATUS.md)** for
progress updates.

> [!WARNING]
> **Pre-1.0 Notice:** We are aggressively refactoring to build the best
> system possible. Until we reach 1.0, nothing is sacred except correctness
> and the test suite.

## Quick start

[Tested on](https://4ward.buildbuddy.io/tests/) macOS and Ubuntu. You need
[Bazel](https://bazel.build) 8+ (or just grab
[Bazelisk](https://github.com/bazelbuild/bazelisk) and forget about it) and a
C++20 compiler for the p4c backend. Everything else is hermetic — Bazel
handles it.

```sh
git clone https://github.com/smolkaj/4ward.git && cd 4ward
bazel build //...   # build everything
bazel test //...    # run all tests
```

Now point it at a P4 program. Set up a shell alias first:

```sh
alias 4ward='bazel run //cli:4ward --'
```

Here's
[`passthrough.p4`](examples/passthrough.p4) — the simplest possible program:
parse an Ethernet header, hardcode the output port to 1, emit the packet
unchanged.

```sh
4ward run examples/passthrough.p4 - << 'EOF'
packet 0 FFFFFFFFFFFF 000000000001 0800
expect 1 FFFFFFFFFFFF 000000000001 0800
EOF
```
```
packet received: port 0, 14 bytes
  parse: start -> accept
  output port 1, 14 bytes
PASS
```

Every step is visible: the parser walked `start` -> `accept`, the packet exited
port 1, and the test passed. This is what *glass-box* means — you see every
decision the simulator made.

Things get interesting with tables. [`basic_table.p4`](examples/basic_table.p4)
forwards based on Ethernet type — IPv4 packets hit the table and get forwarded,
everything else misses and gets dropped:

```sh
4ward run examples/basic_table.p4 - << 'EOF'
add port_table hdr.ethernet.etherType:0x0800 forward(1)
packet 0 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
expect 1 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
packet 0 FFFFFFFFFFFF 000000000001 0806 DEADBEEF
EOF
```
```
packet received: port 0, 18 bytes
  parse: start -> accept
  table port_table: hit -> forward
  action forward(port=1)
  output port 1, 18 bytes
packet received: port 0, 18 bytes
  parse: start -> accept
  table port_table: miss -> drop
  action drop
  mark_to_drop()
  drop (reason: mark_to_drop)
PASS
```

You can see exactly *why* one packet was forwarded and the other was dropped.
No printf debugging. No Wireshark. No guessing — just read the trace.

For the full walkthrough — compiling, machine-readable output, error handling,
and more — see the **[tutorial](examples/tutorial.t)**.

## Web playground

The web playground is a browser-based IDE for P4 — edit, compile, and simulate
in a single feedback loop. No separate tools, no context switching.

```sh
bazel run //web:playground    # open http://localhost:8080
```

<p align="center"><img src="docs/playground.gif" alt="4ward playground demo" width="100%"></p>

**Write** P4 with syntax highlighting, **install** table entries with a few
clicks, **inject** packets, and **explore** what happened:

- **Trace playback** — step through the trace event by event (arrow keys,
  Escape to reset). Each step highlights the active P4 source line in the
  editor and the active node in the control-flow graph — three views
  in sync.
- **Control-flow graph** — visual pipeline diagram showing tables, conditions,
  and control flow for each pipeline stage.
- **Packet decoding** — output packets are decoded into named header fields
  using the program's own deparser. Like Wireshark, but aware of your P4
  headers.

## Trace trees

P4 programs have non-deterministic choice points — action selectors, multicast,
clone. Other tools pick one path. 4ward shows you *all* of them as a **trace
tree**.

Here's a [63-line P4 program](e2e_tests/trace_tree/clone_with_egress.p4) that
clones a packet and forwards the original and clone out of two different ports.
One packet goes in, two come out
([full trace](e2e_tests/trace_tree/clone_with_egress.golden.txtpb)):

```protobuf
events { parser_transition { from_state: "start"  to_state: "accept" } }
events { clone { session_id: 100 } }
fork_outcome {
  reason: CLONE
  branches {
    label: "original"
    subtree {
      events { table_lookup { action_name: "tag_original" } }
      packet_outcome { output { egress_port: 2 } }
    }
  }
  branches {
    label: "clone"
    subtree {
      events { table_lookup { action_name: "tag_clone" } }
      packet_outcome { output { egress_port: 3 } }
    }
  }
}
```

## `@p4runtime_translation` done right

P4 programs use `@p4runtime_translation` to decouple controller-facing values
from data-plane values — but the spec leaves the actual mapping mechanism
unspecified. Every deployment rolls its own. 4ward ships a built-in translation
engine with three modes:

- **Explicit** — you provide the full mapping table upfront.
- **Auto-allocate** — 4ward assigns data-plane values on first use. Zero config.
- **Hybrid** — pin the values that matter, auto-allocate the rest.

Both `sdn_bitwidth` (numeric) and `sdn_string` (SAI P4-style) are supported.

```
Hybrid mode example — pin special ports, auto-allocate the rest:

  explicit:  "CpuPort"    → 510
  explicit:  "DropPort"   → 511
  auto:      "Ethernet0"  →   0  (assigned on first use)
  auto:      "Ethernet1"  →   1
  auto:      "Ethernet2"  →   2
```

## Should you trust AI-written code?

4ward is **[100% AI-written](docs/AI_WORKFLOW.md)** — every line, every test,
every doc you're reading right now. Naturally, you might wonder: should you
trust the output?

The answer isn't "trust the AI." It's **trust the tests.**

4ward uses three independent testing layers, each with a different source of
truth:

- **Conformance tests** from p4c's own test suite — 87 hand-written STF
  tests by the people who built the language.
- **Symbolic path exploration** via p4testgen — 500+ auto-generated tests
  that systematically cover execution paths humans wouldn't think to exercise.
- **Differential testing** against BMv2 — run identical inputs through the
  reference implementation and 4ward, compare every output.

When three independent oracles agree, the code is correct — regardless of who
wrote it. See [Testing Strategy](docs/TESTING_STRATEGY.md) for the full story.

## Why Kotlin?

The P4 ecosystem is written in C++. So why isn't 4ward?

Since no one needs to hold language minutiae in their head — the
[AI writes the code](docs/AI_WORKFLOW.md) — we're free to pick the best
language for the problem, not the most familiar one.

**Why not C++?** Its top strengths — speed, ecosystem familiarity —
don't matter here. Its top weaknesses — compile times, complexity — matter a lot.

**Why Kotlin?** Fast builds, simple language, strong type system, excellent
ergonomics (sealed classes, pattern matching).

**Why not…**
- **Rust?** Borrow checker is overkill — we don't need manual memory control.
- **Go?** Weaker type system — no algebraic data types, no pattern matching.
- **Python?** Weak type system, slow test execution.
- **Java?** Kotlin, but worse.
- **OCaml?** Excellent fit, but not well-supported within Google's ecosystem :(

> [!IMPORTANT]
> **You don't need Kotlin to contribute to — or use — 4ward.**
> [AI writes the code](docs/AI_WORKFLOW.md); C++ projects embed via
> [`//p4runtime_cc:dataplane_client`](https://smolkaj.github.io/4ward/reference/embedding-cc/);
> any gRPC client works in any language.

## Project structure

```
4ward/
├── cli/                    Standalone CLI (4ward compile / sim / run)
├── simulator/              Kotlin simulator — the brain
│   ├── ir.proto            Behavioral IR (the contract between backend & sim)
│   └── simulator.proto     Simulator service protocol (in-process + gRPC)
├── p4c_backend/            p4c backend plugin (C++, emits the proto IR)
├── p4runtime/              P4Runtime gRPC server (Kotlin)
├── stf/                    STF parser + runner (drives the simulator from .stf files)
├── web/                    Interactive web playground
├── examples/               Ready-to-run P4 programs and STF tests
├── e2e_tests/
│   ├── corpus/             p4c STF corpus (bulk regression)
│   ├── trace_tree/         Golden trace-tree tests
│   ├── p4testgen/          p4testgen integration (auto-generated paths)
│   ├── bmv2_diff/          BMv2 differential testing
│   ├── sai_p4/             SAI P4 test fixtures
│   └── <feature>/          Hand-written feature tests (passthrough, lpm, …)
├── designs/                Design documents
├── userdocs/               User-facing documentation (MkDocs → smolkaj.github.io/4ward/)
├── docs/                   Developer documentation (architecture, roadmap, testing)
└── tools/                  Developer scripts (format, lint, coverage, …)
```

Curious about the design? [ARCHITECTURE.md](docs/ARCHITECTURE.md) has the full story.

## CI that has your back

We think fast, reliable CI is key to keeping developers happy and productive.

Every PR gets built, linted, and tested in about 2 minutes — with a differential coverage
report in about 5. No flakes, no "works on my machine." See for yourself on the
[BuildBuddy dashboard](https://4ward.buildbuddy.io/trends/).

## Documentation

**[User documentation](https://smolkaj.github.io/4ward/)** — getting started
guides, reference pages, and concept explainers for the web playground, CLI,
gRPC API, and [C++ library](https://smolkaj.github.io/4ward/reference/embedding-cc/)
(including composable [gtest matchers](https://smolkaj.github.io/4ward/reference/dataplane-matchers/)).

**[Tutorial](examples/tutorial.t)** — a hands-on walkthrough from hello
world to machine-readable trace output. Doubles as a regression test (cram
format — every command and expected output is verified in CI).

Developer docs (in [`docs/`](docs/)):

| Document | Purpose |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design rationale and component overview |
| [ENTRY_POINTS.md](docs/ENTRY_POINTS.md) | CLI, P4Runtime server, web playground, test APIs |
| [ROADMAP.md](docs/ROADMAP.md) | Development tracks, priorities, and sequencing |
| [STATUS.md](docs/STATUS.md) | Append-only log of daily progress |
| [CONTRIBUTING.md](docs/CONTRIBUTING.md) | How to get involved |
| [AI_WORKFLOW.md](docs/AI_WORKFLOW.md) | How to develop with AI agents |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | Benchmark methodology, results, and BMv2 comparison |
| [TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md) | Why three test oracles, and what that enables |
| [P4RUNTIME_COMPLIANCE.md](docs/P4RUNTIME_COMPLIANCE.md) | P4Runtime spec compliance matrix |
| [SAI_P4_CONFIDENCE.md](docs/SAI_P4_CONFIDENCE.md) | SAI P4 confidence gaps and action plan |
| [LIMITATIONS.md](docs/LIMITATIONS.md) | Known shortcuts and gaps |
| [RELEASING.md](docs/RELEASING.md) | How to cut a release and publish to the BCR |
| [REFACTORING.md](docs/REFACTORING.md) | Tech debt and cleanup backlog |
| [AGENTS.md](AGENTS.md) | Guide for AI coding agents |
| [designs/](designs/) | Design proposals |

## Want to help?

We'd love that! See [CONTRIBUTING.md](docs/CONTRIBUTING.md) for how to get
started.

## License

Apache 2.0. See [LICENSE](LICENSE).
