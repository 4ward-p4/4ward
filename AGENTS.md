# 4ward — Agent Guide

**Always work in a dedicated git worktree** — never modify the main tree
directly. This keeps the main tree clean and allows parallel work.

```sh
git fetch origin main
git worktree add ../4ward-<branch> -b <branch> origin/main  # create from latest main
git worktree remove ../4ward-<branch>                       # clean up after merging
git worktree prune                                          # gc dangling refs
```

For completed changes, do not stop at local edits. Commit, push, and open a
draft PR unless the user explicitly asks not to.

## Philosophy

**We strive for simplicity.** Complex is easy; simple is extremely hard.
Simple code, simple designs, simple interfaces — earned through the
effort of deeply understanding the problem. Every layer of indirection,
every abstraction, every "just in case" parameter must justify its
existence. When in doubt, leave it out.

**Build the ideal, not "good enough."** Before committing to a design,
define what the ideal solution looks like — unconstrained by schedule,
legacy, or expedience. Then build it. A pragmatic shortcut is legitimate
when you've considered the ideal and have a concrete reason to defer
it — but the default should be to do the right thing, not to stop
early. Name the north star, name what you're trading away, and name why.

**Write the test first.** The test is the spec — it defines the behavior
you want before you write the code. If you can't write a clear test, you
don't understand the problem yet. A failing test is the starting point
for every change, not an afterthought.

**Write DAMP tests, not DRY tests.** Each test should be readable
top-to-bottom without chasing helpers. When a test fails, you want the
full context right there. Three similar test bodies are better than one
parameterized helper that obscures the scenario.

**Walking skeleton first.** Build a minimal end-to-end slice before
filling in any one layer. Get an ugly-but-working pipeline — compiling,
wiring, passing one trivial test — before polishing internals.
Integration problems are cheap to fix now, expensive later.

**Churn is free.** Don't leave behind dead code, redundant helpers, or
stale call sites because updating them would "touch too many files."
You are an AI coding agent — mechanical refactoring across dozens of
files is exactly what you're good at.

## Design invariants

1. **Simplicity over performance.** This is a development and testing
   tool. Optimize for correctness and ease of reasoning — fast to
   understand, fast to debug, fast to change. Not fast to execute.

2. **Never fail silently.** Prefer compile-time failures (exhaustive
   `when`, type constraints) over runtime checks. When runtime checks are
   needed, fail loudly (`error()`, `require()`, gRPC `UNIMPLEMENTED`).
   Never let unhandled inputs fall through to a default path.

   **Proto oneofs: always switch on the case enum.** Use
   `when (msg.kindCase)`, never `when { msg.hasFoo() -> ... }`. The
   enum form is exhaustive at compile time — adding a new variant
   produces a compiler warning rather than silently falling through.
   This applies everywhere: `evalExpr`, `evalLiteral`, `execStmt`,
   statement/expression visitors, type dispatchers, etc.

3. **The proto IR uses names, not IDs.** All cross-references in
   `ir.proto` and `simulator.proto` use string names. Numeric IDs belong
   to p4info (the control-plane API) only.

4. **Every Expr carries a Type.** The `type` field on `Expr` is always
   populated by the p4c backend. The simulator must never infer types at
   runtime.

5. **The simulator owns all data-plane state.** Table entries, counters,
   registers — all live in the Kotlin simulator. The P4Runtime server
   (`grpc/`) is a thin adapter that forwards requests; it holds no P4
   state of its own.

6. **When changing concurrency assumptions, audit every dependent site.**
   Code written under "single-threaded" hides caches, shared mutable
   state, and lazy init that becomes racy under concurrency. Update both
   the code and any docs that asserted the old contract.

## Workflow

### Build and test

```sh
bazel build //...                              # build everything
bazel test //... --test_tag_filters=-heavy     # run tests (skip heavy ones)
bazel test //...                               # run ALL tests (CI does this)
./tools/format.sh                              # auto-format all files
./tools/lint.sh                                # lint (clang-tidy + detekt)
```

Use `--test_tag_filters=-heavy` locally to skip tests that spawn many JVM
processes. CI runs all tests including heavy ones. **CI has a warm remote
cache — often faster than a cold local build.** Push early and let CI
run.

### Before submitting

- Proactively add unit tests. For one-off P4 programs, use
  `compileInlineP4()` (`e2e_tests/InlineP4Compiler.kt`) to compile P4
  source at test time without a dedicated BUILD target.
- Run `./tools/format.sh` and `./tools/lint.sh`. Fix all warnings, even
  pre-existing ones.
- Check whether your change affects [LIMITATIONS.md](docs/LIMITATIONS.md)
  or [REFACTORING.md](docs/REFACTORING.md).
- **NEVER edit docs/STATUS.md.** It is maintained exclusively by the
  project owner.
- **The linter serves us, not the other way around.** When a rule doesn't
  fit the code's natural structure, adjust the threshold rather than
  contorting the code.

### Commits and PRs

After committing and pushing a completed change, proactively open a draft PR
unless the user explicitly asks not to. Open PRs in draft mode
(`gh pr create --draft`). Rebase onto `origin/main` before submitting.

Commit messages: focus on *why*, not *what* — the diff already shows
what changed. PR descriptions: lead with the win, be concise. Don't
drown achievements in low-level details.

## Code style

We follow [Google C++][cpp], [Google Kotlin][kotlin],
[Abseil Tips of the Week][abseil], and [Protobuf Best Practices][proto].

[cpp]: https://google.github.io/styleguide/cppguide.html
[kotlin]: https://google.github.io/styleguide/kotlinguide.html
[abseil]: https://abseil.io/tips/
[proto]: https://protobuf.dev/best-practices/dos-donts/

Key project-specific rules:

- **Optimize for the reader, not the writer.** Code is read far more
  often than it is written. Every decision — naming, structure,
  comments — should minimize cognitive load for the reader.
- **Self-documenting code, generous why-comments.** Comments should
  anticipate and answer questions an expert reader new to this code
  would have: *why is this needed? why not the simpler thing? why is
  this safe?* The ideal is code that doesn't raise questions to begin
  with — this is the north star. The next best thing is
  code that concisely answers the reader's questions at the exact place
  they emerge using a comment. If the reader has to stop and think, the
  code or its comments have failed.
- **Assertions over comments.** `require()`, `check()`, or exhaustive
  `when` enforce invariants; comments only describe intent.
- **Never use deprecated APIs.** Find the successor immediately — a
  deprecated call that works today is a broken call on the next upgrade.
- **Flat C++ namespaces.** `namespace fourward` is almost always enough
  ([TotW #130](https://abseil.io/tips/130)).
- **Abseil containers over std.** `absl::flat_hash_map` over
  `std::unordered_map`, `absl::btree_map` over `std::map`, etc.
- **Shortcuts go in [LIMITATIONS.md](docs/LIMITATIONS.md)** with a `TODO`
  at the site. Workarounds get a `WORKAROUND` comment explaining what's
  broken and what the code should look like once the upstream issue is
  fixed.

### Writing style

Write the way you'd explain something to a colleague — clear, simple
sentences. What → why → how. If someone reads only the first paragraph,
they should walk away with the right mental model. Design docs are
historical records — don't rewrite them to match current code; add a
revision note.

## P4 language notes

The authoritative source is the [P4₁₆ Language Specification][p4spec].
**When in doubt, consult the spec.** If the spec is ambiguous, follow
p4c's behavior and document with a comment citing the spec section.
For v1model, the de facto spec is the [BMv2 simple_switch docs][bmv2].

[p4spec]: https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html
[bmv2]: https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md

[jafingerhut/p4-guide](https://github.com/jafingerhut/p4-guide) is a
community knowledge base. Especially useful:
[v1model-special-ops](https://github.com/jafingerhut/p4-guide/tree/master/v1model-special-ops)
(clone, resubmit, recirculate, multicast) and
[p4-table-behaviors](https://github.com/jafingerhut/p4-guide/blob/master/docs/p4-table-behaviors.md)
(table match/miss edge cases).

The IR is emitted after p4c's midend — it reflects a simplified,
fully-resolved program.

## Repository map

```
docs/ARCHITECTURE.md         Design rationale. Read this first.
simulator/ir.proto           The behavioral IR. The core contract.
simulator/simulator.proto    Shared types (P4Runtime, STF, tests).
simulator/*.kt               Kotlin simulator (the heart of 4ward).
p4c_backend/*.{h,cpp}        C++ p4c backend (emits proto IR).
grpc/*.kt                    P4Runtime + Dataplane gRPC services.
fourward_cc/*.{h,cc}         C++ embedding API.
cli/*.kt                     Standalone CLI (compile / sim / run).
web/*.kt                     Web playground and graph extractors.
examples/*.p4                Ready-to-run example programs.
examples/tutorial.t          CLI tutorial (also a cram regression test).
e2e_tests/                   STF, trace-tree, corpus, p4testgen tests.
designs/                     Design documents.
docs/                        Project documentation.
docs/RELEASING.md            How to cut a release and publish to the BCR.
userdocs/                    User-facing documentation site (mkdocs).
tools/                       Developer scripts (format, lint, …).
```

Unit tests live alongside source (`FooTest.kt` next to `Foo.kt`).
