package fourward.grpc

import fourward.simulator.TableDataReader
import fourward.simulator.sameKey
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Assembles P4Runtime [Entity] protos for table entry reads.
 *
 * Owns the p4info ID↔name mappings needed for proto construction; delegates to a [TableDataReader]
 * for raw data-plane state (entries, default actions, direct counter/meter data).
 *
 * Created once per pipeline load and bundled into [P4RuntimeService]'s `PipelineState`.
 */
class EntityReader
private constructor(
  private val tableNameById: Map<Int, String>,
  private val tableIdByName: Map<String, Int>,
  private val actionIdByName: Map<String, Int>,
  private val noActionId: Int,
) {

  /**
   * Returns table entries as [Entity] protos, filtered by [filter].
   * - `table_id=0`, no match fields: wildcard — all entries from all tables.
   * - `table_id=N`, no match fields: all entries from table N.
   * - `table_id=N` with match fields: single entry matching the key (P4Runtime spec §11.1).
   */
  fun readTableEntities(filter: TableEntry, tables: TableDataReader): List<Entity> {
    val tableIds = if (filter.tableId == 0) tableIdByName.values else listOf(filter.tableId)
    val hasMatchFilter = filter.matchCount > 0
    val result = mutableListOf<Entity>()

    for (tableId in tableIds) {
      val tableName = tableNameById[tableId] ?: continue

      // P4Runtime spec §9.1.6: is_default_action selects which entries to return.
      // false (default) → non-default entries only. true → default entry only.
      if (filter.isDefaultAction) {
        if (!hasMatchFilter && tables.isDefaultModified(tableName)) {
          buildDefaultEntryEntity(tableName, tableId, tables)?.let { result.add(it) }
        }
      } else {
        val hasDirectCounter = tables.hasDirectCounter(tableName)
        val hasDirectMeter = tables.hasDirectMeter(tableName)
        val entries = tables.getTableEntries(tableName)
        val matched =
          if (hasMatchFilter) listOfNotNull(entries.find { it.sameKey(filter) }) else entries
        for (entry in matched) {
          result.add(buildTableEntryEntity(entry, hasDirectCounter, hasDirectMeter, tables))
        }
      }
    }
    return result
  }

  /** Wraps a [TableEntry] in an [Entity], attaching direct counter/meter data if applicable. */
  private fun buildTableEntryEntity(
    entry: TableEntry,
    hasDirectCounter: Boolean,
    hasDirectMeter: Boolean,
    tables: TableDataReader,
  ): Entity {
    if (!hasDirectCounter && !hasDirectMeter) {
      return Entity.newBuilder().setTableEntry(entry).build()
    }
    // P4Runtime spec §9.1: table entry reads include direct counter/meter data.
    // Counters default to zeros (always present); meters have no meaningful default (omitted).
    val builder = entry.toBuilder()
    if (hasDirectCounter) {
      builder.counterData =
        tables.getDirectCounterData(entry) ?: P4RuntimeOuterClass.CounterData.getDefaultInstance()
    }
    if (hasDirectMeter) {
      tables.getDirectMeterData(entry)?.let { builder.meterConfig = it }
    }
    return Entity.newBuilder().setTableEntry(builder).build()
  }

  /** Builds the default entry [Entity] for a table, or null if no default action is resolvable. */
  private fun buildDefaultEntryEntity(
    tableName: String,
    tableId: Int,
    tables: TableDataReader,
  ): Entity? {
    val default = tables.getDefaultAction(tableName)
    val actionId = default?.let { actionIdByName[it.name] } ?: noActionId
    if (actionId == 0) return null
    val actionBuilder = P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId)
    default?.params?.forEach { actionBuilder.addParams(it) }
    return Entity.newBuilder()
      .setTableEntry(
        TableEntry.newBuilder()
          .setTableId(tableId)
          .setIsDefaultAction(true)
          .setAction(P4RuntimeOuterClass.TableAction.newBuilder().setAction(actionBuilder))
      )
      .build()
  }

  companion object {
    /** Placeholder for [PipelineState] before the simulator has loaded the pipeline. */
    val EMPTY = EntityReader(emptyMap(), emptyMap(), emptyMap(), 0)

    /**
     * Builds an [EntityReader] from p4info and name resolution maps.
     *
     * Called after pipeline load so the name maps are already populated.
     */
    fun create(
      p4info: P4Info,
      tableNameById: Map<Int, String>,
      actionNameById: Map<Int, String>,
    ): EntityReader {
      val tableIdByName = tableNameById.entries.associate { (id, name) -> name to id }
      val actionIdByName = actionNameById.entries.associate { (id, name) -> name to id }
      val noActionId = p4info.actionsList.find { it.preamble.name == "NoAction" }?.preamble?.id ?: 0
      return EntityReader(tableNameById, tableIdByName, actionIdByName, noActionId)
    }
  }
}
