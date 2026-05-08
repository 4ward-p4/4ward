# gRPC services

P4Runtime and Dataplane gRPC services backed by the 4ward simulator.

The [P4Runtime](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html)
service handles write validation, type translation (`@p4runtime_translation`),
packet I/O, and role-based access control, then forwards dataplane state
changes to the simulator — which remains the single source of truth for
table entries, counters, and registers.

The Dataplane service provides direct packet injection, making 4ward a
drop-in replacement for BMv2 in tools like DVaaS.

See [`docs/ENTRY_POINTS.md`](../docs/ENTRY_POINTS.md#p4runtime-server).
