---
description: "P4 architecture support in 4ward: v1model, PSA, and PNA — what works, what differs, and how to choose."
---

# Architectures

4ward supports three P4 architectures: **v1model**, **PSA**, and **PNA**. Each
defines a fixed pipeline of stages, a set of externs, and metadata structures.
You can also fork or extend these — see
[Architecture Modifications](architecture-modifications.md).

## v1model

The BMv2 simple_switch architecture, originally designed to support P4_14
programs compiled to P4_16. Defined in
[v1model.p4](https://github.com/p4lang/p4c/blob/main/p4include/v1model.p4);
see the [simple_switch documentation](https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md)
for the canonical behavioral spec.

The pipeline has six stages in fixed order:

```
MyParser → MyVerifyChecksum → MyIngress → MyEgress → MyComputeChecksum → MyDeparser
```

Ports are 9-bit by default (`bit<9>`). The drop port is 511 (`2^9 - 1`) and
the CPU port is 510 (`2^9 - 2`, auto-enabled when `@controller_header` is
present). Both can be overridden via `--drop-port` and `--cpu-port` flags.

### Key metadata (`standard_metadata_t`)

| Field | Type | Description |
|-------|------|-------------|
| `ingress_port` | `bit<9>` | Input port (read-only) |
| `egress_spec` | `bit<9>` | Desired output port (set by ingress) |
| `egress_port` | `bit<9>` | Actual output port (read-only in egress) |
| `instance_type` | `bit<32>` | 0 = normal, 1 = ingress clone, 2 = egress clone |
| `mcast_grp` | `bit<16>` | Multicast group ID |
| `packet_length` | `bit<32>` | Packet byte count |

### Externs

**Forwarding and drops:**

- `mark_to_drop(standard_metadata)` drops the packet.

**Cloning, resubmit, recirculate:**

- `clone(CloneType, session_id)` creates an I2E or E2E clone.
- `clone_preserving_field_list(CloneType, session_id, field_list_id)` clones
  and preserves metadata fields annotated with the matching `@field_list` ID.
  Replaces the deprecated `clone3`.
- `resubmit_preserving_field_list(field_list_id)` re-injects the packet into
  ingress. Replaces the deprecated `resubmit(data)`.
- `recirculate_preserving_field_list(field_list_id)` feeds the deparsed packet
  back to the parser. Replaces the deprecated `recirculate(data)`.

!!! warning "Avoid the deprecated `clone3`, `resubmit`, and `recirculate`"

    These still compile but **do not reliably preserve metadata** with modern
    p4c — field lists may be
    [silently emitted as empty](https://github.com/p4lang/p4c/issues/2875),
    and the underlying semantics
    [violate P4_16 call conventions](https://github.com/p4lang/p4c/issues/1514).
    Always use the `_preserving_field_list` variants. See the
    [v1model special ops guide](https://github.com/jafingerhut/p4-guide/blob/master/v1model-special-ops/README.md)
    for a thorough writeup.

Multiple calls use last-writer-wins semantics. All of these produce
[trace tree branches](traces.md#forks).

**Checksums:**

- `verify_checksum(condition, data, checksum, algo)` validates a checksum.
- `update_checksum(condition, data, checksum, algo)` computes a checksum.
- The `_with_payload` variants include the packet payload.

**Hashing:**

- `hash(out result, algo, base, data, max)` computes
  `result = base + hash(data) mod max`.

**Logging:**

- `log_msg(msg)` / `log_msg(msg, data)` prints debug output, with `{}`
  as placeholders.

**Stateful:**

- `register<T>.read(out result, index)` / `.write(index, value)` provides
  generic array storage.
- `counter.count(index)` / `direct_counter.count()` increments a counter
  (fire-and-forget).
- `meter.execute_meter(index, out color)` always returns GREEN — runtime meter
  rate behavior is outside the simulator model.

## PSA

The Portable Switch Architecture is a more modern design with a cleaner
separation between ingress and egress. Defined in
[psa.p4](https://github.com/p4lang/p4-spec/blob/psa-v1.2/p4-16/psa/psa.p4);
see the [PSA specification](https://p4lang.github.io/p4-spec/docs/PSA.pdf)
for the canonical behavioral spec.

It uses a two-pipeline design with separate ingress and egress:

```
IngressParser → Ingress → IngressDeparser → EgressParser → Egress → EgressDeparser
```

Unlike v1model, egress re-parses the deparsed packet, so header and metadata
state does not persist across the ingress→egress boundary.

Ports are 32-bit by default (`bit<32>`). The drop port is `2^32 - 1` and the
CPU port is `2^32 - 2` (when `@controller_header` is present).

### Key metadata

The ingress output metadata (`psa_ingress_output_metadata_t`) controls the
packet's fate after ingress:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `drop` | `bool` | **true** | Must clear via `send_to_port()` or `multicast()` to forward |
| `egress_port` | `bit<32>` | - | Output port |
| `multicast_group` | `bit<16>` | - | Multicast group ID |
| `clone` | `bool` | false | I2E clone requested |
| `clone_session_id` | `bit<16>` | - | Clone session |
| `resubmit` | `bool` | false | Resubmit requested |

Note that **ingress drops by default** — you must explicitly call
`send_to_port()` to forward a packet. This is the opposite of v1model, where
packets forward by default.

### Externs

**Forwarding:**

- `send_to_port(ostd, port)` clears the drop flag and sets the egress port.
- `multicast(ostd, group)` clears the drop flag and sets the multicast group.
- `ingress_drop(ostd)` / `egress_drop(ostd)` sets the drop flag.

**Stateful:**

- `Register<T>.read(index) → T` / `.write(index, value)` returns the value
  directly (unlike v1model's `out` parameter).
- `Counter.count()` / `DirectCounter.count()` increments a counter
  (fire-and-forget).
- `Meter.execute(index) → color` always returns GREEN.
- `Random.read() → T` returns a random value in the constructor-specified
  range.

**Hashing:**

- `Hash.get_hash(data) → hash` is the 1-arg form.
- `Hash.get_hash(base, data, max) → (base + hash) mod max` is the 3-arg form.

Supported algorithms: `IDENTITY`, `CRC16`, `CRC32`, `ONES_COMPLEMENT16`.

**Checksum:**

- `InternetChecksum` supports `clear()`, `add(data)`, `subtract(data)`,
  `get()`, `get_state()`, and `set_state(value)`.

**Other:**

- `Digest.pack(data)` queues a message to the control plane (no-op in the
  simulator).

## PNA

The Portable NIC Architecture is designed for smart NICs with a single-pipeline
structure. Defined in
[pna.p4](https://github.com/p4lang/p4c/blob/main/p4include/pna.p4).

It uses a single-pipeline design with no separate egress:

```
MainParser → PreControl → MainControl → MainDeparser
```

Unlike PSA, forwarding uses free functions (`send_to_port()`,
`drop_packet()`) with last-writer-wins semantics instead of output metadata
fields. Like PSA, it drops by default — you must call `send_to_port()` to
forward a packet.

Ports are 32-bit (`bit<32>`) and direction-aware: each packet has a
`PNA_Direction_t` (`NET_TO_HOST` or `HOST_TO_NET`).

### Key metadata

The main input metadata (`pna_main_input_metadata_t`):

| Field | Type | Description |
|-------|------|-------------|
| `direction` | `PNA_Direction_t` | `NET_TO_HOST` or `HOST_TO_NET` |
| `input_port` | `bit<32>` | Input port |
| `parser_error` | `error` | Parser error (set after parsing) |
| `pass` | `bit<3>` | Recirculation pass number (0–7) |
| `loopedback` | `bool` | True if recirculated |
| `timestamp` | `bit<64>` | Packet arrival timestamp |

### Externs

**Forwarding (last-writer-wins):**

- `send_to_port(port)` forwards to a port.
- `drop_packet()` drops the packet (main control only per spec).
- `recirculate()` loops the deparsed bytes back to the parser.
- `mirror_packet(slot_id, session_id)` mirrors the deparsed bytes to a clone
  session.

**Direction:**

- `SelectByDirection(direction, n2h_value, h2n_value)` selects a value based
  on the packet's direction.

**Add-on-miss (data-plane table insertion):**

- `add_entry(action_name, action_params, expire_time_profile_id) → bool`
  inserts a table entry on a miss.

**Stateful:** Same as PSA — `Register`, `Counter`, `Meter` (always GREEN),
`Hash`, `InternetChecksum`, `Digest` (no-op), `Random`.

## Limitations

These limitations apply to all architectures:

- **Meters always return GREEN** because runtime meter rate behavior is outside
  the simulator model.
- **Counters are fire-and-forget** and can't be read from P4 logic (only via
  P4Runtime Read from the control plane).
- **Digest is a no-op** because asynchronous controller queues are outside the
  simulator model.

See [LIMITATIONS.md]({{ config.repo_url }}/blob/main/docs/LIMITATIONS.md)
for the full list.
