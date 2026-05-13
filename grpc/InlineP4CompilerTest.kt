package fourward.grpc

import fourward.PipelineConfig
import fourward.e2e.compileInlineP4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineP4CompilerTest {

  @Test
  fun `compiles minimal passthrough program`() {
    val config = compilePassthrough()
    assertTrue(config.hasP4Info())
    assertTrue(config.hasDevice())
  }

  @Test
  fun `compiled program runs in simulator`() {
    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(compilePassthrough())
      val packet = ByteArray(14) { 0xFF.toByte() }
      val response = it.injectPacket(ingressPort = 0, payload = packet)
      val outputs = response.possibleOutcomesList.flatMap { it.packetsList }
      assertTrue("expected output on port 1", outputs.any { it.dataplaneEgressPort == 1 })
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `non-byte-aligned header in continuous bit stream increases output size`() {
    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(compileSixBitTag())
      val packet = ByteArray(14) { 0xAB.toByte() }
      val response = it.injectPacket(ingressPort = 0, payload = packet)
      val output = response.possibleOutcomesList.single().packetsList.single()
      // 6 header bits + 112 ethernet bits = 118 bits → ceil(118/8) = 15 bytes.
      assertEquals("output should be 15 bytes", 15, output.payload.size())
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `non-byte-aligned header packs as continuous bit stream`() {
    // A 6-bit tag (0x3F = 0b111111) followed by ethernet dst 0xAA (0b10101010).
    // Continuous bit stream byte 0: 111111_10 = 0xFE.
    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(compileSixBitTag())
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
      val payload =
        response.possibleOutcomesList.single().packetsList.single().payload.toByteArray()

      assertEquals("output should be 15 bytes", 15, payload.size)
      assertEquals("byte 0: tag bits + first 2 ethernet bits", 0xFE, payload[0].toInt() and 0xFF)
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `byte-level stripping of non-byte-aligned header corrupts payload`() {
    // With a 6-bit header, ByteString.substring(1) strips 8 bits instead of 6,
    // losing 2 payload bits and shifting all subsequent bytes.
    val harness = FourwardTestHarness()
    harness.use {
      it.loadPipeline(compileSixBitTag())
      // Ethertype 0x0806 so the last byte (0x06) makes corruption visible.
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
      val deparsed = response.possibleOutcomesList.single().packetsList.single().payload

      val stripped = deparsed.substring(1)
      assertEquals(14, stripped.size())
      // Original last byte 0x06 = 0b00000110 becomes 0b00011000 = 0x18 after 2-bit shift.
      assertEquals(
        "last byte corrupted: 0x06 becomes 0x18 due to 2-bit shift",
        0x18,
        stripped.toByteArray()[13].toInt() and 0xFF,
      )
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `parser extracts non-byte-aligned headers correctly`() {
    // Parse a 4-bit tag followed by a 12-bit length (16 bits total, byte-aligned as a pair
    // but each sub-byte). Ingress writes the parsed len into ethernet etype for observability.
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
      // tag=0xA (4 bits) ++ length=0xBC3 (12 bits) = 0xABC3, then 14 zero bytes for ethernet.
      val packet = byteArrayOf(0xAB.toByte(), 0xC3.toByte()) + ByteArray(14) { 0x00 }
      val response = it.injectPacket(ingressPort = 0, payload = packet)
      val payload =
        response.possibleOutcomesList.single().packetsList.single().payload.toByteArray()

      assertEquals("output should be 14 bytes with correct bit-level parsing", 14, payload.size)
      val etype = ((payload[12].toInt() and 0xFF) shl 8) or (payload[13].toInt() and 0xFF)
      assertEquals("len should parse as 0xBC3 (written into etype)", 0x0BC3, etype)
    }
  }

  companion object {
    private fun compilePassthrough(): PipelineConfig =
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

    private fun compileSixBitTag(): PipelineConfig =
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
  }
}
