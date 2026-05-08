package fourward.cli

import fourward.bazel.repoRoot
import fourward.stf.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [startNetworkServers]. */
class NetworkServeTest {

  @Test
  fun `starts one server per switch`() {
    val nstf = NetworkStf.parse(repoRoot.resolve("$PKG/two_switch_inline.nstf"))
    val servers = startNetworkServers(nstf, EPHEMERAL_PORT)
    try {
      assertEquals(2, servers.size)
      assertTrue("server ports should be positive", servers.all { it.port() > 0 })
    } finally {
      servers.forEach { it.stop() }
    }
  }

  @Test
  fun `servers are pre-loaded with pipelines`() {
    val nstf = NetworkStf.parse(repoRoot.resolve("$PKG/two_switch_inline.nstf"))
    val servers = startNetworkServers(nstf, EPHEMERAL_PORT)
    try {
      val s1Result = servers[0].simulator.processPacket(0, PAYLOAD.decodeHex())
      val s1Outputs = s1Result.possibleOutcomes.single()
      assertEquals(1, s1Outputs.size)
      assertEquals(1, s1Outputs[0].dataplaneEgressPort)

      val s2Result = servers[1].simulator.processPacket(1, PAYLOAD.decodeHex())
      val s2Outputs = s2Result.possibleOutcomes.single()
      assertEquals(1, s2Outputs.size)
      assertEquals(2, s2Outputs[0].dataplaneEgressPort)
    } finally {
      servers.forEach { it.stop() }
    }
  }

  @Test
  fun `port collision on second switch cleans up first`() {
    val nstf = NetworkStf.parse(repoRoot.resolve("$PKG/two_switch_inline.nstf"))
    // Start a server on a known port, then try to start both switches on that same port.
    val blocker = fourward.grpc.FourwardServer(port = EPHEMERAL_PORT).start()
    val blockedPort = blocker.port()
    try {
      val e =
        assertThrows(NetworkServeException::class.java) { startNetworkServers(nstf, blockedPort) }
      assertTrue("should mention failed to start", e.message!!.contains("failed to start"))
    } finally {
      blocker.stop()
    }
  }

  companion object {
    private const val PKG = "e2e_tests/network"
    private const val EPHEMERAL_PORT = 0
    private const val PAYLOAD = "FFFFFFFFFFFF 000000000001 0800 DEADBEEF"
  }
}
