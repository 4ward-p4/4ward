---
description: "4ward is a glass-box P4 simulator that traces every decision your packet makes — parser transitions, table lookups, actions, and branches — as structured trace trees."
---

<p align="center">
  <img src="assets/logo.svg" alt="4ward logo" width="160" class="off-glb">
</p>

# 4ward

**A glass-box P4 simulator — trace every decision your packet makes.**

4ward is a spec-compliant [P4₁₆](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/P4-16-v1.2.4.html) reference simulator that produces **trace
trees**: a complete record of every parser transition, table lookup, action
execution, and branch decision a packet encounters. At non-deterministic
choice points (action selectors, clone, multicast), the trace forks — showing
all possible outcomes in a single pass.

!!! tip "Prefer to get your hands dirty?"

    Jump straight to the **[hands-on tutorial]({{ repo }}/blob/main/examples/tutorial.t)** — compile a P4 program, send packets, read traces, all from the terminal. Every command is CI-verified, so it always works.

## Pick your entry point

| Entry point | Description |
|---|---|
| **[Web Playground](getting-started/playground.md)** | Edit P4 in the browser, install table entries, send packets, step through traces visually. |
| **[CLI](getting-started/cli.md)** | Compile a P4 program, run an STF test, read the trace — all from the terminal. |
| **[gRPC API](getting-started/grpc.md)** | Load pipelines and inject packets programmatically via the DataplaneService and P4Runtime gRPC services. |
| **[C++ Library](reference/embedding-cc.md)** | Embed 4ward in a C++ project via Bazel, inject packets with designated-init call sites, assert with composable [gtest matchers](reference/dataplane-matchers.md). |

## Who is this for?

- **Network engineers** testing P4 programs — send a packet, see exactly what
  happened and why.
- **Platform teams** integrating a P4 simulator into test infrastructure
  (DVaaS, sonic-pins) via the gRPC API.
- **Students and researchers** learning P4 — the web playground lets you edit,
  compile, and trace P4 programs in the browser.

## Key features

- **Trace trees** record every parser transition, table lookup, action, and
  branch — and fork at non-deterministic choice points to show all possible
  outcomes. [Learn more](concepts/traces.md).
- **v1model, PSA, and PNA** are fully implemented, with support for
  architecture modifications like translated port types.
  [Learn more](concepts/architectures.md).
- A spec-compliant **[P4Runtime](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html) server** provides full
  arbitration, table management, PacketIO, and clone/multicast support.
- **Actionable error messages** tell you what went wrong, show the value you
  sent, and list the valid options — all 75 error paths are golden-tested.
  [Learn more](reference/errors.md).
- **Type translation** lets `@p4runtime_translation` types (string port names,
  translated IDs) flow through the entire stack, including traces.
  [Learn more](concepts/type-translation.md).
- The **web playground** gives you visual pipeline diagrams, animated trace
  playback, and packet dissection right in the browser.
  [Try it](getting-started/playground.md).
