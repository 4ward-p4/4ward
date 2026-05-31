package fourward.simulator

import com.google.protobuf.ByteString
import fourward.BehavioralConfig
import fourward.DeviceConfig
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Cache for wide (>63-bit) ByteString→BigInteger conversions. Identity-keyed: proto ByteStrings
 * from table entries are immutable and reused, so pointer equality suffices.
 */
private val bigIntCache = java.util.IdentityHashMap<ByteString, BigInteger>()

/** Interprets a protobuf [ByteString] as an unsigned big-endian [BigInteger]. Cached for reuse. */
fun ByteString.toUnsignedBigInteger(): BigInteger =
  bigIntCache.getOrPut(this) { BigInteger(1, toByteArray()) }

/** Decode a proto ByteString as an unsigned Long. For the common case (≤8 bytes). */
private fun ByteString.toUnsignedLong(): Long {
  var v = 0L
  for (i in 0 until size()) v = (v shl 8) or (byteAt(i).toLong() and 0xFF)
  return v
}

/**
 * Tests whether a [BitVector] matches a P4Runtime [FieldMatch].
 *
 * Supports exact, ternary, LPM, range, and optional match kinds. An unset FieldMatch (no kind set)
 * acts as a wildcard (matches any value). For fields ≤ 63 bits (ports, IPs, MACs — the vast
 * majority), uses Long arithmetic with zero heap allocation. Wider fields (IPv6 at 128 bits) fall
 * back to BigInteger.
 */
fun matchesFieldMatch(bits: BitVector, match: P4RuntimeOuterClass.FieldMatch): Boolean =
  if (bits.width <= BitVector.LONG_WIDTH) matchLong(bits.longValue, bits.width, match)
  else matchBig(bits.value, bits.width, match)

private data class InlineDirectResourceWriteContext(
  val directCounterTables: Set<String>,
  val directMeterTables: Set<String>,
  val directCounterData: ConcurrentHashMap<TableEntry, AtomicLongArray>,
  val writeState: TableStore.ForwardingSnapshot,
)

private data class ResolvedTableWrite(
  val rawEntry: TableEntry,
  val effectiveEntryForValidation: TableEntry?,
  val storedEntry: TableEntry,
)

private fun applyInlineDirectResources(
  rawEntry: TableEntry,
  storedEntry: TableEntry,
  oldEntry: TableEntry?,
  tableName: String,
  context: InlineDirectResourceWriteContext,
) {
  if (tableName in context.directCounterTables && rawEntry.hasCounterData()) {
    oldEntry?.let { context.directCounterData.remove(it) }
    val data = rawEntry.counterData
    context.directCounterData[storedEntry] =
      AtomicLongArray(longArrayOf(data.packetCount, data.byteCount))
  } else if (tableName in context.directCounterTables && oldEntry != null) {
    context.directCounterData.remove(oldEntry)?.let { context.directCounterData[storedEntry] = it }
  }

  if (tableName in context.directMeterTables) {
    oldEntry?.let { context.writeState.directMeterData.remove(it) }
    if (rawEntry.hasMeterConfig()) {
      context.writeState.directMeterData[storedEntry] = rawEntry.meterConfig
    }
  }
}

private fun defaultDirectResourceEntry(tableId: Int): TableEntry =
  TableEntry.newBuilder().setTableId(tableId).setIsDefaultAction(true).build()

private fun entryWithInitialDefaultActionForReset(
  entry: TableEntry,
  tableName: String,
  initialDefaultActions: Map<String, DefaultAction>,
  actionIdByName: Map<String, Int>,
): TableEntry {
  val default = initialDefaultActions[tableName] ?: DefaultAction("NoAction")
  val actionId = actionIdByName[default.name] ?: return entry
  return entry
    .toBuilder()
    .setAction(
      P4RuntimeOuterClass.TableAction.newBuilder()
        .setAction(
          P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId).addAllParams(default.params)
        )
    )
    .build()
}

private fun resolveTableWrite(
  rawEntry: TableEntry,
  updateType: Update.Type,
  tableName: String,
  existingEntry: TableEntry?,
  initialDefaultActions: Map<String, DefaultAction>,
  actionIdByName: Map<String, Int>,
): ResolvedTableWrite {
  val effectiveEntry =
    if (
      updateType == Update.Type.MODIFY &&
        rawEntry.action.typeCase == P4RuntimeOuterClass.TableAction.TypeCase.TYPE_NOT_SET
    ) {
      if (rawEntry.isDefaultAction) {
        entryWithInitialDefaultActionForReset(
          rawEntry,
          tableName,
          initialDefaultActions,
          actionIdByName,
        )
      } else {
        existingEntry?.let { rawEntry.toBuilder().setAction(it.action).build() }
      }
    } else {
      rawEntry
    }

  val storedEntry =
    if (!rawEntry.hasCounterData() && !rawEntry.hasMeterConfig()) {
      effectiveEntry ?: rawEntry
    } else {
      // TableEntry carries direct extern data on reads, but forwarding entries are keyed only by
      // match/action data. Store direct counter and meter state in their dedicated maps.
      (effectiveEntry ?: rawEntry).toBuilder().clearCounterData().clearMeterConfig().build()
    }

  return ResolvedTableWrite(rawEntry, effectiveEntry, storedEntry)
}

private fun defaultActionForEntry(
  entry: TableEntry,
  tableName: String,
  initialDefaultActions: Map<String, DefaultAction>,
  actionNameById: Map<Int, String>,
): DefaultAction {
  if (entry.action.typeCase != P4RuntimeOuterClass.TableAction.TypeCase.ACTION) {
    return initialDefaultActions[tableName] ?: DefaultAction("NoAction")
  }
  val action = entry.action.action
  return DefaultAction(
    actionNameById[action.actionId] ?: error("unknown action ID ${action.actionId}"),
    action.paramsList,
  )
}

private fun validateInlineDirectResourceWrite(
  write: ResolvedTableWrite,
  updateType: Update.Type,
  tableName: String,
  directCounterTables: Set<String>,
  directMeterTables: Set<String>,
  context: DirectResourceActionValidationContext,
): WriteResult? {
  if (updateType == Update.Type.DELETE) return null
  val rawEntry = write.rawEntry
  if (rawEntry.hasCounterData() && tableName !in directCounterTables) {
    return WriteResult.InvalidArgument(
      "table '$tableName' has no direct counter, but TableEntry contained counter_data"
    )
  }
  if (rawEntry.hasMeterConfig() && tableName !in directMeterTables) {
    return WriteResult.InvalidArgument(
      "table '$tableName' has no direct meter, but TableEntry contained meter_config"
    )
  }
  if (
    (rawEntry.hasCounterData() || rawEntry.hasMeterConfig()) &&
      write.effectiveEntryForValidation?.action?.typeCase ==
        P4RuntimeOuterClass.TableAction.TypeCase.TYPE_NOT_SET
  ) {
    return WriteResult.InvalidArgument(
      "TableEntry contained direct resource data, but the selected action for table " +
        "'$tableName' does not execute a direct resource"
    )
  }
  return write.effectiveEntryForValidation?.let {
    validateInlineDirectResourceActions(it, tableName, context)
  }
}

private fun matchLong(bits: Long, width: Int, match: P4RuntimeOuterClass.FieldMatch): Boolean =
  when (match.fieldMatchTypeCase) {
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT ->
      bits == match.exact.value.toUnsignedLong()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY -> {
      val mask = match.ternary.mask.toUnsignedLong()
      (bits and mask) == (match.ternary.value.toUnsignedLong() and mask)
    }
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM -> {
      val prefixLen = match.lpm.prefixLen
      if (prefixLen == 0) true
      else {
        val shift = width - prefixLen
        bits ushr shift == match.lpm.value.toUnsignedLong() ushr shift
      }
    }
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE ->
      bits in match.range.low.toUnsignedLong()..match.range.high.toUnsignedLong()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL ->
      bits == match.optional.value.toUnsignedLong()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
    null -> true
  }

private fun matchBig(bits: BigInteger, width: Int, match: P4RuntimeOuterClass.FieldMatch): Boolean =
  when (match.fieldMatchTypeCase) {
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT ->
      bits == match.exact.value.toUnsignedBigInteger()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY -> {
      val mask = match.ternary.mask.toUnsignedBigInteger()
      bits.and(mask) == match.ternary.value.toUnsignedBigInteger().and(mask)
    }
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM -> {
      val prefixLen = match.lpm.prefixLen
      if (prefixLen == 0) true
      else {
        val shift = width - prefixLen
        bits.shiftRight(shift) == match.lpm.value.toUnsignedBigInteger().shiftRight(shift)
      }
    }
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE -> {
      val lo = match.range.low.toUnsignedBigInteger()
      val hi = match.range.high.toUnsignedBigInteger()
      bits in lo..hi
    }
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL ->
      bits == match.optional.value.toUnsignedBigInteger()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
    null -> true
  }

/**
 * P4Runtime spec §9.1: two entries match the same key iff they have the same table_id, match
 * fields, and priority (priority is part of the key for ternary/range tables).
 */
fun TableEntry.sameKey(other: TableEntry): Boolean =
  tableId == other.tableId && priority == other.priority && matchList == other.matchList

/** Default action state for a table: action name and optional parameters. */
data class DefaultAction(
  val name: String,
  val params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
)

/** Result of a [TableStore.write] operation. */
sealed class WriteResult {
  open val message: String
    get() = ""

  data object Success : WriteResult()

  data class AlreadyExists(override val message: String) : WriteResult()

  data class NotFound(override val message: String) : WriteResult()

  data class InvalidArgument(override val message: String) : WriteResult()

  data class ResourceExhausted(override val message: String) : WriteResult()
}

/**
 * Stores and looks up P4 table entries for all tables in a loaded pipeline.
 *
 * Supports exact, LPM, ternary, range, and optional match kinds. Entries are stored per-table;
 * lookup returns the highest-priority match.
 *
 * Call [loadMappings] once per pipeline load before any [write] or [lookup] calls.
 */
class TableStore : TableDataReader {

  private data class RegisterInfo(val name: String, val bitwidth: Int, val size: Int)

  /** Metadata for statically-allocated indexed externs (counters, meters). */
  private data class IndexedExternInfo(val size: Int)

  private data class CounterMappings(
    val infoById: Map<Int, IndexedExternInfo>,
    val idByName: Map<String, Int>,
  )

  internal data class StatefulExternCheckpoint(
    val directCounters: Map<TableEntry, LongArray>,
    val indirectCounters: Map<Int, Map<Int, LongArray>>,
    val registers: Map<String, Map<Int, Value>>,
  )

  // -------------------------------------------------------------------------
  // Forwarding snapshot (immutable view published to the data plane)
  // -------------------------------------------------------------------------

  /**
   * Immutable forwarding state published to data-plane threads via a volatile pointer swap.
   *
   * Control-plane writes mutate [writeState] (a private mutable copy), then publish by calling
   * [publishSnapshot]. Packet-processing threads read [snapshot] without any lock — the snapshot is
   * immutable by construction.
   *
   * Bundled into a single class so that [deepCopy] (used for ROLLBACK_ON_ERROR, DATAPLANE_ATOMIC,
   * and snapshot publishing) is defined right next to the fields it operates on. When adding a new
   * field, update [deepCopy] — it's right here so you can't miss it.
   *
   * Entries themselves are immutable protobuf messages; only the container structures (maps, lists)
   * need copying.
   */
  class ForwardingSnapshot internal constructor() {
    internal val tables: MutableMap<String, MutableList<TableEntry>> = mutableMapOf()
    internal val directMeterData: MutableMap<TableEntry, P4RuntimeOuterClass.MeterConfig> =
      mutableMapOf()
    internal val defaultActions: MutableMap<String, DefaultAction> = mutableMapOf()
    /** Tables whose default action has been explicitly modified via P4Runtime Write. */
    internal val modifiedDefaults: MutableSet<String> = mutableSetOf()
    internal val profileMembers:
      MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.ActionProfileMember>> =
      mutableMapOf()
    internal val profileGroups:
      MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.ActionProfileGroup>> =
      mutableMapOf()
    internal val counters: MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.CounterData>> =
      mutableMapOf()
    internal val meters: MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.MeterConfig>> =
      mutableMapOf()
    internal val cloneSessions: MutableMap<Int, P4RuntimeOuterClass.CloneSessionEntry> =
      mutableMapOf()
    internal val multicastGroups: MutableMap<Int, P4RuntimeOuterClass.MulticastGroupEntry> =
      mutableMapOf()
    internal val valueSets: MutableMap<String, MutableList<P4RuntimeOuterClass.ValueSetMember>> =
      mutableMapOf()

    /** Creates a structural copy. Protobuf entries are shared; only containers are copied. */
    fun deepCopy(): ForwardingSnapshot =
      ForwardingSnapshot().also { copy ->
        for ((k, v) in tables) copy.tables[k] = v.toMutableList()
        copy.directMeterData.putAll(directMeterData)
        copy.defaultActions.putAll(defaultActions)
        copy.modifiedDefaults.addAll(modifiedDefaults)
        for ((k, v) in profileMembers) copy.profileMembers[k] = v.toMutableMap()
        for ((k, v) in profileGroups) copy.profileGroups[k] = v.toMutableMap()
        for ((k, v) in counters) copy.counters[k] = v.toMutableMap()
        for ((k, v) in meters) copy.meters[k] = v.toMutableMap()
        copy.cloneSessions.putAll(cloneSessions)
        copy.multicastGroups.putAll(multicastGroups)
        for ((k, v) in valueSets) copy.valueSets[k] = v.toMutableList()
      }
  }

  // -------------------------------------------------------------------------
  // Write-state and snapshot
  // -------------------------------------------------------------------------

  /**
   * Mutable state owned by the control plane. Only accessed under the control-plane mutex (in
   * [P4RuntimeService]). Never read by data-plane threads.
   */
  private var writeState = ForwardingSnapshot()

  /**
   * Published immutable snapshot read by data-plane threads. Updated by [publishSnapshot] after
   * every control-plane mutation. Data-plane threads read this without any lock — one volatile
   * pointer load, no CAS, no contention.
   */
  @Volatile
  var snapshot: ForwardingSnapshot = writeState.deepCopy()
    private set

  /**
   * Direct counter data: `[packetCount, byteCount]` per table entry. Lives outside the snapshot
   * because it is mutated lock-free during packet processing via [directCounterIncrement]. Uses
   * value-based keying (`ConcurrentHashMap` uses `equals`/`hashCode`), unlike the previous
   * `IdentityHashMap` — this is safe because `TableEntry` is a protobuf message with value-based
   * equality, and eliminates the fragile identity-keying invariant that `deepCopy` had to preserve.
   *
   * TODO: protobuf `equals`/`hashCode` walks all fields — expensive on every table hit. Consider a
   *   cheaper key (e.g., integer index assigned at insert time) if profiling shows this matters.
   */
  private val directCounterData = ConcurrentHashMap<TableEntry, AtomicLongArray>()

  /**
   * Indirect counter data written by explicit `Counter.count()` calls. Like direct counters, this
   * lives outside [writeState] so packet-side increments never publish partially-applied
   * control-plane batches. P4Runtime counter writes update this live store too; [writeState] keeps
   * the same values only as the control-plane shadow used by rollback and publish bookkeeping.
   */
  private val indirectCounterData =
    ConcurrentHashMap<Int, ConcurrentHashMap<Int, AtomicLongArray>>()

  /**
   * Register state: mutated lock-free during packet processing. Lives outside the snapshot for the
   * same reason as direct counters. Concurrent packets writing the same register index is a data
   * race at the P4 level (the program is wrong); we intentionally don't provide atomicity for
   * read-modify-write sequences split across two extern calls, matching hardware semantics.
   */
  private val registers = ConcurrentHashMap<String, ConcurrentHashMap<Int, Value>>()
  private val registerReadCapture = ThreadLocal<RegisterReadCapture?>()

  /**
   * Publishes the current [writeState] as a new immutable [snapshot] for data-plane threads.
   *
   * Callers are responsible for calling this after mutations. This is intentionally not automatic —
   * it makes publish points explicit and allows batch callers to publish once instead of
   * per-update.
   */
  @Synchronized
  fun publishSnapshot() {
    snapshot = writeState.deepCopy()
  }

  /**
   * Returns the p4info alias names of tables that have at least one installed entry. This includes
   * const entries installed from `device.staticEntries` at load time, since those also need
   * schema-compatible tables in the new pipeline to survive RECONCILE_AND_COMMIT.
   *
   * Reads from [writeState] (not [snapshot]) because it is called during pipeline reload under the
   * control-plane write mutex. Must only be called from the control-plane write path.
   */
  fun populatedTableAliases(): Set<String> =
    writeState.tables.entries
      .filter { (_, entries) -> entries.isNotEmpty() }
      .mapNotNull { (behavioralName, _) -> tableAliasByName[behavioralName] }
      .toSet()

  /**
   * Restores table entries from a previous [ForwardingSnapshot], matching tables by p4info alias.
   *
   * Only entries for tables whose alias appears in both the old and new pipeline are restored. The
   * caller is responsible for verifying schema compatibility before calling this.
   */
  @Synchronized
  fun restoreTableEntries(oldSnapshot: ForwardingSnapshot, oldAliasByName: Map<String, String>) {
    // Reverse of tableAliasByName: p4info alias → new behavioral name.
    val newNameByAlias = tableAliasByName.entries.associate { (name, alias) -> alias to name }

    for ((oldName, entries) in oldSnapshot.tables) {
      if (entries.isEmpty()) continue
      val alias = oldAliasByName[oldName] ?: continue
      val newName = newNameByAlias[alias] ?: continue
      writeState.tables[newName] = entries.toMutableList()
      for (entry in entries) {
        oldSnapshot.directMeterData[entry]?.let { writeState.directMeterData[entry] = it }
        // Direct counters (AtomicLongArray in directCounterData) are not restored — they
        // reset to zero on pipeline reload, matching BMv2 behavior. Counters are best-effort.
      }
    }

    for (oldName in oldSnapshot.modifiedDefaults) {
      val alias = oldAliasByName[oldName] ?: continue
      val newName = newNameByAlias[alias] ?: continue
      oldSnapshot.defaultActions[oldName]?.let {
        writeState.defaultActions[newName] = it
        writeState.modifiedDefaults.add(newName)
      }
    }

    // Restore PRE entries (multicast groups, clone sessions) — these are table-independent.
    writeState.cloneSessions.putAll(oldSnapshot.cloneSessions)
    writeState.multicastGroups.putAll(oldSnapshot.multicastGroups)

    // Restore action profile members and groups.
    for ((profileId, members) in oldSnapshot.profileMembers) {
      writeState.profileMembers.getOrPut(profileId) { mutableMapOf() }.putAll(members)
    }
    for ((profileId, groups) in oldSnapshot.profileGroups) {
      writeState.profileGroups.getOrPut(profileId) { mutableMapOf() }.putAll(groups)
    }
  }

  // Pipeline config (populated by loadMappings, not part of write-state).
  private var tableSizeLimit: Map<String, Int> = emptyMap()
  private var profileMaxGroupSize: Map<Int, Int> = emptyMap()
  private var profileSizeLimit: Map<Int, Int> = emptyMap()
  private var profileNameById: Map<Int, String> = emptyMap()
  private val tableActionProfile: MutableMap<String, Int> = mutableMapOf()
  private var directCounterTables: Set<String> = emptySet()
  private var directMeterTables: Set<String> = emptySet()
  private var directCounterActionsByTable: Map<String, Set<String>> = emptyMap()
  private var directMeterActionsByTable: Map<String, Set<String>> = emptyMap()
  private var tableActionOverrides: Map<String, Map<String, String>> = emptyMap()
  private var initialDefaultActions: Map<String, DefaultAction> = emptyMap()

  // For unit tests: makes lookup() return hit=true with this action rather than searching entries.
  private val forcedHits: MutableMap<String, String> = mutableMapOf()

  fun setForcedHit(tableName: String, actionName: String) {
    forcedHits[tableName] = actionName
  }

  // Populated by loadMappings; used to resolve IDs to names in write() and lookup().
  // Public read-only accessors let the P4Runtime layer build Entity protos without
  // duplicating the behavioral name resolution logic.
  var tableNameById: Map<Int, String> = emptyMap()
    private set

  var actionNameById: Map<Int, String> = emptyMap()
    private set

  // Map behavioral (post-midend) names back to p4info aliases (the short names from the
  // P4 source). Used for human-readable trace output.
  private var actionAliasByName: Map<String, String> = emptyMap()

  /**
   * Behavioral-name → p4info-alias mapping for tables. Populated by [loadMappings]. Public for
   * [Simulator.loadPipelinePreservingEntries] to snapshot before reload.
   */
  var tableAliasByName: Map<String, String> = emptyMap()
    private set

  /** Reverse of [tableAliasByName]: p4info alias → behavioral name. */
  private var tableNameByAlias: Map<String, String> = emptyMap()

  private var registerInfoById: Map<Int, RegisterInfo> = emptyMap()
  private var registerInfoByName: Map<String, Pair<Int, RegisterInfo>> = emptyMap()
  private var counterInfoById: Map<Int, IndexedExternInfo> = emptyMap()
  private var counterIdByName: Map<String, Int> = emptyMap()
  private var meterInfoById: Map<Int, IndexedExternInfo> = emptyMap()

  private data class ValueSetInfo(val name: String, val size: Int)

  private var valueSetInfoById: Map<Int, ValueSetInfo> = emptyMap()

  /** Match field descriptors per table (behavioral name), for data-plane add_entry. */
  private var tableMatchFields: Map<String, List<P4InfoOuterClass.MatchField>> = emptyMap()

  /** Reverse mapping: behavioral action name -> action ID. */
  private var actionIdByName: Map<String, Int> = emptyMap()

  /** Reverse mapping: behavioral table name -> table ID. */
  private var tableIdByName: Map<String, Int> = emptyMap()

  /** Action parameter info per action (behavioral name), for data-plane add_entry. */
  private var actionParamInfo: Map<String, List<P4InfoOuterClass.Action.Param>> = emptyMap()

  /**
   * Initialises the store for a loaded pipeline and clears all mutable state.
   *
   * Resolves p4info IDs to behavioral IR names (which may differ from p4info aliases for inlined
   * controls — e.g. behavioral "c_t" vs p4info alias "t"). When [device] has no behavioral config,
   * p4info aliases are used directly (convenient for tests).
   *
   * Also installs default actions and static table entries from [p4info] and [device].
   *
   * Must be called before [write] or [lookup]. Calling it again (pipeline reload) resets all state.
   */
  fun loadMappings(
    p4info: P4InfoOuterClass.P4Info = P4InfoOuterClass.P4Info.getDefaultInstance(),
    device: DeviceConfig = DeviceConfig.getDefaultInstance(),
  ) {
    // Extract behavioral names from the IR. The behavioral IR uses its own table/action
    // names (e.g. inlined "c_t" vs p4info alias "t"). When the device config is empty
    // (e.g. unit tests), resolveName falls through to the p4info alias itself.
    val behavioral = device.behavioral
    val behavioralTableNames = behavioral.tablesList.map { it.name }
    val behavioralActionNames =
      (behavioral.actionsList + behavioral.controlsList.flatMap { it.localActionsList }).flatMap {
        action ->
        listOfNotNull(action.name, action.currentName.ifEmpty { null })
      }

    fun resolveName(alias: String, candidates: List<String>): String {
      // p4info aliases use dots for nested controls (e.g. "ct.ipv4_da"), while
      // the behavioral IR uses underscores (e.g. "ct_ipv4_da"). When two tables
      // share a short name, p4c disambiguates with a control prefix
      // (e.g. "MainControlImpl.ipv4_da" for behavioral "ipv4_da"). Try multiple
      // forms to handle all cases.
      val underscored = alias.replace('.', '_')
      val afterFirstDot = if ('.' in alias) alias.substringAfter('.') else null
      val afterFirstDotUnderscored = afterFirstDot?.replace('.', '_')
      return candidates.find { it == alias }
        ?: candidates.find { it.endsWith("_$alias") }
        ?: candidates.find { it == underscored }
        ?: candidates.find { it.endsWith("_$underscored") }
        ?: afterFirstDotUnderscored?.let { suffix ->
          candidates.find { it == suffix } ?: candidates.find { it.endsWith("_$suffix") }
        }
        ?: alias
    }

    // Build both forward (id → behavioral name) and reverse (behavioral name → alias) maps
    // in a single pass per list.
    val tableById = mutableMapOf<Int, String>()
    val tableByName = mutableMapOf<String, String>()
    for (table in p4info.tablesList) {
      val alias = table.preamble.alias.ifEmpty { table.preamble.name }
      val behavioral = resolveName(alias, behavioralTableNames)
      tableById[table.preamble.id] = behavioral
      tableByName[behavioral] = alias
    }
    this.tableNameById = tableById
    this.tableAliasByName = tableByName
    this.tableNameByAlias = tableByName.entries.associate { (name, alias) -> alias to name }

    val actionById = mutableMapOf<Int, String>()
    val actionByName = mutableMapOf<String, String>()
    for (action in p4info.actionsList) {
      val alias = action.preamble.alias.ifEmpty { action.preamble.name }
      val behavioral = resolveName(alias, behavioralActionNames)
      actionById[action.preamble.id] = behavioral
      actionByName[behavioral] = alias
    }
    this.actionNameById = actionById
    this.actionAliasByName = actionByName
    this.actionIdByName = actionById.entries.associate { (id, name) -> name to id }
    this.tableIdByName = tableById.entries.associate { (id, name) -> name to id }
    this.actionParamInfo =
      p4info.actionsList
        .mapNotNull { action ->
          val name = actionById[action.preamble.id] ?: return@mapNotNull null
          name to action.paramsList
        }
        .toMap()
    this.tableMatchFields =
      p4info.tablesList
        .mapNotNull { table ->
          val name = tableById[table.preamble.id] ?: return@mapNotNull null
          name to table.matchFieldsList
        }
        .toMap()
    this.registerInfoById =
      p4info.registersList.associate { reg ->
        val bitwidth = reg.typeSpec.bitstring.bit.bitwidth
        reg.preamble.id to RegisterInfo(reg.preamble.name, bitwidth, reg.size)
      }
    this.registerInfoByName = registerInfoById.entries.associate { it.value.name to it.toPair() }
    val counterMappings = buildCounterMappings(p4info, behavioral, ::resolveName)
    this.counterInfoById = counterMappings.infoById
    this.counterIdByName = counterMappings.idByName
    this.meterInfoById =
      p4info.metersList.associate { it.preamble.id to IndexedExternInfo(it.size.toInt()) }
    this.valueSetInfoById =
      p4info.valueSetsList.associate {
        it.preamble.id to
          ValueSetInfo(name = it.preamble.alias.ifEmpty { it.preamble.name }, size = it.size)
      }
    this.directCounterTables =
      p4info.directCountersList.mapNotNull { tableNameById[it.directTableId] }.toSet()
    this.directMeterTables =
      p4info.directMetersList.mapNotNull { tableNameById[it.directTableId] }.toSet()
    tableActionOverrides = behavioral.tablesList.associate { it.name to it.actionOverridesMap }
    val directResourceActions =
      deriveDirectResourceActions(
        behavioral,
        p4info,
        tableNameById,
        actionNameById,
        tableActionOverrides,
      )
    directCounterActionsByTable = directResourceActions.counterActionsByTable
    directMeterActionsByTable = directResourceActions.meterActionsByTable
    writeState = ForwardingSnapshot()
    indirectCounterData.clear()
    directCounterData.clear()
    registers.clear()
    forcedHits.clear()
    tableActionProfile.clear()

    // Cache proto repeated-field accessor (each call creates a defensive copy).
    val p4infoTables = p4info.tablesList

    // P4Runtime spec §9.27: enforce table size limits from p4info.
    tableSizeLimit =
      p4infoTables
        .filter { it.size > 0 }
        .mapNotNull { table ->
          val name = tableNameById[table.preamble.id] ?: return@mapNotNull null
          name to table.size.toInt()
        }
        .toMap()

    // P4Runtime spec §9.2: enforce max_group_size and total size from p4info action profiles.
    profileMaxGroupSize =
      p4info.actionProfilesList
        .filter { it.maxGroupSize > 0 }
        .associate { it.preamble.id to it.maxGroupSize }
    profileSizeLimit =
      p4info.actionProfilesList
        .filter { it.size > 0 }
        .associate { it.preamble.id to it.size.toInt() }
    profileNameById =
      p4info.actionProfilesList.associate {
        it.preamble.id to it.preamble.alias.ifEmpty { it.preamble.name }
      }

    // Register which tables use action profiles (implementation_id != 0).
    for (table in p4infoTables) {
      if (table.implementationId != 0) {
        val tableName = tableNameById[table.preamble.id] ?: continue
        tableActionProfile[tableName] = table.implementationId
      }
    }

    // Install default actions from p4info.
    for (table in p4infoTables) {
      // const_default_action_id: immutable default set in the P4 source with `const`.
      // initial_default_action: mutable default set in the P4 source without `const`.
      val defaultActionId =
        if (table.constDefaultActionId != 0) table.constDefaultActionId
        else if (table.hasInitialDefaultAction()) table.initialDefaultAction.actionId else 0
      if (defaultActionId != 0) {
        val tableName = tableNameById[table.preamble.id] ?: continue
        val actionName = actionNameById[defaultActionId] ?: "NoAction"
        // Convert p4info TableActionCall.Argument to P4Runtime Action.Param.
        val params =
          if (table.hasInitialDefaultAction())
            table.initialDefaultAction.argumentsList.map { arg ->
              p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(arg.paramId)
                .setValue(arg.value)
                .build()
            }
          else emptyList()
        setDefaultAction(tableName, actionName, params)
      }
    }
    initialDefaultActions = writeState.defaultActions.toMap()

    // Install static table entries declared with `const entries` in the P4 source.
    for (update in device.staticEntries.updatesList) {
      write(update)
    }

    publishSnapshot()
  }

  private fun buildCounterMappings(
    p4info: P4InfoOuterClass.P4Info,
    behavioral: BehavioralConfig,
    resolveName: (String, List<String>) -> String,
  ): CounterMappings {
    val externInstanceNames =
      (behavioral.parsersList.flatMap { it.externInstancesList } +
          behavioral.controlsList.flatMap { it.externInstancesList })
        .map { it.name }
    val countersById = mutableMapOf<Int, IndexedExternInfo>()
    val countersByName = mutableMapOf<String, Int>()
    for (counter in p4info.countersList) {
      val alias = counter.preamble.alias.ifEmpty { counter.preamble.name }
      val behavioralName = resolveName(alias, externInstanceNames)
      countersById[counter.preamble.id] = IndexedExternInfo(counter.size.toInt())
      countersByName[behavioralName] = counter.preamble.id
      countersByName[alias] = counter.preamble.id
      if (counter.preamble.name.isNotEmpty())
        countersByName[counter.preamble.name] = counter.preamble.id
    }
    return CounterMappings(countersById, countersByName)
  }

  fun setDefaultAction(
    tableName: String,
    actionName: String,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
  ) {
    writeState.defaultActions[tableName] = DefaultAction(actionName, params)
  }

  // -------------------------------------------------------------------------
  // Data-plane table insertion (PNA add_entry)
  // -------------------------------------------------------------------------

  /**
   * Inserts a table entry from the data plane, as done by PNA's `add_entry` extern.
   *
   * Unlike [write] (which uses p4info IDs), this method works with behavioral names and runtime
   * [Value]s -- the natural representation available inside the simulator during packet processing.
   *
   * Synchronized on the [TableStore] instance to serialize with control-plane writes (which use the
   * same monitor via `@Synchronized` on [write], [publishSnapshot], etc.).
   *
   * @param tableName the behavioral name of the table to insert into.
   * @param keyValues the match key values (field ID string to [Value]), from the table miss.
   * @param actionName the behavioral name of the action to install.
   * @param actionParams action parameter values, in declaration order.
   * @return true if the entry was inserted, false if the table is full or the entry already exists.
   */
  fun addEntry(
    tableName: String,
    keyValues: List<Pair<String, Value>>,
    actionName: String,
    actionParams: List<Value>,
  ): Boolean {
    val tableId = tableIdByName[tableName] ?: error("add_entry: unknown table '$tableName'")
    val actionId = actionIdByName[actionName] ?: error("add_entry: unknown action '$actionName'")
    val matchFields =
      tableMatchFields[tableName] ?: error("add_entry: no match fields for '$tableName'")
    val paramInfo = actionParamInfo[actionName] ?: emptyList()

    val entryBuilder = TableEntry.newBuilder().setTableId(tableId)

    for (mf in matchFields) {
      val (_, value) =
        keyValues.find { it.first == mf.id.toString() }
          ?: error("add_entry: missing key value for field ${mf.id} in table '$tableName'")
      val bytes = valueToBytesForMatch(value, mf.bitwidth)
      val fm = P4RuntimeOuterClass.FieldMatch.newBuilder().setFieldId(mf.id)
      when (mf.matchType) {
        P4InfoOuterClass.MatchField.MatchType.EXACT ->
          fm.setExact(P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(bytes))
        P4InfoOuterClass.MatchField.MatchType.LPM ->
          fm.setLpm(
            P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
              .setValue(bytes)
              .setPrefixLen(mf.bitwidth)
          )
        P4InfoOuterClass.MatchField.MatchType.TERNARY ->
          fm.setTernary(
            P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
              .setValue(bytes)
              .setMask(ByteString.copyFrom(ByteArray((mf.bitwidth + 7) / 8) { 0xFF.toByte() }))
          )
        P4InfoOuterClass.MatchField.MatchType.OPTIONAL ->
          fm.setOptional(P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(bytes))
        else -> error("add_entry: unsupported match type ${mf.matchType} in table '$tableName'")
      }
      entryBuilder.addMatch(fm)
    }

    val actionBuilder = P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId)
    for ((i, value) in actionParams.withIndex()) {
      val paramId = paramInfo.getOrNull(i)?.id ?: (i + 1)
      actionBuilder.addParams(
        P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(paramId)
          .setValue(valueToBytes(value))
      )
    }
    entryBuilder.setAction(P4RuntimeOuterClass.TableAction.newBuilder().setAction(actionBuilder))

    val entry = entryBuilder.build()

    // synchronized(this) serializes with control-plane writes (which also synchronize on
    // this TableStore instance via the @Synchronized write/publishSnapshot methods).
    return synchronized(this) {
      val entries = writeState.tables.getOrPut(tableName) { mutableListOf() }

      // If an entry with the same key already exists, the add_entry is a no-op (returns true
      // per PNA spec -- the existing entry is retained).
      if (entries.any { it.sameKey(entry) }) return@synchronized true

      val limit = tableSizeLimit[tableName]
      if (limit != null && entries.size >= limit) return@synchronized false

      entries.add(entry)
      publishSnapshot()
      true
    }
  }

  /** Encodes a runtime [Value] as a P4Runtime [ByteString] for a match field of [bitwidth] bits. */
  private fun valueToBytesForMatch(value: Value, bitwidth: Int): ByteString =
    when (value) {
      is BitVal -> ByteString.copyFrom(value.bits.toByteArray())
      is BoolVal -> {
        val b = if (value.value) 1 else 0
        ByteString.copyFrom(BitVector.ofInt(b, bitwidth).toByteArray())
      }
      else -> error("add_entry: unsupported key value type: ${value::class.simpleName}")
    }

  /** Encodes a runtime [Value] as a P4Runtime [ByteString] for an action parameter. */
  private fun valueToBytes(value: Value): ByteString =
    when (value) {
      is BitVal -> ByteString.copyFrom(value.bits.toByteArray())
      is BoolVal -> ByteString.copyFrom(byteArrayOf(if (value.value) 1 else 0))
      else -> error("add_entry: unsupported action param type: ${value::class.simpleName}")
    }

  // -------------------------------------------------------------------------
  // Raw data accessors (used by the P4Runtime layer's EntityReader)
  // -------------------------------------------------------------------------

  override fun getTableEntries(tableName: String): List<TableEntry> =
    snapshot.tables[tableName] ?: emptyList()

  override fun getDefaultAction(tableName: String): DefaultAction? =
    snapshot.defaultActions[tableName]

  override fun isDefaultModified(tableName: String): Boolean =
    tableName in snapshot.modifiedDefaults

  /**
   * Builds a P4Runtime [Entity] for the modified default action of [displayName] (p4info alias), or
   * null if the default has not been modified or the table doesn't exist.
   *
   * Takes an explicit [snapshot] so callers can pin the snapshot that was live during packet
   * processing, avoiding races with concurrent [publishSnapshot] calls.
   */
  fun buildModifiedDefaultActionEntity(
    displayName: String,
    snapshot: ForwardingSnapshot,
  ): P4RuntimeOuterClass.Entity? {
    val behavioralName = tableNameByAlias[displayName] ?: displayName
    if (behavioralName !in snapshot.modifiedDefaults) return null
    val default = snapshot.defaultActions[behavioralName] ?: return null
    val tableId = tableIdByName[behavioralName] ?: return null
    val actionId = actionIdByName[default.name] ?: return null
    return P4RuntimeOuterClass.Entity.newBuilder()
      .setTableEntry(
        P4RuntimeOuterClass.TableEntry.newBuilder()
          .setTableId(tableId)
          .setIsDefaultAction(true)
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(
                P4RuntimeOuterClass.Action.newBuilder()
                  .setActionId(actionId)
                  .addAllParams(default.params)
              )
          )
      )
      .build()
  }

  /** Returns the action profile ID for the table with [tableId], or null if the table has none. */
  fun actionProfileIdForTable(tableId: Int): Int? {
    val tableName = tableNameById[tableId] ?: return null
    return tableActionProfile[tableName]
  }

  override fun getDirectCounterData(entry: TableEntry): P4RuntimeOuterClass.CounterData? {
    val counters = directCounterData[entry] ?: return null
    return P4RuntimeOuterClass.CounterData.newBuilder()
      .setPacketCount(counters.get(0))
      .setByteCount(counters.get(1))
      .build()
  }

  override fun getDirectMeterData(entry: TableEntry): P4RuntimeOuterClass.MeterConfig? =
    snapshot.directMeterData[entry]

  override fun hasDirectCounter(tableName: String): Boolean = tableName in directCounterTables

  override fun hasDirectMeter(tableName: String): Boolean = tableName in directMeterTables

  // -------------------------------------------------------------------------
  // Registers
  // -------------------------------------------------------------------------

  fun registerRead(name: String, index: Int): Value? {
    val value = registers[name]?.get(index)
    registerReadCapture.get()?.recordRead(name, index, value)
    return value
  }

  fun registerWrite(name: String, index: Int, value: Value) {
    registerReadCapture.get()?.rememberInitial(name, index, registers[name]?.get(index))
    registers.getOrPut(name) { ConcurrentHashMap() }[index] = value
  }

  fun <T> captureRegisterSeeds(block: () -> T): CapturedRegisterSeeds<T> {
    check(registerReadCapture.get() == null) { "nested register seed capture is not supported" }
    val capture = RegisterReadCapture()
    registerReadCapture.set(capture)
    try {
      return CapturedRegisterSeeds(block(), capture.dependencies())
    } finally {
      registerReadCapture.remove()
    }
  }

  fun buildRegisterEntity(dependency: RegisterSeedDependency): P4RuntimeOuterClass.Entity {
    val (regId, info) =
      registerInfoByName[dependency.registerName]
        ?: error("register '${dependency.registerName}' has no P4Info mapping")
    val value =
      dependency.initialValue as? BitVal
        ?: error(
          "register '${dependency.registerName}' stored non-bit value ${dependency.initialValue}"
        )
    require(dependency.index in 0 until info.size) {
      "register index ${dependency.index} out of bounds [0, ${info.size})"
    }
    return buildRegisterEntity(regId, dependency.index, value.bits)
  }

  // P4Runtime spec: RegisterEntry MODIFY only (statically allocated arrays).
  private fun writeRegisterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.RegisterEntry,
  ): WriteResult {
    if (type != Update.Type.MODIFY)
      return WriteResult.InvalidArgument("registers only support MODIFY, not $type")
    val info =
      registerInfoById[entry.registerId]
        ?: return WriteResult.NotFound(
          "unknown register ID ${entry.registerId} " +
            "(valid registers: ${formatOptions(registerInfoById.entries.map {
              "'${it.value.name}' (${it.key})"
            })})"
        )
    val index = entry.index.index.toInt()
    if (index < 0 || index >= info.size)
      return WriteResult.InvalidArgument("register index $index out of bounds [0, ${info.size})")
    val value = BitVal(BitVector(entry.data.bitstring.toUnsignedBigInteger(), info.bitwidth))
    registerWrite(info.name, index, value)
    return WriteResult.Success
  }

  /**
   * Reads register entries as P4Runtime Entity protos, filtered by [filter].
   * - `register_id=0` → wildcard: all indices of all registers.
   * - `register_id=N`, no index → all indices for register N (0..size-1).
   * - `register_id=N`, with index → single entry.
   *
   * Unwritten indices return the default value (zero).
   */
  fun readRegisterEntries(
    filter: P4RuntimeOuterClass.RegisterEntry =
      P4RuntimeOuterClass.RegisterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val infos =
      if (filter.registerId == 0) registerInfoById
      else {
        val info = registerInfoById[filter.registerId] ?: return emptyList()
        mapOf(filter.registerId to info)
      }
    val hasIndex = filter.hasIndex()
    return infos.flatMap { (regId, info) ->
      val zeroBits = BitVector(BigInteger.ZERO, info.bitwidth)
      val indices = if (hasIndex) listOf(filter.index.index.toInt()) else (0 until info.size)
      indices.map { idx ->
        val bits = (registerRead(info.name, idx) as? BitVal)?.bits ?: zeroBits
        buildRegisterEntity(regId, idx, bits)
      }
    }
  }

  private fun buildRegisterEntity(
    registerId: Int,
    index: Int,
    bits: BitVector,
  ): P4RuntimeOuterClass.Entity {
    val data = ByteString.copyFrom(bits.toByteArray())
    return P4RuntimeOuterClass.Entity.newBuilder()
      .setRegisterEntry(
        P4RuntimeOuterClass.RegisterEntry.newBuilder()
          .setRegisterId(registerId)
          .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(index.toLong()))
          .setData(p4.v1.P4DataOuterClass.P4Data.newBuilder().setBitstring(data))
      )
      .build()
  }

  // -------------------------------------------------------------------------
  // Indexed externs (counters, meters) — shared write helper
  // -------------------------------------------------------------------------

  /**
   * Validates and stores a value for a MODIFY-only indexed extern (counter, meter).
   *
   * Checks: MODIFY-only, known ID, index in bounds. On success, stores [value] at
   * `storage[id][index]`.
   */
  private fun <V> writeIndexedExtern(
    type: Update.Type,
    entityName: String,
    id: Int,
    index: P4RuntimeOuterClass.Index,
    infoById: Map<Int, IndexedExternInfo>,
    storage: MutableMap<Int, MutableMap<Int, V>>,
    value: V,
  ): WriteResult {
    if (type != Update.Type.MODIFY)
      return WriteResult.InvalidArgument("${entityName}s only support MODIFY, not $type")
    val info =
      infoById[id]
        ?: return WriteResult.NotFound(
          "unknown $entityName ID $id " +
            "(valid ${entityName}s: ${formatOptions(infoById.keys.sorted().map { it.toString() })})"
        )
    val idx = index.index.toInt()
    if (idx < 0 || idx >= info.size)
      return WriteResult.InvalidArgument("$entityName index $idx out of bounds [0, ${info.size})")
    storage.getOrPut(id) { mutableMapOf() }[idx] = value
    return WriteResult.Success
  }

  // -------------------------------------------------------------------------
  // Counters
  // -------------------------------------------------------------------------

  private fun writeCounterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.CounterEntry,
  ): WriteResult {
    // Counters have one live data source: indirectCounterData. writeState.counters is updated in
    // parallel so rollback checkpoints and published snapshots preserve the control-plane view
    // without making packet-side Counter.count() publish writeState.
    val result =
      writeIndexedExtern(
        type,
        "counter",
        entry.counterId,
        entry.index,
        counterInfoById,
        writeState.counters,
        entry.data,
      )
    if (result == WriteResult.Success) {
      setIndirectCounterData(entry.counterId, entry.index.index.toInt(), entry.data)
    }
    return result
  }

  private fun setIndirectCounterData(
    counterId: Int,
    index: Int,
    data: P4RuntimeOuterClass.CounterData,
  ) {
    indirectCounterData
      .computeIfAbsent(counterId) { ConcurrentHashMap() }
      .computeIfAbsent(index) { AtomicLongArray(2) }
      .also {
        it.set(0, data.packetCount)
        it.set(1, data.byteCount)
      }
  }

  private fun readIndirectCounterData(
    counterId: Int,
    index: Int,
  ): P4RuntimeOuterClass.CounterData? {
    val data = indirectCounterData[counterId]?.get(index) ?: return null
    return P4RuntimeOuterClass.CounterData.newBuilder()
      .setPacketCount(data.get(0))
      .setByteCount(data.get(1))
      .build()
  }

  fun counterIncrement(counterName: String, index: Int, byteCount: Int) {
    val counterId = counterIdByName[counterName] ?: return
    val info = counterInfoById[counterId] ?: return
    if (index < 0 || index >= info.size) return

    val counters = indirectCounterData.computeIfAbsent(counterId) { ConcurrentHashMap() }
    val data = counters.computeIfAbsent(index) { AtomicLongArray(longArrayOf(0L, 0L)) }
    data.addAndGet(0, 1)
    data.addAndGet(1, byteCount.toLong())
  }

  fun readCounterEntries(
    filter: P4RuntimeOuterClass.CounterEntry = P4RuntimeOuterClass.CounterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val infos =
      if (filter.counterId == 0) counterInfoById
      else {
        val info = counterInfoById[filter.counterId] ?: return emptyList()
        mapOf(filter.counterId to info)
      }
    val hasIndex = filter.hasIndex()
    return infos.flatMap { (counterId, info) ->
      val indices = if (hasIndex) listOf(filter.index.index.toInt()) else (0 until info.size)
      indices.map { idx ->
        val data =
          readIndirectCounterData(counterId, idx)
            ?: P4RuntimeOuterClass.CounterData.getDefaultInstance()
        P4RuntimeOuterClass.Entity.newBuilder()
          .setCounterEntry(
            P4RuntimeOuterClass.CounterEntry.newBuilder()
              .setCounterId(counterId)
              .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(idx.toLong()))
              .setData(data)
          )
          .build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Meters
  // -------------------------------------------------------------------------

  private fun writeMeterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.MeterEntry,
  ): WriteResult =
    writeIndexedExtern(
      type,
      "meter",
      entry.meterId,
      entry.index,
      meterInfoById,
      writeState.meters,
      entry.config,
    )

  fun readMeterEntries(
    filter: P4RuntimeOuterClass.MeterEntry = P4RuntimeOuterClass.MeterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val infos =
      if (filter.meterId == 0) meterInfoById
      else {
        val info = meterInfoById[filter.meterId] ?: return emptyList()
        mapOf(filter.meterId to info)
      }
    val hasIndex = filter.hasIndex()
    return infos.flatMap { (meterId, info) ->
      val indices = if (hasIndex) listOf(filter.index.index.toInt()) else (0 until info.size)
      indices.map { idx ->
        val builder =
          P4RuntimeOuterClass.MeterEntry.newBuilder()
            .setMeterId(meterId)
            .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(idx.toLong()))
        snapshot.meters[meterId]?.get(idx)?.let { builder.setConfig(it) }
        P4RuntimeOuterClass.Entity.newBuilder().setMeterEntry(builder).build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Direct counters
  // -------------------------------------------------------------------------

  /**
   * Increments the direct counter for [entry] in [tableName] (called on every table hit).
   *
   * Lock-free: each counter is an [AtomicLongArray] with `[packetCount, byteCount]`. Multiple
   * packet threads can increment concurrently without synchronization.
   */
  fun directCounterIncrement(tableName: String, entry: TableEntry, packetLengthBytes: Int) {
    if (tableName !in directCounterTables) return
    val counters = directCounterData.getOrPut(entry) { AtomicLongArray(2) }
    counters.addAndGet(0, 1)
    counters.addAndGet(1, packetLengthBytes.toLong())
  }

  /**
   * Validates a MODIFY-only direct extern write and calls [update] with the stored entry.
   *
   * Checks: MODIFY-only, known table ID, table has the direct extern, table entry exists.
   */
  private inline fun writeDirectExtern(
    type: Update.Type,
    entityName: String,
    tableEntry: TableEntry,
    knownTables: Set<String>,
    update: (TableEntry) -> Unit,
  ): WriteResult {
    if (type != Update.Type.MODIFY)
      return WriteResult.InvalidArgument("${entityName}s only support MODIFY, not $type")
    val tableName =
      tableNameById[tableEntry.tableId]
        ?: return WriteResult.NotFound(
          "unknown table ID ${tableEntry.tableId} " +
            "(valid tables: ${formatOptions(tableNameById.entries.map {
              "'${it.value}' (${it.key})"
            })})"
        )
    if (tableName !in knownTables)
      return WriteResult.InvalidArgument("table '$tableName' has no $entityName")
    if (tableEntry.isDefaultAction) {
      update(defaultDirectResourceEntry(tableEntry.tableId))
      return WriteResult.Success
    }
    val entries =
      writeState.tables[tableName]
        ?: return WriteResult.NotFound("no entries in table '$tableName'")
    val stored =
      entries.find { it.sameKey(tableEntry) }
        ?: return WriteResult.NotFound("no matching entry in table '$tableName'")
    update(stored)
    return WriteResult.Success
  }

  private fun writeDirectCounterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.DirectCounterEntry,
  ): WriteResult =
    writeDirectExtern(type, "direct counter", entry.tableEntry, directCounterTables) {
      val arr = directCounterData.getOrPut(it) { AtomicLongArray(2) }
      arr.set(0, entry.data.packetCount)
      arr.set(1, entry.data.byteCount)
    }

  fun readDirectCounterEntries(
    filter: P4RuntimeOuterClass.DirectCounterEntry =
      P4RuntimeOuterClass.DirectCounterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val tableEntry = filter.tableEntry
    val tableNames = resolveTableNames(tableEntry.tableId, directCounterTables)
    if (tableEntry.isDefaultAction) {
      return tableNames.mapNotNull { tableName ->
        val tableId = tableIdByName[tableName] ?: return@mapNotNull null
        val entry = defaultDirectResourceEntry(tableId)
        val data =
          getDirectCounterData(entry) ?: P4RuntimeOuterClass.CounterData.getDefaultInstance()
        P4RuntimeOuterClass.Entity.newBuilder()
          .setDirectCounterEntry(
            P4RuntimeOuterClass.DirectCounterEntry.newBuilder().setTableEntry(entry).setData(data)
          )
          .build()
      }
    }
    val matchFilter = if (tableEntry.matchCount > 0) tableEntry else null
    return tableNames.flatMap { tableName ->
      filteredEntries(tableName, matchFilter).map { entry ->
        val data =
          getDirectCounterData(entry) ?: P4RuntimeOuterClass.CounterData.getDefaultInstance()
        P4RuntimeOuterClass.Entity.newBuilder()
          .setDirectCounterEntry(
            P4RuntimeOuterClass.DirectCounterEntry.newBuilder().setTableEntry(entry).setData(data)
          )
          .build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Direct meters
  // -------------------------------------------------------------------------

  private fun writeDirectMeterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.DirectMeterEntry,
  ): WriteResult =
    writeDirectExtern(type, "direct meter", entry.tableEntry, directMeterTables) {
      writeState.directMeterData[it] = entry.config
    }

  fun readDirectMeterEntries(
    filter: P4RuntimeOuterClass.DirectMeterEntry =
      P4RuntimeOuterClass.DirectMeterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val tableEntry = filter.tableEntry
    val tableNames = resolveTableNames(tableEntry.tableId, directMeterTables)
    if (tableEntry.isDefaultAction) {
      return tableNames.mapNotNull { tableName ->
        val tableId = tableIdByName[tableName] ?: return@mapNotNull null
        val entry = defaultDirectResourceEntry(tableId)
        val builder = P4RuntimeOuterClass.DirectMeterEntry.newBuilder().setTableEntry(entry)
        snapshot.directMeterData[entry]?.let { builder.setConfig(it) }
        P4RuntimeOuterClass.Entity.newBuilder().setDirectMeterEntry(builder).build()
      }
    }
    val matchFilter = if (tableEntry.matchCount > 0) tableEntry else null
    return tableNames.flatMap { tableName ->
      filteredEntries(tableName, matchFilter).map { entry ->
        val builder = P4RuntimeOuterClass.DirectMeterEntry.newBuilder().setTableEntry(entry)
        snapshot.directMeterData[entry]?.let { builder.setConfig(it) }
        P4RuntimeOuterClass.Entity.newBuilder().setDirectMeterEntry(builder).build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Value sets (P4 spec §12.14, P4Runtime spec §9.6)
  // -------------------------------------------------------------------------

  /** Returns the members of a parser value_set, or empty if not populated. */
  fun getValueSetMembers(name: String): List<P4RuntimeOuterClass.ValueSetMember> =
    snapshot.valueSets[name] ?: emptyList()

  /** Directly sets value_set members by name, bypassing P4Runtime write path. For testing. */
  internal fun populateValueSet(name: String, members: List<P4RuntimeOuterClass.ValueSetMember>) {
    writeState.valueSets[name] = members.toMutableList()
  }

  private fun writeValueSetEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.ValueSetEntry,
  ): WriteResult {
    // P4Runtime spec §9.6: value_set only supports MODIFY (replaces all members atomically).
    if (type != Update.Type.MODIFY)
      return WriteResult.InvalidArgument("value_set only supports MODIFY, not $type")
    val info =
      valueSetInfoById[entry.valueSetId]
        ?: return WriteResult.NotFound(
          "unknown value_set ID ${entry.valueSetId} " +
            "(valid value_sets: ${formatOptions(valueSetInfoById.entries.map {
              "'${it.value.name}' (${it.key})"
            })})"
        )
    val name = info.name
    val maxSize = info.size
    if (entry.membersCount > maxSize)
      return WriteResult.ResourceExhausted(
        "value_set '$name' has max size $maxSize, got ${entry.membersCount} members"
      )
    writeState.valueSets[name] = entry.membersList.toMutableList()
    return WriteResult.Success
  }

  fun readValueSetEntries(
    filter: P4RuntimeOuterClass.ValueSetEntry =
      P4RuntimeOuterClass.ValueSetEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val entries =
      if (filter.valueSetId == 0) valueSetInfoById
      else {
        val info = valueSetInfoById[filter.valueSetId] ?: return emptyList()
        mapOf(filter.valueSetId to info)
      }
    return entries.map { (vsId, info) ->
      P4RuntimeOuterClass.Entity.newBuilder()
        .setValueSetEntry(
          P4RuntimeOuterClass.ValueSetEntry.newBuilder()
            .setValueSetId(vsId)
            .addAllMembers(snapshot.valueSets[info.name] ?: emptyList())
        )
        .build()
    }
  }

  // -------------------------------------------------------------------------
  // Write
  // -------------------------------------------------------------------------

  @Synchronized
  fun write(update: Update): WriteResult {
    val entity = update.entity
    return when (entity.entityCase) {
      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_MEMBER ->
        writeProfileMember(update.type, entity.actionProfileMember)
      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_GROUP ->
        writeProfileGroup(update.type, entity.actionProfileGroup)
      P4RuntimeOuterClass.Entity.EntityCase.REGISTER_ENTRY ->
        writeRegisterEntry(update.type, entity.registerEntry)
      P4RuntimeOuterClass.Entity.EntityCase.COUNTER_ENTRY ->
        writeCounterEntry(update.type, entity.counterEntry)
      P4RuntimeOuterClass.Entity.EntityCase.METER_ENTRY ->
        writeMeterEntry(update.type, entity.meterEntry)
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_COUNTER_ENTRY ->
        writeDirectCounterEntry(update.type, entity.directCounterEntry)
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_METER_ENTRY ->
        writeDirectMeterEntry(update.type, entity.directMeterEntry)
      P4RuntimeOuterClass.Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY ->
        writePreEntry(update.type, entity.packetReplicationEngineEntry)
      P4RuntimeOuterClass.Entity.EntityCase.VALUE_SET_ENTRY ->
        writeValueSetEntry(update.type, entity.valueSetEntry)
      P4RuntimeOuterClass.Entity.EntityCase.TABLE_ENTRY -> writeTableEntry(update)
      P4RuntimeOuterClass.Entity.EntityCase.EXTERN_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIGEST_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.ENTITY_NOT_SET,
      null ->
        WriteResult.InvalidArgument(
          "unsupported entity type; only table entries, action profiles, counters, meters, " +
            "registers, value sets, and PRE entries are supported"
        )
    }
  }

  private fun writeTableEntry(update: Update): WriteResult {
    val rawEntry = update.entity.tableEntry
    val tableName =
      tableNameById[rawEntry.tableId]
        ?: return WriteResult.NotFound(
          "unknown table ID ${rawEntry.tableId} " +
            "(valid tables: ${formatOptions(tableNameById.entries.map {
              "'${it.value}' (${it.key})"
            })})"
        )
    val entries = writeState.tables.getOrPut(tableName) { mutableListOf() }
    val existingIndex = entries.indexOfFirst { it.sameKey(rawEntry) }
    val resolvedWrite =
      resolveTableWrite(
        rawEntry,
        update.type,
        tableName,
        entries.getOrNull(existingIndex),
        initialDefaultActions,
        actionIdByName,
      )

    validateInlineDirectResourceWrite(
        resolvedWrite,
        update.type,
        tableName,
        directCounterTables,
        directMeterTables,
        DirectResourceActionValidationContext(
          actionNameById,
          tableActionOverrides,
          tableActionProfile,
          writeState,
          directCounterActionsByTable,
          directMeterActionsByTable,
        ),
      )
      ?.let {
        return it
      }
    val entry = resolvedWrite.storedEntry
    val directResourceContext =
      InlineDirectResourceWriteContext(
        directCounterTables,
        directMeterTables,
        directCounterData,
        writeState,
      )

    // P4Runtime spec §9.1: default entries are stored separately and only support MODIFY.
    // The WriteValidator already rejects INSERT/DELETE for defaults.
    if (entry.isDefaultAction) {
      writeState.defaultActions[tableName] =
        defaultActionForEntry(entry, tableName, initialDefaultActions, actionNameById)
      writeState.modifiedDefaults.add(tableName)
      val defaultResourceEntry = defaultDirectResourceEntry(entry.tableId)
      if (rawEntry.action.typeCase == P4RuntimeOuterClass.TableAction.TypeCase.TYPE_NOT_SET) {
        directCounterData.remove(defaultResourceEntry)
        writeState.directMeterData.remove(defaultResourceEntry)
      }
      applyInlineDirectResources(
        rawEntry,
        defaultResourceEntry,
        defaultResourceEntry,
        tableName,
        directResourceContext,
      )
      return WriteResult.Success
    }

    // P4Runtime spec §9.1: INSERT requires the entry not to exist, MODIFY and DELETE
    // require it to exist.
    return when (update.type) {
      Update.Type.INSERT -> {
        if (existingIndex >= 0) {
          WriteResult.AlreadyExists(
            "table '$tableName' already contains an entry with the same match key"
          )
        } else {
          val limit = tableSizeLimit[tableName]
          if (limit != null && entries.size >= limit) {
            WriteResult.ResourceExhausted("table '$tableName' is full ($limit entries)")
          } else {
            entries.add(entry)
            applyInlineDirectResources(
              rawEntry,
              entry,
              oldEntry = null,
              tableName,
              directResourceContext,
            )
            WriteResult.Success
          }
        }
      }
      Update.Type.MODIFY -> {
        if (existingIndex < 0) {
          WriteResult.NotFound("table '$tableName' has no entry with the given match key")
        } else {
          // Transfer direct counter/meter data from old entry object to new one.
          // NOTE: if a third direct extern type is added, add a transfer line here and
          // a remove in the DELETE branch below.
          val old = entries[existingIndex]
          entries[existingIndex] = entry
          applyInlineDirectResources(rawEntry, entry, old, tableName, directResourceContext)
          WriteResult.Success
        }
      }
      Update.Type.DELETE -> {
        if (existingIndex < 0) {
          WriteResult.NotFound("table '$tableName' has no entry with the given match key")
        } else {
          val removed = entries.removeAt(existingIndex)
          directCounterData.remove(removed)
          writeState.directMeterData.remove(removed)
          WriteResult.Success
        }
      }
      else -> WriteResult.InvalidArgument("unsupported update type: ${update.type}")
    }
  }

  /** Generic INSERT/MODIFY/DELETE for a keyed map entry. */
  private fun <V> writeProfileEntity(
    type: Update.Type,
    map: MutableMap<Int, V>,
    key: Int,
    value: V,
    desc: String,
  ): WriteResult =
    when (type) {
      Update.Type.INSERT ->
        if (key in map) WriteResult.AlreadyExists("$desc already exists")
        else {
          map[key] = value
          WriteResult.Success
        }
      Update.Type.MODIFY ->
        if (key !in map) WriteResult.NotFound("$desc not found")
        else {
          map[key] = value
          WriteResult.Success
        }
      Update.Type.DELETE ->
        if (key !in map) WriteResult.NotFound("$desc not found")
        else {
          map.remove(key)
          WriteResult.Success
        }
      else -> WriteResult.InvalidArgument("unsupported update type: $type")
    }

  private fun writeProfileMember(
    type: Update.Type,
    member: P4RuntimeOuterClass.ActionProfileMember,
  ): WriteResult {
    if (type == Update.Type.INSERT) {
      val err = checkProfileSizeLimit(member.actionProfileId)
      if (err != null) return err
    }
    return writeProfileEntity(
      type,
      writeState.profileMembers.getOrPut(member.actionProfileId) { mutableMapOf() },
      member.memberId,
      member,
      "member ${member.memberId} in action profile ${member.actionProfileId}",
    )
  }

  private fun writeProfileGroup(
    type: Update.Type,
    group: P4RuntimeOuterClass.ActionProfileGroup,
  ): WriteResult {
    if (type == Update.Type.INSERT) {
      val err = checkProfileSizeLimit(group.actionProfileId)
      if (err != null) return err
    }
    // P4Runtime spec §9.2: enforce max_group_size on INSERT and MODIFY.
    if (type == Update.Type.INSERT || type == Update.Type.MODIFY) {
      val maxSize = profileMaxGroupSize[group.actionProfileId]
      if (maxSize != null && group.membersCount > maxSize) {
        return WriteResult.ResourceExhausted(
          "group ${group.groupId} in action profile " +
            "'${profileNameById[group.actionProfileId] ?: group.actionProfileId}' " +
            "has ${group.membersCount} members, max is $maxSize"
        )
      }
    }
    return writeProfileEntity(
      type,
      writeState.profileGroups.getOrPut(group.actionProfileId) { mutableMapOf() },
      group.groupId,
      group,
      "group ${group.groupId} in action profile ${group.actionProfileId}",
    )
  }

  /** Checks total member+group count against the p4info action_profile.size limit. */
  private fun checkProfileSizeLimit(profileId: Int): WriteResult? {
    val limit = profileSizeLimit[profileId] ?: return null
    val memberCount = writeState.profileMembers[profileId]?.size ?: 0
    val groupCount = writeState.profileGroups[profileId]?.size ?: 0
    val current = memberCount + groupCount
    if (current >= limit) {
      return WriteResult.ResourceExhausted(
        "action profile '${profileNameById[profileId] ?: profileId}' " +
          "is at capacity ($current/$limit members+groups)"
      )
    }
    return null
  }

  private fun writePreEntry(
    type: Update.Type,
    pre: P4RuntimeOuterClass.PacketReplicationEngineEntry,
  ): WriteResult =
    when (pre.typeCase) {
      P4RuntimeOuterClass.PacketReplicationEngineEntry.TypeCase.CLONE_SESSION_ENTRY -> {
        val entry = pre.cloneSessionEntry
        writeProfileEntity(
          type,
          writeState.cloneSessions,
          entry.sessionId,
          entry,
          "clone session ${entry.sessionId}",
        )
      }
      P4RuntimeOuterClass.PacketReplicationEngineEntry.TypeCase.MULTICAST_GROUP_ENTRY -> {
        val entry = pre.multicastGroupEntry
        writeProfileEntity(
          type,
          writeState.multicastGroups,
          entry.multicastGroupId,
          entry,
          "multicast group ${entry.multicastGroupId}",
        )
      }
      P4RuntimeOuterClass.PacketReplicationEngineEntry.TypeCase.TYPE_NOT_SET,
      null -> WriteResult.InvalidArgument("PRE entry must have a clone session or multicast group")
    }

  fun getCloneSession(sessionId: Int): P4RuntimeOuterClass.CloneSessionEntry? =
    snapshot.cloneSessions[sessionId]

  fun getMulticastGroup(groupId: Int): P4RuntimeOuterClass.MulticastGroupEntry? =
    snapshot.multicastGroups[groupId]

  /**
   * Reads PRE entries matching the filter.
   *
   * No oneof set → wildcard (return all clone sessions and multicast groups). One oneof set →
   * return entries of that type; ID 0 means all of that type, non-zero means that specific entry.
   */
  fun readPreEntries(
    filter: P4RuntimeOuterClass.PacketReplicationEngineEntry =
      P4RuntimeOuterClass.PacketReplicationEngineEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val includeClone = filter.hasCloneSessionEntry() || !filter.hasMulticastGroupEntry()
    val includeMulticast = filter.hasMulticastGroupEntry() || !filter.hasCloneSessionEntry()
    return buildList {
      if (includeClone) {
        val id = if (filter.hasCloneSessionEntry()) filter.cloneSessionEntry.sessionId else 0
        for (entry in readFromMap(snapshot.cloneSessions, id)) {
          add(entry.toPreEntity { setCloneSessionEntry(entry) })
        }
      }
      if (includeMulticast) {
        val id =
          if (filter.hasMulticastGroupEntry()) filter.multicastGroupEntry.multicastGroupId else 0
        for (entry in readFromMap(snapshot.multicastGroups, id)) {
          add(entry.toPreEntity { setMulticastGroupEntry(entry) })
        }
      }
    }
  }

  /** Returns all values if [id] is 0 (wildcard), or the single entry matching [id]. */
  private fun <T> readFromMap(map: MutableMap<Int, T>, id: Int): Collection<T> =
    if (id != 0) listOfNotNull(map[id]) else map.values

  /** Wraps a PRE sub-entry into an `Entity` proto. */
  private fun <T> T.toPreEntity(
    setter: P4RuntimeOuterClass.PacketReplicationEngineEntry.Builder.(T) -> Unit
  ): P4RuntimeOuterClass.Entity = buildPreEntity(this, setter)

  // -------------------------------------------------------------------------
  // Snapshot / Restore (for ROLLBACK_ON_ERROR / DATAPLANE_ATOMIC)
  // -------------------------------------------------------------------------

  /**
   * Captures a deep copy of all mutable state for later [restore]. Used by ROLLBACK_ON_ERROR and
   * DATAPLANE_ATOMIC to provide all-or-none write semantics.
   */
  @Synchronized
  fun snapshot(): RollbackCheckpoint =
    RollbackCheckpoint(writeState.deepCopy(), snapshotStatefulExterns())

  /** Restores all mutable state to a previously captured [RollbackCheckpoint]. */
  @Synchronized
  fun restore(checkpoint: RollbackCheckpoint) {
    writeState = checkpoint.forwarding
    restoreStatefulExterns(checkpoint.statefulExterns)
  }

  /**
   * Checkpoint of all mutable state for rollback. Bundles the forwarding snapshot (tables, PRE,
   * profiles, etc.) with stateful externs (direct counters, registers) that live outside the
   * forwarding snapshot because they are mutated lock-free during packet processing.
   */
  class RollbackCheckpoint
  internal constructor(
    internal val forwarding: ForwardingSnapshot,
    internal val statefulExterns: StatefulExternCheckpoint,
  )

  private fun snapshotStatefulExterns(): StatefulExternCheckpoint =
    StatefulExternCheckpoint(
      directCounters = snapshotDirectCounters(),
      indirectCounters = snapshotIndirectCounters(),
      registers = snapshotRegisters(),
    )

  private fun restoreStatefulExterns(saved: StatefulExternCheckpoint) {
    restoreDirectCounters(saved.directCounters)
    restoreIndirectCounters(saved.indirectCounters)
    restoreRegisters(saved.registers)
  }

  private fun snapshotDirectCounters(): Map<TableEntry, LongArray> =
    directCounterData.entries.associate { (k, v) -> k to longArrayOf(v.get(0), v.get(1)) }

  private fun restoreDirectCounters(saved: Map<TableEntry, LongArray>) {
    directCounterData.clear()
    for ((entry, arr) in saved) {
      directCounterData[entry] = AtomicLongArray(arr)
    }
  }

  private fun snapshotIndirectCounters(): Map<Int, Map<Int, LongArray>> =
    indirectCounterData.entries.associate { (counterId, counters) ->
      counterId to
        counters.entries.associate { (idx, arr) -> idx to longArrayOf(arr.get(0), arr.get(1)) }
    }

  private fun restoreIndirectCounters(saved: Map<Int, Map<Int, LongArray>>) {
    indirectCounterData.clear()
    for ((counterId, counters) in saved) {
      val restored = ConcurrentHashMap<Int, AtomicLongArray>()
      for ((idx, arr) in counters) {
        restored[idx] = AtomicLongArray(arr)
      }
      indirectCounterData[counterId] = restored
    }
  }

  private fun snapshotRegisters(): Map<String, Map<Int, Value>> =
    registers.entries.associate { (k, v) -> k to HashMap(v) }

  private fun restoreRegisters(saved: Map<String, Map<Int, Value>>) {
    registers.clear()
    for ((name, values) in saved) {
      registers[name] = ConcurrentHashMap(values)
    }
  }

  // -------------------------------------------------------------------------
  // Read
  // -------------------------------------------------------------------------

  /** Resolves table names for a read, optionally restricted to [scope]. */
  private fun resolveTableNames(tableId: Int, scope: Set<String>? = null): Collection<String> {
    if (tableId == 0) return scope ?: tableNameById.values
    val name = tableNameById[tableId] ?: return emptyList()
    if (scope != null && name !in scope) return emptyList()
    return listOf(name)
  }

  /** Returns entries from [tableName], filtered by match key if [matchFilter] is non-null. */
  private fun filteredEntries(
    tableName: String,
    matchFilter: P4RuntimeOuterClass.TableEntry?,
  ): List<P4RuntimeOuterClass.TableEntry> {
    val entries = snapshot.tables[tableName] ?: return emptyList()
    if (matchFilter == null) return entries
    // P4Runtime spec §9.1: match key + priority uniquely identify an entry.
    return listOfNotNull(entries.find { it.sameKey(matchFilter) })
  }

  /**
   * Generic read for a two-level profile map (profile_id → entry_id → value).
   * - `profileId=0` → wildcard: all values from all profiles.
   * - `profileId=N, entryId=0` → all values from profile N.
   * - `profileId=N, entryId=M` → single value (N, M).
   */
  private fun <V> readProfileEntities(
    storage: Map<Int, Map<Int, V>>,
    profileId: Int,
    entryId: Int,
    toEntity: (V) -> P4RuntimeOuterClass.Entity,
  ): List<P4RuntimeOuterClass.Entity> {
    val sources =
      if (profileId == 0) storage.values.flatMap { it.values }
      else {
        val entries = storage[profileId] ?: return emptyList()
        if (entryId != 0) listOfNotNull(entries[entryId]) else entries.values.toList()
      }
    return sources.map(toEntity)
  }

  fun readProfileMembers(
    filter: P4RuntimeOuterClass.ActionProfileMember =
      P4RuntimeOuterClass.ActionProfileMember.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> =
    readProfileEntities(snapshot.profileMembers, filter.actionProfileId, filter.memberId) {
      P4RuntimeOuterClass.Entity.newBuilder().setActionProfileMember(it).build()
    }

  fun readProfileGroups(
    filter: P4RuntimeOuterClass.ActionProfileGroup =
      P4RuntimeOuterClass.ActionProfileGroup.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> =
    readProfileEntities(snapshot.profileGroups, filter.actionProfileId, filter.groupId) {
      P4RuntimeOuterClass.Entity.newBuilder().setActionProfileGroup(it).build()
    }

  /**
   * Returns true if the table with p4info [tableId] has an entry with match field [fieldId] equal
   * to [value].
   *
   * Used by `@refers_to` referential integrity validation during [P4RuntimeService] writes. Reads
   * from [writeState] (not [snapshot]) because it validates against uncommitted state within a
   * write batch. Must only be called under the control-plane write mutex.
   */
  fun hasEntryWithFieldValue(tableId: Int, fieldId: Int, value: ByteString): Boolean {
    val tableName = tableNameById[tableId] ?: return false
    return writeState.tables[tableName]?.any { entry ->
      entry.matchList.any { fm ->
        fm.fieldId == fieldId &&
          when (fm.fieldMatchTypeCase) {
            P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT -> fm.exact.value == value
            P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL -> fm.optional.value == value
            P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY,
            P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM,
            P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE,
            P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
            P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
            null -> false
          }
      }
    } ?: false
  }

  // -------------------------------------------------------------------------
  // Lookup
  // -------------------------------------------------------------------------

  data class MemberAction(
    val memberId: Int,
    val actionName: String,
    val params: List<P4RuntimeOuterClass.Action.Param>,
  )

  data class LookupResult(
    val hit: Boolean,
    val entry: TableEntry?,
    val actionName: String,
    val actionParams: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
    val members: List<MemberAction>? = null,
  )

  /**
   * Looks up [keyValues] in [tableName]. Returns the best-matching entry or, on a miss, the default
   * action.
   *
   * For LPM tables, "best match" means the entry with the longest prefix. For ternary tables, "best
   * match" means the entry with the highest priority.
   */
  fun lookup(tableName: String, keyValues: List<Pair<String, Value>>): LookupResult {
    forcedHits[tableName]?.let {
      return LookupResult(true, null, it)
    }

    val entries = snapshot.tables[tableName] ?: emptyList<TableEntry>()
    val default = snapshot.defaultActions[tableName] ?: DefaultAction("NoAction")

    // Index key values by field ID for O(1) array lookup in scoreEntry.
    val keyByFieldId =
      if (keyValues.isEmpty()) emptyArray()
      else arrayOfNulls<Value>(keyValues.maxOf { it.first.toInt() } + 1)
    for ((name, value) in keyValues) keyByFieldId[name.toInt()] = value

    var bestEntry: TableEntry? = null
    var bestScore = -1L
    for (j in 0 until entries.size) {
      val entry = entries[j]
      val score = scoreEntry(entry, keyByFieldId) ?: continue
      if (score > bestScore) {
        bestScore = score
        bestEntry = entry
      }
    }

    val entry = bestEntry ?: return LookupResult(false, null, default.name, default.params)
    val tableAction = entry.action

    // Action profile group: resolve group → members → individual actions.
    if (tableAction.hasActionProfileGroupId() && tableAction.actionProfileGroupId != 0) {
      val profileId =
        tableActionProfile[tableName] ?: error("table $tableName has no action profile")
      val group =
        snapshot.profileGroups[profileId]?.get(tableAction.actionProfileGroupId)
          ?: error("unknown group ${tableAction.actionProfileGroupId} in profile $profileId")
      val members =
        group.membersList.map { groupMember ->
          val member =
            snapshot.profileMembers[profileId]?.get(groupMember.memberId)
              ?: error("unknown member ${groupMember.memberId} in profile $profileId")
          MemberAction(
            groupMember.memberId,
            resolveActionName(member.action.actionId),
            member.action.paramsList,
          )
        }
      // Use the first member's action name for the table_lookup event.
      val actionName = members.firstOrNull()?.actionName ?: "NoAction"
      return LookupResult(true, entry, actionName, members = members)
    }

    // One-shot action selector (P4Runtime spec §9.2.3): the entry embeds actions inline.
    // Member IDs are synthetic (list indices) since one-shot entries have no real member IDs.
    if (tableAction.hasActionProfileActionSet()) {
      val members =
        tableAction.actionProfileActionSet.actionProfileActionsList.mapIndexed { i, action ->
          MemberAction(i, resolveActionName(action.action.actionId), action.action.paramsList)
        }
      val actionName = members.firstOrNull()?.actionName ?: "NoAction"
      return LookupResult(true, entry, actionName, members = members)
    }

    // Action profile member (direct, no group): resolve to a single action.
    if (tableAction.hasActionProfileMemberId() && tableAction.actionProfileMemberId != 0) {
      val profileId =
        tableActionProfile[tableName] ?: error("table $tableName has no action profile")
      val member =
        snapshot.profileMembers[profileId]?.get(tableAction.actionProfileMemberId)
          ?: error("unknown member ${tableAction.actionProfileMemberId} in profile $profileId")
      return LookupResult(true, entry, resolveActionName(member.action.actionId))
    }

    return LookupResult(true, entry, resolveActionName(entry.action.action.actionId))
  }

  private fun formatOptions(options: List<String>): String =
    when {
      options.isEmpty() -> "none"
      options.size <= MAX_DISPLAYED_OPTIONS -> options.joinToString(", ")
      else ->
        options.take(MAX_DISPLAYED_OPTIONS).joinToString(", ") +
          " ... and ${options.size - MAX_DISPLAYED_OPTIONS} more"
    }

  private fun resolveActionName(actionId: Int): String =
    actionNameById[actionId]
      ?: error(
        "unknown action ID $actionId " +
          "(valid actions: ${formatOptions(actionNameById.entries.map {
            "'${it.value}' (${it.key})"
          })})"
      )

  /** Resolves an action name (alias or behavioral) to its behavioral name. */
  fun resolveActionByAlias(name: String): String? {
    if (name in actionIdByName) return name
    // Exact alias match.
    actionAliasByName.entries
      .find { it.value == name }
      ?.let {
        return it.key
      }
    // Fuzzy match: nested controls use underscored behavioral names for dotted aliases.
    val candidates = actionIdByName.keys.toList()
    val underscored = name.replace('.', '_')
    candidates
      .find { it.endsWith("_$name") || it.endsWith("_$underscored") }
      ?.let {
        return it
      }
    return null
  }

  /** Returns the short p4info alias for a behavioral action name, or the name itself if unknown. */
  fun actionDisplayName(behavioralName: String): String =
    actionAliasByName[behavioralName] ?: behavioralName

  /** Returns the short p4info alias for a behavioral table name, or the name itself if unknown. */
  fun tableDisplayName(behavioralName: String): String =
    tableAliasByName[behavioralName] ?: behavioralName

  /** Returns the short p4info alias for a behavioral name (table or action), or the name itself. */
  fun displayName(behavioralName: String): String =
    tableAliasByName[behavioralName] ?: actionAliasByName[behavioralName] ?: behavioralName

  /**
   * Scores an entry against [keyByFieldId]. Returns null if the entry does not match. Returns a
   * non-negative score where a higher value means a better match (used to implement LPM
   * longest-prefix and ternary priority semantics).
   */
  private fun scoreEntry(entry: TableEntry, keyByFieldId: Array<Value?>): Long? {
    var score = 0L
    val matchList = entry.matchList
    for (i in 0 until matchList.size) {
      val match = matchList[i]
      val value = keyByFieldId.getOrNull(match.fieldId) ?: return null

      val bits =
        when (value) {
          is BitVal -> value.bits
          is BoolVal -> if (value.value) BOOL_TRUE_BITS else BOOL_FALSE_BITS
          else -> return null
        }

      if (!matchesFieldMatch(bits, match)) return null

      // Accumulate score for priority-based match kinds.
      // Exact and optional don't contribute — all exact fields either match or don't.
      when (match.fieldMatchTypeCase) {
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM ->
          score += match.lpm.prefixLen.toLong()
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY ->
          score += entry.priority.toLong()
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE -> score += entry.priority.toLong()
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT,
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL,
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
        null -> {}
      }
    }
    return score
  }

  companion object {
    private const val MAX_DISPLAYED_OPTIONS = 10
    private val BOOL_TRUE_BITS = BitVector.ofInt(1, 1)
    private val BOOL_FALSE_BITS = BitVector.ofInt(0, 1)
  }
}

/** Wraps a PRE sub-entry (clone session or multicast group) into an [Entity] proto. */
internal fun <T> buildPreEntity(
  entry: T,
  setter: P4RuntimeOuterClass.PacketReplicationEngineEntry.Builder.(T) -> Unit,
): P4RuntimeOuterClass.Entity =
  P4RuntimeOuterClass.Entity.newBuilder()
    .setPacketReplicationEngineEntry(
      P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder().also { it.setter(entry) }
    )
    .build()
