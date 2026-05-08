package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.assertGrpcError
import fourward.p4runtime.P4RuntimeTestHarness.Companion.findAction
import fourward.p4runtime.P4RuntimeTestHarness.Companion.findTable
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.longToBytes
import fourward.p4runtime.P4RuntimeTestHarness.Companion.matchFieldId
import fourward.p4runtime.P4RuntimeTestHarness.Companion.paramId
import io.grpc.Status
import java.nio.file.Path
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Tests that @entry_restriction and @action_restriction constraints on SAI P4 middleblock tables
 * are enforced via the p4-constraints validator.
 *
 * Each test constructs a table entry that violates a specific constraint and verifies that the
 * P4Runtime Write RPC rejects it with INVALID_ARGUMENT.
 */
class SaiP4ConstraintTest {

  // =========================================================================
  // @entry_restriction: acl_pre_ingress_table
  // =========================================================================

  @Test
  fun `acl_pre_ingress - valid entry with dscp on IPv4 is accepted`() {
    // dscp::mask != 0 with is_ipv4 == 1 → constraint satisfied.
    val table = findTable(config, "acl_pre_ingress_table")
    val action = findAction(config, "set_vrf")
    val entry =
      buildTernaryAclEntry(
        table,
        action,
        ternaryFields = mapOf("dscp" to TernaryValue(value = 0x10, mask = 0x3F, byteLen = 1)),
        optionalFields = mapOf("is_ipv4" to OptionalValue(value = 1, byteLen = 1)),
        actionParams = mapOf("vrf_id" to stringValue("vrf-1")),
      )
    harness.installEntry(entry)
  }

  @Test
  fun `acl_pre_ingress - dscp match without IP type is rejected`() {
    // dscp::mask != 0 but no is_ip/is_ipv4/is_ipv6 → violates constraint.
    val table = findTable(config, "acl_pre_ingress_table")
    val action = findAction(config, "set_vrf")
    val entry =
      buildTernaryAclEntry(
        table,
        action,
        ternaryFields = mapOf("dscp" to TernaryValue(value = 0x10, mask = 0x3F, byteLen = 1)),
        actionParams = mapOf("vrf_id" to stringValue("vrf-1")),
      )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  @Test
  fun `acl_pre_ingress - is_ipv4 and is_ipv6 both matched is rejected`() {
    // Mutual exclusion: is_ipv4::mask != 0 -> is_ipv6::mask == 0.
    val table = findTable(config, "acl_pre_ingress_table")
    val action = findAction(config, "set_vrf")
    val entry =
      buildTernaryAclEntry(
        table,
        action,
        optionalFields =
          mapOf(
            "is_ipv4" to OptionalValue(value = 1, byteLen = 1),
            "is_ipv6" to OptionalValue(value = 1, byteLen = 1),
          ),
        actionParams = mapOf("vrf_id" to stringValue("vrf-1")),
      )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // @entry_restriction: ipv4_multicast_table
  // =========================================================================

  @Test
  fun `ipv4_multicast - multicast address is accepted`() {
    // ipv4_dst in 224.0.0.0 - 239.255.255.255 → constraint satisfied.
    // Uses "vrf-1" (not default VRF "") because @entry_restriction("vrf_id != 0") prevents
    // programming the default VRF through the control plane.
    val table = findTable(config, "ipv4_multicast_table")
    val action = findAction(config, "set_multicast_group_id")
    val entry =
      buildExactEntry(
        table,
        action,
        exactFields =
          mapOf(
            "vrf_id" to stringValue("vrf-1"),
            "ipv4_dst" to bytesValue(byteArrayOf(224.toByte(), 0, 0, 1)),
          ),
        actionParams = mapOf("multicast_group_id" to bytesValue(longToBytes(1, 2))),
      )
    harness.installEntry(entry)
  }

  @Test
  fun `ipv4_multicast - unicast address is rejected`() {
    // ipv4_dst = 10.0.0.1, not in multicast range → violates constraint.
    val table = findTable(config, "ipv4_multicast_table")
    val action = findAction(config, "set_multicast_group_id")
    val entry =
      buildExactEntry(
        table,
        action,
        exactFields =
          mapOf("vrf_id" to stringValue(""), "ipv4_dst" to bytesValue(byteArrayOf(10, 0, 0, 1))),
        actionParams = mapOf("multicast_group_id" to bytesValue(longToBytes(1, 2))),
      )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // @action_restriction: set_multicast_group_id
  // =========================================================================

  @Test
  fun `set_multicast_group_id - group_id 0 is rejected`() {
    // @action_restriction("multicast_group_id != 0").
    val table = findTable(config, "ipv4_multicast_table")
    val action = findAction(config, "set_multicast_group_id")
    val entry =
      buildExactEntry(
        table,
        action,
        exactFields =
          mapOf(
            "vrf_id" to stringValue(""),
            "ipv4_dst" to bytesValue(byteArrayOf(224.toByte(), 0, 0, 1)),
          ),
        actionParams = mapOf("multicast_group_id" to bytesValue(longToBytes(0, 2))),
      )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // @action_restriction: src_mac != 0
  // =========================================================================

  @Test
  fun `router_interface - src_mac all zeros is rejected`() {
    // @action_restriction("src_mac != 0") on set_port_and_src_mac.
    val table = findTable(config, "router_interface_table")
    val action = findAction(config, "set_port_and_src_mac")
    val entry =
      buildExactEntry(
        table,
        action,
        exactFields = mapOf("router_interface_id" to stringValue("rif-test")),
        actionParams =
          mapOf("port" to stringValue("Ethernet0"), "src_mac" to bytesValue(ByteArray(MAC_LEN))),
      )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  @Test
  fun `router_interface - nonzero src_mac is accepted`() {
    val table = findTable(config, "router_interface_table")
    val action = findAction(config, "set_port_and_src_mac")
    val entry =
      buildExactEntry(
        table,
        action,
        exactFields = mapOf("router_interface_id" to stringValue("rif-test")),
        actionParams =
          mapOf(
            "port" to stringValue("Ethernet0"),
            "src_mac" to bytesValue(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)),
          ),
      )
    harness.installEntry(entry)
  }

  // =========================================================================
  // @entry_restriction: disable_vlan_checks_table (dummy_match::mask == 0)
  // =========================================================================

  @Test
  fun `disable_vlan_checks - wildcard dummy_match is accepted`() {
    // dummy_match::mask == 0 → must be wildcard.
    val table = findTable(config, "disable_vlan_checks_table")
    val action = findAction(config, "disable_vlan_checks")
    // No match fields at all → all wildcarded → constraint satisfied.
    val entry = buildTernaryAclEntry(table, action, ternaryFields = emptyMap())
    harness.installEntry(entry)
  }

  @Test
  fun `disable_vlan_checks - exact dummy_match is rejected`() {
    // dummy_match::mask != 0 → violates constraint.
    val table = findTable(config, "disable_vlan_checks_table")
    val action = findAction(config, "disable_vlan_checks")
    val entry =
      buildTernaryAclEntry(
        table,
        action,
        ternaryFields = mapOf("dummy_match" to TernaryValue(value = 1, mask = 1, byteLen = 1)),
      )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private fun stringValue(s: String): ByteString = ByteString.copyFromUtf8(s)

  private fun bytesValue(b: ByteArray): ByteString = ByteString.copyFrom(b)

  data class TernaryValue(val value: Long, val mask: Long, val byteLen: Int)

  data class OptionalValue(val value: Long, val byteLen: Int)

  /** Builds the action portion of a table entry from an action proto and named params. */
  private fun buildTableAction(
    action: P4InfoOuterClass.Action,
    actionParams: Map<String, ByteString> = emptyMap(),
  ): p4.v1.P4RuntimeOuterClass.TableAction {
    val actionBuilder =
      p4.v1.P4RuntimeOuterClass.Action.newBuilder().setActionId(action.preamble.id)
    for ((paramName, value) in actionParams) {
      actionBuilder.addParams(
        p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(paramId(action, paramName))
          .setValue(value)
      )
    }
    return p4.v1.P4RuntimeOuterClass.TableAction.newBuilder().setAction(actionBuilder).build()
  }

  /** Builds a ternary ACL-style table entry with ternary and optional match fields. */
  @Suppress("LongParameterList")
  private fun buildTernaryAclEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    ternaryFields: Map<String, TernaryValue> = emptyMap(),
    optionalFields: Map<String, OptionalValue> = emptyMap(),
    actionParams: Map<String, ByteString> = emptyMap(),
    priority: Int = 10,
  ): Entity {
    val tableEntry = TableEntry.newBuilder().setTableId(table.preamble.id).setPriority(priority)

    for ((fieldName, tv) in ternaryFields) {
      tableEntry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(matchFieldId(table, fieldName))
          .setTernary(
            FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(tv.value, tv.byteLen)))
              .setMask(ByteString.copyFrom(longToBytes(tv.mask, tv.byteLen)))
          )
      )
    }

    for ((fieldName, ov) in optionalFields) {
      tableEntry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(matchFieldId(table, fieldName))
          .setOptional(
            FieldMatch.Optional.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(ov.value, ov.byteLen)))
          )
      )
    }

    tableEntry.setAction(buildTableAction(action, actionParams))
    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  /** Builds a table entry with exact match fields. */
  private fun buildExactEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    exactFields: Map<String, ByteString> = emptyMap(),
    actionParams: Map<String, ByteString> = emptyMap(),
  ): Entity {
    val tableEntry = TableEntry.newBuilder().setTableId(table.preamble.id)

    for ((fieldName, value) in exactFields) {
      tableEntry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(matchFieldId(table, fieldName))
          .setExact(FieldMatch.Exact.newBuilder().setValue(value))
      )
    }

    tableEntry.setAction(buildTableAction(action, actionParams))
    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  companion object {
    private const val MAC_LEN = 6
    private val VALIDATOR_BINARY: Path =
      fourward.bazel.repoRoot.resolve("p4runtime/constraint_validator")

    // Shared across all tests — pipeline load + constraint validator subprocess is expensive.
    private lateinit var harness: P4RuntimeTestHarness
    private lateinit var config: PipelineConfig

    @JvmStatic
    @BeforeClass
    fun setUp() {
      harness = P4RuntimeTestHarness(constraintValidatorBinary = VALIDATOR_BINARY)
      config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      harness.loadPipeline(config)
      installPrerequisites()
    }

    /**
     * Installs entries required by `@refers_to` validation in downstream tests. Without these,
     * tests that reference VRFs or multicast groups would fail on referential integrity before
     * reaching constraint validation.
     *
     * Note: the default VRF (vrf_id="", dataplane value 0) cannot be installed because
     * `@entry_restriction("vrf_id != 0")` on vrf_table rejects it. This matches real SAI behavior
     * where the default VRF is implicitly present.
     */
    @Suppress("MagicNumber")
    private fun installPrerequisites() {
      val vrfTable = findTable(config, "vrf_table")
      val noAction = findAction(config, "no_action")
      harness.installEntry(
        Entity.newBuilder()
          .setTableEntry(
            TableEntry.newBuilder()
              .setTableId(vrfTable.preamble.id)
              .addMatch(
                FieldMatch.newBuilder()
                  .setFieldId(matchFieldId(vrfTable, "vrf_id"))
                  .setExact(
                    FieldMatch.Exact.newBuilder().setValue(ByteString.copyFromUtf8("vrf-1"))
                  )
              )
              .setAction(
                p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                  .setAction(
                    p4.v1.P4RuntimeOuterClass.Action.newBuilder().setActionId(noAction.preamble.id)
                  )
              )
          )
          .build()
      )
      // Multicast group 1 (used by ipv4_multicast tests).
      harness.installEntry(
        Entity.newBuilder()
          .setPacketReplicationEngineEntry(
            p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
              .setMulticastGroupEntry(
                p4.v1.P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                  .setMulticastGroupId(1)
                  .addReplicas(
                    @Suppress("DEPRECATION")
                    p4.v1.P4RuntimeOuterClass.Replica.newBuilder().setEgressPort(0).setInstance(0)
                  )
              )
          )
          .build()
      )
    }

    @JvmStatic
    @AfterClass
    fun tearDown() {
      harness.close()
    }
  }
}
