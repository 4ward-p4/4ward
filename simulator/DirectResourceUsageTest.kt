package fourward.simulator

import com.google.protobuf.ByteString
import fourward.ActionDecl
import fourward.Architecture
import fourward.BehavioralConfig
import fourward.ControlDecl
import fourward.DeviceConfig
import fourward.Expr
import fourward.ExternInstanceDecl
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
  // Regression tests for https://github.com/4ward-p4/4ward/issues/737.
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

  @Test
  fun `MODIFY with unset action validates counter data against existing action`() {
    val store = TableStore()
    store.loadMappings(p4info = p4infoWithDirectCounter(), device = deviceWithDirectCounterAction())
    assertEquals(WriteResult.Success, store.writeAndPublish(insertUpdate(exactEntry(ACTION_20_ID))))
    val modify = withCounterData(exactEntryBuilder().build())

    val result = store.writeAndPublish(modifyUpdate(modify))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `DELETE rejects counter data on table without direct counter`() {
    val store = TableStore()
    store.loadMappings(
      p4info = p4infoWithoutDirectResources(),
      device = deviceWithDirectCounterAction(),
    )
    val inserted = insertUpdate(exactEntry(ACTION_20_ID))
    assertEquals(WriteResult.Success, store.writeAndPublish(inserted))
    val delete = withCounterData(exactEntryBuilder().build())

    val result = store.writeAndPublish(deleteUpdate(delete))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `DELETE rejects meter config on table without direct meter`() {
    val store = TableStore()
    store.loadMappings(
      p4info = p4infoWithoutDirectResources(),
      device = deviceWithDirectMeterAction(),
    )
    val inserted = insertUpdate(exactEntry(ACTION_20_ID))
    assertEquals(WriteResult.Success, store.writeAndPublish(inserted))
    val delete = withMeterConfig(exactEntryBuilder().build())

    val result = store.writeAndPublish(deleteUpdate(delete))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `default entry direct counter data is stored and readable`() {
    val store = TableStore()
    store.loadMappings(p4info = p4infoWithDirectCounter(), device = deviceWithDirectCounterAction())
    val defaultEntry = withCounterData(defaultEntry(actionId = ACTION_10_ID))

    val result = store.writeAndPublish(modifyUpdate(defaultEntry))

    assertEquals(WriteResult.Success, result)
    val readBack =
      store.readDirectCounterEntries(
        P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
          .setTableEntry(TableEntry.newBuilder().setTableId(TABLE_ID).setIsDefaultAction(true))
          .build()
      )
    assertEquals(1, readBack.size)
    assertEquals(42, readBack[0].directCounterEntry.data.packetCount)
  }

  @Test
  fun `default entry direct meter config is stored and readable`() {
    val store = TableStore()
    store.loadMappings(p4info = p4infoWithDirectMeter(), device = deviceWithDirectMeterAction())
    val defaultEntry = withMeterConfig(defaultEntry(actionId = ACTION_10_ID))

    val result = store.writeAndPublish(modifyUpdate(defaultEntry))

    assertEquals(WriteResult.Success, result)
    val readBack =
      store.readDirectMeterEntries(
        P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
          .setTableEntry(TableEntry.newBuilder().setTableId(TABLE_ID).setIsDefaultAction(true))
          .build()
      )
    assertEquals(1, readBack.size)
    assertEquals(1000, readBack[0].directMeterEntry.config.cir)
  }

  @Test
  fun `resetting default entry resets direct counter data`() {
    val store = TableStore()
    store.loadMappings(p4info = p4infoWithDirectCounter(), device = deviceWithDirectCounterAction())
    val defaultEntry = withCounterData(defaultEntry(actionId = ACTION_10_ID))
    assertEquals(WriteResult.Success, store.writeAndPublish(modifyUpdate(defaultEntry)))

    val result = store.writeAndPublish(modifyUpdate(defaultEntry()))

    assertEquals(WriteResult.Success, result)
    val readBack =
      store.readDirectCounterEntries(
        P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
          .setTableEntry(TableEntry.newBuilder().setTableId(TABLE_ID).setIsDefaultAction(true))
          .build()
      )
    assertEquals(0, readBack[0].directCounterEntry.data.packetCount)
  }

  @Test
  fun `default entry with unset action rejects counter data when initial default is NoAction`() {
    val store = TableStore()
    store.loadMappings(p4info = p4infoWithDirectCounter(), device = deviceWithDirectCounterAction())
    val defaultEntry = withCounterData(defaultEntry())

    val result = store.writeAndPublish(modifyUpdate(defaultEntry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `explicit direct counter matches midend-qualified IR instance name`() {
    // After p4c midend, a counter declared as "my_counter" inside control
    // "my_ctrl" may appear in the IR as "my_ctrl_my_counter". The p4info
    // preamble has name="ingress.my_ctrl.my_counter" and alias="my_counter".
    // The action body references the IR name, not the p4info name.
    val store = TableStore()
    store.loadMappings(
      p4info =
        baseP4InfoBuilder()
          .addDirectCounters(
            P4InfoOuterClass.DirectCounter.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(DIRECT_COUNTER_ID)
                  .setName("ingress.my_ctrl.my_counter")
                  .setAlias("my_counter")
              )
              .setDirectTableId(TABLE_ID)
          )
          .build(),
      device = deviceWithDirectCounterAction(counterInstanceName = "my_ctrl_my_counter"),
    )
    val entry = withCounterData(exactEntry(actionId = ACTION_10_ID))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
  }

  @Test
  fun `explicit direct counter rejects action without count even with qualified names`() {
    val store = TableStore()
    store.loadMappings(
      p4info =
        baseP4InfoBuilder()
          .addDirectCounters(
            P4InfoOuterClass.DirectCounter.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(DIRECT_COUNTER_ID)
                  .setName("ingress.my_ctrl.my_counter")
                  .setAlias("my_counter")
              )
              .setDirectTableId(TABLE_ID)
          )
          .build(),
      device = deviceWithDirectCounterAction(counterInstanceName = "my_ctrl_my_counter"),
    )
    val entry = withCounterData(exactEntry(actionId = ACTION_20_ID))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument, got $result", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `suffix match does not cross-contaminate counters on different tables`() {
    val tableAId = 1
    val tableBId = 2
    val store = TableStore()
    store.loadMappings(
      p4info =
        P4InfoOuterClass.P4Info.newBuilder()
          .addTables(
            P4InfoOuterClass.Table.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder().setId(tableAId).setAlias("tableA")
              )
              .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_10_ID))
              .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_20_ID))
          )
          .addTables(
            P4InfoOuterClass.Table.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder().setId(tableBId).setAlias("tableB")
              )
              .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_10_ID))
              .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_20_ID))
          )
          .addActions(action(ACTION_10_ID, "action10"))
          .addActions(action(ACTION_20_ID, "action20"))
          .addDirectCounters(
            P4InfoOuterClass.DirectCounter.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(801)
                  .setName("ingress.ctrl.a_counter")
                  .setAlias("a_counter")
              )
              .setDirectTableId(tableAId)
          )
          .addDirectCounters(
            P4InfoOuterClass.DirectCounter.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(802)
                  .setName("ingress.ctrl.b_counter")
                  .setAlias("b_counter")
              )
              .setDirectTableId(tableBId)
          )
          .build(),
      device =
        DeviceConfig.newBuilder()
          .setBehavioral(
            BehavioralConfig.newBuilder()
              .addTables(TableBehavior.newBuilder().setName("tableA"))
              .addTables(TableBehavior.newBuilder().setName("tableB"))
              .addActions(
                ActionDecl.newBuilder()
                  .setName("action10")
                  .addBody(directResourceCall("ctrl_a_counter", "direct_counter", "count"))
              )
              .addActions(
                ActionDecl.newBuilder()
                  .setName("action20")
                  .addBody(directResourceCall("ctrl_b_counter", "direct_counter", "count"))
              )
              .addControls(
                ControlDecl.newBuilder()
                  .setName("ctrl")
                  .addExternInstances(
                    ExternInstanceDecl.newBuilder()
                      .setTypeName("direct_counter")
                      .setName("ctrl_a_counter")
                  )
                  .addExternInstances(
                    ExternInstanceDecl.newBuilder()
                      .setTypeName("direct_counter")
                      .setName("ctrl_b_counter")
                  )
              )
          )
          .build(),
    )

    val entryA =
      TableEntry.newBuilder()
        .setTableId(tableAId)
        .addMatch(
          FieldMatch.newBuilder()
            .setFieldId(FIELD_ID)
            .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(1))))
        )
        .setAction(tableAction(ACTION_10_ID))
        .setCounterData(P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(1))
        .build()
    assertEquals(WriteResult.Success, store.writeAndPublish(insertUpdate(entryA)))

    val entryA2 =
      TableEntry.newBuilder()
        .setTableId(tableAId)
        .addMatch(
          FieldMatch.newBuilder()
            .setFieldId(FIELD_ID)
            .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(2))))
        )
        .setAction(tableAction(ACTION_20_ID))
        .setCounterData(P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(1))
        .build()
    val result = store.writeAndPublish(insertUpdate(entryA2))
    assertTrue("expected InvalidArgument, got $result", result is WriteResult.InvalidArgument)
  }

  private fun TableStore.writeAndPublish(update: Update): WriteResult =
    write(update).also { publishSnapshot() }

  private fun exactEntry(actionId: Int): TableEntry =
    exactEntryBuilder().setAction(tableAction(actionId)).build()

  private fun exactMemberEntry(memberId: Int): TableEntry =
    exactEntryBuilder()
      .setAction(TableAction.newBuilder().setActionProfileMemberId(memberId))
      .build()

  private fun exactGroupEntry(groupId: Int): TableEntry =
    exactEntryBuilder().setAction(TableAction.newBuilder().setActionProfileGroupId(groupId)).build()

  private fun tableAction(actionId: Int): TableAction.Builder =
    TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId))

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

  private fun defaultEntry(actionId: Int? = null): TableEntry {
    val builder = TableEntry.newBuilder().setTableId(TABLE_ID).setIsDefaultAction(true)
    if (actionId != null) {
      builder.setAction(tableAction(actionId))
    }
    return builder.build()
  }

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

  private fun insertUpdate(entry: TableEntry): Update = update(Update.Type.INSERT, entry)

  private fun modifyUpdate(entry: TableEntry): Update = update(Update.Type.MODIFY, entry)

  private fun deleteUpdate(entry: TableEntry): Update = update(Update.Type.DELETE, entry)

  private fun update(type: Update.Type, entry: TableEntry): Update =
    Update.newBuilder().setType(type).setEntity(Entity.newBuilder().setTableEntry(entry)).build()

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

  private fun deviceWithDirectCounterAction(counterInstanceName: String = "dc"): DeviceConfig =
    DeviceConfig.newBuilder()
      .setBehavioral(
        BehavioralConfig.newBuilder()
          .addTables(TableBehavior.newBuilder().setName(TABLE_NAME))
          .addActions(
            ActionDecl.newBuilder()
              .setName("action10")
              .addBody(directResourceCall(counterInstanceName, "direct_counter", "count"))
          )
          .addActions(ActionDecl.newBuilder().setName("action20"))
          .addControls(
            ControlDecl.newBuilder()
              .setName("ctrl")
              .addExternInstances(
                ExternInstanceDecl.newBuilder()
                  .setTypeName("direct_counter")
                  .setName(counterInstanceName)
              )
          )
      )
      .build()

  private fun deviceWithDirectMeterAction(): DeviceConfig =
    DeviceConfig.newBuilder()
      .setBehavioral(
        BehavioralConfig.newBuilder()
          .addTables(TableBehavior.newBuilder().setName(TABLE_NAME))
          .addActions(
            ActionDecl.newBuilder()
              .setName("action10")
              .addBody(directResourceCall("dm", "direct_meter", "read"))
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
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder()
              .setId(DIRECT_COUNTER_ID)
              .setName("dc")
              .setAlias("dc")
          )
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

  private fun p4infoWithoutDirectResources(): P4InfoOuterClass.P4Info = baseP4InfoBuilder().build()

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
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder()
              .setId(DIRECT_METER_ID)
              .setName("dm")
              .setAlias("dm")
          )
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
