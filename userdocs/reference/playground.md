---
description: "4ward web playground reference: keyboard shortcuts, URL parameters, and editor features."
---

# Web Playground Reference

## Starting the server

```sh
bazel run //web:playground
```

| Flag | Default | Description |
|------|---------|-------------|
| `--http-port` | 8080 | HTTP server port |
| `--grpc-port` | 9559 | gRPC server port (P4Runtime + Dataplane) |
| `--drop-port` | `2^N - 1` | Override drop port (e.g., 511 for 9-bit ports) |
| `--cpu-port` | `2^N - 2` | Override CPU port (auto-enabled when `@controller_header` is present) |
| `--static-dir` | - | Serve frontend from a local directory (for development) |

The browser opens automatically. A gRPC server runs alongside the HTTP
server, so you can use gRPC clients (grpcurl, P4Runtime libraries) against
the same pipeline.

## Layout

The **left panel** is a P4 source editor (Monaco) with syntax highlighting.
The **right panel** has three tabs — Tables, Packets, and Trace. The
**control-flow graph** appears at the bottom after compilation.

## Editor

The **example selector** dropdown at the top loads built-in examples:
`passthrough`, `basic_table`, `mirror`, `sai_middleblock`. Click
**Compile & Load** to compile the P4 source and load it into the simulator.
The status bar shows the pipeline summary or any compilation errors.

## Tables tab

Manage control-plane state for the loaded pipeline.

**Add entry:**

1. Select a table from the dropdown.
2. Fill in match fields (supports exact, LPM, ternary, optional, and range).
3. Select an action and fill in parameters.
4. Set priority (for ternary/range tables).
5. Click **Add Entry**.

**Clone sessions:**

Enter a session ID and egress port, then click **Add Clone Session**.

**Installed entries:** lists all current table entries (including `const
entries` from the P4 source).

## Packets tab

Use the **presets** (IPv4, IPv6, ARP, Raw) to fill the payload textarea with
a valid frame for that protocol. Set the **ingress port** and hex payload,
then click **Send Packet**.

The output section shows each output packet with its egress port, byte count,
and decoded header fields (Ethernet MAC addresses, IPv4 addresses, protocol
numbers).

## Trace tab

The default **visual view** shows an interactive trace tree with events
grouped by pipeline stage. Table hits include matched entry details, and
branching trees display their subtrees.

Use the **← →** arrow keys to step through events. Each step highlights:

- The current event in the trace panel.
- The corresponding P4 source line in the editor.
- The active node in the control-flow graph.

Press **Escape** to reset playback.

The **JSON view** and **Proto view** tabs show raw serializations of the trace
tree proto, each with a `proto-file` / `proto-message` header.

## Control-flow graph

The control-flow graph is a visual DAG of the pipeline's parser and control
blocks, with one tab per pipeline stage (parser, verify_checksum, ingress,
egress, etc.). Table nodes show table names, condition nodes show if/else
branching, and edge labels show branch conditions. During trace playback, the
active node highlights automatically. A fullscreen toggle is in the corner.

## REST API

The playground exposes a REST API used by the frontend. These are also
available to external tools:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/compile-and-load` | POST | Compile P4 source and load pipeline |
| `/api/write` | POST | Install table entries (P4Runtime WriteRequest JSON) |
| `/api/read?table_id=N` | GET | Read table entries (0 = all tables) |
| `/api/packet` | POST | Inject packet (`ingress_port` + `payload_hex`) |
| `/api/pipeline` | GET | Check if a pipeline is loaded |

## Limitations

- Table entries can't be modified in place — delete and re-add instead.
- Default actions can't be changed through the UI.
- All browser tabs share a single simulator instance.
- Restarting the server clears all state.
- Sending another packet overwrites the previous result (no history).
- The Monaco editor requires an internet connection (loaded from CDN).

See [LIMITATIONS.md](https://github.com/smolkaj/4ward/blob/main/docs/LIMITATIONS.md)
for the full list.
