package fourward.e2e.tracetree

import com.google.protobuf.TextFormat
import fourward.TraceTree
import fourward.bazel.repoRoot
import fourward.e2e.assertMatchesGoldenFile
import fourward.simulator.Simulator
import fourward.stf.StfFile
import fourward.stf.installStfEntries
import fourward.stf.loadPipelineConfig
import java.nio.file.Path
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Parameterized golden test for trace trees.
 *
 * Each test case consists of:
 * - A compiled PipelineConfig (.txtpb)
 * - An STF file (.stf) with optional table entries and at least one packet
 * - An expected TraceTree (.golden.txtpb)
 *
 * To update golden files after an intentional change: bazel run
 * //e2e_tests/trace_tree:golden_trace_tree_test -- --update
 */
@RunWith(Parameterized::class)
class GoldenTraceTreeTest(private val testName: String) {

  companion object {
    private const val PKG = "e2e_tests/trace_tree"

    @JvmStatic
    @Parameters(name = "{0}")
    fun testCases(): List<Array<String>> =
      System.getProperty("fourward.trace_tree_goldens")
        .orEmpty()
        .split(",")
        .filter { it.isNotEmpty() }
        .sorted()
        .map { arrayOf(it) }
  }

  @Test
  fun `trace tree matches golden file`() {
    val configPath = repoRoot.resolve("$PKG/$testName.txtpb")
    val stfPath = repoRoot.resolve("$PKG/$testName.stf")
    val actual = captureTraceTree(configPath, stfPath)

    assertMatchesGoldenFile(
      goldenFileName = "$testName.golden.txtpb",
      pkg = PKG,
      actual = TextFormat.printer().printToString(actual),
    )
  }

  private fun captureTraceTree(configPath: Path, stfPath: Path): TraceTree {
    val config = loadPipelineConfig(configPath)
    val stf = StfFile.parse(stfPath)
    val sim = Simulator()
    sim.loadPipeline(config)
    installStfEntries(sim, stf, config.p4Info)
    val packet = stf.packets.first()
    return sim.processPacket(packet.ingressPort, packet.payload).trace
  }
}
