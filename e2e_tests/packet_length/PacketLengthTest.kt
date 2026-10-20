package fourward.e2e.packetlength

import fourward.stf.TestResult
import fourward.stf.runStfTest
import org.junit.Assert.fail
import org.junit.Test

/**
 * Egress `standard_metadata.packet_length` reflects ingress `setValid()` / `setInvalid()`, matching
 * BMv2 simple_switch. Regression test for b/567572012 (wrong GRE-encap IPv6 payload_length in SAI
 * P4 after a VLAN tag is popped in ingress).
 */
class PacketLengthTest {

  @Test
  fun `egress packet_length accounts for headers added and removed in ingress`() {
    val result = runStfTest("packet_length")
    if (result is TestResult.Failure) fail(result.message)
  }
}
