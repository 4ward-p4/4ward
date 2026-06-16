---
description: "Get started with the 4ward CLI: compile P4 programs, run STF tests, and inspect trace trees from the command line."
---

# Getting Started: CLI

The CLI compiles P4 programs, runs STF tests, and prints trace trees — all
from the terminal.

## Setup

```sh
git clone {{ repo }}.git && cd 4ward
```

An alias makes the examples below easier to type:

```sh
alias 4ward='bazel run //cli:4ward --'
```

## Hello world

The simplest test: send a packet through `passthrough.p4` (hardcodes every
packet to port 1) and check that it exits on port 1.

```sh
4ward run examples/passthrough.p4 - <<'EOF'
packet 0 FFFFFFFFFFFF 000000000001 0800
expect 1 FFFFFFFFFFFF 000000000001 0800
EOF
```

Output:

```
packet received: port 0, 14 bytes
  parse: start -> accept
  output port 1, 14 bytes
PASS
```

Every event the simulator processed is right there — parser state transition,
output port, byte count. `PASS` means the output matched the `expect` line.

## Match-action tables

`basic_table.p4` has a table that matches on `etherType` and forwards to a
port. Install a table entry, send a matching packet, then a non-matching one:

```sh
4ward run examples/basic_table.p4 - <<'EOF'
add port_table hdr.ethernet.etherType:0x0800 forward(1)

packet 0 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
expect 1 FFFFFFFFFFFF 000000000001 0800 DEADBEEF

packet 0 FFFFFFFFFFFF 000000000001 0806 DEADBEEF
EOF
```

Output:

```
packet received: port 0, 18 bytes
  parse: start -> accept
  table port_table: hit -> forward
  action forward(port=1)
  output port 1, 18 bytes
packet received: port 0, 18 bytes
  parse: start -> accept
  table port_table: miss -> drop
  action drop
  mark_to_drop()
  drop (reason: mark_to_drop)
PASS
```

The first packet hits the table and forwards. The second misses — the default
action is `drop()`.

## Machine-readable output

Use `--format=textproto` or `--format=json` for programmatic consumption:

```sh
4ward run --format=json examples/passthrough.p4 - <<'EOF'
packet 0 DEADBEEF
EOF
```

Both formats include a `proto-file` / `proto-message` header identifying the
schema. See [Trace Trees: Output formats](../concepts/traces.md#output-formats)
for details.

## Three subcommands

`4ward run` compiles and simulates in one step. For iterating on larger
programs, split the work:

```sh
# Compile once
4ward compile examples/basic_table.p4 -o pipeline.txtpb

# Run multiple tests against the same pipeline
4ward sim pipeline.txtpb test1.stf
4ward sim pipeline.txtpb test2.stf
```

## What's next

- **Tutorial** — the full [hands-on walkthrough]({{ repo }}/blob/main/examples/tutorial.t),
  CI-verified so it's always up to date.
- **STF format** — full syntax for [table entries, match types, and
  clone/multicast setup](../reference/stf.md).
- **Trace trees** — understand the [event types and fork
  semantics](../concepts/traces.md) behind the trace output.
- **Reference** — complete [CLI reference](../reference/cli.md) with all flags.
