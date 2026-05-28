package fourward.simulator

import fourward.OutputPacket
import fourward.PipelineConfig
import fourward.TraceTree
import java.util.concurrent.atomic.AtomicReference

/**
 * Result of processing a single packet through the pipeline.
 *
 * [possibleOutcomes] captures both kinds of nondeterminism in the trace tree:
 * - **Parallel forks** (clone, multicast, resubmit, recirculate): all branches execute
 *   simultaneously, so their outputs are combined within each possible outcome.
 * - **Alternative forks** (action selector): exactly one branch executes at runtime, so each branch
 *   produces a separate possible outcome.
 *
 * Programs with no alternative forks have exactly one possible outcome. Programs with action
 * selectors have one possible outcome per alternative (Cartesian product when nested inside
 * parallel forks).
 *
 * Decouples the simulator from the gRPC wire format ([fourward.InjectPacketResponse]). Each RPC
 * method builds its own wire proto from this data class.
 */
data class ProcessPacketResult(
  val trace: TraceTree,
  val possibleOutcomes: List<List<OutputPacket>>,
  val forwardingSnapshot: TableStore.ForwardingSnapshot,
)

/**
 * The top-level simulator state machine.
 *
 * Holds the loaded pipeline configuration and all mutable data-plane state (table entries,
 * counter/meter/register values).
 *
 * Public methods: [loadPipeline], [processPacket], [writeEntry], [readEntries]. These use natural
 * Kotlin error handling (exceptions, sealed results).
 *
 * One [Simulator] instance runs for the lifetime of the process. [processPacket] is safe to call
 * concurrently from any number of threads — it reads the loaded pipeline atomically (one snapshot,
 * no tearing across reload) and the published forwarding snapshot from [TableStore]. Control-plane
 * mutations ([loadPipeline], [writeEntry]) must be serialized by the caller (the P4Runtime layer
 * does this via its write mutex), but they do not require packet processing to be quiesced.
 */
class Simulator : TableDataReader {

  /**
   * Atomically published bundle of "the currently loaded pipeline". Includes the [TableStore] so
   * `(architecture, tableStore)` publish as one snapshot — no torn pair across reload.
   */
  private data class LoadedPipeline(
    val config: PipelineConfig,
    val architecture: Architecture,
    val tableStore: TableStore,
  )

  // Single source of truth for "the loaded pipeline". Published via AtomicReference so all
  // readers (processPacket, writeEntry, snapshot, ...) observe the architecture and tableStore
  // as one consistent pair, even mid-reload. JMM happens-before across the .set() carries the
  // prior tableStore.loadMappings writes along with the publish.
  private val current = AtomicReference<LoadedPipeline?>(null)

  /** Throws if no pipeline is loaded; otherwise returns the current snapshot. */
  private fun loaded(): LoadedPipeline = checkNotNull(current.get()) { "no pipeline loaded" }

  /**
   * Installs a compiled P4 program. Must be called before [processPacket].
   *
   * Replaces the current program and clears all table entries.
   *
   * **Concurrency:** single-writer. Callers must serialize this against other [loadPipeline],
   * [loadPipelinePreservingEntries], and [writeEntry] calls (P4Runtime does this via its write
   * mutex). Concurrent [processPacket] callers are safe — they observe either the old or the new
   * pipeline as one atomic `(architecture, tableStore)` snapshot, never a torn mix.
   *
   * @param dropPortOverride overrides the v1model drop port. When null (default), derived from
   *   `standard_metadata` port width: `2^N - 1` (511 for 9-bit ports).
   * @throws IllegalArgumentException if the architecture is unsupported.
   */
  fun loadPipeline(config: PipelineConfig, dropPortOverride: Int? = null) {
    val tableStore = TableStore()
    tableStore.loadMappings(config.p4Info, config.device)
    val behavioral = config.device.behavioral
    val architecture =
      when (val archName = behavioral.architecture.name) {
        "v1model" -> V1ModelArchitecture(behavioral, dropPortOverride)
        "psa" -> PSAArchitecture(behavioral)
        "pna" -> PNAArchitecture(behavioral)
        else -> throw IllegalArgumentException("unsupported architecture: $archName")
      }
    current.set(LoadedPipeline(config, architecture, tableStore))
  }

  /**
   * Like [loadPipeline], but preserves forwarding state for tables whose schema is unchanged.
   *
   * Used by RECONCILE_AND_COMMIT. Snapshots the current write-state, loads the new pipeline (which
   * starts with a fresh [TableStore]), then restores entries for tables that exist in both
   * pipelines. The caller must verify schema compatibility before calling this.
   *
   * **Concurrency:** same as [loadPipeline] — single-writer.
   */
  fun loadPipelinePreservingEntries(config: PipelineConfig, dropPortOverride: Int? = null) {
    val old = loaded()
    val oldSnapshot = old.tableStore.snapshot()
    val oldAliasByName = old.tableStore.tableAliasByName
    loadPipeline(config, dropPortOverride)
    val newTableStore = loaded().tableStore
    newTableStore.restoreTableEntries(oldSnapshot.forwarding, oldAliasByName)
    newTableStore.publishSnapshot()
  }

  /**
   * Processes a single packet through the pipeline.
   *
   * **Concurrency:** safe to call concurrently from any number of threads. Reads the loaded
   * pipeline as one atomic snapshot; reads forwarding state from [TableStore]'s published snapshot.
   * No locks on the hot path.
   *
   * @throws IllegalStateException if no pipeline is loaded.
   */
  fun processPacket(
    ingressPort: Int,
    payload: ByteArray,
    payloadBitLength: Int = payload.size * Byte.SIZE_BITS,
  ): ProcessPacketResult {
    val loaded = loaded()
    require(payloadBitLength in 0..payload.size * Byte.SIZE_BITS) {
      "payloadBitLength must be between 0 and ${payload.size * Byte.SIZE_BITS}, " +
        "got $payloadBitLength"
    }
    // Pin the forwarding snapshot before processing so the reproducer's entity extraction
    // uses the exact same state the packet saw — no race with concurrent publishSnapshot().
    val snapshot = loaded.tableStore.snapshot

    val result =
      loaded.architecture.processPacket(
        ingressPort = ingressPort.toUInt(),
        payload = payload,
        tableStore = loaded.tableStore,
        payloadBitLength = payloadBitLength,
      )

    // Output packets are extracted from trace tree leaves — the tree is the single source
    // of truth for packet outcomes. Parallel forks (clone, multicast) combine outputs within
    // each world; alternative forks (action selector) produce separate possible worlds.
    val trace = result.trace
    return ProcessPacketResult(
      trace = trace,
      possibleOutcomes = collectPossibleOutcomes(trace),
      forwardingSnapshot = snapshot,
    )
  }

  /**
   * Writes a table entry (insert, modify, or delete).
   *
   * **Concurrency:** single-writer. Callers must serialize this against other [writeEntry] and
   * [loadPipeline] calls (P4Runtime does this via its write mutex). [TableStore.write] mutates
   * write-state; visibility to [processPacket] requires a subsequent [publishSnapshot].
   *
   * @throws IllegalStateException if no pipeline is loaded.
   */
  fun writeEntry(update: p4.v1.P4RuntimeOuterClass.Update): WriteResult =
    loaded().tableStore.write(update)

  /** The pipeline config from the currently loaded pipeline, or null if none is loaded. */
  val pipelineConfig: PipelineConfig?
    get() = current.get()?.config

  /** The table store from the currently loaded pipeline, or null if none is loaded. */
  val tableStore: TableStore?
    get() = current.get()?.tableStore

  /** Captures a checkpoint of all mutable state for rollback. */
  fun snapshot(): TableStore.RollbackCheckpoint = loaded().tableStore.snapshot()

  /** Restores all mutable state to a previously captured checkpoint. */
  fun restore(checkpoint: TableStore.RollbackCheckpoint) = loaded().tableStore.restore(checkpoint)

  /** Publishes the current write-state as a new immutable snapshot for data-plane threads. */
  fun publishSnapshot() = loaded().tableStore.publishSnapshot()

  /**
   * Returns true if the table with p4info [tableId] has an entry with match field [fieldId] equal
   * to [value].
   *
   * Used by `@refers_to` referential integrity validation.
   */
  fun hasTableEntryWithFieldValue(
    tableId: Int,
    fieldId: Int,
    value: com.google.protobuf.ByteString,
  ): Boolean = loaded().tableStore.hasEntryWithFieldValue(tableId, fieldId, value)

  /** Returns true if a multicast group with [groupId] exists in the PRE. */
  fun hasMulticastGroup(groupId: Int): Boolean =
    loaded().tableStore.getMulticastGroup(groupId) != null

  // -------------------------------------------------------------------------
  // Raw data accessors (used by the P4Runtime layer's EntityReader)
  // -------------------------------------------------------------------------

  /** ID→name maps populated by [loadPipeline], for building P4Runtime Entity protos. */
  val tableNameById: Map<Int, String>
    get() = loaded().tableStore.tableNameById

  val actionNameById: Map<Int, String>
    get() = loaded().tableStore.actionNameById

  /** P4info alias names of tables that have at least one installed entry. */
  val populatedTableAliases: Set<String>
    get() = loaded().tableStore.populatedTableAliases()

  override fun getTableEntries(tableName: String) = loaded().tableStore.getTableEntries(tableName)

  override fun getDefaultAction(tableName: String) = loaded().tableStore.getDefaultAction(tableName)

  override fun isDefaultModified(tableName: String) =
    loaded().tableStore.isDefaultModified(tableName)

  override fun getDirectCounterData(entry: p4.v1.P4RuntimeOuterClass.TableEntry) =
    loaded().tableStore.getDirectCounterData(entry)

  override fun getDirectMeterData(entry: p4.v1.P4RuntimeOuterClass.TableEntry) =
    loaded().tableStore.getDirectMeterData(entry)

  override fun hasDirectCounter(tableName: String) = loaded().tableStore.hasDirectCounter(tableName)

  override fun hasDirectMeter(tableName: String) = loaded().tableStore.hasDirectMeter(tableName)

  /**
   * Reads non-table entities matching the given filters.
   *
   * Table entries are handled by [fourward.grpc.EntityReader] in the P4Runtime layer; this method
   * handles all other entity types.
   */
  fun readEntries(
    filters: List<p4.v1.P4RuntimeOuterClass.Entity>
  ): List<p4.v1.P4RuntimeOuterClass.Entity> {
    val tableStore = loaded().tableStore
    return filters.flatMap { entity ->
      when (entity.entityCase) {
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.TABLE_ENTRY -> emptyList()
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_MEMBER ->
          tableStore.readProfileMembers(entity.actionProfileMember)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_GROUP ->
          tableStore.readProfileGroups(entity.actionProfileGroup)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.REGISTER_ENTRY ->
          tableStore.readRegisterEntries(entity.registerEntry)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.COUNTER_ENTRY ->
          tableStore.readCounterEntries(entity.counterEntry)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.METER_ENTRY ->
          tableStore.readMeterEntries(entity.meterEntry)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.DIRECT_COUNTER_ENTRY ->
          tableStore.readDirectCounterEntries(entity.directCounterEntry)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.DIRECT_METER_ENTRY ->
          tableStore.readDirectMeterEntries(entity.directMeterEntry)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY ->
          tableStore.readPreEntries(entity.packetReplicationEngineEntry)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.VALUE_SET_ENTRY ->
          tableStore.readValueSetEntries(entity.valueSetEntry)
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.EXTERN_ENTRY,
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.DIGEST_ENTRY,
        p4.v1.P4RuntimeOuterClass.Entity.EntityCase.ENTITY_NOT_SET,
        null -> error("unsupported entity type for read: ${entity.entityCase}")
      }
    }
  }

  /** Returns the short p4info alias for a behavioral name (table or action), or the name itself. */
  fun displayName(behavioralName: String): String = loaded().tableStore.displayName(behavioralName)
}

/**
 * Collects all possible outcome sets from a trace tree, respecting fork semantics.
 * - **Parallel forks** (clone, multicast, resubmit, recirculate): outputs from all branches are
 *   combined within each possible world (Cartesian product of branch outcomes).
 * - **Alternative forks** (action selector): each branch is a separate possible world; outcomes are
 *   concatenated (union of branch outcomes).
 * - **Leaf (output):** one possible world with one packet.
 * - **Leaf (drop):** one possible world with no packets.
 *
 * Returns a non-empty list. Each inner list is one complete set of output packets that could result
 * from a single real execution.
 */
fun collectPossibleOutcomes(tree: TraceTree): List<List<OutputPacket>> {
  when (tree.outcomeCase) {
    TraceTree.OutcomeCase.PACKET_OUTCOME ->
      return if (tree.packetOutcome.hasOutput()) {
        listOf(listOf(tree.packetOutcome.output))
      } else {
        listOf(emptyList())
      }
    TraceTree.OutcomeCase.OUTCOME_NOT_SET,
    null -> return listOf(emptyList())
    TraceTree.OutcomeCase.FORK_OUTCOME -> {} // fall through to fork handling below
  }
  val fork = tree.forkOutcome
  val branchOutcomes = fork.branchesList.map { collectPossibleOutcomes(it.subtree) }
  if (branchOutcomes.isEmpty()) return listOf(emptyList())
  return when (forkModeOf(fork.reason)) {
    // Alternative: each branch adds its worlds to the result.
    ForkMode.ALTERNATIVE -> branchOutcomes.flatten()
    // Parallel: Cartesian product across branches — for each combination of one world per
    // branch, concatenate the packets.
    ForkMode.PARALLEL ->
      branchOutcomes.reduce { acc, next ->
        acc.flatMap { world -> next.map { branchWorld -> world + branchWorld } }
      }
  }
}
