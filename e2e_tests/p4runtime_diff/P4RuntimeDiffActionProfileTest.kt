package fourward.e2e.p4runtimediff

import com.google.protobuf.ByteString
import fourward.bazel.repoRoot
import fourward.stf.loadPipelineConfig
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.ActionProfileGroup
import p4.v1.P4RuntimeOuterClass.ActionProfileMember
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
 * P4Runtime differential scenarios for action profiles, using the action_selector_3 fixture. Same
 * pattern as [P4RuntimeDiffScenariosTest] but with its own server pair and pipeline.
 */
class P4RuntimeDiffActionProfileTest {

  @Before
  fun resetState() {
    deleteAllState(fourward)
    deleteAllState(bmv2)
  }

  @Test
  fun `8 — action profile member CRUD — write, read, delete, read`() {
    val member = memberEntity(memberId = 1, port = 3)
    writeEntityOnBoth(Update.Type.INSERT, member)
    assertMemberReadAgrees()

    writeEntityOnBoth(Update.Type.DELETE, member)
    assertMemberReadAgrees()
  }

  @Test
  fun `9 — action profile group — create group with members, read back`() {
    writeEntityOnBoth(Update.Type.INSERT, memberEntity(memberId = 1, port = 1))
    writeEntityOnBoth(Update.Type.INSERT, memberEntity(memberId = 2, port = 2))
    writeEntityOnBoth(Update.Type.INSERT, memberEntity(memberId = 3, port = 3))

    val group = groupEntity(groupId = 1, memberIds = listOf(1, 2, 3))
    writeEntityOnBoth(Update.Type.INSERT, group)
    assertGroupReadAgrees()
  }

  @Test
  fun `10 — table entry referencing action profile group — read back agrees`() {
    writeEntityOnBoth(Update.Type.INSERT, memberEntity(memberId = 1, port = 1))
    writeEntityOnBoth(Update.Type.INSERT, memberEntity(memberId = 2, port = 2))

    val group = groupEntity(groupId = 1, memberIds = listOf(1, 2))
    writeEntityOnBoth(Update.Type.INSERT, group)

    val tableEntry =
      TableEntry.newBuilder()
        .setTableId(schema.tableId)
        .addMatch(
          FieldMatch.newBuilder()
            .setFieldId(schema.matchFieldId)
            .setExact(
              FieldMatch.Exact.newBuilder()
                .setValue(ByteString.copyFrom(byteArrayOf(0, 0, 0, 0, 0, 1)))
            )
        )
        .setAction(TableAction.newBuilder().setActionProfileGroupId(1))
        .build()
    writeOnBoth(Update.Type.INSERT, tableEntry)
    assertTableReadAgrees()
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun writeOnBoth(type: Update.Type, entry: TableEntry) {
    val entity = Entity.newBuilder().setTableEntry(entry).build()
    writeRawEntity(fourward, type, entity)
    writeRawEntity(bmv2, type, entity)
  }

  private fun writeEntityOnBoth(type: Update.Type, entity: Entity) {
    writeRawEntity(fourward, type, entity)
    writeRawEntity(bmv2, type, entity)
  }

  private fun writeRawEntity(runner: P4RuntimeRunner, type: Update.Type, entity: Entity) {
    runner.stub.write(
      WriteRequest.newBuilder()
        .setDeviceId(DEVICE_ID)
        .addUpdates(Update.newBuilder().setType(type).setEntity(entity))
        .build()
    )
  }

  private fun assertTableReadAgrees() = assertReadAgrees(tableReadRequest())

  private fun assertMemberReadAgrees() = assertReadAgrees(memberReadRequest())

  private fun assertGroupReadAgrees() = assertReadAgrees(groupReadRequest())

  private fun assertReadAgrees(req: ReadRequest) {
    assertProtosEqual(
      canonicalizeReadResponse(readAll(fourward, req)),
      canonicalizeReadResponse(readAll(bmv2, req)),
      leftLabel = "4ward",
      rightLabel = "bmv2",
    )
  }

  private fun tableReadRequest(): ReadRequest =
    ReadRequest.newBuilder()
      .setDeviceId(DEVICE_ID)
      .addEntities(
        Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(schema.tableId))
      )
      .build()

  private fun memberReadRequest(): ReadRequest =
    ReadRequest.newBuilder()
      .setDeviceId(DEVICE_ID)
      .addEntities(
        Entity.newBuilder()
          .setActionProfileMember(
            ActionProfileMember.newBuilder().setActionProfileId(schema.profileId)
          )
      )
      .build()

  private fun groupReadRequest(): ReadRequest =
    ReadRequest.newBuilder()
      .setDeviceId(DEVICE_ID)
      .addEntities(
        Entity.newBuilder()
          .setActionProfileGroup(
            ActionProfileGroup.newBuilder().setActionProfileId(schema.profileId)
          )
      )
      .build()

  private fun readAll(runner: P4RuntimeRunner, req: ReadRequest): ReadResponse {
    val builder = ReadResponse.newBuilder()
    val stream = runner.stub.read(req)
    while (stream.hasNext()) builder.addAllEntities(stream.next().entitiesList)
    return builder.build()
  }

  private fun memberEntity(memberId: Int, port: Int): Entity =
    Entity.newBuilder()
      .setActionProfileMember(
        ActionProfileMember.newBuilder()
          .setActionProfileId(schema.profileId)
          .setMemberId(memberId)
          .setAction(
            Action.newBuilder()
              .setActionId(schema.setPortActionId)
              .addParams(
                Action.Param.newBuilder()
                  .setParamId(schema.setPortParamId)
                  .setValue(ByteString.copyFrom(byteArrayOf(0, port.toByte())))
              )
          )
      )
      .build()

  private fun groupEntity(groupId: Int, memberIds: List<Int>): Entity =
    Entity.newBuilder()
      .setActionProfileGroup(
        ActionProfileGroup.newBuilder()
          .setActionProfileId(schema.profileId)
          .setGroupId(groupId)
          .setMaxSize(memberIds.size)
          .addAllMembers(
            memberIds.map { mid ->
              ActionProfileGroup.Member.newBuilder().setMemberId(mid).setWeight(1).build()
            }
          )
      )
      .build()

  /** Deletes table entries, then groups, then members — order matters for referential integrity. */
  private fun deleteAllState(runner: P4RuntimeRunner) {
    val deletes = mutableListOf<Update>()
    for (entity in readAll(runner, tableReadRequest()).entitiesList) {
      deletes.add(Update.newBuilder().setType(Update.Type.DELETE).setEntity(entity).build())
    }
    for (entity in readAll(runner, groupReadRequest()).entitiesList) {
      deletes.add(Update.newBuilder().setType(Update.Type.DELETE).setEntity(entity).build())
    }
    for (entity in readAll(runner, memberReadRequest()).entitiesList) {
      deletes.add(Update.newBuilder().setType(Update.Type.DELETE).setEntity(entity).build())
    }
    if (deletes.isEmpty()) return
    runner.stub.write(
      WriteRequest.newBuilder().setDeviceId(DEVICE_ID).addAllUpdates(deletes).build()
    )
  }

  // ---------------------------------------------------------------------------
  // Class-scoped fixture
  // ---------------------------------------------------------------------------

  data class SelectorSchema(
    val tableId: Int,
    val matchFieldId: Int,
    val profileId: Int,
    val setPortActionId: Int,
    val setPortParamId: Int,
  ) {
    companion object {
      fun discover(p4Info: P4Info): SelectorSchema {
        val profile = p4Info.actionProfilesList.single()
        val table = p4Info.tablesList.single { it.implementationId == profile.preamble.id }
        val exactField =
          table.matchFieldsList.single {
            it.matchType == p4.config.v1.P4InfoOuterClass.MatchField.MatchType.EXACT
          }
        val actionsById = p4Info.actionsList.associateBy { it.preamble.id }
        val setPort =
          table.actionRefsList.mapNotNull { actionsById[it.id] }.single { it.paramsCount == 1 }
        return SelectorSchema(
          tableId = table.preamble.id,
          matchFieldId = exactField.id,
          profileId = profile.preamble.id,
          setPortActionId = setPort.preamble.id,
          setPortParamId = setPort.paramsList.first().id,
        )
      }
    }
  }

  companion object {
    private const val DEVICE_ID = 1L
    private const val SET_CONFIG_TIMEOUT_S = 30L

    private lateinit var fourward: FourwardP4RuntimeRunner
    private lateinit var bmv2: Bmv2P4RuntimeRunner
    private lateinit var schema: SelectorSchema

    @BeforeClass
    @JvmStatic
    fun spawnServers() {
      val pkg = "e2e_tests/p4runtime_diff"
      val binary = repoRoot.resolve("$pkg/simple_switch_grpc")
      val fourwardPath = repoRoot.resolve("$pkg/action_selector_fourward.txtpb")
      val bmv2JsonPath = repoRoot.resolve("$pkg/action_selector.json")
      Assume.assumeTrue(
        "simple_switch_grpc binary or P4 fixtures missing — see e2e_tests/p4runtime_diff/README.md",
        Files.exists(binary) && Files.exists(fourwardPath) && Files.exists(bmv2JsonPath),
      )

      val fourwardPipeline = loadPipelineConfig(fourwardPath)
      val p4Info = fourwardPipeline.p4Info
      schema = SelectorSchema.discover(p4Info)

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
