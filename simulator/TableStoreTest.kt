package fourward.simulator

import com.google.protobuf.ByteString
import fourward.ActionDecl
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableAction
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Unit tests for [TableStore] covering exact, LPM, and ternary match kinds along with basic
 * write/delete and default-action semantics.
 */
/** Writes and immediately publishes the snapshot so reads see the change. */
private fun TableStore.writeAndPublish(update: Update): WriteResult =
  write(update).also { publishSnapshot() }

/** Sets default action and immediately publishes the snapshot. */
private fun TableStore.setDefaultActionAndPublish(
  tableName: String,
  actionName: String,
  params: List<P4RuntimeOuterClass.Action.Param> = emptyList(),
) {
  setDefaultAction(tableName, actionName, params)
  publishSnapshot()
}

class TableStoreTest {

  private lateinit var store: TableStore

  @Before
  fun setUp() {
    store = TableStore()
    store.loadMappings(p4info = BASE_P4INFO)
  }

  /** Builds a [P4InfoOuterClass.P4Info] from individual entity lists. */
  private fun buildP4Info(
    tables: List<P4InfoOuterClass.Table> = emptyList(),
    actions: List<P4InfoOuterClass.Action> = emptyList(),
    registers: List<P4InfoOuterClass.Register> = emptyList(),
    actionProfiles: List<P4InfoOuterClass.ActionProfile> = emptyList(),
    counters: List<P4InfoOuterClass.Counter> = emptyList(),
    meters: List<P4InfoOuterClass.Meter> = emptyList(),
    directCounters: List<P4InfoOuterClass.DirectCounter> = emptyList(),
    directMeters: List<P4InfoOuterClass.DirectMeter> = emptyList(),
    valueSets: List<P4InfoOuterClass.ValueSet> = emptyList(),
  ): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addAllTables(tables)
      .addAllActions(actions)
      .addAllRegisters(registers)
      .addAllActionProfiles(actionProfiles)
      .addAllCounters(counters)
      .addAllMeters(meters)
      .addAllDirectCounters(directCounters)
      .addAllDirectMeters(directMeters)
      .addAllValueSets(valueSets)
      .build()

  /** Builds a minimal p4info [P4InfoOuterClass.Table] with the given ID and name. */
  private fun p4infoTable(
    id: Int,
    name: String,
    size: Long = 0,
    implementationId: Int = 0,
    constDefaultActionId: Int = 0,
  ): P4InfoOuterClass.Table =
    P4InfoOuterClass.Table.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name).setAlias(name))
      .setSize(size)
      .setImplementationId(implementationId)
      .setConstDefaultActionId(constDefaultActionId)
      .build()

  // ---------------------------------------------------------------------------
  // Entry builders
  // ---------------------------------------------------------------------------

  private fun exactEntry(fieldId: Int, value: ByteArray, actionId: Int): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value)))
      )
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun lpmEntry(fieldId: Int, value: ByteArray, prefixLen: Int, actionId: Int): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setLpm(
            FieldMatch.LPM.newBuilder().setValue(ByteString.copyFrom(value)).setPrefixLen(prefixLen)
          )
      )
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun ternaryEntry(
    fieldId: Int,
    value: ByteArray,
    mask: ByteArray,
    priority: Int,
    actionId: Int,
  ): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setTernary(
            FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(value))
              .setMask(ByteString.copyFrom(mask))
          )
      )
      .setPriority(priority)
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun rangeEntry(
    fieldId: Int,
    lo: ByteArray,
    hi: ByteArray,
    priority: Int,
    actionId: Int,
  ): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setRange(
            FieldMatch.Range.newBuilder()
              .setLow(ByteString.copyFrom(lo))
              .setHigh(ByteString.copyFrom(hi))
          )
      )
      .setPriority(priority)
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun optionalEntry(fieldId: Int, value: ByteArray, actionId: Int): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setOptional(FieldMatch.Optional.newBuilder().setValue(ByteString.copyFrom(value)))
      )
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun withAction(entry: TableEntry, actionId: Int): TableEntry =
    entry
      .toBuilder()
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun withCounterData(
    entry: TableEntry,
    packetCount: Long = 42,
    byteCount: Long = 1000,
  ): TableEntry =
    entry
      .toBuilder()
      .setCounterData(
        P4RuntimeOuterClass.CounterData.newBuilder()
          .setPacketCount(packetCount)
          .setByteCount(byteCount)
      )
      .build()

  private fun withMeterConfig(entry: TableEntry, cir: Long = 1000, cburst: Long = 100): TableEntry =
    entry
      .toBuilder()
      .setMeterConfig(P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(cir).setCburst(cburst))
      .build()

  private fun withMeterCounterData(
    entry: TableEntry,
    packetCount: Long = 42,
    byteCount: Long = 1000,
  ): TableEntry =
    entry
      .toBuilder()
      .setMeterCounterData(
        P4RuntimeOuterClass.MeterCounterData.newBuilder()
          .setGreen(
            P4RuntimeOuterClass.CounterData.newBuilder()
              .setPacketCount(packetCount)
              .setByteCount(byteCount)
          )
      )
      .build()

  private fun actionDecl(name: String, vararg body: Stmt): ActionDecl =
    ActionDecl.newBuilder().setName(name).addAllBody(body.toList()).build()

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

  private fun deviceWithActions(vararg actions: ActionDecl): DeviceConfig =
    DeviceConfig.newBuilder()
      .setBehavioral(
        BehavioralConfig.newBuilder()
          .addTables(
            TableBehavior.newBuilder()
              .setName(TABLE_NAME)
              .putAllActionOverrides(ACTION_IDS.associate { "action$it" to "action$it" })
          )
          .addAllActions(actions.toList())
      )
      .setControlPlaneBindings(
        fourward.ControlPlaneBindings.newBuilder()
          .addTables(binding(TABLE_NAME, TABLE_NAME))
          .addAllActions(ACTION_IDS.map { binding("action$it", "action$it") })
      )
      .build()

  private fun binding(p4infoName: String, simulatorName: String): fourward.ControlPlaneBinding =
    fourward.ControlPlaneBinding.newBuilder()
      .setP4InfoName(p4infoName)
      .setSimulatorName(simulatorName)
      .build()

  private fun write(entry: TableEntry) {
    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()
    )
  }

  // ---------------------------------------------------------------------------
  // Default action / miss
  // ---------------------------------------------------------------------------

  @Test
  fun `miss on empty table returns default action`() {
    store.setDefaultActionAndPublish(TABLE_NAME, "drop")
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(1, 8)))
    assertFalse(result.hit)
    assertNull(result.entry)
    assertEquals("drop", result.actionName)
  }

  @Test
  fun `miss with no registered default returns NoAction`() {
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(1, 8)))
    assertFalse(result.hit)
    assertEquals("NoAction", result.actionName)
  }

  // ---------------------------------------------------------------------------
  // Exact match
  // ---------------------------------------------------------------------------

  @Test
  fun `exact match hit returns entry and resolved action name`() {
    write(exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42))
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8)))
    assertTrue(result.hit)
    assertEquals("action42", result.actionName)
  }

  @Test
  fun `exact match hit with BoolVal key`() {
    write(exactEntry(fieldId = 1, value = byteArrayOf(0x00), actionId = 42))
    val result = store.lookup(TABLE_NAME, listOf("1" to BoolVal(false)))
    assertTrue(result.hit)
    assertEquals("action42", result.actionName)
  }

  @Test
  fun `exact match miss on different value`() {
    write(exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42))
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(11, 8)))
    assertFalse(result.hit)
  }

  // ---------------------------------------------------------------------------
  // LPM
  // ---------------------------------------------------------------------------

  @Test
  fun `LPM selects the longest matching prefix`() {
    // /1: top bit set → matches 0b1xxx_xxxx (0x80–0xFF), action10
    write(lpmEntry(1, byteArrayOf(0x80.toByte()), prefixLen = 1, actionId = 10))
    // /2: top two bits = 11 → matches 0b11xx_xxxx (0xC0–0xFF), action20
    write(lpmEntry(1, byteArrayOf(0xC0.toByte()), prefixLen = 2, actionId = 20))

    // 0xC1 matches both /1 and /2; /2 is longer → action20
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xC1, 8)))
    assertTrue(result.hit)
    assertEquals("action20", result.actionName)
  }

  @Test
  fun `LPM falls back to shorter prefix when longer does not match`() {
    write(lpmEntry(1, byteArrayOf(0x80.toByte()), prefixLen = 1, actionId = 10))
    write(lpmEntry(1, byteArrayOf(0xC0.toByte()), prefixLen = 2, actionId = 20))

    // 0x81 matches /1 (top bit set) but not /2 (top two bits = 10) → action10
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0x81, 8)))
    assertTrue(result.hit)
    assertEquals("action10", result.actionName)
  }

  @Test
  fun `LPM miss when no prefix covers the lookup key`() {
    // Only covers 0b11xx_xxxx
    write(lpmEntry(1, byteArrayOf(0xC0.toByte()), prefixLen = 2, actionId = 20))
    // 0x01 (top bit clear) does not match
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(1, 8)))
    assertFalse(result.hit)
  }

  // ---------------------------------------------------------------------------
  // Ternary
  // ---------------------------------------------------------------------------

  @Test
  fun `ternary highest priority entry wins when both match`() {
    val ff = byteArrayOf(0xFF.toByte())
    // Both entries match 0xFF exactly (all-ones mask); higher priority wins
    write(ternaryEntry(1, value = ff, mask = ff, priority = 5, actionId = 100))
    write(ternaryEntry(1, value = ff, mask = ff, priority = 10, actionId = 200))

    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xFF, 8)))
    assertTrue(result.hit)
    assertEquals("action200", result.actionName)
  }

  @Test
  fun `ternary wildcard mask matches any value`() {
    // All-zeros mask → match anything (value && 0x00 == 0x00 && 0x00)
    write(
      ternaryEntry(
        1,
        value = byteArrayOf(0x00),
        mask = byteArrayOf(0x00),
        priority = 1,
        actionId = 50,
      )
    )
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xAB, 8)))
    assertTrue(result.hit)
    assertEquals("action50", result.actionName)
  }

  @Test
  fun `ternary miss when value does not match masked pattern`() {
    // Matches only if top nibble = 0xA (mask = 0xF0, value = 0xA0)
    write(
      ternaryEntry(
        1,
        value = byteArrayOf(0xA0.toByte()),
        mask = byteArrayOf(0xF0.toByte()),
        priority = 1,
        actionId = 77,
      )
    )
    // 0xB0: top nibble = 0xB ≠ 0xA → no match
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xB0, 8)))
    assertFalse(result.hit)
  }

  // ---------------------------------------------------------------------------
  // Range
  // ---------------------------------------------------------------------------

  @Test
  fun `range match hit when value is within bounds`() {
    write(
      rangeEntry(1, lo = byteArrayOf(0x10), hi = byteArrayOf(0x20), priority = 1, actionId = 10)
    )
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0x18, 8)))
    assertTrue(result.hit)
    assertEquals("action10", result.actionName)
  }

  @Test
  fun `range match hit on exact boundary values`() {
    write(
      rangeEntry(1, lo = byteArrayOf(0x10), hi = byteArrayOf(0x20), priority = 1, actionId = 10)
    )
    assertTrue(store.lookup(TABLE_NAME, listOf("1" to BitVal(0x10, 8))).hit)
    assertTrue(store.lookup(TABLE_NAME, listOf("1" to BitVal(0x20, 8))).hit)
  }

  @Test
  fun `range match miss when value is outside bounds`() {
    write(
      rangeEntry(1, lo = byteArrayOf(0x10), hi = byteArrayOf(0x20), priority = 1, actionId = 10)
    )
    assertFalse(store.lookup(TABLE_NAME, listOf("1" to BitVal(0x0F, 8))).hit)
    assertFalse(store.lookup(TABLE_NAME, listOf("1" to BitVal(0x21, 8))).hit)
  }

  // ---------------------------------------------------------------------------
  // Optional
  // ---------------------------------------------------------------------------

  @Test
  fun `optional match hit on exact value`() {
    write(optionalEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42))
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8)))
    assertTrue(result.hit)
    assertEquals("action42", result.actionName)
  }

  @Test
  fun `optional match miss on different value`() {
    write(optionalEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42))
    assertFalse(store.lookup(TABLE_NAME, listOf("1" to BitVal(11, 8))).hit)
  }

  @Test
  fun `optional wildcard matches any value when field is absent from entry`() {
    // Entry has no match fields → all keys are wildcarded
    val entry =
      TableEntry.newBuilder()
        .setTableId(TABLE_ID)
        .setPriority(1)
        .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(10)))
        .build()
    write(entry)
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xFF, 8)))
    assertTrue(result.hit)
  }

  // ---------------------------------------------------------------------------
  // Write operations
  // ---------------------------------------------------------------------------

  @Test
  fun `delete removes entry so subsequent lookup misses`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42)
    write(entry)

    assertTrue(store.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8))).hit)

    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.DELETE)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()
    )

    assertFalse(store.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8))).hit)
  }

  @Test
  fun `modify overwrites an existing entry`() {
    write(exactEntry(fieldId = 1, value = byteArrayOf(0x05), actionId = 10))

    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.MODIFY)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(exactEntry(fieldId = 1, value = byteArrayOf(0x05), actionId = 99))
        )
        .build()
    )

    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(5, 8)))
    assertTrue(result.hit)
    assertEquals("action99", result.actionName)
  }

  // P4Runtime spec §9.1: INSERT of a duplicate entry must return ALREADY_EXISTS.
  @Test
  fun `insert duplicate entry returns AlreadyExists`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42)
    write(entry)
    val result =
      store.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.INSERT)
          .setEntity(Entity.newBuilder().setTableEntry(entry))
          .build()
      )
    assertTrue("expected AlreadyExists", result is WriteResult.AlreadyExists)
  }

  // P4Runtime spec §9.1: MODIFY of a non-existent entry must return NotFound.
  @Test
  fun `modify non-existent entry returns NotFound`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42)
    val result =
      store.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(Entity.newBuilder().setTableEntry(entry))
          .build()
      )
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  // P4Runtime spec §9.1: DELETE of a non-existent entry must return NotFound.
  @Test
  fun `delete non-existent entry returns NotFound`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42)
    val result =
      store.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.DELETE)
          .setEntity(Entity.newBuilder().setTableEntry(entry))
          .build()
      )
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  @Test
  fun `write with unknown table ID returns NotFound`() {
    val entry = TableEntry.newBuilder().setTableId(99999).build()
    val result =
      store.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.INSERT)
          .setEntity(Entity.newBuilder().setTableEntry(entry))
          .build()
      )
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  // ---------------------------------------------------------------------------
  // Table capacity enforcement (P4Runtime spec §9.27)
  // ---------------------------------------------------------------------------

  @Test
  fun `insert into full table returns ResourceExhausted`() {
    val store = storeWithTableSize(TABLE_SIZE_LIMIT)
    // Fill the table to capacity.
    for (i in 0 until TABLE_SIZE_LIMIT) {
      assertEquals(
        WriteResult.Success,
        store.writeAndPublish(insertUpdate(exactEntry(1, byteArrayOf(i.toByte()), 10))),
      )
    }
    // One more INSERT should fail.
    val result = store.writeAndPublish(insertUpdate(exactEntry(1, byteArrayOf(0xFF.toByte()), 10)))
    assertTrue("expected ResourceExhausted", result is WriteResult.ResourceExhausted)
  }

  @Test
  fun `modify in full table succeeds`() {
    val store = storeWithTableSize(TABLE_SIZE_LIMIT)
    for (i in 0 until TABLE_SIZE_LIMIT) {
      store.writeAndPublish(insertUpdate(exactEntry(1, byteArrayOf(i.toByte()), 10)))
    }
    // MODIFY doesn't consume capacity — it replaces an existing entry.
    val result =
      store.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(Entity.newBuilder().setTableEntry(exactEntry(1, byteArrayOf(0x00), 99)))
          .build()
      )
    assertEquals(WriteResult.Success, result)
  }

  @Test
  fun `delete then insert in full table succeeds`() {
    val store = storeWithTableSize(TABLE_SIZE_LIMIT)
    val entry = exactEntry(1, byteArrayOf(0x01), 10)
    for (i in 0 until TABLE_SIZE_LIMIT) {
      store.writeAndPublish(insertUpdate(exactEntry(1, byteArrayOf(i.toByte()), 10)))
    }
    // Delete frees a slot.
    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.DELETE)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()
    )
    // Now INSERT should succeed.
    val result = store.writeAndPublish(insertUpdate(exactEntry(1, byteArrayOf(0xAA.toByte()), 10)))
    assertEquals(WriteResult.Success, result)
  }

  /** Creates a TableStore with a table that has a size limit. */
  private fun storeWithTableSize(size: Int): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(
          tables = listOf(p4infoTable(TABLE_ID, TABLE_NAME, size = size.toLong())),
          actions = ACTION_LIST,
        )
    )
    return store
  }

  // ---------------------------------------------------------------------------
  // Default entry writes (P4Runtime spec §9.1)
  // ---------------------------------------------------------------------------

  @Test
  fun `MODIFY default entry updates the default action`() {
    store.setDefaultActionAndPublish(TABLE_NAME, "drop")
    assertEquals("drop", store.lookup(TABLE_NAME, emptyList()).actionName)

    val result =
      store.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(
            Entity.newBuilder()
              .setTableEntry(
                TableEntry.newBuilder()
                  .setTableId(TABLE_ID)
                  .setIsDefaultAction(true)
                  .setAction(
                    P4RuntimeOuterClass.TableAction.newBuilder()
                      .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(42))
                  )
              )
          )
          .build()
      )
    assertEquals(WriteResult.Success, result)
    assertEquals("action42", store.lookup(TABLE_NAME, emptyList()).actionName)
  }

  @Test
  fun `MODIFY default entry with params preserves params`() {
    val param =
      P4RuntimeOuterClass.Action.Param.newBuilder()
        .setParamId(1)
        .setValue(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x09)))
        .build()
    val result =
      store.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(
            Entity.newBuilder()
              .setTableEntry(
                TableEntry.newBuilder()
                  .setTableId(TABLE_ID)
                  .setIsDefaultAction(true)
                  .setAction(
                    P4RuntimeOuterClass.TableAction.newBuilder()
                      .setAction(
                        P4RuntimeOuterClass.Action.newBuilder().setActionId(42).addParams(param)
                      )
                  )
              )
          )
          .build()
      )
    assertEquals(WriteResult.Success, result)
    val defaultAction = store.getDefaultAction(TABLE_NAME)!!
    assertEquals("action42", defaultAction.name)
    assertEquals(1, defaultAction.params.size)
  }

  @Test
  fun `isDefaultModified returns false before any Write`() {
    assertFalse(store.isDefaultModified(TABLE_NAME))
  }

  @Test
  fun `isDefaultModified returns true after MODIFY default entry`() {
    store.setDefaultActionAndPublish(TABLE_NAME, "drop")
    assertFalse("pipeline-loaded default is not 'modified'", store.isDefaultModified(TABLE_NAME))

    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.MODIFY)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(TABLE_ID)
                .setIsDefaultAction(true)
                .setAction(
                  P4RuntimeOuterClass.TableAction.newBuilder()
                    .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(42))
                )
            )
        )
        .build()
    )
    assertTrue("should be modified after Write", store.isDefaultModified(TABLE_NAME))
  }

  // ---------------------------------------------------------------------------
  // Action profiles
  // ---------------------------------------------------------------------------

  /** Creates a TableStore with an action-profile-backed table for profile tests. */
  private fun storeWithProfile(): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(
          tables =
            listOf(
              p4infoTable(PROFILE_TABLE_ID, PROFILE_TABLE_NAME, implementationId = PROFILE_ID)
            ),
          actions = ACTION_LIST,
        )
    )
    return store
  }

  private fun writeMember(
    store: TableStore,
    memberId: Int,
    actionId: Int,
    paramValue: Byte,
    type: Update.Type = Update.Type.INSERT,
  ): WriteResult =
    store.writeAndPublish(
      Update.newBuilder()
        .setType(type)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileMember(
              P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(PROFILE_ID)
                .setMemberId(memberId)
                .setAction(
                  Action.newBuilder()
                    .setActionId(actionId)
                    .addParams(
                      Action.Param.newBuilder()
                        .setParamId(1)
                        .setValue(ByteString.copyFrom(byteArrayOf(paramValue)))
                    )
                )
            )
        )
        .build()
    )

  private fun writeGroup(
    store: TableStore,
    groupId: Int,
    memberIds: List<Int>,
    type: Update.Type = Update.Type.INSERT,
  ): WriteResult =
    store.writeAndPublish(
      Update.newBuilder()
        .setType(type)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileGroup(
              P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                .setActionProfileId(PROFILE_ID)
                .setGroupId(groupId)
                .addAllMembers(
                  memberIds.map { mid ->
                    P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                      .setMemberId(mid)
                      .setWeight(1)
                      .build()
                  }
                )
            )
        )
        .build()
    )

  private fun writeGroupEntry(store: TableStore, fieldValue: Byte, groupId: Int) {
    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(PROFILE_TABLE_ID)
                .addMatch(
                  FieldMatch.newBuilder()
                    .setFieldId(1)
                    .setExact(
                      FieldMatch.Exact.newBuilder()
                        .setValue(ByteString.copyFrom(byteArrayOf(fieldValue)))
                    )
                )
                .setAction(TableAction.newBuilder().setActionProfileGroupId(groupId))
            )
        )
        .build()
    )
  }

  private fun writeMemberEntry(store: TableStore, fieldValue: Byte, memberId: Int) {
    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(PROFILE_TABLE_ID)
                .addMatch(
                  FieldMatch.newBuilder()
                    .setFieldId(1)
                    .setExact(
                      FieldMatch.Exact.newBuilder()
                        .setValue(ByteString.copyFrom(byteArrayOf(fieldValue)))
                    )
                )
                .setAction(TableAction.newBuilder().setActionProfileMemberId(memberId))
            )
        )
        .build()
    )
  }

  @Test
  fun `group entry lookup returns members for forking`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 0, actionId = 10, paramValue = 1)
    writeMember(s, memberId = 1, actionId = 20, paramValue = 2)
    writeGroup(s, groupId = 1, memberIds = listOf(0, 1))
    writeGroupEntry(s, fieldValue = 0x0A, groupId = 1)

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0A, 8)))
    assertTrue(result.hit)
    val members = checkNotNull(result.members)
    assertEquals(2, members.size)
    assertEquals(0, members[0].memberId)
    assertEquals("action10", members[0].actionName)
    assertEquals(1, members[1].memberId)
    assertEquals("action20", members[1].actionName)
  }

  @Test
  fun `direct member entry lookup returns no members`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 5, actionId = 42, paramValue = 0x7F)
    writeMemberEntry(s, fieldValue = 0x0B, memberId = 5)

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0B, 8)))
    assertTrue(result.hit)
    assertNull(result.members)
    assertEquals("action42", result.actionName)
  }

  @Test
  fun `group entry miss returns default action without members`() {
    val s = storeWithProfile()
    s.setDefaultActionAndPublish(PROFILE_TABLE_NAME, "drop")

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0xFF, 8)))
    assertFalse(result.hit)
    assertNull(result.members)
    assertEquals("drop", result.actionName)
  }

  @Test
  fun `group member params are preserved through lookup`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 0, actionId = 10, paramValue = 0x42)
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    writeGroupEntry(s, fieldValue = 0x0A, groupId = 1)

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0A, 8)))
    val params = checkNotNull(result.members)[0].params
    assertEquals(1, params.size)
    assertEquals(ByteString.copyFrom(byteArrayOf(0x42)), params[0].value)
  }

  // ---------------------------------------------------------------------------
  // One-shot action selector (§9.2.3)
  // ---------------------------------------------------------------------------

  private fun writeOneShotEntry(
    store: TableStore,
    fieldValue: Byte,
    actions: List<Pair<Int, Byte>>,
  ) {
    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(PROFILE_TABLE_ID)
                .addMatch(
                  FieldMatch.newBuilder()
                    .setFieldId(1)
                    .setExact(
                      FieldMatch.Exact.newBuilder()
                        .setValue(ByteString.copyFrom(byteArrayOf(fieldValue)))
                    )
                )
                .setAction(
                  TableAction.newBuilder()
                    .setActionProfileActionSet(
                      P4RuntimeOuterClass.ActionProfileActionSet.newBuilder()
                        .addAllActionProfileActions(
                          actions.map { (actionId, paramValue) ->
                            P4RuntimeOuterClass.ActionProfileAction.newBuilder()
                              .setAction(
                                Action.newBuilder()
                                  .setActionId(actionId)
                                  .addParams(
                                    Action.Param.newBuilder()
                                      .setParamId(1)
                                      .setValue(ByteString.copyFrom(byteArrayOf(paramValue)))
                                  )
                              )
                              .setWeight(1)
                              .build()
                          }
                        )
                    )
                )
            )
        )
        .build()
    )
  }

  @Test
  fun `one-shot entry lookup returns members for forking`() {
    val s = storeWithProfile()
    writeOneShotEntry(s, fieldValue = 0x0A, actions = listOf(10 to 0x01, 20 to 0x02))

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0A, 8)))
    assertTrue(result.hit)
    val members = checkNotNull(result.members)
    assertEquals(2, members.size)
    assertEquals("action10", members[0].actionName)
    assertEquals("action20", members[1].actionName)
  }

  @Test
  fun `one-shot entry with single action returns one member`() {
    val s = storeWithProfile()
    writeOneShotEntry(s, fieldValue = 0x0B, actions = listOf(42 to 0x7F))

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0B, 8)))
    assertTrue(result.hit)
    val members = checkNotNull(result.members)
    assertEquals(1, members.size)
    assertEquals("action42", members[0].actionName)
    val params = members[0].params
    assertEquals(1, params.size)
    assertEquals(ByteString.copyFrom(byteArrayOf(0x7F)), params[0].value)
  }

  @Test
  fun `one-shot member IDs are synthetic sequential indices`() {
    val s = storeWithProfile()
    writeOneShotEntry(s, fieldValue = 0x0C, actions = listOf(10 to 0x01, 20 to 0x02, 42 to 0x03))

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0C, 8)))
    val members = checkNotNull(result.members)
    assertEquals(3, members.size)
    assertEquals(0, members[0].memberId)
    assertEquals(1, members[1].memberId)
    assertEquals(2, members[2].memberId)
  }

  // ---------------------------------------------------------------------------
  // PRE: clone sessions and multicast groups
  // ---------------------------------------------------------------------------

  private fun writeCloneSession(
    target: TableStore = store,
    sessionId: Int,
    egressPort: Int,
    type: Update.Type = Update.Type.INSERT,
  ): WriteResult =
    target.writeAndPublish(
      Update.newBuilder()
        .setType(type)
        .setEntity(
          Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setCloneSessionEntry(
                  P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                    .setSessionId(sessionId)
                    .addReplicas(
                      P4RuntimeOuterClass.Replica.newBuilder().setPort(portToBytes(egressPort))
                    )
                )
            )
        )
        .build()
    )

  private fun writeMulticastGroup(
    target: TableStore = store,
    groupId: Int,
    replicas: List<Pair<Int, Int>>,
    type: Update.Type = Update.Type.INSERT,
  ): WriteResult =
    target.writeAndPublish(
      Update.newBuilder()
        .setType(type)
        .setEntity(
          Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setMulticastGroupEntry(
                  P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                    .setMulticastGroupId(groupId)
                    .addAllReplicas(
                      replicas.map { (rid, port) ->
                        P4RuntimeOuterClass.Replica.newBuilder()
                          .setInstance(rid)
                          .setPort(portToBytes(port))
                          .build()
                      }
                    )
                )
            )
        )
        .build()
    )

  @Test
  fun `clone session round-trip`() {
    writeCloneSession(sessionId = 100, egressPort = 5)
    val session = store.getCloneSession(100)
    assertNotNull(session)
    assertEquals(100, session!!.sessionId)
    assertEquals(5, replicaPort(session.replicasList[0]))
  }

  @Test
  fun `clone session miss returns null`() {
    assertNull(store.getCloneSession(999))
  }

  @Test
  fun `multicast group round-trip`() {
    writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1, 0 to 2, 0 to 3))
    val group = store.getMulticastGroup(1)
    assertNotNull(group)
    assertEquals(1, group!!.multicastGroupId)
    assertEquals(3, group.replicasCount)
    assertEquals(1, replicaPort(group.replicasList[0]))
    assertEquals(2, replicaPort(group.replicasList[1]))
    assertEquals(3, replicaPort(group.replicasList[2]))
  }

  @Test
  fun `multicast group miss returns null`() {
    assertNull(store.getMulticastGroup(999))
  }

  @Test
  fun `clone session INSERT duplicate fails`() {
    assertEquals(WriteResult.Success, writeCloneSession(sessionId = 1, egressPort = 5))
    assertTrue(writeCloneSession(sessionId = 1, egressPort = 6) is WriteResult.AlreadyExists)
  }

  @Test
  fun `clone session MODIFY existing succeeds`() {
    writeCloneSession(sessionId = 1, egressPort = 5)
    assertEquals(
      WriteResult.Success,
      writeCloneSession(sessionId = 1, egressPort = 9, type = Update.Type.MODIFY),
    )
    assertEquals(9, replicaPort(store.getCloneSession(1)!!.replicasList[0]))
  }

  @Test
  fun `clone session MODIFY non-existent fails`() {
    assertTrue(
      writeCloneSession(sessionId = 1, egressPort = 5, type = Update.Type.MODIFY)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `clone session DELETE existing succeeds`() {
    writeCloneSession(sessionId = 1, egressPort = 5)
    assertEquals(
      WriteResult.Success,
      writeCloneSession(sessionId = 1, egressPort = 0, type = Update.Type.DELETE),
    )
    assertNull(store.getCloneSession(1))
  }

  @Test
  fun `clone session DELETE non-existent fails`() {
    assertTrue(
      writeCloneSession(sessionId = 1, egressPort = 0, type = Update.Type.DELETE)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `multicast group INSERT duplicate fails`() {
    assertEquals(WriteResult.Success, writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1)))
    assertTrue(
      writeMulticastGroup(groupId = 1, replicas = listOf(0 to 2)) is WriteResult.AlreadyExists
    )
  }

  @Test
  fun `multicast group MODIFY existing succeeds`() {
    writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1))
    val result =
      writeMulticastGroup(groupId = 1, replicas = listOf(0 to 5, 0 to 6), type = Update.Type.MODIFY)
    assertEquals(WriteResult.Success, result)
    assertEquals(2, store.getMulticastGroup(1)!!.replicasCount)
  }

  @Test
  fun `multicast group MODIFY non-existent fails`() {
    assertTrue(
      writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1), type = Update.Type.MODIFY)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `multicast group DELETE existing succeeds`() {
    writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1))
    assertEquals(
      WriteResult.Success,
      writeMulticastGroup(groupId = 1, replicas = emptyList(), type = Update.Type.DELETE),
    )
    assertNull(store.getMulticastGroup(1))
  }

  @Test
  fun `multicast group DELETE non-existent fails`() {
    assertTrue(
      writeMulticastGroup(groupId = 1, replicas = emptyList(), type = Update.Type.DELETE)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `PRE wildcard read returns all entries`() {
    writeCloneSession(sessionId = 1, egressPort = 5)
    writeCloneSession(sessionId = 2, egressPort = 6)
    writeMulticastGroup(groupId = 10, replicas = listOf(0 to 1))
    val results = store.readPreEntries()
    assertEquals(3, results.size)
  }

  @Test
  fun `PRE read by clone session ID`() {
    writeCloneSession(sessionId = 1, egressPort = 5)
    writeCloneSession(sessionId = 2, egressPort = 6)
    val filter =
      P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
        .setCloneSessionEntry(P4RuntimeOuterClass.CloneSessionEntry.newBuilder().setSessionId(1))
        .build()
    val results = store.readPreEntries(filter)
    assertEquals(1, results.size)
    assertEquals(1, results[0].packetReplicationEngineEntry.cloneSessionEntry.sessionId)
  }

  @Test
  fun `PRE read by multicast group ID`() {
    writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1))
    writeMulticastGroup(groupId = 2, replicas = listOf(0 to 2))
    val filter =
      P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
        .setMulticastGroupEntry(
          P4RuntimeOuterClass.MulticastGroupEntry.newBuilder().setMulticastGroupId(2)
        )
        .build()
    val results = store.readPreEntries(filter)
    assertEquals(1, results.size)
    assertEquals(2, results[0].packetReplicationEngineEntry.multicastGroupEntry.multicastGroupId)
  }

  @Test
  fun `PRE read non-existent returns empty`() {
    val filter =
      P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
        .setCloneSessionEntry(P4RuntimeOuterClass.CloneSessionEntry.newBuilder().setSessionId(999))
        .build()
    assertEquals(0, store.readPreEntries(filter).size)
  }

  @Test
  fun `loadMappings clears PRE entries`() {
    writeCloneSession(sessionId = 1, egressPort = 5)
    writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1))

    store.loadMappings(p4info = BASE_P4INFO)

    assertNull(store.getCloneSession(1))
    assertNull(store.getMulticastGroup(1))
  }

  @Test
  fun `loadMappings clears default actions`() {
    store.setDefaultActionAndPublish(TABLE_NAME, "customAction")
    assertEquals("customAction", store.lookup(TABLE_NAME, emptyList()).actionName)

    // Reload without any default action configured — should revert to NoAction.
    store.loadMappings(p4info = BASE_P4INFO)

    assertEquals("NoAction", store.lookup(TABLE_NAME, emptyList()).actionName)
  }

  // ---------------------------------------------------------------------------
  // Action profile write semantics
  // ---------------------------------------------------------------------------

  @Test
  fun `insert duplicate member returns AlreadyExists`() {
    val s = storeWithProfile()
    assertEquals(WriteResult.Success, writeMember(s, memberId = 1, actionId = 10, paramValue = 1))
    assertTrue(
      writeMember(s, memberId = 1, actionId = 20, paramValue = 2) is WriteResult.AlreadyExists
    )
  }

  @Test
  fun `modify existing member succeeds`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    assertEquals(
      WriteResult.Success,
      writeMember(s, memberId = 1, actionId = 20, paramValue = 2, type = Update.Type.MODIFY),
    )
  }

  @Test
  fun `modify non-existent member returns NotFound`() {
    val s = storeWithProfile()
    assertTrue(
      writeMember(s, memberId = 99, actionId = 10, paramValue = 1, type = Update.Type.MODIFY)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `delete existing member succeeds`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    assertEquals(
      WriteResult.Success,
      writeMember(s, memberId = 1, actionId = 10, paramValue = 1, type = Update.Type.DELETE),
    )
  }

  @Test
  fun `delete non-existent member returns NotFound`() {
    val s = storeWithProfile()
    assertTrue(
      writeMember(s, memberId = 99, actionId = 10, paramValue = 1, type = Update.Type.DELETE)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `insert duplicate group returns AlreadyExists`() {
    val s = storeWithProfile()
    assertEquals(WriteResult.Success, writeGroup(s, groupId = 1, memberIds = listOf(0)))
    assertTrue(writeGroup(s, groupId = 1, memberIds = listOf(0)) is WriteResult.AlreadyExists)
  }

  @Test
  fun `modify existing group succeeds`() {
    val s = storeWithProfile()
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    assertEquals(
      WriteResult.Success,
      writeGroup(s, groupId = 1, memberIds = listOf(0, 1), type = Update.Type.MODIFY),
    )
  }

  @Test
  fun `modify non-existent group returns NotFound`() {
    val s = storeWithProfile()
    assertTrue(
      writeGroup(s, groupId = 99, memberIds = listOf(0), type = Update.Type.MODIFY)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `delete existing group succeeds`() {
    val s = storeWithProfile()
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    assertEquals(
      WriteResult.Success,
      writeGroup(s, groupId = 1, memberIds = listOf(0), type = Update.Type.DELETE),
    )
  }

  @Test
  fun `delete non-existent group returns NotFound`() {
    val s = storeWithProfile()
    assertTrue(
      writeGroup(s, groupId = 99, memberIds = listOf(0), type = Update.Type.DELETE)
        is WriteResult.NotFound
    )
  }

  // ---------------------------------------------------------------------------
  // Group max_size enforcement (P4Runtime spec §9.2)
  // ---------------------------------------------------------------------------

  @Test
  fun `group exceeding max_group_size returns ResourceExhausted`() {
    val s = storeWithProfileMaxGroupSize(MAX_GROUP_SIZE)
    val result = writeGroup(s, groupId = 1, memberIds = listOf(1, 2, 3))
    assertTrue("expected ResourceExhausted", result is WriteResult.ResourceExhausted)
  }

  @Test
  fun `group at max_group_size succeeds`() {
    val s = storeWithProfileMaxGroupSize(MAX_GROUP_SIZE)
    assertEquals(WriteResult.Success, writeGroup(s, groupId = 1, memberIds = listOf(1, 2)))
  }

  @Test
  fun `modify group exceeding max_group_size returns ResourceExhausted`() {
    val s = storeWithProfileMaxGroupSize(MAX_GROUP_SIZE)
    writeGroup(s, groupId = 1, memberIds = listOf(1))
    val result = writeGroup(s, groupId = 1, memberIds = listOf(1, 2, 3), type = Update.Type.MODIFY)
    assertTrue("expected ResourceExhausted", result is WriteResult.ResourceExhausted)
  }

  // ---------------------------------------------------------------------------
  // Profile size enforcement (P4Runtime spec §9.2)
  // ---------------------------------------------------------------------------

  @Test
  fun `member insert at profile size limit returns ResourceExhausted`() {
    val s = storeWithProfileSizeLimit(2)
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    writeMember(s, memberId = 2, actionId = 10, paramValue = 2)
    val result = writeMember(s, memberId = 3, actionId = 10, paramValue = 3)
    assertTrue("expected ResourceExhausted", result is WriteResult.ResourceExhausted)
  }

  @Test
  fun `member insert below profile size limit succeeds`() {
    val s = storeWithProfileSizeLimit(3)
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    assertEquals(WriteResult.Success, writeMember(s, memberId = 2, actionId = 10, paramValue = 2))
  }

  @Test
  fun `group insert at profile size limit returns ResourceExhausted`() {
    val s = storeWithProfileSizeLimit(1)
    writeGroup(s, groupId = 1, memberIds = listOf(1))
    val result = writeGroup(s, groupId = 2, memberIds = listOf(2))
    assertTrue("expected ResourceExhausted", result is WriteResult.ResourceExhausted)
  }

  @Test
  fun `mixed members and groups count toward profile size limit`() {
    val s = storeWithProfileSizeLimit(2)
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    writeGroup(s, groupId = 1, memberIds = listOf(1))
    // 2 entities (1 member + 1 group) = at capacity.
    val result = writeMember(s, memberId = 2, actionId = 10, paramValue = 2)
    assertTrue("expected ResourceExhausted", result is WriteResult.ResourceExhausted)
  }

  @Test
  fun `delete frees capacity for new inserts`() {
    val s = storeWithProfileSizeLimit(2)
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    writeMember(s, memberId = 2, actionId = 10, paramValue = 2)
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1, type = Update.Type.DELETE)
    assertEquals(WriteResult.Success, writeMember(s, memberId = 3, actionId = 10, paramValue = 3))
  }

  /** Creates a TableStore with an action profile configured via [configure]. */
  private fun storeWithProfileConfig(
    configure: P4InfoOuterClass.ActionProfile.Builder.() -> Unit
  ): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(
          tables =
            listOf(
              p4infoTable(PROFILE_TABLE_ID, PROFILE_TABLE_NAME, implementationId = PROFILE_ID)
            ),
          actions = ACTION_LIST,
          actionProfiles =
            listOf(
              P4InfoOuterClass.ActionProfile.newBuilder()
                .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(PROFILE_ID))
                .apply(configure)
                .build()
            ),
        )
    )
    return store
  }

  private fun storeWithProfileSizeLimit(size: Int) = storeWithProfileConfig {
    setSize(size.toLong())
  }

  private fun storeWithProfileMaxGroupSize(maxGroupSize: Int) = storeWithProfileConfig {
    setMaxGroupSize(maxGroupSize)
  }

  // ---------------------------------------------------------------------------
  // Action profile reads
  // ---------------------------------------------------------------------------

  @Test
  fun `readProfileMembers wildcard returns all members`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    writeMember(s, memberId = 2, actionId = 20, paramValue = 2)
    val results = s.readProfileMembers()
    assertEquals(2, results.size)
    assertTrue(results.all { it.hasActionProfileMember() })
  }

  @Test
  fun `readProfileMembers with profile filter returns only that profile`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    // Members from a different profile (id=999) should not be returned.
    s.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileMember(
              P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(999)
                .setMemberId(1)
                .setAction(Action.newBuilder().setActionId(10))
            )
        )
        .build()
    )
    val filter =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder().setActionProfileId(PROFILE_ID).build()
    val results = s.readProfileMembers(filter)
    assertEquals(1, results.size)
    assertEquals(PROFILE_ID, results[0].actionProfileMember.actionProfileId)
  }

  @Test
  fun `readProfileMembers with member filter returns single member`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    writeMember(s, memberId = 2, actionId = 20, paramValue = 2)
    val filter =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder()
        .setActionProfileId(PROFILE_ID)
        .setMemberId(1)
        .build()
    val results = s.readProfileMembers(filter)
    assertEquals(1, results.size)
    assertEquals(1, results[0].actionProfileMember.memberId)
  }

  @Test
  fun `readProfileMembers non-matching returns empty`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    val filter =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder()
        .setActionProfileId(PROFILE_ID)
        .setMemberId(99)
        .build()
    assertTrue(s.readProfileMembers(filter).isEmpty())
  }

  @Test
  fun `readProfileGroups wildcard returns all groups`() {
    val s = storeWithProfile()
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    writeGroup(s, groupId = 2, memberIds = listOf(0))
    val results = s.readProfileGroups()
    assertEquals(2, results.size)
    assertTrue(results.all { it.hasActionProfileGroup() })
  }

  @Test
  fun `readProfileGroups with group filter returns single group`() {
    val s = storeWithProfile()
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    writeGroup(s, groupId = 2, memberIds = listOf(0))
    val filter =
      P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
        .setActionProfileId(PROFILE_ID)
        .setGroupId(1)
        .build()
    val results = s.readProfileGroups(filter)
    assertEquals(1, results.size)
    assertEquals(1, results[0].actionProfileGroup.groupId)
  }

  // ---------------------------------------------------------------------------
  // Register write semantics
  // ---------------------------------------------------------------------------

  /** Creates a TableStore with register metadata for register tests. */
  private fun storeWithRegister(): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(
          registers =
            listOf(buildRegisterProto(REGISTER_ID, REGISTER_NAME, REGISTER_BITWIDTH, REGISTER_SIZE))
        )
    )
    return store
  }

  private fun buildRegisterProto(
    id: Int,
    name: String,
    bitwidth: Int,
    size: Int,
  ): P4InfoOuterClass.Register =
    P4InfoOuterClass.Register.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name))
      .setTypeSpec(
        p4.config.v1.P4Types.P4DataTypeSpec.newBuilder()
          .setBitstring(
            p4.config.v1.P4Types.P4BitstringLikeTypeSpec.newBuilder()
              .setBit(p4.config.v1.P4Types.P4BitTypeSpec.newBuilder().setBitwidth(bitwidth))
          )
      )
      .setSize(size)
      .build()

  private fun registerUpdate(
    type: Update.Type,
    registerId: Int = REGISTER_ID,
    index: Long = 0,
    value: Long = 0,
  ): Update {
    val entry =
      P4RuntimeOuterClass.RegisterEntry.newBuilder()
        .setRegisterId(registerId)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(index))
        .setData(
          p4.v1.P4DataOuterClass.P4Data.newBuilder()
            .setBitstring(ByteString.copyFrom(longToBytes(value, (REGISTER_BITWIDTH + 7) / 8)))
        )
        .build()
    return Update.newBuilder()
      .setType(type)
      .setEntity(Entity.newBuilder().setRegisterEntry(entry))
      .build()
  }

  private fun longToBytes(value: Long, byteLen: Int): ByteArray {
    val bytes = ByteArray(byteLen)
    for (i in 0 until byteLen) {
      bytes[byteLen - 1 - i] = (value shr (i * 8) and 0xFF).toByte()
    }
    return bytes
  }

  @Test
  fun `modify register entry persists value`() {
    val s = storeWithRegister()
    assertEquals(
      WriteResult.Success,
      s.writeAndPublish(registerUpdate(Update.Type.MODIFY, value = 42)),
    )
    val readBack = s.registerRead(REGISTER_NAME, 0)
    assertNotNull(readBack)
    assertEquals(BitVal(42, REGISTER_BITWIDTH), readBack)
  }

  @Test
  fun `modify register entry at out-of-bounds index returns error`() {
    val s = storeWithRegister()
    val result =
      s.writeAndPublish(registerUpdate(Update.Type.MODIFY, index = REGISTER_SIZE.toLong()))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `insert register entry returns InvalidArgument`() {
    val s = storeWithRegister()
    val result = s.writeAndPublish(registerUpdate(Update.Type.INSERT))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `delete register entry returns InvalidArgument`() {
    val s = storeWithRegister()
    val result = s.writeAndPublish(registerUpdate(Update.Type.DELETE))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `modify register with unknown ID returns NotFound`() {
    val s = storeWithRegister()
    val result = s.writeAndPublish(registerUpdate(Update.Type.MODIFY, registerId = 999))
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  // ---------------------------------------------------------------------------
  // Register reads
  // ---------------------------------------------------------------------------

  @Test
  fun `readRegisterEntries single index returns written value`() {
    val s = storeWithRegister()
    s.writeAndPublish(registerUpdate(Update.Type.MODIFY, index = 1, value = 0xABCD))
    val filter =
      P4RuntimeOuterClass.RegisterEntry.newBuilder()
        .setRegisterId(REGISTER_ID)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(1))
        .build()
    val results = s.readRegisterEntries(filter)
    assertEquals(1, results.size)
    val entry = results[0].registerEntry
    assertEquals(REGISTER_ID, entry.registerId)
    assertEquals(1, entry.index.index)
    assertEquals(
      ByteString.copyFrom(longToBytes(0xABCD, (REGISTER_BITWIDTH + 7) / 8)),
      entry.data.bitstring,
    )
  }

  @Test
  fun `readRegisterEntries all indices returns full array with defaults`() {
    val s = storeWithRegister()
    s.writeAndPublish(registerUpdate(Update.Type.MODIFY, index = 2, value = 99))
    val filter = P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(REGISTER_ID).build()
    val results = s.readRegisterEntries(filter)
    assertEquals(REGISTER_SIZE, results.size)
    // Index 2 has value 99, others default to 0.
    for (entity in results) {
      val entry = entity.registerEntry
      val expected = if (entry.index.index == 2L) 99L else 0L
      assertEquals(
        ByteString.copyFrom(longToBytes(expected, (REGISTER_BITWIDTH + 7) / 8)),
        entry.data.bitstring,
      )
    }
  }

  @Test
  fun `readRegisterEntries wildcard returns all registers`() {
    val s = TableStore()
    s.loadMappings(
      p4info =
        buildP4Info(
          registers =
            listOf(
              buildRegisterProto(REGISTER_ID, REGISTER_NAME, REGISTER_BITWIDTH, 2),
              buildRegisterProto(REGISTER_ID + 1, "otherRegister", 8, 1),
            )
        )
    )
    s.writeAndPublish(
      registerUpdate(Update.Type.MODIFY, registerId = REGISTER_ID, index = 0, value = 1)
    )
    val filter = P4RuntimeOuterClass.RegisterEntry.getDefaultInstance()
    val results = s.readRegisterEntries(filter)
    // 2 entries from first register + 1 from second = 3
    assertEquals(3, results.size)
  }

  @Test
  fun `readRegisterEntries unknown register returns empty`() {
    val s = storeWithRegister()
    val filter = P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(999).build()
    assertTrue(s.readRegisterEntries(filter).isEmpty())
  }

  @Test
  fun `captureRegisterSeeds records packet-start value for read registers`() {
    val s = storeWithRegister()
    s.registerWrite(REGISTER_NAME, 0, BitVal(42, REGISTER_BITWIDTH))

    val captured =
      s.captureRegisterSeeds {
        s.registerWrite(REGISTER_NAME, 0, BitVal(7, REGISTER_BITWIDTH))
        s.registerRead(REGISTER_NAME, 0)
      }

    assertEquals(BitVal(7, REGISTER_BITWIDTH), captured.result)
    assertEquals(
      listOf(RegisterSeedDependency(REGISTER_NAME, 0, BitVal(42, REGISTER_BITWIDTH))),
      captured.dependencies,
    )
  }

  @Test
  fun `captureRegisterSeeds ignores default-zero dependencies`() {
    val s = storeWithRegister()

    val captured = s.captureRegisterSeeds { s.registerRead(REGISTER_NAME, 0) }

    assertNull(captured.result)
    assertTrue(captured.dependencies.isEmpty())
  }

  @Test
  fun `buildRegisterEntity encodes register seed dependency`() {
    val s = storeWithRegister()
    val entity =
      s.buildRegisterEntity(
        RegisterSeedDependency(REGISTER_NAME, 2, BitVal(0xABCD, REGISTER_BITWIDTH))
      )

    assertTrue(entity.hasRegisterEntry())
    assertEquals(REGISTER_ID, entity.registerEntry.registerId)
    assertEquals(2, entity.registerEntry.index.index)
    assertEquals(
      ByteString.copyFrom(longToBytes(0xABCD, (REGISTER_BITWIDTH + 7) / 8)),
      entity.registerEntry.data.bitstring,
    )
  }

  // ---------------------------------------------------------------------------
  // Per-entry reads
  // ---------------------------------------------------------------------------

  @Test
  fun `getTableEntries returns inserted entries`() {
    val entry1 = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    val entry2 = exactEntry(fieldId = 1, value = byteArrayOf(200.toByte()), actionId = 20)
    store.writeAndPublish(insertUpdate(entry1))
    store.writeAndPublish(insertUpdate(entry2))

    val entries = store.getTableEntries(TABLE_NAME)
    assertEquals(2, entries.size)
    assertEquals(entry1.matchList, entries[0].matchList)
    assertEquals(entry2.matchList, entries[1].matchList)
  }

  @Test
  fun `insert rejects counter data for table without direct counter`() {
    val entry = withCounterData(exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertTrue(store.getTableEntries(TABLE_NAME).isEmpty())
  }

  @Test
  fun `insert rejects meter config for table without direct meter`() {
    val entry = withMeterConfig(exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertTrue(store.getTableEntries(TABLE_NAME).isEmpty())
  }

  @Test
  fun `insert rejects meter counter data for table without direct meter`() {
    val entry =
      withMeterCounterData(exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10))

    val result = store.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertTrue(store.getTableEntries(TABLE_NAME).isEmpty())
  }

  @Test
  fun `modify rejects counter data for table without direct counter`() {
    val original = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    store.writeAndPublish(insertUpdate(original))
    val modified = withCounterData(withAction(original, actionId = 20))

    val result = store.writeAndPublish(modifyUpdate(modified))

    val entries = store.getTableEntries(TABLE_NAME)
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertEquals(1, entries.size)
    assertEquals(10, entries[0].action.action.actionId)
  }

  @Test
  fun `modify rejects meter counter data for table without direct meter`() {
    val original = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    store.writeAndPublish(insertUpdate(original))
    val modified = withMeterCounterData(withAction(original, actionId = 20))

    val result = store.writeAndPublish(modifyUpdate(modified))

    val entries = store.getTableEntries(TABLE_NAME)
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertEquals(1, entries.size)
    assertEquals(10, entries[0].action.action.actionId)
  }

  @Test
  fun `delete rejects counter data for table without direct counter`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    store.writeAndPublish(insertUpdate(entry))

    val result = store.writeAndPublish(deleteUpdate(withCounterData(entry)))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `delete rejects meter config for table without direct meter`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    store.writeAndPublish(insertUpdate(entry))

    val result = store.writeAndPublish(deleteUpdate(withMeterConfig(entry)))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `delete rejects meter counter data for table without direct meter`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    store.writeAndPublish(insertUpdate(entry))

    val result = store.writeAndPublish(deleteUpdate(withMeterCounterData(entry)))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `getTableEntries returns empty for unknown table`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    store.writeAndPublish(insertUpdate(entry))
    assertTrue(store.getTableEntries("nonexistent").isEmpty())
  }

  @Test
  fun `getTableEntries stores ternary entries with different priorities`() {
    val entry1 =
      ternaryEntry(
        fieldId = 1,
        value = byteArrayOf(0x0A),
        mask = byteArrayOf(0xFF.toByte()),
        priority = 10,
        actionId = 10,
      )
    val entry2 =
      ternaryEntry(
        fieldId = 1,
        value = byteArrayOf(0x0A),
        mask = byteArrayOf(0xFF.toByte()),
        priority = 20,
        actionId = 20,
      )
    store.writeAndPublish(insertUpdate(entry1))
    store.writeAndPublish(insertUpdate(entry2))

    val entries = store.getTableEntries(TABLE_NAME)
    assertEquals(2, entries.size)
    assertEquals(setOf(10, 20), entries.map { it.priority }.toSet())
  }

  @Test
  fun `getTableEntries returns entries per table`() {
    store.loadMappings(
      p4info =
        buildP4Info(
          tables = listOf(p4infoTable(TABLE_ID, TABLE_NAME), p4infoTable(3, "otherTable")),
          actions = ACTION_LIST,
        )
    )

    val entry1 = exactEntry(fieldId = 1, value = byteArrayOf(1), actionId = 10)
    val entry2 =
      TableEntry.newBuilder()
        .setTableId(3)
        .addMatch(
          FieldMatch.newBuilder()
            .setFieldId(1)
            .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(2))))
        )
        .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(10)))
        .build()
    store.writeAndPublish(insertUpdate(entry1))
    store.writeAndPublish(insertUpdate(entry2))

    assertEquals(1, store.getTableEntries(TABLE_NAME).size)
    assertEquals(1, store.getTableEntries("otherTable").size)
  }

  private fun insertUpdate(entry: TableEntry): Update =
    Update.newBuilder()
      .setType(Update.Type.INSERT)
      .setEntity(Entity.newBuilder().setTableEntry(entry))
      .build()

  private fun modifyUpdate(entry: TableEntry): Update =
    Update.newBuilder()
      .setType(Update.Type.MODIFY)
      .setEntity(Entity.newBuilder().setTableEntry(entry))
      .build()

  private fun deleteUpdate(entry: TableEntry): Update =
    Update.newBuilder()
      .setType(Update.Type.DELETE)
      .setEntity(Entity.newBuilder().setTableEntry(entry))
      .build()

  // ---------------------------------------------------------------------------
  // Counter write semantics
  // ---------------------------------------------------------------------------

  private fun storeWithCounter(): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(counters = listOf(buildCounterProto(COUNTER_ID, "myCounter", COUNTER_SIZE)))
    )
    return store
  }

  private fun buildCounterProto(id: Int, name: String, size: Int): P4InfoOuterClass.Counter =
    P4InfoOuterClass.Counter.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name))
      .setSize(size.toLong())
      .build()

  private fun counterUpdate(
    type: Update.Type,
    counterId: Int = COUNTER_ID,
    index: Long = 0,
    byteCount: Long = 0,
    packetCount: Long = 0,
  ): Update {
    val entry =
      P4RuntimeOuterClass.CounterEntry.newBuilder()
        .setCounterId(counterId)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(index))
        .setData(
          P4RuntimeOuterClass.CounterData.newBuilder()
            .setByteCount(byteCount)
            .setPacketCount(packetCount)
        )
        .build()
    return Update.newBuilder()
      .setType(type)
      .setEntity(Entity.newBuilder().setCounterEntry(entry))
      .build()
  }

  @Test
  fun `modify counter entry persists value`() {
    val s = storeWithCounter()
    assertEquals(
      WriteResult.Success,
      s.writeAndPublish(counterUpdate(Update.Type.MODIFY, byteCount = 100, packetCount = 5)),
    )
    val results =
      s.readCounterEntries(
        P4RuntimeOuterClass.CounterEntry.newBuilder()
          .setCounterId(COUNTER_ID)
          .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(0))
          .build()
      )
    assertEquals(1, results.size)
    assertEquals(100, results[0].counterEntry.data.byteCount)
    assertEquals(5, results[0].counterEntry.data.packetCount)
  }

  @Test
  fun `modify counter at out-of-bounds index returns error`() {
    val s = storeWithCounter()
    val result = s.writeAndPublish(counterUpdate(Update.Type.MODIFY, index = COUNTER_SIZE.toLong()))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `insert counter entry returns InvalidArgument`() {
    val s = storeWithCounter()
    val result = s.writeAndPublish(counterUpdate(Update.Type.INSERT))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `delete counter entry returns InvalidArgument`() {
    val s = storeWithCounter()
    val result = s.writeAndPublish(counterUpdate(Update.Type.DELETE))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `modify counter overwrites previous value`() {
    val s = storeWithCounter()
    s.writeAndPublish(counterUpdate(Update.Type.MODIFY, byteCount = 10, packetCount = 1))
    s.writeAndPublish(counterUpdate(Update.Type.MODIFY, byteCount = 20, packetCount = 2))
    val filter =
      P4RuntimeOuterClass.CounterEntry.newBuilder()
        .setCounterId(COUNTER_ID)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(0))
        .build()
    val results = s.readCounterEntries(filter)
    assertEquals(20, results[0].counterEntry.data.byteCount)
    assertEquals(2, results[0].counterEntry.data.packetCount)
  }

  @Test
  fun `modify counter with unknown ID returns NotFound`() {
    val s = storeWithCounter()
    val result = s.writeAndPublish(counterUpdate(Update.Type.MODIFY, counterId = 999))
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  @Test
  fun `counterIncrement does not publish uncommitted control-plane writes`() {
    val s = TableStore()
    s.loadMappings(
      p4info =
        buildP4Info(
          tables = listOf(p4infoTable(TABLE_ID, TABLE_NAME)),
          actions = ACTION_LIST,
          counters = listOf(buildCounterProto(COUNTER_ID, "myCounter", COUNTER_SIZE)),
        )
    )
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)

    assertEquals(WriteResult.Success, s.write(insertUpdate(entry)))
    s.counterIncrement("myCounter", index = 0, byteCount = 7)

    assertFalse(s.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8))).hit)
    val counterFilter =
      P4RuntimeOuterClass.CounterEntry.newBuilder()
        .setCounterId(COUNTER_ID)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(0))
        .build()
    val counterData = s.readCounterEntries(counterFilter).single().counterEntry.data
    assertEquals(1, counterData.packetCount)
    assertEquals(7, counterData.byteCount)

    s.publishSnapshot()

    assertTrue(s.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8))).hit)
  }

  // ---------------------------------------------------------------------------
  // Counter reads
  // ---------------------------------------------------------------------------

  @Test
  fun `readCounterEntries single index returns written value`() {
    val s = storeWithCounter()
    s.writeAndPublish(counterUpdate(Update.Type.MODIFY, index = 1, byteCount = 42, packetCount = 3))
    val filter =
      P4RuntimeOuterClass.CounterEntry.newBuilder()
        .setCounterId(COUNTER_ID)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(1))
        .build()
    val results = s.readCounterEntries(filter)
    assertEquals(1, results.size)
    assertEquals(42, results[0].counterEntry.data.byteCount)
    assertEquals(3, results[0].counterEntry.data.packetCount)
  }

  @Test
  fun `readCounterEntries all indices returns full array with defaults`() {
    val s = storeWithCounter()
    s.writeAndPublish(counterUpdate(Update.Type.MODIFY, index = 2, byteCount = 99))
    val filter = P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(COUNTER_ID).build()
    val results = s.readCounterEntries(filter)
    assertEquals(COUNTER_SIZE, results.size)
    for (entity in results) {
      val entry = entity.counterEntry
      val expectedBytes = if (entry.index.index == 2L) 99L else 0L
      assertEquals(expectedBytes, entry.data.byteCount)
    }
  }

  @Test
  fun `readCounterEntries wildcard returns all counters`() {
    val s = TableStore()
    s.loadMappings(
      p4info =
        buildP4Info(
          counters =
            listOf(
              buildCounterProto(COUNTER_ID, "counter1", 2),
              buildCounterProto(COUNTER_ID + 1, "counter2", 1),
            )
        )
    )
    val results = s.readCounterEntries()
    // 2 entries from first counter + 1 from second = 3
    assertEquals(3, results.size)
  }

  @Test
  fun `readCounterEntries unknown counter returns empty`() {
    val s = storeWithCounter()
    val filter = P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(999).build()
    assertTrue(s.readCounterEntries(filter).isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Meter write semantics
  // ---------------------------------------------------------------------------

  private fun storeWithMeter(): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info = buildP4Info(meters = listOf(buildMeterProto(METER_ID, "myMeter", METER_SIZE)))
    )
    return store
  }

  private fun buildMeterProto(id: Int, name: String, size: Int): P4InfoOuterClass.Meter =
    P4InfoOuterClass.Meter.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name))
      .setSize(size.toLong())
      .build()

  private fun meterUpdate(
    type: Update.Type,
    meterId: Int = METER_ID,
    index: Long = 0,
    cir: Long = 0,
    cburst: Long = 0,
    pir: Long = 0,
    pburst: Long = 0,
  ): Update {
    val entry =
      P4RuntimeOuterClass.MeterEntry.newBuilder()
        .setMeterId(meterId)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(index))
        .setConfig(
          P4RuntimeOuterClass.MeterConfig.newBuilder()
            .setCir(cir)
            .setCburst(cburst)
            .setPir(pir)
            .setPburst(pburst)
        )
        .build()
    return Update.newBuilder()
      .setType(type)
      .setEntity(Entity.newBuilder().setMeterEntry(entry))
      .build()
  }

  @Test
  fun `modify meter entry persists config`() {
    val s = storeWithMeter()
    assertEquals(
      WriteResult.Success,
      s.writeAndPublish(
        meterUpdate(Update.Type.MODIFY, cir = 1000, cburst = 100, pir = 2000, pburst = 200)
      ),
    )
    val results =
      s.readMeterEntries(
        P4RuntimeOuterClass.MeterEntry.newBuilder()
          .setMeterId(METER_ID)
          .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(0))
          .build()
      )
    assertEquals(1, results.size)
    val config = results[0].meterEntry.config
    assertEquals(1000, config.cir)
    assertEquals(100, config.cburst)
    assertEquals(2000, config.pir)
    assertEquals(200, config.pburst)
  }

  @Test
  fun `modify meter at out-of-bounds index returns error`() {
    val s = storeWithMeter()
    val result = s.writeAndPublish(meterUpdate(Update.Type.MODIFY, index = METER_SIZE.toLong()))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `insert meter entry returns InvalidArgument`() {
    val s = storeWithMeter()
    val result = s.writeAndPublish(meterUpdate(Update.Type.INSERT))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `delete meter entry returns InvalidArgument`() {
    val s = storeWithMeter()
    val result = s.writeAndPublish(meterUpdate(Update.Type.DELETE))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `modify meter overwrites previous config`() {
    val s = storeWithMeter()
    s.writeAndPublish(meterUpdate(Update.Type.MODIFY, cir = 100))
    s.writeAndPublish(meterUpdate(Update.Type.MODIFY, cir = 200))
    val filter =
      P4RuntimeOuterClass.MeterEntry.newBuilder()
        .setMeterId(METER_ID)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(0))
        .build()
    val results = s.readMeterEntries(filter)
    assertEquals(200, results[0].meterEntry.config.cir)
  }

  @Test
  fun `modify meter with unknown ID returns NotFound`() {
    val s = storeWithMeter()
    val result = s.writeAndPublish(meterUpdate(Update.Type.MODIFY, meterId = 999))
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  // ---------------------------------------------------------------------------
  // Meter reads
  // ---------------------------------------------------------------------------

  @Test
  fun `readMeterEntries single index returns written config`() {
    val s = storeWithMeter()
    s.writeAndPublish(meterUpdate(Update.Type.MODIFY, index = 1, cir = 500, pir = 1000))
    val filter =
      P4RuntimeOuterClass.MeterEntry.newBuilder()
        .setMeterId(METER_ID)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(1))
        .build()
    val results = s.readMeterEntries(filter)
    assertEquals(1, results.size)
    assertEquals(500, results[0].meterEntry.config.cir)
    assertEquals(1000, results[0].meterEntry.config.pir)
  }

  @Test
  fun `readMeterEntries all indices returns full array`() {
    val s = storeWithMeter()
    s.writeAndPublish(meterUpdate(Update.Type.MODIFY, index = 2, cir = 42))
    val filter = P4RuntimeOuterClass.MeterEntry.newBuilder().setMeterId(METER_ID).build()
    val results = s.readMeterEntries(filter)
    assertEquals(METER_SIZE, results.size)
    // Index 2 has config, others have no config set.
    for (entity in results) {
      val entry = entity.meterEntry
      if (entry.index.index == 2L) {
        assertEquals(42, entry.config.cir)
      } else {
        assertFalse("unwritten meter should have no config", entry.hasConfig())
      }
    }
  }

  @Test
  fun `readMeterEntries wildcard returns all meters`() {
    val s = TableStore()
    s.loadMappings(
      p4info =
        buildP4Info(
          meters =
            listOf(
              buildMeterProto(METER_ID, "meter1", 2),
              buildMeterProto(METER_ID + 1, "meter2", 1),
            )
        )
    )
    val results = s.readMeterEntries()
    // 2 entries from first meter + 1 from second = 3
    assertEquals(3, results.size)
  }

  @Test
  fun `readMeterEntries unknown meter returns empty`() {
    val s = storeWithMeter()
    val filter = P4RuntimeOuterClass.MeterEntry.newBuilder().setMeterId(999).build()
    assertTrue(s.readMeterEntries(filter).isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Direct counters
  // ---------------------------------------------------------------------------

  /** Creates a TableStore with a table that has a direct counter attached. */
  private fun storeWithDirectCounter(): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(
          tables = listOf(p4infoTable(TABLE_ID, TABLE_NAME)),
          actions = ACTION_LIST,
          directCounters =
            listOf(
              P4InfoOuterClass.DirectCounter.newBuilder()
                .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(DIRECT_COUNTER_ID))
                .setDirectTableId(TABLE_ID)
                .build()
            ),
        )
    )
    return store
  }

  private fun storeWithActionAwareDirectCounter(): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(
          tables = listOf(p4infoTable(TABLE_ID, TABLE_NAME)),
          actions = ACTION_LIST,
          directCounters =
            listOf(
              P4InfoOuterClass.DirectCounter.newBuilder()
                .setPreamble(
                  P4InfoOuterClass.Preamble.newBuilder()
                    .setId(DIRECT_COUNTER_ID)
                    .setName("dc")
                    .setAlias("dc")
                )
                .setDirectTableId(TABLE_ID)
                .build()
            ),
        ),
      device =
        deviceWithActions(
          actionDecl("action10", directResourceCall("dc", "direct_counter", "count")),
          actionDecl("action20"),
        ),
    )
    return store
  }

  @Test
  fun `directCounterIncrement accumulates packet and byte counts`() {
    val s = storeWithDirectCounter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    s.directCounterIncrement(TABLE_NAME, entry, 100)
    s.directCounterIncrement(TABLE_NAME, entry, 200)

    val results =
      s.readDirectCounterEntries(
        P4RuntimeOuterClass.DirectCounterEntry.newBuilder().setTableEntry(entry).build()
      )
    assertEquals(1, results.size)
    val data = results[0].directCounterEntry.data
    assertEquals(2, data.packetCount)
    assertEquals(300, data.byteCount)
  }

  @Test
  fun `directCounterIncrement is no-op for table without direct counter`() {
    // Default store has no direct counter configured.
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    store.writeAndPublish(insertUpdate(entry))
    store.directCounterIncrement(TABLE_NAME, entry, 100)
    // Should not crash; reading returns empty since no direct counter is configured.
    val results = store.readDirectCounterEntries()
    assertTrue(results.isEmpty())
  }

  @Test
  fun `readDirectCounterEntries returns zero for unincremented entries`() {
    val s = storeWithDirectCounter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    val results =
      s.readDirectCounterEntries(
        P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
          .setTableEntry(TableEntry.newBuilder().setTableId(TABLE_ID))
          .build()
      )
    assertEquals(1, results.size)
    assertEquals(0, results[0].directCounterEntry.data.packetCount)
    assertEquals(0, results[0].directCounterEntry.data.byteCount)
  }

  @Test
  fun `readDirectCounterEntries wildcard returns all entries`() {
    val s = storeWithDirectCounter()
    val entry1 = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    val entry2 = exactEntry(fieldId = 1, value = byteArrayOf(20), actionId = 20)
    s.writeAndPublish(insertUpdate(entry1))
    s.writeAndPublish(insertUpdate(entry2))
    s.directCounterIncrement(TABLE_NAME, entry1, 50)

    val results = s.readDirectCounterEntries()
    assertEquals(2, results.size)
  }

  @Test
  fun `table entry INSERT with counter data initializes direct counter`() {
    val s = storeWithDirectCounter()
    val entry = withCounterData(exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10))

    val result = s.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    val stored = s.getTableEntries(TABLE_NAME).single()
    assertFalse(stored.hasCounterData())
    val readBack =
      s.readDirectCounterEntries(
        P4RuntimeOuterClass.DirectCounterEntry.newBuilder().setTableEntry(stored).build()
      )
    assertEquals(42, readBack[0].directCounterEntry.data.packetCount)
    assertEquals(1000, readBack[0].directCounterEntry.data.byteCount)
  }

  @Test
  fun `table entry INSERT with counter data rejects action that does not execute direct counter`() {
    val s = storeWithActionAwareDirectCounter()
    val entry = withCounterData(exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 20))

    val result = s.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertTrue(s.getTableEntries(TABLE_NAME).isEmpty())
  }

  @Test
  fun `table entry INSERT with counter data accepts action that executes direct counter`() {
    val s = storeWithActionAwareDirectCounter()
    val entry = withCounterData(exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10))

    val result = s.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    assertEquals(1, s.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `table entry MODIFY with counter data updates direct counter`() {
    val s = storeWithDirectCounter()
    val original = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(original))
    s.directCounterIncrement(TABLE_NAME, original, 100)
    val modified = withCounterData(withAction(original, actionId = 20))

    val result = s.writeAndPublish(modifyUpdate(modified))

    assertEquals(WriteResult.Success, result)
    val stored = s.getTableEntries(TABLE_NAME).single()
    assertEquals(20, stored.action.action.actionId)
    assertFalse(stored.hasCounterData())
    val readBack =
      s.readDirectCounterEntries(
        P4RuntimeOuterClass.DirectCounterEntry.newBuilder().setTableEntry(stored).build()
      )
    assertEquals(42, readBack[0].directCounterEntry.data.packetCount)
    assertEquals(1000, readBack[0].directCounterEntry.data.byteCount)
  }

  @Test
  fun `writeDirectCounterEntry MODIFY updates counter data`() {
    val s = storeWithDirectCounter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    val directCounterEntry =
      P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
        .setTableEntry(entry)
        .setData(P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(42).setByteCount(1000))
        .build()
    val result =
      s.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(Entity.newBuilder().setDirectCounterEntry(directCounterEntry))
          .build()
      )
    assertEquals(WriteResult.Success, result)

    val readBack =
      s.readDirectCounterEntries(
        P4RuntimeOuterClass.DirectCounterEntry.newBuilder().setTableEntry(entry).build()
      )
    assertEquals(42, readBack[0].directCounterEntry.data.packetCount)
    assertEquals(1000, readBack[0].directCounterEntry.data.byteCount)
  }

  @Test
  fun `writeDirectCounterEntry INSERT returns InvalidArgument`() {
    val s = storeWithDirectCounter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    val directCounterEntry =
      P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
        .setTableEntry(entry)
        .setData(P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(1))
        .build()
    val result =
      s.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.INSERT)
          .setEntity(Entity.newBuilder().setDirectCounterEntry(directCounterEntry))
          .build()
      )
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `writeDirectCounterEntry for non-existent table entry returns NotFound`() {
    val s = storeWithDirectCounter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    // Don't insert the entry into the table.

    val directCounterEntry =
      P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
        .setTableEntry(entry)
        .setData(P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(1))
        .build()
    val result =
      s.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(Entity.newBuilder().setDirectCounterEntry(directCounterEntry))
          .build()
      )
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  @Test
  fun `deleting table entry clears direct counter data`() {
    val s = storeWithDirectCounter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))
    s.directCounterIncrement(TABLE_NAME, entry, 100)

    // Delete the table entry.
    s.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.DELETE)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()
    )

    // Re-insert and verify counter is reset to zero.
    s.writeAndPublish(insertUpdate(entry))
    val results =
      s.readDirectCounterEntries(
        P4RuntimeOuterClass.DirectCounterEntry.newBuilder().setTableEntry(entry).build()
      )
    assertEquals(0, results[0].directCounterEntry.data.packetCount)
  }

  // ---------------------------------------------------------------------------
  // Direct meters
  // ---------------------------------------------------------------------------

  /** Creates a TableStore with a table that has a direct meter attached. */
  private fun storeWithDirectMeter(): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(
          tables = listOf(p4infoTable(TABLE_ID, TABLE_NAME)),
          actions = ACTION_LIST,
          directMeters =
            listOf(
              P4InfoOuterClass.DirectMeter.newBuilder()
                .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(DIRECT_METER_ID))
                .setDirectTableId(TABLE_ID)
                .build()
            ),
        )
    )
    return store
  }

  private fun storeWithActionAwareDirectMeter(): TableStore {
    val store = TableStore()
    store.loadMappings(
      p4info =
        buildP4Info(
          tables = listOf(p4infoTable(TABLE_ID, TABLE_NAME)),
          actions = ACTION_LIST,
          directMeters =
            listOf(
              P4InfoOuterClass.DirectMeter.newBuilder()
                .setPreamble(
                  P4InfoOuterClass.Preamble.newBuilder()
                    .setId(DIRECT_METER_ID)
                    .setName("dm")
                    .setAlias("dm")
                )
                .setDirectTableId(TABLE_ID)
                .build()
            ),
        ),
      device =
        deviceWithActions(
          actionDecl("action10", directResourceCall("dm", "direct_meter", "read")),
          actionDecl("action20"),
        ),
    )
    return store
  }

  @Test
  fun `writeDirectMeterEntry MODIFY persists config`() {
    val s = storeWithDirectMeter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    val directMeterEntry =
      P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
        .setTableEntry(entry)
        .setConfig(
          P4RuntimeOuterClass.MeterConfig.newBuilder()
            .setCir(1000)
            .setCburst(100)
            .setPir(2000)
            .setPburst(200)
        )
        .build()
    val result =
      s.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(Entity.newBuilder().setDirectMeterEntry(directMeterEntry))
          .build()
      )
    assertEquals(WriteResult.Success, result)

    val readBack =
      s.readDirectMeterEntries(
        P4RuntimeOuterClass.DirectMeterEntry.newBuilder().setTableEntry(entry).build()
      )
    assertEquals(1, readBack.size)
    val config = readBack[0].directMeterEntry.config
    assertEquals(1000, config.cir)
    assertEquals(2000, config.pir)
  }

  @Test
  fun `writeDirectMeterEntry INSERT returns InvalidArgument`() {
    val s = storeWithDirectMeter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    val directMeterEntry =
      P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
        .setTableEntry(entry)
        .setConfig(P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(100))
        .build()
    val result =
      s.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.INSERT)
          .setEntity(Entity.newBuilder().setDirectMeterEntry(directMeterEntry))
          .build()
      )
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `writeDirectMeterEntry for non-existent table entry returns NotFound`() {
    val s = storeWithDirectMeter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)

    val directMeterEntry =
      P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
        .setTableEntry(entry)
        .setConfig(P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(100))
        .build()
    val result =
      s.writeAndPublish(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(Entity.newBuilder().setDirectMeterEntry(directMeterEntry))
          .build()
      )
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  @Test
  fun `readDirectMeterEntries returns no config for unconfigured entries`() {
    val s = storeWithDirectMeter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    val results =
      s.readDirectMeterEntries(
        P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
          .setTableEntry(TableEntry.newBuilder().setTableId(TABLE_ID))
          .build()
      )
    assertEquals(1, results.size)
    assertFalse(
      "unconfigured direct meter should have no config",
      results[0].directMeterEntry.hasConfig(),
    )
  }

  @Test
  fun `table entry INSERT with meter config initializes direct meter`() {
    val s = storeWithDirectMeter()
    val entry = withMeterConfig(exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10))

    val result = s.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    val stored = s.getTableEntries(TABLE_NAME).single()
    assertFalse(stored.hasMeterConfig())
    val readBack =
      s.readDirectMeterEntries(
        P4RuntimeOuterClass.DirectMeterEntry.newBuilder().setTableEntry(stored).build()
      )
    assertEquals(1000, readBack[0].directMeterEntry.config.cir)
    assertEquals(100, readBack[0].directMeterEntry.config.cburst)
  }

  @Test
  fun `table entry INSERT with meter config rejects action that does not execute direct meter`() {
    val s = storeWithActionAwareDirectMeter()
    val entry = withMeterConfig(exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 20))

    val result = s.writeAndPublish(insertUpdate(entry))

    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
    assertTrue(s.getTableEntries(TABLE_NAME).isEmpty())
  }

  @Test
  fun `table entry INSERT with meter config accepts action that executes direct meter`() {
    val s = storeWithActionAwareDirectMeter()
    val entry = withMeterConfig(exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10))

    val result = s.writeAndPublish(insertUpdate(entry))

    assertEquals(WriteResult.Success, result)
    assertEquals(1, s.getTableEntries(TABLE_NAME).size)
  }

  @Test
  fun `table entry MODIFY without meter config resets direct meter`() {
    val s = storeWithDirectMeter()
    val original = withMeterConfig(exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10))
    s.writeAndPublish(insertUpdate(original))
    val modified = withAction(original, actionId = 20).toBuilder().clearMeterConfig().build()

    val result = s.writeAndPublish(modifyUpdate(modified))

    assertEquals(WriteResult.Success, result)
    val stored = s.getTableEntries(TABLE_NAME).single()
    assertEquals(20, stored.action.action.actionId)
    assertFalse(stored.hasMeterConfig())
    val readBack =
      s.readDirectMeterEntries(
        P4RuntimeOuterClass.DirectMeterEntry.newBuilder().setTableEntry(stored).build()
      )
    assertFalse("direct meter should reset to default", readBack[0].directMeterEntry.hasConfig())
  }

  @Test
  fun `deleting table entry clears direct meter data`() {
    val s = storeWithDirectMeter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    // Configure the direct meter.
    val directMeterEntry =
      P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
        .setTableEntry(entry)
        .setConfig(P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(100))
        .build()
    s.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.MODIFY)
        .setEntity(Entity.newBuilder().setDirectMeterEntry(directMeterEntry))
        .build()
    )

    // Delete and re-insert the table entry.
    s.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.DELETE)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()
    )
    s.writeAndPublish(insertUpdate(entry))

    // Direct meter config should be cleared.
    val results =
      s.readDirectMeterEntries(
        P4RuntimeOuterClass.DirectMeterEntry.newBuilder().setTableEntry(entry).build()
      )
    assertFalse(
      "should have no config after delete+re-insert",
      results[0].directMeterEntry.hasConfig(),
    )
  }

  // ---------------------------------------------------------------------------
  // Snapshot / Restore round-trip
  // ---------------------------------------------------------------------------

  /**
   * Populates every WriteState field, snapshots, mutates everything, restores, and verifies.
   *
   * This is the safety net for WriteState.deepCopy()/restoreFrom(): if a new field is added to
   * WriteState but not copied, this test will fail. Each field is checked independently so the
   * failure message pinpoints exactly which field was missed.
   */
  @Test
  fun `snapshot and restore round-trips all WriteState fields`() {
    // Build a p4info with every entity type: table, register, counter, meter, action profile,
    // direct counter, direct meter. This populates the ID→name mappings that write() needs.
    val s = TableStore()
    s.loadMappings(
      p4info =
        buildP4Info(
          tables =
            listOf(
              p4infoTable(TABLE_ID, TABLE_NAME),
              p4infoTable(PROFILE_TABLE_ID, PROFILE_TABLE_NAME, implementationId = PROFILE_ID),
            ),
          actions = ACTION_LIST,
          registers =
            listOf(
              buildRegisterProto(REGISTER_ID, REGISTER_NAME, REGISTER_BITWIDTH, REGISTER_SIZE)
            ),
          counters = listOf(buildCounterProto(COUNTER_ID, "myCounter", COUNTER_SIZE)),
          meters = listOf(buildMeterProto(METER_ID, "myMeter", METER_SIZE)),
          directCounters =
            listOf(
              P4InfoOuterClass.DirectCounter.newBuilder()
                .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(DIRECT_COUNTER_ID))
                .setDirectTableId(TABLE_ID)
                .build()
            ),
          directMeters =
            listOf(
              P4InfoOuterClass.DirectMeter.newBuilder()
                .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(DIRECT_METER_ID))
                .setDirectTableId(TABLE_ID)
                .build()
            ),
        )
    )

    // --- Populate every field ---

    // tables + directCounterData + directMeterData
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))
    s.directCounterIncrement(TABLE_NAME, entry, 100)
    s.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.MODIFY)
        .setEntity(
          Entity.newBuilder()
            .setDirectMeterEntry(
              P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
                .setTableEntry(entry)
                .setConfig(P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(1000))
            )
        )
        .build()
    )

    // defaultActions + modifiedDefaults
    s.setDefaultActionAndPublish(TABLE_NAME, "drop")
    s.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.MODIFY)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(TABLE_ID)
                .setIsDefaultAction(true)
                .setAction(
                  P4RuntimeOuterClass.TableAction.newBuilder()
                    .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(42))
                )
            )
        )
        .build()
    )
    assertTrue("default should be marked modified", s.isDefaultModified(TABLE_NAME))

    // profileMembers
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)

    // profileGroups
    writeGroup(s, groupId = 1, memberIds = listOf(1))

    // registers
    s.registerWrite(REGISTER_NAME, 0, BitVal(42, REGISTER_BITWIDTH))

    // counters
    s.writeAndPublish(
      counterUpdate(Update.Type.MODIFY, index = 0, byteCount = 500, packetCount = 5)
    )

    // meters
    s.writeAndPublish(meterUpdate(Update.Type.MODIFY, index = 0, cir = 1000, pir = 2000))

    // cloneSessions
    writeCloneSession(s, sessionId = 1, egressPort = 5)

    // multicastGroups
    writeMulticastGroup(s, groupId = 1, replicas = listOf(0 to 1))

    // --- Snapshot ---
    val snapshot = s.snapshot()

    // --- Mutate every field ---

    // tables: add another entry
    s.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(exactEntry(fieldId = 1, value = byteArrayOf(0x0B), actionId = 20))
        )
        .build()
    )

    // directCounterData: increment again
    s.directCounterIncrement(TABLE_NAME, entry, 999)

    // defaultActions: change it
    s.setDefaultActionAndPublish(TABLE_NAME, "forward")

    // profileMembers: add another
    writeMember(s, memberId = 2, actionId = 20, paramValue = 2)

    // profileGroups: add another
    writeGroup(s, groupId = 2, memberIds = listOf(1, 2))

    // registers: overwrite
    s.registerWrite(REGISTER_NAME, 0, BitVal(999, REGISTER_BITWIDTH))

    // counters: overwrite
    s.writeAndPublish(
      counterUpdate(Update.Type.MODIFY, index = 0, byteCount = 9999, packetCount = 99)
    )

    // meters: overwrite
    s.writeAndPublish(meterUpdate(Update.Type.MODIFY, index = 0, cir = 9999, pir = 9999))

    // cloneSessions: add another
    writeCloneSession(s, sessionId = 2, egressPort = 9)

    // multicastGroups: add another
    writeMulticastGroup(s, groupId = 2, replicas = listOf(1 to 2))

    // --- Restore ---
    s.restore(snapshot)
    s.publishSnapshot()

    // --- Verify every field is back to its pre-mutation state ---

    // tables: should have exactly 1 entry, not 2
    assertEquals(1, s.getTableEntries(TABLE_NAME).size)

    // directCounterData: should show 1 packet / 100 bytes, not 2 packets / 1099 bytes
    val counterData = s.readDirectCounterEntries().single().directCounterEntry.data
    assertEquals(1, counterData.packetCount)
    assertEquals(100, counterData.byteCount)

    // directMeterData: should still have cir=1000
    val meterData = s.readDirectMeterEntries().single().directMeterEntry.config
    assertEquals(1000, meterData.cir)

    // defaultActions: should be "action42" (set by Write RPC above), not "forward"
    assertEquals("action42", s.lookup(TABLE_NAME, emptyList()).actionName)

    // modifiedDefaults: should still be marked modified (was modified before snapshot)
    assertTrue("modifiedDefaults should survive restore", s.isDefaultModified(TABLE_NAME))

    // profileMembers: should have 1 member, not 2
    assertEquals(1, s.readProfileMembers().size)

    // profileGroups: should have 1 group, not 2
    assertEquals(1, s.readProfileGroups().size)

    // registers: should be 42, not 999
    assertEquals(BitVal(42, REGISTER_BITWIDTH), s.registerRead(REGISTER_NAME, 0))

    // counters: should be 500 bytes / 5 packets
    val ctrFilter = P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(COUNTER_ID).build()
    val ctrEntry = s.readCounterEntries(ctrFilter).first().counterEntry.data
    assertEquals(500, ctrEntry.byteCount)
    assertEquals(5, ctrEntry.packetCount)

    // meters: should be cir=1000, not 9999
    val mtrFilter = P4RuntimeOuterClass.MeterEntry.newBuilder().setMeterId(METER_ID).build()
    val mtrEntry = s.readMeterEntries(mtrFilter).first().meterEntry.config
    assertEquals(1000, mtrEntry.cir)

    // cloneSessions: should have session 1 only
    assertNotNull(s.getCloneSession(1))
    assertNull(s.getCloneSession(2))

    // multicastGroups: should have group 1 only
    assertNotNull(s.getMulticastGroup(1))
    assertNull(s.getMulticastGroup(2))
  }

  /**
   * Verifies that snapshot produces a deep copy: mutating the original after snapshot does not
   * affect the snapshot, and restoring recovers the snapshotted state.
   */
  @Test
  fun `snapshot is a deep copy - mutations do not leak`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 10)
    write(entry)

    val snapshot = store.snapshot()

    // Delete the entry from the live store; after restore it should reappear.
    store.writeAndPublish(
      Update.newBuilder()
        .setType(Update.Type.DELETE)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()
    )
    assertTrue(store.getTableEntries(TABLE_NAME).isEmpty())

    // restore() consumes the snapshot, so take a second one first.
    val snapshot2 = store.snapshot()

    store.restore(snapshot)
    store.publishSnapshot()

    assertEquals(1, store.getTableEntries(TABLE_NAME).size)

    // Restore to the empty state (snapshot2) to verify the first restore
    // did not leak mutable structures.
    store.restore(snapshot2)
    store.publishSnapshot()

    assertTrue(store.getTableEntries(TABLE_NAME).isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Value sets (P4Runtime spec §9.6)
  // ---------------------------------------------------------------------------

  private fun storeWithValueSet(
    valueSetId: Int = VALUE_SET_ID,
    valueSetName: String = VALUE_SET_NAME,
    valueSetSize: Int = VALUE_SET_SIZE,
  ): TableStore {
    val s = TableStore()
    s.loadMappings(
      p4info =
        buildP4Info(
          tables =
            listOf(
              P4InfoOuterClass.Table.newBuilder()
                .setPreamble(
                  P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID).setAlias(TABLE_NAME)
                )
                .build()
            ),
          actions = ACTION_LIST,
          valueSets =
            listOf(
              P4InfoOuterClass.ValueSet.newBuilder()
                .setPreamble(
                  P4InfoOuterClass.Preamble.newBuilder().setId(valueSetId).setAlias(valueSetName)
                )
                .setSize(valueSetSize)
                .build()
            ),
        )
    )
    return s
  }

  private fun valueSetUpdate(
    valueSetId: Int = VALUE_SET_ID,
    type: Update.Type = Update.Type.MODIFY,
    members: List<P4RuntimeOuterClass.ValueSetMember> = emptyList(),
  ): Update =
    Update.newBuilder()
      .setType(type)
      .setEntity(
        Entity.newBuilder()
          .setValueSetEntry(
            P4RuntimeOuterClass.ValueSetEntry.newBuilder()
              .setValueSetId(valueSetId)
              .addAllMembers(members)
          )
      )
      .build()

  private fun exactVsMember(value: ByteArray): P4RuntimeOuterClass.ValueSetMember =
    P4RuntimeOuterClass.ValueSetMember.newBuilder()
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(1)
          .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value)))
      )
      .build()

  @Test
  fun `value_set MODIFY succeeds`() {
    val s = storeWithValueSet()
    val member = exactVsMember(byteArrayOf(0x42))
    assertEquals(WriteResult.Success, s.writeAndPublish(valueSetUpdate(members = listOf(member))))
    assertEquals(listOf(member), s.getValueSetMembers(VALUE_SET_NAME))
  }

  @Test
  fun `value_set MODIFY replaces all members atomically`() {
    val s = storeWithValueSet()
    val m1 = exactVsMember(byteArrayOf(0x01))
    val m2 = exactVsMember(byteArrayOf(0x02))
    s.writeAndPublish(valueSetUpdate(members = listOf(m1)))
    s.writeAndPublish(valueSetUpdate(members = listOf(m2)))
    assertEquals(listOf(m2), s.getValueSetMembers(VALUE_SET_NAME))
  }

  @Test
  fun `value_set MODIFY with empty members clears the set`() {
    val s = storeWithValueSet()
    s.writeAndPublish(valueSetUpdate(members = listOf(exactVsMember(byteArrayOf(0x01)))))
    s.writeAndPublish(valueSetUpdate(members = emptyList()))
    assertEquals(
      emptyList<P4RuntimeOuterClass.ValueSetMember>(),
      s.getValueSetMembers(VALUE_SET_NAME),
    )
  }

  @Test
  fun `value_set INSERT is rejected`() {
    val s = storeWithValueSet()
    assertTrue(
      s.writeAndPublish(valueSetUpdate(type = Update.Type.INSERT)) is WriteResult.InvalidArgument
    )
  }

  @Test
  fun `value_set DELETE is rejected`() {
    val s = storeWithValueSet()
    assertTrue(
      s.writeAndPublish(valueSetUpdate(type = Update.Type.DELETE)) is WriteResult.InvalidArgument
    )
  }

  @Test
  fun `value_set unknown ID returns NotFound`() {
    val s = storeWithValueSet()
    assertTrue(s.writeAndPublish(valueSetUpdate(valueSetId = 999)) is WriteResult.NotFound)
  }

  @Test
  fun `value_set exceeding size limit returns ResourceExhausted`() {
    val s = storeWithValueSet(valueSetSize = 2)
    val members = (1..3).map { exactVsMember(byteArrayOf(it.toByte())) }
    assertTrue(
      s.writeAndPublish(valueSetUpdate(members = members)) is WriteResult.ResourceExhausted
    )
  }

  @Test
  fun `value_set wildcard read returns all value sets`() {
    val s = storeWithValueSet()
    s.writeAndPublish(valueSetUpdate(members = listOf(exactVsMember(byteArrayOf(0x01)))))
    val results = s.readValueSetEntries()
    assertEquals(1, results.size)
    assertEquals(VALUE_SET_ID, results[0].valueSetEntry.valueSetId)
    assertEquals(1, results[0].valueSetEntry.membersCount)
  }

  @Test
  fun `value_set read by ID returns matching entry`() {
    val s = storeWithValueSet()
    s.writeAndPublish(valueSetUpdate(members = listOf(exactVsMember(byteArrayOf(0x01)))))
    val filter = P4RuntimeOuterClass.ValueSetEntry.newBuilder().setValueSetId(VALUE_SET_ID).build()
    val results = s.readValueSetEntries(filter)
    assertEquals(1, results.size)
    assertEquals(VALUE_SET_ID, results[0].valueSetEntry.valueSetId)
  }

  @Test
  fun `value_set read unknown ID returns empty`() {
    val s = storeWithValueSet()
    val filter = P4RuntimeOuterClass.ValueSetEntry.newBuilder().setValueSetId(999).build()
    assertEquals(0, s.readValueSetEntries(filter).size)
  }

  // ---------------------------------------------------------------------------
  // Snapshot isolation and concurrency
  // ---------------------------------------------------------------------------

  @Test
  @Suppress("MaxLineLength")
  fun `publishSnapshot creates a new snapshot that does not share mutable state with the old one`() {
    store.writeAndPublish(
      insertUpdate(exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10))
    )
    val snapshotBeforeWrite = store.snapshot

    store.writeAndPublish(
      insertUpdate(exactEntry(fieldId = 1, value = byteArrayOf(20), actionId = 20))
    )

    assertNotSame("publish must create a new snapshot object", snapshotBeforeWrite, store.snapshot)
    assertEquals(2, store.getTableEntries(TABLE_NAME).size)
    assertTrue(store.lookup(TABLE_NAME, listOf("1" to BitVal(20, 8))).hit)
  }

  @Test
  fun `concurrent directCounterIncrement is thread-safe`() {
    val s = storeWithDirectCounter()
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(10), actionId = 10)
    s.writeAndPublish(insertUpdate(entry))

    val threads = 8
    val incrementsPerThread = 1000
    val latch = java.util.concurrent.CountDownLatch(1)
    val workers =
      (1..threads).map {
        Thread {
          latch.await()
          repeat(incrementsPerThread) { s.directCounterIncrement(TABLE_NAME, entry, 1) }
        }
      }
    workers.forEach { it.start() }
    latch.countDown()
    workers.forEach { it.join() }

    val data = s.getDirectCounterData(entry)!!
    assertEquals((threads * incrementsPerThread).toLong(), data.packetCount)
    assertEquals((threads * incrementsPerThread).toLong(), data.byteCount)
  }

  @Test
  fun `concurrent registerWrite is thread-safe`() {
    val s = TableStore()
    s.loadMappings(
      p4info =
        buildP4Info(
          registers =
            listOf(buildRegisterProto(REGISTER_ID, REGISTER_NAME, REGISTER_BITWIDTH, 1000))
        )
    )

    val threads = 8
    val writesPerThread = 100
    val latch = java.util.concurrent.CountDownLatch(1)
    val workers =
      (0 until threads).map { threadId ->
        Thread {
          latch.await()
          repeat(writesPerThread) { i ->
            val index = threadId * writesPerThread + i
            s.registerWrite(REGISTER_NAME, index, BitVal(index, REGISTER_BITWIDTH))
          }
        }
      }
    workers.forEach { it.start() }
    latch.countDown()
    workers.forEach { it.join() }

    for (i in 0 until threads * writesPerThread) {
      assertEquals(BitVal(i, REGISTER_BITWIDTH), s.registerRead(REGISTER_NAME, i))
    }
  }

  // ---------------------------------------------------------------------------
  // Constants
  // ---------------------------------------------------------------------------

  companion object {
    private const val TABLE_ID = 1
    private const val TABLE_NAME = "myTable"
    private const val PROFILE_TABLE_ID = 2
    private const val PROFILE_TABLE_NAME = "selectorTable"
    private const val PROFILE_ID = 100
    private const val REGISTER_ID = 500
    private const val REGISTER_NAME = "myRegister"
    private const val REGISTER_BITWIDTH = 32
    private const val REGISTER_SIZE = 4
    private const val COUNTER_ID = 600
    private const val COUNTER_SIZE = 4
    private const val METER_ID = 700
    private const val METER_SIZE = 4
    private const val TABLE_SIZE_LIMIT = 3
    private const val MAX_GROUP_SIZE = 2
    private const val DIRECT_COUNTER_ID = 800
    private const val DIRECT_METER_ID = 900
    private const val VALUE_SET_ID = 1000
    private const val VALUE_SET_NAME = "myValueSet"
    private const val VALUE_SET_SIZE = 4
    private val ACTION_IDS = listOf(10, 20, 42, 50, 77, 99, 100, 200)
    private val ACTION_LIST: List<P4InfoOuterClass.Action> =
      ACTION_IDS.map { id ->
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder()
              .setId(id)
              .setName("action$id")
              .setAlias("action$id")
          )
          .build()
      }

    /** Default p4info used by most tests: one table + the standard set of action IDs. */
    private val BASE_P4INFO: P4InfoOuterClass.P4Info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(
              P4InfoOuterClass.Preamble.newBuilder()
                .setId(TABLE_ID)
                .setName(TABLE_NAME)
                .setAlias(TABLE_NAME)
            )
        )
        .addAllActions(ACTION_LIST)
        .build()
  }
}
