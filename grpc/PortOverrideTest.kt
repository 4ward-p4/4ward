package fourward.grpc

import fourward.PipelineConfig
import fourward.TranslationEntry
import fourward.TypeTranslation
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import io.grpc.Status
import org.junit.Test

/**
 * Tests that CPU port and drop port can be configured using P4RT port names, not just raw dataplane
 * integers. The P4RT name is resolved at pipeline load time using explicit translation mappings.
 */
class PortOverrideTest {

  @Test
  fun `P4RT cpu port resolves to dataplane port from explicit mapping`() {
    FourwardTestHarness(cpuPortConfig = CpuPortConfig.Override(PortOverride.P4rt("CpuPort"))).use {
      harness ->
      val config = withPortMappings(loadTranslatedPort(), mapOf("CpuPort" to 510, "Eth0" to 0))
      harness.loadPipeline(config)
    }
  }

  @Test
  fun `P4RT drop port resolves to dataplane port from explicit mapping`() {
    FourwardTestHarness(dropPortOverride = PortOverride.P4rt("DropPort")).use { harness ->
      val config = withPortMappings(loadTranslatedPort(), mapOf("DropPort" to 511, "Eth0" to 0))
      harness.loadPipeline(config)
    }
  }

  @Test
  fun `dataplane cpu port still works`() {
    FourwardTestHarness(cpuPortConfig = CpuPortConfig.Override(PortOverride.Dataplane(510))).use {
      harness ->
      harness.loadPipeline(loadTranslatedPort())
    }
  }

  @Test
  fun `P4RT cpu port fails when pipeline has no port translation`() {
    val cpuPort = CpuPortConfig.Override(PortOverride.P4rt("CpuPort"))
    FourwardTestHarness(cpuPortConfig = cpuPort).use { harness ->
      val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
      FourwardTestHarness.assertGrpcError(
        Status.Code.FAILED_PRECONDITION,
        "no @p4runtime_translation",
      ) {
        harness.loadPipeline(config)
      }
    }
  }

  @Test
  fun `P4RT cpu port fails when name has no explicit mapping`() {
    val cpuPort = CpuPortConfig.Override(PortOverride.P4rt("UnknownPort"))
    FourwardTestHarness(cpuPortConfig = cpuPort).use { harness ->
      val config = withPortMappings(loadTranslatedPort(), mapOf("Eth0" to 0))
      FourwardTestHarness.assertGrpcError(
        Status.Code.FAILED_PRECONDITION,
        "no explicit translation",
      ) {
        harness.loadPipeline(config)
      }
    }
  }

  @Test
  fun `P4RT drop port fails when name has no explicit mapping`() {
    FourwardTestHarness(dropPortOverride = PortOverride.P4rt("UnknownPort")).use { harness ->
      val config = withPortMappings(loadTranslatedPort(), mapOf("Eth0" to 0))
      FourwardTestHarness.assertGrpcError(
        Status.Code.FAILED_PRECONDITION,
        "no explicit translation",
      ) {
        harness.loadPipeline(config)
      }
    }
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private fun loadTranslatedPort(): PipelineConfig =
    loadConfig("e2e_tests/translated_port/translated_port.txtpb")

  @Suppress("MagicNumber")
  private fun withPortMappings(config: PipelineConfig, ports: Map<String, Int>): PipelineConfig {
    val portTranslation = TypeTranslation.newBuilder().setTypeName("port_id_t")
    for ((name, dpValue) in ports) {
      portTranslation.addEntries(
        TranslationEntry.newBuilder().setSdnStr(name).setDataplaneValue(encodeMinWidth(dpValue))
      )
    }
    return config
      .toBuilder()
      .setDevice(config.device.toBuilder().addTranslations(portTranslation))
      .build()
  }
}
