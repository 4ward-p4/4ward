package fourward.e2e.pipelineoutputs

import com.google.protobuf.TextFormat
import fourward.DeviceConfig
import fourward.PipelineConfig
import fourward.bazel.repoRoot
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig

/**
 * Verifies that `fourward_pipeline` emits each configured output as the expected proto message
 * type, in both text and binary encodings. See the companion BUILD.bazel for the target
 * configurations.
 */
class PipelineOutputsTest {

  @Test
  fun `out + out_format=native produces PipelineConfig as txtpb`() {
    val path = runfile("tiny_native.txtpb")
    assertHeader(path, "fourward.PipelineConfig")
    val cfg =
      PipelineConfig.newBuilder().also { TextFormat.merge(Files.readString(path), it) }.build()
    assertHasBasicTable(cfg.p4Info)
    assertFalse(cfg.device.behavioral.controlsList.isEmpty())
  }

  @Test
  fun `out + out_format=p4runtime produces ForwardingPipelineConfig as binpb`() {
    val bytes = Files.readAllBytes(runfile("tiny_p4runtime.binpb"))
    val fpc = ForwardingPipelineConfig.parseFrom(bytes)
    assertHasBasicTable(fpc.p4Info)
    // p4_device_config is an opaque blob; we only assert it decodes as our DeviceConfig.
    val dc = DeviceConfig.parseFrom(fpc.p4DeviceConfig)
    assertFalse(dc.behavioral.controlsList.isEmpty())
  }

  @Test
  fun `out_p4info produces standalone P4Info in txtpb`() {
    val path = runfile("tiny.p4info.txtpb")
    assertHeader(path, "p4.config.v1.P4Info")
    val p4info = P4Info.newBuilder().also { TextFormat.merge(Files.readString(path), it) }.build()
    assertHasBasicTable(p4info)
  }

  @Test
  fun `out_p4info produces standalone P4Info in binpb`() {
    val bytes = Files.readAllBytes(runfile("tiny.p4info.binpb"))
    val p4info = P4Info.parseFrom(bytes)
    assertHasBasicTable(p4info)
  }

  @Test
  fun `out_p4_device_config produces standalone DeviceConfig in txtpb`() {
    val path = runfile("tiny.device_config.txtpb")
    assertHeader(path, "fourward.DeviceConfig")
    val dc = DeviceConfig.newBuilder().also { TextFormat.merge(Files.readString(path), it) }.build()
    assertFalse(dc.behavioral.controlsList.isEmpty())
  }

  @Test
  fun `out_p4_device_config produces standalone DeviceConfig in binpb`() {
    val bytes = Files.readAllBytes(runfile("tiny.device_config.binpb"))
    val dc = DeviceConfig.parseFrom(bytes)
    assertFalse(dc.behavioral.controlsList.isEmpty())
  }

  @Test
  fun `large binpb output round-trips cleanly (regression for #592)`() {
    // tiny.p4's binpb (~2 KB) fits in a single ofstream buffer chunk so it
    // can't catch silent flush truncation. SAI middleblock's DeviceConfig is
    // ~130 KB — well past typical ofstream buffer sizes (~8-64 KB) — and is
    // the smallest fixture in the tree large enough to exercise the
    // multi-chunk write path. Manifested as 201-byte truncated files in
    // google3 before #596.
    val saiDir = repoRoot.resolve("e2e_tests/sai_p4")
    val binpbBytes = Files.readAllBytes(saiDir.resolve("sai_middleblock.dc.binpb"))
    // 100 KB lower bound is comfortably above any plausible ofstream buffer
    // size; if SAI middleblock ever shrinks below this, pick a different
    // fixture rather than lowering the threshold.
    assertTrue(
      "expected SAI middleblock DeviceConfig > 100 KB to force " +
        "multi-chunk writes, got ${binpbBytes.size}",
      binpbBytes.size > 100_000,
    )
    // Compare against the device sub-message of the combined PipelineConfig
    // emitted by the same p4c invocation. Catches truncation that happens to
    // land at a message boundary (where parseFrom wouldn't throw) and asserts
    // that the standalone binpb matches the combined output for a real-sized
    // pipeline (tiny.p4 already covers this property at small scale).
    val combined =
      PipelineConfig.newBuilder()
        .also { TextFormat.merge(Files.readString(saiDir.resolve("sai_middleblock.txtpb")), it) }
        .build()
    assertEquals(combined.device, DeviceConfig.parseFrom(binpbBytes))
  }

  @Test
  fun `combined target emits same artifacts as single-output targets`() {
    // p4c is invoked once with all three flags; each file should match what
    // the dedicated single-output targets produce in isolation.
    val combined =
      ForwardingPipelineConfig.parseFrom(Files.readAllBytes(runfile("tiny_all.combined.binpb")))
    val standalone =
      ForwardingPipelineConfig.parseFrom(Files.readAllBytes(runfile("tiny_p4runtime.binpb")))
    assertEquals(standalone, combined)

    val p4info =
      P4Info.newBuilder()
        .also { TextFormat.merge(Files.readString(runfile("tiny_all.p4info.txtpb")), it) }
        .build()
    val p4infoStandalone =
      P4Info.newBuilder()
        .also { TextFormat.merge(Files.readString(runfile("tiny.p4info.txtpb")), it) }
        .build()
    assertEquals(p4infoStandalone, p4info)

    val dc = DeviceConfig.parseFrom(Files.readAllBytes(runfile("tiny_all.device_config.binpb")))
    val dcStandalone =
      DeviceConfig.parseFrom(Files.readAllBytes(runfile("tiny.device_config.binpb")))
    assertEquals(dcStandalone, dc)
  }

  private fun runfile(name: String): Path = repoRoot.resolve("e2e_tests/pipeline_outputs/$name")

  private fun assertHeader(path: Path, expectedMessage: String) {
    val firstTwo = Files.readAllLines(path).take(2)
    assertEquals(
      "text-proto output must declare proto-file and proto-message",
      2,
      firstTwo.count { it.startsWith("# proto-") },
    )
    assertTrue(
      "expected proto-message '$expectedMessage' in header, got: $firstTwo",
      firstTwo.any { it == "# proto-message: $expectedMessage" },
    )
  }

  private fun assertHasBasicTable(p4info: P4Info) {
    assertEquals("expected exactly one table (tiny.p4 declares one)", 1, p4info.tablesCount)
  }
}
