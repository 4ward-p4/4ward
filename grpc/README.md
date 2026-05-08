# P4Runtime

A [P4Runtime](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html)
gRPC frontend to the simulator. Handles write validation, type
translation (`@p4runtime_translation`), packet I/O, and role-based
access control, then forwards dataplane state changes to the simulator
— which remains the single source of truth for table entries,
counters, and registers. Also exposes a Dataplane service for direct
packet injection, making 4ward a drop-in replacement for BMv2 (the
Behavioral Model reference simulator) in tools like DVaaS (Dataplane
Validation-as-a-Service). See [`docs/ENTRY_POINTS.md`](../docs/ENTRY_POINTS.md#p4runtime-server).
