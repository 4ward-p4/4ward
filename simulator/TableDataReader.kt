package fourward.simulator

import org.p4.v1.CounterData
import org.p4.v1.MeterConfig
import org.p4.v1.TableEntry

/**
 * Read-only view of table data-plane state.
 *
 * Used by the P4Runtime layer's [fourward.grpc.EntityReader] to assemble table entry Entity protos
 * without depending on the full [Simulator].
 */
interface TableDataReader {
  fun getTableEntries(tableName: String): List<TableEntry>

  fun getDefaultAction(tableName: String): DefaultAction?

  /** True if the default action for [tableName] was explicitly modified via P4Runtime Write. */
  fun isDefaultModified(tableName: String): Boolean

  fun getDirectCounterData(entry: TableEntry): CounterData?

  fun getDirectMeterData(entry: TableEntry): MeterConfig?

  fun hasDirectCounter(tableName: String): Boolean

  fun hasDirectMeter(tableName: String): Boolean
}
