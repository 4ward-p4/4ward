# Intrinsic Ports Design

**Status: implemented**

## What makes a port intrinsic?

All non-intrinsic ports are equal and interchangeable — the data plane treats
them identically. Intrinsic ports are special because the system attaches
semantics to them beyond just forwarding:

- **Drop port**: packets egressing on this port are discarded. No output is
  produced.
- **CPU port**: packets egressing on this port are delivered to the control plane
  (P4Runtime) as PacketIn. Packets from the control plane (PacketOut) enter the
  data plane on this port.

### v1model and BMv2 specifics

v1model's `mark_to_drop` does nothing more than set `egress_spec` to the drop
port — dropping *is* egressing on the drop port.

BMv2 makes both intrinsic ports configurable at runtime (`--drop-port`,
`--cpu-port`). The drop port defaults to 511; the CPU port has no default and
must be explicitly set to enable PacketIn/PacketOut.

## Problem

The drop port and CPU port are handled in different layers with no shared
abstraction. The drop port is derived from port width in the simulator; the CPU
port is derived from port width in the P4Runtime layer. Neither is configurable.

## Design

### Defaults

Both intrinsic ports are data-plane values. Default values are derived from the
data-plane port width (`N`), following BMv2 conventions:

| Port | Default data-plane value | Port width source | Enabled by default |
|------|--------------------------|-------------------|--------------------|
| Drop port | `2^N - 1` (511 for `N=9`) | IR's `standard_metadata` port field width | Always |
| CPU port | `2^N - 2` (510 for `N=9`) | IR's `controller_packet_metadata` header field width | Only when p4info has `ControllerPacketMetadata` |

When `@p4runtime_translation` is in use, the control-plane representation of
these ports (e.g., SDN strings) is a separate concern handled by the
`TypeTranslator`.

#### Why enable the CPU port by default?

BMv2 requires an explicit `--cpu-port` flag — without it, packet I/O is
silently disabled. We chose a different default: the CPU port is automatically
derived when the p4info contains
[`ControllerPacketMetadata`](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html#sec-controller-packet-metadata)
(i.e., the P4 program uses `@controller_header`), and disabled otherwise.

The presence of `@controller_header` is a strong signal that the program expects
packet I/O. Silently disabling it when the user forgets a flag is worse than an
occasionally surprising default — especially for a development and testing tool
where "just works" matters. When the auto-configured value is wrong, the
override is always available.

### Simulator stays port-agnostic

The simulator knows about the drop port (needed by `mark_to_drop` and the
traffic manager) but not the CPU port. The CPU port has no data-plane semantics
— it's only meaningful to the layer that bridges data plane and control plane
(P4Runtime). This keeps the simulator focused on packet processing.

### Overrides

Both intrinsic ports are configurable via constructor/config parameters. The
drop port is always present (there is no way to disable dropping). The CPU port
override has three states:

- **unset** (default) → derive from p4info
- **explicit value** (e.g., `--cpu-port=510`) → use that data-plane port
- **disabled** (e.g., `--cpu-port=none`) → no CPU port, even if the p4info has
  `ControllerPacketMetadata`. Useful for testing the data plane in isolation
  without packet I/O interception. Packets egressing on what would have been
  the CPU port are treated as regular output. PacketOut is rejected with a
  `StreamError` / `PacketOutError` response on the StreamChannel (P4Runtime
  spec §16.6).

Each entry point sources the override however makes sense for its context:

| Entry point | Drop port override | CPU port override |
|---|---|---|
| **CLI** (`4ward run`) | `--drop-port` flag → passed to `Simulator` | `--cpu-port` flag → passed to `FourwardServer` |
| **P4Runtime server** | `--drop-port` flag → passed to `Simulator` | `--cpu-port` flag → kept in server |
| **Web playground** | Same as P4Runtime server (it wraps one) | Same as P4Runtime server |
| **STF runner** | Constructor param on `StfRunner` (default: null) | N/A — no P4Runtime, no CPU port semantics |
| **Test harness** | Constructor param on `FourwardTestHarness` | Constructor param on `FourwardTestHarness` |
