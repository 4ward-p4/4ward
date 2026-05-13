# P4Runtime Differential Testing Design

**Status: implemented.** Tracks issue #595; realizes the
external-validation goal in
[`docs/TESTING_STRATEGY.md`](../docs/TESTING_STRATEGY.md) §"Open
questions" and [`docs/ROADMAP.md`](../docs/ROADMAP.md) Track 9.

## Goal

Drive 4ward's P4Runtime gRPC server and BMv2's `simple_switch_grpc`
with the same gRPC requests, diff the responses with a small set of
documented canonicalizations.

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
   │   (in-process)       │       │  simple_switch_grpc  │
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
(`e2e_tests/bmv2_diff/`, 187 STF tests) never touches. Today the rest
of 4ward's P4Runtime tests encode our *reading* of the spec; this
harness uses an external implementation as oracle.

## Architecture

`simple_switch_grpc` builds from upstream source under Bazel. The
build chain wires together p4lang/PI's gRPC server (`piserver`,
`pifeproto`) with behavioral-model's data plane (`bm_sim`,
`simple_switch_lib`) via behavioral-model's own Thrift-free PI
integration adapter (`libbmpi`). cc_library / cc_binary targets live
in `e2e_tests/p4runtime_diff/` rather than in the PI or
behavioral-model patches — both upstream modules need to be deps of
the binary, so putting libraries in either patch would create a
Bzlmod cycle. Source files come in via filegroups exposed by the
patches.

PI's existing BUILD files use legacy WORKSPACE-style apparent names
(`@com_github_grpc_grpc`, `@com_google_absl`, etc.). The PI patch's
`MODULE.bazel` declares each as a `bazel_dep` with `repo_name` mapped
to our Bzlmod equivalents (protobuf 33.5, grpc 1.80.0, abseil
20260107.1) so PI's BUILD files resolve without source rewrites.

No additional system deps beyond `libgmp-dev` and `libpcap-dev`
(already installed for BMv2). The diff suite is `dev_dependency = True`
in MODULE.bazel — BCR consumers don't see it.

## Scenarios

Pipeline fixture: `basic_table.p4` compiled both ways
(`fourward_pipeline` for 4ward `.txtpb`, `p4_library(target = "bmv2")`
for BMv2 `.json`). Each scenario sets the pipeline config on both
servers, runs gRPC operations, diffs responses.

Two fixtures: `basic_table.p4` (single exact-match table, scenarios
1-7) and `action_selector_3.p4` (exact-match table with action
selector, scenarios 8-10).

| # | Scenario | Fixture | Status |
|---|----------|---------|--------|
| 1 | Round-trip canonical form (§8.3 bytestring padding) | basic_table | ✓ pass |
| 2 | Modify-after-padded-write (key matches across encodings) | basic_table | ✓ pass |
| 3 | Out-of-range values (both reject) | basic_table | ✓ pass |
| 4 | Batch atomicity (read-back after partial failure) | basic_table | ✓ pass |
| 5 | Default action modify (§9.1.6 read-back) | basic_table | ✓ pass |
| 6 | Error semantics (DELETE/INSERT/MODIFY non-existent/duplicate) | basic_table | ✓ pass |
| 7 | Wildcard read with multiple entries | basic_table | ✓ pass |
| 8 | Action profile member CRUD | action_selector | ✓ pass |
| 9 | Action profile group with members | action_selector | ✓ pass |
| 10 | Table entry referencing action profile group | action_selector | ✓ pass |

## Canonicalizations before diff

Differences that are non-bugs are canonicalized away before the
proto-equality assertion (see `ResponseDiff.kt`):

- **Field ordering** in repeated fields (`match` lists) — sort by
  `field_id`.
- **Error message text** — compare gRPC status code only, not the
  description string.
- **Server-assigned IDs** (action profile member handles, multicast
  group node handles) — substituted by the test before compare.
- **Counter/meter timing values** — out of scope; initial scenarios
  avoid these.

A divergence not anticipated by these canonicalizations counts as a
test failure — the harness surfaces unknown unknowns, it doesn't paper
over them.

## Non-goals

- **Replacing the existing `bmv2_diff` data-plane harness.** Different
  layer, different oracle, different failure modes. Both stay.
- **Behavioral parity with BMv2 across the full P4Runtime API.** BMv2
  has features 4ward doesn't and vice versa. The corpus targets the
  intersection; documented divergences are an output, not a defect.
- **Continuous coverage of every P4Runtime field.** This is a
  spec-conformance harness, not a fuzzer.

## Future work

- **Corpus growth.** Add scenarios as P4Runtime features land. Strong
  candidates: role config, idle timeout.

## Alternatives considered

- **STF-as-control-plane.** Extending the existing `bmv2_diff` STF
  corpus doesn't reach the gRPC layer — STF only exercises the data
  plane.
- **Diff data-plane behavior across two control-plane paths.** Drive
  4ward via gRPC and BMv2 via its existing C++ driver, then compare
  output packets. Catches "did the control plane translate to the
  same data-plane state?" but not gRPC-API-only bugs.
- **Existing P4Runtime conformance suites** ([`p4lang/PI`'s PTF
  tests](https://github.com/p4lang/PI),
  [`p4runtime-shell`](https://github.com/p4lang/p4runtime-shell),
  [`fabric-p4test`](https://github.com/opennetworkinglab/fabric-p4test))
  are written against specific testbeds, not as libraries to point at
  two implementations and diff. Individual test cases from them may
  still be portable into the corpus over time.
