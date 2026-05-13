package fourward.grpc

import fourward.e2e.compileInlineP4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineP4CompilerTest {

  @Test
  fun `compiles minimal passthrough program`() {
    val config =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply { sm.egress_spec = 1; }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) { apply {} }
      control D(packet_out pkt, in headers_t h) { apply { pkt.emit(h.eth); } }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    assertTrue(config.hasP4Info())
    assertTrue(config.hasDevice())
  }

  @Test
  fun `compiled program runs in simulator`() {
    val config =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply { sm.egress_spec = 1; }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) { apply {} }
      control D(packet_out pkt, in headers_t h) { apply { pkt.emit(h.eth); } }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(config)
      val packet = ByteArray(14) { 0xFF.toByte() }
      val response = it.injectPacket(ingressPort = 0, payload = packet)
      val outputs = response.possibleOutcomesList.flatMap { it.packetsList }
      assertTrue("expected output on port 1", outputs.any { it.dataplaneEgressPort == 1 })
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `non-byte-aligned header emitted by deparser adds padding byte to output`() {
    // A 6-bit header is not byte-aligned. The deparser pads it to 1 byte, so the
    // output payload is 1 byte larger than the original packet.
    val config =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      header extra_t { bit<6> value; }
      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { extra_t extra; ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply {
          sm.egress_spec = 1;
          h.extra.setValid();
          h.extra.value = 0x3F;
        }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) { apply {} }
      control D(packet_out pkt, in headers_t h) { apply { pkt.emit(h.extra); pkt.emit(h.eth); } }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(config)
      val packet = ByteArray(14) { 0xAB.toByte() }
      val response = it.injectPacket(ingressPort = 0, payload = packet)
      val output = response.possibleOutcomesList.single().packetsList.single()
      val payload = output.payload.toByteArray()
      // 6 header bits + 112 ethernet bits = 118 bits → ceil(118/8) = 15 bytes.
      assertEquals("output should be 15 bytes", 15, payload.size)
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `non-byte-aligned header packs as continuous bit stream`() {
    // A 6-bit tag header (value 0x3F = 0b111111) followed by a 14-byte ethernet header
    // (dst = 0xAA repeated). Total: 6 + 112 = 118 bits = 15 bytes.
    //
    // Continuous bit stream (MSB-first):
    //   byte 0 = tag[5:0] ++ eth_dst[47:46] = 111111_10 = 0xFE
    //   byte 1 = eth_dst[45:38] = 10101010 = 0xAA
    //   ... subsequent bytes bit-shifted by 2, with 2 trailing zero bits at the end.
    val config =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      header tag_t { bit<6> value; }
      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { tag_t tag; ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply {
          sm.egress_spec = 1;
          h.tag.setValid();
          h.tag.value = 0x3F;
        }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) { apply {} }
      control D(packet_out pkt, in headers_t h) { apply { pkt.emit(h.tag); pkt.emit(h.eth); } }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(config)
      val packet =
        byteArrayOf(
          0xAA.toByte(),
          0xAA.toByte(),
          0xAA.toByte(),
          0xAA.toByte(),
          0xAA.toByte(),
          0xAA.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0x08,
          0x00,
        )
      val response = it.injectPacket(ingressPort = 0, payload = packet)
      val output = response.possibleOutcomesList.single().packetsList.single()
      val payload = output.payload.toByteArray()

      assertEquals("output should be 15 bytes", 15, payload.size)

      // Continuous bit stream: tag 0b111111 ++ ethernet dst 0xAA = 0b10101010...
      // byte 0: 111111_10 = 0xFE
      assertEquals("byte 0: tag bits + first 2 ethernet bits", 0xFE, payload[0].toInt() and 0xFF)
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `byte-level stripping of non-byte-aligned header corrupts payload`() {
    // Simulate what P4RuntimeService.buildPacketInResponses does: strip
    // ceil(headerBits/8) bytes from the front of the deparsed payload.
    //
    // With a 6-bit controller header, the deparsed bit stream is:
    //   bits: HHHHHH EEEEEEEE EEEEEEEE ... (6 header + 112 ethernet = 118 bits)
    //   bytes: [HHHHHHEE] [EEEEEEEE] ... [EEEEEE00]  (15 bytes, 2 trailing pad bits)
    //
    // ByteString.substring(1) removes the first byte (8 bits), but only 6 were
    // header — 2 bits of the ethernet frame are lost, and all remaining bytes are
    // shifted by 2 relative to the original.
    val config =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      header tag_t { bit<6> value; }
      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { tag_t tag; ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply {
          sm.egress_spec = 1;
          h.tag.setValid();
          h.tag.value = 0x3F;
        }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) { apply {} }
      control D(packet_out pkt, in headers_t h) { apply { pkt.emit(h.tag); pkt.emit(h.eth); } }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(config)
      // Use ethertype 0x0806 (ARP) so the last byte (0x06) makes corruption visible.
      val packet =
        byteArrayOf(
          0xAA.toByte(),
          0xAA.toByte(),
          0xAA.toByte(),
          0xAA.toByte(),
          0xAA.toByte(),
          0xAA.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0xBB.toByte(),
          0x08,
          0x06,
        )
      val response = it.injectPacket(ingressPort = 0, payload = packet)
      val output = response.possibleOutcomesList.single().packetsList.single()
      val deparsed = output.payload

      // Byte-level strip: remove ceil(6/8) = 1 byte.
      val stripped = deparsed.substring(1)

      // The stripped payload is 14 bytes — same size as the original.
      assertEquals(14, stripped.size())
      // But the last byte is corrupted. The original ends with 0x06 = 0b00000110.
      // In the continuous bit stream, these 8 bits are shifted left by 2. The last
      // deparsed byte contains the final 6 ethernet bits (000110) + 2 pad zeros
      // = 0b00011000 = 0x18. After byte-level stripping, this becomes the last
      // byte of the "recovered" payload — wrong.
      val strippedBytes = stripped.toByteArray()
      assertEquals(
        "last byte should be corrupted: original 0x06 becomes 0x18 due to 2-bit shift",
        0x18,
        strippedBytes[13].toInt() and 0xFF,
      )
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `parser extracts non-byte-aligned headers correctly`() {
    // Parse a 4-bit tag followed by a 12-bit length field (= 16 bits total, byte-aligned
    // as a pair but each sub-byte). Then parse ethernet.
    // Ingress writes the parsed len value into ethernet etype for observability.
    val config =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      header tag_t { bit<4> value; }
      header len_t { bit<12> value; }
      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { tag_t tag; len_t len; ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start {
          pkt.extract(hdr.tag);
          pkt.extract(hdr.len);
          pkt.extract(hdr.eth);
          transition accept;
        }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply {
          sm.egress_spec = 1;
          h.eth.etype = (bit<16>) h.len.value;
        }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) { apply {} }
      control D(packet_out pkt, in headers_t h) { apply { pkt.emit(h.eth); } }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(config)
      // First 2 bytes: tag=0xA (4 bits) ++ length=0xBC3 (12 bits) = 0xABC3
      // Bytes 2-15: ethernet header (14 bytes, all zeros)
      val packet = byteArrayOf(0xAB.toByte(), 0xC3.toByte()) + ByteArray(14) { 0x00 }
      val response = it.injectPacket(ingressPort = 0, payload = packet)
      val output = response.possibleOutcomesList.single().packetsList.single()
      val payload = output.payload.toByteArray()

      // With correct bit-level parsing, the parser would consume exactly 16 bits
      // (4 + 12) for tag+len, leaving 14 bytes for ethernet → 14-byte output.
      // With the current byte-level parser, extracting the 4-bit tag consumes a
      // full byte, so tag+len consume 3 bytes, leaving only 13 for ethernet.
      assertEquals("output should be 14 bytes with correct bit-level parsing", 14, payload.size)

      val etype = ((payload[12].toInt() and 0xFF) shl 8) or (payload[13].toInt() and 0xFF)
      assertEquals("len should parse as 0xBC3 (written into etype)", 0x0BC3, etype)
    }
  }
}
