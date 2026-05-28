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
import p4.v1.P4RuntimeOuterClass.MulticastGroupEntry
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.Replica
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.TableAction
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * P4Runtime differential scenarios using the basic_table fixture. Each scenario sends the same gRPC
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
    deleteAllMulticastGroups(fourward)
    deleteAllMulticastGroups(bmv2)
  }

  // ===========================================================================
  // Scenarios 1-5: table entry encoding and semantics
  // ===========================================================================

  @Test
  fun `1 — round-trip canonical form — both servers return shortest bytestrings`() {
    val padded = byteArrayOf(0x00, 0x08, 0x00)
    writeOnBoth(Update.Type.INSERT, exactEntry(ByteString.copyFrom(padded), port = 1))
    assertReadAgrees()
  }

  @Test
  fun `2 — modify-after-padded-write — same logical key matches across encodings`() {
    val padded = byteArrayOf(0x00, 0x08, 0x00)
    val canonical = byteArrayOf(0x08, 0x00)
    writeOnBoth(Update.Type.INSERT, exactEntry(ByteString.copyFrom(padded), port = 1))
    writeOnBoth(Update.Type.MODIFY, exactEntry(ByteString.copyFrom(canonical), port = 2))
    assertReadAgrees()
  }

  @Test
  fun `3 — out-of-range values — both servers reject`() {
    val tooLarge = byteArrayOf(0x01, 0x00, 0x00)
    val entry = exactEntry(ByteString.copyFrom(tooLarge), port = 1)
    expectStatusFrom { writeUpdate(fourward, Update.Type.INSERT, entry) }
    expectStatusFrom { writeUpdate(bmv2, Update.Type.INSERT, entry) }
  }

  @Test
  fun `4 — batch atomicity — partial failure under default atomicity`() {
    val valid = exactEntry(ByteString.copyFrom(byteArrayOf(0x08, 0x00)), port = 1)
    val invalid = exactEntry(ByteString.copyFrom(byteArrayOf(0x01, 0x00, 0x00)), port = 2)
    val req =
      WriteRequest.newBuilder()
        .setDeviceId(DEVICE_ID)
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(asEntity(valid)))
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(asEntity(invalid)))
        .build()
    ignoreGrpcStatus { fourward.stub.write(req) }
    ignoreGrpcStatus { bmv2.stub.write(req) }
    assertReadAgrees()
  }

  // Previously @Ignore'd: 4ward included defaults in wildcard reads, BMv2 didn't.
  // Resolved per §9.1.6.
  @Test
  fun `5 — default action modify — both servers read it back identically`() {
    val defaultEntry =
      TableEntry.newBuilder()
        .setTableId(schema.tableId)
        .setIsDefaultAction(true)
        .setAction(forwardAction(port = 7))
        .build()
    writeOnBoth(Update.Type.MODIFY, defaultEntry)
    assertReadAgrees()
    assertDefaultReadAgrees()
  }

  // ===========================================================================
  // Scenario 6: table entry error codes
  // ===========================================================================

  @Test
  fun `6 — error semantics — DELETE non-existent, INSERT duplicate, MODIFY non-existent`() {
    val entry = exactEntry(ByteString.copyFrom(byteArrayOf(0x08, 0x00)), port = 1)

    // DELETE a non-existent entry — both should reject.
    expectStatusFrom { writeUpdate(fourward, Update.Type.DELETE, entry) }
    expectStatusFrom { writeUpdate(bmv2, Update.Type.DELETE, entry) }

    // INSERT then INSERT duplicate — both should reject the second.
    writeOnBoth(Update.Type.INSERT, entry)
    expectStatusFrom { writeUpdate(fourward, Update.Type.INSERT, entry) }
    expectStatusFrom { writeUpdate(bmv2, Update.Type.INSERT, entry) }

    // After the duplicate failure, the original entry should still be readable.
    assertReadAgrees()

    // DELETE it, then MODIFY — both should reject the MODIFY.
    writeOnBoth(Update.Type.DELETE, entry)
    expectStatusFrom { writeUpdate(fourward, Update.Type.MODIFY, entry) }
    expectStatusFrom { writeUpdate(bmv2, Update.Type.MODIFY, entry) }
  }

  // ===========================================================================
  // Scenario 7: wildcard reads across multiple entries
  // ===========================================================================

  @Test
  fun `7 — wildcard read with multiple entries — both servers agree on content`() {
    val entries =
      listOf(0x0800, 0x0806, 0x86DD).map { etherType ->
        exactEntry(
          ByteString.copyFrom(byteArrayOf((etherType shr 8).toByte(), etherType.toByte())),
          port = 1,
        )
      }
    for (entry in entries) writeOnBoth(Update.Type.INSERT, entry)
    assertReadAgrees()
  }

  // ===========================================================================
  // Scenario 11: PRE multicast group CRUD/read-back
  // ===========================================================================

  @Test
  fun `11 — PRE multicast group CRUD — both servers agree on read-back`() {
    val group = multicastGroupEntity(groupId = 1, replicas = listOf(1 to 101, 2 to 102))
    writeEntityOnBoth(Update.Type.INSERT, group)
    assertMulticastReadAgrees()

    val modified = multicastGroupEntity(groupId = 1, replicas = listOf(3 to 201))
    writeEntityOnBoth(Update.Type.MODIFY, modified)
    assertMulticastReadAgrees()

    writeEntityOnBoth(Update.Type.DELETE, modified)
    assertMulticastReadAgrees()
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun writeOnBoth(type: Update.Type, entry: TableEntry) {
    writeUpdate(fourward, type, entry)
    writeUpdate(bmv2, type, entry)
  }

  private fun writeUpdate(runner: P4RuntimeRunner, type: Update.Type, entry: TableEntry) {
    writeRawEntity(runner, type, asEntity(entry))
  }

  private fun writeEntityOnBoth(type: Update.Type, entity: Entity) {
    writeRawEntity(fourward, type, entity)
    writeRawEntity(bmv2, type, entity)
  }

  private fun writeRawEntity(runner: P4RuntimeRunner, type: Update.Type, entity: Entity) {
    val req =
      WriteRequest.newBuilder()
        .setDeviceId(DEVICE_ID)
        .addUpdates(Update.newBuilder().setType(type).setEntity(entity))
        .build()
    runner.stub.write(req)
  }

  private fun assertReadAgrees() = assertReadAgrees(wildcardTableReadRequest())

  private fun assertDefaultReadAgrees() = assertReadAgrees(defaultTableReadRequest())

  private fun assertMulticastReadAgrees() = assertReadAgrees(multicastReadRequest())

  private fun assertReadAgrees(req: ReadRequest) {
    assertProtosEqual(
      canonicalizeReadResponse(readAll(fourward, req)),
      canonicalizeReadResponse(readAll(bmv2, req)),
      leftLabel = "4ward",
      rightLabel = "bmv2",
    )
  }

  private fun wildcardTableReadRequest(): ReadRequest =
    ReadRequest.newBuilder()
      .setDeviceId(DEVICE_ID)
      .addEntities(
        Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(schema.tableId))
      )
      .build()

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

  private fun multicastReadRequest(): ReadRequest =
    ReadRequest.newBuilder()
      .setDeviceId(DEVICE_ID)
      .addEntities(
        Entity.newBuilder()
          .setPacketReplicationEngineEntry(
            PacketReplicationEngineEntry.newBuilder()
              .setMulticastGroupEntry(MulticastGroupEntry.getDefaultInstance())
          )
      )
      .build()

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

  private inline fun ignoreGrpcStatus(block: () -> Unit) {
    try {
      block()
    } catch (_: StatusRuntimeException) {}
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

  private fun deleteAllMulticastGroups(runner: P4RuntimeRunner) {
    val deletes =
      readAll(runner, multicastReadRequest()).entitiesList.map { entity ->
        Update.newBuilder().setType(Update.Type.DELETE).setEntity(entity).build()
      }
    if (deletes.isEmpty()) return
    ignoreGrpcStatus {
      runner.stub.write(
        WriteRequest.newBuilder().setDeviceId(DEVICE_ID).addAllUpdates(deletes).build()
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Class-scoped fixture
  // ---------------------------------------------------------------------------

  /** P4Info IDs the scenarios need, discovered once from the loaded pipeline. */
  data class TableSchema(
    val tableId: Int,
    val matchFieldId: Int,
    val forwardActionId: Int,
    val forwardParamId: Int,
  ) {
    companion object {
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
