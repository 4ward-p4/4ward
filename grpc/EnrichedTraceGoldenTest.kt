package fourward.grpc

import com.google.protobuf.ByteString
import com.google.protobuf.TextFormat
import fourward.PipelineConfig
import fourward.TraceTree
import fourward.grpc.FourwardTestHarness.Companion.buildEthernetFrame
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import fourward.grpc.FourwardTestHarness.Companion.longToBytes
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass

/**
 * Golden test for P4RT-enriched trace trees.
 *
 * Loads `translated_port.p4` — a minimal program using the SAI forked v1model with
 * `@p4runtime_translation("", string)` on port types. Installs a forwarding entry with port name
 * `"Ethernet1"` via P4Runtime, injects a packet on `"Ethernet0"`, and compares the enriched trace
 * against a golden file.
 *
 * The golden file documents the full enrichment: `p4rt_ingress_port`, `p4rt_egress_port`, and
 * `p4rt_matched_entry` with human-readable port names alongside raw dataplane values.
 */
class EnrichedTraceGoldenTest {

  private lateinit var harness: FourwardTestHarness
  private lateinit var config: PipelineConfig

  @Before
  fun setUp() {
    harness = FourwardTestHarness()
    config = loadConfig("e2e_tests/translated_port/translated_port.txtpb")
    harness.loadPipeline(config)
  }

  @After
  fun tearDown() {
    harness.close()
  }

  @Test
  fun `enriched trace matches golden file`() {
    // Install: etherType=0x0800 → forward(port="Ethernet1").
    harness.installEntry(buildForwardingEntry(matchValue = 0x0800, portName = "Ethernet1"))

    // Inject on "Ethernet0" — auto-allocates dp port 1 (0 is taken by "Ethernet1").
    val p4rtPort = ByteString.copyFromUtf8("Ethernet0")
    val payload = buildEthernetFrame(etherType = 0x0800)
    val response = harness.injectPacketP4rt(p4rtPort, payload)
    val actual = response.trace

    if (UPDATE) {
      fourward.bazel.repoRoot
        .resolve(GOLDEN_PATH)
        .toFile()
        .writeText(TextFormat.printer().printToString(actual))
      return
    }

    val golden = loadGolden()
    if (actual != golden) {
      fail(
        "Enriched trace mismatch.\n" +
          "Expected:\n${TextFormat.printer().printToString(golden)}\n" +
          "Actual:\n${TextFormat.printer().printToString(actual)}"
      )
    }
  }

  private fun loadGolden(): TraceTree {
    val path = fourward.bazel.repoRoot.resolve(GOLDEN_PATH)
    val builder = TraceTree.newBuilder()
    TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }

  private fun buildForwardingEntry(matchValue: Long, portName: String): P4RuntimeOuterClass.Entity {
    val p4info = config.p4Info
    val table = p4info.tablesList.first()
    val forwardAction = p4info.actionsList.find { it.preamble.name.contains("forward") }!!
    val matchField = table.matchFieldsList.first()

    val entry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addMatch(
          P4RuntimeOuterClass.FieldMatch.newBuilder()
            .setFieldId(matchField.id)
            .setExact(
              P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                .setValue(
                  ByteString.copyFrom(longToBytes(matchValue, (matchField.bitwidth + 7) / 8))
                )
            )
        )
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(forwardAction.preamble.id)
                .addParams(
                  P4RuntimeOuterClass.Action.Param.newBuilder()
                    .setParamId(forwardAction.paramsList.first().id)
                    .setValue(ByteString.copyFromUtf8(portName))
                )
            )
        )
        .build()

    return P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry).build()
  }

  companion object {
    private const val GOLDEN_PATH = "grpc/enriched_trace.golden.txtpb"
    private val UPDATE = System.getProperty("update") == "true"
  }
}
