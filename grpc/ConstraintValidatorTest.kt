package fourward.grpc

import com.google.protobuf.ByteString
import fourward.PipelineConfig
import java.nio.file.Path
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry

/** Unit tests for [ConstraintValidator] — the p4-constraints subprocess client. */
class ConstraintValidatorTest {

  // =========================================================================
  // Factory: create()
  // =========================================================================

  @Test
  fun `create returns non-null for P4Info with entry_restriction`() {
    val p4info = loadP4Info("e2e_tests/constrained_table/constrained_table.txtpb")
    ConstraintValidator.create(p4info, VALIDATOR_BINARY)!!.use { validator ->
      assertNotNull(validator)
    }
  }

  @Test
  fun `create returns null for P4Info without constraints`() {
    val p4info = loadP4Info("e2e_tests/basic_table/basic_table.txtpb")
    val validator = ConstraintValidator.create(p4info, VALIDATOR_BINARY)
    assertNull(validator)
  }

  // =========================================================================
  // Validation: valid entries
  // =========================================================================

  @Test
  fun `validateEntry returns null for entry satisfying constraint`() {
    val p4info = loadP4Info("e2e_tests/constrained_table/constrained_table.txtpb")
    ConstraintValidator.create(p4info, VALIDATOR_BINARY)!!.use { validator ->
      // ipv4_dst matched with ether_type == 0x0800 → constraint satisfied.
      val entry = buildAclEntry(p4info, etherType = 0x0800, ipv4Dst = 0x0A000001L)
      assertNull(validator.validateEntry(entry))
    }
  }

  @Test
  fun `validateEntry returns null when constraint antecedent is false`() {
    val p4info = loadP4Info("e2e_tests/constrained_table/constrained_table.txtpb")
    ConstraintValidator.create(p4info, VALIDATOR_BINARY)!!.use { validator ->
      // ipv4_dst not matched (mask == 0) → implication trivially true.
      val entry = buildAclEntry(p4info, etherType = 0x0806)
      assertNull(validator.validateEntry(entry))
    }
  }

  // =========================================================================
  // Validation: violating entries
  // =========================================================================

  @Test
  fun `validateEntry returns violation for entry violating constraint`() {
    val p4info = loadP4Info("e2e_tests/constrained_table/constrained_table.txtpb")
    ConstraintValidator.create(p4info, VALIDATOR_BINARY)!!.use { validator ->
      // ipv4_dst matched but ether_type != 0x0800 → constraint violated.
      val entry = buildAclEntry(p4info, etherType = 0x0806, ipv4Dst = 0x0A000001L)
      val violation = validator.validateEntry(entry)
      assertNotNull("expected a constraint violation", violation)
      assertTrue("violation should mention the table", violation!!.isNotEmpty())
    }
  }

  // =========================================================================
  // Lifecycle
  // =========================================================================

  @Test
  fun `close shuts down subprocess cleanly`() {
    val p4info = loadP4Info("e2e_tests/constrained_table/constrained_table.txtpb")
    val validator = ConstraintValidator.create(p4info, VALIDATOR_BINARY)!!
    validator.close()
    // No exception means the subprocess shut down within the timeout.
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private fun loadP4Info(configPath: String): p4.config.v1.P4InfoOuterClass.P4Info {
    val path = fourward.bazel.repoRoot.resolve(configPath)
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(path.toFile().readText(), builder)
    return builder.build().p4Info
  }

  /** Builds a ternary ACL entry for the constrained_table fixture. */
  @Suppress("LongParameterList")
  private fun buildAclEntry(
    p4info: p4.config.v1.P4InfoOuterClass.P4Info,
    etherType: Int? = null,
    ipv4Dst: Long? = null,
    port: Int = 1,
    priority: Int = 10,
  ): TableEntry {
    val table = p4info.tablesList.first { it.preamble.name.contains("acl") }
    val forwardAction = p4info.actionsList.first { it.preamble.name.contains("forward") }

    val entry = TableEntry.newBuilder().setTableId(table.preamble.id).setPriority(priority)

    if (etherType != null) {
      val fieldId = table.matchFieldsList.first { it.name.contains("ether_type") }.id
      entry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setTernary(
            FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(etherType.toLong(), 2)))
              .setMask(ByteString.copyFrom(longToBytes(0xFFFFL, 2)))
          )
      )
    }

    if (ipv4Dst != null) {
      val fieldId = table.matchFieldsList.first { it.name.contains("ipv4_dst") }.id
      entry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setTernary(
            FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(ipv4Dst, 4)))
              .setMask(ByteString.copyFrom(longToBytes(0xFFFFFFFFL, 4)))
          )
      )
    }

    entry.setAction(
      p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
        .setAction(
          p4.v1.P4RuntimeOuterClass.Action.newBuilder()
            .setActionId(forwardAction.preamble.id)
            .addParams(
              p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(1)
                .setValue(ByteString.copyFrom(longToBytes(port.toLong(), 2)))
            )
        )
    )

    return entry.build()
  }

  private fun longToBytes(value: Long, byteLen: Int): ByteArray {
    val bytes = ByteArray(byteLen)
    for (i in 0 until byteLen) {
      bytes[byteLen - 1 - i] = (value shr (i * 8) and 0xFF).toByte()
    }
    return bytes
  }

  companion object {
    private val VALIDATOR_BINARY: Path =
      fourward.bazel.repoRoot.resolve("grpc/constraint_validator")
  }
}
