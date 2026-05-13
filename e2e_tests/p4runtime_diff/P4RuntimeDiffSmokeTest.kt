package fourward.e2e.p4runtimediff

import fourward.bazel.repoRoot
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.CapabilitiesRequest

/**
 * Smoke test: spawn both servers, exchange a `Capabilities` RPC with each, sanity-check P4Runtime
 * API version alignment. No pipeline config or table operations — those live in
 * [P4RuntimeDiffScenariosTest].
 *
 * Skipped via `Assume` on hosts where the `simple_switch_grpc` binary isn't built.
 */
class P4RuntimeDiffSmokeTest {

  private lateinit var fourward: FourwardP4RuntimeRunner
  private lateinit var bmv2: Bmv2P4RuntimeRunner

  @Before
  fun setUp() {
    val binary = repoRoot.resolve("e2e_tests/p4runtime_diff/simple_switch_grpc")
    Assume.assumeTrue(
      "simple_switch_grpc binary not present — build " +
        "//e2e_tests/p4runtime_diff:simple_switch_grpc first",
      Files.exists(binary) && Files.isExecutable(binary),
    )
    fourward = FourwardP4RuntimeRunner(deviceId = DEVICE_ID)
    bmv2 = Bmv2P4RuntimeRunner(binary = binary, deviceId = DEVICE_ID)
  }

  @After
  fun tearDown() {
    if (::bmv2.isInitialized) bmv2.close()
    if (::fourward.isInitialized) fourward.close()
  }

  @Test
  fun `both servers answer Capabilities RPC`() {
    val fwResp = fourward.stub.capabilities(CapabilitiesRequest.getDefaultInstance())
    val bmResp = bmv2.stub.capabilities(CapabilitiesRequest.getDefaultInstance())
    // We're not asserting equal versions — both servers report their own P4Runtime version.
    // The point is that both responded without error.
    assertTrue("4ward returned empty version", fwResp.p4RuntimeApiVersion.isNotEmpty())
    assertTrue("bmv2 returned empty version", bmResp.p4RuntimeApiVersion.isNotEmpty())
  }

  @Test
  fun `both servers report the same P4Runtime API version family`() {
    // §6.5: capabilities returns the server's P4Runtime version. They might not match exactly —
    // but the major version should align. This documents whatever divergence exists.
    val capRequest = CapabilitiesRequest.getDefaultInstance()
    val fwVer = fourward.stub.capabilities(capRequest).p4RuntimeApiVersion
    val bmVer = bmv2.stub.capabilities(capRequest).p4RuntimeApiVersion
    val fwMajor = fwVer.substringBefore(".")
    val bmMajor = bmVer.substringBefore(".")
    assertEquals(
      "P4Runtime major version divergence (4ward=$fwVer, bmv2=$bmVer) — update the harness " +
        "if this is intentional.",
      fwMajor,
      bmMajor,
    )
  }

  companion object {
    private const val DEVICE_ID = 1L
  }
}
