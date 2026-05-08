package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildEthernetFrame
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.longToBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Entity

/**
 * Tests for @p4runtime_translation support.
 *
 * Uses the translated_type.p4 fixture which declares: @p4runtime_translation("test.port_id", 32)
 * type bit<9> port_id_t;
 *
 * The `forward` action takes a `port_id_t port` parameter. The p4info reports bitwidth=32 (SDN
 * width), but the dataplane type is bit<9>. The translation layer must convert between these
 * representations on Write and Read.
 *
 * The table's match field (hdr.ethernet.etherType) is bit<16> and NOT translated.
 */
class P4RuntimeTranslationTest {

  private lateinit var harness: P4RuntimeTestHarness
  private lateinit var config: PipelineConfig

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
    config = P4RuntimeTestHarness.loadConfig("e2e_tests/translated_type/translated_type.txtpb")
    harness.loadPipeline(config)
  }

  @After
  fun tearDown() {
    harness.close()
  }

  // =========================================================================
  // Write path: controller → dataplane (narrow SDN bitwidth)
  // =========================================================================

  @Test
  fun `write with translated type narrows to dataplane bitwidth`() {
    // Controller sends port=1 as 32-bit value (4 bytes per p4info bitwidth).
    val entry = buildEntry(portValue = byteArrayOf(0, 0, 0, 1))
    harness.installEntry(entry)

    // Read it back — the simulator should have stored it in dataplane width,
    // and the read path should widen it back to SDN width (32-bit).
    val entities = harness.readRegularEntries()
    assertEquals("expected one entity", 1, entities.size)

    val readAction = entities[0].tableEntry.action.action
    val portParam = readAction.paramsList.find { it.paramId == 1 }!!

    // The returned value must be in SDN (32-bit) representation.
    // P4Runtime canonical form: minimum-width unsigned big-endian, so port=1 → 0x01.
    // But the key point is the value must represent 1, not be truncated or garbled.
    val portValue =
      portParam.value.toByteArray().fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    assertEquals("port value should round-trip correctly", 1L, portValue)
  }

  @Test
  fun `write with translated type handles large SDN values`() {
    // Port 256 — fits in 32 bits but not in 9 bits (bit<9> max is 511).
    // The translation layer should accept this and narrow to 9 bits.
    val entry = buildEntry(portValue = byteArrayOf(0, 0, 1, 0))
    harness.installEntry(entry)

    val entities = harness.readRegularEntries()
    assertEquals(1, entities.size)

    val portParam = entities[0].tableEntry.action.action.paramsList.find { it.paramId == 1 }!!
    val portValue =
      portParam.value.toByteArray().fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    assertEquals("port 256 should round-trip through translation", 256L, portValue)
  }

  // =========================================================================
  // Read path: dataplane → controller (widen to SDN bitwidth)
  // =========================================================================

  @Test
  fun `read returns values in SDN bitwidth`() {
    // Install entry with port=7 in SDN (32-bit) encoding.
    val entry = buildEntry(portValue = byteArrayOf(0, 0, 0, 7))
    harness.installEntry(entry)

    val entities = harness.readEntries()
    val portParam = entities[0].tableEntry.action.action.paramsList.find { it.paramId == 1 }!!

    // Read must return port=7 in a form that's valid for the SDN bitwidth (32-bit).
    val portValue =
      portParam.value.toByteArray().fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    assertEquals(7L, portValue)
  }

  @Test
  fun `non-translated match field passes through unchanged`() {
    // etherType is bit<16>, NOT translated. Values should pass through unchanged.
    val entry = buildEntry(matchValue = 0x0800, portValue = byteArrayOf(0, 0, 0, 1))
    harness.installEntry(entry)

    val entities = harness.readEntries()
    val matchField = entities[0].tableEntry.matchList.first()
    val matchBytes = matchField.exact.value.toByteArray()
    val matchValue = matchBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    assertEquals("etherType match should be unchanged", 0x0800L, matchValue)
  }

  // =========================================================================
  // End-to-end: forwarding with translated port
  // =========================================================================

  @Test
  fun `forwarding works with translated port type`() {
    // Install: etherType=0x0800 → forward(port=1), with port in 32-bit SDN encoding.
    val entry = buildEntry(matchValue = 0x0800, portValue = byteArrayOf(0, 0, 0, 1))
    harness.installEntry(entry)

    // Send an Ethernet frame with etherType=0x0800 through the simulator directly.
    // translated_type.p4 has no @controller_header, so PacketOut would not produce PacketIn.
    val payload = buildEthernetFrame(etherType = 0x0800)
    val outputs = harness.simulatePacket(ingressPort = 0, payload = payload).single().packetsList

    // SDN port 1 is auto-allocated to data-plane port 0 (first available).
    assertEquals("expected 1 output packet", 1, outputs.size)
    assertEquals("should exit on auto-allocated dp port", 0, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `forwarding to translated port 2 works`() {
    // SDN port 2 is auto-allocated to the first available data-plane value (0),
    // since no explicit translation mappings are configured.
    val entry = buildEntry(matchValue = 0x0800, portValue = byteArrayOf(0, 0, 0, 2))
    harness.installEntry(entry)

    // Verify forwarding through the simulator — no @controller_header, so no PacketIn.
    val payload = buildEthernetFrame(etherType = 0x0800)
    val outputs = harness.simulatePacket(ingressPort = 0, payload = payload).single().packetsList

    assertEquals("expected 1 output packet", 1, outputs.size)
    assertEquals(
      "egress port should be auto-allocated dp value 0",
      0,
      outputs[0].dataplaneEgressPort,
    )
  }

  // =========================================================================
  // Match field translation: port_forward table with translated match key
  // =========================================================================

  @Test
  fun `translated match field round-trips through write and read`() {
    // port_forward table matches on meta.ingress_port (port_id_t, SDN bitwidth=32).
    // Write with SDN port value 42 and read back — should get 42 back.
    val portForwardEntry =
      buildPortForwardEntry(sdnPort = 42, forwardPort = byteArrayOf(0, 0, 0, 1))
    harness.installEntry(portForwardEntry)

    val portForwardTable =
      config.p4Info.tablesList.find { it.preamble.name.contains("port_forward") }!!
    val entities = harness.readRegularTableEntries(portForwardTable.preamble.id)
    assertEquals("expected one entity in port_forward", 1, entities.size)

    val matchField = entities[0].tableEntry.matchList.first()
    val matchValue =
      matchField.exact.value.toByteArray().fold(0L) { acc, b ->
        (acc shl 8) or (b.toLong() and 0xFF)
      }
    assertEquals("match field should round-trip as SDN value", 42L, matchValue)
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private fun buildPortForwardEntry(sdnPort: Int, forwardPort: ByteArray): Entity =
    buildEntry(tableName = "port_forward", matchValue = sdnPort.toLong(), portValue = forwardPort)

  /** Builds a table entry: exact match on the first field → forward(port). */
  private fun buildEntry(
    matchValue: Long = 0x0800,
    portValue: ByteArray,
    tableName: String? = null,
  ): Entity {
    val p4info = config.p4Info
    val table =
      if (tableName != null) {
        p4info.tablesList.find { it.preamble.name.contains(tableName) }!!
      } else {
        p4info.tablesList.first()
      }
    val forwardAction = p4info.actionsList.find { it.preamble.name.contains("forward") }!!
    val matchField = table.matchFieldsList.first()

    val fieldMatch =
      p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(matchField.id)
        .setExact(
          p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
            .setValue(ByteString.copyFrom(longToBytes(matchValue, (matchField.bitwidth + 7) / 8)))
        )
        .build()

    val actionParam =
      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
        .setParamId(forwardAction.paramsList.first().id)
        .setValue(ByteString.copyFrom(portValue))
        .build()

    val tableEntry =
      p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addMatch(fieldMatch)
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(forwardAction.preamble.id)
                .addParams(actionParam)
            )
        )
        .build()

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }
}
