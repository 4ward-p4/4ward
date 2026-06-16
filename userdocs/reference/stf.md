---
description: "STF test format reference: packet injection, table entry installation, expected output matching, and all supported commands."
---

# STF Test Format

STF (Simple Test Framework) is a plain-text format for defining packet tests,
originally from the [p4c](https://github.com/p4lang/p4c) compiler test suite
and widely used across the P4 ecosystem (p4c,
[BMv2](https://github.com/p4lang/behavioral-model),
[p4testgen](https://github.com/p4lang/p4c/tree/main/backends/p4tools/modules/testgen)).
4ward uses the same format for compatibility. See the
[`e2e_tests/`]({{ repo }}/tree/main/e2e_tests)
directory for real-world examples.

Lines starting with `#` are comments, and tokens are whitespace-separated.

## Packets

**Send a packet:**

```
packet <port> <hex_bytes>
```

Hex bytes can include spaces or not — `FFFFFFFFFFFF 000000000001 0800` and
`FFFFFFFFFFFF0000000000010800` are equivalent.

**Expect an output packet:**

```
expect <port> <hex_bytes>
```

Within the same port, outputs are matched in FIFO order. Cross-port ordering
is not checked.

Append `$` to assert an exact length — without it, trailing bytes in the
actual output are ignored:

```
expect 1 FFFFFFFFFFFF 000000000001 0800$   # exact 14 bytes
expect 1 FFFFFFFFFFFF 000000000001 0800    # prefix match
```

A test with no `expect` lines is send-only and always passes. Once you add at
least one `expect`, unexpected output packets cause a failure.

## Table entries

**Add an entry:**

```
add <table_name> [priority] <match_fields>... <action(params)>
```

### Match types

**Exact:**

```
add port_table hdr.ethernet.etherType:0x0800 forward(1)
```

**LPM (longest prefix match):**

```
add ipv4_lpm hdr.ipv4.dstAddr:10.0.0.0/8 forward(1)
```

**Ternary (value & mask):**

```
add acl 10 hdr.ipv4.protocol:0x06&&&0xff forward(1)
```

A priority (here `10`) is required for ternary tables.

**Wildcard (don't-care nibbles/bits):**

```
add acl hdr.ipv4.protocol:0x0*   forward(1)     # hex nibble wildcard
add acl hdr.ipv4.protocol:0b1010**** forward(1)  # binary bit wildcard
```

### Value formats

Match values and action parameters accept decimal (`42`), hex (`0x0800`),
binary (`0b11010`), and IPv4 (`10.0.1.0`) formats.

### Action parameters

Parameters can be positional or named:

```
add table field:value action(0x1234 42)
add table field:value action(param1=0x1234 param2=42)
```

### Default action

```
setdefault <table_name> <action(params)>
```

## Action profiles

```
member <profile_name> <member_id> <action_name> [param=value ...]
group <profile_name> <group_id> <member_id> [member_id ...]
```

To reference a group from a table entry:

```
add my_table field:value group=10
```

## Clone sessions

```
mirroring_add <session_id> <egress_port>
```

This sets up a clone session that copies packets to the specified port, for
use with `clone()` / `clone3()` in P4. For PSA multicast-based cloning:

```
mirroring_add_mc <session_id> <multicast_group_id>
```

## Multicast groups

```
mc_mgrp_create <group_id>
mc_node_create <rid> <port> [port ...]
mc_node_associate <group_id> <node_handle>
```

Example (multicast group 1 with replicas on ports 1, 2, 3):

```
mc_mgrp_create 1
mc_node_create 0 1 2 3
mc_node_associate 1 0
```

## Complete example

```stf
# Install a forwarding entry and a clone session.
add port_table hdr.ethernet.etherType:0x0800 forward(1)
mirroring_add 100 2

# Send an IPv4 packet on port 0.
packet 0 FFFFFFFFFFFF 000000000001 0800 DEADBEEF

# Expect original on port 1 and clone on port 2.
expect 1 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
expect 2 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
```
