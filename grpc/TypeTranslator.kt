package fourward.grpc

import com.google.protobuf.ByteString
import fourward.TranslationEntry
import fourward.TypeTranslation
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.config.v1.P4Types
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.PacketIn
import p4.v1.P4RuntimeOuterClass.PacketOut
import p4.v1.P4RuntimeOuterClass.Update

/** Interprets a [ByteString] as an unsigned big-endian integer. Inverse of [encodeMinWidth]. */
fun ByteString.toUnsignedInt(): Int =
  toByteArray().fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

/** Formats a [ByteString] as a lowercase hex string (e.g. "0a01ff"). */
fun ByteString.toHex(): String = toByteArray().joinToString("") { "%02x".format(it) }

/**
 * Minimum-width unsigned big-endian encoding of a non-negative integer (P4Runtime canonical form).
 */
fun encodeMinWidth(value: Int): ByteString {
  if (value == 0) return ByteString.copyFrom(byteArrayOf(0))
  val bytes = mutableListOf<Byte>()
  var v = value
  while (v > 0) {
    bytes.add(0, (v and 0xFF).toByte())
    v = v shr 8
  }
  return ByteString.copyFrom(bytes.toByteArray())
}

/**
 * Minimum-width unsigned big-endian encoding of a non-negative [BigInteger] (P4Runtime canonical
 * form). Handles values wider than [Int], as controller-header fields may exceed 32 bits.
 */
fun encodeMinWidth(value: BigInteger): ByteString {
  require(value.signum() >= 0) { "value must be non-negative: $value" }
  if (value.signum() == 0) return ByteString.copyFrom(byteArrayOf(0))
  // BigInteger.toByteArray() is big-endian two's complement; a positive value whose top bit is set
  // gets a leading 0x00 sign byte. Drop it to reach the canonical minimum-width form.
  val raw = value.toByteArray()
  val start = if (raw[0].toInt() == 0) 1 else 0
  return ByteString.copyFrom(raw, start, raw.size - start)
}

/** The P4Runtime (controller-facing) value for a translated type. */
sealed class P4rtValue {
  data class Bitstring(val value: ByteString) : P4rtValue()

  data class Str(val value: String) : P4rtValue()
}

/** Thrown when a value cannot be translated (no mapping and auto-allocate is off). */
class TranslationException(message: String) : RuntimeException(message)

/**
 * Direction of a `@p4runtime_translation` translation. Reads and Writes go through symmetric pairs
 * of helpers in [TypeTranslator]; carrying the direction as a named enum (rather than a raw
 * boolean) makes the call sites self-explanatory and prevents accidental inversion.
 */
private enum class Direction {
  /** P4Runtime SDN form → data-plane representation (Write). */
  TO_DATAPLANE,
  /** Data-plane representation → P4Runtime SDN form (Read). */
  TO_P4RT,
}

/**
 * Translates between P4Runtime port IDs and dataplane port numbers.
 *
 * Most `@p4runtime_translation` types appear only in dynamically-typed table entry fields (match
 * fields, action params), where [TypeTranslator] discovers the type from p4info field-level
 * metadata. Ports are different: they appear in hardcoded proto fields across multiple messages —
 * `InputPacket.ingress_port`, `OutputPacket.egress_port`, `PacketIn`/`PacketOut` metadata,
 * `CloneSessionEntry.replicas` — so the server needs the port type as a pipeline-wide property, not
 * per-field.
 *
 * The port type is derived from `controller_packet_metadata` in the p4info: metadata fields whose
 * [`type_name`](https://github.com/p4lang/p4runtime/blob/main/proto/p4/config/v1/p4info.proto#L453)
 * resolves to a `@p4runtime_translation`-annotated type in `type_info` identify the port
 * translation.
 */
class PortTranslator internal constructor(private val table: TranslationTable) {
  /** Translates a P4Runtime port ID to a dataplane port number. */
  fun p4rtToDataplane(p4rtPort: ByteString): Int {
    val dp =
      if (table.isStringType) {
        table.lookupOrAllocateString(p4rtPort.toStringUtf8())
      } else {
        table.lookupOrAllocateBitstring(p4rtPort)
      }
    return dp.toUnsignedInt()
  }

  /**
   * Resolves a P4RT port name to a dataplane port number using only explicit mappings. Returns null
   * if no explicit mapping exists — never auto-allocates.
   *
   * Only works for `sdn_string` port types (e.g. SAI P4). `sdn_bitwidth` types are not supported
   * because the caller provides a string name, not a bitstring.
   */
  fun resolveExplicit(p4rtName: String): Int? =
    table.lookupStringExplicit(p4rtName)?.toUnsignedInt()

  /** Translates a dataplane port number to a P4Runtime port ID, or null if no mapping exists. */
  fun dataplaneToP4rt(dataplanePort: Int): ByteString? =
    when (val p4rtValue = table.reverseLookupOrNull(encodeMinWidth(dataplanePort))) {
      is P4rtValue.Str -> ByteString.copyFromUtf8(p4rtValue.value)
      is P4rtValue.Bitstring -> p4rtValue.value
      null -> null
    }
}

/**
 * Bidirectional mapping between P4Runtime (controller-facing) and data-plane values for
 * `@p4runtime_translation`-annotated types.
 *
 * Translation tables are keyed by **fully qualified type name** (from p4info `type_info`), not by
 * URI. This is because SAI P4 uses an empty URI for all translated types, relying on type names for
 * disambiguation. See [docs/TYPE_TRANSLATION.md] for details.
 *
 * Supports three modes per type:
 * - **Explicit**: all mappings provided upfront; unknown values are rejected.
 * - **Auto-allocate**: data-plane values assigned sequentially on first use.
 * - **Hybrid**: explicit pins for known values, auto-allocate for the rest.
 *
 * When no [TypeTranslation] is provided for a type, auto-allocation is used by default.
 *
 * Translates action parameters, match fields (exact/optional), and PacketIO metadata.
 */
class TypeTranslator
private constructor(
  private val tables: ConcurrentHashMap<String, TranslationTable>,
  private val paramTypeNames: Map<Long, String>,
  private val matchFieldTypeNames: Map<Long, String>,
  // Separate maps per direction: packet_out and packet_in metadata IDs can
  // overlap (both use @id(1), @id(2), …) but refer to different fields.
  private val packetOutMetadataTypeNames: Map<Int, String>,
  private val packetInMetadataTypeNames: Map<Int, String>,
  /** Port translator for hardcoded port fields, or null if the port type is not translated. */
  val portTranslator: PortTranslator?,
) {

  /** True if this translator has any translated types to handle. */
  val hasTranslations: Boolean =
    tables.isNotEmpty() ||
      paramTypeNames.isNotEmpty() ||
      matchFieldTypeNames.isNotEmpty() ||
      packetOutMetadataTypeNames.isNotEmpty() ||
      packetInMetadataTypeNames.isNotEmpty()

  /**
   * Translates a P4Runtime bitstring value to its data-plane representation.
   *
   * For auto-allocate types, creates a new mapping on first use.
   */
  fun p4rtToDataplane(typeName: String, p4rtValue: ByteArray): ByteArray =
    getOrCreateTable(typeName)
      .lookupOrAllocateBitstring(ByteString.copyFrom(p4rtValue))
      .toByteArray()

  /**
   * Translates a P4Runtime string value to its data-plane representation.
   *
   * For auto-allocate types, creates a new mapping on first use.
   */
  fun p4rtToDataplane(typeName: String, p4rtStr: String): ByteArray =
    getOrCreateTable(typeName).lookupOrAllocateString(p4rtStr).toByteArray()

  /**
   * Translates a data-plane value back to its P4Runtime representation.
   *
   * @throws TranslationException if no reverse mapping exists.
   */
  fun dataplaneToP4rt(typeName: String, dataplaneValue: ByteArray): P4rtValue =
    getOrCreateTable(typeName).reverseLookup(ByteString.copyFrom(dataplaneValue))

  /** Gets an existing table or creates a default auto-allocate table for unknown types. */
  private fun getOrCreateTable(typeName: String): TranslationTable =
    tables.computeIfAbsent(typeName) { TranslationTable(autoAllocate = true) }

  // ---------------------------------------------------------------------------
  // P4Runtime Write/Read translation
  // ---------------------------------------------------------------------------

  /** Translates a Write update from P4Runtime to data-plane representation. */
  fun translateForWrite(update: Update): Update =
    update
      .toBuilder()
      .setEntity(translateEntity(update.entity, direction = Direction.TO_DATAPLANE))
      .build()

  /** Translates a Read entity from data-plane to P4Runtime representation. */
  fun translateForRead(entity: Entity): Entity =
    translateEntity(entity, direction = Direction.TO_P4RT)

  /**
   * Translates [entity] in the given [direction]. P4Runtime spec §11.2 requires that whatever
   * `TO_DATAPLANE` does on Write is reversed by `TO_P4RT` on Read; this single switch keeps the two
   * directions co-located so the pair stays obviously symmetric in code review. Structural
   * enforcement of "every supported entity type round-trips" lives in `RoundTripTest` — this
   * dispatcher is the shape that makes that coverage easy to reason about.
   */
  private fun translateEntity(entity: Entity, direction: Direction): Entity =
    when (entity.entityCase) {
      Entity.EntityCase.TABLE_ENTRY -> {
        val translated = translateTableEntry(entity.tableEntry, direction)
        if (translated == null) entity else entity.toBuilder().setTableEntry(translated).build()
      }
      Entity.EntityCase.ACTION_PROFILE_MEMBER -> {
        val member = entity.actionProfileMember
        val translated = translateAction(member.action, direction)
        if (translated == null) entity
        else
          entity
            .toBuilder()
            .setActionProfileMember(member.toBuilder().setAction(translated))
            .build()
      }
      Entity.EntityCase.DIRECT_COUNTER_ENTRY -> {
        // Direct counters are addressed by the table entry they attach to; the embedded
        // TableEntry carries match values that may be translated.
        val direct = entity.directCounterEntry
        val translated = translateTableEntry(direct.tableEntry, direction)
        if (translated == null) entity
        else
          entity
            .toBuilder()
            .setDirectCounterEntry(direct.toBuilder().setTableEntry(translated))
            .build()
      }
      Entity.EntityCase.DIRECT_METER_ENTRY -> {
        val direct = entity.directMeterEntry
        val translated = translateTableEntry(direct.tableEntry, direction)
        if (translated == null) entity
        else
          entity
            .toBuilder()
            .setDirectMeterEntry(direct.toBuilder().setTableEntry(translated))
            .build()
      }
      Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY -> {
        val translated = translatePreReplicas(entity.packetReplicationEngineEntry, direction)
        if (translated == null) entity
        else entity.toBuilder().setPacketReplicationEngineEntry(translated).build()
      }
      Entity.EntityCase.ACTION_PROFILE_GROUP,
      Entity.EntityCase.COUNTER_ENTRY,
      Entity.EntityCase.METER_ENTRY,
      Entity.EntityCase.REGISTER_ENTRY,
      Entity.EntityCase.VALUE_SET_ENTRY,
      Entity.EntityCase.DIGEST_ENTRY,
      Entity.EntityCase.EXTERN_ENTRY,
      Entity.EntityCase.ENTITY_NOT_SET,
      null -> entity
    }

  /**
   * Translates `Replica.port` on every replica of a [PacketReplicationEngineEntry], in either
   * direction. Returns null if no port translation is configured for this pipeline (in which case
   * replicas pass through untouched).
   *
   * `TO_DATAPLANE` (Write): forward-allocate — the controller's P4Runtime-encoded port bytes become
   * the simulator's raw dataplane integer, and the [PortTranslator]'s table is updated so
   * subsequent reads can reverse the mapping.
   *
   * `TO_P4RT` (Read): reverse-lookup — the simulator's dataplane integer becomes the controller-
   * facing P4Runtime bytes. Replicas without an existing reverse mapping (e.g. those installed via
   * the deprecated `Replica.egress_port` int32) pass through unchanged.
   */
  private fun translatePreReplicas(
    pre: p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry,
    direction: Direction,
  ): p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry? {
    val pt = portTranslator ?: return null

    fun translate(replica: p4.v1.P4RuntimeOuterClass.Replica): p4.v1.P4RuntimeOuterClass.Replica {
      if (replica.port.isEmpty) return replica
      val newPort: ByteString =
        when (direction) {
          Direction.TO_DATAPLANE -> encodeMinWidth(pt.p4rtToDataplane(replica.port))
          Direction.TO_P4RT -> pt.dataplaneToP4rt(replica.port.toUnsignedInt()) ?: return replica
        }
      return replica.toBuilder().setPort(newPort).build()
    }

    return when (pre.typeCase) {
      p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry.TypeCase.MULTICAST_GROUP_ENTRY -> {
        val group = pre.multicastGroupEntry
        pre
          .toBuilder()
          .setMulticastGroupEntry(
            group.toBuilder().clearReplicas().addAllReplicas(group.replicasList.map(::translate))
          )
          .build()
      }
      p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry.TypeCase.CLONE_SESSION_ENTRY -> {
        val session = pre.cloneSessionEntry
        pre
          .toBuilder()
          .setCloneSessionEntry(
            session
              .toBuilder()
              .clearReplicas()
              .addAllReplicas(session.replicasList.map(::translate))
          )
          .build()
      }
      p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry.TypeCase.TYPE_NOT_SET,
      null -> null
    }
  }

  /**
   * Translates match field values and action parameter values in a table entry. Returns null if
   * nothing was translated.
   */
  private fun translateTableEntry(
    entry: p4.v1.P4RuntimeOuterClass.TableEntry,
    direction: Direction,
  ): p4.v1.P4RuntimeOuterClass.TableEntry? {
    val translatedMatches = translateMatchFields(entry.tableId, entry.matchList, direction)
    val translatedAction = translateTableAction(entry.action, direction)
    if (translatedMatches == null && translatedAction == null) return null
    val builder = entry.toBuilder()
    if (translatedMatches != null) {
      builder.clearMatch().addAllMatch(translatedMatches)
    }
    if (translatedAction != null) {
      builder.setAction(translatedAction)
    }
    return builder.build()
  }

  // ---------------------------------------------------------------------------
  // PacketIO metadata translation
  // ---------------------------------------------------------------------------

  /** Translates PacketOut metadata from P4Runtime to data-plane representation. */
  fun translatePacketOut(packetOut: PacketOut): PacketOut {
    if (packetOutMetadataTypeNames.isEmpty()) return packetOut
    val translated =
      translateMetadata(
        packetOutMetadataTypeNames,
        packetOut.metadataList,
        direction = Direction.TO_DATAPLANE,
      ) ?: return packetOut
    return packetOut.toBuilder().clearMetadata().addAllMetadata(translated).build()
  }

  /**
   * Translates PacketIn metadata from data-plane to P4Runtime representation.
   *
   * Reverse translation is lenient: a metadata value with no SDN mapping is passed through as its
   * raw data-plane value rather than throwing. PacketIn carries whatever ports the P4 program
   * computed — including architectural sentinels like the drop port that the control plane never
   * named — and a single such packet must not tear down PacketIn delivery for the whole stream.
   * (Table-entry reads stay strict: a stored value always came from a forward translation, so a
   * missing reverse mapping there is a real inconsistency worth surfacing.)
   */
  fun translatePacketIn(packetIn: PacketIn): PacketIn {
    if (packetInMetadataTypeNames.isEmpty()) return packetIn
    val translated =
      translateMetadata(
        packetInMetadataTypeNames,
        packetIn.metadataList,
        direction = Direction.TO_P4RT,
        lenient = true,
      ) ?: return packetIn
    return packetIn.toBuilder().clearMetadata().addAllMetadata(translated).build()
  }

  /**
   * Translates action params within a [TableAction], handling direct actions and one-shot action
   * profile action sets.
   */
  private fun translateTableAction(
    tableAction: p4.v1.P4RuntimeOuterClass.TableAction,
    direction: Direction,
  ): p4.v1.P4RuntimeOuterClass.TableAction? {
    if (tableAction.hasAction()) {
      val translated = translateAction(tableAction.action, direction) ?: return null
      return tableAction.toBuilder().setAction(translated).build()
    }
    if (tableAction.hasActionProfileActionSet()) {
      var changed = false
      val translatedActions =
        tableAction.actionProfileActionSet.actionProfileActionsList.map { profileAction ->
          val translated = translateAction(profileAction.action, direction)
          if (translated != null) {
            changed = true
            profileAction.toBuilder().setAction(translated).build()
          } else {
            profileAction
          }
        }
      if (!changed) return null
      return tableAction
        .toBuilder()
        .setActionProfileActionSet(
          tableAction.actionProfileActionSet
            .toBuilder()
            .clearActionProfileActions()
            .addAllActionProfileActions(translatedActions)
        )
        .build()
    }
    return null
  }

  /** Translates params of a single [Action], returning null if no translation was needed. */
  private fun translateAction(
    action: p4.v1.P4RuntimeOuterClass.Action,
    direction: Direction,
  ): p4.v1.P4RuntimeOuterClass.Action? {
    val translated = translateParams(action.actionId, action.paramsList, direction) ?: return null
    return action.toBuilder().clearParams().addAllParams(translated).build()
  }

  // ---------------------------------------------------------------------------
  // Internal translation helpers
  // ---------------------------------------------------------------------------

  private fun translateParams(
    actionId: Int,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param>,
    direction: Direction,
  ): List<p4.v1.P4RuntimeOuterClass.Action.Param>? {
    var changed = false
    val result =
      params.map { param ->
        val typeName = paramTypeNames[packKey(actionId, param.paramId)]
        if (typeName != null) {
          changed = true
          val translated = translateValue(getOrCreateTable(typeName), param.value, direction)
          param.toBuilder().setValue(translated).build()
        } else {
          param
        }
      }
    return if (changed) result else null
  }

  private fun translateMatchFields(
    tableId: Int,
    matches: List<p4.v1.P4RuntimeOuterClass.FieldMatch>,
    direction: Direction,
  ): List<p4.v1.P4RuntimeOuterClass.FieldMatch>? {
    var changed = false
    val result =
      matches.map { match ->
        val typeName = matchFieldTypeNames[packKey(tableId, match.fieldId)]
        if (typeName != null) {
          changed = true
          val table = getOrCreateTable(typeName)
          when (match.fieldMatchTypeCase) {
            p4.v1.P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT -> {
              val translated = translateValue(table, match.exact.value, direction)
              match
                .toBuilder()
                .setExact(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(translated)
                )
                .build()
            }
            p4.v1.P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL -> {
              val translated = translateValue(table, match.optional.value, direction)
              match
                .toBuilder()
                .setOptional(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(translated)
                )
                .build()
            }
            // Ternary/LPM/Range on translated types is nonsensical — pass through.
            p4.v1.P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY,
            p4.v1.P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM,
            p4.v1.P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE,
            p4.v1.P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
            p4.v1.P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
            null -> match
          }
        } else {
          match
        }
      }
    return if (changed) result else null
  }

  private fun translateMetadata(
    typeNameMap: Map<Int, String>,
    metadata: List<p4.v1.P4RuntimeOuterClass.PacketMetadata>,
    direction: Direction,
    lenient: Boolean = false,
  ): List<p4.v1.P4RuntimeOuterClass.PacketMetadata>? {
    var changed = false
    val result =
      metadata.map { meta ->
        val typeName = typeNameMap[meta.metadataId]
        if (typeName != null) {
          val translated =
            translateValue(getOrCreateTable(typeName), meta.value, direction, lenient)
          changed = true
          meta.toBuilder().setValue(translated).build()
        } else {
          meta
        }
      }
    return if (changed) result else null
  }

  /**
   * Translates a single ByteString value forward (P4RT→DP) or reverse (DP→P4RT).
   *
   * For `sdn_string` tables, the P4Runtime value is a UTF-8 string encoded in the proto `bytes`
   * field (per P4Runtime spec §8.3 — there is no separate string field).
   *
   * When [lenient] (reverse direction only), a value with no SDN mapping is returned unchanged
   * instead of throwing — see [translatePacketIn].
   */
  private fun translateValue(
    table: TranslationTable,
    value: ByteString,
    direction: Direction,
    lenient: Boolean = false,
  ): ByteString =
    when (direction) {
      Direction.TO_DATAPLANE ->
        if (table.isStringType) {
          table.lookupOrAllocateString(value.toStringUtf8())
        } else {
          table.lookupOrAllocateBitstring(value)
        }
      Direction.TO_P4RT ->
        when (
          val p4rtValue =
            if (lenient) table.reverseLookupOrNull(value) else table.reverseLookup(value)
        ) {
          is P4rtValue.Bitstring -> p4rtValue.value
          is P4rtValue.Str -> ByteString.copyFromUtf8(p4rtValue.value)
          null -> value // No SDN name for this data-plane value; deliver it as-is.
        }
    }

  companion object {
    /**
     * Creates a TypeTranslator from translation configurations.
     *
     * For use without p4info — the translator supports direct type-name-based lookups via
     * [p4rtToDataplane] and [dataplaneToP4rt], but not message-level translation methods (which
     * require p4info to map field IDs to type names).
     */
    fun create(translations: List<TypeTranslation> = emptyList()): TypeTranslator =
      TypeTranslator(
        buildTables(translations, resolveKey = { it.resolveKey() }),
        paramTypeNames = emptyMap(),
        matchFieldTypeNames = emptyMap(),
        packetOutMetadataTypeNames = emptyMap(),
        packetInMetadataTypeNames = emptyMap(),
        portTranslator = null,
      )

    /**
     * Creates a TypeTranslator from p4info and translation configurations.
     *
     * Discovers translated types from p4info and maps field IDs to type names, enabling translation
     * of action parameters, match fields, and PacketIO metadata in P4Runtime messages.
     *
     * @param portTypeName the fully qualified P4 type name for ports (from
     *   `Architecture.port_type_name` in the compiled IR). Empty if ports use a bare `bit<N>` (no
     *   newtype). A [PortTranslator] is created only when this type also has
     *   `@p4runtime_translation`.
     */
    fun create(
      p4info: P4Info,
      translations: List<TypeTranslation> = emptyList(),
      portTypeName: String = "",
    ): TypeTranslator {
      val translatedTypes =
        p4info.typeInfo.newTypesMap.filter { (_, spec) -> spec.hasTranslatedType() }

      // Build URI → type name index for resolving TypeTranslation entries that use type_uri.
      val uriToTypeNames = mutableMapOf<String, MutableList<String>>()
      for ((name, spec) in translatedTypes) {
        val uri = spec.translatedType.uri
        if (uri.isNotEmpty()) {
          uriToTypeNames.getOrPut(uri) { mutableListOf() }.add(name)
        }
      }

      val paramTypeNames = buildParamTypeNames(p4info, translatedTypes)
      val matchFieldTypeNames = buildMatchFieldTypeNames(p4info, translatedTypes)
      val (packetOutMetadataTypeNames, packetInMetadataTypeNames) =
        buildPacketMetadataTypeNames(p4info, translatedTypes)

      val stringTypeNames =
        translatedTypes.filter { (_, spec) -> spec.translatedType.hasSdnString() }.keys.toSet()

      val tables =
        buildTables(translations, stringTypeNames) { translation ->
          resolveTranslationKey(translation, uriToTypeNames)
        }
      // Pre-create tables for string types that have no translation config,
      // so getOrCreateTable finds them with the correct isStringType.
      for (typeName in stringTypeNames) {
        tables.computeIfAbsent(typeName) {
          TranslationTable(autoAllocate = true, isStringType = true)
        }
      }

      // Create PortTranslator if the port type has @p4runtime_translation.
      val portTranslator =
        if (portTypeName.isNotEmpty() && portTypeName in translatedTypes) {
          val isStringType = portTypeName in stringTypeNames
          val portTable =
            tables.computeIfAbsent(portTypeName) {
              TranslationTable(autoAllocate = true, isStringType = isStringType)
            }
          PortTranslator(portTable)
        } else {
          null
        }

      return TypeTranslator(
        tables,
        paramTypeNames,
        matchFieldTypeNames,
        packetOutMetadataTypeNames,
        packetInMetadataTypeNames,
        portTranslator,
      )
    }

    private fun buildParamTypeNames(
      p4info: P4Info,
      translatedTypes: Map<String, P4Types.P4NewTypeSpec>,
    ): Map<Long, String> {
      val typeNames = mutableMapOf<Long, String>()
      for (action in p4info.actionsList) {
        for (param in action.paramsList) {
          if (!param.hasTypeName()) continue
          if (param.typeName.name !in translatedTypes) continue
          typeNames[packKey(action.preamble.id, param.id)] = param.typeName.name
        }
      }
      return typeNames
    }

    private fun buildMatchFieldTypeNames(
      p4info: P4Info,
      translatedTypes: Map<String, P4Types.P4NewTypeSpec>,
    ): Map<Long, String> {
      val typeNames = mutableMapOf<Long, String>()
      for (table in p4info.tablesList) {
        for (matchField in table.matchFieldsList) {
          if (!matchField.hasTypeName()) continue
          if (matchField.typeName.name !in translatedTypes) continue
          typeNames[packKey(table.preamble.id, matchField.id)] = matchField.typeName.name
        }
      }
      return typeNames
    }

    /**
     * Builds per-direction metadata type name maps. IDs can overlap between packet_out and
     * packet_in (both start @id(1)), so a flat map would cause untranslated fields (like
     * submit_to_ingress) to be incorrectly translated.
     */
    private fun buildPacketMetadataTypeNames(
      p4info: P4Info,
      translatedTypes: Map<String, P4Types.P4NewTypeSpec>,
    ): Pair<Map<Int, String>, Map<Int, String>> {
      val packetOut = mutableMapOf<Int, String>()
      val packetIn = mutableMapOf<Int, String>()
      for (controllerMeta in p4info.controllerPacketMetadataList) {
        val target =
          when (controllerMeta.preamble.name) {
            "packet_out" -> packetOut
            "packet_in" -> packetIn
            else -> continue
          }
        for (metadata in controllerMeta.metadataList) {
          if (!metadata.hasTypeName()) continue
          if (metadata.typeName.name !in translatedTypes) continue
          target[metadata.id] = metadata.typeName.name
        }
      }
      return packetOut to packetIn
    }

    private fun buildTables(
      translations: List<TypeTranslation>,
      stringTypeNames: Set<String> = emptySet(),
      resolveKey: (TypeTranslation) -> String,
    ): ConcurrentHashMap<String, TranslationTable> {
      val tables = ConcurrentHashMap<String, TranslationTable>()
      for (translation in translations) {
        val key = resolveKey(translation)
        tables[key] = TranslationTable.fromProto(translation, isStringType = key in stringTypeNames)
      }
      return tables
    }

    /**
     * Resolves a [TypeTranslation]'s key using the URI → type name index from p4info. If the
     * translation specifies `type_name`, uses it directly. If it specifies `type_uri`, resolves it
     * to a type name; errors if the URI is ambiguous (maps to multiple types).
     */
    private fun resolveTranslationKey(
      translation: TypeTranslation,
      uriToTypeNames: Map<String, List<String>>,
    ): String =
      when (translation.typeCase) {
        TypeTranslation.TypeCase.TYPE_NAME -> translation.typeName
        TypeTranslation.TypeCase.TYPE_URI -> {
          val uri = translation.typeUri
          val names = uriToTypeNames[uri]
          when {
            names == null || names.isEmpty() ->
              throw IllegalArgumentException(
                "TypeTranslation type_uri '$uri' does not match any translated type in p4info"
              )
            names.size > 1 ->
              throw IllegalArgumentException(
                "TypeTranslation type_uri '$uri' is ambiguous — matches types: " +
                  "${names.joinToString()}. Use type_name instead."
              )
            else -> names.single()
          }
        }
        TypeTranslation.TypeCase.TYPE_NOT_SET,
        null -> throw IllegalArgumentException("TypeTranslation must specify type_name or type_uri")
      }

    /**
     * Resolves key from a [TypeTranslation] without p4info. Only `type_name` is accepted —
     * resolving `type_uri` requires p4info, which is not available in this context.
     */
    private fun TypeTranslation.resolveKey(): String =
      when (typeCase) {
        TypeTranslation.TypeCase.TYPE_NAME -> typeName
        TypeTranslation.TypeCase.TYPE_URI ->
          throw IllegalArgumentException(
            "TypeTranslation with type_uri requires p4info for resolution; use type_name instead"
          )
        TypeTranslation.TypeCase.TYPE_NOT_SET,
        null -> throw IllegalArgumentException("TypeTranslation must specify type_name or type_uri")
      }

    /** Packs two IDs into a single Long for fast compound-key lookup. */
    private fun packKey(high: Int, low: Int): Long =
      (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
  }
}

/**
 * Bidirectional mapping table for a single translated type.
 *
 * Thread-safe: all mutating operations are synchronized.
 */
internal class TranslationTable(
  private val autoAllocate: Boolean,
  /** True if this table's P4Runtime values are strings (UTF-8 encoded in proto bytes fields). */
  val isStringType: Boolean = false,
) {

  // Forward maps: P4Runtime → data-plane.
  private val bitstringForward = mutableMapOf<ByteString, ByteString>()
  private val stringForward = mutableMapOf<String, ByteString>()

  // Reverse map: data-plane → P4Runtime.
  private val reverse = mutableMapOf<ByteString, P4rtValue>()

  // Data-plane values claimed by explicit entries (auto-allocator skips these).
  // Stored as integers (not ByteStrings) so width differences don't cause missed collisions.
  private val reservedValues = mutableSetOf<Int>()

  // Counter for sequential auto-allocation.
  private var nextValue = 0

  /** Looks up or auto-allocates a data-plane value for a P4Runtime bitstring. */
  @Synchronized
  fun lookupOrAllocateBitstring(p4rtValue: ByteString): ByteString =
    lookupOrAllocate(
      bitstringForward,
      p4rtValue,
      P4rtValue::Bitstring,
      "no mapping for P4Runtime bitstring 0x${p4rtValue.toHex()} (auto-allocate off)",
    )

  /** Looks up or auto-allocates a data-plane value for a P4Runtime string. */
  @Synchronized
  fun lookupOrAllocateString(p4rtStr: String): ByteString =
    lookupOrAllocate(
      stringForward,
      p4rtStr,
      P4rtValue::Str,
      "no mapping for P4Runtime string '$p4rtStr' (auto-allocate off)",
    )

  private fun putReverse(dataplaneValue: ByteString, p4rtValue: P4rtValue) {
    reverse[encodeMinWidth(dataplaneValue.toUnsignedInt())] = p4rtValue
  }

  /** Reverse-translates a data-plane value to its P4Runtime representation. */
  @Synchronized
  fun reverseLookup(dataplaneValue: ByteString): P4rtValue =
    reverse[dataplaneValue]
      ?: throw TranslationException(
        "no reverse mapping for data-plane value 0x${dataplaneValue.toHex()}"
      )

  /** Like [reverseLookup] but returns null instead of throwing. */
  @Synchronized
  fun reverseLookupOrNull(dataplaneValue: ByteString): P4rtValue? = reverse[dataplaneValue]

  @Synchronized fun lookupStringExplicit(p4rtStr: String): ByteString? = stringForward[p4rtStr]

  private fun <K> lookupOrAllocate(
    forward: MutableMap<K, ByteString>,
    key: K,
    wrapP4rt: (K) -> P4rtValue,
    errorMsg: String,
  ): ByteString {
    forward[key]?.let {
      return it
    }
    if (!autoAllocate) throw TranslationException(errorMsg)
    val dp = allocateNext()
    forward[key] = dp
    putReverse(dp, wrapP4rt(key))
    return dp
  }

  /** Allocates the next available data-plane value, skipping reserved ones. */
  private fun allocateNext(): ByteString {
    while (true) {
      val candidate = nextValue++
      if (candidate !in reservedValues) return encodeMinWidth(candidate)
    }
  }

  companion object {
    /** Builds a TranslationTable from a proto config, pre-populating explicit entries. */
    fun fromProto(proto: TypeTranslation, isStringType: Boolean = false): TranslationTable {
      val table = TranslationTable(autoAllocate = proto.autoAllocate, isStringType = isStringType)
      for (entry in proto.entriesList) {
        val dp = entry.dataplaneValue
        // Normalize to minimum-width so reverse lookups (which use
        // encodeMinWidth) match regardless of the config's byte width.
        val dpNorm = encodeMinWidth(dp.toUnsignedInt())
        table.reservedValues.add(dp.toUnsignedInt())
        table.putReverse(
          dpNorm,
          when (entry.sdnValueCase) {
            TranslationEntry.SdnValueCase.SDN_BITSTRING -> {
              table.bitstringForward[entry.sdnBitstring] = dpNorm
              P4rtValue.Bitstring(entry.sdnBitstring)
            }
            TranslationEntry.SdnValueCase.SDN_STR -> {
              table.stringForward[entry.sdnStr] = dpNorm
              P4rtValue.Str(entry.sdnStr)
            }
            else -> throw IllegalArgumentException("TranslationEntry must have an sdn_value")
          },
        )
      }
      return table
    }
  }
}
