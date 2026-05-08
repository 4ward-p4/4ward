# Bazel Central Registry (BCR) Test Module

A minimal Bzlmod module that depends on `@fourward` via
`local_path_override`, exercising the same resolution path 
[BCR](https://registry.bazel.build/) consumers use. It serves two purposes:

- **BCR presubmit validation.** Referenced by `bcr_test_module:` in the
  fourward BCR entry's `presubmit.yml`. BCR's CI builds `@fourward//...`
  from here to verify the module is consumable as a dependency.

- **Regression check in our own CI.** The `bcr-consumer-check` job in
  [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) builds from
  this module on every PR, catching "works as root, broken as dep" bugs
  before they reach the BCR submission PR — things like dev-dep leaks,
  `git_override`s that don't apply to non-root modules, or maven
  namespace conflicts.

## Using @fourward as a consumer

Copy [`MODULE.bazel`](MODULE.bazel) as a starting point for your own
project. The `known_contributing_modules` bless is only needed if you
pull in `@fourward//grpc` (see the comment in that
file for why); otherwise the `bazel_dep(name = "rules_jvm_external", ...)`
and the `maven.install(...)` block can go away.

## Building locally

```sh
cd bcr_test_module
bazel build -- @fourward//... -@fourward//e2e_tests/... -@fourward//examples/...
```

`e2e_tests/` and `examples/` are excluded because they carry
`dev_dependency` targets (like `@behavioral_model`) that aren't
visible to non-root consumers — and they're not part of the public
surface anyway.

Use [bazelisk](https://github.com/bazelbuild/bazelisk): the
[`.bazelversion`](.bazelversion) here pins Bazel 8.x because BCR's
`grpc@1.80.0` pulls `rules_swift@2.5.0`, which conflicts with
`rules_swift@3.1.2` from Bazel 9's `bazel_tools`. (`@fourward`-as-root
sidesteps this with a `grpc` `git_override`, but overrides don't
apply to non-root modules — which is the whole point of this test.)
