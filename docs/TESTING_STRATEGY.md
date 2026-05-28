# Testing Strategy

The README asks "[Should you trust AI-written code?](../README.md#should-you-trust-ai-written-code)"
and gives the short answer: trust the tests, not the author. This document
tells the full story.

## The key insight

If success criteria are machine-checkable, *who* writes the code doesn't
matter — only whether the tests are good. There's no ambiguity, no judgment
call on "is this done?" A test passes or it doesn't.

This makes the project automatable: AI agents run a tight loop — pick a
failing test, implement, green, ship. And it makes the output trustworthy:
three independent oracles agreeing is stronger evidence of correctness than
any code review.

## Three layers, three oracles

### Layer 1: STF corpus — breadth

p4c ships over 200 [STF](https://github.com/p4lang/p4c/tree/main/testdata)
(Simple Test Framework) test programs. Each is a self-contained spec: a P4
program, table entries, input packets, and expected output. The STF runner
compiles through p4c + the 4ward backend, loads the pipeline, sends packets,
and diffs actual output against expectations.

The source of truth is hand-written expectations by the language authors — a
direct statement of what correct behavior looks like. This catches regressions,
feature gaps, and basic correctness across the full breadth of P4₁₆. The blind
spot: only paths someone thought to write get tested.

The failing-test list *is* the feature backlog — pick one, make it pass, ship.

### Layer 2: p4testgen — depth

[p4testgen](https://github.com/p4lang/p4c/tree/main/backends/p4tools/modules/testgen)
symbolically executes P4 programs with an SMT solver, generating concrete test
cases that exercise each reachable path — including ones no human would think
to write.

The source of truth is p4testgen's own model of P4 and BMv2 semantics, an
independent implementation that shares no code with 4ward or BMv2. This catches
bugs on paths humans miss. The blind spot: p4testgen's model could disagree
with the real BMv2 implementation.

### Layer 3: BMv2 differential testing — correctness

Identical inputs go through BMv2 and 4ward. Every output packet, every drop
decision, every egress port is compared. If they disagree, one of them has a
bug.

The source of truth is the reference implementation itself — not a model, not
a spec. This catches the case that the other layers can't: 4ward produces
output, but the output is *wrong*. The blind spot: BMv2 has its own bugs, and
can't serve as oracle for features unique to 4ward (like trace trees).

## Unit tests

Underneath the three layers, unit tests provide fast development feedback —
bit-precise arithmetic, match kinds, select expressions, packet I/O. Not part
of the correctness strategy, but they catch problems early, before the
expensive end-to-end tests run.

## P4Runtime server

The layers above verify the data plane — does the simulator execute P4 programs
correctly? The P4Runtime server is a different surface: a gRPC control plane
that translates between P4Runtime-speaking controllers and the simulator.
Different surface, same philosophy: multiple independent methodologies,
machine-checkable success criteria.

### Layer 1: Conformance tests — spec compliance

Hand-written tests that walk the P4Runtime spec section by section. Each test
cites the spec requirement it validates. Three categories:

- **Per-RPC happy paths.** SetForwardingPipelineConfig, Write, Read,
  StreamChannel — does the basic lifecycle work?
- **Per-RPC error codes.** The P4Runtime spec (§9.1) prescribes specific gRPC
  status codes for each error condition: ALREADY_EXISTS for duplicate INSERT,
  NOT_FOUND for delete of nonexistent entry, FAILED_PRECONDITION for Write
  before pipeline load. Each condition gets a test that asserts the exact code.
  Modeled after
  [sonic-pins/p4rt_app/tests/response_path_test.cc](https://github.com/sonic-net/sonic-pins/tree/main/p4rt_app/tests)
  which systematically covers per-RPC error paths including batch partial
  failure, error message sanitization, and state consistency after failed
  writes.
- **Translation correctness.** `@p4runtime_translation` round-trips: write a
  value in SDN bitwidth, read it back, verify it matches. Covers both the
  narrowing (write) and widening (read) paths.

The source of truth is the P4Runtime spec itself. The blind spot: only covers
scenarios someone thought to write.

### Layer 2: Differential testing — external implementation agreement

The simulator is already validated by three independent oracles. The P4Runtime
server has a different risk: our implementation might encode our own mistaken
reading of the P4Runtime spec. To catch that, `e2e_tests/p4runtime_diff` runs
the same P4Runtime sessions against 4ward and BMv2's `simple_switch_grpc`, then
canonicalizes and diffs the responses.

Current coverage is intentionally scoped: `Capabilities`, table entry
write/read scenarios, bytestring canonicalization, default action reads,
wildcard reads, error semantics, action profile members/groups, and table
entries referencing selector groups. The suite is tagged `heavy` and Linux-only
because it builds and spawns BMv2's P4Runtime server.

The source of truth is not "BMv2 is always right" — BMv2 has its own bugs and
does not cover 4ward-specific features. The value is independence: where BMv2
and 4ward agree on the same P4Runtime exchange, we have evidence that our
spec-reading, encoding, canonicalization, and state transitions match a real
external implementation.

The remaining gap is breadth. The diff suite does not yet cover the full STF
corpus, `StreamChannel` PacketIO, arbitration/roles, PRE, registers,
counters/meters, or randomized request sequences.

### Layer 3: Fuzz testing — robustness

The data plane has p4testgen exploring paths no human would write. The control
plane equivalent is
[sonic-pins/p4_fuzzer](https://github.com/sonic-net/sonic-pins/tree/main/p4_fuzzer):
given a P4Info, it generates random valid and mutated P4Runtime WriteRequests
(invalid table IDs, missing match fields, duplicate inserts, deletes of
nonexistent entries, out-of-range values — 16 mutation types), sends them to
the server, and checks responses against a spec oracle that knows what the
P4Runtime spec says should happen.

The key insight is the oracle pattern: the fuzzer maintains a `SwitchState`
model that tracks what entries should be installed. After each Write, it checks:
did the server accept/reject correctly per spec? Does a Read back match the
modeled state? This catches crashes, state corruption, and spec violations that
hand-written tests miss.

The source of truth is the P4Runtime spec oracle (independent of our
implementation). The blind spot: doesn't test data plane correctness, only
control plane protocol compliance.

This has been proven as an external validation artifact, but is intentionally
not merged. [PR #665](https://github.com/smolkaj/4ward/pull/665) wired
sonic-pins' P4 fuzzer into 4ward, spawning a `FourwardServer`, pushing a
pipeline, and running 10,000 iterations of random `WriteRequest`s against the
P4Runtime oracle. The run covered roughly 400k updates with zero oracle
failures after fixing validation-ordering bugs that the fuzzer surfaced.

We keep that work out of `main` because sonic-pins contains DVaaS, and DVaaS is
the downstream system that should use 4ward as an oracle. Making 4ward depend on
sonic-pins would create the wrong dependency direction; it would also pull heavy
sonic-pins dependencies into the repo. The useful artifact is the evidence and
the recipe: when we need another independent validation pass, run the fuzzer
branch or an external harness against a built 4ward server rather than making
those dependencies part of the default build.

### Compliance matrix

[P4RUNTIME_COMPLIANCE.md](P4RUNTIME_COMPLIANCE.md) maps every testable
P4Runtime spec requirement to its test status. This answers the first open
question below — and makes the remaining gaps visible at a glance.

### Confidence assessment

The [compliance matrix](P4RUNTIME_COMPLIANCE.md) shows 119/120 applicable
requirements tested — but the matrix is self-authored. We wrote the
requirements, wrote the tests, and checked our own boxes. Four blind spots:

1. **Spec coverage is hand-distilled.** The matrix was not systematically
   extracted from every MUST/SHALL in the P4Runtime spec. Entire requirement
   areas could be missing — e.g. bytestring canonicalization on reads (§9.1.2),
   ordering guarantees across batched updates (§12.4), role config interaction
   with arbitration (§15).

2. **Tests are shallow.** Many tests check the happy path plus one error case.
   The arbitration tests cover 7 scenarios, but the state machine has many more
   edge cases: same election_id on two streams, re-arbitration on the same
   stream, races between arbitration and writes, rapid connect/disconnect. Write
   validation tests cover one bad value per field type, not boundary cases.

3. **N/A judgments deserve scrutiny.** 10 requirements are marked N/A. Most are
   defensible (digests, idle timeouts, two-phase commit), but should be
   re-evaluated as scope grows — particularly DATAPLANE_ATOMIC (§12) and
   role-based access control (§15) if DVaaS compatibility requires them.

4. **Independent oracles are scoped.** The data plane has BMv2 differential
   testing (186 programs, bit-for-bit). The P4Runtime server now has scoped BMv2
   differential tests and a proven-but-unmerged fuzzer harness, but these do not
   yet cover every RPC surface, entity type, or concurrency edge case. Our
   remaining risk is breadth, not absence of independent validation.

| Area | Confidence | Why |
|------|-----------|-----|
| Write validation (fields, actions, params) | High | Thorough unit tests, multiple P4 schemas |
| Read/wildcard semantics | High | Well-covered by conformance tests |
| Pipeline config lifecycle | High | Good coverage of load/reload/clear |
| Translation (@p4runtime_translation) | High | Dedicated test suite + SAI P4 E2E |
| Arbitration state machine | Medium | Core cases covered, edge cases not |
| Error code compliance (exact gRPC status) | Medium | Conformance-tested; fuzzer PR independently checked Write status codes |
| Batch/atomicity semantics | Medium | Simple scenarios only |
| P4Runtime diff coverage | Medium | BMv2 diff suite covers table/action-profile scenarios; broader RPC coverage remains |
| Spec completeness (did we miss requirements?) | Low | Hand-distilled, no systematic extraction |

### Open questions

- Can we extract a small, dependency-light subset of the sonic-pins P4 fuzzer
  oracle, or is an external/manual fuzzer branch the right long-term boundary?
- What is the equivalent of fuzzing for Read, StreamChannel, arbitration, and
  pipeline config lifecycle?
- How far should the BMv2 P4Runtime diff suite grow before the maintenance cost
  outweighs the additional confidence?
