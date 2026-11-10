package fourward.simulator

import com.google.protobuf.ByteString
import fourward.e2e.compileInlineP4
import fourward.stf.StfFile
import fourward.stf.installStfEntries
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.p4.v1.CloneSessionEntry
import org.p4.v1.Entity
import org.p4.v1.PacketReplicationEngineEntry
import org.p4.v1.Replica
import org.p4.v1.Update

/**
 * v1model `standard_metadata.packet_length` semantics, matching BMv2 simple_switch.
 *
 * BMv2 keeps the packet length in a per-packet register that `setValid()` / `setInvalid()` (its
 * `add_header` / `remove_header` primitives) adjust by the header's size. Ingress sees the input
 * length; egress sees the register, reloaded at egress entry. SAI P4 relies on this to compute
 * encap lengths (b/567572012: `gre_encap.p4` after `vlan_untag` pops a VLAN tag in ingress).
 *
 * The programs below report the lengths they observe by writing them into the output Ethernet
 * header: ingress `packet_length` (if recorded) into `dst_addr`, egress `packet_length` into
 * `src_addr`. That keeps the observation itself from changing the packet length.
 */
@Suppress("MagicNumber")
class V1ModelPacketLengthTest {

  @Test
  fun `ingress sees the input length even after setValid and setInvalid`() {
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header vlan_t { bit<16> tci; bit<16> ether_type; }
        header tag_t { bit<16> marker; }
        struct headers_t { ethernet_t ethernet; vlan_t vlan; tag_t tag; }
        struct meta_t { bit<32> ingress_packet_length; }

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start {
            pkt.extract(hdr.ethernet);
            transition select(hdr.ethernet.ether_type) {
              0x8100: parse_vlan;
              default: accept;
            }
          }
          state parse_vlan { pkt.extract(hdr.vlan); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.ethernet.ether_type = hdr.vlan.ether_type;
            hdr.vlan.setInvalid();  // -4 bytes.
            hdr.vlan.setInvalid();  // Already invalid: must not subtract again.
            hdr.tag.setValid();     // +2 bytes.
            hdr.tag.setValid();     // Already valid: must not add again.
            // Read after the header changes: ingress must still see the input length.
            meta.ingress_packet_length = sm.packet_length;
            sm.egress_spec = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.ethernet.dst_addr = (bit<48>) meta.ingress_packet_length;
            hdr.ethernet.src_addr = (bit<48>) sm.packet_length;
          }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.vlan); pkt.emit(hdr.tag); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """
      )

    // ethernet (14) | vlan (4) | payload (2) = 20 bytes.
    val input = hex("FFFFFFFFFFFF 000000000001 8100 0000 0800 ABCD")
    val output = sim.processPacket(0, input).possibleOutcomes.single().single()

    assertEquals(20L, ingressPacketLength(output.payload))
    assertEquals(20L - 4 + 2, egressPacketLength(output.payload))
  }

  @Test
  fun `egress packet_length shrinks when ingress pops a VLAN tag`() {
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header vlan_t { bit<16> tci; bit<16> ether_type; }
        struct headers_t { ethernet_t ethernet; vlan_t vlan; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start {
            pkt.extract(hdr.ethernet);
            transition select(hdr.ethernet.ether_type) {
              0x8100: parse_vlan;
              default: accept;
            }
          }
          state parse_vlan { pkt.extract(hdr.vlan); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            if (hdr.vlan.isValid()) {
              hdr.ethernet.ether_type = hdr.vlan.ether_type;
              hdr.vlan.setInvalid();
            }
            sm.egress_spec = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply { hdr.ethernet.src_addr = (bit<48>) sm.packet_length; }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.vlan); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """
      )

    // Tagged: ethernet (14) | vlan (4) | payload (2) = 20 bytes, minus the 4-byte tag.
    val tagged = hex("FFFFFFFFFFFF 000000000001 8100 0000 0800 ABCD")
    val taggedOutput = sim.processPacket(0, tagged).possibleOutcomes.single().single()
    assertEquals(16L, egressPacketLength(taggedOutput.payload))

    // Untagged: ethernet (14) | payload (2) = 16 bytes, unchanged.
    val untagged = hex("FFFFFFFFFFFF 000000000001 0800 ABCD")
    val untaggedOutput = sim.processPacket(0, untagged).possibleOutcomes.single().single()
    assertEquals(16L, egressPacketLength(untaggedOutput.payload))
  }

  @Test
  fun `egress packet_length excludes a varbit header invalidated in ingress`() {
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header opt_len_t { bit<8> len; }
        header opt_t { varbit<64> data; }
        struct headers_t { ethernet_t ethernet; opt_len_t opt_len; opt_t opt; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start {
            pkt.extract(hdr.ethernet);
            pkt.extract(hdr.opt_len);
            pkt.extract(hdr.opt, (bit<32>) hdr.opt_len.len * 8);
            transition accept;
          }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.opt.setInvalid();  // -opt_len.len bytes (the varbit's runtime width).
            sm.egress_spec = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply { hdr.ethernet.src_addr = (bit<48>) sm.packet_length; }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.opt_len); pkt.emit(hdr.opt); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """
      )

    // ethernet (14) | opt_len (1) = 4 | opt (4) | payload (2) = 21 bytes, minus the 4-byte varbit.
    val input = hex("FFFFFFFFFFFF 000000000001 88B5 04 11223344 ABCD")
    val output = sim.processPacket(0, input).possibleOutcomes.single().single()

    assertEquals(17L, egressPacketLength(output.payload))
  }

  @Test
  fun `egress packet_length clamps at zero instead of crashing`() {
    // hdr.copy becomes valid by assignment, which (as in BMv2) does not add to the packet length.
    // Invalidating both headers then removes more bits than the packet has.
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header len_t { bit<32> egress_packet_length; }
        struct headers_t { ethernet_t ethernet; ethernet_t copy; len_t len; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start { pkt.extract(hdr.ethernet); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.copy = hdr.ethernet;
            hdr.ethernet.setInvalid();  // -14 bytes.
            hdr.copy.setInvalid();      // -14 bytes.
            sm.egress_spec = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.len.setValid();
            hdr.len.egress_packet_length = sm.packet_length;
          }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.copy); pkt.emit(hdr.len); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """
      )

    // ethernet (14) only: 14 - 14 - 14 < 0.
    val input = hex("FFFFFFFFFFFF 000000000001 0800")
    val output = sim.processPacket(0, input).possibleOutcomes.single().single()

    assertEquals(hex("00000000").toList(), output.payload.toByteArray().toList())
  }

  @Test
  fun `I2E clone sees the input length while the original sees ingress header changes`() {
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header vlan_t { bit<16> tci; bit<16> ether_type; }
        struct headers_t { ethernet_t ethernet; vlan_t vlan; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start {
            pkt.extract(hdr.ethernet);
            transition select(hdr.ethernet.ether_type) {
              0x8100: parse_vlan;
              default: accept;
            }
          }
          state parse_vlan { pkt.extract(hdr.vlan); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.ethernet.ether_type = hdr.vlan.ether_type;
            hdr.vlan.setInvalid();  // -4 bytes, for the original only.
            clone(CloneType.I2E, 32w100);
            sm.egress_spec = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply { hdr.ethernet.src_addr = (bit<48>) sm.packet_length; }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.vlan); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """,
        stf = "mirroring_add 100 2",
      )

    // ethernet (14) | vlan (4) | payload (2) = 20 bytes.
    val input = hex("FFFFFFFFFFFF 000000000001 8100 0000 0800 ABCD")
    val outputs = sim.processPacket(0, input).possibleOutcomes.single()

    val original = outputs.single { it.dataplaneEgressPort == 1 }
    val clone = outputs.single { it.dataplaneEgressPort == 2 }
    assertEquals(16L, egressPacketLength(original.payload))
    // Like BMv2, the I2E clone gets the pre-ingress headers and the input length.
    assertEquals(20L, egressPacketLength(clone.payload))
  }

  @Test
  fun `E2E clone sees header changes from both ingress and the original's egress`() {
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header vlan_t { bit<16> tci; bit<16> ether_type; }
        header tag_t { bit<16> marker; }
        struct headers_t { ethernet_t ethernet; vlan_t vlan; tag_t tag; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start {
            pkt.extract(hdr.ethernet);
            transition select(hdr.ethernet.ether_type) {
              0x8100: parse_vlan;
              default: accept;
            }
          }
          state parse_vlan { pkt.extract(hdr.vlan); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.ethernet.ether_type = hdr.vlan.ether_type;
            hdr.vlan.setInvalid();  // -4 bytes.
            sm.egress_spec = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.ethernet.src_addr = (bit<48>) sm.packet_length;
            if (sm.instance_type == 0) {
              hdr.tag.setValid();  // +2 bytes, seen by the E2E clone.
              clone(CloneType.E2E, 32w100);
            }
          }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.vlan); pkt.emit(hdr.tag); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """,
        stf = "mirroring_add 100 2",
      )

    // ethernet (14) | vlan (4) | payload (2) = 20 bytes.
    val input = hex("FFFFFFFFFFFF 000000000001 8100 0000 0800 ABCD")
    val outputs = sim.processPacket(0, input).possibleOutcomes.single()

    val original = outputs.single { it.dataplaneEgressPort == 1 }
    val clone = outputs.single { it.dataplaneEgressPort == 2 }
    assertEquals(20L - 4, egressPacketLength(original.payload))
    assertEquals(20L - 4 + 2, egressPacketLength(clone.payload))
  }

  @Test
  fun `truncated E2E clone applies ingress header changes before the truncation cap`() {
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header vlan_t { bit<16> tci; bit<16> ether_type; }
        struct headers_t { ethernet_t ethernet; vlan_t vlan; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start {
            pkt.extract(hdr.ethernet);
            transition select(hdr.ethernet.ether_type) {
              0x8100: parse_vlan;
              default: accept;
            }
          }
          state parse_vlan { pkt.extract(hdr.vlan); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.ethernet.ether_type = hdr.vlan.ether_type;
            hdr.vlan.setInvalid();  // -4 bytes.
            sm.egress_spec = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.ethernet.src_addr = (bit<48>) sm.packet_length;
            if (sm.instance_type == 0) {
              clone(CloneType.E2E, 32w100);
            }
          }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.vlan); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """
      )
    sim.writeEntry(cloneSession(sessionId = 100, egressPort = 2, packetLengthBytes = 64))
    sim.publishSnapshot()

    // ethernet (14) | vlan (4) | payload (82) = 100 bytes.
    val input = hex("FFFFFFFFFFFF 000000000001 8100 0000 0800") + ByteArray(82)
    val outputs = sim.processPacket(0, input).possibleOutcomes.single()

    val original = outputs.single { it.dataplaneEgressPort == 1 }
    val clone = outputs.single { it.dataplaneEgressPort == 2 }
    assertEquals(96L, egressPacketLength(original.payload))
    assertEquals(96, original.payload.size())
    // min(100 - 4, 64), not 64 - 4. The clone's wire payload matches its packet_length.
    assertEquals(64L, egressPacketLength(clone.payload))
    assertEquals(64, clone.payload.size())
  }

  @Test
  fun `truncated I2E clone caps headers added in egress and survives a cap inside the headers`() {
    // The clone session truncates to 8 bytes: less than the 14-byte Ethernet header the parser
    // already consumed. The clone must still deparse (the cap applies to its output, not its
    // input), and a header added in egress must not be emitted past the cap.
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header tag_t { bit<16> marker; }
        struct headers_t { ethernet_t ethernet; tag_t tag; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start { pkt.extract(hdr.ethernet); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            clone(CloneType.I2E, 32w100);
            sm.egress_spec = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.tag.setValid();  // +2 bytes, before the Ethernet header on the wire.
            hdr.tag.marker = 0xBEEF;
            hdr.ethernet.src_addr = (bit<48>) sm.packet_length;
          }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.tag); pkt.emit(hdr.ethernet); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """
      )
    sim.writeEntry(cloneSession(sessionId = 100, egressPort = 2, packetLengthBytes = 8))
    sim.publishSnapshot()

    // ethernet (14) | payload (2) = 16 bytes.
    val input = hex("FFFFFFFFFFFF 000000000001 0800 ABCD")
    val outputs = sim.processPacket(0, input).possibleOutcomes.single()

    // Original: tag (2) | ethernet (14) | payload (2). src_addr holds egress packet_length = 16:
    // the tag is added during egress, after packet_length was loaded.
    val original = outputs.single { it.dataplaneEgressPort == 1 }
    assertEquals(
      hex("BEEF FFFFFFFFFFFF 000000000010 0800 ABCD").toList(),
      original.payload.toByteArray().toList(),
    )
    // Clone: the same deparsed bytes cut to 8, so the egress-added tag counts against the cap.
    val clone = outputs.single { it.dataplaneEgressPort == 2 }
    assertEquals(hex("BEEF FFFFFFFFFFFF").toList(), clone.payload.toByteArray().toList())
  }

  @Test
  fun `multicast replicas all see ingress header changes`() {
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header vlan_t { bit<16> tci; bit<16> ether_type; }
        struct headers_t { ethernet_t ethernet; vlan_t vlan; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start {
            pkt.extract(hdr.ethernet);
            transition select(hdr.ethernet.ether_type) {
              0x8100: parse_vlan;
              default: accept;
            }
          }
          state parse_vlan { pkt.extract(hdr.vlan); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply {
            hdr.ethernet.ether_type = hdr.vlan.ether_type;
            hdr.vlan.setInvalid();  // -4 bytes.
            sm.mcast_grp = 1;
          }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply { hdr.ethernet.src_addr = (bit<48>) sm.packet_length; }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.vlan); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """,
        stf =
          """
          mc_mgrp_create 1
          mc_node_create 0 1 2 3
          mc_node_associate 1 0
          """,
      )

    // ethernet (14) | vlan (4) | payload (2) = 20 bytes.
    val input = hex("FFFFFFFFFFFF 000000000001 8100 0000 0800 ABCD")
    val outputs = sim.processPacket(0, input).possibleOutcomes.single()

    assertEquals(listOf(1, 2, 3), outputs.map { it.dataplaneEgressPort }.sorted())
    for (output in outputs) assertEquals(16L, egressPacketLength(output.payload))
  }

  @Test
  fun `action selector members see only their own header changes`() {
    val sim =
      loadInlineP4(
        """
        #include <core.p4>
        #include <v1model.p4>

        header ethernet_t { bit<48> dst_addr; bit<48> src_addr; bit<16> ether_type; }
        header vlan_t { bit<16> tci; bit<16> ether_type; }
        header tag_t { bit<16> marker; }
        struct headers_t { ethernet_t ethernet; vlan_t vlan; tag_t tag; }
        struct meta_t {}

        parser P(packet_in pkt, out headers_t hdr, inout meta_t meta,
                 inout standard_metadata_t sm) {
          state start {
            pkt.extract(hdr.ethernet);
            transition select(hdr.ethernet.ether_type) {
              0x8100: parse_vlan;
              default: accept;
            }
          }
          state parse_vlan { pkt.extract(hdr.vlan); transition accept; }
        }
        control VC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control Ig(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          action pop_vlan(bit<9> port) {
            hdr.ethernet.ether_type = hdr.vlan.ether_type;
            hdr.vlan.setInvalid();  // -4 bytes.
            sm.egress_spec = port;
          }
          action push_tag(bit<9> port) {
            hdr.tag.setValid();  // +2 bytes.
            sm.egress_spec = port;
          }
          action_selector(HashAlgorithm.crc16, 32w1024, 32w14) selector;
          table t {
            key = {
              hdr.ethernet.dst_addr : exact;
              hdr.ethernet.src_addr : selector;
            }
            actions = { pop_vlan; push_tag; }
            implementation = selector;
          }
          apply { t.apply(); }
        }
        control Eg(inout headers_t hdr, inout meta_t meta, inout standard_metadata_t sm) {
          apply { hdr.ethernet.src_addr = (bit<48>) sm.packet_length; }
        }
        control CC(inout headers_t hdr, inout meta_t meta) { apply {} }
        control D(packet_out pkt, in headers_t hdr) {
          apply { pkt.emit(hdr.ethernet); pkt.emit(hdr.vlan); pkt.emit(hdr.tag); }
        }
        V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
        """,
        stf =
          """
          member selector 0 pop_vlan port=0x0001
          member selector 1 push_tag port=0x0002
          group selector 1 0 1
          add t hdr.ethernet.dst_addr:0xFFFFFFFFFFFF group=1
          """,
      )

    // ethernet (14) | vlan (4) | payload (2) = 20 bytes.
    val input = hex("FFFFFFFFFFFF 000000000001 8100 0000 0800 ABCD")
    val outcomes = sim.processPacket(0, input).possibleOutcomes

    // One possible outcome per member; each must see only its own member's header change.
    val lengthByPort = mutableMapOf<Int, Long>()
    for (outcome in outcomes) {
      val output = outcome.single()
      lengthByPort[output.dataplaneEgressPort] = egressPacketLength(output.payload)
    }
    assertEquals(mapOf(1 to 20L - 4, 2 to 20L + 2), lengthByPort)
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Compiles [p4] and loads it into a fresh [Simulator] with the STF entries in [stf]. */
  private fun loadInlineP4(p4: String, stf: String = ""): Simulator {
    val config = compileInlineP4(p4)
    val sim = Simulator()
    sim.loadPipeline(config)
    installStfEntries(sim, StfFile.parse(stf.trimIndent().lines()), config.p4Info)
    return sim
  }

  /** A clone session to [egressPort] that truncates clones to [packetLengthBytes]. */
  private fun cloneSession(sessionId: Int, egressPort: Int, packetLengthBytes: Int): Update =
    Update.newBuilder()
      .setType(Update.Type.INSERT)
      .setEntity(
        Entity.newBuilder()
          .setPacketReplicationEngineEntry(
            PacketReplicationEngineEntry.newBuilder()
              .setCloneSessionEntry(
                CloneSessionEntry.newBuilder()
                  .setSessionId(sessionId)
                  .addReplicas(
                    Replica.newBuilder().apply {
                      @Suppress("DEPRECATION") setEgressPort(egressPort)
                    }
                  )
                  .setPacketLengthBytes(packetLengthBytes)
              )
          )
      )
      .build()

  /** Ingress `packet_length`, as recorded in the output Ethernet `dst_addr`. */
  private fun ingressPacketLength(payload: ByteString): Long =
    BigInteger(1, payload.substring(0, 6).toByteArray()).toLong()

  /** Egress `packet_length`, as recorded in the output Ethernet `src_addr`. */
  private fun egressPacketLength(payload: ByteString): Long =
    BigInteger(1, payload.substring(6, 12).toByteArray()).toLong()

  private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
