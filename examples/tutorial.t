4ward tutorial
==============

4ward is a glass-box P4 simulator. You give it a P4 program and a
test, and it shows you exactly what happens to each packet -- which
parser states it visits, which tables it hits or misses, which actions
fire, and where the packet ends up. This tutorial walks through
everything you need to know.

To try it yourself, clone the repo and set up a shell alias:

$ git clone https://github.com/4ward-p4/4ward.git && cd 4ward
$ alias 4ward='bazel run //cli:4ward --'

With that alias, every command below will work as shown. The example
programs live in the examples/ directory.


Part 1: Hello world
--------------------

passthrough.p4 is the simplest possible P4 program. It parses an
Ethernet header, hardcodes the output port to 1, and emits the packet
unchanged. No tables, no conditionals -- just a straight line through
the pipeline.

  $ cp "$PASSTHROUGH_P4" passthrough.p4

Here's the interesting part -- the ingress apply block sets
egress_spec to 1, meaning every packet exits on port 1:

  $ grep -B 1 -A 1 egress_spec passthrough.p4
      apply {
          standard_metadata.egress_spec = 1;
      }

Now let's run it. The test sends one Ethernet frame on port 0 and
expects it back on port 1 with the same bytes. The '-' tells 4ward
to read the test from stdin:

  $ 4ward run passthrough.p4 - << 'EOF'
  > # Send one Ethernet frame on port 0, expect it on port 1.
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 1 FFFFFFFFFFFF 000000000001 0800
  > EOF
  packet received: port 0, 14 bytes
    parse: start -> accept
    standard_metadata.egress_spec = 1
    output port 1, 14 bytes
  PASS

Each packet trace starts with the port and size of the incoming packet. Then the
events: the parser walked start -> accept (extracting the Ethernet
header), ingress sets egress_spec to 1, and the packet exits port 1, all 14
bytes intact. PASS means the actual output matched the expected output.

This is what glass-box means. Instead of a binary pass/fail, you see
every decision the simulator made.


Part 2: Match-action tables
----------------------------

passthrough.p4 hardcodes the output port. Real P4 programs use tables
to make forwarding decisions at runtime. basic_table.p4 defines a
table keyed on the Ethernet type field:

  $ cp "$BASIC_TABLE_P4" basic_table.p4

  $ grep -A 4 'table port_table' basic_table.p4
      table port_table {
          key = { hdr.ethernet.etherType : exact; }
          actions = { forward; drop; NoAction; }
          default_action = drop();
      }

If the etherType matches an installed entry, forward to the specified
port. Otherwise, the default action drops the packet.

The test installs one entry (IPv4 -> port 1) and sends two packets:
an IPv4 frame that matches, and an ARP frame that doesn't.

  $ 4ward run basic_table.p4 - << 'EOF'
  > add port_table hdr.ethernet.etherType:0x0800 forward(1)
  > packet 0 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
  > expect 1 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
  > packet 0 FFFFFFFFFFFF 000000000001 0806 DEADBEEF
  > EOF
  packet received: port 0, 18 bytes
    parse: start -> accept
    table port_table: hit -> forward
    action forward(port=1)
    standard_metadata.egress_spec = 1
    output port 1, 18 bytes
  packet received: port 0, 18 bytes
    parse: start -> accept
    table port_table: miss -> drop
    action drop
    mark_to_drop()
    drop (reason: mark_to_drop)
  PASS

Now the trace tells a much richer story. The first packet (etherType
0x0800, IPv4) hits the table, runs the 'forward' action, and exits
on port 1. The second packet (etherType 0x0806, ARP) misses the
table, runs the default 'drop' action, and is discarded.

You can see exactly why one packet was forwarded and the other was
dropped. No guessing, no printf debugging -- just read the trace.


Part 3: When tests fail
-------------------------

What happens when the actual output doesn't match? passthrough.p4
always outputs on port 1. Let's write a test that expects port 9:

  $ 4ward run passthrough.p4 - 2>&1 << 'EOF'
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 9 FFFFFFFFFFFF 000000000001 0800
  > EOF
  packet received: port 0, 14 bytes
    parse: start -> accept
    standard_metadata.egress_spec = 1
    output port 1, 14 bytes
  FAIL
    expected packet on port 9 but got none
    unexpected packet on port 1: ffffffffffff0000000000010800
  [1]

The trace shows exactly what happened -- the packet exited port 1,
not port 9. The FAIL message pinpoints the mismatch, and exit code 1
lets scripts detect test failures.


Part 4: Under the hood
------------------------

The 'run' subcommand compiles and simulates in one shot. You can
split the steps to inspect the intermediate representation.

Step 1: compile P4 source to a pipeline config.

  $ 4ward compile -o pipeline.txtpb passthrough.p4

The compiled output is a text-format protobuf containing the full
program and its P4Info metadata. Here's the top:

  $ head -20 pipeline.txtpb
  # proto-file: @fourward//simulator/ir.proto
  # proto-message: fourward.PipelineConfig
  
  p4info {
    pkg_info {
      arch: "v1model"
    }
  }
  device {
    behavioral {
      architecture {
        name: "v1model"
        stages {
          name: "parser"
          kind: PARSER
          block_name: "MyParser"
        }
        stages {
          name: "verify_checksum"
          kind: CONTROL

You can see the v1model pipeline stages (parser, verify_checksum,
ingress, egress, ...) and the block names from the P4 source. The
full file also contains the parser states, control flow, expressions,
table definitions -- everything the simulator needs.

Step 2: simulate a test against the compiled pipeline.

  $ 4ward sim pipeline.txtpb - << 'EOF'
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 1 FFFFFFFFFFFF 000000000001 0800
  > EOF
  packet received: port 0, 14 bytes
    parse: start -> accept
    standard_metadata.egress_spec = 1
    output port 1, 14 bytes
  PASS

Same trace, same verdict. This workflow is useful when you want to
compile once and run many tests, or when you want to inspect or diff
the compiled proto between program versions.


Part 5: Machine-readable output
---------------------------------

The human-readable trace is compact but lossy -- it omits source
locations and raw byte payloads. The --format=textproto flag outputs
the full trace tree as a protocol buffer:

  $ 4ward run --format=textproto passthrough.p4 - << 'EOF' | grep -A 10 parser_transition
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 1 FFFFFFFFFFFF 000000000001 0800
  > EOF
    parser_transition {
      parser_name: "MyParser"
      from_state: "start"
      to_state: "accept"
    }
    source_info {
      file: "passthrough.p4"
      line: 25
      column: 10
      source_fragment: "start"
    }

The full textproto includes every pipeline stage and the raw payload
bytes -- a structured format you can parse, diff, or feed into other
tools. Here we grep for the interesting part: the source location
(passthrough.p4:25) that the human-readable output omits.


Part 6: Error handling
-----------------------

4ward gives clear errors, not stacktraces.

  $ 4ward sim missing.txtpb missing.stf 2>&1
  error: missing.txtpb (No such file or directory)
  [2]

  $ 4ward bogus 2>&1 | head -1
  error: unknown command 'bogus'

Every subcommand has --help:

  $ 4ward compile --help | head -1
  Usage: 4ward compile [options] <program.p4>

  $ 4ward sim --help | head -1
  Usage: 4ward sim [--format=human|textproto|json] [--drop-port=N] <pipeline.txtpb> <test.stf>

  $ 4ward run --help | head -1
  Usage: 4ward run [--format=human|textproto|json] [--drop-port=N] <program.p4> <test.stf>
