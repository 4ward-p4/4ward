package fourward.simulator

import com.google.protobuf.ByteString
import fourward.ActionDecl
import fourward.Architecture
import fourward.BehavioralConfig
import fourward.DeviceConfig
import fourward.Expr
import fourward.MethodCall
import fourward.MethodCallStmt
import fourward.NameRef
import fourward.Stmt
import fourward.TableBehavior
import fourward.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableAction
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

class DirectResourceUsageTest {
  @Test
  fun `v1model direct counter accepts inline data for action without explicit count`() {
    val store = TableStore()
    store.loadMappings(p4info = p4infoWithDirectCounter(), device = v1modelDevice())
    val entry = withCounterData(exactEntry(actionId = ACTION_20_ID))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `v1model direct meter accepts inline config for action without explicit read`() {
    val store = TableStore()
    store.loadMappings(p4info = p4infoWithDirectMeter(), device = v1modelDevice())
    val entry = withMeterConfig(exactEntry(actionId = ACTION_20_ID))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `action profile member with counter data rejects action without direct counter call`() {
    val store = TableStore()
    store.loadMappings(
      p4info = p4infoWithActionProfileDirectCounter(),
      device = deviceWithDirectCounterAction(),
    )
    writeMember(store, memberId = 1, actionId = ACTION_20_ID)
    val entry = withCounterData(exactMemberEntry(memberId = 1))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertTrue(store.getTableEntries(TABLE_NAME).isEmpty())
  }

  @Test
  fun `action profile member with counter data accepts action with direct counter call`() {
    val store = TableStore()
    store.loadMappings(
      p4info = p4infoWithActionProfileDirectCounter(),
      device = deviceWithDirectCounterAction(),
    )
    writeMember(store, memberId = 1, actionId = ACTION_10_ID)
    val entry = withCounterData(exactMemberEntry(memberId = 1))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `action profile group with counter data rejects member action without direct counter call`() {
    val store = TableStore()
    store.loadMappings(
      p4info = p4infoWithActionProfileDirectCounter(),
      device = deviceWithDirectCounterAction(),
    )
    writeMember(store, memberId = 1, actionId = ACTION_10_ID)
    writeMember(store, memberId = 2, actionId = ACTION_20_ID)
    writeGroup(store, groupId = 1, memberIds = listOf(1, 2))
    val entry = withCounterData(exactGroupEntry(groupId = 1))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertTrue(store.getTableEntries(TABLE_NAME).isEmpty())
  }

  // -------------------------------------------------------------------------
  // v1model + action profile/selector: counters fire implicitly on every hit,
  // so counter_data must be accepted even for actions that don't call count().
  // Regression tests for https://github.com/smolkaj/4ward/issues/737.
  // -------------------------------------------------------------------------

  @Test
  fun `v1model action profile member accepts counter data for action without explicit count`() {
    val store = TableStore()
    store.loadMappings(p4info = p4infoWithActionProfileDirectCounter(), device = v1modelDevice())
    writeMember(store, memberId = 1, actionId = ACTION_20_ID)
    val entry = withCounterData(exactMemberEntry(memberId = 1))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `v1model action selector one-shot accepts counter data for action without explicit count`() {
    val store = TableStore()
    store.loadMappings(
      p4info = p4infoWithActionProfileDirectCounter(withSelector = true),
      device = v1modelDevice(),
    )
    val entry = withCounterData(exactOneShotEntry(ACTION_20_ID))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `non-v1model rejects counter data for action without explicit count`() {
    val store = TableStore()
    store.loadMappings(
      p4info = p4infoWithNamedDirectCounter(),
      device = deviceWithDirectCounterAction(),
    )
    val entry = withCounterData(exactEntry(actionId = ACTION_20_ID))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertTrue(store.getTableEntries(TABLE_NAME).isEmpty())
  }

  private fun TableStore.writeAndPublish(update: Update): WriteResult =
    write(update).also { publishSnapshot() }

  private fun exactEntry(actionId: Int): TableEntry =
    exactEntryBuilder()
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun exactMemberEntry(memberId: Int): TableEntry =
    exactEntryBuilder()
      .setAction(TableAction.newBuilder().setActionProfileMemberId(memberId))
      .build()

  private fun exactGroupEntry(groupId: Int): TableEntry =
    exactEntryBuilder().setAction(TableAction.newBuilder().setActionProfileGroupId(groupId)).build()

  private fun exactOneShotEntry(vararg actionIds: Int): TableEntry =
    exactEntryBuilder()
      .setAction(
        TableAction.newBuilder()
          .setActionProfileActionSet(
            P4RuntimeOuterClass.ActionProfileActionSet.newBuilder()
              .addAllActionProfileActions(
                actionIds.map { actionId ->
                  P4RuntimeOuterClass.ActionProfileAction.newBuilder()
                    .setAction(Action.newBuilder().setActionId(actionId))
                    .setWeight(1)
                    .build()
                }
              )
          )
      )
      .build()

  private fun exactEntryBuilder(): TableEntry.Builder =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(FIELD_ID)
          .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(10))))
      )

  private fun withCounterData(entry: TableEntry): TableEntry =
    entry
      .toBuilder()
      .setCounterData(P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(42))
      .build()

  private fun withMeterConfig(entry: TableEntry): TableEntry =
    entry
      .toBuilder()
      .setMeterConfig(P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(1000))
      .build()

  private fun insertUpdate(entry: TableEntry): Update =
    Update.newBuilder()
      .setType(Update.Type.INSERT)
      .setEntity(Entity.newBuilder().setTableEntry(entry))
      .build()

  private fun writeMember(store: TableStore, memberId: Int, actionId: Int) {
    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileMember(
              P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(PROFILE_ID)
                .setMemberId(memberId)
                .setAction(Action.newBuilder().setActionId(actionId))
            )
        )
        .build()
    )
  }

  private fun writeGroup(store: TableStore, groupId: Int, memberIds: List<Int>) {
    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileGroup(
              P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                .setActionProfileId(PROFILE_ID)
                .setGroupId(groupId)
                .addAllMembers(
                  memberIds.map { memberId ->
                    P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                      .setMemberId(memberId)
                      .setWeight(1)
                      .build()
                  }
                )
            )
        )
        .build()
    )
  }

  private fun v1modelDevice(): DeviceConfig =
    DeviceConfig.newBuilder()
      .setBehavioral(
        BehavioralConfig.newBuilder()
          .setArchitecture(Architecture.newBuilder().setName("v1model"))
          .addTables(TableBehavior.newBuilder().setName(TABLE_NAME))
          .addActions(ActionDecl.newBuilder().setName("action10"))
          .addActions(ActionDecl.newBuilder().setName("action20"))
      )
      .build()

  private fun deviceWithDirectCounterAction(): DeviceConfig =
    DeviceConfig.newBuilder()
      .setBehavioral(
        BehavioralConfig.newBuilder()
          .addTables(TableBehavior.newBuilder().setName(TABLE_NAME))
          .addActions(
            ActionDecl.newBuilder()
              .setName("action10")
              .addBody(directResourceCall("dc", "direct_counter", "count"))
          )
          .addActions(ActionDecl.newBuilder().setName("action20"))
      )
      .build()

  private fun directResourceCall(instance: String, externType: String, method: String): Stmt =
    Stmt.newBuilder()
      .setMethodCall(
        MethodCallStmt.newBuilder()
          .setCall(
            Expr.newBuilder()
              .setMethodCall(
                MethodCall.newBuilder()
                  .setTarget(
                    Expr.newBuilder()
                      .setNameRef(NameRef.newBuilder().setName(instance))
                      .setType(Type.newBuilder().setNamed(externType))
                  )
                  .setMethod(method)
              )
          )
      )
      .build()

  private fun p4infoWithDirectCounter(): P4InfoOuterClass.P4Info =
    baseP4InfoBuilder()
      .addDirectCounters(
        P4InfoOuterClass.DirectCounter.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(DIRECT_COUNTER_ID))
          .setDirectTableId(TABLE_ID)
      )
      .build()

  /**
   * Like [p4infoWithDirectCounter], but with a name on the counter preamble so the explicit
   * (non-v1model) path can match it against `directResourceCalls` in the action body.
   */
  private fun p4infoWithNamedDirectCounter(): P4InfoOuterClass.P4Info =
    baseP4InfoBuilder()
      .addDirectCounters(
        P4InfoOuterClass.DirectCounter.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder()
              .setId(DIRECT_COUNTER_ID)
              .setName("dc")
              .setAlias("dc")
          )
          .setDirectTableId(TABLE_ID)
      )
      .build()

  private fun p4infoWithActionProfileDirectCounter(
    withSelector: Boolean = false
  ): P4InfoOuterClass.P4Info =
    baseP4InfoBuilder(implementationId = PROFILE_ID)
      .addActionProfiles(
        P4InfoOuterClass.ActionProfile.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(PROFILE_ID))
          .setWithSelector(withSelector)
      )
      .addDirectCounters(
        P4InfoOuterClass.DirectCounter.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder()
              .setId(DIRECT_COUNTER_ID)
              .setName("dc")
              .setAlias("dc")
          )
          .setDirectTableId(TABLE_ID)
      )
      .build()

  private fun p4infoWithDirectMeter(): P4InfoOuterClass.P4Info =
    baseP4InfoBuilder()
      .addDirectMeters(
        P4InfoOuterClass.DirectMeter.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(DIRECT_METER_ID))
          .setDirectTableId(TABLE_ID)
      )
      .build()

  private fun baseP4InfoBuilder(implementationId: Int = 0): P4InfoOuterClass.P4Info.Builder =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID).setAlias(TABLE_NAME))
          .setImplementationId(implementationId)
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_10_ID))
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_20_ID))
      )
      .addActions(action(ACTION_10_ID, "action10"))
      .addActions(action(ACTION_20_ID, "action20"))

  private fun action(id: Int, name: String): P4InfoOuterClass.Action =
    P4InfoOuterClass.Action.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setAlias(name))
      .build()

  companion object {
    private const val TABLE_ID = 1
    private const val TABLE_NAME = "myTable"
    private const val FIELD_ID = 1
    private const val PROFILE_ID = 100
    private const val ACTION_10_ID = 10
    private const val ACTION_20_ID = 20
    private const val DIRECT_COUNTER_ID = 800
    private const val DIRECT_METER_ID = 900
  }
}
