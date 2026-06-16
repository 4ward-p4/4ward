# Error Messages

4ward produces actionable, specific error messages for every P4Runtime
operation. Every error tells you what went wrong, shows the value you sent,
and lists the valid options.

## Examples

**Unknown table ID:**
```
NOT_FOUND: unknown table ID 99999 (valid tables: 'port_table' (44171635))
```

**Wrong action for a table:**
```
INVALID_ARGUMENT: unknown action ID 99999 in table 'port_table'
  (valid actions: 'MyIngress.forward' (29683729),
   'MyIngress.drop' (25652968), 'NoAction' (21257015))
```

**Match field type mismatch:**
```
INVALID_ARGUMENT: match field 'hdr.ethernet.etherType' expects EXACT but got TERNARY
```

**Table full:**
```
RESOURCE_EXHAUSTED: table 'port_table' is full (1024 entries)
```

**@refers_to violation:**
```
INVALID_ARGUMENT: @refers_to violation: no entry in 'target_table'
  with 'id' = 0xaabbccddeeff
```

**Constraint violation** (with source location from `@entry_restriction`):
```
INVALID_ARGUMENT: All entries must satisfy:

In @entry_restriction of table 'MyIngress.acl'; at offset line 2, columns 9 to 58:
  |
2 |         ipv4_dst::mask != 0 -> ether_type::value == 0x0800;
  |         ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

But your entry does not.
```

**Simulator error** (with P4 source location):
```
INTERNAL: undefined variable: x (at my_program.p4:42:3)
```

## Design principles

- **What, not just that.** Every error includes the entity name, the value
  that caused the problem, and the constraint that was violated.
- **How to fix it.** "Unknown" errors list valid options with their numeric
  IDs. Enum errors list valid values.
- **Where in the P4 source.** Simulator errors include the P4 file, line, and
  column from the IR source info.
- **Proper gRPC status codes.** `NOT_FOUND` for missing entities,
  `INVALID_ARGUMENT` for malformed requests, `RESOURCE_EXHAUSTED` for capacity
  limits, `FAILED_PRECONDITION` for state requirements, `PERMISSION_DENIED` for
  arbitration, `UNIMPLEMENTED` for unsupported features.
- **Structured error details.** Batch writes include per-update status with
  `space` and `code` fields per P4Runtime spec section 12.3.
- **Golden-tested.** All 75 error paths are locked down by golden tests.
  Messages cannot silently degrade. Update with:
  ```
  bazel test //grpc:GoldenErrorTest --test_env=UPDATE_GOLDEN=1
  ```

## All error messages

See [`grpc/golden_errors/`]({{ config.repo_url }}/tree/main/grpc/golden_errors)
for the complete list of golden-tested error messages.
