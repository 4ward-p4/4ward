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
    val eventIdCounts = collectEventIdCounts(trace)

    assertTrue("Trace tree for $testName has no leaf outcomes", leafTrees.isNotEmpty())
    val duplicateIds = eventIdCounts.filterValues { it > 1 }.keys.sorted()
    assertTrue(
      "Trace event ids must be unique for $testName; duplicate ids: $duplicateIds.\n" +
        "Trace:\n${TextFormat.printer().printToString(trace)}",
      duplicateIds.isEmpty(),
    )
    assertCausesResolveOnce(trace, eventIdCounts)

    // Verify possibleOutcomes is consistent with the trace tree: flattening all worlds
    // should produce the same outputs as collecting all leaf outputs from the tree.
    val outputsFromPossibleOutcomes =
      result.possibleOutcomes.flatten().map { it.dataplaneEgressPort to it.payload }

    val outputsFromTree =
      leafTrees.filter { it.hasOutput() }.map { it.output.dataplaneEgressPort to it.output.payload }

    // possibleOutcomes is a set of distinct outcomes, so flattening it drops the packets of any
    // folded duplicate outcome — but every distinct output the tree can produce still survives in
    // some outcome. So we compare as sets of observable outputs rather than as multisets.
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

  /** Recursively counts all [TraceEvent.id] values from a trace tree. */
  private fun collectEventIdCounts(tree: TraceTree): Map<Long, Int> {
    val counts = mutableMapOf<Long, Int>()
    fun collect(node: TraceTree) {
      for (event in node.eventsList) counts[event.id] = counts.getOrDefault(event.id, 0) + 1
      when (node.outcomeCase) {
        TraceTree.OutcomeCase.REPLICATION -> node.replication.branchesList.forEach { collect(it) }
        TraceTree.OutcomeCase.CHOICE -> node.choice.branchesList.forEach { collect(it) }
        TraceTree.OutcomeCase.CONTINUATION -> collect(node.continuation.next)
        TraceTree.OutcomeCase.OUTPUT,
        TraceTree.OutcomeCase.DROP,
        TraceTree.OutcomeCase.OUTCOME_NOT_SET,
        null -> {}
      }
    }
    collect(tree)
    return counts
  }

  private fun assertCausesResolveOnce(tree: TraceTree, eventIdCounts: Map<Long, Int>) {
    val cause =
      when (tree.outcomeCase) {
        TraceTree.OutcomeCase.REPLICATION ->
          if (tree.replication.hasCause()) tree.replication.cause else null
        TraceTree.OutcomeCase.CHOICE -> if (tree.choice.hasCause()) tree.choice.cause else null
        TraceTree.OutcomeCase.DROP -> if (tree.drop.hasCause()) tree.drop.cause else null
        TraceTree.OutcomeCase.CONTINUATION,
        TraceTree.OutcomeCase.OUTPUT,
        TraceTree.OutcomeCase.OUTCOME_NOT_SET,
        null -> null
      }
    if (cause != null) {
      assertEquals(
        "Trace outcome cause $cause must resolve to exactly one event for $testName.\n" +
          "Trace:\n${TextFormat.printer().printToString(tree)}",
        1,
        eventIdCounts[cause] ?: 0,
      )
    }

    when (tree.outcomeCase) {
      TraceTree.OutcomeCase.REPLICATION ->
        tree.replication.branchesList.forEach { assertCausesResolveOnce(it, eventIdCounts) }
      TraceTree.OutcomeCase.CHOICE ->
        tree.choice.branchesList.forEach { assertCausesResolveOnce(it, eventIdCounts) }
      TraceTree.OutcomeCase.CONTINUATION ->
        assertCausesResolveOnce(tree.continuation.next, eventIdCounts)
      TraceTree.OutcomeCase.OUTPUT,
      TraceTree.OutcomeCase.DROP,
      TraceTree.OutcomeCase.OUTCOME_NOT_SET,
      null -> {}
    }
  }
}
