---
description: "How 4ward handles forked P4 architectures with modified standard_metadata fields, custom headers, and architecture-specific extern behavior."
---

# Architecture Modifications

4ward supports forked P4 architecture definitions out of the box — no changes
to 4ward itself. You can customize port widths, add `@p4runtime_translation`
for string port names, and adjust metadata fields. The simulator
automatically adapts: it derives the drop port from the port width, creates a
port translator when translation annotations are present, and enriches traces
with P4RT values. This works because 4ward reads the architecture from the
compiled IR, not from hardcoded assumptions.

There are two kinds of modifications: **forking a `.p4` definition** (the
common case — no 4ward changes needed) and **implementing a new backend**
(for entirely new architectures — requires changes to 4ward).

## Forking an architecture definition

A forked architecture is a customized copy of a standard architecture file
(like `v1model.p4`). Common reasons to fork:

- **Translated port types** — enable `@p4runtime_translation` for string port
  names like `"Ethernet0"`.
- **Wider ports** — change `bit<9>` to `bit<16>` or `bit<32>` for platforms
  with more ports. The drop port adjusts automatically (`2^N - 1`).
- **Custom metadata** — add fields to `standard_metadata_t`.

### Example: translated port types

4ward's test suite includes a forked `v1model.p4` that replaces the stock port
`typedef` with a `type` annotated with `@p4runtime_translation`. This is the
kind of change planned for SAI P4 but not yet adopted upstream.

**Stock v1model:**

```p4
typedef bit<9> PortId_t;   // just an alias — no translation possible
```

**Forked v1model:**

```p4
@p4runtime_translation("", string)
type bit<16> port_id_t;    // newtype — string port names, wider ports
```

Both changes cascade through the system automatically:

- **String port names** — controllers send `"Ethernet0"` instead of `0`.
- **Dual port encoding** — the DataplaneService returns both
  `dataplane_egress_port: 0` and `p4rt_egress_port: "Ethernet0"`.
- **Trace enrichment** — trace trees show P4RT port names alongside raw
  numbers.
- **Drop port** — shifts from 511 (`2^9 - 1`) to 65535 (`2^16 - 1`).

### How to fork

1. **Copy** the architecture file (e.g., `v1model.p4` from p4c's include
   directory).
2. **Make your changes** — port type, port width, metadata fields.
3. **Include your fork** in your P4 program:
   ```p4
   #include "my_v1model.p4"
   ```
4. **Compile normally** — the 4ward backend reads everything from the IR.
   No 4ward configuration needed.

The pipeline structure (stages, externs) stays the same — only the type and
metadata definitions change.

## Implementing a new architecture

Adding a completely new P4 architecture (e.g., PNA, TNA) requires changes to
4ward: a Kotlin class that implements the `Architecture` interface and handles
the architecture's pipeline stages, externs, and non-deterministic operations.

Use [`V1ModelArchitecture.kt`](https://github.com/smolkaj/4ward/blob/main/simulator/V1ModelArchitecture.kt)
as the reference implementation.

### Step 1: Implement `Architecture`

```kotlin
class PnaArchitecture : Architecture {
  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult {
    // Run pipeline stages in order.
    // Handle architecture-specific externs.
    // Produce trace tree with continuations/choices for packet
    // multiplication and non-deterministic operations.
  }
}
```

### Step 2: Handle externs

Create an `ExternHandler` for your architecture's free functions and object
methods:

```kotlin
private fun createExternHandler(state: State): ExternHandler =
  ExternHandler { call, eval ->
    when (call) {
      is ExternCall.FreeFunction -> when (call.name) {
        "my_forward" -> { /* set egress port */ }
        else -> error("unhandled extern: ${call.name}")
      }
      is ExternCall.Method -> when (call.externType) {
        "MyRegister" -> when (call.method) {
          "read" -> { /* return value */ }
          "write" -> { /* store value */ }
          else -> error("unhandled method")
        }
        else -> error("unhandled extern type")
      }
    }
  }
```

### Step 3: Register

Add your architecture to the dispatcher in [`Simulator.kt`](https://github.com/smolkaj/4ward/blob/main/simulator/Simulator.kt):

```kotlin
architecture = when (archName) {
  "v1model" -> V1ModelArchitecture(behavioral, dropPort)
  "psa" -> PSAArchitecture(behavioral)
  "pna" -> PnaArchitecture(behavioral)   // ← add here
  else -> throw IllegalArgumentException("unsupported architecture: $archName")
}
```

### Key patterns

- **Branch at boundaries** — clone/resubmit/recirculate decisions are
  deferred to pipeline boundaries, not executed inline. The extern sets a
  flag; the boundary logic checks it and emits the continuations.
- **Drop port from port width** — `(1L shl portBits) - 1`. Never hardcoded.
- **Never fail silently** — unknown externs should `error()`, not fall through.
- **Trace everything** — log architecture-specific decisions (drops, clones,
  multicast) as trace events so users can understand packet flow.
