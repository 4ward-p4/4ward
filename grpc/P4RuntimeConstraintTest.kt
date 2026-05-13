package fourward.grpc

import com.google.protobuf.ByteString
import fourward.PipelineConfig
import fourward.grpc.FourwardTestHarness.Companion.assertGrpcError
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import fourward.grpc.FourwardTestHarness.Companion.longToBytes
import io.grpc.Status
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Tests for p4-constraints validation in the P4Runtime Write RPC.
 *
 * Uses the constrained_table.p4 fixture which has an `@entry_restriction` requiring that matching
 * on ipv4_dst (mask != 0) implies ether_type == 0x0800.
 */
class P4RuntimeConstraintTest {

  private lateinit var harness: FourwardTestHarness
  private lateinit var config: PipelineConfig

  @Before
  fun setUp() {
    harness = FourwardTestHarness(constraintValidatorBinary = VALIDATOR_BINARY)
    config = loadConfig("e2e_tests/constrained_table/constrained_table.txtpb")
    harness.loadPipeline(config)
  }

  @After
  fun tearDown() {
    harness.close()
  }

  private fun aclTableId(): Int =
    config.p4Info.tablesList.first { it.preamble.name.contains("acl") }.preamble.id

  private fun forwardActionId(): Int =
    config.p4Info.actionsList.first { it.preamble.name.contains("forward") }.preamble.id

  private fun matchFieldId(name: String): Int =
    config.p4Info.tablesList
      .first { it.preamble.name.contains("acl") }
      .matchFieldsList
      .first { it.name.contains(name) }
      .id

  /** Builds a ternary ACL entry with the given ether_type and ipv4_dst match values. */
  @Suppress("LongParameterList")
  private fun buildAclEntry(
    etherType: Int? = null,
    etherTypeMask: Int? = null,
    ipv4Dst: Long? = null,
    ipv4DstMask: Long? = null,
    port: Int = 1,
    priority: Int = 10,
  ): Entity {
    val tableEntry = TableEntry.newBuilder().setTableId(aclTableId()).setPriority(priority)

    if (etherType != null) {
      tableEntry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(matchFieldId("ether_type"))
          .setTernary(
            FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(etherType.toLong(), 2)))
              .setMask(ByteString.copyFrom(longToBytes((etherTypeMask ?: 0xFFFF).toLong(), 2)))
          )
      )
    }

    if (ipv4Dst != null) {
      tableEntry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(matchFieldId("ipv4_dst"))
          .setTernary(
            FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(ipv4Dst, 4)))
              .setMask(ByteString.copyFrom(longToBytes(ipv4DstMask ?: 0xFFFFFFFFL, 4)))
          )
      )
    }

    tableEntry.setAction(
      p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
        .setAction(
          p4.v1.P4RuntimeOuterClass.Action.newBuilder()
            .setActionId(forwardActionId())
            .addParams(
              p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(1)
                .setValue(ByteString.copyFrom(longToBytes(port.toLong(), 2)))
            )
        )
    )

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  // =========================================================================
  // Valid entries (constraint satisfied)
  // =========================================================================

  @Test
  fun `entry matching ipv4_dst with ether_type 0x0800 is accepted`() {
    // ipv4_dst mask != 0 AND ether_type == 0x0800 → constraint satisfied.
    val entry = buildAclEntry(etherType = 0x0800, ipv4Dst = 0x0A000001L)
    harness.installEntry(entry)
  }

  @Test
  fun `entry matching only ether_type without ipv4_dst is accepted`() {
    // ipv4_dst mask == 0 (not matched) → constraint trivially satisfied.
    val entry = buildAclEntry(etherType = 0x0806)
    harness.installEntry(entry)
  }

  // =========================================================================
  // Violating entries (constraint violated)
  // =========================================================================

  @Test
  fun `entry matching ipv4_dst with wrong ether_type is rejected`() {
    // ipv4_dst mask != 0 but ether_type != 0x0800 → constraint violated.
    val entry = buildAclEntry(etherType = 0x0806, ipv4Dst = 0x0A000001L)
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  @Test
  fun `entry matching ipv4_dst without ether_type match is rejected`() {
    // ipv4_dst mask != 0 but no ether_type match → constraint violated
    // (ether_type::value is unconstrained, so the implication is not satisfied).
    val entry = buildAclEntry(ipv4Dst = 0x0A000001L)
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // DELETE bypasses constraint validation
  // =========================================================================

  @Test
  fun `delete bypasses constraint validation`() {
    // First install a valid entry, then delete it. The delete should succeed
    // regardless of constraint checks.
    val entry = buildAclEntry(etherType = 0x0800, ipv4Dst = 0x0A000001L)
    harness.installEntry(entry)
    harness.deleteEntry(entry)
  }

  // =========================================================================
  // No constraints → validator not spawned
  // =========================================================================

  @Test
  fun `pipeline without constraints works normally`() {
    // Load basic_table which has no @entry_restriction — no validator subprocess.
    val basicConfig = loadConfig("e2e_tests/basic_table/basic_table.txtpb")
    harness.close()
    FourwardTestHarness(constraintValidatorBinary = VALIDATOR_BINARY).use { basicHarness ->
      basicHarness.loadPipeline(basicConfig)
      val entry = FourwardTestHarness.buildExactEntry(basicConfig, matchValue = 0x0800, port = 1)
      basicHarness.installEntry(entry)
      assertEquals(1, basicHarness.readEntries().size)
    }
  }

  companion object {
    private val VALIDATOR_BINARY: Path =
      fourward.bazel.repoRoot.resolve("grpc/constraint_validator")
  }
}
