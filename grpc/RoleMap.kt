package fourward.grpc

import p4.config.v1.P4InfoOuterClass

/**
 * Maps P4 entity IDs to their `@p4runtime_role` (P4Runtime spec §15).
 *
 * Built at pipeline load time from p4info annotations. Tables declare their role via
 * `@p4runtime_role("role_name")`; direct counters/meters inherit the role of their parent table;
 * action profiles inherit from their associated tables.
 *
 * Entities without a `@p4runtime_role` annotation belong to the **default role** (empty string).
 * Controllers that arbitrate with no role (empty `Role.name`) have full access to all entities.
 */
class RoleMap private constructor(private val entityRoles: Map<Int, String>) {

  /** Returns the role for the given entity ID, or null if it belongs to the default role. */
  fun role(entityId: Int): String? = entityRoles[entityId]

  /** True if any entity in the pipeline has a `@p4runtime_role` annotation. */
  val hasRoles: Boolean
    get() = entityRoles.isNotEmpty()

  companion object {
    // Matches @p4runtime_role("role_name") in unstructured p4info annotations.
    // The quotes are escaped in protobuf text format: @p4runtime_role(\"role_name\")
    private val ROLE_PATTERN = Regex("""@p4runtime_role\("(.+?)"\)""")

    /** Parses `@p4runtime_role` annotations from p4info and builds entity-to-role mappings. */
    fun create(p4info: P4InfoOuterClass.P4Info): RoleMap {
      val roles = mutableMapOf<Int, String>()

      // Tables: direct annotation.
      for (table in p4info.tablesList) {
        val role = parseRole(table.preamble.annotationsList) ?: continue
        roles[table.preamble.id] = role
      }

      // Direct counters: inherit from parent table.
      for (dc in p4info.directCountersList) {
        val tableRole = roles[dc.directTableId]
        if (tableRole != null) roles[dc.preamble.id] = tableRole
      }

      // Direct meters: inherit from parent table.
      for (dm in p4info.directMetersList) {
        val tableRole = roles[dm.directTableId]
        if (tableRole != null) roles[dm.preamble.id] = tableRole
      }

      // Action profiles: inherit from associated tables.
      for (ap in p4info.actionProfilesList) {
        val tableRole = ap.tableIdsList.firstNotNullOfOrNull { roles[it] }
        if (tableRole != null) roles[ap.preamble.id] = tableRole
      }

      return RoleMap(roles)
    }

    private fun parseRole(annotations: List<String>): String? {
      for (annotation in annotations) {
        val match = ROLE_PATTERN.find(annotation)
        if (match != null) return match.groupValues[1]
      }
      return null
    }
  }
}
