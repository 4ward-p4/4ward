---
description: "Use 4ward from C++ — Bazel setup and example usage."
---

# C++ API

**Treat 4ward like a native C++ library.** Depend on
`//fourward_cc:dataplane_client` for the ergonomic wrapper, or
`//fourward_cc:fourward_server` for raw stub access. Your project sees
a C++ API and a Bazel target; the server's implementation language never
enters the picture.

## Bazel dependency

```starlark
cc_test(
    name = "my_test",
    srcs = ["my_test.cc"],
    deps = [
        "@fourward//fourward_cc:dataplane_client",
        "@fourward//fourward_cc:fourward_server",
        # ... your other deps ...
    ],
)
```


## DataplaneClient

The recommended entry point for packet injection and result observation:

```cpp
#include "fourward_cc/dataplane_client.h"

absl::Status RunAgainstFourward() {
  ASSIGN_OR_RETURN(fourward::FourwardServer server,
                   fourward::FourwardServer::Start());

  // Use default 10s timeout for everything:
  fourward::DataplaneClient dataplane(server);

  // Or configure a longer default for slow networks:
  fourward::DataplaneClient dataplane(server, absl::Seconds(30));

  // Inject a single packet — returns trace + outputs inline.
  ASSIGN_OR_RETURN(auto response,
                   dataplane.InjectPacket({
                       .ingress_port = fourward::DataplanePort{.port = 0},
                       .payload = raw_ethernet_bytes,
                   }));

  // Override timeout per-call when needed:
  ASSIGN_OR_RETURN(auto fast,
                   dataplane.InjectPacket(args, absl::Seconds(1)));

  // Subscribe to results from all injection sources.
  ASSIGN_OR_RETURN(fourward::ResultStream stream,
                   dataplane.SubscribeResults());
  ASSIGN_OR_RETURN(auto result, stream.Next());

  return absl::OkStatus();
}
```

Method names mirror `dataplane.proto` one-to-one: `InjectPacket`,
`InjectPackets`, `SubscribeResults`. `RegisterPrePacketHook` is
intentionally not wrapped — use the raw stub for advanced use cases.

## FourwardServer (low-level)

For direct stub access or when you need the P4Runtime service:

```cpp
#include "fourward_cc/fourward_server.h"

absl::Status RunAgainstFourward() {
  ASSIGN_OR_RETURN(fourward::FourwardServer server,
                   fourward::FourwardServer::Start());

  auto p4rt = server.NewP4RuntimeStub();
  auto dataplane = server.NewDataplaneStub();
  // ... drive the server via gRPC ...

  return absl::OkStatus();
  // `server` destructor kills the subprocess.
}
```

Options cover `device_id`, the listening `port` (unset by default — the
kernel picks an ephemeral port), `drop_port`, `cpu_port`, and
`startup_timeout`. See [`fourward_server.h`](https://github.com/smolkaj/4ward/blob/main/fourward_cc/fourward_server.h)
for the full API.

## Startup contract

The wrapper spawns the server with `--port-file=PATH`. The server
atomically writes its listening port to `PATH` once it is accepting RPCs;
file existence is the readiness signal, file contents are the port. The
contract is stable and suitable for hand-rolled wrappers in other
languages.

| Flag | Semantics |
|------|-----------|
| `--port=N` | Pin the listening port. `--port=0` (the default) lets the kernel pick an ephemeral port — recommended for parallel test shards. |
| `--port-file=PATH` | After binding, the server atomically writes the bound port as ASCII to `PATH` (tempfile + rename, so a concurrent reader never sees a partial value). |

## Related

- [`fourward_cc/`](https://github.com/smolkaj/4ward/tree/main/fourward_cc)
  — source of the wrapper.
- [gRPC API reference](grpc.md) — server flags, RPC surface, and proto
  definitions.
