package fourward.simulator

import p4.v1.P4RuntimeOuterClass

/**
 * Read-only view of table data-plane state.
 *
 * Used by the P4Runtime layer's [fourward.grpc.EntityReader] to assemble table entry Entity protos
 * without depending on the full [Simulator].
 */
interface TableDataReader {
  fun getTableEntries(tableName: String): List<P4RuntimeOuterClass.TableEntry>

  fun getDefaultAction(tableName: String): DefaultAction?

  /** True if the default action for [tableName] was explicitly modified via P4Runtime Write. */
  fun isDefaultModified(tableName: String): Boolean

  fun getDirectCounterData(entry: P4RuntimeOuterClass.TableEntry): P4RuntimeOuterClass.CounterData?

  fun getDirectMeterData(entry: P4RuntimeOuterClass.TableEntry): P4RuntimeOuterClass.MeterConfig?

  fun hasDirectCounter(tableName: String): Boolean

  fun hasDirectMeter(tableName: String): Boolean
}
