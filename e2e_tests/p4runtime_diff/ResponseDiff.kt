package fourward.e2e.p4runtimediff

import com.google.protobuf.Message
import com.google.protobuf.TextFormat
import fourward.grpc.canonicalizeTableEntry as canonicalizeTableEntryBytestrings
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Canonicalize-then-compare logic for P4Runtime responses, per `designs/p4runtime_diff.md`
 * §"Canonicalizations before diff".
 *
 * The design enumerates four allowed divergences that must be canonicalized away before equality:
 * 1. Field ordering in repeated fields — sort by `field_id`.
 * 2. Error message text — compared via gRPC status code only (handled by callers).
 * 3. Server-assigned IDs — recorded and substituted by callers; not canonicalized here.
 * 4. Counter/meter timing values — initial scenarios avoid these; not canonicalized here.
 *
 * Anything not anticipated by these canonicalizations is reported as a divergence — the design
 * makes that an explicit failure, not a silent ignore.
 */

/** Returns a copy of [entry] with match fields sorted by `field_id`. */
fun sortMatchesByFieldId(entry: TableEntry): TableEntry {
  val sortedMatches: List<FieldMatch> = entry.matchList.sortedBy { it.fieldId }
  if (sortedMatches == entry.matchList) return entry
  return entry.toBuilder().clearMatch().addAllMatch(sortedMatches).build()
}

/**
 * Applies both spec §8.3 bytestring canonicalization (via `fourward.grpc`'s helper) and match-list
 * ordering. Either or both may differ between server implementations; both are documented
 * divergences from §8.3 read-write symmetry, and we apply them client-side so unrelated fields
 * don't get masked by encoding differences.
 */
fun canonicalizeEntity(entity: Entity): Entity =
  when (entity.entityCase) {
    Entity.EntityCase.TABLE_ENTRY -> {
      val withSortedMatches = sortMatchesByFieldId(entity.tableEntry)
      val withCanonicalBytes = canonicalizeTableEntryBytestrings(withSortedMatches)
      if (withCanonicalBytes === entity.tableEntry) entity
      else entity.toBuilder().setTableEntry(withCanonicalBytes).build()
    }
    else -> entity
  }

/** Returns a copy of [response] with each entity canonicalized. */
fun canonicalizeReadResponse(response: ReadResponse): ReadResponse {
  val canonical = response.entitiesList.map(::canonicalizeEntity)
  if (canonical == response.entitiesList) return response
  return response.toBuilder().clearEntities().addAllEntities(canonical).build()
}

/**
 * Asserts two protobuf messages are equal after canonicalization. Throws [AssertionError] with a
 * structural diff suitable for surfacing the actual divergence in test failures. [leftLabel] and
 * [rightLabel] identify the two implementations being compared (e.g. `"4ward"` / `"bmv2"`).
 */
fun assertProtosEqual(
  left: Message,
  right: Message,
  leftLabel: String = "left",
  rightLabel: String = "right",
) {
  if (left == right) return
  throw AssertionError(
    "responses diverged:\n" +
      "--- $leftLabel ---\n${TextFormat.printer().printToString(left)}" +
      "--- $rightLabel ---\n${TextFormat.printer().printToString(right)}"
  )
}
