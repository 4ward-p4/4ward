package fourward.grpc

import com.google.protobuf.ByteString
import fourward.simulator.DefaultAction
import fourward.simulator.TableDataReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Unit tests for [EntityReader]'s table entry → Entity proto assembly.
 *
 * Uses a [StubTableData] instead of a real [fourward.simulator.Simulator], so tests exercise only
 * the proto assembly and filtering logic — no pipeline loading or architecture setup needed.
 */
class EntityReaderTest {

  private val stub = StubTableData()
  private lateinit var reader: EntityReader

  @Before
  fun setUp() {
    stub.defaults[TABLE_A_NAME] = DefaultAction("drop")
    reader = buildReader()
  }

  // ---------------------------------------------------------------------------
  // Wildcard reads
  // ---------------------------------------------------------------------------

  @Test
  fun `wildcard read with no entries and no modified defaults returns empty`() {
    val result = readWildcard()
    assertTrue("unmodified defaults should not appear in reads", result.isEmpty())
  }

  // §9.1.6: wildcard read with is_default_action=false (default) excludes default entries.
  @Test
  fun `wildcard read excludes modified default entries`() {
    stub.modifiedDefaults.add(TABLE_A_NAME)
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    val result = readWildcard()
    assertEquals(1, result.size)
    assertTrue("wildcard should not include defaults", !result[0].tableEntry.isDefaultAction)
  }

  @Test
  fun `wildcard read returns entries from all tables`() {
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    stub.addEntry(TABLE_B_NAME, TABLE_B_ID, byteArrayOf(2))

    val result = readWildcard()
    assertEquals(2, result.size)
    val tableIds = result.map { it.tableEntry.tableId }.toSet()
    assertEquals(setOf(TABLE_A_ID, TABLE_B_ID), tableIds)
  }

  // §9.1.6: is_default_action=true returns only the default entry.
  @Test
  fun `wildcard read with is_default_action returns modified defaults`() {
    stub.modifiedDefaults.add(TABLE_A_NAME)
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    val result = readDefaultWildcard()
    assertEquals(1, result.size)
    assertTrue(result[0].tableEntry.isDefaultAction)
  }

  @Test
  fun `wildcard read with is_default_action excludes unmodified defaults`() {
    val result = readDefaultWildcard()
    assertTrue(result.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Per-table reads
  // ---------------------------------------------------------------------------

  @Test
  fun `per-table read returns only entries from that table`() {
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    stub.addEntry(TABLE_B_NAME, TABLE_B_ID, byteArrayOf(2))

    val result = readTable(TABLE_A_ID)
    assertEquals(1, result.size)
    assertEquals(TABLE_A_ID, result[0].tableEntry.tableId)
  }

  // §9.1.6: per-table read with is_default_action=false excludes defaults.
  @Test
  fun `per-table read excludes modified default entry`() {
    stub.modifiedDefaults.add(TABLE_A_NAME)
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    val result = readTable(TABLE_A_ID)
    assertEquals(1, result.size)
    assertTrue("per-table read should not include defaults", !result[0].tableEntry.isDefaultAction)
  }

  @Test
  fun `per-table read with is_default_action returns modified default`() {
    stub.modifiedDefaults.add(TABLE_A_NAME)
    val result = readDefaultTable(TABLE_A_ID)
    assertEquals(1, result.size)
    assertTrue(result[0].tableEntry.isDefaultAction)
    assertEquals(TABLE_A_ID, result[0].tableEntry.tableId)
    assertEquals(DROP_ACTION_ID, result[0].tableEntry.action.action.actionId)
  }

  @Test
  fun `per-table read with is_default_action excludes unmodified default`() {
    val result = readDefaultTable(TABLE_A_ID)
    assertTrue(result.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Per-entry (match filter) reads
  // ---------------------------------------------------------------------------

  @Test
  fun `match filter returns only the matching entry`() {
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(2))

    val filter = entryFilter(TABLE_A_ID, byteArrayOf(1))
    val result = reader.readTableEntities(filter, stub)
    assertEquals(1, result.size)
    assertEquals(byteArrayOf(1).toList(), matchValue(result[0]))
  }

  @Test
  fun `match filter excludes default entry`() {
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))

    val filter = entryFilter(TABLE_A_ID, byteArrayOf(1))
    val result = reader.readTableEntities(filter, stub)
    assertEquals(1, result.size)
    assertTrue("should not include default", !result[0].tableEntry.isDefaultAction)
  }

  @Test
  fun `match filter with no match returns empty`() {
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))

    val filter = entryFilter(TABLE_A_ID, byteArrayOf(99))
    val result = reader.readTableEntities(filter, stub)
    assertTrue(result.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Default entry assembly
  // ---------------------------------------------------------------------------

  @Test
  fun `default entry has correct table_id and is_default_action`() {
    stub.modifiedDefaults.add(TABLE_A_NAME)
    val result = readDefaultTable(TABLE_A_ID)
    assertEquals(1, result.size)
    val entry = result[0].tableEntry
    assertTrue(entry.isDefaultAction)
    assertEquals(TABLE_A_ID, entry.tableId)
    assertEquals(DROP_ACTION_ID, entry.action.action.actionId)
  }

  @Test
  fun `default entry includes action parameters`() {
    val params =
      listOf(
        P4RuntimeOuterClass.Action.Param.newBuilder().setParamId(1).setValue(bytes(42)).build(),
        P4RuntimeOuterClass.Action.Param.newBuilder().setParamId(2).setValue(bytes(7)).build(),
      )
    stub.defaults[TABLE_A_NAME] = DefaultAction("drop", params)
    stub.modifiedDefaults.add(TABLE_A_NAME)

    val result = readDefaultTable(TABLE_A_ID)
    assertEquals(1, result.size)
    val action = result[0].tableEntry.action.action
    assertEquals(DROP_ACTION_ID, action.actionId)
    assertEquals(2, action.paramsCount)
    assertEquals(1, action.getParams(0).paramId)
    assertEquals(bytes(42), action.getParams(0).value)
    assertEquals(2, action.getParams(1).paramId)
    assertEquals(bytes(7), action.getParams(1).value)
  }

  @Test
  fun `modified default with no explicit action uses NoAction`() {
    stub.modifiedDefaults.add(TABLE_B_NAME)
    val result = readDefaultTable(TABLE_B_ID)
    assertEquals(1, result.size)
    assertEquals(NO_ACTION_ID, result[0].tableEntry.action.action.actionId)
  }

  // ---------------------------------------------------------------------------
  // Direct counter/meter data
  // ---------------------------------------------------------------------------

  @Test
  fun `entry in table with direct counter includes counter data`() {
    stub.directCounters += TABLE_A_NAME
    val entry = stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    stub.counterData[entry] =
      P4RuntimeOuterClass.CounterData.newBuilder().setByteCount(100).setPacketCount(5).build()

    val result = readTable(TABLE_A_ID)
    assertEquals(1, result.size)
    assertTrue(result[0].tableEntry.hasCounterData())
    assertEquals(100, result[0].tableEntry.counterData.byteCount)
    assertEquals(5, result[0].tableEntry.counterData.packetCount)
  }

  @Test
  fun `entry in table with direct meter includes meter config`() {
    stub.directMeters += TABLE_A_NAME
    val entry = stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    stub.meterData[entry] =
      P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(1000).setCburst(500).build()

    val result = readTable(TABLE_A_ID)
    assertEquals(1, result.size)
    assertTrue(result[0].tableEntry.hasMeterConfig())
    assertEquals(1000, result[0].tableEntry.meterConfig.cir)
    assertEquals(500, result[0].tableEntry.meterConfig.cburst)
  }

  @Test
  fun `default entry in table with direct counter includes counter data`() {
    stub.directCounters += TABLE_A_NAME
    stub.modifiedDefaults.add(TABLE_A_NAME)
    stub.counterData[defaultEntry(TABLE_A_ID)] =
      P4RuntimeOuterClass.CounterData.newBuilder().setByteCount(100).setPacketCount(5).build()

    val result = readDefaultTable(TABLE_A_ID)

    assertEquals(1, result.size)
    assertTrue(result[0].tableEntry.hasCounterData())
    assertEquals(100, result[0].tableEntry.counterData.byteCount)
    assertEquals(5, result[0].tableEntry.counterData.packetCount)
  }

  @Test
  fun `default entry in table with direct meter includes meter config`() {
    stub.directMeters += TABLE_A_NAME
    stub.modifiedDefaults.add(TABLE_A_NAME)
    stub.meterData[defaultEntry(TABLE_A_ID)] =
      P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(1000).setCburst(500).build()

    val result = readDefaultTable(TABLE_A_ID)

    assertEquals(1, result.size)
    assertTrue(result[0].tableEntry.hasMeterConfig())
    assertEquals(1000, result[0].tableEntry.meterConfig.cir)
    assertEquals(500, result[0].tableEntry.meterConfig.cburst)
  }

  @Test
  fun `entry in table without direct counter has no counter data`() {
    stub.addEntry(TABLE_A_NAME, TABLE_A_ID, byteArrayOf(1))
    val result = readTable(TABLE_A_ID)
    assertEquals(1, result.size)
    assertTrue("should not have counter data", !result[0].tableEntry.hasCounterData())
  }

  // ---------------------------------------------------------------------------
  // Unknown table ID
  // ---------------------------------------------------------------------------

  @Test
  fun `per-table read for unknown table returns empty`() {
    val filter = TableEntry.newBuilder().setTableId(99999).build()
    assertTrue(reader.readTableEntities(filter, stub).isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun readWildcard(): List<Entity> =
    reader.readTableEntities(TableEntry.getDefaultInstance(), stub)

  private fun readDefaultWildcard(): List<Entity> =
    reader.readTableEntities(TableEntry.newBuilder().setIsDefaultAction(true).build(), stub)

  private fun readTable(tableId: Int): List<Entity> =
    reader.readTableEntities(TableEntry.newBuilder().setTableId(tableId).build(), stub)

  private fun readDefaultTable(tableId: Int): List<Entity> =
    reader.readTableEntities(
      TableEntry.newBuilder().setTableId(tableId).setIsDefaultAction(true).build(),
      stub,
    )

  private fun entryFilter(tableId: Int, matchValue: ByteArray): TableEntry =
    TableEntry.newBuilder()
      .setTableId(tableId)
      .addMatch(
        P4RuntimeOuterClass.FieldMatch.newBuilder()
          .setFieldId(MATCH_FIELD_ID)
          .setExact(
            P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
              .setValue(ByteString.copyFrom(matchValue))
          )
      )
      .build()

  private fun defaultEntry(tableId: Int): TableEntry =
    TableEntry.newBuilder().setTableId(tableId).setIsDefaultAction(true).build()

  private fun matchValue(entity: Entity): List<Byte> =
    entity.tableEntry.getMatch(0).exact.value.toByteArray().toList()

  private fun bytes(vararg values: Int): ByteString =
    ByteString.copyFrom(values.map { it.toByte() }.toByteArray())

  private fun buildReader(): EntityReader {
    val tableNameById = mapOf(TABLE_A_ID to TABLE_A_NAME, TABLE_B_ID to TABLE_B_NAME)
    val actionNameById = mapOf(NO_ACTION_ID to "NoAction", DROP_ACTION_ID to "drop")
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(
              P4InfoOuterClass.Preamble.newBuilder()
                .setId(NO_ACTION_ID)
                .setName("NoAction")
                .setAlias("NoAction")
            )
        )
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(
              P4InfoOuterClass.Preamble.newBuilder()
                .setId(DROP_ACTION_ID)
                .setName("drop")
                .setAlias("drop")
            )
        )
        .build()
    return EntityReader.create(p4info, tableNameById, actionNameById)
  }

  // ---------------------------------------------------------------------------
  // Stub TableDataReader
  // ---------------------------------------------------------------------------

  private class StubTableData : TableDataReader {
    val entries = mutableMapOf<String, MutableList<TableEntry>>()
    val defaults = mutableMapOf<String, DefaultAction>()
    val modifiedDefaults = mutableSetOf<String>()
    val directCounters = mutableSetOf<String>()
    val directMeters = mutableSetOf<String>()
    val counterData = mutableMapOf<TableEntry, P4RuntimeOuterClass.CounterData>()
    val meterData = mutableMapOf<TableEntry, P4RuntimeOuterClass.MeterConfig>()

    /** Adds an entry and returns it (for attaching counter/meter data). */
    fun addEntry(tableName: String, tableId: Int, matchValue: ByteArray): TableEntry {
      val entry =
        TableEntry.newBuilder()
          .setTableId(tableId)
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(MATCH_FIELD_ID)
              .setExact(
                P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                  .setValue(ByteString.copyFrom(matchValue))
              )
          )
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(DROP_ACTION_ID))
          )
          .build()
      entries.getOrPut(tableName) { mutableListOf() }.add(entry)
      return entry
    }

    override fun getTableEntries(tableName: String) = entries[tableName] ?: emptyList()

    override fun getDefaultAction(tableName: String) = defaults[tableName]

    override fun isDefaultModified(tableName: String) = tableName in modifiedDefaults

    override fun getDirectCounterData(entry: TableEntry) = counterData[entry]

    override fun getDirectMeterData(entry: TableEntry) = meterData[entry]

    override fun hasDirectCounter(tableName: String) = tableName in directCounters

    override fun hasDirectMeter(tableName: String) = tableName in directMeters
  }

  companion object {
    private const val TABLE_A_ID = 1
    private const val TABLE_A_NAME = "tableA"
    private const val TABLE_B_ID = 2
    private const val TABLE_B_NAME = "tableB"
    private const val MATCH_FIELD_ID = 1
    private const val NO_ACTION_ID = 100
    private const val DROP_ACTION_ID = 200
  }
}
