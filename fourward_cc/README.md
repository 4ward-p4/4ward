# C++ API

A C++ interface to 4ward. If your project is C++ — like
[PINS](https://github.com/sonic-net/sonic-pins) or
[DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas) — you
can drive 4ward directly from your C++ code without any Kotlin or JVM
setup.

Three pieces:

- **`FourwardServer`** — lets you spin up a 4ward instance simply by
  creating a C++ object (RAII wrapper). C++ code can then interact
  with 4ward via its [gRPC API](../grpc/).
- **`DataplaneClient`** — if using gRPC directly feels tedious, this
  wraps the Dataplane service into plain C++ calls for injecting
  packets and reading results.
- **[Dataplane matchers](../userdocs/reference/dataplane-matchers.md)**
  — gtest matchers for asserting on packet outputs. `ForwardsTo(1)`,
  `Drops()`, `OutcomeIs(OnPort(1), OnPort(2))`.

See the [embedding guide](../userdocs/reference/embedding-cc.md).
