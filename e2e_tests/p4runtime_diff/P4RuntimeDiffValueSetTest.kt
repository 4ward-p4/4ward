package fourward.e2e.p4runtimediff

import com.google.protobuf.ByteString
import fourward.bazel.repoRoot
import fourward.stf.loadPipelineConfig
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import p4.config.v1.P4InfoOuterClass.MatchField
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.ValueSetEntry
import p4.v1.P4RuntimeOuterClass.ValueSetMember
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * P4Runtime ValueSetEntry oracle-gap coverage, using a minimal v1model parser fixture.
 *
 * P4Runtime §9.6 allows MODIFY and read, while INSERT and DELETE are invalid operations. 4ward
 * implements that surface. BMv2's simple_switch_grpc path cannot be used as an oracle here because
 * p4lang/PI's DeviceMgr returns "ValueSet reads/writes are not supported yet" for ValueSetEntry.
 */
class P4RuntimeDiffValueSetTest {

  @Before
  fun resetState() {
    writeEntity(fourward, Update.Type.MODIFY, valueSetEntity(members = emptyList()))
  }

  @Test
  fun `12 — ValueSetEntry MODIFY and read-back — 4ward supports, BMv2 cannot oracle`() {
    val first =
      valueSetEntity(
        members = listOf(exactMember(0x0800), exactMember(0x0806), exactMember(0x86DD))
      )
    writeEntity(fourward, Update.Type.MODIFY, first)
    assertValueSetReadEquals(first)

    val second = valueSetEntity(members = listOf(exactMember(0x8847)))
    writeEntity(fourward, Update.Type.MODIFY, second)
    assertValueSetReadEquals(second)

    val empty = valueSetEntity(members = emptyList())
    writeEntity(fourward, Update.Type.MODIFY, empty)
    assertValueSetReadEquals(empty)

    assertEquals(Status.Code.UNIMPLEMENTED, writeStatus(bmv2, Update.Type.MODIFY, first))
    assertEquals(Status.Code.UNIMPLEMENTED, readStatus(bmv2, valueSetReadRequest()))
  }

  @Test
  fun `13 — ValueSetEntry INSERT and DELETE — 4ward rejects, BMv2 cannot oracle`() {
    val entry = valueSetEntity(members = listOf(exactMember(0x0800)))
    assertEquals(Status.Code.INVALID_ARGUMENT, writeStatus(fourward, Update.Type.INSERT, entry))
    assertEquals(Status.Code.INVALID_ARGUMENT, writeStatus(fourward, Update.Type.DELETE, entry))
    assertEquals(Status.Code.UNIMPLEMENTED, writeStatus(bmv2, Update.Type.INSERT, entry))
    assertEquals(Status.Code.UNIMPLEMENTED, writeStatus(bmv2, Update.Type.DELETE, entry))
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun writeEntity(runner: P4RuntimeRunner, type: Update.Type, entity: Entity) {
    runner.stub.write(
      WriteRequest.newBuilder()
        .setDeviceId(DEVICE_ID)
        .addUpdates(Update.newBuilder().setType(type).setEntity(entity))
        .build()
    )
  }

  private fun writeStatus(runner: P4RuntimeRunner, type: Update.Type, entity: Entity): Status.Code =
    try {
      writeEntity(runner, type, entity)
      Status.Code.OK
    } catch (e: StatusRuntimeException) {
      e.singleBatchItemStatusCode() ?: e.status.code
    }

  private fun readStatus(runner: P4RuntimeRunner, req: ReadRequest): Status.Code =
    try {
      readAll(runner, req)
      Status.Code.OK
    } catch (e: StatusRuntimeException) {
      e.status.code
    }

  private fun assertValueSetReadEquals(entity: Entity) {
    val response = readAll(fourward, valueSetReadRequest())
    assertEquals(1, response.entitiesCount)
    assertEquals(entity, response.getEntities(0))
  }

  private fun valueSetReadRequest(): ReadRequest =
    ReadRequest.newBuilder()
      .setDeviceId(DEVICE_ID)
      .addEntities(
        Entity.newBuilder().setValueSetEntry(ValueSetEntry.newBuilder().setValueSetId(schema.id))
      )
      .build()

  private fun readAll(runner: P4RuntimeRunner, req: ReadRequest): ReadResponse {
    val builder = ReadResponse.newBuilder()
    val stream = runner.stub.read(req)
    while (stream.hasNext()) builder.addAllEntities(stream.next().entitiesList)
    return builder.build()
  }

  private fun valueSetEntity(members: List<ValueSetMember>): Entity =
    Entity.newBuilder()
      .setValueSetEntry(ValueSetEntry.newBuilder().setValueSetId(schema.id).addAllMembers(members))
      .build()

  private fun exactMember(value: Int): ValueSetMember =
    ValueSetMember.newBuilder()
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(schema.matchFieldId)
          .setExact(
            FieldMatch.Exact.newBuilder()
              .setValue(ByteString.copyFrom(byteArrayOf((value shr 8).toByte(), value.toByte())))
          )
      )
      .build()

  // ---------------------------------------------------------------------------
  // Class-scoped fixture
  // ---------------------------------------------------------------------------

  data class ValueSetSchema(val id: Int, val matchFieldId: Int) {
    companion object {
      fun discover(p4Info: P4Info): ValueSetSchema {
        val valueSet = p4Info.valueSetsList.single()
        val matchField = valueSet.matchList.single()
        require(matchField.bitwidth == 16) {
          "expected 16-bit value_set match field; got ${matchField.bitwidth}"
        }
        require(matchField.matchType == MatchField.MatchType.EXACT) {
          "expected EXACT value_set match field; got ${matchField.matchType}"
        }
        return ValueSetSchema(id = valueSet.preamble.id, matchFieldId = matchField.id)
      }
    }
  }

  companion object {
    private const val DEVICE_ID = 1L
    private const val SET_CONFIG_TIMEOUT_S = 30L

    private lateinit var fourward: FourwardP4RuntimeRunner
    private lateinit var bmv2: Bmv2P4RuntimeRunner
    private lateinit var schema: ValueSetSchema

    @BeforeClass
    @JvmStatic
    fun spawnServers() {
      val pkg = "e2e_tests/p4runtime_diff"
      val binary = repoRoot.resolve("$pkg/simple_switch_grpc")
      val fourwardPath = repoRoot.resolve("$pkg/value_set_fourward.txtpb")
      val bmv2JsonPath = repoRoot.resolve("$pkg/value_set.json")
      Assume.assumeTrue(
        "simple_switch_grpc binary or P4 fixtures missing — see e2e_tests/p4runtime_diff/README.md",
        Files.exists(binary) && Files.exists(fourwardPath) && Files.exists(bmv2JsonPath),
      )

      val fourwardPipeline = loadPipelineConfig(fourwardPath)
      val p4Info = fourwardPipeline.p4Info
      schema = ValueSetSchema.discover(p4Info)

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
