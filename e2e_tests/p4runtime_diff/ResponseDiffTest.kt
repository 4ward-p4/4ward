package fourward.e2e.p4runtimediff

import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.MulticastGroupEntry
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.Replica
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Unit tests for the canonicalize-and-diff helpers in [ResponseDiff]. Always runnable, no
 * subprocess dependencies — the differential scenarios that consume these helpers are tagged
 * `heavy` and gated on the simple_switch_grpc binary.
 */
class ResponseDiffTest {

  @Test
  fun `sortMatchesByFieldId sorts match list by field_id`() {
    val unsorted = entry(matches = listOf(exact(2, 0xCAFE), exact(1, 0xBEEF)))
    val canonical = sortMatchesByFieldId(unsorted)
    assertEquals(listOf(1, 2), canonical.matchList.map { it.fieldId })
  }

  @Test
  fun `sortMatchesByFieldId returns same instance when already canonical`() {
    val canonical = entry(matches = listOf(exact(1, 0xBEEF), exact(2, 0xCAFE)))
    assertSame(canonical, sortMatchesByFieldId(canonical))
  }

  @Test
  fun `canonicalizeEntity is identity for non-table entries`() {
    val entity =
      Entity.newBuilder()
        .setCounterEntry(p4.v1.P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(7))
        .build()
    assertSame(entity, canonicalizeEntity(entity))
  }

  @Test
  fun `canonicalizeReadResponse sorts every nested match list`() {
    val resp =
      ReadResponse.newBuilder()
        .addEntities(asEntity(entry(matches = listOf(exact(2, 0x01), exact(1, 0x02)))))
        .addEntities(asEntity(entry(matches = listOf(exact(3, 0x03), exact(1, 0x04)))))
        .build()
    val canonical = canonicalizeReadResponse(resp)
    assertEquals(listOf(1, 2), canonical.getEntities(0).tableEntry.matchList.map { it.fieldId })
    assertEquals(listOf(1, 3), canonical.getEntities(1).tableEntry.matchList.map { it.fieldId })
  }

  @Test
  fun `canonicalizeReadResponse sorts multicast replicas`() {
    val resp =
      ReadResponse.newBuilder()
        .addEntities(multicastGroupEntity(groupId = 1, replicas = listOf(2 to 102, 1 to 101)))
        .build()
    val canonical = canonicalizeReadResponse(resp)
    val replicas =
      canonical.getEntities(0).packetReplicationEngineEntry.multicastGroupEntry.replicasList.map {
        it.port.byteAt(0).toInt() to it.instance
      }
    assertEquals(listOf(1 to 101, 2 to 102), replicas)
  }

  @Test
  fun `assertProtosEqual passes on equal messages`() {
    val a = entry(matches = listOf(exact(1, 0xBEEF)))
    val b = entry(matches = listOf(exact(1, 0xBEEF)))
    assertProtosEqual(a, b)
  }

  @Test
  fun `assertProtosEqual throws with text-format diff on mismatch`() {
    val a = entry(matches = listOf(exact(1, 0xBEEF)))
    val b = entry(matches = listOf(exact(1, 0xCAFE)))
    val e = assertThrows(AssertionError::class.java) { assertProtosEqual(a, b, "4ward", "bmv2") }
    val msg = e.message ?: ""
    assert(msg.contains("--- 4ward ---")) { "left text-format header missing in: $msg" }
    assert(msg.contains("--- bmv2 ---")) { "right text-format header missing in: $msg" }
  }

  @Test
  fun `out-of-order match lists become equal after canonicalization`() {
    val a = entry(matches = listOf(exact(1, 0x01), exact(2, 0x02)))
    val b = entry(matches = listOf(exact(2, 0x02), exact(1, 0x01)))
    assertNotEquals("different proto order should not be equal raw", a, b)
    assertEquals(sortMatchesByFieldId(a), sortMatchesByFieldId(b))
  }

  // ---------------------------------------------------------------------------

  private fun entry(matches: List<FieldMatch>): TableEntry =
    TableEntry.newBuilder().setTableId(1).addAllMatch(matches).build()

  private fun exact(fieldId: Int, value: Int): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setExact(
        FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(value.toByte())))
      )
      .build()

  private fun asEntity(entry: TableEntry): Entity = Entity.newBuilder().setTableEntry(entry).build()

  private fun multicastGroupEntity(groupId: Int, replicas: List<Pair<Int, Int>>): Entity =
    Entity.newBuilder()
      .setPacketReplicationEngineEntry(
        PacketReplicationEngineEntry.newBuilder()
          .setMulticastGroupEntry(
            MulticastGroupEntry.newBuilder()
              .setMulticastGroupId(groupId)
              .addAllReplicas(
                replicas.map { (port, instance) ->
                  Replica.newBuilder()
                    .setPort(ByteString.copyFrom(byteArrayOf(port.toByte())))
                    .setInstance(instance)
                    .build()
                }
              )
          )
      )
      .build()
}
