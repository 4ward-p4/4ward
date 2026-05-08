---
description: "How 4ward translates between P4Runtime SDN values and data-plane values using explicit, auto-allocate, and hybrid mapping modes."
---

# Type Translation

Type translation is a
[P4Runtime](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html)
mechanism that decouples controller-facing values from data-plane values. P4
programs declare translated types with the
[`@p4runtime_translation`](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html#sec-user-defined-types)
annotation — but the P4Runtime spec leaves the actual mapping mechanism
unspecified. Every deployment rolls its own. 4ward ships a built-in
translation engine that handles this automatically.

## The problem

A P4 program might declare:

```p4
@p4runtime_translation("", string)
type bit<9> port_id_t;
```

The controller sends `"Ethernet0"` (a string). The P4 program processes a
9-bit integer. Something has to map between these — but the P4Runtime spec
doesn't say how. Both `sdn_string` (like SAI P4's port names) and
`sdn_bitwidth` (fixed-width integers in a wider SDN representation) are
supported.

## Translation modes

4ward supports three modes:

- **Auto-allocate** (default) — 4ward assigns data-plane values on first use.
  Zero config. The controller sends any value; 4ward maps it to 0, 1, 2, ...
- **Explicit** — you provide the full mapping table upfront. Unknown values
  are rejected.
- **Hybrid** — pin the values that matter, auto-allocate the rest.

```
Hybrid mode example — pin special ports, auto-allocate the rest:

  explicit:  "CpuPort"    → 510
  explicit:  "DropPort"   → 511
  auto:      "Ethernet0"  →   0  (assigned on first use)
  auto:      "Ethernet1"  →   1
  auto:      "Ethernet2"  →   2
```

## Configuring mappings

There are two ways to provide explicit mappings.

### In P4 source: `@p4runtime_translation_mappings`

The P4 program itself can declare mappings inline:

```p4
@p4runtime_translation("", string)
@p4runtime_translation_mappings({
  {"CpuPort", 510},
  {"DropPort", 511}
})
type bit<9> port_id_t;
```

The 4ward p4c backend extracts these at compile time and converts them to
translation entries with auto-allocate enabled (hybrid mode). This is the
most convenient approach — the mappings live with the type declaration.

### At pipeline load: `DeviceConfig.translations`

Alternatively, configure mappings in `SetForwardingPipelineConfig`:

```textproto
# proto-file: @fourward//simulator/ir.proto
# proto-message: fourward.DeviceConfig
translations {
  type_name: "port_id_t"
  auto_allocate: true
  entries { sdn_str: "Ethernet0"  dataplane_value: "\x00" }
  entries { sdn_str: "Ethernet1"  dataplane_value: "\x01" }
}
```

This is useful when the controller, not the P4 program, owns the mapping.

Without either source of configuration, auto-allocation is used by default.

## Where translation happens

| Operation | Direction | What is translated |
|-----------|-----------|-------------------|
| **Write** (table entry, action profile) | P4RT → dataplane | Match fields, action params |
| **Read** (table entry, action profile) | Dataplane → P4RT | Match fields, action params |
| **PacketOut** | P4RT → dataplane | Metadata fields |
| **PacketIn** | Dataplane → P4RT | Metadata fields |
| **InjectPacket** | Either direction | Ingress port (`p4rt_ingress_port` oneof) |

Translation only applies to fields whose p4info `type_name` resolves to a
`@p4runtime_translation`-annotated type.

## Ports are special

Most translated types appear in table entries — match fields and action
parameters. Ports are different: they appear in hardcoded proto fields across
multiple messages (`InjectPacketRequest.ingress_port`,
`OutputPacket.egress_port`, `PacketIn`/`PacketOut` metadata, clone session
replicas).

4ward creates a port translator when the architecture's port metadata field
uses a `type` (not `typedef`) with `@p4runtime_translation`. Stock
architectures use `typedef` — no port translation. Programs that need port
translation use a [forked architecture](architecture-modifications.md).

## Traces

When translation is available, [trace trees](traces.md) carry P4RT values
alongside dataplane values — port names on ingress/egress events, and
translated match fields and action params on table lookups. See
[P4RT enrichment](traces.md#p4rt-enrichment).
