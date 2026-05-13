package fourward.grpc

import fourward.e2e.compileInlineP4
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
}
