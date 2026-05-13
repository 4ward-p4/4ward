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
      // The 6-bit header is padded to 1 byte by the deparser, so the output is
      // 15 bytes (1 header byte + 14 original) instead of the original 14.
      assertEquals(
        "deparser should pad 6-bit header to 1 byte, making output 15 bytes",
        15,
        payload.size,
      )
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
}
