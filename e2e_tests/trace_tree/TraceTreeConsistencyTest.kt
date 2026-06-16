package fourward.e2e.tracetree

import com.google.protobuf.TextFormat
import fourward.TraceTree
import fourward.simulator.ProcessPacketResult
import fourward.simulator.Simulator
import fourward.stf.StfFile
import fourward.stf.installStfEntries
import fourward.stf.loadPipelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Verifies that output packets from [ProcessPacketResult] are consistent with the leaf outcomes in
 * the trace tree.
 *
 * The possible outcomes should be consistent with the trace tree's leaf outcomes, whether the trace
 * forks (multicast, clone, action selectors) or not.
 */
@RunWith(Parameterized::class)
class TraceTreeConsistencyTest(private val testName: String) {

  companion object {
    private const val PKG = "e2e_tests/trace_tree"

    @JvmStatic
    @Parameters(name = "{0}")
    fun testCases(): List<Array<String>> =
      System.getProperty("fourward.trace_tree_programs")
        .orEmpty()
        .split(",")
        .filter { it.isNotEmpty() }
        .sorted()
        .map { arrayOf(it) }
  }

  @Test
  fun `output packets match trace tree leaves`() {
    val configPath = fourward.bazel.repoRoot.resolve("$PKG/$testName.txtpb")
    val stfPath = fourward.bazel.repoRoot.resolve("$PKG/$testName.stf")

    val config = loadPipelineConfig(configPath)
    val stf = StfFile.parse(stfPath)

    val sim = Simulator()
    sim.loadPipeline(config)
    installStfEntries(sim, stf, config.p4Info)

    for (packet in stf.packets) {
      verifyConsistency(sim.processPacket(packet.ingressPort, packet.payload))
    }
  }

  private fun verifyConsistency(result: ProcessPacketResult) {
    val trace = result.trace
    val leafTrees = collectLeafOutcomes(trace)

    assertTrue("Trace tree for $testName has no leaf outcomes", leafTrees.isNotEmpty())

    // Verify possibleOutcomes is consistent with the trace tree: flattening all worlds
    // should produce the same outputs as collecting all leaf outputs from the tree.
    val outputsFromPossibleOutcomes =
      result.possibleOutcomes.flatten().map { it.dataplaneEgressPort to it.payload }

    val outputsFromTree =
      leafTrees.filter { it.hasOutput() }.map { it.output.dataplaneEgressPort to it.output.payload }

    // For trees without nested alternative-inside-parallel forks, these match exactly.
    // For trees with such nesting, possibleOutcomes may have duplicates from the Cartesian
    // product, so we compare as sets.
    assertEquals(
      "Output packets vs trace tree mismatch for $testName.\n" +
        "Trace:\n${TextFormat.printer().printToString(trace)}",
      outputsFromPossibleOutcomes.toSet(),
      outputsFromTree.toSet(),
    )
  }

  /**
   * Recursively collects all leaf [TraceTree]s (nodes with terminal outcomes) from a trace tree.
   */
  private fun collectLeafOutcomes(tree: TraceTree): List<TraceTree> =
    when (tree.outcomeCase) {
      TraceTree.OutcomeCase.OUTPUT,
      TraceTree.OutcomeCase.DROP -> listOf(tree)
      TraceTree.OutcomeCase.REPLICATION ->
        tree.replication.branchesList.flatMap { collectLeafOutcomes(it) }
      TraceTree.OutcomeCase.CHOICE -> tree.choice.branchesList.flatMap { collectLeafOutcomes(it) }
      TraceTree.OutcomeCase.CONTINUATION -> collectLeafOutcomes(tree.continuation.next)
      TraceTree.OutcomeCase.OUTCOME_NOT_SET,
      null -> emptyList()
    }
}
