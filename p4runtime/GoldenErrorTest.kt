// `val unused = stub.X(...)` is the documented way to discard return values from gRPC stubs that
// the downstream sync's `@CheckReturnValue` lint flags as must-be-used. Detekt's
// `UnusedPrivateProperty` then complains about the local `unused` — suppress it here.
@file:Suppress("UnusedPrivateProperty")

package fourward.p4runtime

import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.ByteString
import fourward.ir.Architecture
import fourward.ir.BehavioralConfig
import fourward.ir.DeviceConfig
import fourward.ir.PipelineConfig
import fourward.ir.TypeTranslation
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildExactEntry
import fourward.p4runtime.P4RuntimeTestHarness.Companion.extractBatchErrors
import fourward.p4runtime.P4RuntimeTestHarness.Companion.findAction
import fourward.p4runtime.P4RuntimeTestHarness.Companion.findTable
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.longToBytes
import fourward.p4runtime.P4RuntimeTestHarness.Companion.matchFieldId
import fourward.p4runtime.P4RuntimeTestHarness.Companion.paramId
import fourward.p4runtime.P4RuntimeTestHarness.Companion.uint128
import io.grpc.Status
import io.grpc.StatusException
import java.nio.file.Path
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * Golden tests for P4Runtime error messages.
 *
 * Each test triggers a specific error path and compares the full gRPC error description against a
 * golden file. This catches unintentional changes to user-facing error messages.
 *
 * Set `UPDATE_GOLDEN=1` to regenerate golden files from actual output.
 */
@RunWith(Parameterized::class)
class GoldenErrorTest(private val testName: String) {

  private lateinit var harness: P4RuntimeTestHarness

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  @Test
  fun test() {
    val actual = captureError { triggerError(testName) }
    val goldenPath = goldenDir().resolve("$testName.golden.txt")

    if (System.getenv("UPDATE_GOLDEN") != null) {
      goldenPath.toFile().parentFile.mkdirs()
      goldenPath.toFile().writeText(actual + "\n")
      // Also print to stdout so the message can be captured from test logs.
      println("GOLDEN[$testName]: $actual")
      return
    }

    val expected = goldenPath.toFile().readText().trim()
    if (expected != actual.trim()) {
      fail(
        "Error message mismatch for '$testName'.\n\n" +
          "Expected (golden):\n  $expected\n\n" +
          "Actual:\n  $actual\n\n" +
          "To update, run:\n" +
          "  bazel test ${System.getenv("TEST_TARGET") ?: "<test target>"}" +
          " --test_env=UPDATE_GOLDEN=1"
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Error triggers
  // ---------------------------------------------------------------------------

  @Suppress("CyclomaticComplexMethod")
  private fun triggerError(name: String) {
    when (name) {
      "no-pipeline-loaded" -> triggerNoPipelineLoaded()
      "unknown-table-id" -> triggerUnknownTableId()
      "unknown-action-id" -> triggerUnknownActionId()
      "wrong-param-count" -> triggerWrongParamCount()
      "match-value-out-of-range" -> triggerMatchValueOutOfRange()
      "wrong-match-kind" -> triggerWrongMatchKind()
      "missing-exact-field" -> triggerMissingExactField()
      "duplicate-match-field" -> triggerDuplicateMatchField()
      "const-table-write" -> triggerConstTableWrite()
      "batch-write-failure" -> triggerBatchWriteFailure()
      "invalid-device-config" -> triggerInvalidDeviceConfig()
      "missing-pipeline-config" -> triggerMissingPipelineConfig()
      "wrong-device-id" -> triggerWrongDeviceId()
      "not-primary-controller" -> triggerNotPrimaryController()
      "digest-not-supported" -> triggerDigestNotSupported()
      "extern-entry-not-supported" -> triggerExternEntryNotSupported()
      "unrecognized-atomicity" -> triggerUnrecognizedAtomicity()
      "idle-timeout-not-supported" -> triggerIdleTimeoutNotSupported()
      "default-entry-wrong-type" -> triggerDefaultEntryWrongType()
      "default-entry-with-match" -> triggerDefaultEntryWithMatch()
      "unknown-param-id" -> triggerUnknownParamId()
      "unknown-match-field-id" -> triggerUnknownMatchFieldId()
      "match-field-no-value" -> triggerMatchFieldNoValue()
      "priority-must-be-zero" -> triggerPriorityMustBeZero()
      "table-entry-already-exists" -> triggerTableEntryAlreadyExists()
      "table-entry-not-found" -> triggerTableEntryNotFound()
      "commit-without-save" -> triggerCommitWithoutSave()
      "unrecognized-pipeline-action" -> triggerUnrecognizedPipelineAction()
      "simulator-rejected-pipeline" -> triggerSimulatorRejectedPipeline()
      "param-value-out-of-range" -> triggerParamValueOutOfRange()
      "priority-required" -> triggerPriorityRequired()
      "ternary-masked-bits" -> triggerTernaryMaskedBits()
      "lpm-trailing-bits" -> triggerLpmTrailingBits()
      "counter-insert-not-supported" -> triggerCounterInsertNotSupported()
      "meter-insert-not-supported" -> triggerMeterInsertNotSupported()
      "register-insert-not-supported" -> triggerRegisterInsertNotSupported()
      "pre-entry-missing-type" -> triggerPreEntryMissingType()
      "clone-session-already-exists" -> triggerCloneSessionAlreadyExists()
      "clone-session-not-found" -> triggerCloneSessionNotFound()
      "multicast-group-already-exists" -> triggerMulticastGroupAlreadyExists()
      "multicast-group-not-found" -> triggerMulticastGroupNotFound()
      "unknown-register-id" -> triggerUnknownRegisterId()
      "unknown-counter-id" -> triggerUnknownCounterId()
      "unknown-meter-id" -> triggerUnknownMeterId()
      "direct-counter-insert-not-supported" -> triggerDirectCounterInsertNotSupported()
      "direct-counter-unknown-table" -> triggerDirectCounterUnknownTable()
      "direct-meter-insert-not-supported" -> triggerDirectMeterInsertNotSupported()
      "direct-meter-unknown-table" -> triggerDirectMeterUnknownTable()
      "register-index-out-of-bounds" -> triggerRegisterIndexOutOfBounds()
      "counter-index-out-of-bounds" -> triggerCounterIndexOutOfBounds()
      "meter-index-out-of-bounds" -> triggerMeterIndexOutOfBounds()
      "range-low-greater-than-high" -> triggerRangeLowGreaterThanHigh()
      "value-set-full" -> triggerValueSetFull()
      "action-not-valid-for-profile" -> triggerActionNotValidForProfile()
      "table-full" -> triggerTableFull()
      "action-profile-at-capacity" -> triggerActionProfileAtCapacity()
      "action-profile-group-max-size" -> triggerActionProfileGroupMaxSize()
      "value-set-insert-not-supported" -> triggerValueSetInsertNotSupported()
      "unknown-value-set-id" -> triggerUnknownValueSetId()
      "role-config-not-supported" -> triggerRoleConfigNotSupported()
      "unrecognized-response-type" -> triggerUnrecognizedResponseType()
      "reconcile-table-absent" -> triggerReconcileTableAbsent()
      "digest-stream-not-supported" -> triggerDigestStreamNotSupported()
      "unknown-profile-id-member" -> triggerUnknownProfileIdMember()
      "unknown-profile-id-group" -> triggerUnknownProfileIdGroup()
      "unknown-action-in-profile" -> triggerUnknownActionInProfile()
      "action-not-valid-for-table" -> triggerActionNotValidForTable()
      "reconcile-schema-changed" -> triggerReconcileSchemaChanged()
      "direct-counter-no-entries" -> triggerDirectCounterNoEntries()
      "direct-counter-no-match" -> triggerDirectCounterNoMatch()
      "constraint-validation-failed" -> triggerConstraintValidationFailed()
      "refers-to-violation-no-entry" -> triggerRefersToViolationNoEntry()
      "refers-to-violation-no-multicast" -> triggerRefersToViolationNoMulticast()
      "type-translation-failed" -> triggerTypeTranslationFailed()
      "wrong-role-write" -> triggerWrongRoleWrite()
      "inject-packet-no-pipeline" -> triggerInjectPacketNoPipeline()
      "inject-packet-no-port-translation" -> triggerInjectPacketNoPortTranslation()
      "inject-packet-simulator-throws" -> triggerInjectPacketSimulatorThrows()
      "inject-packet-port-overflow" -> triggerInjectPacketPortOverflow()
      else -> error("unknown test: $name")
    }
  }

  private fun triggerInjectPacketSimulatorThrows() {
    // Bypasses the live harness and drives DataplaneService with a broker whose
    // simulator unconditionally throws, to pin the exception-to-gRPC-status
    // translation (see #499). The real benchmark trigger — 4000 routes worth of
    // entries and a trace proto over gRPC's size limit — is too expensive to
    // reproduce here; what this golden protects is the *translation format*.
    val throwingBroker =
      PacketBroker(
        simulatorFn = { _, _ -> throw ArithmeticException("simulated failure") },
        writeMutex = kotlinx.coroutines.sync.Mutex(),
      )
    val service = DataplaneService(throwingBroker)
    val request =
      fourward.dataplane.InjectPacketRequest.newBuilder()
        .setDataplaneIngressPort(0)
        .setPayload(ByteString.copyFrom(byteArrayOf(0x01)))
        .build()
    runBlocking { service.injectPacket(request) }
  }

  private fun triggerNoPipelineLoaded() {
    val entity = Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(1)).build()
    harness.installEntry(entity)
  }

  private fun triggerInjectPacketNoPipeline() {
    val p4rtPort = ByteString.copyFrom(byteArrayOf(0, 0, 0, 1))
    harness.injectPacketP4rt(p4rtPort, byteArrayOf(0x01))
  }

  @Suppress("MagicNumber")
  private fun triggerInjectPacketPortOverflow() {
    // v1model's ingress_port is bit<9> (max 511). Injecting port 512 triggers
    // a setBitField overflow in the simulator.
    harness.loadPipeline(loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.injectPacket(512, P4RuntimeTestHarness.buildEthernetFrame(0x0800))
  }

  private fun triggerInjectPacketNoPortTranslation() {
    // Passthrough's port type has no @p4runtime_translation, so p4rt_ingress_port
    // hits the "pipeline loaded but no translation" branch — distinct from the
    // no-pipeline branch above.
    harness.loadPipeline(loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    val p4rtPort = ByteString.copyFrom(byteArrayOf(0, 0, 0, 1))
    harness.injectPacketP4rt(p4rtPort, byteArrayOf(0x01))
  }

  private fun triggerUnknownTableId() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(99999)).build()
    harness.installEntry(entity)
  }

  private fun triggerUnknownActionId() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity =
      valid
        .toBuilder()
        .apply { tableEntryBuilder.actionBuilder.actionBuilder.setActionId(99999).clearParams() }
        .build()
    harness.installEntry(entity)
  }

  private fun triggerWrongParamCount() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity =
      valid
        .toBuilder()
        .apply { tableEntryBuilder.actionBuilder.actionBuilder.clearParams() }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerMatchValueOutOfRange() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // etherType is 16-bit; send a 3-byte value 0x010000 that overflows.
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder
            .getMatchBuilder(0)
            .exactBuilder
            .setValue(ByteString.copyFrom(byteArrayOf(0x01, 0x00, 0x00)))
        }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerWrongMatchKind() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val matchField = config.p4Info.tablesList.first().matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder.setPriority(1)
          tableEntryBuilder
            .getMatchBuilder(0)
            .clearExact()
            .setFieldId(matchField.id)
            .ternaryBuilder
            .setValue(ByteString.copyFrom(longToBytes(0x0800, byteWidth)))
            .setMask(ByteString.copyFrom(longToBytes(0xFFFF, byteWidth)))
        }
        .build()
    harness.installEntry(entity)
  }

  private fun triggerMissingExactField() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity = valid.toBuilder().apply { tableEntryBuilder.clearMatch() }.build()
    harness.installEntry(entity)
  }

  private fun triggerDuplicateMatchField() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Add the same match field a second time.
    val entity =
      valid.toBuilder().apply { tableEntryBuilder.addMatch(valid.tableEntry.getMatch(0)) }.build()
    harness.installEntry(entity)
  }

  private fun triggerConstTableWrite() {
    val config = loadConfig("e2e_tests/trace_tree/clone_with_egress.txtpb")
    harness.loadPipeline(config)
    val constTable = config.p4Info.tablesList.find { it.isConstTable }!!
    val entity =
      Entity.newBuilder()
        .setTableEntry(TableEntry.newBuilder().setTableId(constTable.preamble.id))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerBatchWriteFailure() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val good = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val bad = Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(99999)).build()
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(good, bad))
    harness.writeRaw(request)
  }

  private fun triggerInvalidDeviceConfig() = runBlocking {
    val unused =
      harness.stub.setForwardingPipelineConfig(
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
          .setConfig(
            ForwardingPipelineConfig.newBuilder()
              .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
              .setP4DeviceConfig(ByteString.copyFromUtf8("garbage"))
          )
          .build()
      )
  }

  private fun triggerMissingPipelineConfig() = runBlocking {
    val unused =
      harness.stub.setForwardingPipelineConfig(
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
          .setConfig(
            ForwardingPipelineConfig.newBuilder()
              .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
          )
          .build()
      )
  }

  @Suppress("MagicNumber")
  private fun triggerWrongDeviceId() {
    harness.loadPipeline(loadBasicTable())
    val request =
      WriteRequest.newBuilder()
        .setDeviceId(999)
        .addUpdates(
          Update.newBuilder()
            .setType(Update.Type.INSERT)
            .setEntity(
              Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(1)).build()
            )
        )
        .build()
    harness.writeRaw(request)
  }

  private fun triggerNotPrimaryController() {
    harness.loadPipeline(loadBasicTable())
    // Establish a primary with election_id=5, then attempt a write with a lower election_id.
    harness.openStream().use { stream -> stream.arbitrate(electionId = 5) }
    val entry = buildExactEntry(loadBasicTable(), matchValue = 0x0800, port = 1)
    harness.installEntry(entry, uint128(low = 3))
  }

  private fun triggerDigestNotSupported() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setDigestEntry(P4RuntimeOuterClass.DigestEntry.newBuilder().setDigestId(1))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerExternEntryNotSupported() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setExternEntry(P4RuntimeOuterClass.ExternEntry.newBuilder().setExternTypeId(1))
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnrecognizedAtomicity() {
    harness.loadPipeline(loadBasicTable())
    val request =
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .setAtomicityValue(999)
        .addUpdates(
          Update.newBuilder()
            .setType(Update.Type.INSERT)
            .setEntity(
              Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(1)).build()
            )
        )
        .build()
    harness.writeRaw(request)
  }

  @Suppress("MagicNumber")
  private fun triggerIdleTimeoutNotSupported() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity = valid.toBuilder().apply { tableEntryBuilder.setIdleTimeoutNs(1000) }.build()
    harness.installEntry(entity)
  }

  private fun triggerDefaultEntryWrongType() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val tableId = config.p4Info.tablesList.first().preamble.id
    val entity =
      Entity.newBuilder()
        .setTableEntry(TableEntry.newBuilder().setTableId(tableId).setIsDefaultAction(true))
        .build()
    // INSERT a default entry (should be MODIFY).
    harness.installEntry(entity)
  }

  private fun triggerDefaultEntryWithMatch() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // MODIFY a default entry that still has match fields set.
    val entity = valid.toBuilder().apply { tableEntryBuilder.setIsDefaultAction(true) }.build()
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownParamId() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Replace the param with one that has the correct count but wrong ID.
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder.actionBuilder.actionBuilder.setParams(
            0,
            valid.tableEntry.action.action.getParams(0).toBuilder().setParamId(99999),
          )
        }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerParamValueOutOfRange() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // The 'port' param is 9-bit (max value 0x1FF); send 0x0200 which overflows.
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder.actionBuilder.actionBuilder.setParams(
            0,
            valid.tableEntry.action.action
              .getParams(0)
              .toBuilder()
              .setValue(ByteString.copyFrom(byteArrayOf(0x02, 0x00))),
          )
        }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownMatchFieldId() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity =
      valid.toBuilder().apply { tableEntryBuilder.getMatchBuilder(0).setFieldId(99999) }.build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerMatchFieldNoValue() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val matchField = config.p4Info.tablesList.first().matchFieldsList.first()
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Set a FieldMatch with only field_id — no exact/ternary/lpm/etc.
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder.setMatch(
            0,
            P4RuntimeOuterClass.FieldMatch.newBuilder().setFieldId(matchField.id),
          )
        }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerPriorityMustBeZero() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Exact-match table: priority must be zero.
    val entity = valid.toBuilder().apply { tableEntryBuilder.setPriority(42) }.build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerTableEntryAlreadyExists() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    // INSERT the same entry again.
    harness.installEntry(entry)
  }

  @Suppress("MagicNumber")
  private fun triggerTableEntryNotFound() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // DELETE an entry that was never inserted.
    harness.deleteEntry(entry)
  }

  private fun triggerCommitWithoutSave() = runBlocking {
    val unused =
      harness.stub.setForwardingPipelineConfig(
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setAction(SetForwardingPipelineConfigRequest.Action.COMMIT)
          .build()
      )
  }

  private fun triggerUnrecognizedPipelineAction() = runBlocking {
    val unused =
      harness.stub.setForwardingPipelineConfig(
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setAction(SetForwardingPipelineConfigRequest.Action.UNSPECIFIED)
          .build()
      )
  }

  private fun triggerSimulatorRejectedPipeline() {
    // Build a DeviceConfig with an unsupported architecture name. The proto parses fine
    // but the simulator rejects it at loadPipeline time.
    val deviceConfig =
      DeviceConfig.newBuilder()
        .setBehavioral(
          BehavioralConfig.newBuilder()
            .setArchitecture(Architecture.newBuilder().setName("bogus_arch"))
        )
        .build()
    val fwdConfig =
      ForwardingPipelineConfig.newBuilder()
        .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
        .setP4DeviceConfig(deviceConfig.toByteString())
        .build()
    runBlocking {
      val unused =
        harness.stub.setForwardingPipelineConfig(
          SetForwardingPipelineConfigRequest.newBuilder()
            .setDeviceId(1)
            .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
            .setConfig(fwdConfig)
            .build()
        )
    }
  }

  @Suppress("MagicNumber")
  private fun triggerPriorityRequired() {
    val config = loadTernaryAcl()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .setPriority(0)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setTernary(
                  P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0x06, byteWidth)))
                    .setMask(ByteString.copyFrom(longToBytes(0xFF, byteWidth)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(forwardAction.paramsList.first().id)
                        .setValue(
                          ByteString.copyFrom(
                            longToBytes(1, (forwardAction.paramsList.first().bitwidth + 7) / 8)
                          )
                        )
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerTernaryMaskedBits() {
    val config = loadTernaryAcl()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    // value=0xFF but mask=0x0F — high nibble has bits set where mask is 0.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .setPriority(1)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setTernary(
                  P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0xFF, byteWidth)))
                    .setMask(ByteString.copyFrom(longToBytes(0x0F, byteWidth)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(forwardAction.paramsList.first().id)
                        .setValue(
                          ByteString.copyFrom(
                            longToBytes(1, (forwardAction.paramsList.first().bitwidth + 7) / 8)
                          )
                        )
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerLpmTrailingBits() {
    val config = loadLpmRouting()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    // 10.0.0.255/24 — the last byte (255) extends beyond the 24-bit prefix.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setLpm(
                  P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0x0A0000FF, byteWidth)))
                    .setPrefixLen(24)
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(forwardAction.paramsList.first().id)
                        .setValue(
                          ByteString.copyFrom(
                            longToBytes(1, (forwardAction.paramsList.first().bitwidth + 7) / 8)
                          )
                        )
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  private fun triggerCounterInsertNotSupported() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val entity =
      Entity.newBuilder()
        .setCounterEntry(P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(1))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerMeterInsertNotSupported() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val entity =
      Entity.newBuilder()
        .setMeterEntry(P4RuntimeOuterClass.MeterEntry.newBuilder().setMeterId(1))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerRegisterInsertNotSupported() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    // INSERT a register entry — registers only support MODIFY.
    val entity =
      Entity.newBuilder()
        .setRegisterEntry(P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(1))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerPreEntryMissingType() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.getDefaultInstance()
        )
        .build()
    harness.installEntry(entity)
  }

  private fun triggerCloneSessionAlreadyExists() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setCloneSessionEntry(
              P4RuntimeOuterClass.CloneSessionEntry.newBuilder().setSessionId(1)
            )
        )
        .build()
    harness.installEntry(entity)
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerCloneSessionNotFound() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setCloneSessionEntry(
              P4RuntimeOuterClass.CloneSessionEntry.newBuilder().setSessionId(999)
            )
        )
        .build()
    harness.deleteEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerMulticastGroupAlreadyExists() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(
              P4RuntimeOuterClass.MulticastGroupEntry.newBuilder().setMulticastGroupId(42)
            )
        )
        .build()
    harness.installEntry(entity)
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerMulticastGroupNotFound() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(
              P4RuntimeOuterClass.MulticastGroupEntry.newBuilder().setMulticastGroupId(999)
            )
        )
        .build()
    harness.deleteEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownRegisterId() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setRegisterEntry(P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(99999))
        .build()
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownCounterId() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setCounterEntry(P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(99999))
        .build()
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownMeterId() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setMeterEntry(P4RuntimeOuterClass.MeterEntry.newBuilder().setMeterId(99999))
        .build()
    harness.modifyEntry(entity)
  }

  private fun triggerDirectCounterInsertNotSupported() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(TableEntry.newBuilder().setTableId(1))
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerDirectCounterUnknownTable() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(TableEntry.newBuilder().setTableId(99999))
        )
        .build()
    harness.modifyEntry(entity)
  }

  private fun triggerDirectMeterInsertNotSupported() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setDirectMeterEntry(
          P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
            .setTableEntry(TableEntry.newBuilder().setTableId(1))
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerDirectMeterUnknownTable() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setDirectMeterEntry(
          P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
            .setTableEntry(TableEntry.newBuilder().setTableId(99999))
        )
        .build()
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerRegisterIndexOutOfBounds() {
    val config = loadStateful()
    harness.loadPipeline(config)
    val registerId = config.p4Info.registersList.first().preamble.id
    val entity = P4RuntimeTestHarness.buildRegisterEntry(registerId, index = 999, value = 0)
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerCounterIndexOutOfBounds() {
    val config = loadStateful()
    harness.loadPipeline(config)
    val counterId = config.p4Info.countersList.first().preamble.id
    val entity = P4RuntimeTestHarness.buildCounterEntry(counterId, index = 999)
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerMeterIndexOutOfBounds() {
    val config = loadStateful()
    harness.loadPipeline(config)
    val meterId = config.p4Info.metersList.first().preamble.id
    val entity = P4RuntimeTestHarness.buildMeterEntry(meterId, index = 999)
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerRangeLowGreaterThanHigh() {
    val config = loadRangeTable()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .setPriority(1)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setRange(
                  P4RuntimeOuterClass.FieldMatch.Range.newBuilder()
                    .setLow(ByteString.copyFrom(longToBytes(0x2000, byteWidth)))
                    .setHigh(ByteString.copyFrom(longToBytes(0x1000, byteWidth)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(forwardAction.paramsList.first().id)
                        .setValue(
                          ByteString.copyFrom(
                            longToBytes(1, (forwardAction.paramsList.first().bitwidth + 7) / 8)
                          )
                        )
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerValueSetFull() {
    val config = loadValueSet()
    harness.loadPipeline(config)
    val vs = config.p4Info.valueSetsList.first()
    // Insert 5 members into a value_set with capacity 4.
    val members =
      (1..5).map { i ->
        P4RuntimeOuterClass.ValueSetMember.newBuilder()
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(vs.matchList.first().id)
              .setExact(
                P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                  .setValue(ByteString.copyFrom(longToBytes(i.toLong(), 2)))
              )
          )
          .build()
      }
    val entity =
      Entity.newBuilder()
        .setValueSetEntry(
          P4RuntimeOuterClass.ValueSetEntry.newBuilder()
            .setValueSetId(vs.preamble.id)
            .addAllMembers(members)
        )
        .build()
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerActionNotValidForProfile() {
    val config = loadActionProfile()
    harness.loadPipeline(config)
    val profile = config.p4Info.actionProfilesList.first()
    // Use 'noop' action which exists in p4info but is not in the profile's table action_refs.
    val noopAction = config.p4Info.actionsList.find { it.preamble.alias == "noop" }!!
    val entity =
      P4RuntimeTestHarness.buildMemberEntity(
        actionProfileId = profile.preamble.id,
        memberId = 1,
        actionId = noopAction.preamble.id,
      )
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerTableFull() {
    val config = loadBasicTable()
    // Patch p4info to set the first table's size to 2.
    val patchedP4Info =
      config.p4Info.toBuilder().apply { setTables(0, getTables(0).toBuilder().setSize(2)) }.build()
    val patchedConfig = config.toBuilder().setP4Info(patchedP4Info).build()
    harness.loadPipeline(patchedConfig)
    harness.installEntry(buildExactEntry(patchedConfig, matchValue = 0x0800, port = 1))
    harness.installEntry(buildExactEntry(patchedConfig, matchValue = 0x0801, port = 2))
    harness.installEntry(buildExactEntry(patchedConfig, matchValue = 0x0802, port = 3))
  }

  @Suppress("MagicNumber")
  private fun triggerActionProfileAtCapacity() {
    val config = loadActionSelector3()
    val profile = config.p4Info.actionProfilesList.first()
    val profileIdx = config.p4Info.actionProfilesList.indexOf(profile)
    // Patch p4info to set profile.size=2.
    val patchedP4Info =
      config.p4Info
        .toBuilder()
        .apply { setActionProfiles(profileIdx, profile.toBuilder().setSize(2)) }
        .build()
    val patchedConfig = config.toBuilder().setP4Info(patchedP4Info).build()
    harness.loadPipeline(patchedConfig)
    // Use 'drop' action which takes no params (buildMemberEntity omits params).
    val dropAction = config.p4Info.actionsList.find { it.preamble.alias == "drop" }!!
    val actionId = dropAction.preamble.id
    harness.installEntry(P4RuntimeTestHarness.buildMemberEntity(profile.preamble.id, 1, actionId))
    harness.installEntry(P4RuntimeTestHarness.buildMemberEntity(profile.preamble.id, 2, actionId))
    harness.installEntry(P4RuntimeTestHarness.buildMemberEntity(profile.preamble.id, 3, actionId))
  }

  @Suppress("MagicNumber")
  private fun triggerActionProfileGroupMaxSize() {
    val config = loadActionSelector3()
    val profile = config.p4Info.actionProfilesList.first()
    val profileIdx = config.p4Info.actionProfilesList.indexOf(profile)
    // Patch p4info to set max_group_size=1.
    val patchedP4Info =
      config.p4Info
        .toBuilder()
        .apply { setActionProfiles(profileIdx, profile.toBuilder().setMaxGroupSize(1)) }
        .build()
    val patchedConfig = config.toBuilder().setP4Info(patchedP4Info).build()
    harness.loadPipeline(patchedConfig)
    // Use 'drop' action which takes no params.
    val dropAction = config.p4Info.actionsList.find { it.preamble.alias == "drop" }!!
    val actionId = dropAction.preamble.id
    harness.installEntry(P4RuntimeTestHarness.buildMemberEntity(profile.preamble.id, 1, actionId))
    harness.installEntry(P4RuntimeTestHarness.buildMemberEntity(profile.preamble.id, 2, actionId))
    // Insert group with 2 members — exceeds max_group_size=1.
    harness.installEntry(
      P4RuntimeTestHarness.buildGroupEntity(profile.preamble.id, 1, listOf(1, 2))
    )
  }

  private fun triggerValueSetInsertNotSupported() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setValueSetEntry(P4RuntimeOuterClass.ValueSetEntry.newBuilder().setValueSetId(1))
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownValueSetId() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setValueSetEntry(P4RuntimeOuterClass.ValueSetEntry.newBuilder().setValueSetId(99999))
        .build()
    harness.modifyEntry(entity)
  }

  private fun triggerRoleConfigNotSupported() = runBlocking {
    val request =
      P4RuntimeOuterClass.StreamMessageRequest.newBuilder()
        .setArbitration(
          P4RuntimeOuterClass.MasterArbitrationUpdate.newBuilder()
            .setDeviceId(1)
            .setElectionId(P4RuntimeOuterClass.Uint128.newBuilder().setHigh(0).setLow(1))
            .setRole(
              P4RuntimeOuterClass.Role.newBuilder()
                .setName("sdn_controller")
                .setConfig(ProtoAny.getDefaultInstance())
            )
        )
        .build()
    harness.stub.streamChannel(flowOf(request)).collect {}
  }

  @Suppress("MagicNumber")
  private fun triggerUnrecognizedResponseType() = runBlocking {
    harness.loadPipeline(loadBasicTable())
    val unused =
      harness.stub.getForwardingPipelineConfig(
        GetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setResponseTypeValue(999)
          .build()
      )
  }

  @Suppress("MagicNumber")
  private fun triggerReconcileTableAbsent() {
    val basicTable = loadBasicTable()
    harness.loadPipeline(basicTable)
    harness.installEntry(buildExactEntry(basicTable, matchValue = 0x0800, port = 1))
    // RECONCILE_AND_COMMIT with ternary_acl: basic_table's table is absent.
    val ternaryAcl = loadTernaryAcl()
    runBlocking {
      val unused =
        harness.stub.setForwardingPipelineConfig(
          SetForwardingPipelineConfigRequest.newBuilder()
            .setDeviceId(1)
            .setAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT)
            .setConfig(harness.buildForwardingPipelineConfig(ternaryAcl))
            .build()
        )
    }
  }

  private fun triggerDigestStreamNotSupported() = runBlocking {
    val request =
      P4RuntimeOuterClass.StreamMessageRequest.newBuilder()
        .setDigestAck(P4RuntimeOuterClass.DigestListAck.newBuilder().setDigestId(1))
        .build()
    harness.stub.streamChannel(flowOf(request)).collect {}
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownProfileIdMember() {
    harness.loadPipeline(loadBasicTable())
    harness.installEntry(P4RuntimeTestHarness.buildMemberEntity(99999, 1, 1))
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownProfileIdGroup() {
    harness.loadPipeline(loadBasicTable())
    harness.installEntry(P4RuntimeTestHarness.buildGroupEntity(99999, 1, listOf(1)))
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownActionInProfile() {
    val config = loadActionSelector3()
    harness.loadPipeline(config)
    val profile = config.p4Info.actionProfilesList.first()
    harness.installEntry(P4RuntimeTestHarness.buildMemberEntity(profile.preamble.id, 1, 99999))
  }

  @Suppress("MagicNumber")
  private fun triggerActionNotValidForTable() {
    val config = loadBasicTable()
    // Patch p4info: add a fake action that is NOT in the table's action_refs.
    val fakeActionId = 99998
    val patchedP4Info =
      config.p4Info
        .toBuilder()
        .addActions(
          p4.config.v1.P4InfoOuterClass.Action.newBuilder()
            .setPreamble(
              p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                .setId(fakeActionId)
                .setName("fake_action")
                .setAlias("fake_action")
            )
        )
        .build()
    val patched = config.toBuilder().setP4Info(patchedP4Info).build()
    harness.loadPipeline(patched)
    val table = patched.p4Info.tablesList.first()
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(table.matchFieldsList.first().id)
                .setExact(
                  P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0x0800, 2)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(fakeActionId))
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerReconcileSchemaChanged() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    // RECONCILE_AND_COMMIT with a schema change: add an extra match field to the same table.
    val table = config.p4Info.tablesList.first()
    val patchedTable =
      table
        .toBuilder()
        .addMatchFields(
          p4.config.v1.P4InfoOuterClass.MatchField.newBuilder()
            .setId(99)
            .setName("extra_field")
            .setBitwidth(8)
            .setMatchType(p4.config.v1.P4InfoOuterClass.MatchField.MatchType.EXACT)
        )
        .build()
    val patchedP4Info = config.p4Info.toBuilder().setTables(0, patchedTable).build()
    val patchedConfig = config.toBuilder().setP4Info(patchedP4Info).build()
    runBlocking {
      val unused =
        harness.stub.setForwardingPipelineConfig(
          SetForwardingPipelineConfigRequest.newBuilder()
            .setDeviceId(1)
            .setAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT)
            .setConfig(harness.buildForwardingPipelineConfig(patchedConfig))
            .build()
        )
    }
  }

  @Suppress("MagicNumber")
  private fun triggerDirectCounterNoEntries() {
    // Load basic_table with a direct counter attached, but don't install any entries.
    val config = loadBasicTableWithDirectCounter()
    harness.loadPipeline(config)
    val tableId = config.p4Info.tablesList.first().preamble.id
    val entity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(tableId)
                .addMatch(
                  P4RuntimeOuterClass.FieldMatch.newBuilder()
                    .setFieldId(config.p4Info.tablesList.first().matchFieldsList.first().id)
                    .setExact(
                      P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                        .setValue(ByteString.copyFrom(longToBytes(0x0800, 2)))
                    )
                )
            )
        )
        .build()
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerDirectCounterNoMatch() {
    // Load basic_table with a direct counter, install one entry, then MODIFY the counter
    // for a different (non-matching) key.
    val config = loadBasicTableWithDirectCounter()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    val tableId = config.p4Info.tablesList.first().preamble.id
    val entity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(tableId)
                .addMatch(
                  P4RuntimeOuterClass.FieldMatch.newBuilder()
                    .setFieldId(config.p4Info.tablesList.first().matchFieldsList.first().id)
                    .setExact(
                      P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                        .setValue(ByteString.copyFrom(longToBytes(0x0801, 2)))
                    )
                )
            )
        )
        .build()
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerConstraintValidationFailed() {
    // Use a separate harness with the constraint validator binary.
    harness.close()
    P4RuntimeTestHarness(constraintValidatorBinary = VALIDATOR_BINARY).use { constrainedHarness ->
      val config = loadConfig("e2e_tests/constrained_table/constrained_table.txtpb")
      constrainedHarness.loadPipeline(config)

      val table = config.p4Info.tablesList.first { it.preamble.name.contains("acl") }
      val forwardAction = config.p4Info.actionsList.first { it.preamble.name.contains("forward") }
      val etherTypeFieldId = table.matchFieldsList.first { it.name.contains("ether_type") }.id
      val ipv4DstFieldId = table.matchFieldsList.first { it.name.contains("ipv4_dst") }.id

      // ipv4_dst mask != 0 but ether_type != 0x0800 -- violates the @entry_restriction.
      val entry =
        Entity.newBuilder()
          .setTableEntry(
            TableEntry.newBuilder()
              .setTableId(table.preamble.id)
              .setPriority(10)
              .addMatch(
                P4RuntimeOuterClass.FieldMatch.newBuilder()
                  .setFieldId(etherTypeFieldId)
                  .setTernary(
                    P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                      .setValue(ByteString.copyFrom(longToBytes(0x0806, 2)))
                      .setMask(ByteString.copyFrom(longToBytes(0xFFFF, 2)))
                  )
              )
              .addMatch(
                P4RuntimeOuterClass.FieldMatch.newBuilder()
                  .setFieldId(ipv4DstFieldId)
                  .setTernary(
                    P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                      .setValue(ByteString.copyFrom(longToBytes(0x0A000001, 4)))
                      .setMask(ByteString.copyFrom(longToBytes(0xFFFFFFFFL, 4)))
                  )
              )
              .setAction(
                P4RuntimeOuterClass.TableAction.newBuilder()
                  .setAction(
                    P4RuntimeOuterClass.Action.newBuilder()
                      .setActionId(forwardAction.preamble.id)
                      .addParams(
                        P4RuntimeOuterClass.Action.Param.newBuilder()
                          .setParamId(1)
                          .setValue(ByteString.copyFrom(longToBytes(1, 2)))
                      )
                  )
              )
          )
          .build()
      constrainedHarness.installEntry(entry)
    }
  }

  @Suppress("MagicNumber")
  private fun triggerRefersToViolationNoEntry() {
    val config = loadRefersTo()
    harness.loadPipeline(config)
    val refTable = findTable(config, "ref_table")
    val forwardAction = findAction(config, "forward")
    val srcAddrFieldId = matchFieldId(refTable, "hdr.ethernet.srcAddr")
    // INSERT into ref_table without a matching entry in target_table.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(refTable.preamble.id)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(srcAddrFieldId)
                .setExact(
                  P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0xAABBCCDDEEFFL, 6)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(paramId(forwardAction, "port"))
                        .setValue(ByteString.copyFrom(longToBytes(1, 2)))
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerRefersToViolationNoMulticast() {
    // Build synthetic p4info with a @refers_to(builtin::multicast_group_table) annotation
    // on an action param. p4c emits spaces around "::" in annotations.
    val config = loadBasicTable()
    val table = config.p4Info.tablesList.first()
    val mcastActionId = MCAST_ACTION_ID
    val mcastParamId = 1
    val patchedP4Info =
      config.p4Info
        .toBuilder()
        .addActions(
          p4.config.v1.P4InfoOuterClass.Action.newBuilder()
            .setPreamble(
              p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                .setId(mcastActionId)
                .setName("set_multicast")
                .setAlias("set_multicast")
            )
            .addParams(
              p4.config.v1.P4InfoOuterClass.Action.Param.newBuilder()
                .setId(mcastParamId)
                .setName("mcast_group_id")
                .setBitwidth(16)
                .addAnnotations(
                  "@refers_to(builtin : : multicast_group_table , multicast_group_id)"
                )
            )
        )
        .setTables(
          0,
          table
            .toBuilder()
            .addActionRefs(
              p4.config.v1.P4InfoOuterClass.ActionRef.newBuilder().setId(mcastActionId)
            ),
        )
        .build()
    val patched = config.toBuilder().setP4Info(patchedP4Info).build()
    harness.loadPipeline(patched)
    // INSERT with a multicast group ID that doesn't exist in the PRE.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(table.matchFieldsList.first().id)
                .setExact(
                  P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0x0800, 2)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(mcastActionId)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(mcastParamId)
                        .setValue(ByteString.copyFrom(longToBytes(42, 2)))
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerTypeTranslationFailed() {
    // Load translated_type.p4 with auto_allocate=false and no explicit mappings,
    // so any translated value triggers a TranslationException.
    val config = loadTranslatedType()
    val noAutoAllocate =
      config
        .toBuilder()
        .setDevice(
          config.device
            .toBuilder()
            .addTranslations(
              TypeTranslation.newBuilder().setTypeName("port_id_t").setAutoAllocate(false)
            )
        )
        .build()
    harness.loadPipeline(noAutoAllocate)
    // The 'forward' action has a port_id_t param. With no mapping, this will fail.
    val table = config.p4Info.tablesList.first()
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(table.matchFieldsList.first().id)
                .setExact(
                  P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0x0800, 2)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(forwardAction.paramsList.first().id)
                        .setValue(ByteString.copyFrom(longToBytes(1, 4)))
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerWrongRoleWrite() {
    val config = loadBasicTable()
    val table = config.p4Info.tablesList.first()
    // Patch the table to belong to "packet_replication_engine_manager".
    val patchedP4Info =
      config.p4Info
        .toBuilder()
        .setTables(
          0,
          table
            .toBuilder()
            .setPreamble(
              table.preamble
                .toBuilder()
                .addAnnotations("""@p4runtime_role("packet_replication_engine_manager")""")
            ),
        )
        .build()
    val patched = config.toBuilder().setP4Info(patchedP4Info).build()
    harness.loadPipeline(patched)
    val entry = buildExactEntry(patched, matchValue = 0x0800, port = 1)
    harness.installEntry(entry, role = "sdn_controller")
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun loadBasicTable(): PipelineConfig =
    loadConfig("e2e_tests/basic_table/basic_table.txtpb")

  private fun loadTernaryAcl(): PipelineConfig =
    loadConfig("e2e_tests/ternary_acl/ternary_acl.txtpb")

  private fun loadLpmRouting(): PipelineConfig =
    loadConfig("e2e_tests/lpm_routing/lpm_routing.txtpb")

  private fun loadStateful(): PipelineConfig = loadConfig("e2e_tests/golden_errors/stateful.txtpb")

  private fun loadRangeTable(): PipelineConfig =
    loadConfig("e2e_tests/golden_errors/range_table.txtpb")

  private fun loadValueSet(): PipelineConfig = loadConfig("e2e_tests/golden_errors/value_set.txtpb")

  private fun loadActionProfile(): PipelineConfig =
    loadConfig("e2e_tests/golden_errors/action_profile.txtpb")

  private fun loadActionSelector3(): PipelineConfig =
    loadConfig("e2e_tests/trace_tree/action_selector_3.txtpb")

  private fun loadRefersTo(): PipelineConfig = loadConfig("e2e_tests/golden_errors/refers_to.txtpb")

  private fun loadTranslatedType(): PipelineConfig =
    loadConfig("e2e_tests/translated_type/translated_type.txtpb")

  @Suppress("MagicNumber")
  private fun loadBasicTableWithDirectCounter(): PipelineConfig {
    val base = loadBasicTable()
    val tableId = base.p4Info.tablesList.first().preamble.id
    val directCounter =
      p4.config.v1.P4InfoOuterClass.DirectCounter.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
            .setId(DCTR_ID)
            .setName("myDirectCounter")
        )
        .setDirectTableId(tableId)
        .build()
    return base
      .toBuilder()
      .setP4Info(base.p4Info.toBuilder().addDirectCounters(directCounter))
      .build()
  }

  /**
   * Captures the error description from a gRPC call.
   *
   * For single-update batches that return UNKNOWN with per-update details, unwraps the batch error
   * to get the underlying error message. For multi-update batches, captures the top-level message.
   */
  private fun captureError(block: () -> Unit): String {
    try {
      block()
      fail("expected gRPC error but call succeeded")
      error("unreachable")
    } catch (e: StatusException) {
      // For batch errors (UNKNOWN status), check if there's exactly one failing update.
      if (e.status.code == Status.Code.UNKNOWN) {
        val batchErrors = extractBatchErrors(e)
        if (batchErrors != null && batchErrors.size == 1) {
          // Single-update batch: return the per-update error message.
          return "${statusCodeName(batchErrors[0].canonicalCode)}: ${batchErrors[0].message}"
        }
        // Multi-update batch: return the top-level UNKNOWN message.
        return "${e.status.code}: ${e.status.description}"
      }
      return "${e.status.code}: ${e.status.description}"
    }
  }

  private fun statusCodeName(canonicalCode: Int): String =
    Status.Code.values().find { it.value() == canonicalCode }?.name ?: "CODE_$canonicalCode"

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun testNames(): List<String> =
      listOf(
        "no-pipeline-loaded",
        "unknown-table-id",
        "unknown-action-id",
        "wrong-param-count",
        "match-value-out-of-range",
        "wrong-match-kind",
        "missing-exact-field",
        "duplicate-match-field",
        "const-table-write",
        "batch-write-failure",
        "invalid-device-config",
        "missing-pipeline-config",
        "wrong-device-id",
        "not-primary-controller",
        "digest-not-supported",
        "extern-entry-not-supported",
        "unrecognized-atomicity",
        "idle-timeout-not-supported",
        "default-entry-wrong-type",
        "default-entry-with-match",
        "unknown-param-id",
        "unknown-match-field-id",
        "match-field-no-value",
        "priority-must-be-zero",
        "table-entry-already-exists",
        "table-entry-not-found",
        "commit-without-save",
        "unrecognized-pipeline-action",
        "simulator-rejected-pipeline",
        "param-value-out-of-range",
        "priority-required",
        "ternary-masked-bits",
        "lpm-trailing-bits",
        "counter-insert-not-supported",
        "meter-insert-not-supported",
        "register-insert-not-supported",
        "pre-entry-missing-type",
        "clone-session-already-exists",
        "clone-session-not-found",
        "multicast-group-already-exists",
        "multicast-group-not-found",
        "unknown-register-id",
        "unknown-counter-id",
        "unknown-meter-id",
        "direct-counter-insert-not-supported",
        "direct-counter-unknown-table",
        "direct-meter-insert-not-supported",
        "direct-meter-unknown-table",
        "register-index-out-of-bounds",
        "counter-index-out-of-bounds",
        "meter-index-out-of-bounds",
        "range-low-greater-than-high",
        "value-set-full",
        "action-not-valid-for-profile",
        "table-full",
        "action-profile-at-capacity",
        "action-profile-group-max-size",
        "value-set-insert-not-supported",
        "unknown-value-set-id",
        "role-config-not-supported",
        "unrecognized-response-type",
        "reconcile-table-absent",
        "digest-stream-not-supported",
        "unknown-profile-id-member",
        "unknown-profile-id-group",
        "unknown-action-in-profile",
        "action-not-valid-for-table",
        "reconcile-schema-changed",
        "direct-counter-no-entries",
        "direct-counter-no-match",
        "constraint-validation-failed",
        "refers-to-violation-no-entry",
        "refers-to-violation-no-multicast",
        "type-translation-failed",
        "wrong-role-write",
        "inject-packet-no-pipeline",
        "inject-packet-no-port-translation",
        "inject-packet-simulator-throws",
        "inject-packet-port-overflow",
      )

    private val VALIDATOR_BINARY: Path =
      fourward.bazel.repoRoot.resolve("p4runtime/constraint_validator")

    private const val MCAST_ACTION_ID = 99997

    private const val DCTR_ID = 800

    private fun goldenDir(): java.nio.file.Path =
      fourward.bazel.repoRoot.resolve("p4runtime/golden_errors")
  }
}
