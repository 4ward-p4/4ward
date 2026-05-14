package fourward.grpc

import com.google.protobuf.ByteString
import fourward.simulator.toUnsignedBigInteger
import io.grpc.Status
import io.grpc.StatusException
import java.math.BigInteger
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Validates P4Runtime Write updates against the p4info schema.
 *
 * Enforces P4Runtime spec §9.1: action IDs/refs, action parameter count/IDs, match field IDs/kinds,
 * required exact fields, and priority rules. Bytestring values are checked against the §8.3
 * fits-in-bitwidth rule via [requireFitsInBitwidth] — the validator accepts any length (including
 * the canonical shortest form) as long as the represented integer fits in the P4Info-specified
 * bitwidth.
 *
 * All lookup maps are pre-computed at construction time so [validate] does zero allocations.
 * Constructed once per pipeline load; call [validate] for each update before type translation so
 * SDN-visible values are checked against their P4-program bitwidths (§8.3).
 */
class WriteValidator(p4Info: P4InfoOuterClass.P4Info) {

  private val tableInfoById = p4Info.tablesList.associateBy { it.preamble.id }
  private val actionInfoById = p4Info.actionsList.associateBy { it.preamble.id }

  // Pre-computed per-table: valid action IDs, match field lookup, priority/const.
  private val constTableIds: Set<Int> =
    p4Info.tablesList.filter { it.isConstTable }.map { it.preamble.id }.toSet()

  private val actionRefIdsByTable: Map<Int, Set<Int>> =
    p4Info.tablesList.associate { it.preamble.id to it.actionRefsList.map { r -> r.id }.toSet() }

  private val fieldInfoByTable: Map<Int, Map<Int, P4InfoOuterClass.MatchField>> =
    p4Info.tablesList.associate { it.preamble.id to it.matchFieldsList.associateBy { f -> f.id } }

  private val tableRequiresPriority: Map<Int, Boolean> =
    p4Info.tablesList.associate {
      it.preamble.id to it.matchFieldsList.any { f -> f.matchType in PRIORITY_MATCH_TYPES }
    }

  // Pre-computed per-action: param lookup.
  private val paramInfoByAction: Map<Int, Map<Int, P4InfoOuterClass.Action.Param>> =
    p4Info.actionsList.associate { it.preamble.id to it.paramsList.associateBy { p -> p.id } }

  // Pre-computed per-action-profile: ID lookup and valid action IDs (union from associated tables).
  private val actionProfileInfoById: Map<Int, P4InfoOuterClass.ActionProfile> =
    p4Info.actionProfilesList.associateBy { it.preamble.id }

  private val actionRefIdsByProfile: Map<Int, Set<Int>> =
    p4Info.actionProfilesList.associate { ap ->
      ap.preamble.id to
        ap.tableIdsList.flatMap { tableId -> actionRefIdsByTable[tableId] ?: emptySet() }.toSet()
    }

  /**
   * Validates an update against the p4info schema. Dispatches to entity-specific validation based
   * on the entity type. Throws [StatusException] on spec violations.
   */
  fun validate(update: P4RuntimeOuterClass.Update) {
    val entity = update.entity
    when (entity.entityCase) {
      P4RuntimeOuterClass.Entity.EntityCase.TABLE_ENTRY -> validateTableEntry(update)
      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_MEMBER -> validateMember(update)
      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_GROUP -> validateGroup(update)
      P4RuntimeOuterClass.Entity.EntityCase.EXTERN_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.COUNTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_COUNTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.METER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_METER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.REGISTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.VALUE_SET_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIGEST_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.ENTITY_NOT_SET,
      null -> {}
    }
  }

  // ---------------------------------------------------------------------------
  // Table entry validation (§9.1)
  // ---------------------------------------------------------------------------

  private fun validateTableEntry(update: P4RuntimeOuterClass.Update) {
    val entry = update.entity.tableEntry
    val tableInfo =
      tableInfoById[entry.tableId]
        ?: throw invalidArg(
          "unknown table ID ${entry.tableId} " +
            "(valid tables: ${formatOptions(
              tableInfoById.values.map { "'${it.tableName}' (${it.preamble.id})" }
            )})"
        )

    // §9.1: const tables are immutable — no writes allowed.
    if (entry.tableId in constTableIds) {
      throw invalidArg("table '${tableInfo.tableName}' is const; writes are not allowed")
    }

    if (entry.isDefaultAction) {
      validateDefaultEntry(update, entry, tableInfo)
      return
    }

    // Validate match fields for all operations — DELETE needs a valid key too.
    validateMatchFields(entry, tableInfo)

    // P4Runtime spec §9.1: DELETE only needs the match key; skip content validation.
    if (update.type == P4RuntimeOuterClass.Update.Type.DELETE) return

    // Idle timeout is not supported — reject rather than silently ignoring.
    if (entry.idleTimeoutNs != 0L) {
      throw unimplemented("idle_timeout_ns is not supported on table '${tableInfo.tableName}'")
    }

    if (entry.hasAction()) {
      val tableAction = entry.action
      when (tableAction.typeCase) {
        P4RuntimeOuterClass.TableAction.TypeCase.ACTION ->
          validateAction(tableAction.action, tableInfo)
        // P4Runtime spec §9.2.3: validate each action in a one-shot action set.
        P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_ACTION_SET ->
          for (profileAction in tableAction.actionProfileActionSet.actionProfileActionsList) {
            validateAction(profileAction.action, tableInfo)
          }
        P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_MEMBER_ID,
        P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_GROUP_ID,
        P4RuntimeOuterClass.TableAction.TypeCase.TYPE_NOT_SET,
        null -> {}
      }
    }
    validatePriority(entry, tableInfo)
  }

  // ---------------------------------------------------------------------------
  // Default entry validation (§9.1)
  // ---------------------------------------------------------------------------

  private fun validateDefaultEntry(
    update: P4RuntimeOuterClass.Update,
    entry: P4RuntimeOuterClass.TableEntry,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val name = tableInfo.tableName
    // §9.1: default entries only support MODIFY.
    if (update.type != P4RuntimeOuterClass.Update.Type.MODIFY) {
      throw invalidArg("default entry for table '$name' only supports MODIFY, got ${update.type}")
    }
    // §9.1: default entries must not have match fields.
    if (entry.matchCount > 0) {
      throw invalidArg("default entry for table '$name' must not have match fields")
    }
    if (entry.hasAction() && entry.action.hasAction()) {
      validateAction(entry.action.action, tableInfo)
    }
  }

  // ---------------------------------------------------------------------------
  // Action validation (§9.1.2)
  // ---------------------------------------------------------------------------

  private fun validateAction(
    action: P4RuntimeOuterClass.Action,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val validRefIds = actionRefIdsByTable[tableInfo.preamble.id] ?: emptySet()
    val validActionDescs =
      validRefIds.mapNotNull { id -> actionInfoById[id]?.let { "'${it.preamble.name}' ($id)" } }
    val actionInfo =
      actionInfoById[action.actionId]
        ?: throw invalidArg(
          "unknown action ID ${action.actionId} " +
            "in table '${tableInfo.tableName}' " +
            "(valid actions: ${formatOptions(validActionDescs)})"
        )

    if (action.actionId !in validRefIds) {
      throw invalidArg(
        "action '${actionInfo.preamble.name}' " +
          "(ID ${action.actionId}) " +
          "is not valid for table '${tableInfo.tableName}' " +
          "(valid actions: ${formatOptions(validActionDescs)})"
      )
    }

    validateActionParams(action, actionInfo)
  }

  private fun validateActionParams(
    action: P4RuntimeOuterClass.Action,
    actionInfo: P4InfoOuterClass.Action,
  ) {
    val expected = actionInfo.paramsList
    if (action.paramsCount != expected.size) {
      throw invalidArg(
        "action '${actionInfo.preamble.name}' expects ${expected.size} params, " +
          "got ${action.paramsCount}"
      )
    }
    val paramLookup = paramInfoByAction[action.actionId] ?: return
    for (param in action.paramsList) {
      val paramInfo =
        paramLookup[param.paramId]
          ?: throw invalidArg(
            "unknown param ID ${param.paramId} for action '${actionInfo.preamble.name}' " +
              "(valid params: ${formatOptions(paramLookup.entries.map {
                "'${it.value.name}' (${it.key})"
              })})"
          )
      // §8.3: value must fit in the param's bitwidth. Bitwidth 0 means unspecified
      // (e.g. @p4runtime_translation with sdn_string) — only an empty bytestring is rejected.
      requireFitsInBitwidth(param.value, paramInfo.bitwidth, "param '${paramInfo.name}'")
    }
  }

  // ---------------------------------------------------------------------------
  // Match field validation (§9.1.1)
  // ---------------------------------------------------------------------------

  private fun validateMatchFields(
    entry: P4RuntimeOuterClass.TableEntry,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val expectedFields = tableInfo.matchFieldsList
    if (expectedFields.isEmpty()) return

    val fieldLookup = fieldInfoByTable[tableInfo.preamble.id] ?: return
    val presentFieldIds = mutableSetOf<Int>()
    for (fm in entry.matchList) {
      val fieldInfo =
        fieldLookup[fm.fieldId]
          ?: throw invalidArg(
            "unknown match field ID ${fm.fieldId} in table '${tableInfo.tableName}' " +
              "(valid fields: ${formatOptions(fieldLookup.entries.map {
                "'${it.value.name}' (${it.key})"
              })})"
          )
      // §9.1: each match field may appear at most once.
      if (!presentFieldIds.add(fm.fieldId)) {
        throw invalidArg(
          "duplicate match field '${fieldInfo.name}' (ID ${fm.fieldId}) " +
            "in table '${tableInfo.tableName}'"
        )
      }
      validateMatchKind(fm, fieldInfo)
      validateMatchWidth(fm, fieldInfo)
    }

    // EXACT fields must be present; omission is not allowed.
    for (fieldInfo in expectedFields) {
      if (
        fieldInfo.matchType == P4InfoOuterClass.MatchField.MatchType.EXACT &&
          fieldInfo.id !in presentFieldIds
      ) {
        throw invalidArg(
          "missing required exact match field '${fieldInfo.name}' in table '${tableInfo.tableName}'"
        )
      }
    }
  }

  private fun validateMatchKind(
    fm: P4RuntimeOuterClass.FieldMatch,
    fieldInfo: P4InfoOuterClass.MatchField,
  ) {
    val actual =
      when (fm.fieldMatchTypeCase) {
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT ->
          P4InfoOuterClass.MatchField.MatchType.EXACT
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY ->
          P4InfoOuterClass.MatchField.MatchType.TERNARY
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM ->
          P4InfoOuterClass.MatchField.MatchType.LPM
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE ->
          P4InfoOuterClass.MatchField.MatchType.RANGE
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL ->
          P4InfoOuterClass.MatchField.MatchType.OPTIONAL
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
        P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
        null -> throw invalidArg("match field '${fieldInfo.name}' has no value set")
      }
    if (actual != fieldInfo.matchType) {
      throw invalidArg(
        "match field '${fieldInfo.name}' expects ${fieldInfo.matchType.name} but got ${actual.name}"
      )
    }
  }

  private fun validateMatchWidth(
    fm: P4RuntimeOuterClass.FieldMatch,
    fieldInfo: P4InfoOuterClass.MatchField,
  ) {
    val w = fieldInfo.bitwidth
    val f = fieldInfo.name
    when (fm.fieldMatchTypeCase) {
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT ->
        requireFitsInBitwidth(fm.exact.value, w, "match field '$f' value")
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY -> {
        requireFitsInBitwidth(fm.ternary.value, w, "match field '$f' value")
        requireFitsInBitwidth(fm.ternary.mask, w, "match field '$f' mask")
        // §8.3: bits where mask is 0 must also be 0 in value.
        checkTernaryMaskedBits(fm.ternary.value, fm.ternary.mask, f)
      }
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM -> {
        requireFitsInBitwidth(fm.lpm.value, w, "match field '$f' value")
        // §9.1.1: prefix_len must be in [0, W].
        if (fm.lpm.prefixLen < 0 || fm.lpm.prefixLen > w) {
          throw invalidArg(
            "match field '$f' has prefix_len ${fm.lpm.prefixLen} outside the valid range [0, $w]"
          )
        }
        // §8.3: bits beyond prefix_len must be zero.
        checkLpmTrailingBits(fm.lpm.value, fm.lpm.prefixLen, w, f)
      }
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE -> {
        requireFitsInBitwidth(fm.range.low, w, "match field '$f' low")
        requireFitsInBitwidth(fm.range.high, w, "match field '$f' high")
        // P4Runtime spec §9.1.1: low must be <= high.
        checkRangeOrder(fm.range.low, fm.range.high, f)
      }
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL ->
        requireFitsInBitwidth(fm.optional.value, w, "match field '$f' value")
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
      null -> {}
    }
  }

  // ---------------------------------------------------------------------------
  // Priority validation (§9.1.1)
  // ---------------------------------------------------------------------------

  private fun validatePriority(
    entry: P4RuntimeOuterClass.TableEntry,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val hasPriorityFields = tableRequiresPriority[tableInfo.preamble.id] ?: false
    val name = tableInfo.tableName
    if (hasPriorityFields && entry.priority <= 0) {
      throw invalidArg(
        "table '$name' requires priority > 0 (has ternary/range/optional match fields)"
      )
    }
    if (!hasPriorityFields && entry.priority != 0) {
      throw invalidArg("table '$name' must have priority == 0 (exact/LPM match only)")
    }
  }

  // ---------------------------------------------------------------------------
  // Action profile member validation (§9.2.1)
  // ---------------------------------------------------------------------------

  private fun validateMember(update: P4RuntimeOuterClass.Update) {
    val member = update.entity.actionProfileMember
    val profileId = member.actionProfileId
    val profileInfo =
      actionProfileInfoById[profileId]
        ?: throw notFound(
          "unknown action_profile_id $profileId " +
            "(valid profiles: ${formatOptions(
              actionProfileInfoById.values.map {
                "'${it.preamble.alias.ifEmpty { it.preamble.name }}' (${it.preamble.id})"
              }
            )})"
        )

    // DELETE only needs the key (profile_id + member_id); skip content validation.
    if (update.type == P4RuntimeOuterClass.Update.Type.DELETE) return

    val action = member.action
    val validActionIds = actionRefIdsByProfile[profileId] ?: emptySet()
    val profileName = profileInfo.preamble.alias.ifEmpty { profileInfo.preamble.name }
    val validActionDescs =
      validActionIds.mapNotNull { id -> actionInfoById[id]?.let { "'${it.preamble.name}' ($id)" } }
    val actionInfo =
      actionInfoById[action.actionId]
        ?: throw invalidArg(
          "unknown action ID ${action.actionId} in action profile '$profileName' " +
            "(valid actions: ${formatOptions(validActionDescs)})"
        )

    if (action.actionId !in validActionIds) {
      throw invalidArg(
        "action '${actionInfo.preamble.name}' (ID ${action.actionId}) " +
          "is not valid for action profile '$profileName' " +
          "(valid actions: ${formatOptions(validActionDescs)})"
      )
    }

    validateActionParams(action, actionInfo)
  }

  // ---------------------------------------------------------------------------
  // Action profile group validation (§9.2.2)
  // ---------------------------------------------------------------------------

  private fun validateGroup(update: P4RuntimeOuterClass.Update) {
    val group = update.entity.actionProfileGroup
    val profileId = group.actionProfileId
    if (profileId !in actionProfileInfoById) {
      throw notFound(
        "unknown action_profile_id $profileId " +
          "(valid profiles: ${formatOptions(
            actionProfileInfoById.values.map {
              "'${it.preamble.alias.ifEmpty { it.preamble.name }}' (${it.preamble.id})"
            }
          )})"
      )
    }
    // Group members reference member_ids, which are validated at write time by the simulator.
    // Schema-level validation only checks the profile ID exists.
  }

  companion object {
    private val PRIORITY_MATCH_TYPES =
      setOf(
        P4InfoOuterClass.MatchField.MatchType.TERNARY,
        P4InfoOuterClass.MatchField.MatchType.RANGE,
        P4InfoOuterClass.MatchField.MatchType.OPTIONAL,
      )

    /**
     * §8.3: ternary value bits must be zero where the mask is zero.
     *
     * Length-agnostic: value and mask may be any length (each separately validated by
     * [requireFitsInBitwidth] beforehand), so the check is done on the integer values directly.
     */
    private fun checkTernaryMaskedBits(value: ByteString, mask: ByteString, fieldName: String) {
      val v = value.toUnsignedBigInteger()
      val m = mask.toUnsignedBigInteger()
      if (v.and(m.not()).signum() != 0) {
        throw invalidArg(
          "match field '$fieldName' has masked-off bits set in value (value & ~mask != 0)"
        )
      }
    }

    /** §9.1.1: range match low must be <= high (unsigned comparison). */
    private fun checkRangeOrder(low: ByteString, high: ByteString, fieldName: String) {
      if (low.toUnsignedBigInteger() > high.toUnsignedBigInteger()) {
        throw invalidArg("match field '$fieldName' has range low > high")
      }
    }

    /**
     * §8.3: LPM value bits beyond prefix_len must be zero.
     *
     * The value is interpreted as a [bitwidth]-bit unsigned integer; the trailing (bitwidth -
     * prefixLen) bits must be zero, regardless of how many bytes were used to send it. (E.g. for
     * `bit<32>` LPM with prefix_len=8, the value `\x0A` represents integer 10 with implicit leading
     * zeros — and bit positions [0,24) of the bit<32> integer are non-zero, so it is rejected.)
     */
    private fun checkLpmTrailingBits(
      value: ByteString,
      prefixLen: Int,
      bitwidth: Int,
      fieldName: String,
    ) {
      val trailingBits = bitwidth - prefixLen
      if (trailingBits == 0) return // entire value is significant
      val mask = BigInteger.ONE.shiftLeft(trailingBits).subtract(BigInteger.ONE)
      if (value.toUnsignedBigInteger().and(mask).signum() != 0) {
        throw invalidArg(
          "match field '$fieldName' has non-zero bits beyond prefix length $prefixLen"
        )
      }
    }

    private val P4InfoOuterClass.Table.tableName: String
      get() = preamble.alias.ifEmpty { preamble.name }

    private const val MAX_LISTED_OPTIONS = 10

    /** Formats a list of option names for inclusion in an error message, truncating if needed. */
    private fun formatOptions(options: List<String>): String =
      when {
        options.isEmpty() -> "none"
        options.size <= MAX_LISTED_OPTIONS -> options.joinToString(", ")
        else ->
          options.take(MAX_LISTED_OPTIONS).joinToString(", ") +
            ", ... and ${options.size - MAX_LISTED_OPTIONS} more"
      }

    private fun invalidArg(msg: String): StatusException =
      Status.INVALID_ARGUMENT.withDescription(msg).asException()

    private fun notFound(msg: String): StatusException =
      Status.NOT_FOUND.withDescription(msg).asException()

    private fun unimplemented(msg: String): StatusException =
      Status.UNIMPLEMENTED.withDescription(msg).asException()
  }
}
