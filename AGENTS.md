# 4ward — Agent Guide

**Always work in a dedicated git worktree — never modify the main tree
directly.** This keeps the main tree clean and allows parallel work without
conflicts. Create one with:

```sh
git worktree add ../4ward-<branch> -b <branch>
```

Clean up after merging:

```sh
cd /path/to/main/tree
git worktree remove ../4ward-<branch>
git worktree prune
```

## Understand ideal before settling for less

Before committing to any design or implementation, define what the ideal
solution looks like — unconstrained by schedule, legacy, or expedience.
You don't have to build the ideal, but YOU MUST UNDERSTAND IT. A pragmatic
shortcut is a legitimate engineering choice; a shortcut you took because you
never considered the alternative is just a blind spot. Name the north star,
name what you're trading away, and name why.

## Test-driven development

**Write the test first.** The test is the spec — it defines the behavior
you want before you write the code. If you can't write a clear test, you
don't understand the problem yet. A failing test is the starting point for
every change, not an afterthought.

**Write DAMP tests, not DRY tests.** Each test should be readable
top-to-bottom without chasing helpers. Prefer duplicating setup over
extracting it into a shared function — when a test fails, you want the
full context right there. Three similar test bodies are better than one
parameterized helper that obscures the scenario.

## Walking skeleton first

Build a minimal end-to-end slice through the entire system before filling in
any one layer. Get an ugly-but-working pipeline — compiling, wiring, passing
one trivial test — before polishing internals. This forces integration
problems to surface immediately, when they're cheap to fix, instead of at
the end when everything is "done" but nothing works together. Depth comes
second; connectivity comes first.

## Code style

Write self-explanatory code. Add a comment when the code deviates from the
obvious approach, works around a non-obvious constraint, or implements a
subtle spec requirement. Include spec references (section numbers, GitHub
issues) where helpful. Do not add comments that merely restate the code.

**Never use deprecated APIs.** When a library marks a function deprecated,
find and use the successor immediately — don't suppress the warning. A
deprecated call that works today is a broken call on the next upgrade.

For C++, follow the [Google C++ Style Guide] and
[Abseil Tips of the Week]. In particular, keep namespaces flat
([TotW #130]) — don't introduce sub-namespaces that don't earn their
keep. `namespace fourward` is almost always enough.

[Google C++ Style Guide]: https://google.github.io/styleguide/cppguide.html
[Abseil Tips of the Week]: https://abseil.io/tips/
[TotW #130]: https://abseil.io/tips/130

If you take a shortcut or skip a corner case, note it in
[LIMITATIONS.md](docs/LIMITATIONS.md) with a `TODO` comment at the site.
Mark workarounds with a prominent `WORKAROUND` comment explaining what is
broken and what the code should look like once the upstream issue is fixed.

## Writing style (docs, READMEs, comments)

Write the way you'd explain something to a colleague — clear, simple
sentences that flow naturally. Not compressed noun phrases, not verbose
filler. [Paul Graham](https://paulgraham.com/articles.html) and
[Scott Galloway](https://www.profgalloway.com/) are good north stars.

- **What → why → how.** First sentence: what is this thing? Then: why
  does it exist? Implementation details come last.
- **One big idea.** Every doc has a single most important message.
  State it plainly up front. If someone reads only the first paragraph,
  they should walk away with the right mental model.
- **Be precise about scope.** "The gRPC interface" claims exclusivity;
  "a gRPC interface" is honest when there are other ways in. Don't say
  "test tooling" when controllers and debug scripts use it too.
- **Match the existing voice.** The 4ward docs have personality ("where
  the magic happens", "life is too short for overflow bugs"). Read the
  surrounding prose before adding to it.
- **Design docs are historical records.** Don't rewrite them to match
  current code — add a revision note and leave the original reasoning
  intact.

## Build and test

```sh
bazel build //...                              # build everything
bazel test //... --test_tag_filters=-heavy     # run tests (skip heavy ones)
bazel test //...                               # run ALL tests (CI does this)
./tools/format.sh                              # auto-format all files
./tools/lint.sh                                # lint (clang-tidy + detekt)
./tools/dev.sh help                            # show all developer commands
```

**Use `--test_tag_filters=-heavy` locally** to skip tests that spawn many
JVM processes. CI runs all tests including heavy ones.

**CI is fast and has a warm remote cache — often faster than a cold local
build.** Push early and monitor results.

## Commits and pull requests

Focus commit messages on *why* the change is being made and what problem it
solves. Don't restate what the diff already shows.

Open PRs in draft mode (`gh pr create --draft`). Rebase onto `origin/main`
before submitting. Lead with the win — what changed for the project, how it
fits into the big picture. Be concise and punchy. Don't drown achievements
in low-level details; the diff already has those.

Before submitting:

- Proactively add unit test. For one-off P4 programs, use
  `compileInlineP4()` (`e2e_tests/InlineP4Compiler.kt`) to compile P4
  source at test time without a dedicated BUILD target.
- Run `./tools/format.sh` and `./tools/lint.sh`. Fix all warnings, even
  pre-existing ones.
- Check whether your change affects [LIMITATIONS.md](docs/LIMITATIONS.md) or
  [REFACTORING.md](docs/REFACTORING.md).
- **NEVER edit docs/STATUS.md.** It is maintained exclusively by the project
  owner.
- **The linter serves us, not the other way around.** When a rule doesn't fit
  the code's natural structure, adjust the threshold rather than contorting the
  code.

## Key design invariants — do not break these

1. **Correctness over performance.** If you are tempted to optimize something at
   the cost of readability or correctness, don't. This is a development and
   testing tool.

2. **Never fail silently.** Prefer compile-time failures (exhaustive `when`
   expressions, type system constraints) over runtime checks. When runtime
   checks are needed, fail loudly (`error()`, `require()`, gRPC
   `UNIMPLEMENTED`). Never let unhandled inputs fall through to a default path.

   **Proto oneofs: always switch on the case enum.** When dispatching on a
   proto `oneof`, use `when (msg.kindCase) { KindCase.FOO -> ... }`, never
   `when { msg.hasFoo() -> ... msg.hasBar() -> ... }`. The enum-based form
   is exhaustive at compile time — adding a new oneof variant produces a
   compiler warning rather than silently falling through to `else`. This
   applies everywhere: the interpreter's `evalExpr`, `evalLiteral`,
   `execStmt`, statement/expression visitors, type dispatchers, etc.

3. **The proto IR uses names, not IDs.** All cross-references in `ir.proto` and
   `simulator.proto` use string names. Numeric IDs belong to p4info (the
   control-plane API) only.

4. **Every Expr carries a Type.** The `type` field (field number 100) on `Expr`
   is always populated by the p4c backend. The simulator must never infer types
   at runtime.

5. **The simulator is the source of truth for all data-plane state.** Table
   entries, counters, registers — all live in the Kotlin simulator. The
   P4Runtime server (`grpc/`) is a thin adapter that forwards requests;
   it holds no P4 state of its own.

6. **When changing concurrency assumptions, audit every site that depended on
   the old assumption.** Code written under "single-threaded" can hide caches,
   shared mutable state, and lazy initialisation that becomes racy the moment
   multiple threads enter. Update both the code and any docs that asserted the
   old contract — a stale "single-threaded" comment is how the next
   maintainer assumes a guarantee the code no longer provides. Document the
   new contract per-method, not just at the class level, so it shows up at the
   call site.

## P4 language notes

The authoritative source for language semantics is the
[P4₁₆ Language Specification](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html).
**When in doubt, consult the spec.** If the spec is ambiguous, follow p4c's
behavior and document the ambiguity with a comment citing the relevant spec
section. For v1model architecture semantics, the de facto spec is the
[BMv2 simple_switch documentation](https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md).

[jafingerhut/p4-guide](https://github.com/jafingerhut/p4-guide) is a
community knowledge base with worked examples and detailed write-ups for
many P4 features. Especially useful:
- [`v1model-special-ops/`](https://github.com/jafingerhut/p4-guide/tree/master/v1model-special-ops) —
  clone, resubmit, recirculate, and multicast examples with packet
  traces. Essential reference for v1model semantics the spec leaves
  underspecified.
- [`docs/p4-table-behaviors.md`](https://github.com/jafingerhut/p4-guide/blob/master/docs/p4-table-behaviors.md) —
  exhaustive catalog of table match/miss/default-action edge cases.

The IR is emitted after p4c's midend, so it reflects a simplified,
fully-resolved program.

## Repository map

```
docs/ARCHITECTURE.md         Design rationale. Read this first.
simulator/ir.proto           The behavioral IR. The core contract of the project.
simulator/simulator.proto    Shared types for simulator clients (P4Runtime, STF, tests).
simulator/*.kt               Kotlin simulator (the heart of 4ward).
p4c_backend/*.{h,cpp}        C++ p4c backend plugin (emits proto IR from P4 source).
grpc/*.kt                    P4Runtime + Dataplane gRPC services (Kotlin).
fourward_cc/*.{h,cc}         C++ embedding API (FourwardServer, DataplaneClient).
cli/*.kt                     Standalone CLI (4ward compile / sim / run).
web/*.kt                     Web playground server and graph extractors (Kotlin).
examples/*.p4                Ready-to-run example programs.
examples/tutorial.t          CLI tutorial (also a cram regression test).
e2e_tests/stf/               STF test runner (drives the simulator subprocess).
e2e_tests/corpus/            Corpus-based STF test harness (bulk p4c test suite).
e2e_tests/trace_tree/        Golden trace-tree tests (proto-based, not STF).
e2e_tests/p4testgen/         p4testgen-generated STF tests (one target per P4 program).
e2e_tests/*/                 Per-feature STF tests (passthrough, basic_table, lpm, …).
designs/                     Design documents (architecture decisions, feature proposals).
docs/                        Project documentation.
docs/RELEASING.md            How to cut a release and publish to the BCR.
userdocs/                    User-facing documentation site (mkdocs).
tools/                       Developer scripts (format, lint, coverage, …).
```

Unit tests live alongside the source they test (`FooTest.kt` next to `Foo.kt`).
