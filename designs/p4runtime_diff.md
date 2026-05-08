# P4Runtime Differential Testing Design

**Status: proposal.** Tracks issue #595; realizes the open question in
[`docs/TESTING_STRATEGY.md`](../docs/TESTING_STRATEGY.md) §"Open
questions" ("What's the equivalent of BMv2 diff testing?") and the
external-validation goal in [`docs/ROADMAP.md`](../docs/ROADMAP.md)
Track 9.

## Proposal

Drive 4ward's P4Runtime gRPC server and BMv2's `simple_switch_grpc` with
the same gRPC requests, diff the responses with a small set of documented
canonicalizations.

```
                 ┌──────────────────────────────┐
                 │   P4Runtime test scenario     │
                 │   (Write/Read sequences)      │
                 └────────────┬─────────────────┘
                              │ same gRPC requests
              ┌───────────────┴───────────────┐
              ▼                               ▼
   ┌──────────────────────┐       ┌──────────────────────┐
   │   4ward p4runtime    │       │  BMv2                │
   │   server subprocess  │       │  simple_switch_grpc  │
   └──────────┬───────────┘       └──────────┬───────────┘
              │ WriteResponse,               │ WriteResponse,
              │ ReadResponse                 │ ReadResponse
              └───────────────┬──────────────┘
                              ▼
                        diff (with allowed
                        canonicalizations)
```

## Why

The §8.3 bytestring fix in #594 lived in the gRPC adapter — code BMv2's
C++ API never reaches and our existing differential harness
(`e2e_tests/bmv2_diff/`, 187 STF tests) never touches. Candidate
corners that could be similarly affected include status codes, batch
atomicity, role config, idle timeout, default-action semantics, and
action-profile group membership rules — none verified, hence the
proposal. Today, 4ward's tests encode our *reading* of the spec; a
control-plane differential harness uses an external implementation as
oracle.

## Design

### Building `simple_switch_grpc` is the long pole

`bazel/behavioral_model.patch` builds `simple_switch_lib` and its
transitive deps but explicitly omits PI ("Minimal build: no Thrift, no
nanomsg, no debugger, no PI"). The `simple_switch_grpc` binary depends
on [`p4lang/PI`](https://github.com/p4lang/PI).

Some prior art reduces the cost:

- **Upstream PI already has WORKSPACE-style Bazel rules** for the core
  library (`pihdrs`, `piutils`, `pip4info`, `pi`, …). See PI's
  [`BUILD`](https://github.com/p4lang/PI/blob/main/BUILD) and
  [`bazel/deps.bzl`](https://github.com/p4lang/PI/blob/main/bazel/deps.bzl).
  Stratum already consumes it as a Bazel dep
  ([`stratum/bazel/deps.bzl`](https://github.com/stratum/stratum/blob/main/bazel/deps.bzl)),
  proving it works in production.
- **What's missing upstream**: BUILD files for `targets/bmv2/` (the
  `simple_switch_grpc` target plugin) and `bin/`. Those are
  autotools-only today. We'd write them.
- **Bzlmod shim**: 4ward uses Bzlmod, PI is WORKSPACE-only, no BCR
  module exists. We'd add a small `MODULE.bazel` wrapping the
  upstream WORKSPACE rules.
- **Version skew**: PI pins protobuf/gRPC versions that may diverge
  from ours; resolving that is the most likely source of incidental
  scope.

Net: the core PI port is reuse, not greenfield; the
`simple_switch_grpc`/`bin/` BUILD files plus a Bzlmod shim are the
genuinely new work. Plausibly 1–2 weeks of focused work, dominated by
version-skew triage. **This is Phase 1 and remains the dominant risk**
(see Risks).

### Scenarios

The corpus is a new set of Kotlin-defined sequences, not the existing
STF corpus (which is data-plane oriented). Each scenario describes:

- A pipeline config (P4 program + P4Info).
- A sequence of P4Runtime requests.
- For each request, the expected outcome class — used as a sanity
  check before the diff fires.

Initial scenarios:

- **Round-trip canonical form.** Write a TableEntry with various
  encodings; Read it back; both servers must return the same canonical
  form. (The §8.3 regression test, externally validated.)
- **Modify-after-padded-write.** Write with padded encoding; Modify
  with canonical encoding; both servers must treat them as the same
  key.
- **Out-of-range values.** Write with a value exceeding the field
  bitwidth; both must reject. 4ward returns `OUT_OF_RANGE` per spec;
  BMv2's actual code is to be measured during Phase 2 and recorded as
  either an expected match or a documented divergence.
- **Batch atomicity.** A `WriteRequest` with one valid + one invalid
  update under each `Atomicity` mode.
- **Default action semantics.** Modify default; read; verify both
  servers' read-back behavior.

Five scenarios is the Phase 3 target. Phase 4 grows to ~20, prioritised
by recent P4Runtime feature work.

### Canonicalizations before diff

Some differences are non-bugs and must be canonicalized away before
diffing:

- **Field ordering** in repeated fields (`match` lists) — sort by
  `field_id`.
- **Error message text** — compare error code only, not description.
- **Server-assigned IDs** (action profile member handles, multicast
  group node handles) — record and substitute.
- **Counter/meter timing values** — out of scope for the first cut;
  initial scenarios avoid these.

A sealed `DiffStrategy` per response type makes these choices explicit;
divergence in a strategy that wasn't anticipated counts as a test
failure.

### Reuse of existing infrastructure

- `e2e_tests/bmv2_diff/Bmv2Runner.kt` already manages a BMv2 subprocess
  with port allocation, startup readiness checks, and graceful
  shutdown. Phase 2's `Bmv2P4RuntimeRunner` should mirror that
  lifecycle directly — same patterns, different transport.
- 4ward already exposes a `fourward_server` Kotlin binary
  (`grpc/BUILD.bazel`); the harness spawns it as a subprocess
  the same way DVaaS integration (designs/dvaas_integration.md) does.
- The gRPC client is the standard P4Runtime Kotlin stub already used
  by `P4RuntimeConformanceTest`.

## Phasing

1. **PI Bazel port.** Pull `p4lang/PI` in as a `git_override`'d
   `bazel_dep`, reusing its upstream WORKSPACE Bazel rules; add the
   missing BUILD files for `targets/bmv2/` and `bin/`; resolve
   version skew with our protobuf/gRPC. No 4ward code changes; ships
   as its own PR.
2. **Harness skeleton.** A `Bmv2P4RuntimeRunner`. A test that spawns
   both servers, sets a pipeline config on each, performs one
   Write+Read, asserts responses match.
3. **Initial scenario corpus.** The five scenarios above. Triage
   divergences: file 4ward bugs as issues, file BMv2 bugs upstream,
   raise spec ambiguities with the P4 API working group.
4. **Corpus growth.** Add scenarios as P4Runtime features land.

Phases 2 and 3 land together — a harness with no scenarios isn't
useful.

## Non-goals

- **Replacing the existing `bmv2_diff` data-plane harness.** Different
  layer, different oracle, different failure modes. Both stay.
- **Behavioral parity with BMv2 across the full P4Runtime API.** BMv2
  has features 4ward doesn't (and vice versa). The corpus targets the
  intersection. Documented divergences are an output, not a defect.
- **Continuous coverage of every P4Runtime field.** This is a
  spec-conformance harness, not a fuzzer.

## Risks

- **PI's Bazel port is the long pole.** PI itself depends on Thrift,
  protobuf, gRPC, and several p4lang sub-deps. If transitive
  dependencies clash with our existing protobuf/gRPC versions, Phase
  1 stalls. Pre-flight by attempting a minimal `cc_library(name =
  "PI")` build before committing to the full design.
- **Subprocess management flakiness.** Two servers per scenario with
  port allocation, startup races, and graceful shutdown is more
  failure surface than the existing single-process harness. Reusing
  `Bmv2Runner.kt`'s lifecycle is mitigation.
- **Divergence triage.** Baseline rate is unknown until Phase 3 runs.
  Mitigation: scenarios are ordered by confidence, starting with the
  §8.3 regression test where 4ward and the spec demonstrably agree —
  any divergence there is a BMv2 bug, not ours. If volume turns out
  to be high, scenarios get a `divergence-classified` tag and fail
  only on unclassified differences.
- **CI cost.** Two gRPC servers per scenario doesn't fit the default
  test budget; tag the suite `heavy` and skip locally, run on CI.
  Only two `heavy` targets exist today (`bmv2_diff_test` and the
  `P4RuntimeWriteErrorTest` heavy variant), so adding a peer suite
  is well within the existing pattern.

## Alternatives considered

- **STF-as-control-plane.** Extending the existing harness with more
  STF tests doesn't help — STF doesn't exercise the gRPC layer.
- **Diff data-plane behavior across two control-plane paths.** Drive
  4ward via gRPC and BMv2 via its existing C++ driver, then compare
  output packets. Catches "did the control plane translate to the
  same data-plane state?" but not gRPC-API-only bugs (status codes,
  encoding rules).
- **Existing P4Runtime conformance suites.**
  [`p4lang/PI`'s PTF tests](https://github.com/p4lang/PI),
  [`p4runtime-shell`](https://github.com/p4lang/p4runtime-shell), and
  [`fabric-p4test`](https://github.com/opennetworkinglab/fabric-p4test)
  are written against specific testbeds, not as libraries you point
  at two implementations and diff. None replace the proposed harness;
  individual test cases from them may be portable into the corpus.
- **Stratum's PI consumption.** Stratum already pins PI as a Bazel
  dep but uses it as a *backend*, not for differential testing. Their
  Bazel wiring is reusable as reference; their use case is not.
