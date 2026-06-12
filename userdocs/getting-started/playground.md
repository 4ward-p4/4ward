---
description: "Get started with the 4ward web playground: edit P4, install table entries, inject packets, and explore trace trees in your browser."
---

# Getting Started: Web Playground

The playground is the fastest way to try 4ward — edit, compile, and trace P4
programs in the browser.

![4ward Web Playground](https://raw.githubusercontent.com/smolkaj/4ward/main/docs/playground.gif)

## Start the server

Clone and build if you haven't already:

```sh
git clone https://github.com/smolkaj/4ward.git && cd 4ward
```

Then start the playground:

```sh
bazel run //web:playground
```

The server prints:

```
4ward Playground
  Web UI:   http://localhost:8080
  gRPC:     localhost:9559
```

Your browser opens automatically. If it doesn't, navigate to
`http://localhost:8080`.

## Load an example

Click the **example selector** dropdown (top of the editor) and choose
**basic_table**. You'll see a short v1model program with one table:

```p4
table port_table {
    key = { hdr.ethernet.etherType : exact; }
    actions = { forward; drop; NoAction; }
    default_action = drop();
}
```

Click **Compile & Load**. The status bar turns green and shows the pipeline
summary (tables, actions). The **control-flow graph** appears at the bottom,
showing the ingress pipeline's table and control flow.

## Install a table entry

Switch to the **Tables** tab on the right panel.

1. Select table **port_table**.
2. Set the match field `hdr.ethernet.etherType` to `0x0800` (IPv4).
3. Select action **forward**, set port to `1`.
4. Click **Add Entry**.

The entry appears in the installed entries list below.

## Send a packet

Switch to the **Packets** tab.

1. Click the **IPv4** preset — it fills the payload with a valid Ethernet +
   IPv4 frame.
2. Set **Ingress port** to `0`.
3. Click **Send Packet**.

The output panel shows one packet exiting on port 1 with decoded header
fields.

## Read the trace

Switch to the **Trace** tab to see every decision the simulator made:

1. **Parser** — `start → accept` after extracting the Ethernet header.
2. **Ingress** — `port_table: hit → forward` with the matched entry details.
3. **Action** — `forward(port=1)` executed.
4. **Deparser** — Ethernet header emitted (14 bytes).
5. **Output** — packet exits on port 1.

Use the **arrow keys** (← →) to step through events one at a time. Each step
highlights the corresponding P4 source line in the editor and the active node
in the control-flow graph. Press **Escape** to reset.

## Try a miss

Send an ARP packet (click the **ARP** preset) without installing a matching
entry. The trace shows `port_table: miss → drop` and the packet is dropped.

## What's next

- **Trace trees** — understand the [event types and branching
  semantics](../concepts/traces.md) behind the trace visualization.
- **CLI** — run the same tests [from the terminal](cli.md) with STF files.
- **Reference** — full [playground feature reference](../reference/playground.md).
