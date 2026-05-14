package fourward.simulator

import com.google.protobuf.ByteString
import fourward.Fork
import fourward.ForkBranch
import fourward.ForkReason
import fourward.PacketOutcome
import fourward.TableLookupEvent
import fourward.TraceEvent
import fourward.TraceTree
import fourward.CloneSessionLookupEvent
import fourward.Drop
import fourward.DropReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.v1.P4RuntimeOuterClass

class ReproducerExtractorTest {

  private fun tableEntry(
    tableId: Int,
    matchValue: ByteArray,
    actionId: Int,
  ): P4RuntimeOuterClass.TableEntry =
    P4RuntimeOuterClass.TableEntry.newBuilder()
      .setTableId(tableId)
      .addMatch(
        P4RuntimeOuterClass.FieldMatch.newBuilder()
          .setFieldId(1)
          .setExact(
            P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
              .setValue(ByteString.copyFrom(matchValue))
          )
      )
      .setAction(
        P4RuntimeOuterClass.TableAction.newBuilder()
          .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId))
      )
      .build()

  private fun tableLookupHit(entry: P4RuntimeOuterClass.TableEntry): TraceEvent =
    TraceEvent.newBuilder()
      .setTableLookup(
        TableLookupEvent.newBuilder()
          .setTableName("MyTable")
          .setHit(true)
          .setMatchedEntry(entry)
          .setActionName("forward")
      )
      .build()

  private fun tableLookupMiss(tableName: String = "MyTable"): TraceEvent =
    TraceEvent.newBuilder()
      .setTableLookup(
        TableLookupEvent.newBuilder()
          .setTableName(tableName)
          .setHit(false)
          .setActionName("NoAction")
      )
      .build()

  private fun cloneSessionLookup(sessionId: Int, found: Boolean): TraceEvent =
    TraceEvent.newBuilder()
      .setCloneSessionLookup(
        CloneSessionLookupEvent.newBuilder()
          .setSessionId(sessionId)
          .setSessionFound(found)
      )
      .build()

  private fun dropOutcome(): PacketOutcome =
    PacketOutcome.newBuilder()
      .setDrop(Drop.newBuilder().setReason(DropReason.MARK_TO_DROP))
      .build()

  private fun emptySnapshot(): TableStore.ForwardingSnapshot = TableStore.ForwardingSnapshot()

  @Test
  fun `empty trace produces no entities`() {
    val trace = TraceTree.newBuilder()
      .setPacketOutcome(dropOutcome())
      .build()
    val entities = extractReproducerEntities(trace, emptySnapshot(), emptyList())
    assertTrue(entities.isEmpty())
  }

  @Test
  fun `table hit extracts matched entry`() {
    val entry = tableEntry(tableId = 1, matchValue = byteArrayOf(0x08, 0x00), actionId = 10)
    val trace = TraceTree.newBuilder()
      .addEvents(tableLookupHit(entry))
      .setPacketOutcome(dropOutcome())
      .build()

    val entities = extractReproducerEntities(trace, emptySnapshot(), emptyList())

    assertEquals(1, entities.size)
    assertTrue(entities[0].hasTableEntry())
    assertEquals(entry, entities[0].tableEntry)
  }

  @Test
  fun `table miss produces no entities`() {
    val trace = TraceTree.newBuilder()
      .addEvents(tableLookupMiss())
      .setPacketOutcome(dropOutcome())
      .build()

    val entities = extractReproducerEntities(trace, emptySnapshot(), emptyList())
    assertTrue(entities.isEmpty())
  }

  @Test
  fun `static entries are excluded`() {
    val entry = tableEntry(tableId = 1, matchValue = byteArrayOf(0x08, 0x00), actionId = 10)
    val staticUpdate = P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
      .build()

    val trace = TraceTree.newBuilder()
      .addEvents(tableLookupHit(entry))
      .setPacketOutcome(dropOutcome())
      .build()

    val entities = extractReproducerEntities(trace, emptySnapshot(), listOf(staticUpdate))
    assertTrue("static entry should be excluded", entities.isEmpty())
  }

  @Test
  fun `duplicate entries are deduplicated`() {
    val entry = tableEntry(tableId = 1, matchValue = byteArrayOf(0x08, 0x00), actionId = 10)
    val trace = TraceTree.newBuilder()
      .addEvents(tableLookupHit(entry))
      .addEvents(tableLookupHit(entry))
      .setPacketOutcome(dropOutcome())
      .build()

    val entities = extractReproducerEntities(trace, emptySnapshot(), emptyList())
    assertEquals("duplicate should be deduplicated", 1, entities.size)
  }

  @Test
  fun `clone session lookup extracts clone session entry`() {
    val snapshot = emptySnapshot()
    val cloneEntry = P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
      .setSessionId(42)
      .setClassOfService(0)
      .setPacketLengthBytes(0)
      .addReplicas(
        P4RuntimeOuterClass.Replica.newBuilder()
          .setPort(ByteString.copyFrom(byteArrayOf(1)))
          .setInstance(0)
      )
      .build()
    snapshot.cloneSessions[42] = cloneEntry

    val trace = TraceTree.newBuilder()
      .addEvents(cloneSessionLookup(sessionId = 42, found = true))
      .setPacketOutcome(dropOutcome())
      .build()

    val entities = extractReproducerEntities(trace, snapshot, emptyList())

    assertEquals(1, entities.size)
    assertTrue(entities[0].hasPacketReplicationEngineEntry())
    assertEquals(cloneEntry, entities[0].packetReplicationEngineEntry.cloneSessionEntry)
  }

  @Test
  fun `clone session not found produces no entities`() {
    val trace = TraceTree.newBuilder()
      .addEvents(cloneSessionLookup(sessionId = 99, found = false))
      .setPacketOutcome(dropOutcome())
      .build()

    val entities = extractReproducerEntities(trace, emptySnapshot(), emptyList())
    assertTrue(entities.isEmpty())
  }

  @Test
  fun `fork branches are traversed recursively`() {
    val entry1 = tableEntry(tableId = 1, matchValue = byteArrayOf(0x01), actionId = 10)
    val entry2 = tableEntry(tableId = 2, matchValue = byteArrayOf(0x02), actionId = 20)

    val trace = TraceTree.newBuilder()
      .setForkOutcome(
        Fork.newBuilder()
          .setReason(ForkReason.CLONE)
          .addBranches(
            ForkBranch.newBuilder()
              .setLabel("original")
              .setSubtree(
                TraceTree.newBuilder()
                  .addEvents(tableLookupHit(entry1))
                  .setPacketOutcome(dropOutcome())
              )
          )
          .addBranches(
            ForkBranch.newBuilder()
              .setLabel("clone")
              .setSubtree(
                TraceTree.newBuilder()
                  .addEvents(tableLookupHit(entry2))
                  .setPacketOutcome(dropOutcome())
              )
          )
      )
      .build()

    val entities = extractReproducerEntities(trace, emptySnapshot(), emptyList())

    assertEquals(2, entities.size)
    assertEquals(entry1, entities[0].tableEntry)
    assertEquals(entry2, entities[1].tableEntry)
  }

  @Test
  fun `action profile member is extracted from matched entry`() {
    val member = P4RuntimeOuterClass.ActionProfileMember.newBuilder()
      .setActionProfileId(100)
      .setMemberId(5)
      .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(10))
      .build()

    val snapshot = emptySnapshot()
    snapshot.profileMembers[100] = mutableMapOf(5 to member)

    val entry = P4RuntimeOuterClass.TableEntry.newBuilder()
      .setTableId(1)
      .setAction(
        P4RuntimeOuterClass.TableAction.newBuilder().setActionProfileMemberId(5)
      )
      .build()

    val trace = TraceTree.newBuilder()
      .addEvents(tableLookupHit(entry))
      .setPacketOutcome(dropOutcome())
      .build()

    val entities = extractReproducerEntities(trace, snapshot, emptyList())

    assertEquals(2, entities.size)
    assertTrue(entities[0].hasTableEntry())
    assertTrue(entities[1].hasActionProfileMember())
    assertEquals(member, entities[1].actionProfileMember)
  }

  @Test
  fun `action profile group and its members are extracted`() {
    val member1 = P4RuntimeOuterClass.ActionProfileMember.newBuilder()
      .setActionProfileId(100)
      .setMemberId(1)
      .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(10))
      .build()
    val member2 = P4RuntimeOuterClass.ActionProfileMember.newBuilder()
      .setActionProfileId(100)
      .setMemberId(2)
      .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(20))
      .build()
    val group = P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
      .setActionProfileId(100)
      .setGroupId(7)
      .addMembers(
        P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder().setMemberId(1).setWeight(1)
      )
      .addMembers(
        P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder().setMemberId(2).setWeight(1)
      )
      .build()

    val snapshot = emptySnapshot()
    snapshot.profileMembers[100] = mutableMapOf(1 to member1, 2 to member2)
    snapshot.profileGroups[100] = mutableMapOf(7 to group)

    val entry = P4RuntimeOuterClass.TableEntry.newBuilder()
      .setTableId(1)
      .setAction(
        P4RuntimeOuterClass.TableAction.newBuilder().setActionProfileGroupId(7)
      )
      .build()

    val trace = TraceTree.newBuilder()
      .addEvents(tableLookupHit(entry))
      .setPacketOutcome(dropOutcome())
      .build()

    val entities = extractReproducerEntities(trace, snapshot, emptyList())

    assertEquals(4, entities.size)
    assertTrue("table entry", entities[0].hasTableEntry())
    assertTrue("group", entities[1].hasActionProfileGroup())
    assertTrue("member 1", entities[2].hasActionProfileMember())
    assertTrue("member 2", entities[3].hasActionProfileMember())
  }
}
