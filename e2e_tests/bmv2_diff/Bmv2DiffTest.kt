package fourward.e2e.bmv2

import fourward.simulator.Simulator
import fourward.stf.StfFile
import fourward.stf.hex
import fourward.stf.installStfEntries
import fourward.stf.loadPipelineConfig
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Differential test: runs STF test vectors through both the 4ward simulator and BMv2's
 * simple_switch, then compares output packets bit-for-bit.
 *
 * Both simulators receive the same table entries and input packets (translated from each STF file).
 * Output packets are sorted by (port, payload) before comparison, since cross-port ordering is
 * unspecified.
 */
@RunWith(Parameterized::class)
class Bmv2DiffTest(private val testName: String) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun testCases(): List<Array<String>> =
      System.getProperty("fourward.bmv2_diff_testcases")
        .orEmpty()
        .split(",")
        .filter { it.isNotEmpty() }
        .sorted()
        .map { arrayOf(it) }
  }

  @Test
  fun test() {
    val pkg = "e2e_tests/bmv2_diff"
    val stfPath = fourward.bazel.repoRoot.resolve("$pkg/$testName.stf")
    val jsonPath = fourward.bazel.repoRoot.resolve("$pkg/$testName.json")
    val configPath = fourward.bazel.repoRoot.resolve("$pkg/$testName.txtpb")
    val driverBinary = fourward.bazel.repoRoot.resolve("$pkg/bmv2_driver")

    val stf = StfFile.parse(stfPath)
    val config = loadPipelineConfig(configPath)

    // --- Run through 4ward ---
    val fourwardOutputs = mutableListOf<Pair<Int, ByteArray>>()
    val sim = Simulator()
    sim.loadPipeline(config)
    installStfEntries(sim, stf, config.p4Info)
    for (packet in stf.packets) {
      val result = sim.processPacket(packet.ingressPort, packet.payload)
      // Flatten all possible outcomes — BMv2's sendPacketExploring also explores all
      // action selector members, so both sides produce the same flat set of outputs.
      for (pkt in result.possibleOutcomes.flatten()) {
        fourwardOutputs.add(pkt.dataplaneEgressPort to pkt.payload.toByteArray())
      }
    }

    // --- Run through BMv2 ---
    val bmv2Outputs = mutableListOf<Pair<Int, ByteArray>>()
    Bmv2Runner(driverBinary, jsonPath, config.p4Info).use { bmv2 ->
      bmv2.installEntries(stf)
      for (packet in stf.packets) {
        // sendPacketExploring handles action selectors via round-robin exploration
        // (temporarily reducing groups to single members); falls back to a normal
        // sendPacket when no selector groups are present.
        bmv2Outputs.addAll(bmv2.sendPacketExploring(packet.ingressPort, packet.payload))
      }
    }

    assertOutputsMatch(fourwardOutputs, bmv2Outputs)
  }
}

/**
 * Asserts that 4ward and BMv2 produced the same set of output packets.
 *
 * Outputs are sorted by (port, payload hex) before comparison so cross-port ordering differences —
 * unspecified by both simulators — don't cause spurious failures.
 */
fun assertOutputsMatch(
  fourwardOutputs: List<Pair<Int, ByteArray>>,
  bmv2Outputs: List<Pair<Int, ByteArray>>,
) {
  val sortKey: (Pair<Int, ByteArray>) -> String = { (port, payload) ->
    "%04d:%s".format(port, payload.hex())
  }
  val fourwardSorted = fourwardOutputs.sortedBy(sortKey)
  val bmv2Sorted = bmv2Outputs.sortedBy(sortKey)

  val mismatches = mutableListOf<String>()
  if (fourwardSorted.size != bmv2Sorted.size) {
    mismatches.add("Output count mismatch: 4ward=${fourwardSorted.size}, bmv2=${bmv2Sorted.size}")
  }
  val n = minOf(fourwardSorted.size, bmv2Sorted.size)
  for (i in 0 until n) {
    val (fPort, fPayload) = fourwardSorted[i]
    val (bPort, bPayload) = bmv2Sorted[i]
    if (fPort != bPort || !fPayload.contentEquals(bPayload)) {
      mismatches.add(
        "Output $i differs:\n" +
          "  4ward: port=$fPort payload=${fPayload.hex()}\n" +
          "  bmv2:  port=$bPort payload=${bPayload.hex()}"
      )
    }
  }

  if (mismatches.isNotEmpty()) {
    Assert.fail(mismatches.joinToString("\n"))
  }
}
