package fourward.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Validates `@refers_to` referential integrity constraints at write time.
 *
 * P4 programs use `@refers_to(table, field)` annotations on match fields and action parameters to
 * declare foreign-key relationships between tables (e.g., `nexthop_id` in `ipv4_table` must exist
 * in `nexthop_table`). This validator parses those annotations from p4info and checks that
 * referenced entries exist before accepting an INSERT or MODIFY.
 *
 * Special case: `builtin::multicast_group_table` references are checked against the PRE's multicast
 * group entries rather than regular table entries.
 *
 * All annotation parsing is done at construction time; [validate] only does lookups.
 */
class ReferenceValidator
private constructor(
  private val matchFieldRefs: Map<Int, Map<Int, List<Reference>>>,
  private val actionParamRefs: Map<Int, Map<Int, List<Reference>>>,
) {

  /**
   * A parsed `@refers_to` annotation: the value must exist as [fieldId] in table [tableId].
   *
   * For `builtin::multicast_group_table`, [tableName] is the sentinel [BUILTIN_MULTICAST] and
   * [tableId]/[fieldId] are unused — the value is interpreted as a multicast group ID.
   */
  data class Reference(
    val tableName: String,
    val fieldName: String,
    val tableId: Int,
    val fieldId: Int,
  )

  /**
   * Checks referential integrity for an update.
   *
   * Validates table entries (match fields + action params) and action profile members (action
   * params). Only validates INSERT and MODIFY — DELETE never fails referential checks.
   *
   * @param entryExists returns true if a table (identified by p4info table ID) has an entry with
   *   match [fieldId] equal to [value].
   * @param multicastGroupExists returns true if a multicast group with the given ID exists.
   */
  fun validate(
    update: P4RuntimeOuterClass.Update,
    entryExists: (tableId: Int, fieldId: Int, value: ByteString) -> Boolean,
    multicastGroupExists: (groupId: Int) -> Boolean,
  ) {
    if (update.type == P4RuntimeOuterClass.Update.Type.DELETE) return
    val entity = update.entity

    when (entity.entityCase) {
      P4RuntimeOuterClass.Entity.EntityCase.TABLE_ENTRY -> {
        val entry = entity.tableEntry

        // Check match fields.
        val fieldRefs = matchFieldRefs[entry.tableId]
        if (fieldRefs != null) {
          for (fm in entry.matchList) {
            val refs = fieldRefs[fm.fieldId] ?: continue
            val value = extractExactValue(fm) ?: continue
            for (ref in refs) {
              checkReference(ref, value, entryExists, multicastGroupExists)
            }
          }
        }

        // Check action params — direct action or one-shot action profile action set.
        if (entry.hasAction()) {
          val tableAction = entry.action
          when (tableAction.typeCase) {
            P4RuntimeOuterClass.TableAction.TypeCase.ACTION ->
              validateActionParams(tableAction.action, entryExists, multicastGroupExists)
            P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_ACTION_SET ->
              for (profileAction in tableAction.actionProfileActionSet.actionProfileActionsList) {
                validateActionParams(profileAction.action, entryExists, multicastGroupExists)
              }
            P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_MEMBER_ID,
            P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_GROUP_ID,
            P4RuntimeOuterClass.TableAction.TypeCase.TYPE_NOT_SET,
            null -> {}
          }
        }
      }

      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_MEMBER ->
        validateActionParams(entity.actionProfileMember.action, entryExists, multicastGroupExists)

      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_GROUP,
      P4RuntimeOuterClass.Entity.EntityCase.COUNTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_COUNTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.METER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_METER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.REGISTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.VALUE_SET_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIGEST_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.EXTERN_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.ENTITY_NOT_SET,
      null -> {}
    }
  }

  /** Validates `@refers_to` annotations on action parameters. */
  private fun validateActionParams(
    action: P4RuntimeOuterClass.Action,
    entryExists: (Int, Int, ByteString) -> Boolean,
    multicastGroupExists: (Int) -> Boolean,
  ) {
    val paramRefs = actionParamRefs[action.actionId] ?: return
    for (param in action.paramsList) {
      val refs = paramRefs[param.paramId] ?: continue
      for (ref in refs) {
        checkReference(ref, param.value, entryExists, multicastGroupExists)
      }
    }
  }

  private fun checkReference(
    ref: Reference,
    value: ByteString,
    entryExists: (Int, Int, ByteString) -> Boolean,
    multicastGroupExists: (Int) -> Boolean,
  ) {
    if (ref.tableName == BUILTIN_MULTICAST) {
      val groupId = value.toUnsignedInt()
      if (!multicastGroupExists(groupId)) {
        throw Status.INVALID_ARGUMENT.withDescription(
            "@refers_to violation: no multicast group with ID $groupId exists"
          )
          .asException()
      }
    } else {
      if (!entryExists(ref.tableId, ref.fieldId, value)) {
        throw Status.INVALID_ARGUMENT.withDescription(
            "@refers_to violation: no entry in '${ref.tableName}' with " +
              "'${ref.fieldName}' = 0x${value.toByteArray().joinToString("") { "%02x".format(it) }}"
          )
          .asException()
      }
    }
  }

  /** Extracts the exact-match value from a FieldMatch, or null for non-exact fields. */
  private fun extractExactValue(fm: P4RuntimeOuterClass.FieldMatch): ByteString? =
    when (fm.fieldMatchTypeCase) {
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT -> fm.exact.value
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL -> fm.optional.value
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY,
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM,
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE,
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
      P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
      null -> null
    }

  companion object {
    private const val BUILTIN_MULTICAST = "builtin::multicast_group_table"
    private val REFERS_TO_PATTERN = Regex("""@refers_to\(\s*(.+?)\s*,\s*(.+?)\s*\)""")

    /**
     * Creates a validator from p4info, or null if no `@refers_to` annotations exist.
     *
     * Resolves table and field names from annotations to their p4info IDs at construction time.
     */
    fun create(p4info: P4InfoOuterClass.P4Info): ReferenceValidator? {
      val tablesByName =
        p4info.tablesList.associateBy { it.preamble.alias.ifEmpty { it.preamble.name } }

      fun resolveFieldId(tableName: String, fieldName: String): Int? =
        tablesByName[tableName]?.matchFieldsList?.find { it.name == fieldName }?.id

      val matchFieldRefs = mutableMapOf<Int, MutableMap<Int, MutableList<Reference>>>()
      val actionParamRefs = mutableMapOf<Int, MutableMap<Int, MutableList<Reference>>>()
      var hasAny = false

      // Scan match fields for @refers_to annotations.
      for (table in p4info.tablesList) {
        for (field in table.matchFieldsList) {
          val refs =
            parseRefersTo(field.annotationsList, tablesByName) { tbl, fld ->
              resolveFieldId(tbl, fld)
            }
          if (refs.isNotEmpty()) {
            matchFieldRefs.getOrPut(table.preamble.id) { mutableMapOf() }[field.id] =
              refs.toMutableList()
            hasAny = true
          }
        }
      }

      // Scan action params for @refers_to annotations.
      for (action in p4info.actionsList) {
        for (param in action.paramsList) {
          val refs =
            parseRefersTo(param.annotationsList, tablesByName) { tbl, fld ->
              resolveFieldId(tbl, fld)
            }
          if (refs.isNotEmpty()) {
            actionParamRefs.getOrPut(action.preamble.id) { mutableMapOf() }[param.id] =
              refs.toMutableList()
            hasAny = true
          }
        }
      }

      return if (hasAny) ReferenceValidator(matchFieldRefs, actionParamRefs) else null
    }

    private fun parseRefersTo(
      annotations: List<String>,
      tablesByName: Map<String, P4InfoOuterClass.Table>,
      resolveFieldId: (tableName: String, fieldName: String) -> Int?,
    ): List<Reference> =
      annotations.mapNotNull { annotation ->
        val match = REFERS_TO_PATTERN.find(annotation) ?: return@mapNotNull null
        // p4c wraps annotation arguments in double quotes (e.g.
        // @refers_to("target_table", "id")); strip them.
        val tableName = match.groupValues[1].removeSurrounding("\"")
        val fieldName = match.groupValues[2].removeSurrounding("\"")

        // builtin::multicast_group_table is handled specially — no table ID to resolve.
        // p4c may insert spaces around '::' in annotations.
        val normalizedTableName = tableName.replace(" ", "")
        if (normalizedTableName.startsWith("builtin::")) {
          return@mapNotNull Reference(normalizedTableName, fieldName, tableId = 0, fieldId = 0)
        }

        // Skip unresolvable references (table/field not in p4info). This can happen
        // for annotations referencing tables outside the current program scope.
        val targetTable = tablesByName[tableName] ?: return@mapNotNull null
        val fieldId = resolveFieldId(tableName, fieldName) ?: return@mapNotNull null
        Reference(tableName, fieldName, tableId = targetTable.preamble.id, fieldId)
      }
  }
}
