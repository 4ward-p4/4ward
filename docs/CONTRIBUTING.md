# Contributing to 4ward

Hey, thanks for being here! Whether it's your first open-source PR or your
thousandth, you're welcome.

## The easiest way to contribute

Check the [roadmap](ROADMAP.md) and [issue tracker](https://github.com/4ward-p4/4ward/issues)
for open work — or add a new STF test for an untested edge case and make it
pass. STF contributions are great because they're **naturally well-scoped** —
you know exactly when you're done (the test passes), and you don't need to
understand the whole codebase to get started.

## Getting started

```sh
# Clone and build — that's really all there is to it.
git clone https://github.com/4ward-p4/4ward
cd 4ward
bazel build //...
bazel test //...
```

Builds are hermetic — Bazel grabs everything it needs. You just need a C++20
compiler and Bazel 8+ (or grab
[Bazelisk](https://github.com/bazelbuild/bazelisk) so you never think about
Bazel versions again). Works on macOS and Ubuntu.

## How we work

4ward is developed with AI coding agents — the human's job is to steer and
review, not to type every line. We strongly encourage you to work this way
too. The repo is designed for it: [AGENTS.md](../AGENTS.md) and
[CLAUDE.md](../CLAUDE.md) give agents the context they need to make good changes
autonomously, and `bazel test //...` is the definition of done.

If you're new to AI-assisted development on complex codebases, see
[AI_WORKFLOW.md](AI_WORKFLOW.md).

Run `./tools/dev.sh help` for a summary of available developer commands.

## Making changes

1. Fork the repo and create a branch.
2. Make your change. New behavior needs a test (usually an STF test does the
   trick).
3. Run `bazel test //...` — everything should be green.
4. Run `./tools/format.sh` to auto-format everything.
5. Open a **draft** PR explaining what you changed and why.

Don't worry about getting it perfect on the first try — that's what review is
for. CI will post a coverage report on your PR automatically.

## Style

We follow the [Google style guides] — in particular the [C++] and
[Kotlin] guides. For C++, also follow the [Abseil Tips of the Week].
For protos, follow [Protobuf Best Practices].
Formatters handle the details (`./tools/format.sh`).

[Google style guides]: https://google.github.io/styleguide/
[C++]: https://google.github.io/styleguide/cppguide.html
[Kotlin]: https://google.github.io/styleguide/kotlinguide.html
[Abseil Tips of the Week]: https://abseil.io/tips/
[Protobuf Best Practices]: https://protobuf.dev/best-practices/dos-donts/

- **Proto**: `snake_case` fields, `UPPER_SNAKE_CASE` enum values with a type prefix
- **Commit messages**: tell us *why*, not *what* — the diff already shows the what

## Proto stability

`ir.proto` and `simulator.proto` are the contract between the backend and the
simulator, so treat field numbers like promises: never remove or renumber them.
Add new fields instead.

## On correctness

4ward is a spec-compliant reference implementation: it should behave exactly as
the [P4₁₆ spec](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html)
describes. When you're unsure about a language detail, check the spec. If the
spec is ambiguous, follow p4c's behavior and document the ambiguity in a
comment citing the relevant section.

4ward is proudly not fast. It's correct, it's observable, and it's readable.
Please don't send PRs that trade any of those for speed — if you want a fast P4
data plane, BMv2 or DPDK are excellent choices.
