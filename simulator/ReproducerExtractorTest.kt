package fourward.simulator

import com.google.protobuf.ByteString
import fourward.CloneSessionLookupEvent
import fourward.Drop
import fourward.Replication
import fourward.TableLookupEvent
import fourward.TraceEvent
import fourward.TraceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.p4.config.v1.P4Info
import org.p4.config.v1.Preamble
import org.p4.config.v1.Register
import org.p4.v1.Action
import org.p4.v1.ActionProfileGroup
import org.p4.v1.ActionProfileMember
import org.p4.v1.CloneSessionEntry
import org.p4.v1.Entity
import org.p4.v1.FieldMatch
import org.p4.v1.MulticastGroupEntry
import org.p4.v1.Replica
import org.p4.v1.TableAction
import org.p4.v1.TableEntry
import org.p4.v1.Update

class ReproducerExtractorTest {

  private fun tableEntry(tableId: Int, matchValue: ByteArray, actionId: Int): TableEntry =
    TableEntry.newBuilder()
      .setTableId(tableId)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(1)
          .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(matchValue)))
      )
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun tableLookupHit(entry: TableEntry): TraceEvent =
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
        CloneSessionLookupEvent.newBuilder().setSessionId(sessionId).setSessionFound(found)
      )
      .build()

  private fun dropOutcome(): Drop = Drop.getDefaultInstance()

  private fun emptyTableStore(): TableStore = TableStore()

  private fun tableStoreWithRegister(): TableStore {
    val store = TableStore()
    val register =
      Register.newBuilder()
        .setPreamble(Preamble.newBuilder().setId(500).setName("myRegister"))
        .setTypeSpec(
          org.p4.config.v1.P4DataTypeSpec.newBuilder()
            .setBitstring(
              org.p4.config.v1.P4BitstringLikeTypeSpec.newBuilder()
                .setBit(org.p4.config.v1.P4BitTypeSpec.newBuilder().setBitwidth(32))
            )
        )
        .setSize(4)
        .build()
    store.loadMappings(P4Info.newBuilder().addRegisters(register).build())
    return store
  }

  /** Creates a TableStore whose published snapshot has the given state pre-populated. */
  private fun tableStoreWith(setup: TableStore.ForwardingSnapshot.() -> Unit): TableStore {
    val store = TableStore()
    store.snapshot.setup()
    return store
  }

  private fun extract(
    trace: TraceTree,
    store: TableStore,
    staticEntries: List<Update> = emptyList(),
  ): List<Entity> = extractReproducerEntities(trace, store.snapshot, store, staticEntries)

  @Test
  fun `empty trace produces no entities`() {
    val trace = TraceTree.newBuilder().setDrop(dropOutcome()).build()
    val entities = extract(trace, emptyTableStore())
    assertTrue(entities.isEmpty())
  }

  @Test
  fun `table hit extracts matched entry`() {
    val entry = tableEntry(tableId = 1, matchValue = byteArrayOf(0x08, 0x00), actionId = 10)
    val trace =
      TraceTree.newBuilder().addEvents(tableLookupHit(entry)).setDrop(dropOutcome()).build()

    val entities = extract(trace, emptyTableStore())

    assertEquals(1, entities.size)
    assertTrue(entities[0].hasTableEntry())
    assertEquals(entry, entities[0].tableEntry)
  }

  @Test
  fun `table miss produces no entities`() {
    val trace = TraceTree.newBuilder().addEvents(tableLookupMiss()).setDrop(dropOutcome()).build()

    val entities = extract(trace, emptyTableStore())
    assertTrue(entities.isEmpty())
  }

  @Test
  fun `static entries are excluded`() {
    val entry = tableEntry(tableId = 1, matchValue = byteArrayOf(0x08, 0x00), actionId = 10)
    val staticUpdate =
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()

    val trace =
      TraceTree.newBuilder().addEvents(tableLookupHit(entry)).setDrop(dropOutcome()).build()

    val entities = extract(trace, emptyTableStore(), listOf(staticUpdate))
    assertTrue("static entry should be excluded", entities.isEmpty())
  }

  @Test
  fun `duplicate entries are deduplicated`() {
    val entry = tableEntry(tableId = 1, matchValue = byteArrayOf(0x08, 0x00), actionId = 10)
    val trace =
      TraceTree.newBuilder()
        .addEvents(tableLookupHit(entry))
        .addEvents(tableLookupHit(entry))
        .setDrop(dropOutcome())
        .build()

    val entities = extract(trace, emptyTableStore())
    assertEquals("duplicate should be deduplicated", 1, entities.size)
  }

  @Test
  fun `register seed dependencies extract register entries`() {
    val trace = TraceTree.newBuilder().setDrop(dropOutcome()).build()
    val store = tableStoreWithRegister()
    val entities =
      extractReproducerEntities(
        trace,
        store.snapshot,
        store,
        emptyList(),
        listOf(RegisterSeedDependency("myRegister", 1, BitVal(42, 32))),
      )

    assertEquals(1, entities.size)
    assertTrue(entities[0].hasRegisterEntry())
    assertEquals(500, entities[0].registerEntry.registerId)
    assertEquals(1, entities[0].registerEntry.index.index)
    assertEquals(
      ByteString.copyFrom(byteArrayOf(0, 0, 0, 42)),
      entities[0].registerEntry.data.bitstring,
    )
  }

  @Test
  fun `clone session lookup extracts clone session entry`() {
    val cloneEntry =
      CloneSessionEntry.newBuilder()
        .setSessionId(42)
        .setClassOfService(0)
        .setPacketLengthBytes(0)
        .addReplicas(
          Replica.newBuilder().setPort(ByteString.copyFrom(byteArrayOf(1))).setInstance(0)
        )
        .build()
    val store = tableStoreWith { cloneSessions[42] = cloneEntry }

    val trace =
      TraceTree.newBuilder()
        .addEvents(cloneSessionLookup(sessionId = 42, found = true))
        .setDrop(dropOutcome())
        .build()

    val entities = extract(trace, store)

    assertEquals(1, entities.size)
    assertTrue(entities[0].hasPacketReplicationEngineEntry())
    assertEquals(cloneEntry, entities[0].packetReplicationEngineEntry.cloneSessionEntry)
  }

  @Test
  fun `clone session not found produces no entities`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(cloneSessionLookup(sessionId = 99, found = false))
        .setDrop(dropOutcome())
        .build()

    val entities = extract(trace, emptyTableStore())
    assertTrue(entities.isEmpty())
  }

  @Test
  fun `fork branches are traversed recursively`() {
    val entry1 = tableEntry(tableId = 1, matchValue = byteArrayOf(0x01), actionId = 10)
    val entry2 = tableEntry(tableId = 2, matchValue = byteArrayOf(0x02), actionId = 20)

    val trace =
      TraceTree.newBuilder()
        .setReplication(
          Replication.newBuilder()
            .addBranches(
              TraceTree.newBuilder().addEvents(tableLookupHit(entry1)).setDrop(dropOutcome())
            )
            .addBranches(
              TraceTree.newBuilder().addEvents(tableLookupHit(entry2)).setDrop(dropOutcome())
            )
        )
        .build()

    val entities = extract(trace, emptyTableStore())

    assertEquals(2, entities.size)
    assertEquals(entry1, entities[0].tableEntry)
    assertEquals(entry2, entities[1].tableEntry)
  }

  private fun tableStoreWithActionProfile(
    profileId: Int = 100,
    tableId: Int = 1,
    setup: TableStore.ForwardingSnapshot.() -> Unit,
  ): TableStore {
    val store = TableStore()
    val p4info =
      org.p4.config.v1.P4Info.newBuilder()
        .addTables(
          org.p4.config.v1.Table.newBuilder()
            .setPreamble(org.p4.config.v1.Preamble.newBuilder().setId(tableId).setAlias("t"))
            .setImplementationId(profileId)
        )
        .addActionProfiles(
          org.p4.config.v1.ActionProfile.newBuilder()
            .setPreamble(org.p4.config.v1.Preamble.newBuilder().setId(profileId).setAlias("ap"))
        )
        .build()
    store.loadMappings(p4info, fourward.DeviceConfig.getDefaultInstance())
    store.snapshot.setup()
    return store
  }

  @Test
  fun `action profile member is extracted from matched entry`() {
    val member =
      ActionProfileMember.newBuilder()
        .setActionProfileId(100)
        .setMemberId(5)
        .setAction(Action.newBuilder().setActionId(10))
        .build()

    val store = tableStoreWithActionProfile { profileMembers[100] = mutableMapOf(5 to member) }

    val entry =
      TableEntry.newBuilder()
        .setTableId(1)
        .setAction(TableAction.newBuilder().setActionProfileMemberId(5))
        .build()

    val trace =
      TraceTree.newBuilder().addEvents(tableLookupHit(entry)).setDrop(dropOutcome()).build()

    val entities = extract(trace, store)

    assertEquals(2, entities.size)
    assertTrue(entities[0].hasTableEntry())
    assertTrue(entities[1].hasActionProfileMember())
    assertEquals(member, entities[1].actionProfileMember)
  }

  @Test
  fun `action profile group and its members are extracted`() {
    val member1 =
      ActionProfileMember.newBuilder()
        .setActionProfileId(100)
        .setMemberId(1)
        .setAction(Action.newBuilder().setActionId(10))
        .build()
    val member2 =
      ActionProfileMember.newBuilder()
        .setActionProfileId(100)
        .setMemberId(2)
        .setAction(Action.newBuilder().setActionId(20))
        .build()
    val group =
      ActionProfileGroup.newBuilder()
        .setActionProfileId(100)
        .setGroupId(7)
        .addMembers(ActionProfileGroup.Member.newBuilder().setMemberId(1).setWeight(1))
        .addMembers(ActionProfileGroup.Member.newBuilder().setMemberId(2).setWeight(1))
        .build()

    val store = tableStoreWithActionProfile {
      profileMembers[100] = mutableMapOf(1 to member1, 2 to member2)
      profileGroups[100] = mutableMapOf(7 to group)
    }

    val entry =
      TableEntry.newBuilder()
        .setTableId(1)
        .setAction(TableAction.newBuilder().setActionProfileGroupId(7))
        .build()

    val trace =
      TraceTree.newBuilder().addEvents(tableLookupHit(entry)).setDrop(dropOutcome()).build()

    val entities = extract(trace, store)

    assertEquals(4, entities.size)
    assertTrue("table entry", entities[0].hasTableEntry())
    assertTrue("group", entities[1].hasActionProfileGroup())
    assertTrue("member 1", entities[2].hasActionProfileMember())
    assertTrue("member 2", entities[3].hasActionProfileMember())
  }

  @Test
  fun `modified default action is extracted on table miss`() {
    val store = TableStore()
    val p4info =
      org.p4.config.v1.P4Info.newBuilder()
        .addTables(
          org.p4.config.v1.Table.newBuilder()
            .setPreamble(org.p4.config.v1.Preamble.newBuilder().setId(1).setAlias("MyTable"))
        )
        .addActions(
          org.p4.config.v1.Action.newBuilder()
            .setPreamble(org.p4.config.v1.Preamble.newBuilder().setId(10).setAlias("my_action"))
        )
        .build()
    store.loadMappings(p4info, fourward.DeviceConfig.getDefaultInstance())
    store.setDefaultAction("MyTable", "my_action")
    // Use write() with a MODIFY to mark the default as modified, matching production behavior.
    store.write(
      Update.newBuilder()
        .setType(Update.Type.MODIFY)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(1)
                .setIsDefaultAction(true)
                .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(10)))
            )
        )
        .build()
    )
    store.publishSnapshot()

    val trace =
      TraceTree.newBuilder().addEvents(tableLookupMiss("MyTable")).setDrop(dropOutcome()).build()

    val entities = extract(trace, store)

    assertEquals(1, entities.size)
    assertTrue(entities[0].hasTableEntry())
    assertTrue("should be default action", entities[0].tableEntry.isDefaultAction)
    assertEquals(1, entities[0].tableEntry.tableId)
    assertEquals(10, entities[0].tableEntry.action.action.actionId)
  }

  @Test
  fun `unmodified default action is not extracted on table miss`() {
    val store = TableStore()
    val p4info =
      org.p4.config.v1.P4Info.newBuilder()
        .addTables(
          org.p4.config.v1.Table.newBuilder()
            .setPreamble(org.p4.config.v1.Preamble.newBuilder().setId(1).setAlias("MyTable"))
        )
        .addActions(
          org.p4.config.v1.Action.newBuilder()
            .setPreamble(org.p4.config.v1.Preamble.newBuilder().setId(10).setAlias("my_action"))
        )
        .build()
    store.loadMappings(p4info, fourward.DeviceConfig.getDefaultInstance())
    store.setDefaultAction("MyTable", "my_action")
    store.publishSnapshot()

    val trace =
      TraceTree.newBuilder().addEvents(tableLookupMiss("MyTable")).setDrop(dropOutcome()).build()

    val entities = extract(trace, store)
    assertTrue("unmodified default should not be extracted", entities.isEmpty())
  }

  private fun multicastGroupLookup(groupId: Int): TraceEvent =
    TraceEvent.newBuilder()
      .setMulticastGroupLookup(
        fourward.MulticastGroupLookupEvent.newBuilder()
          .setMulticastGroupId(groupId)
          .setGroupFound(true)
          .setReplicaCount(2)
      )
      .build()

  @Test
  fun `multicast group lookup extracts multicast group entity`() {
    val group =
      MulticastGroupEntry.newBuilder()
        .setMulticastGroupId(1)
        .addReplicas(
          Replica.newBuilder().setPort(ByteString.copyFrom(byteArrayOf(1))).setInstance(0)
        )
        .addReplicas(
          Replica.newBuilder().setPort(ByteString.copyFrom(byteArrayOf(2))).setInstance(0)
        )
        .build()
    val store = tableStoreWith { multicastGroups[1] = group }

    val trace =
      TraceTree.newBuilder()
        .addEvents(multicastGroupLookup(groupId = 1))
        .setDrop(dropOutcome())
        .build()

    val entities = extract(trace, store)

    assertEquals(1, entities.size)
    assertTrue(entities[0].hasPacketReplicationEngineEntry())
    assertEquals(group, entities[0].packetReplicationEngineEntry.multicastGroupEntry)
  }

  @Test
  fun `multicast group miss produces no entities`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setMulticastGroupLookup(
              fourward.MulticastGroupLookupEvent.newBuilder()
                .setMulticastGroupId(99)
                .setGroupFound(false)
            )
        )
        .setDrop(dropOutcome())
        .build()

    val entities = extract(trace, emptyTableStore())
    assertTrue(entities.isEmpty())
  }
}
