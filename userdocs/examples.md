---
description: "Ready-to-run P4 example programs for 4ward: passthrough, table-based forwarding, and the interactive tutorial."
---

# Examples

4ward ships with example P4 programs that demonstrate core concepts. Each one
works with the [CLI](getting-started/cli.md) and the
[web playground](getting-started/playground.md).

For a guided walkthrough using these examples, see the
[tutorial]({{ repo }}/blob/main/examples/tutorial.t).

## passthrough

**Source:** [`examples/passthrough.p4`]({{ repo }}/blob/main/examples/passthrough.p4)

The simplest possible v1model program. Parses an Ethernet header, hardcodes
every packet to port 1, emits it unchanged. No tables, no actions.

```p4
control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t standard_metadata) {
    apply {
        standard_metadata.egress_spec = 1;
    }
}
```

**Try it:**

```sh
4ward run examples/passthrough.p4 - <<'EOF'
packet 0 FFFFFFFFFFFF 000000000001 0800
expect 1 FFFFFFFFFFFF 000000000001 0800
EOF
```

**What to look for in the trace:** a linear trace with no tables — just
parser, ingress (no events), and output on port 1.

## basic_table

**Source:** [`examples/basic_table.p4`]({{ repo }}/blob/main/examples/basic_table.p4)

An exact-match table keyed on `etherType`. Matching packets are forwarded to
a specified port; non-matching packets are dropped.

```p4
table port_table {
    key = { hdr.ethernet.etherType : exact; }
    actions = { forward; drop; NoAction; }
    default_action = drop();
}
```

**Try it:**

```sh
4ward run examples/basic_table.p4 - <<'EOF'
add port_table hdr.ethernet.etherType:0x0800 forward(1)

packet 0 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
expect 1 FFFFFFFFFFFF 000000000001 0800 DEADBEEF

packet 0 FFFFFFFFFFFF 000000000001 0806 DEADBEEF
EOF
```

**What to look for in the trace:** the first packet shows
`table port_table: hit → forward`; the second shows
`table port_table: miss → drop` followed by `mark_to_drop()`.

## mirror

**Available in:** web playground (select **mirror** from the example dropdown)

An IPv4 router with port mirroring. Parses Ethernet + IPv4, routes via LPM
on destination IP, and clones every forwarded packet to a mirror session.

```p4
apply {
    if (hdr.ipv4.isValid()) {
        ipv4_lpm.apply();
        clone(CloneType.I2E, 32w100);  // mirror to session 100
    }
}
```

The egress pipeline uses `instance_type` to distinguish originals from clones
and tags cloned packets with a distinctive source MAC.

**What to look for in the trace:** the trace tree **forks** at the clone
point — one branch for the original packet (exits on the routed port) and
one for the clone (exits on the mirror port with rewritten source MAC).

**Concepts demonstrated:**

- Parser state transitions (`select` on `etherType`)
- LPM table matching
- Packet cloning and trace tree forking
- Egress branching on `instance_type`

## sai_middleblock

**Available in:** web playground (select **sai_middleblock** from the example
dropdown)

A simplified version of the SAI P4 middleblock — Google's production P4
pipeline for network switches. Real table names, real control flow, with
`@p4runtime_translation` on all ID types.

This example demonstrates:

- Multiple tables applied in sequence (VRF lookup, L3 routing, next-hop
  resolution, neighbor rewrite)
- String-typed port names (`"Ethernet0"`, `"Ethernet1"`)
- [Type translation](concepts/type-translation.md) across the full stack
  (note: `@p4runtime_translation` on port types is aspirational for SAI P4;
  the middleblock example uses it on non-port ID types like VRF and nexthop)

This is an advanced example. Start with `basic_table` to understand the
fundamentals, then explore `sai_middleblock` to see how a production pipeline
works.
