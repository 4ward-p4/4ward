package fourward.grpc

import com.google.protobuf.ByteString
import fourward.CreateDevicesRequest
import fourward.PipelineConfig
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * Multi-device scale benchmark.
 *
 * Run with:
 * ```
 * bazel test //grpc:MultiDeviceScaleBenchmark --test_output=streamed \
 *   --jvmopt=-Dfourward.multidevice.devices=10000 \
 *   --jvmopt=-Dfourward.multidevice.entriesPerDevice=1000
 * ```
 *
 * The benchmark loads SAI middleblock on every device. `entriesPerDevice` installs that many IPv6
 * route entries per device, plus the four prerequisite SAI objects needed by `@refers_to`.
 */
@Suppress("FunctionNaming", "MagicNumber")
class MultiDeviceScaleBenchmark {
  @Test
  fun `multi-device scale benchmark`() = runBlocking {
    val deviceCount = intProperty("fourward.multidevice.devices", default = 100)
    val entriesPerDevice = intProperty("fourward.multidevice.entriesPerDevice", default = 0)
    val writeBatchSize = intProperty("fourward.multidevice.writeBatchSize", default = 1_000)
    require(deviceCount > 0) { "fourward.multidevice.devices must be positive" }
    require(entriesPerDevice >= 0) { "fourward.multidevice.entriesPerDevice must be non-negative" }
    require(entriesPerDevice <= MAX_IPV6_ROUTES_PER_DEVICE) {
      "entriesPerDevice must be <= $MAX_IPV6_ROUTES_PER_DEVICE for unique IPv6 /128 routes"
    }
    require(writeBatchSize > 0) { "fourward.multidevice.writeBatchSize must be positive" }

    val registry =
      DeviceRegistry(DeviceSettings(), maxDevices = deviceCount, maxDevicesPerRequest = deviceCount)
    try {
      val management = ManagementService(registry)
      val p4runtime = MultiDeviceP4RuntimeService(registry)

      val baselineHeap = usedHeapAfterGc()
      val createMs = elapsedMillis {
        if (deviceCount > 1) {
          management.createDevices(
            CreateDevicesRequest.newBuilder().setFirstDeviceId(2).setCount(deviceCount - 1).build()
          )
        }
      }
      val idleHeap = usedHeapAfterGc()

      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      val forwardingConfig =
        ForwardingPipelineConfig.newBuilder()
          .setP4Info(config.p4Info)
          .setP4DeviceConfig(config.device.toByteString())
          .build()
      val entryBatches = saiEntries(config, entriesPerDevice).chunked(writeBatchSize)

      val populateMs = elapsedMillis {
        for (deviceId in 1L..deviceCount.toLong()) {
          p4runtime.setForwardingPipelineConfig(
            SetForwardingPipelineConfigRequest.newBuilder()
              .setDeviceId(deviceId)
              .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
              .setConfig(forwardingConfig)
              .build()
          )
          for (batch in entryBatches) {
            p4runtime.write(writeRequest(deviceId, batch))
          }
        }
      }
      val populatedHeap = usedHeapAfterGc()

      println()
      println("Multi-device scale benchmark")
      println("pipeline: sai_middleblock")
      println("devices: $deviceCount")
      println("ipv6_routes/device: $entriesPerDevice")
      println("total_table_entries/device: ${entryBatches.sumOf { it.size }}")
      println("create_ms: $createMs")
      println("populate_ms: $populateMs")
      println("idle_heap_mb: ${bytesToMiB(idleHeap - baselineHeap)}")
      println("idle_heap_bytes_per_device: ${(idleHeap - baselineHeap) / deviceCount}")
      println("populated_heap_mb: ${bytesToMiB(populatedHeap - baselineHeap)}")
      println("populated_heap_bytes_per_device: ${(populatedHeap - baselineHeap) / deviceCount}")
    } finally {
      registry.close()
    }
  }

  private fun writeRequest(deviceId: Long, entities: List<Entity>): WriteRequest =
    WriteRequest.newBuilder()
      .setDeviceId(deviceId)
      .apply {
        for (entity in entities) {
          addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(entity))
        }
      }
      .build()

  private fun intProperty(name: String, default: Int): Int =
    System.getProperty(name)?.toIntOrNull() ?: default

  private suspend fun elapsedMillis(block: suspend () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000
  }

  private fun usedHeapAfterGc(): Long {
    repeat(3) {
      System.gc()
      Thread.sleep(100)
    }
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
  }

  private fun bytesToMiB(bytes: Long): Long = bytes / (1024 * 1024)

  private fun saiEntries(config: PipelineConfig, routeCount: Int): List<Entity> {
    if (routeCount == 0) return emptyList()
    return buildList {
      add(buildVrfEntry(config))
      add(buildRouterInterfaceEntry(config))
      add(buildNeighborEntry(config))
      add(buildNexthopEntry(config))
      for (index in 0 until routeCount) {
        add(buildIpv6RouteEntry(config, index))
      }
    }
  }

  private fun buildVrfEntry(config: PipelineConfig): Entity {
    val table = findTable(config, "vrf_table")
    val action = findAction(config, "no_action")
    return buildEntry(table, action, matches = listOf(exactMatch(table, "vrf_id", VRF_ID)))
  }

  private fun buildRouterInterfaceEntry(config: PipelineConfig): Entity {
    val table = findTable(config, "router_interface_table")
    val action = findAction(config, "set_port_and_src_mac")
    return buildEntry(
      table,
      action,
      matches = listOf(exactMatch(table, "router_interface_id", ROUTER_INTERFACE_ID)),
      params =
        listOf(
          stringParam(action, "port", PORT_ID),
          bytesParam(action, "src_mac", byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)),
        ),
    )
  }

  private fun buildNeighborEntry(config: PipelineConfig): Entity {
    val table = findTable(config, "neighbor_table")
    val action = findAction(config, "set_dst_mac")
    return buildEntry(
      table,
      action,
      matches =
        listOf(
          exactMatch(table, "router_interface_id", ROUTER_INTERFACE_ID),
          exactMatch(table, "neighbor_id", NEIGHBOR_ID),
        ),
      params =
        listOf(
          bytesParam(
            action,
            "dst_mac",
            byteArrayOf(
              0x00,
              0xAA.toByte(),
              0xBB.toByte(),
              0xCC.toByte(),
              0xDD.toByte(),
              0xEE.toByte(),
            ),
          )
        ),
    )
  }

  private fun buildNexthopEntry(config: PipelineConfig): Entity {
    val table = findTable(config, "nexthop_table")
    val action = findAction(config, "set_ip_nexthop")
    return buildEntry(
      table,
      action,
      matches = listOf(exactMatch(table, "nexthop_id", NEXTHOP_ID)),
      params =
        listOf(
          stringParam(action, "router_interface_id", ROUTER_INTERFACE_ID),
          bytesParam(action, "neighbor_id", NEIGHBOR_ID),
        ),
    )
  }

  private fun buildIpv6RouteEntry(config: PipelineConfig, index: Int): Entity {
    val table = findTable(config, "ipv6_table")
    val action = findAction(config, "set_nexthop_id")
    return buildEntry(
      table,
      action,
      matches =
        listOf(
          exactMatch(table, "vrf_id", VRF_ID),
          lpmMatch(table, "ipv6_dst", ipv6Address(index), prefixLen = 128),
        ),
      params = listOf(stringParam(action, "nexthop_id", NEXTHOP_ID)),
    )
  }

  private fun buildEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    matches: List<FieldMatch>,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
  ): Entity =
    Entity.newBuilder()
      .setTableEntry(
        TableEntry.newBuilder()
          .setTableId(table.preamble.id)
          .addAllMatch(matches)
          .setAction(
            p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(
                p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                  .setActionId(action.preamble.id)
                  .addAllParams(params)
              )
          )
      )
      .build()

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: String,
  ): FieldMatch = exactMatch(table, fieldName, value.toByteArray(Charsets.UTF_8))

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value)))
      .build()

  private fun lpmMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
    prefixLen: Int,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setLpm(
        FieldMatch.LPM.newBuilder().setValue(ByteString.copyFrom(value)).setPrefixLen(prefixLen)
      )
      .build()

  private fun stringParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: String,
  ): p4.v1.P4RuntimeOuterClass.Action.Param =
    p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFromUtf8(value))
      .build()

  private fun bytesParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: ByteArray,
  ): p4.v1.P4RuntimeOuterClass.Action.Param =
    p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFrom(value))
      .build()

  private fun findTable(config: PipelineConfig, alias: String): P4InfoOuterClass.Table =
    config.p4Info.tablesList.find { it.preamble.alias == alias }
      ?: error("table '$alias' not found")

  private fun findAction(config: PipelineConfig, alias: String): P4InfoOuterClass.Action =
    config.p4Info.actionsList.find { it.preamble.alias == alias }
      ?: error("action '$alias' not found")

  private fun matchFieldId(table: P4InfoOuterClass.Table, name: String): Int =
    table.matchFieldsList.find { it.name == name }?.id
      ?: error("match field '${table.preamble.alias}.$name' not found")

  private fun paramId(action: P4InfoOuterClass.Action, name: String): Int =
    action.paramsList.find { it.name == name }?.id
      ?: error("action param '${action.preamble.alias}.$name' not found")

  private fun ipv6Address(index: Int): ByteArray =
    byteArrayOf(
      0x20,
      0x01,
      0x0D,
      0xB8.toByte(),
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      ((index ushr 16) and 0xFF).toByte(),
      ((index ushr 8) and 0xFF).toByte(),
      0,
      (index and 0xFF).toByte(),
    )

  companion object {
    private const val MAX_IPV6_ROUTES_PER_DEVICE = 1 shl 24
    private const val VRF_ID = "benchmark-vrf"
    private const val ROUTER_INTERFACE_ID = "benchmark-rif"
    private const val NEXTHOP_ID = "benchmark-nhop"
    private const val PORT_ID = "Ethernet0"
    private val NEIGHBOR_ID = ByteArray(16) { if (it == 15) 1 else 0 }
  }
}
