package fourward.e2e.p4runtimediff

import com.google.protobuf.ByteString
import fourward.bazel.repoRoot
import fourward.stf.loadPipelineConfig
import io.grpc.StatusRuntimeException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.TableAction
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * The five P4Runtime scenarios from `designs/p4runtime_diff.md`. Each scenario sends the same gRPC
 * operations to 4ward and BMv2, then diffs responses with the canonicalizations in [ResponseDiff].
 *
 * Both servers and the pipeline config are class-scoped — spawning is the dominant cost, and the
 * scenarios share no state that requires test isolation. State between tests is reset by
 * [resetState] in `@Before`.
 *
 * Skipped via JUnit `Assume` when the simple_switch_grpc binary or the P4 fixtures aren't built.
 * Tagged `heavy` so default test runs don't pay the subprocess-spawn cost.
 */
class P4RuntimeDiffScenariosTest {

  @Before
  fun resetState() {
    deleteAllEntries(fourward)
    deleteAllEntries(bmv2)
  }

  // §designs/p4runtime_diff.md scenario 1: the §8.3 regression test, externally validated.
  @Test
  fun `round-trip canonical form — both servers return shortest bytestrings`() {
    // Send a non-canonical (zero-padded) match value. After Read, both must return the same
    // canonical form per §8.3.
    val padded = byteArrayOf(0x00, 0x08, 0x00) // 0x0800 in 3 bytes; canonical is 2.
    writeOnBoth(Update.Type.INSERT, exactEntry(ByteString.copyFrom(padded), port = 1))
    assertReadAgrees()
  }

  // §designs/p4runtime_diff.md scenario 2.
  @Test
  fun `modify-after-padded-write — same logical key matches across encodings`() {
    val padded = byteArrayOf(0x00, 0x08, 0x00)
    val canonical = byteArrayOf(0x08, 0x00)
    writeOnBoth(Update.Type.INSERT, exactEntry(ByteString.copyFrom(padded), port = 1))
    writeOnBoth(Update.Type.MODIFY, exactEntry(ByteString.copyFrom(canonical), port = 2))
    assertReadAgrees()
  }

  // §designs/p4runtime_diff.md scenario 3.
  @Test
  fun `out-of-range values — both servers reject`() {
    val tooLarge = byteArrayOf(0x01, 0x00, 0x00) // overflows bit<16> etherType
    val entry = exactEntry(ByteString.copyFrom(tooLarge), port = 1)
    // Both servers must reject. The exact gRPC status code may differ — 4ward returns OUT_OF_RANGE
    // per §8.3. Tighten when the BMv2 side aligns with the spec.
    expectStatusFrom { writeUpdate(fourward, Update.Type.INSERT, entry) }
    expectStatusFrom { writeUpdate(bmv2, Update.Type.INSERT, entry) }
  }

  // §designs/p4runtime_diff.md scenario 4.
  @Test
  fun `batch atomicity — partial failure under default atomicity`() {
    // Two-update batch: one valid INSERT, one INSERT with overflowing value.
    val valid = exactEntry(ByteString.copyFrom(byteArrayOf(0x08, 0x00)), port = 1)
    val invalid = exactEntry(ByteString.copyFrom(byteArrayOf(0x01, 0x00, 0x00)), port = 2)
    val req =
      WriteRequest.newBuilder()
        .setDeviceId(DEVICE_ID)
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(asEntity(valid)))
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(asEntity(invalid)))
        .build()
    // Both servers may reject differently (scenario 4 is about the post-failure read state, not the
    // exact error). Tolerate any gRPC-level rejection; let other exceptions bubble.
    ignoreGrpcStatus { fourward.stub.write(req) }
    ignoreGrpcStatus { bmv2.stub.write(req) }
    assertReadAgrees()
  }

  // §designs/p4runtime_diff.md scenario 5.
  // Previously @Ignore'd: 4ward included defaults in wildcard reads, BMv2 didn't.
  // Resolved per §9.1.6 — wildcard reads with is_default_action=false (default) exclude defaults.
  @Test
  fun `default action modify — both servers read it back identically`() {
    val defaultEntry =
      TableEntry.newBuilder()
        .setTableId(schema.tableId)
        .setIsDefaultAction(true)
        .setAction(forwardAction(port = 7))
        .build()
    writeOnBoth(Update.Type.MODIFY, defaultEntry)
    // §9.1.6: wildcard read excludes defaults — both servers should agree on empty.
    assertReadAgrees()
    // Read with is_default_action=true — both servers should return the same modified default.
    assertDefaultReadAgrees()
  }

  // ---------------------------------------------------------------------------
  // Per-test helpers — delegate to class-scoped fixture state.
  // ---------------------------------------------------------------------------

  private fun writeOnBoth(type: Update.Type, entry: TableEntry) {
    writeUpdate(fourward, type, entry)
    writeUpdate(bmv2, type, entry)
  }

  private fun writeUpdate(runner: P4RuntimeRunner, type: Update.Type, entry: TableEntry) {
    val req =
      WriteRequest.newBuilder()
        .setDeviceId(DEVICE_ID)
        .addUpdates(Update.newBuilder().setType(type).setEntity(asEntity(entry)))
        .build()
    runner.stub.write(req)
  }

  /** Reads all table entries from each server, canonicalizes them, asserts equal. */
  private fun assertReadAgrees() {
    val req = wildcardTableReadRequest()
    assertProtosEqual(
      canonicalizeReadResponse(readAll(fourward, req)),
      canonicalizeReadResponse(readAll(bmv2, req)),
      leftLabel = "4ward",
      rightLabel = "bmv2",
    )
  }

  /** Reads default entries from each server, canonicalizes, asserts equal. */
  private fun assertDefaultReadAgrees() {
    val req = defaultTableReadRequest()
    assertProtosEqual(
      canonicalizeReadResponse(readAll(fourward, req)),
      canonicalizeReadResponse(readAll(bmv2, req)),
      leftLabel = "4ward",
      rightLabel = "bmv2",
    )
  }

  /** Wildcard read of every entry in the harness's target table. */
  private fun wildcardTableReadRequest(): ReadRequest =
    ReadRequest.newBuilder()
      .setDeviceId(DEVICE_ID)
      .addEntities(
        Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(schema.tableId))
      )
      .build()

  /** Read the default entry from the harness's target table. */
  private fun defaultTableReadRequest(): ReadRequest =
    ReadRequest.newBuilder()
      .setDeviceId(DEVICE_ID)
      .addEntities(
        Entity.newBuilder()
          .setTableEntry(
            TableEntry.newBuilder().setTableId(schema.tableId).setIsDefaultAction(true)
          )
      )
      .build()

  /** Concatenates every entity from a streaming Read response. Empty stream → empty response. */
  private fun readAll(runner: P4RuntimeRunner, req: ReadRequest): ReadResponse {
    val builder = ReadResponse.newBuilder()
    val stream = runner.stub.read(req)
    while (stream.hasNext()) builder.addAllEntities(stream.next().entitiesList)
    return builder.build()
  }

  private fun expectStatusFrom(block: () -> Unit): StatusRuntimeException =
    try {
      block()
      error("expected StatusRuntimeException; call succeeded")
    } catch (e: StatusRuntimeException) {
      e
    }

  /** Runs [block]; absorbs a [StatusRuntimeException] but lets every other exception propagate. */
  private inline fun ignoreGrpcStatus(block: () -> Unit) {
    try {
      block()
    } catch (_: StatusRuntimeException) {
      // expected — caller doesn't care about the exact rejection
    }
  }

  private fun asEntity(entry: TableEntry): Entity = Entity.newBuilder().setTableEntry(entry).build()

  private fun exactEntry(matchValue: ByteString, port: Int): TableEntry =
    TableEntry.newBuilder()
      .setTableId(schema.tableId)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(schema.matchFieldId)
          .setExact(FieldMatch.Exact.newBuilder().setValue(matchValue))
      )
      .setAction(forwardAction(port))
      .build()

  private fun forwardAction(port: Int): TableAction =
    TableAction.newBuilder()
      .setAction(
        Action.newBuilder()
          .setActionId(schema.forwardActionId)
          .addParams(
            Action.Param.newBuilder()
              .setParamId(schema.forwardParamId)
              .setValue(ByteString.copyFrom(byteArrayOf(port.toByte())))
          )
      )
      .build()

  /** Wildcard-DELETE every entry from [runner]'s table, ignoring "no such entry" failures. */
  private fun deleteAllEntries(runner: P4RuntimeRunner) {
    val deletes =
      readAll(runner, wildcardTableReadRequest()).entitiesList.mapNotNull { entity ->
        if (entity.tableEntry.tableId == schema.tableId && !entity.tableEntry.isDefaultAction) {
          Update.newBuilder().setType(Update.Type.DELETE).setEntity(entity).build()
        } else null
      }
    if (deletes.isEmpty()) return
    ignoreGrpcStatus {
      runner.stub.write(
        WriteRequest.newBuilder().setDeviceId(DEVICE_ID).addAllUpdates(deletes).build()
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Class-scoped fixture: spawn both servers + push pipeline config once.
  // ---------------------------------------------------------------------------

  /** P4Info IDs the scenarios need, discovered once from the loaded pipeline. */
  data class TableSchema(
    val tableId: Int,
    val matchFieldId: Int,
    val forwardActionId: Int,
    val forwardParamId: Int,
  ) {
    companion object {
      /**
       * Identifies the harness's target table structurally: exactly one EXACT match field, plus
       * exactly one referenced action that takes one parameter. Fails loudly if zero or multiple
       * tables/actions match — the harness owner must extend [TableSchema] before adding fixtures
       * with multiple matching shapes.
       */
      fun discover(p4Info: P4Info): TableSchema {
        val candidateTables =
          p4Info.tablesList.filter { t ->
            t.matchFieldsCount == 1 &&
              t.matchFieldsList.first().matchType ==
                p4.config.v1.P4InfoOuterClass.MatchField.MatchType.EXACT
          }
        require(candidateTables.size == 1) {
          "expected exactly one single-EXACT-field table in P4Info; got ${candidateTables.size}: " +
            candidateTables.map { it.preamble.name }
        }
        val table = candidateTables.single()
        val actionsById = p4Info.actionsList.associateBy { it.preamble.id }
        val candidateActions =
          table.actionRefsList.mapNotNull { actionsById[it.id] }.filter { it.paramsCount == 1 }
        require(candidateActions.size == 1) {
          "expected exactly one single-param action on table ${table.preamble.name}; got " +
            "${candidateActions.size}: ${candidateActions.map { it.preamble.name }}"
        }
        val action = candidateActions.single()
        return TableSchema(
          tableId = table.preamble.id,
          matchFieldId = table.matchFieldsList.first().id,
          forwardActionId = action.preamble.id,
          forwardParamId = action.paramsList.first().id,
        )
      }
    }
  }

  companion object {
    private const val DEVICE_ID = 1L
    private const val SET_CONFIG_TIMEOUT_S = 30L

    private lateinit var fourward: FourwardP4RuntimeRunner
    private lateinit var bmv2: Bmv2P4RuntimeRunner
    private lateinit var schema: TableSchema

    @BeforeClass
    @JvmStatic
    fun spawnServers() {
      val pkg = "e2e_tests/p4runtime_diff"
      val binary = repoRoot.resolve("$pkg/simple_switch_grpc")
      val fourwardPipelinePath = repoRoot.resolve("$pkg/basic_table_fourward.txtpb")
      val bmv2JsonPath = repoRoot.resolve("$pkg/basic_table.json")
      Assume.assumeTrue(
        "simple_switch_grpc binary or P4 fixtures missing — see e2e_tests/p4runtime_diff/README.md",
        Files.exists(binary) && Files.exists(fourwardPipelinePath) && Files.exists(bmv2JsonPath),
      )

      val fourwardPipeline = loadPipelineConfig(fourwardPipelinePath)
      val p4Info = fourwardPipeline.p4Info
      schema = TableSchema.discover(p4Info)

      fourward = FourwardP4RuntimeRunner(deviceId = DEVICE_ID)
      bmv2 = Bmv2P4RuntimeRunner(binary = binary, deviceId = DEVICE_ID)
      pushPipelineConfig(fourward, p4Info, fourwardPipeline.device.toByteString())
      pushPipelineConfig(bmv2, p4Info, ByteString.copyFrom(Files.readAllBytes(bmv2JsonPath)))
    }

    @AfterClass
    @JvmStatic
    fun shutdownServers() {
      if (::bmv2.isInitialized) bmv2.close()
      if (::fourward.isInitialized) fourward.close()
    }

    private fun pushPipelineConfig(
      runner: P4RuntimeRunner,
      p4Info: P4Info,
      deviceConfig: ByteString,
    ) {
      runner.stub
        .withDeadlineAfter(SET_CONFIG_TIMEOUT_S, TimeUnit.SECONDS)
        .setForwardingPipelineConfig(
          SetForwardingPipelineConfigRequest.newBuilder()
            .setDeviceId(DEVICE_ID)
            .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
            .setConfig(
              ForwardingPipelineConfig.newBuilder()
                .setP4Info(p4Info)
                .setP4DeviceConfig(deviceConfig)
            )
            .build()
        )
    }
  }
}
