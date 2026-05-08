# gRPC services

A gRPC interface to 4ward.

- **Language-agnostic.** Any gRPC client — C++, Python, Go — can load
  pipelines, program tables, and inject packets. No Kotlin required.
- **Ecosystem-compatible.** The P4Runtime service speaks the standard
  protocol, so 4ward works with existing P4Runtime infrastructure out
  of the box.

4ward implements two gRPC services — one for the control plane, one for
the data plane:

```
control plane:  Controller ──P4Runtime──▶ P4RuntimeService ──▶ Simulator
  data plane:       packet ──Dataplane──▶ DataplaneService ──╯
```

- **P4RuntimeService** — the control plane. Implements the standard
  [P4Runtime](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html)
  API: pipeline loading, table management, arbitration, PacketIO.
- **DataplaneService** — the data plane. Inject packets, get output
  packets and trace trees back.

All P4 logic lives in the simulator. These services are just a gRPC interface to it.

See [`docs/ENTRY_POINTS.md`](../docs/ENTRY_POINTS.md#p4runtime-server).
