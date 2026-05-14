package fourward.grpc

import com.google.protobuf.ByteString
import fourward.PipelineConfig
import fourward.grpc.FourwardTestHarness.Companion.assertGrpcError
import fourward.grpc.FourwardTestHarness.Companion.buildExactEntry
import fourward.grpc.FourwardTestHarness.Companion.extractBatchErrors
import io.grpc.Status
import io.grpc.StatusException
import org.junit.After
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.DigestEntry
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * Spec-driven conformance tests for P4Runtime Write RPC error conditions.
 *
 * Each test encodes a specific requirement from the P4Runtime specification. The spec section and
 * error code are cited in a comment above each test.
 *
 * Uses the basic_table.p4 fixture (exact-match table with forward/drop actions).
 */
class P4RuntimeWriteErrorTest {

  private lateinit var harness: FourwardTestHarness

  @Before
  fun setUp() {
    harness = FourwardTestHarness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  private fun loadBasicTableConfig(): PipelineConfig =
    FourwardTestHarness.loadConfig("e2e_tests/basic_table/basic_table.txtpb")

  // =========================================================================
  // Preconditions
  // =========================================================================

  // P4Runtime spec §9.1: all Write operations require a forwarding pipeline to be configured.
  @Test
  fun `write without pipeline returns FAILED_PRECONDITION`() {
    val entity = Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(1)).build()
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.installEntry(entity) }
  }

  // =========================================================================
  // INSERT errors
  // =========================================================================

  // P4Runtime spec §9.1: INSERT of an entry that already exists must return ALREADY_EXISTS.
  @Test
  fun `insert existing entry returns ALREADY_EXISTS`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    assertGrpcError(Status.Code.ALREADY_EXISTS) { harness.installEntry(entry) }
  }

  @Test
  fun `insert with unknown table ID returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadBasicTableConfig())
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unknown table ID") {
      harness.installEntry(badTableEntity())
    }
  }

  // =========================================================================
  // MODIFY errors
  // =========================================================================

  // P4Runtime spec §9.1: MODIFY of an entry that does not exist must return NOT_FOUND.
  @Test
  fun `modify non-existent entry returns NOT_FOUND`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)

    assertGrpcError(Status.Code.NOT_FOUND) { harness.modifyEntry(entry) }
  }

  // =========================================================================
  // DELETE errors
  // =========================================================================

  // P4Runtime spec §9.1: DELETE of an entry that does not exist must return NOT_FOUND.
  @Test
  fun `delete non-existent entry returns NOT_FOUND`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)

    assertGrpcError(Status.Code.NOT_FOUND) { harness.deleteEntry(entry) }
  }

  // =========================================================================
  // Lifecycle: INSERT → MODIFY → DELETE → DELETE should fail
  // =========================================================================

  // Verifies the full entry lifecycle: insert, modify, delete succeed; second delete fails.
  @Test
  fun `entry lifecycle insert-modify-delete succeeds then delete again fails`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)

    harness.installEntry(entry)
    harness.modifyEntry(buildExactEntry(config, matchValue = 0x0800, port = 2))
    harness.deleteEntry(entry)

    assertGrpcError(Status.Code.NOT_FOUND) { harness.deleteEntry(entry) }
  }

  // =========================================================================
  // Write validation errors
  // =========================================================================

  /** Builds a valid exact entry and applies a mutation to produce an invalid one. */
  private fun buildInvalidEntry(config: PipelineConfig, mutate: (Entity.Builder) -> Unit): Entity {
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    return valid.toBuilder().apply(mutate).build()
  }

  // P4Runtime spec §9.1.2: action_id must be in the table's action_refs.
  @Test
  fun `insert with unknown action ID returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder.actionBuilder.actionBuilder.setActionId(99999).clearParams()
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unknown action ID") {
      harness.installEntry(entity)
    }
  }

  // P4Runtime spec §9.1.2: action parameters must match the p4info schema.
  @Test
  fun `insert with wrong param count returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // forward expects 1 param (port); send 0 params.
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder.actionBuilder.actionBuilder.clearParams()
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "expects") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: exact-only tables must have priority == 0.
  @Test
  fun `insert with nonzero priority on exact table returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity = buildInvalidEntry(config) { b -> b.tableEntryBuilder.setPriority(10) }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "priority") { harness.installEntry(entity) }
  }

  // P4Runtime spec §8.3: match field value must fit in the field's bitwidth.
  @Test
  fun `insert with match value out of range returns OUT_OF_RANGE`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // etherType is 16-bit; send a 3-byte value 0x010000 that overflows.
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder
          .getMatchBuilder(0)
          .exactBuilder
          .setValue(ByteString.copyFrom(byteArrayOf(0x01, 0x00, 0x00)))
      }
    assertGrpcError(Status.Code.OUT_OF_RANGE, "does not fit") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: match field kind must match p4info.
  @Test
  fun `insert with ternary encoding on exact field returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val matchField = config.p4Info.tablesList.first().matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder.setPriority(1)
        b.tableEntryBuilder
          .getMatchBuilder(0)
          .clearExact()
          .setFieldId(matchField.id)
          .ternaryBuilder
          .setValue(ByteString.copyFrom(FourwardTestHarness.longToBytes(0x0800, byteWidth)))
          .setMask(ByteString.copyFrom(FourwardTestHarness.longToBytes(0xFFFF, byteWidth)))
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "EXACT") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: exact match fields are required (cannot be omitted).
  @Test
  fun `insert with missing exact match field returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // No match fields at all — the table requires an exact match.
    val entity = buildInvalidEntry(config) { b -> b.tableEntryBuilder.clearMatch() }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "missing") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.2: param_id must exist in the action's p4info schema.
  @Test
  fun `insert with unknown param ID returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder.actionBuilder.actionBuilder.getParamsBuilder(0).setParamId(99999)
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unknown") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: match field IDs must correspond to p4info fields.
  @Test
  fun `insert with unknown match field ID returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity =
      buildInvalidEntry(config) { b -> b.tableEntryBuilder.getMatchBuilder(0).setFieldId(99999) }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unknown") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1: each match field may appear at most once.
  @Test
  fun `insert with duplicate match field returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity =
      buildInvalidEntry(config) { b ->
        // Add a second copy of the first match field.
        b.tableEntryBuilder.addMatch(b.tableEntryBuilder.getMatch(0))
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "duplicate") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: a match field with no value set must be rejected.
  @Test
  fun `insert with match field missing value returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val matchField = config.p4Info.tablesList.first().matchFieldsList.first()
    val entity =
      buildInvalidEntry(config) { b ->
        // Clear the exact value, leaving field_id set but no oneof variant.
        b.tableEntryBuilder.getMatchBuilder(0).clearExact().setFieldId(matchField.id)
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "no value set") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: exact-only tables must have priority == 0.
  @Test
  fun `insert with priority must be 0 message on exact table`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity = buildInvalidEntry(config) { b -> b.tableEntryBuilder.setPriority(5) }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "priority") { harness.installEntry(entity) }
  }

  // P4Runtime spec §12.3: per-update errors must include space and code fields.
  @Test
  fun `batch error details include space and code fields`() {
    harness.loadPipeline(loadBasicTableConfig())
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(badTableEntity()))
    val errors = assertBatchError { harness.writeRaw(request) }
    assert(errors.size == 1)
    assert(errors[0].space == "p4.v1") { "error space should be 'p4.v1', got '${errors[0].space}'" }
    assert(errors[0].code == errors[0].canonicalCode) {
      "error code should match canonical_code, got code=${errors[0].code} " +
        "canonical_code=${errors[0].canonicalCode}"
    }
  }

  // =========================================================================
  // Unimplemented feature guards
  // =========================================================================

  // Digest entities are not supported; clients must get a clear error rather than silent failure.
  @Test
  fun `write digest entity returns UNIMPLEMENTED`() {
    harness.loadPipeline(loadBasicTableConfig())
    val entity = Entity.newBuilder().setDigestEntry(DigestEntry.newBuilder().setDigestId(1)).build()
    assertGrpcError(Status.Code.UNIMPLEMENTED, "digest") { harness.installEntry(entity) }
  }

  // idle_timeout_ns is not supported; must be rejected rather than silently ignored.
  @Test
  fun `insert with idle_timeout_ns returns UNIMPLEMENTED`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity = buildInvalidEntry(config) { b -> b.tableEntryBuilder.setIdleTimeoutNs(1_000_000) }
    assertGrpcError(Status.Code.UNIMPLEMENTED, "idle_timeout_ns") { harness.installEntry(entity) }
  }

  // =========================================================================
  // Action profile member validation (§9.2.1)
  // =========================================================================

  private fun loadActionSelectorConfig(): PipelineConfig =
    FourwardTestHarness.loadConfig("e2e_tests/trace_tree/action_selector_3.txtpb")

  // P4Runtime spec §9.2.1: member with unknown action_profile_id must return NOT_FOUND.
  @Test
  fun `insert member with unknown profile ID returns NOT_FOUND`() {
    harness.loadPipeline(loadActionSelectorConfig())
    val member =
      FourwardTestHarness.buildMemberEntity(actionProfileId = 99, memberId = 1, actionId = 1)
    assertGrpcError(Status.Code.NOT_FOUND, "action_profile_id") { harness.installEntry(member) }
  }

  // P4Runtime spec §9.2.1: member with unknown action_id must return INVALID_ARGUMENT.
  @Test
  fun `insert member with unknown action ID returns INVALID_ARGUMENT`() {
    val config = loadActionSelectorConfig()
    harness.loadPipeline(config)
    val profileId = config.p4Info.actionProfilesList.first().preamble.id
    val member =
      FourwardTestHarness.buildMemberEntity(
        actionProfileId = profileId,
        memberId = 1,
        actionId = 99,
      )
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unknown action ID") {
      harness.installEntry(member)
    }
  }

  // P4Runtime spec §9.2.2: group with unknown action_profile_id must return NOT_FOUND.
  @Test
  fun `insert group with unknown profile ID returns NOT_FOUND`() {
    harness.loadPipeline(loadActionSelectorConfig())
    val group =
      FourwardTestHarness.buildGroupEntity(actionProfileId = 99, groupId = 1, memberIds = listOf(1))
    assertGrpcError(Status.Code.NOT_FOUND, "action_profile_id") { harness.installEntry(group) }
  }

  // =========================================================================
  // Write atomicity (P4Runtime spec §12.2)
  // =========================================================================

  // CONTINUE_ON_ERROR: all updates attempted; per-update errors reported via gRPC details.
  @Test
  fun `continue on error applies good update and reports bad update`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val good = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(good, badTableEntity()))
    val errors = assertBatchError { harness.writeRaw(request) }
    assert(errors.size == 2) { "expected 2 per-update errors, got ${errors.size}" }
    assert(errors[0].canonicalCode == com.google.rpc.Code.OK_VALUE) { "first update should be OK" }
    assert(errors[1].canonicalCode == com.google.rpc.Code.INVALID_ARGUMENT_VALUE) {
      "second update should be INVALID_ARGUMENT"
    }
    // Good update was applied despite bad one failing.
    val readBack = harness.readEntries()
    assert(readBack.isNotEmpty()) { "good entry should have been applied" }
  }

  // ROLLBACK_ON_ERROR: failure rolls back all prior updates in the batch.
  @Test
  fun `rollback on error undoes prior updates`() =
    assertAtomicRollback(WriteRequest.Atomicity.ROLLBACK_ON_ERROR)

  // DATAPLANE_ATOMIC: same all-or-none semantics as ROLLBACK_ON_ERROR.
  @Test
  fun `dataplane atomic undoes prior updates`() =
    assertAtomicRollback(WriteRequest.Atomicity.DATAPLANE_ATOMIC)

  /** Verifies that [atomicity] mode rolls back all prior updates on failure. */
  private fun assertAtomicRollback(atomicity: WriteRequest.Atomicity) {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val good = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val request =
      harness
        .buildBatchRequest(Update.Type.INSERT, listOf(good, badTableEntity()))
        .toBuilder()
        .setAtomicity(atomicity)
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.writeRaw(request) }
    val readBack = harness.readEntries()
    assert(readBack.isEmpty()) { "$atomicity should have rolled back the good entry" }
  }

  // =========================================================================
  // Batch edge cases (P4Runtime spec §12.2)
  // =========================================================================

  @Test
  fun `empty batch succeeds`() {
    harness.loadPipeline(loadBasicTableConfig())
    val request = WriteRequest.newBuilder().setDeviceId(1).build()
    harness.writeRaw(request)
  }

  @Test
  fun `all-failing batch reports all errors`() {
    harness.loadPipeline(loadBasicTableConfig())
    val request =
      harness.buildBatchRequest(
        Update.Type.INSERT,
        listOf(badTableEntity(99998), badTableEntity(99999)),
      )
    val errors = assertBatchError { harness.writeRaw(request) }
    assert(errors.size == 2) { "expected 2 per-update errors" }
    assert(errors[0].canonicalCode == com.google.rpc.Code.INVALID_ARGUMENT_VALUE)
    assert(errors[1].canonicalCode == com.google.rpc.Code.INVALID_ARGUMENT_VALUE)
  }

  @Test
  fun `mixed INSERT and DELETE batch applies both`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry1 = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entry2 = buildExactEntry(config, matchValue = 0x0806, port = 2)
    // Install two entries first.
    harness.installEntry(entry1)
    harness.installEntry(entry2)
    // Batch: DELETE entry1 + INSERT entry3.
    val entry3 = buildExactEntry(config, matchValue = 0x86DD, port = 3)
    val request =
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .addUpdates(Update.newBuilder().setType(Update.Type.DELETE).setEntity(entry1))
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(entry3))
        .build()
    harness.writeRaw(request)
    val results = harness.readEntries()
    assert(results.size == 2) { "expected 2 entries after mixed DELETE+INSERT batch" }
  }

  @Test
  fun `duplicate INSERT in batch reports ALREADY_EXISTS for second`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(entry, entry))
    val errors = assertBatchError { harness.writeRaw(request) }
    assert(errors.size == 2) { "expected 2 per-update errors" }
    assert(errors[0].canonicalCode == com.google.rpc.Code.OK_VALUE) { "first INSERT should be OK" }
    assert(errors[1].canonicalCode == com.google.rpc.Code.ALREADY_EXISTS_VALUE) {
      "second INSERT should be ALREADY_EXISTS"
    }
  }

  // =========================================================================
  // Structured error detail verification (P4Runtime spec §12.1)
  // =========================================================================

  @Test
  fun `batch error details include message field`() {
    harness.loadPipeline(loadBasicTableConfig())
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(badTableEntity()))
    val errors = assertBatchError { harness.writeRaw(request) }
    assert(errors.size == 1)
    assert(errors[0].canonicalCode == com.google.rpc.Code.INVALID_ARGUMENT_VALUE)
    assert(errors[0].message.isNotEmpty()) { "error detail should include a message" }
  }

  @Test
  fun `batch error details include correct canonical_code for validation errors`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // Use an exact entry with wrong match field width to trigger INVALID_ARGUMENT.
    val table = config.p4Info.tablesList.first()
    val tableId = table.preamble.id
    val actionId = table.actionRefsList.first().id
    val wrongWidth =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(tableId)
            .addMatch(
              p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(table.matchFieldsList.first().id)
                .setExact(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(byteArrayOf(0x01)))
                )
            )
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(p4.v1.P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId))
            )
        )
        .build()
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(wrongWidth))
    val errors = assertBatchError { harness.writeRaw(request) }
    assert(errors.size == 1)
    assert(errors[0].canonicalCode == com.google.rpc.Code.INVALID_ARGUMENT_VALUE) {
      "wrong width should yield INVALID_ARGUMENT, got code ${errors[0].canonicalCode}"
    }
    assert(errors[0].message.isNotEmpty()) { "validation error should include a message" }
  }

  @Test
  fun `batch error with mixed codes reports per-update details`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val good = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(good, badTableEntity()))
    val errors = assertBatchError { harness.writeRaw(request) }
    assert(errors.size == 2) { "expected 2 per-update errors" }
    assert(errors[0].canonicalCode == com.google.rpc.Code.OK_VALUE)
    assert(errors[1].canonicalCode == com.google.rpc.Code.INVALID_ARGUMENT_VALUE)
    // The failing update should have an explanatory message.
    assert(errors[1].message.isNotEmpty()) { "INVALID_ARGUMENT error should include a message" }
  }

  // =========================================================================
  // Unimplemented entity guards (coverage gaps)
  // =========================================================================

  // P4Runtime spec §9.6: value_set only supports MODIFY, not INSERT or DELETE.
  @Test
  fun `write ValueSetEntry with INSERT returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadBasicTableConfig())
    val entity =
      Entity.newBuilder()
        .setValueSetEntry(p4.v1.P4RuntimeOuterClass.ValueSetEntry.newBuilder().setValueSetId(1))
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "MODIFY") { harness.installEntry(entity) }
  }

  @Test
  fun `write ExternEntry returns UNIMPLEMENTED`() {
    harness.loadPipeline(loadBasicTableConfig())
    val entity =
      Entity.newBuilder()
        .setExternEntry(
          p4.v1.P4RuntimeOuterClass.ExternEntry.newBuilder().setExternTypeId(1).setExternId(1)
        )
        .build()
    assertGrpcError(Status.Code.UNIMPLEMENTED, "ExternEntry") { harness.installEntry(entity) }
  }

  // =========================================================================
  // Write atomicity edge cases (coverage gaps)
  // =========================================================================

  // P4Runtime spec §12.2: unrecognized atomicity must be rejected.
  @Test
  fun `UNRECOGNIZED write atomicity returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadBasicTableConfig())
    @Suppress("MagicNumber")
    val request =
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .setAtomicityValue(999)
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(badTableEntity()))
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unrecognized write atomicity") {
      harness.writeRaw(request)
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /** Builds an entity referencing a non-existent table, for error-path testing. */
  private fun badTableEntity(tableId: Int = 99999): Entity =
    Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(tableId)).build()

  /** Executes [block], asserts it throws a batch error, and returns the per-update error list. */
  private fun assertBatchError(block: () -> Unit): List<p4.v1.P4RuntimeOuterClass.Error> {
    try {
      block()
      throw AssertionError("expected batch error")
    } catch (e: StatusException) {
      return extractBatchErrors(e) ?: throw AssertionError("no batch error details in trailers")
    }
  }
}
