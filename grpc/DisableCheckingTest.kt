package fourward.grpc

import com.google.protobuf.ByteString
import fourward.PipelineConfig
import fourward.grpc.FourwardTestHarness.Companion.assertGrpcError
import fourward.grpc.FourwardTestHarness.Companion.findAction
import fourward.grpc.FourwardTestHarness.Companion.findTable
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import fourward.grpc.FourwardTestHarness.Companion.longToBytes
import fourward.grpc.FourwardTestHarness.Companion.matchFieldId
import fourward.grpc.FourwardTestHarness.Companion.paramId
import io.grpc.Status
import java.nio.file.Path
import org.junit.Test
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Tests that `disableRefersToChecking` and `disableP4ConstraintsChecking` flags suppress
 * annotation-based validation on Write RPCs. Each test first proves the violation is normally
 * rejected, then shows it succeeds with the flag set.
 */
class DisableCheckingTest {

  // ===========================================================================
  // @refers_to
  // ===========================================================================

  @Test
  fun `refers_to violation is rejected by default`() {
    FourwardTestHarness().use { harness ->
      val config = loadRefersTo()
      harness.loadPipeline(config)
      assertGrpcError(Status.Code.INVALID_ARGUMENT) {
        harness.installEntry(refersToViolatingEntry(config))
      }
    }
  }

  @Test
  fun `refers_to violation is accepted when checking is disabled`() {
    FourwardTestHarness(disableRefersToChecking = true).use { harness ->
      val config = loadRefersTo()
      harness.loadPipeline(config)
      harness.installEntry(refersToViolatingEntry(config))
    }
  }

  // ===========================================================================
  // @entry_restriction / @action_restriction
  // ===========================================================================

  @Test
  fun `constraint violation is rejected by default`() {
    FourwardTestHarness(constraintValidatorBinary = VALIDATOR_BINARY).use { harness ->
      val config = loadConstrained()
      harness.loadPipeline(config)
      assertGrpcError(Status.Code.INVALID_ARGUMENT) {
        harness.installEntry(constraintViolatingEntry(config))
      }
    }
  }

  @Test
  fun `constraint violation is accepted when checking is disabled`() {
    FourwardTestHarness(
        constraintValidatorBinary = VALIDATOR_BINARY,
        disableP4ConstraintsChecking = true,
      )
      .use { harness ->
        val config = loadConstrained()
        harness.loadPipeline(config)
        harness.installEntry(constraintViolatingEntry(config))
      }
  }

  // ===========================================================================
  // Independence: disabling one does not affect the other
  // ===========================================================================

  @Test
  fun `disabling refers_to still rejects constraint violations`() {
    FourwardTestHarness(
        constraintValidatorBinary = VALIDATOR_BINARY,
        disableRefersToChecking = true,
      )
      .use { harness ->
        val config = loadConstrained()
        harness.loadPipeline(config)
        assertGrpcError(Status.Code.INVALID_ARGUMENT) {
          harness.installEntry(constraintViolatingEntry(config))
        }
      }
  }

  @Test
  fun `disabling p4-constraints still rejects refers_to violations`() {
    FourwardTestHarness(disableP4ConstraintsChecking = true).use { harness ->
      val config = loadRefersTo()
      harness.loadPipeline(config)
      assertGrpcError(Status.Code.INVALID_ARGUMENT) {
        harness.installEntry(refersToViolatingEntry(config))
      }
    }
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private fun loadRefersTo(): PipelineConfig = loadConfig("e2e_tests/golden_errors/refers_to.txtpb")

  private fun loadConstrained(): PipelineConfig =
    loadConfig("e2e_tests/constrained_table/constrained_table.txtpb")

  /** INSERT into ref_table without a matching entry in target_table. */
  @Suppress("MagicNumber")
  private fun refersToViolatingEntry(config: PipelineConfig): Entity {
    val refTable = findTable(config, "ref_table")
    val forwardAction = findAction(config, "forward")
    return Entity.newBuilder()
      .setTableEntry(
        TableEntry.newBuilder()
          .setTableId(refTable.preamble.id)
          .addMatch(
            FieldMatch.newBuilder()
              .setFieldId(matchFieldId(refTable, "hdr.ethernet.srcAddr"))
              .setExact(
                FieldMatch.Exact.newBuilder()
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
  }

  /** Ternary ACL entry matching ipv4_dst with wrong ether_type — violates the constraint. */
  @Suppress("MagicNumber")
  private fun constraintViolatingEntry(config: PipelineConfig): Entity {
    val aclTable = findTable(config, "acl")
    val forwardAction = findAction(config, "forward")

    return Entity.newBuilder()
      .setTableEntry(
        TableEntry.newBuilder()
          .setTableId(aclTable.preamble.id)
          .setPriority(10)
          .addMatch(
            FieldMatch.newBuilder()
              .setFieldId(matchFieldId(aclTable, "ether_type"))
              .setTernary(
                FieldMatch.Ternary.newBuilder()
                  .setValue(ByteString.copyFrom(longToBytes(0x0806L, 2)))
                  .setMask(ByteString.copyFrom(longToBytes(0xFFFFL, 2)))
              )
          )
          .addMatch(
            FieldMatch.newBuilder()
              .setFieldId(matchFieldId(aclTable, "ipv4_dst"))
              .setTernary(
                FieldMatch.Ternary.newBuilder()
                  .setValue(ByteString.copyFrom(longToBytes(0x0A000001L, 4)))
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
                      .setParamId(paramId(forwardAction, "port"))
                      .setValue(ByteString.copyFrom(longToBytes(1L, 2)))
                  )
              )
          )
      )
      .build()
  }

  companion object {
    private val VALIDATOR_BINARY: Path =
      fourward.bazel.repoRoot.resolve("grpc/constraint_validator")
  }
}
