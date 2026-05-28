package fourward.e2e.p4runtimediff

import com.google.protobuf.ByteString
import com.google.protobuf.Message
import com.google.protobuf.TextFormat
import fourward.grpc.canonicalizeTableEntry as canonicalizeTableEntryBytestrings
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.MulticastGroupEntry
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.Replica
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

/** Returns a copy of [entry] with replicas sorted by `(port, instance)`. */
fun sortReplicasByPortAndInstance(entry: MulticastGroupEntry): MulticastGroupEntry {
  val sortedReplicas =
    entry.replicasList.sortedWith { left, right -> compareReplicasByPortAndInstance(left, right) }
  if (sortedReplicas == entry.replicasList) return entry
  return entry.toBuilder().clearReplicas().addAllReplicas(sortedReplicas).build()
}

private fun compareReplicasByPortAndInstance(left: Replica, right: Replica): Int {
  val portOrder = compareByteStrings(left.port, right.port)
  if (portOrder != 0) return portOrder
  return left.instance.compareTo(right.instance)
}

private fun compareByteStrings(left: ByteString, right: ByteString): Int {
  for (index in 0 until minOf(left.size(), right.size())) {
    val byteOrder =
      (left.byteAt(index).toInt() and 0xff).compareTo(right.byteAt(index).toInt() and 0xff)
    if (byteOrder != 0) return byteOrder
  }
  return left.size().compareTo(right.size())
}

fun canonicalizePacketReplicationEngineEntry(
  entry: PacketReplicationEngineEntry
): PacketReplicationEngineEntry =
  when (entry.typeCase) {
    PacketReplicationEngineEntry.TypeCase.MULTICAST_GROUP_ENTRY -> {
      val sortedGroup = sortReplicasByPortAndInstance(entry.multicastGroupEntry)
      if (sortedGroup === entry.multicastGroupEntry) entry
      else entry.toBuilder().setMulticastGroupEntry(sortedGroup).build()
    }
    PacketReplicationEngineEntry.TypeCase.CLONE_SESSION_ENTRY,
    PacketReplicationEngineEntry.TypeCase.TYPE_NOT_SET -> entry
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
    Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY -> {
      val canonical = canonicalizePacketReplicationEngineEntry(entity.packetReplicationEngineEntry)
      if (canonical === entity.packetReplicationEngineEntry) entity
      else entity.toBuilder().setPacketReplicationEngineEntry(canonical).build()
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
