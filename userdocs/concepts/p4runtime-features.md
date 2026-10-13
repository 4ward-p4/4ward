---
description: "P4Runtime annotations supported by 4ward: role-based access control, type translation, entry constraints, and referential integrity."
---

# P4Runtime Features

4ward supports several P4Runtime annotations that control how the server
handles pipelines, table entries, and multi-controller setups. These
annotations are declared in the P4 source and flow through p4info into the
P4Runtime server automatically.

## `@p4runtime_role` — role-based access control

Tables can declare a role via `@p4runtime_role("role_name")`. This scopes
entity access per controller role, following the
[P4Runtime spec
(section 15)](https://p4lang.github.io/p4runtime/spec/v1.6.0/P4Runtime-Spec.html#sec-role).

```p4
@p4runtime_role("sdn_controller")
table ipv4_table {
    // ...
}
```

### How it works

- **Per-role arbitration.** Each role has its own independent primary
  election. Multiple controllers with different roles can coexist — each
  role has its own primary, determined by `election_id`.
- **Entity access control.** A named-role controller can only read and
  write entities whose `@p4runtime_role` matches its role. Direct
  counters, direct meters, and action profiles inherit the role of their
  parent table.
- **Default role.** Entities without `@p4runtime_role` belong to the
  default role (empty string). Controllers that arbitrate with an empty
  `Role.name` have full access to all entities.
- **`Role.config` is not supported.** Role scoping is derived entirely
  from p4info annotations.

### Connecting as a named-role controller

Set `Role.name` in your `MasterArbitrationUpdate`:

```protobuf
StreamMessageRequest {
  arbitration {
    device_id: 1
    role { name: "sdn_controller" }
    election_id { low: 1 }
  }
}
```

If a higher `election_id` connects for the same role, the previous primary
receives a demotion notification. When the primary disconnects, the next
highest automatically promotes.

## `@p4runtime_translation` — type translation

Translated types decouple controller-facing values (like port name strings)
from data-plane values (like 9-bit integers). 4ward ships a built-in
translation engine that handles this automatically, with auto-allocate,
explicit, and hybrid mapping modes.

See [Type Translation](type-translation.md) for full details.

## `@entry_restriction` / `@action_restriction` — constraint validation

These annotations (from the
[p4-constraints](https://github.com/p4lang/p4-constraints) project) let
tables declare logical constraints on which entries are valid:

```p4
@entry_restriction("
    ipv4_dst::mask != 0 -> ether_type::value == 0x0800;
")
table acl {
    // ...
}
```

When constraint validation is enabled, 4ward rejects `Write` requests that
violate these constraints with a clear error message showing the violated
rule and the offending entry values.

## `@refers_to` — referential integrity

The `@refers_to(table, field)` annotation on match fields and action
parameters declares foreign-key relationships between tables:

```p4
table nexthop_table {
    key = { nexthop_id : exact; }
    // ...
}

table ipv4_table {
    // ...
    actions = {
        @refers_to(nexthop_table, nexthop_id)
        set_nexthop(bit<16> nexthop_id);
    }
}
```

4ward validates these references at write time. An `INSERT` or `MODIFY`
that references a nonexistent entry is rejected:

```
INVALID_ARGUMENT: @refers_to violation: no entry in 'nexthop_table'
with 'nexthop_id' = 0x0042
```

References to `builtin::multicast_group_table` are checked against
multicast group entries in the packet replication engine.
