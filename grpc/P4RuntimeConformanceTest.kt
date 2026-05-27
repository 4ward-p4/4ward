// See comment in GoldenErrorTest.kt — `val unused = stub.X(...)` discards must-be-used gRPC
// return values flagged by the downstream sync's `@CheckReturnValue` lint.
@file:Suppress("UnusedPrivateProperty")

package fourward.grpc

import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.ByteString
import com.google.protobuf.TextFormat
import fourward.PipelineConfig
import fourward.grpc.FourwardTestHarness.Companion.assertBatchError
import fourward.grpc.FourwardTestHarness.Companion.assertGrpcError
import fourward.grpc.FourwardTestHarness.Companion.buildEthernetFrame
import fourward.grpc.FourwardTestHarness.Companion.buildExactEntry
import fourward.grpc.FourwardTestHarness.Companion.buildGroupEntity
import fourward.grpc.FourwardTestHarness.Companion.buildMemberEntity
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import fourward.grpc.FourwardTestHarness.Companion.uint128
import fourward.simulator.portToBytes
import io.grpc.Status
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.Uint128
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * P4Runtime conformance tests.
 *
 * Each test verifies one aspect of the P4Runtime server's behavior against the 4ward simulator.
 * Tests are organized by RPC and numbered per the implementation plan.
 *
 * Tests use the basic_table.p4 fixture (exact-match table with forward/drop actions) unless a
 * specific P4 feature is needed.
 */
class P4RuntimeConformanceTest {

  private lateinit var harness: FourwardTestHarness

  @Before
  fun setUp() {
    harness = FourwardTestHarness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  // ---------------------------------------------------------------------------
  // Fixture loading
  // ---------------------------------------------------------------------------

  private fun loadBasicTableConfig() =
    FourwardTestHarness.loadConfig("e2e_tests/basic_table/basic_table.txtpb")

  private fun loadPassthroughConfig() =
    FourwardTestHarness.loadConfig("e2e_tests/passthrough/passthrough.txtpb")

  private fun loadConstEntriesConfig() =
    FourwardTestHarness.loadConfig("e2e_tests/trace_tree/clone_with_egress.txtpb")

  // =========================================================================
  // SetForwardingPipelineConfig (scenarios 1-3)
  // =========================================================================

  @Test
  fun `1 - load valid pipeline succeeds`() {
    val config = loadBasicTableConfig()
    val resp = harness.loadPipeline(config)
    assertNotNull(resp)
  }

  @Test
  fun `2 - load second pipeline replaces first`() {
    harness.loadPipeline(loadBasicTableConfig())
    // Loading a different pipeline should succeed (replaces the first).
    val resp = harness.loadPipeline(loadPassthroughConfig())
    assertNotNull(resp)
  }

  @Test
  fun `3 - load empty config returns error`() {
    assertGrpcError(Status.Code.INVALID_ARGUMENT) {
      harness.loadPipeline(PipelineConfig.getDefaultInstance())
    }
  }

  // =========================================================================
  // SetForwardingPipelineConfig — state clearing & cookie (7.10, 7.11)
  // =========================================================================

  /** P4Runtime spec §7.11: reloading a pipeline clears all table entries. */
  @Test
  fun `58 - pipeline reload clears table entries`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    val regularEntries = harness.readEntries()
    assertEquals("entry should exist before reload", 1, regularEntries.size)

    // Reload the same pipeline — entries should be gone.
    harness.loadPipeline(config)
    val afterReload = harness.readEntries()
    assertEquals("entries should be cleared after reload", 0, afterReload.size)
  }

  /** P4Runtime spec §7.10: cookie set during pipeline load is returned on get. */
  @Test
  fun `59 - cookie round-trip through set and get`() {
    val cookie = ForwardingPipelineConfig.Cookie.newBuilder().setCookie(0xDEADBEEF).build()
    harness.loadPipeline(loadBasicTableConfig(), cookie)

    // ALL response type should include the cookie.
    val allResp = harness.getConfig()
    assertEquals(0xDEADBEEF, allResp.config.cookie.cookie)

    // COOKIE_ONLY should return just the cookie.
    val cookieResp = harness.getConfig(GetForwardingPipelineConfigRequest.ResponseType.COOKIE_ONLY)
    assertEquals(0xDEADBEEF, cookieResp.config.cookie.cookie)

    // P4INFO_AND_COOKIE should include the cookie.
    val p4InfoResp =
      harness.getConfig(GetForwardingPipelineConfigRequest.ResponseType.P4INFO_AND_COOKIE)
    assertEquals(0xDEADBEEF, p4InfoResp.config.cookie.cookie)
  }

  // =========================================================================
  // Write (scenarios 4-8)
  // =========================================================================

  @Test
  fun `4 - install table entry succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val resp = harness.installEntry(entry)
    assertNotNull(resp)
  }

  @Test
  fun `5 - modify existing entry succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    // Modify to forward to a different port.
    val modified = buildExactEntry(config, matchValue = 0x0800, port = 2)
    val resp = harness.modifyEntry(modified)
    assertNotNull(resp)
  }

  @Test
  fun `6 - delete entry succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    val resp = harness.deleteEntry(entry)
    assertNotNull(resp)
  }

  @Test
  fun `7 - write without pipeline returns error`() {
    val entity =
      Entity.newBuilder()
        .setTableEntry(p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(1))
        .build()
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.installEntry(entity) }
  }

  @Test
  fun `8 - write with invalid table ID returns error`() {
    harness.loadPipeline(loadBasicTableConfig())
    val entity =
      Entity.newBuilder()
        .setTableEntry(p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(99999))
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entity) }
  }

  // =========================================================================
  // Read (scenarios 9-11)
  // =========================================================================

  @Test
  fun `9 - read back installed entries matches written`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    val entities = harness.readEntries()
    assertTrue("expected at least one entity", entities.isNotEmpty())
  }

  @Test
  fun `10 - read empty table with unmodified default returns empty`() {
    harness.loadPipeline(loadBasicTableConfig())
    val entities = harness.readEntries()
    // P4Runtime spec §9.1.6: pipeline-loaded defaults are not included in reads.
    assertTrue("unmodified defaults should not appear", entities.isEmpty())
  }

  @Test
  fun `11 - read without pipeline returns error`() {
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.readEntries() }
  }

  // =========================================================================
  // StreamChannel (scenarios 12-15)
  // =========================================================================

  @Test
  fun `12 - arbitration establishes master`() {
    harness.openStream().use { session ->
      val response = session.arbitrate()
      assertTrue("expected arbitration ack", response.hasArbitration())
    }
  }

  @Test
  fun `13 - PacketOut without controller_header produces no PacketIn`() {
    // passthrough.p4 has no @controller_header, so there is no CPU port.
    // PacketOut is still processed by the simulator, but no outputs become PacketIn.
    harness.loadPipeline(loadPassthroughConfig())
    harness.openStream().use { stream ->
      stream.arbitrate()
      val response =
        stream.sendPacket(
          byteArrayOf(0xCA.toByte(), 0xFE.toByte()),
          timeoutMs = FourwardTestHarness.NO_RESPONSE_TIMEOUT_MS,
        )
      assertNull("no PacketIn without @controller_header", response)
    }
  }

  @Test
  fun `14 - PacketOut with table entries but no controller_header produces no PacketIn`() {
    // basic_table.p4 has no @controller_header; PacketOut is processed but no PacketIn is returned.
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    harness.openStream().use { stream ->
      stream.arbitrate()
      val payload = buildEthernetFrame(etherType = 0x0800)
      val response =
        stream.sendPacket(payload, timeoutMs = FourwardTestHarness.NO_RESPONSE_TIMEOUT_MS)
      assertNull("no PacketIn without @controller_header", response)
    }
  }

  @Test
  fun `15 - multiple PacketOut without controller_header produce no PacketIn`() {
    // passthrough.p4 has no @controller_header; no PacketIn for any PacketOut.
    harness.loadPipeline(loadPassthroughConfig())
    harness.openStream().use { stream ->
      stream.arbitrate()
      assertNull(
        stream.sendPacket(
          byteArrayOf(0x01, 0x02),
          timeoutMs = FourwardTestHarness.NO_RESPONSE_TIMEOUT_MS,
        )
      )
      assertNull(
        stream.sendPacket(
          byteArrayOf(0x03, 0x04),
          timeoutMs = FourwardTestHarness.NO_RESPONSE_TIMEOUT_MS,
        )
      )
    }
  }

  @Test
  fun `15b - PacketOut before pipeline loaded silently drops`() {
    // No pipeline loaded — handlePacketOut returns null, no response on stream.
    harness.openStream().use { stream ->
      stream.arbitrate()
      val response =
        stream.sendPacket(
          byteArrayOf(0xCA.toByte(), 0xFE.toByte()),
          timeoutMs = FourwardTestHarness.NO_RESPONSE_TIMEOUT_MS,
        )
      assertNull("no response when pipeline not loaded", response)
    }
  }

  // =========================================================================
  // GetForwardingPipelineConfig (scenarios 16-18)
  // =========================================================================

  @Test
  fun `16 - getForwardingPipelineConfig returns loaded p4info`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val resp = harness.getConfig()
    assertEquals(config.p4Info, resp.config.p4Info)
  }

  @Test
  fun `17 - getForwardingPipelineConfig without pipeline returns error`() {
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.getConfig() }
  }

  @Test
  fun `18 - getForwardingPipelineConfig P4INFO_AND_COOKIE omits device config`() {
    harness.loadPipeline(loadBasicTableConfig())
    val resp = harness.getConfig(GetForwardingPipelineConfigRequest.ResponseType.P4INFO_AND_COOKIE)
    assertTrue("p4info should be present", resp.config.hasP4Info())
    assertTrue("device config should be empty", resp.config.p4DeviceConfig.isEmpty)
  }

  // =========================================================================
  // Capabilities (scenario 19)
  // =========================================================================

  @Test
  fun `19 - capabilities returns API version`() {
    val resp = harness.capabilities()
    assertEquals("1.5.0", resp.p4RuntimeApiVersion)
  }

  // =========================================================================
  // Write batch ordering (scenario 39)
  // =========================================================================

  /** P4Runtime spec §9.28: updates within a WriteRequest are applied in order. */
  @Test
  fun `39 - write batch applies updates in order`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)

    val entry1 = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entry2 = buildExactEntry(config, matchValue = 0x0800, port = 2)

    // Single WriteRequest: INSERT then MODIFY of the same key.
    // If ordering is correct, both succeed and the entry has port=2.
    runBlocking {
      val unused =
        harness.stub.write(
          p4.v1.P4RuntimeOuterClass.WriteRequest.newBuilder()
            .setDeviceId(1)
            .addUpdates(
              p4.v1.P4RuntimeOuterClass.Update.newBuilder()
                .setType(p4.v1.P4RuntimeOuterClass.Update.Type.INSERT)
                .setEntity(entry1)
            )
            .addUpdates(
              p4.v1.P4RuntimeOuterClass.Update.newBuilder()
                .setType(p4.v1.P4RuntimeOuterClass.Update.Type.MODIFY)
                .setEntity(entry2)
            )
            .build()
        )
    }

    // Read back: should have the MODIFY's action (port=2). Reads return canonical
    // (shortest) bytestrings per spec §8.3, regardless of how the value was sent.
    val results = harness.readEntry(FourwardTestHarness.buildMatchFilter(config, 0x0800))
    assertEquals("entry should exist", 1, results.size)
    assertEquals(
      "entry should have the MODIFY's action (canonical-form params)",
      entry2.tableEntry.action.action.paramsList.map(::canonicalizeParam),
      results[0].tableEntry.action.action.paramsList,
    )
  }

  // =========================================================================
  // SetForwardingPipelineConfig validation (scenarios 37-38)
  // =========================================================================

  @Test
  fun `37 - setForwardingPipelineConfig with invalid device config returns INVALID_ARGUMENT`() {
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "not a valid DeviceConfig") {
      loadRawPipeline(
        ForwardingPipelineConfig.newBuilder()
          .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
          .setP4DeviceConfig(com.google.protobuf.ByteString.copyFromUtf8("not-a-proto"))
      )
    }
  }

  @Test
  fun `38 - setForwardingPipelineConfig without device config returns INVALID_ARGUMENT`() {
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "must include p4info and p4_device_config") {
      loadRawPipeline(
        ForwardingPipelineConfig.newBuilder()
          .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
      )
    }
  }

  @Test
  fun `39 - setForwardingPipelineConfig accepts text-format DeviceConfig in p4_device_config`() {
    // p4_device_config is opaque to P4Runtime; 4ward's simulator accepts both
    // binary- and text-format DeviceConfig so people who hand-edit a .txtpb
    // ForwardingPipelineConfig don't have to know which encoding is in there.
    val config = loadBasicTableConfig()
    val textBytes = ByteString.copyFromUtf8(TextFormat.printer().printToString(config.device))
    loadRawPipeline(
      ForwardingPipelineConfig.newBuilder().setP4Info(config.p4Info).setP4DeviceConfig(textBytes)
    )
    val readBack = harness.getConfig()
    assertEquals(config.device, fourward.DeviceConfig.parseFrom(readBack.config.p4DeviceConfig))
  }

  // =========================================================================
  // Read filtering (scenarios 20-21)
  // =========================================================================

  @Test
  fun `20 - read with table filter returns only matching entries`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))

    val tableId = config.p4Info.tablesList.first().preamble.id
    val matching = harness.readTableEntries(tableId)
    // P4Runtime spec §9.1.6: pipeline-loaded defaults are not included in reads.
    assertEquals("matching table ID (1 regular, no unmodified default)", 1, matching.size)
    assertTrue("non-matching table ID", harness.readTableEntries(99999).isEmpty())
  }

  @Test
  fun `21 - read with empty entity filter returns nothing`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))

    // P4Runtime spec §11.1: empty entity list = no filters = no results.
    val request = ReadRequest.newBuilder().setDeviceId(1).build()
    assertTrue("empty filter should return nothing", harness.readEntries(request).isEmpty())
  }

  // =========================================================================
  // GetForwardingPipelineConfig response types (scenarios 22, 36)
  // =========================================================================

  @Test
  fun `22 - getForwardingPipelineConfig DEVICE_CONFIG_AND_COOKIE omits p4info`() {
    harness.loadPipeline(loadBasicTableConfig())
    val resp =
      harness.getConfig(GetForwardingPipelineConfigRequest.ResponseType.DEVICE_CONFIG_AND_COOKIE)
    assertFalse("p4info should be absent", resp.config.hasP4Info())
    assertFalse("device config should be present", resp.config.p4DeviceConfig.isEmpty)
  }

  @Test
  fun `36 - getForwardingPipelineConfig COOKIE_ONLY returns empty config`() {
    harness.loadPipeline(loadBasicTableConfig())
    val resp = harness.getConfig(GetForwardingPipelineConfigRequest.ResponseType.COOKIE_ONLY)
    assertFalse("p4info should be absent", resp.config.hasP4Info())
    assertTrue("device config should be empty", resp.config.p4DeviceConfig.isEmpty)
  }

  // =========================================================================
  // Per-entry reads (scenarios 23-25)
  // =========================================================================

  @Test
  fun `23 - read with match key filter returns only matching entry`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry1 = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entry2 = buildExactEntry(config, matchValue = 0x0806, port = 2)
    harness.installEntry(entry1)
    harness.installEntry(entry2)

    // Read with a filter that matches only entry1's match key.
    val filter = buildReadFilter(config, matchValue = 0x0800)
    val results = harness.readEntry(filter)
    assertEquals("should return exactly one entry", 1, results.size)
    assertEquals(
      "returned entry should match the filter",
      entry1.tableEntry.matchList,
      results[0].tableEntry.matchList,
    )
  }

  @Test
  fun `24 - read with non-matching key returns empty`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))

    val filter = buildReadFilter(config, matchValue = 0x9999)
    assertTrue("non-matching key should return nothing", harness.readEntry(filter).isEmpty())
  }

  @Test
  fun `25 - per-entry read preserves action parameters`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    val filter = buildReadFilter(config, matchValue = 0x0800)
    val results = harness.readEntry(filter)
    assertEquals(1, results.size)
    // The returned entry should have the same action as what was installed.
    // Per spec §8.3, the server returns bytestrings in canonical (shortest) form regardless of
    // the encoding the client sent, so we compare against the canonicalized expected params.
    assertTrue("should have action set", results[0].tableEntry.action.hasAction())
    assertEquals(
      entry.tableEntry.action.action.actionId,
      results[0].tableEntry.action.action.actionId,
    )
    assertEquals(
      entry.tableEntry.action.action.paramsList.map(::canonicalizeParam),
      results[0].tableEntry.action.action.paramsList,
    )
  }

  // =========================================================================
  // Action profile members (scenarios 26-28)
  // =========================================================================

  // Uses action_selector_3.p4 which defines an action profile with set_port and drop actions.
  private data class ActionSelectorFixture(val profileId: Int, val dropId: Int)

  private fun loadActionSelector(): ActionSelectorFixture {
    val config = loadConfig("e2e_tests/trace_tree/action_selector_3.txtpb")
    harness.loadPipeline(config)
    return ActionSelectorFixture(
      profileId = config.p4Info.actionProfilesList.first().preamble.id,
      dropId = FourwardTestHarness.findAction(config, "drop").preamble.id,
    )
  }

  @Test
  fun `26 - write and read back action profile member`() {
    val (profileId, dropId) = loadActionSelector()
    val member = buildMemberEntity(actionProfileId = profileId, memberId = 1, actionId = dropId)
    harness.installEntry(member)
    val results = harness.readProfileMembers(actionProfileId = profileId)
    assertEquals(1, results.size)
    assertTrue(results[0].hasActionProfileMember())
    assertEquals(1, results[0].actionProfileMember.memberId)
  }

  @Test
  fun `27 - insert duplicate member returns ALREADY_EXISTS`() {
    val (profileId, dropId) = loadActionSelector()
    val member = buildMemberEntity(actionProfileId = profileId, memberId = 1, actionId = dropId)
    harness.installEntry(member)
    assertGrpcError(Status.Code.ALREADY_EXISTS) { harness.installEntry(member) }
  }

  @Test
  fun `28 - delete member then read returns empty`() {
    val (profileId, dropId) = loadActionSelector()
    val member = buildMemberEntity(actionProfileId = profileId, memberId = 1, actionId = dropId)
    harness.installEntry(member)
    harness.deleteEntry(member)
    assertTrue(harness.readProfileMembers(actionProfileId = profileId).isEmpty())
  }

  // =========================================================================
  // Action profile groups (scenarios 29-31)
  // =========================================================================

  @Test
  fun `29 - write and read back action profile group`() {
    val (profileId, _) = loadActionSelector()
    val group = buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = listOf(1, 2))
    harness.installEntry(group)
    val results = harness.readProfileGroups(actionProfileId = profileId)
    assertEquals(1, results.size)
    assertTrue(results[0].hasActionProfileGroup())
    assertEquals(1, results[0].actionProfileGroup.groupId)
    assertEquals(2, results[0].actionProfileGroup.membersCount)
  }

  @Test
  fun `30 - modify group with different members succeeds`() {
    val (profileId, _) = loadActionSelector()
    val group = buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = listOf(1))
    harness.installEntry(group)
    val modified =
      buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = listOf(1, 2, 3))
    harness.modifyEntry(modified)
    val results = harness.readProfileGroups(actionProfileId = profileId)
    assertEquals(1, results.size)
    assertEquals(3, results[0].actionProfileGroup.membersCount)
  }

  @Test
  fun `31 - delete non-existent group returns NOT_FOUND`() {
    val (profileId, _) = loadActionSelector()
    val group = buildGroupEntity(actionProfileId = profileId, groupId = 99, memberIds = listOf(1))
    assertGrpcError(Status.Code.NOT_FOUND) { harness.deleteEntry(group) }
  }

  @Test
  fun `35 - wildcard read returns members across all action profiles`() {
    val (profileId, dropId) = loadActionSelector()
    val member1 = buildMemberEntity(actionProfileId = profileId, memberId = 1, actionId = dropId)
    val member2 = buildMemberEntity(actionProfileId = profileId, memberId = 2, actionId = dropId)
    harness.installEntry(member1)
    harness.installEntry(member2)
    // Wildcard read: actionProfileId = 0 returns all members.
    val results = harness.readProfileMembers(actionProfileId = 0)
    assertEquals(2, results.size)
  }

  // =========================================================================
  // Multi-table fixture (scenarios 93-96)
  // =========================================================================

  /**
   * Builds a synthetic multi-table config by injecting additional tables with ternary, LPM, range,
   * and optional match types into the basic_table p4info.
   */
  private fun loadMultiTableConfig(): PipelineConfig {
    val base = loadBasicTableConfig()
    val dropId = FourwardTestHarness.findAction(base, "drop").preamble.id
    val p4InfoBuilder = base.p4Info.toBuilder()
    // Add a ternary table.
    p4InfoBuilder.addTables(
      p4.config.v1.P4InfoOuterClass.Table.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
            .setId(MULTI_TERNARY_TABLE_ID)
            .setName("ternary_table")
            .setAlias("ternary_table")
        )
        .addMatchFields(
          p4.config.v1.P4InfoOuterClass.MatchField.newBuilder()
            .setId(1)
            .setName("f1")
            .setBitwidth(16)
            .setMatchType(p4.config.v1.P4InfoOuterClass.MatchField.MatchType.TERNARY)
        )
        .addActionRefs(p4.config.v1.P4InfoOuterClass.ActionRef.newBuilder().setId(dropId))
    )
    // Add an LPM table.
    p4InfoBuilder.addTables(
      p4.config.v1.P4InfoOuterClass.Table.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
            .setId(MULTI_LPM_TABLE_ID)
            .setName("lpm_table")
            .setAlias("lpm_table")
        )
        .addMatchFields(
          p4.config.v1.P4InfoOuterClass.MatchField.newBuilder()
            .setId(1)
            .setName("f1")
            .setBitwidth(32)
            .setMatchType(p4.config.v1.P4InfoOuterClass.MatchField.MatchType.LPM)
        )
        .addActionRefs(p4.config.v1.P4InfoOuterClass.ActionRef.newBuilder().setId(dropId))
    )
    return base.toBuilder().setP4Info(p4InfoBuilder).build()
  }

  @Test
  fun `93 - write entries to multiple tables and read all back`() {
    val config = loadMultiTableConfig()
    harness.loadPipeline(config)
    // Write to the original exact table.
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    // Write to the ternary table.
    harness.installEntry(buildTernaryEntry(MULTI_TERNARY_TABLE_ID, config))
    // Write to the LPM table.
    harness.installEntry(buildLpmEntry(MULTI_LPM_TABLE_ID, config))

    // Wildcard read returns entries from all tables + defaults.
    val results = harness.readEntries()
    val regular = results.filter { !it.tableEntry.isDefaultAction }
    assertEquals("should have 3 regular entries across tables", 3, regular.size)
    val tableIds = regular.map { it.tableEntry.tableId }.toSet()
    assertEquals("entries should span 3 different tables", 3, tableIds.size)
  }

  @Test
  fun `94 - table-specific read returns only that table entries`() {
    val config = loadMultiTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    harness.installEntry(buildTernaryEntry(MULTI_TERNARY_TABLE_ID, config))
    harness.installEntry(buildLpmEntry(MULTI_LPM_TABLE_ID, config))

    // Read only the ternary table.
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setTableEntry(
              P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(MULTI_TERNARY_TABLE_ID)
            )
        )
        .build()
    val results = harness.readEntries(request)
    val regular = results.filter { !it.tableEntry.isDefaultAction }
    assertEquals("should have 1 entry in ternary table", 1, regular.size)
    assertEquals(MULTI_TERNARY_TABLE_ID, regular[0].tableEntry.tableId)
  }

  @Test
  fun `95 - delete from one table does not affect others`() {
    val config = loadMultiTableConfig()
    harness.loadPipeline(config)
    val exactEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(exactEntry)
    harness.installEntry(buildTernaryEntry(MULTI_TERNARY_TABLE_ID, config))
    harness.installEntry(buildLpmEntry(MULTI_LPM_TABLE_ID, config))

    // Delete the exact entry.
    harness.deleteEntry(exactEntry)

    val regular = harness.readEntries()
    assertEquals("2 entries should remain", 2, regular.size)
    assertTrue(
      "no exact table entries",
      regular.none { it.tableEntry.tableId == config.p4Info.tablesList.first().preamble.id },
    )
  }

  @Test
  fun `96 - wildcard read returns modified default entries for all tables`() {
    val config = loadMultiTableConfig()
    harness.loadPipeline(config)
    val dropId = FourwardTestHarness.findAction(config, "drop").preamble.id

    // P4Runtime spec §9.1.6: only explicitly modified defaults appear in reads.
    // MODIFY the default on each table so they show up.
    for (table in config.p4Info.tablesList) {
      harness.modifyEntry(FourwardTestHarness.buildDefaultActionEntity(table.preamble.id, dropId))
    }

    val defaults = harness.readDefaultEntries()
    assertTrue("should have multiple default entries", defaults.size >= 2)
    val tableIds = defaults.map { it.tableEntry.tableId }.toSet()
    assertTrue("defaults should span multiple tables", tableIds.size >= 2)
  }

  /** Builds a ternary table entry with a simple match. */
  private fun buildTernaryEntry(tableId: Int, config: PipelineConfig): Entity {
    val dropId = FourwardTestHarness.findAction(config, "drop").preamble.id
    return Entity.newBuilder()
      .setTableEntry(
        P4RuntimeOuterClass.TableEntry.newBuilder()
          .setTableId(tableId)
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(1)
              .setTernary(
                P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                  .setValue(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x08, 0x00)))
                  .setMask(
                    com.google.protobuf.ByteString.copyFrom(byteArrayOf(0xFF.toByte(), 0x00))
                  )
              )
          )
          .setPriority(10)
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(dropId))
          )
      )
      .build()
  }

  /** Builds an LPM table entry with a /16 prefix. */
  private fun buildLpmEntry(tableId: Int, config: PipelineConfig): Entity {
    val dropId = FourwardTestHarness.findAction(config, "drop").preamble.id
    return Entity.newBuilder()
      .setTableEntry(
        P4RuntimeOuterClass.TableEntry.newBuilder()
          .setTableId(tableId)
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(1)
              .setLpm(
                P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
                  .setValue(
                    com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x0A, 0x00, 0x00, 0x00))
                  )
                  .setPrefixLen(16)
              )
          )
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(dropId))
          )
      )
      .build()
  }

  // =========================================================================
  // Register entries (scenarios 32-34)
  // =========================================================================

  private fun loadConfigWithRegister(): PipelineConfig {
    val base = loadBasicTableConfig()
    // Inject a register into the p4info so the simulator creates RegisterInfo.
    val register =
      p4.config.v1.P4InfoOuterClass.Register.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder().setId(REG_ID).setName("myReg")
        )
        .setTypeSpec(
          p4.config.v1.P4Types.P4DataTypeSpec.newBuilder()
            .setBitstring(
              p4.config.v1.P4Types.P4BitstringLikeTypeSpec.newBuilder()
                .setBit(p4.config.v1.P4Types.P4BitTypeSpec.newBuilder().setBitwidth(REG_BITWIDTH))
            )
        )
        .setSize(REG_SIZE)
        .build()
    return base.toBuilder().setP4Info(base.p4Info.toBuilder().addRegisters(register)).build()
  }

  @Test
  fun `32 - write register entry and read it back`() {
    harness.loadPipeline(loadConfigWithRegister())
    val entry = FourwardTestHarness.buildRegisterEntry(REG_ID, 0, 0xCAFE)
    harness.modifyEntry(entry)
    val results = harness.readRegisterEntries(REG_ID)
    // Should return all REG_SIZE entries; index 0 has our value.
    assertEquals(REG_SIZE, results.size)
    val written = results.find { it.registerEntry.index.index == 0L }!!
    assertEquals(
      com.google.protobuf.ByteString.copyFrom(
        FourwardTestHarness.longToBytes(0xCAFE, REG_BYTEWIDTH)
      ),
      written.registerEntry.data.bitstring,
    )
  }

  @Test
  fun `33 - read unwritten register entry returns zero`() {
    harness.loadPipeline(loadConfigWithRegister())
    val results = harness.readRegisterEntries(REG_ID)
    assertEquals(REG_SIZE, results.size)
    for (entity in results) {
      val data = entity.registerEntry.data.bitstring.toByteArray()
      assertTrue("unwritten register should be zero", data.all { it == 0.toByte() })
    }
  }

  @Test
  fun `34 - INSERT register entry returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadConfigWithRegister())
    val entry = FourwardTestHarness.buildRegisterEntry(REG_ID, 0, 1)
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // Multi-controller arbitration (scenarios 40-45)
  // =========================================================================

  /** P4Runtime spec §10.2: higher election_id becomes primary. */
  @Test
  fun `40 - higher election_id becomes primary`() {
    harness.openStream().use { stream ->
      val resp1 = stream.arbitrate(electionId = 1)
      assertEquals(
        "first arbitration should be OK",
        com.google.rpc.Code.OK_VALUE,
        resp1.arbitration.status.code,
      )
      val resp2 = stream.arbitrate(electionId = 5)
      assertEquals(
        "higher election_id should be OK",
        com.google.rpc.Code.OK_VALUE,
        resp2.arbitration.status.code,
      )
    }
  }

  /** P4Runtime spec §5: lower election_id is non-primary. */
  @Test
  fun `41 - lower election_id is non-primary`() {
    harness.openStream().use { primary ->
      primary.arbitrate(electionId = 5)
      harness.openStream().use { backup ->
        val resp = backup.arbitrate(electionId = 1)
        assertEquals(
          "lower election_id should get ALREADY_EXISTS",
          com.google.rpc.Code.ALREADY_EXISTS_VALUE,
          resp.arbitration.status.code,
        )
      }
    }
  }

  /** P4Runtime spec §10.3: non-primary writes return PERMISSION_DENIED. */
  @Test
  fun `42 - non-primary write returns PERMISSION_DENIED`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.openStream().use { stream -> stream.arbitrate(electionId = 5) }
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    assertGrpcError(Status.Code.PERMISSION_DENIED) { harness.installEntry(entry, uint128(low = 3)) }
  }

  /** P4Runtime spec §5: primary write succeeds. */
  @Test
  fun `43 - primary write succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.openStream().use { stream ->
      stream.arbitrate(electionId = 5)
      val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
      harness.installEntry(entry, uint128(low = 5))
      val results = harness.readEntries()
      assertEquals(1, results.size)
    }
  }

  /** Backward compatibility: write without any prior arbitration succeeds. */
  @Test
  fun `44 - write without arbitration succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // No arbitration — write should still work.
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    val regular = harness.readEntries()
    assertEquals(1, regular.size)
  }

  /** P4Runtime spec §5: all controllers may read regardless of role. */
  @Test
  fun `45 - all controllers may read regardless of role`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.openStream().use { stream ->
      stream.arbitrate(electionId = 5)
      val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
      harness.installEntry(entry, uint128(low = 5))
    }
    // Read with no election_id (any controller) should succeed even after stream closes.
    val results = harness.readEntries()
    assertEquals("read should return the installed entry", 1, results.size)
  }

  // =========================================================================
  // Counter entries (scenarios 46-48)
  // =========================================================================

  private fun loadConfigWithCounter(): PipelineConfig {
    val base = loadBasicTableConfig()
    val counter =
      p4.config.v1.P4InfoOuterClass.Counter.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder().setId(CTR_ID).setName("myCounter")
        )
        .setSize(CTR_SIZE.toLong())
        .build()
    return base.toBuilder().setP4Info(base.p4Info.toBuilder().addCounters(counter)).build()
  }

  @Test
  fun `46 - write counter entry and read it back`() {
    harness.loadPipeline(loadConfigWithCounter())
    val entry = FourwardTestHarness.buildCounterEntry(CTR_ID, 0, byteCount = 100, packetCount = 5)
    harness.modifyEntry(entry)
    val results = harness.readCounterEntries(CTR_ID)
    assertEquals(CTR_SIZE, results.size)
    val written = results.find { it.counterEntry.index.index == 0L }!!
    assertEquals(100, written.counterEntry.data.byteCount)
    assertEquals(5, written.counterEntry.data.packetCount)
  }

  @Test
  fun `47 - read unwritten counter entry returns zero`() {
    harness.loadPipeline(loadConfigWithCounter())
    val results = harness.readCounterEntries(CTR_ID)
    assertEquals(CTR_SIZE, results.size)
    for (entity in results) {
      assertEquals(0, entity.counterEntry.data.byteCount)
      assertEquals(0, entity.counterEntry.data.packetCount)
    }
  }

  @Test
  fun `48 - INSERT counter entry returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadConfigWithCounter())
    val entry = FourwardTestHarness.buildCounterEntry(CTR_ID, 0, byteCount = 1)
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // Meter entries (scenarios 49-51)
  // =========================================================================

  private fun loadConfigWithMeter(): PipelineConfig {
    val base = loadBasicTableConfig()
    val meter =
      p4.config.v1.P4InfoOuterClass.Meter.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder().setId(MTR_ID).setName("myMeter")
        )
        .setSize(MTR_SIZE.toLong())
        .build()
    return base.toBuilder().setP4Info(base.p4Info.toBuilder().addMeters(meter)).build()
  }

  @Test
  fun `49 - write meter entry and read it back`() {
    harness.loadPipeline(loadConfigWithMeter())
    val entry =
      FourwardTestHarness.buildMeterEntry(
        MTR_ID,
        0,
        cir = 1000,
        cburst = 100,
        pir = 2000,
        pburst = 200,
      )
    harness.modifyEntry(entry)
    val results = harness.readMeterEntries(MTR_ID)
    assertEquals(MTR_SIZE, results.size)
    val written = results.find { it.meterEntry.index.index == 0L }!!
    assertEquals(1000, written.meterEntry.config.cir)
    assertEquals(100, written.meterEntry.config.cburst)
    assertEquals(2000, written.meterEntry.config.pir)
    assertEquals(200, written.meterEntry.config.pburst)
  }

  @Test
  fun `50 - read unwritten meter entry has no config`() {
    harness.loadPipeline(loadConfigWithMeter())
    val results = harness.readMeterEntries(MTR_ID)
    assertEquals(MTR_SIZE, results.size)
    for (entity in results) {
      assertFalse("unwritten meter should have no config", entity.meterEntry.hasConfig())
    }
  }

  @Test
  fun `51 - INSERT meter entry returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadConfigWithMeter())
    val entry = FourwardTestHarness.buildMeterEntry(MTR_ID, 0, cir = 1)
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // Direct counter entries (scenarios 52-54)
  // =========================================================================

  private fun loadConfigWithDirectCounter(): PipelineConfig {
    val base = loadBasicTableConfig()
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

  @Test
  fun `52 - write direct counter entry and read it back`() {
    val config = loadConfigWithDirectCounter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    // MODIFY the direct counter for the installed entry.
    val directCounterEntity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          p4.v1.P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setData(
              p4.v1.P4RuntimeOuterClass.CounterData.newBuilder()
                .setPacketCount(42)
                .setByteCount(1000)
            )
        )
        .build()
    harness.modifyEntry(directCounterEntity)

    val tableId = config.p4Info.tablesList.first().preamble.id
    val results = harness.readDirectCounterEntries(tableId)
    assertEquals(1, results.size)
    assertEquals(42, results[0].directCounterEntry.data.packetCount)
    assertEquals(1000, results[0].directCounterEntry.data.byteCount)
  }

  @Test
  fun `53 - read unwritten direct counter returns zero`() {
    val config = loadConfigWithDirectCounter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val tableId = config.p4Info.tablesList.first().preamble.id
    val results = harness.readDirectCounterEntries(tableId)
    assertEquals(1, results.size)
    assertEquals(0, results[0].directCounterEntry.data.packetCount)
    assertEquals(0, results[0].directCounterEntry.data.byteCount)
  }

  @Test
  fun `54 - INSERT direct counter entry returns INVALID_ARGUMENT`() {
    val config = loadConfigWithDirectCounter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val directCounterEntity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          p4.v1.P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setData(p4.v1.P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(1))
        )
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(directCounterEntity) }
  }

  // =========================================================================
  // Direct meter entries (scenarios 55-57)
  // =========================================================================

  private fun loadConfigWithDirectMeter(): PipelineConfig {
    val base = loadBasicTableConfig()
    val tableId = base.p4Info.tablesList.first().preamble.id
    val directMeter =
      p4.config.v1.P4InfoOuterClass.DirectMeter.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
            .setId(DMTR_ID)
            .setName("myDirectMeter")
        )
        .setDirectTableId(tableId)
        .build()
    return base.toBuilder().setP4Info(base.p4Info.toBuilder().addDirectMeters(directMeter)).build()
  }

  @Test
  fun `55 - write direct meter entry and read it back`() {
    val config = loadConfigWithDirectMeter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val directMeterEntity =
      Entity.newBuilder()
        .setDirectMeterEntry(
          p4.v1.P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setConfig(
              p4.v1.P4RuntimeOuterClass.MeterConfig.newBuilder()
                .setCir(1000)
                .setCburst(100)
                .setPir(2000)
                .setPburst(200)
            )
        )
        .build()
    harness.modifyEntry(directMeterEntity)

    val tableId = config.p4Info.tablesList.first().preamble.id
    val results = harness.readDirectMeterEntries(tableId)
    assertEquals(1, results.size)
    val config2 = results[0].directMeterEntry.config
    assertEquals(1000, config2.cir)
    assertEquals(2000, config2.pir)
  }

  @Test
  fun `56 - read unconfigured direct meter has no config`() {
    val config = loadConfigWithDirectMeter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val tableId = config.p4Info.tablesList.first().preamble.id
    val results = harness.readDirectMeterEntries(tableId)
    assertEquals(1, results.size)
    assertFalse(
      "unconfigured direct meter should have no config",
      results[0].directMeterEntry.hasConfig(),
    )
  }

  @Test
  fun `57 - INSERT direct meter entry returns INVALID_ARGUMENT`() {
    val config = loadConfigWithDirectMeter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val directMeterEntity =
      Entity.newBuilder()
        .setDirectMeterEntry(
          p4.v1.P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setConfig(p4.v1.P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(100))
        )
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(directMeterEntity) }
  }

  // =========================================================================
  // Bytestring encoding (scenario 63)
  // =========================================================================

  /** P4Runtime spec §8.3: read responses use canonical (shortest) bytestring form. */
  @Test
  fun `63 - read responses use canonical bytestring form`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)

    // Send a value (port=1) that has a strictly shorter canonical form than the param's fixed
    // width — that way the assertion catches both "padded to fixed width" and "canonical".
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    val results = harness.readEntries()
    assertEquals(1, results.size)
    val readEntry = results[0].tableEntry
    val readMatch = readEntry.matchList[0].exact.value
    val readParam = readEntry.action.action.paramsList[0].value
    assertEquals(
      "match field bytestring should be canonical (no leading zero bytes)",
      readMatch,
      canonicalize(readMatch),
    )
    assertEquals(
      "action param bytestring should be canonical (no leading zero bytes)",
      readParam,
      canonicalize(readParam),
    )
    // And specifically: port=1 fits in 1 byte, so the read-back param must be 1 byte (not 2).
    assertEquals("port=1 canonical encoding is one byte", 1, readParam.size())
  }

  // =========================================================================
  // device_id validation (scenarios 60-62)
  // =========================================================================

  /** P4Runtime spec §6.3: Write with wrong device_id → NOT_FOUND. */
  @Test
  fun `60 - write with wrong device_id returns NOT_FOUND`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    assertGrpcError(Status.Code.NOT_FOUND, "unknown device_id") {
      runBlocking {
        val unused =
          harness.stub.write(
            p4.v1.P4RuntimeOuterClass.WriteRequest.newBuilder()
              .setDeviceId(999)
              .addUpdates(
                p4.v1.P4RuntimeOuterClass.Update.newBuilder()
                  .setType(p4.v1.P4RuntimeOuterClass.Update.Type.INSERT)
                  .setEntity(buildExactEntry(config, matchValue = 0x0800, port = 1))
              )
              .build()
          )
      }
    }
  }

  /** P4Runtime spec §6.3: Read with wrong device_id → NOT_FOUND. */
  @Test
  fun `61 - read with wrong device_id returns NOT_FOUND`() {
    harness.loadPipeline(loadBasicTableConfig())
    assertGrpcError(Status.Code.NOT_FOUND, "unknown device_id") {
      harness.readEntries(
        ReadRequest.newBuilder()
          .setDeviceId(999)
          .addEntities(
            Entity.newBuilder()
              .setTableEntry(p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(0))
          )
          .build()
      )
    }
  }

  /** P4Runtime spec §6.3: GetForwardingPipelineConfig with wrong device_id → NOT_FOUND. */
  @Test
  fun `62 - getForwardingPipelineConfig with wrong device_id returns NOT_FOUND`() {
    harness.loadPipeline(loadBasicTableConfig())
    assertGrpcError(Status.Code.NOT_FOUND, "unknown device_id") {
      runBlocking {
        val unused =
          harness.stub.getForwardingPipelineConfig(
            GetForwardingPipelineConfigRequest.newBuilder().setDeviceId(999).build()
          )
      }
    }
  }

  // =========================================================================
  // Default entry in wildcard reads (scenario 64)
  // =========================================================================

  /**
   * P4Runtime spec §9.1.6: wildcard reads include default entries that have been explicitly
   * modified via Write. Pipeline-loaded defaults are not included.
   */
  @Test
  fun `64 - wildcard read includes modified default entry`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val tableId = config.p4Info.tablesList.first().preamble.id
    val dropAction = config.p4Info.actionsList.find { it.preamble.name.contains("drop") }!!

    // Install one regular entry.
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))

    // Before modifying the default, it should not appear in default reads.
    assertTrue(
      "unmodified default should not appear",
      harness.readDefaultEntries(tableId).isEmpty(),
    )

    // MODIFY the default action (re-set it to drop).
    harness.modifyEntry(
      FourwardTestHarness.buildDefaultActionEntity(tableId, dropAction.preamble.id)
    )

    // §9.1.6: wildcard read with is_default_action=false excludes defaults.
    val regularEntries = harness.readEntries()
    assertEquals("should have 1 regular entry", 1, regularEntries.size)
    assertTrue(
      "wildcard should not include defaults",
      regularEntries.none { it.tableEntry.isDefaultAction },
    )

    // §9.1.6: is_default_action=true returns the modified default.
    val defaultEntries = harness.readDefaultEntries(tableId)
    assertEquals("should have 1 modified default entry", 1, defaultEntries.size)

    val defaultEntry = defaultEntries.first().tableEntry
    assertEquals("default entry should have correct table_id", tableId, defaultEntry.tableId)
    assertTrue("default entry should have is_default_action set", defaultEntry.isDefaultAction)
    assertTrue("default entry should have an action", defaultEntry.hasAction())
    assertEquals(
      "default action should be drop",
      dropAction.preamble.id,
      defaultEntry.action.action.actionId,
    )
  }

  // =========================================================================
  // Default entry edge cases (scenarios 97-99)
  // =========================================================================

  /** P4Runtime spec §9.1: MODIFY changes the default action; read-back reflects the update. */
  @Test
  fun `97 - MODIFY default entry changes the action`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val tableId = config.p4Info.tablesList.first().preamble.id
    val noActionId = config.p4Info.actionsList.find { it.preamble.name == "NoAction" }!!.preamble.id

    // Change default from drop() to NoAction.
    harness.modifyEntry(FourwardTestHarness.buildDefaultActionEntity(tableId, noActionId))

    // Read back and verify.
    val defaults = harness.readDefaultEntries(tableId)
    val readBack = defaults.first().tableEntry
    assertEquals(
      "default action should now be NoAction",
      noActionId,
      readBack.action.action.actionId,
    )
  }

  /** P4Runtime spec §9.1: INSERT of a default entry is rejected. */
  @Test
  fun `98 - INSERT default entry returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val tableId = config.p4Info.tablesList.first().preamble.id
    val dropId = FourwardTestHarness.findAction(config, "drop").preamble.id

    assertGrpcError(Status.Code.INVALID_ARGUMENT) {
      harness.installEntry(FourwardTestHarness.buildDefaultActionEntity(tableId, dropId))
    }
  }

  /** P4Runtime spec §9.1: DELETE of a default entry is rejected. */
  @Test
  fun `99 - DELETE default entry returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val tableId = config.p4Info.tablesList.first().preamble.id

    assertGrpcError(Status.Code.INVALID_ARGUMENT) {
      harness.deleteEntry(FourwardTestHarness.buildDefaultActionEntity(tableId))
    }
  }

  // =========================================================================
  // Const table entries populated at load time (scenario 65)
  // =========================================================================

  /** P4Runtime spec §7: const entries in P4 source appear after pipeline load. */
  @Test
  fun `65 - const entries populated at load time`() {
    val config = loadConstEntriesConfig()
    harness.loadPipeline(config)

    // The clone_with_egress program has a table `egress_classify` with 2 const entries.
    val egressTable =
      config.p4Info.tablesList.first { it.preamble.name.contains("egress_classify") }
    val entries = harness.readTableEntries(egressTable.preamble.id)
    assertEquals("expected 2 const entries", 2, entries.size)

    // P4Runtime spec §9.1: const tables are immutable.
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.modifyEntry(entries[0]) }
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.deleteEntry(entries[0]) }
  }

  // =========================================================================
  // SetPipeline requires primary controller (scenario 66)
  // =========================================================================

  /** P4Runtime spec §7: SetForwardingPipelineConfig requires primary when arbitration active. */
  @Test
  fun `66 - SetPipeline from non-primary returns PERMISSION_DENIED`() {
    // Establish primary with election_id=5.
    harness.openStream().use { stream -> stream.arbitrate(electionId = 5) }

    // Attempt SetPipeline with election_id=3 (non-primary).
    val config = loadBasicTableConfig()
    assertGrpcError(Status.Code.PERMISSION_DENIED) {
      runBlocking {
        val unused =
          harness.stub.setForwardingPipelineConfig(
            SetForwardingPipelineConfigRequest.newBuilder()
              .setDeviceId(1)
              .setElectionId(Uint128.newBuilder().setHigh(0).setLow(3))
              .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
              .setConfig(harness.buildForwardingPipelineConfig(config))
              .build()
          )
      }
    }
  }

  // =========================================================================
  // StreamError on invalid stream message (scenario 67)
  // =========================================================================

  /** P4Runtime spec §16: unrecognized stream messages get a StreamError response. */
  @Test
  fun `67 - unrecognized stream message returns StreamError`() {
    harness.openStream().use { stream ->
      stream.arbitrate()
      // Send an empty StreamMessageRequest (no oneof field set).
      val response = stream.sendRaw(StreamMessageRequest.getDefaultInstance())
      assertNotNull("expected a StreamError response", response)
      assertTrue("should be a StreamError", response!!.hasError())
      assertEquals(com.google.rpc.Code.INVALID_ARGUMENT_VALUE, response.error.canonicalCode)
    }
  }

  // =========================================================================
  // Direct counter/meter data in table entry reads (scenario 68)
  // =========================================================================

  /** P4Runtime spec §9.1: table entry reads include inline direct counter data. */
  @Test
  fun `68 - table entry read includes direct counter data`() {
    val config = loadConfigWithDirectCounter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    // Write direct counter data.
    val directCounterEntity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setData(
              P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(42).setByteCount(1000)
            )
        )
        .build()
    harness.modifyEntry(directCounterEntity)

    // Read table entries — counter_data should be inlined.
    val results = harness.readEntries()
    assertEquals(1, results.size)
    assertTrue("should have counter_data", results[0].tableEntry.hasCounterData())
    assertEquals(42, results[0].tableEntry.counterData.packetCount)
    assertEquals(1000, results[0].tableEntry.counterData.byteCount)
  }

  /** P4Runtime spec §9.1: table entry reads include inline direct meter config. */
  @Test
  fun `69 - table entry read includes direct meter config`() {
    val config = loadConfigWithDirectMeter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    // Write direct meter config.
    val directMeterEntity =
      Entity.newBuilder()
        .setDirectMeterEntry(
          P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setConfig(
              P4RuntimeOuterClass.MeterConfig.newBuilder()
                .setCir(1000)
                .setCburst(500)
                .setPir(2000)
                .setPburst(1000)
            )
        )
        .build()
    harness.modifyEntry(directMeterEntity)

    // Read table entries — meter_config should be inlined.
    val results = harness.readEntries()
    assertEquals(1, results.size)
    assertTrue("should have meter_config", results[0].tableEntry.hasMeterConfig())
    assertEquals(1000, results[0].tableEntry.meterConfig.cir)
    assertEquals(2000, results[0].tableEntry.meterConfig.pir)
  }

  /** P4Runtime spec §9.1: unwritten direct counter defaults to zero in table reads. */
  @Test
  fun `70 - table entry read includes zero counter data for unwritten direct counter`() {
    val config = loadConfigWithDirectCounter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    // Do NOT write any counter data — the read should still include counter_data with zeros.
    val results = harness.readEntries()
    assertEquals(1, results.size)
    assertTrue(
      "should have counter_data even without explicit write",
      results[0].tableEntry.hasCounterData(),
    )
    assertEquals(0, results[0].tableEntry.counterData.packetCount)
    assertEquals(0, results[0].tableEntry.counterData.byteCount)
  }

  /** P4Runtime spec §9.1: unwritten direct meter is omitted from table reads (unlike counters). */
  @Test
  fun `71 - table entry read omits meter config for unwritten direct meter`() {
    val config = loadConfigWithDirectMeter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    // Do NOT write any meter config — the read should NOT include meter_config.
    val results = harness.readEntries()
    assertEquals(1, results.size)
    assertFalse(
      "should not have meter_config without explicit write",
      results[0].tableEntry.hasMeterConfig(),
    )
  }

  // =========================================================================
  // Arbitration: demotion, promotion, zero election_id (scenarios 72-74)
  // =========================================================================

  /** P4Runtime spec §5: election_id=0 is a backup controller, cannot become primary. */
  @Test
  fun `72 - zero election_id cannot become primary`() {
    harness.openStream().use { stream ->
      val resp = stream.arbitrate(electionId = 0)
      assertEquals(
        "zero election_id should always be non-primary",
        com.google.rpc.Code.ALREADY_EXISTS_VALUE,
        resp.arbitration.status.code,
      )
    }
  }

  /** P4Runtime spec §5: displaced primary receives demotion notification. */
  @Test
  fun `73 - demotion notification sent to displaced primary`() {
    harness.openStream().use { oldPrimary ->
      oldPrimary.arbitrate(electionId = 3)
      harness.openStream().use { newPrimary ->
        // New controller with higher election_id displaces the old primary.
        val resp = newPrimary.arbitrate(electionId = 7)
        assertEquals(
          "higher election_id should be primary",
          com.google.rpc.Code.OK_VALUE,
          resp.arbitration.status.code,
        )
        // Old primary should receive an async demotion notification.
        val demotion = oldPrimary.receiveNext()
        assertNotNull("old primary should receive demotion notification", demotion)
        assertTrue("should be an arbitration message", demotion!!.hasArbitration())
        assertEquals(
          "demotion should include the old primary's election_id",
          3,
          demotion.arbitration.electionId.low,
        )
        assertEquals(
          "demotion status should be ALREADY_EXISTS",
          com.google.rpc.Code.ALREADY_EXISTS_VALUE,
          demotion.arbitration.status.code,
        )
      }
    }
  }

  /** P4Runtime spec §5: when primary disconnects, next highest is promoted. */
  @Test
  fun `74 - automatic promotion when primary disconnects`() {
    val backup = harness.openStream()
    backup.arbitrate(electionId = 3)
    harness.openStream().use { primary ->
      primary.arbitrate(electionId = 7)
      // Drain the demotion notification from backup's channel.
      backup.receiveNext()
    }
    // Primary (election_id=7) has disconnected. Backup should be promoted.
    try {
      val promotion = backup.receiveNext()
      assertNotNull("backup should receive promotion notification", promotion)
      assertTrue("should be an arbitration message", promotion!!.hasArbitration())
      assertEquals(
        "promotion should include the backup's election_id",
        3,
        promotion.arbitration.electionId.low,
      )
      assertEquals(
        "promotion status should be OK",
        com.google.rpc.Code.OK_VALUE,
        promotion.arbitration.status.code,
      )
    } finally {
      backup.close()
    }
  }

  /** Multiple iterations widen the cancellation race window. See `streamChannel`'s `finally`. */
  @Test
  fun `74b - abrupt cancel of primary stream still releases its arbitration slot`() {
    repeat(8) {
      val primary = harness.openStream()
      val arbResp = primary.arbitrate(electionId = 5)
      assertEquals(com.google.rpc.Code.OK_VALUE, arbResp.arbitration.status.code) // sanity: primary
      primary.cancelAbruptly()

      // Give the server's finally block a chance to run handleDisconnect.
      Thread.sleep(50)

      harness.openStream().use { fresh ->
        val resp = fresh.arbitrate(electionId = 3)
        assertEquals(
          "after primary's abrupt cancel, election_id=3 must become primary; " +
            "stale controller in map would demote it",
          com.google.rpc.Code.OK_VALUE,
          resp.arbitration.status.code,
        )
      }
    }
  }

  // =========================================================================
  // SetForwardingPipelineConfig actions (§7.2)
  // =========================================================================

  /** P4Runtime spec §7.2: VERIFY validates without applying. */
  @Test
  fun `75 - VERIFY validates without activating pipeline`() {
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.VERIFY)

    // Pipeline should not be active — reads should fail.
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.readEntries() }
  }

  /** P4Runtime spec §7.2: VERIFY rejects invalid config. */
  @Test
  fun `76 - VERIFY rejects invalid config`() {
    assertGrpcError(Status.Code.INVALID_ARGUMENT) {
      sendPipelineAction(
        SetForwardingPipelineConfigRequest.Action.VERIFY,
        PipelineConfig.getDefaultInstance(),
      )
    }
  }

  /** P4Runtime spec §7.2: VERIFY_AND_SAVE stores config without activating. */
  @Test
  fun `77 - VERIFY_AND_SAVE saves without activating`() {
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_SAVE)

    // Pipeline should not be active — reads should fail.
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.readEntries() }
  }

  /** P4Runtime spec §7.2: COMMIT activates a previously saved pipeline. */
  @Test
  fun `78 - VERIFY_AND_SAVE then COMMIT activates pipeline`() {
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_SAVE)
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.COMMIT)

    // Pipeline should now be active.
    val entries = harness.readEntries()
    assertNotNull(entries)
  }

  /** P4Runtime spec §7.2: COMMIT without prior VERIFY_AND_SAVE fails. */
  @Test
  fun `79 - COMMIT without saved pipeline returns FAILED_PRECONDITION`() {
    assertGrpcError(Status.Code.FAILED_PRECONDITION) {
      sendPipelineAction(SetForwardingPipelineConfigRequest.Action.COMMIT)
    }
  }

  // =========================================================================
  // RECONCILE_AND_COMMIT (scenarios 80, 126-130)
  // =========================================================================

  /** Same pipeline reloaded with RECONCILE_AND_COMMIT preserves entries. */
  @Test
  fun `80 - RECONCILE_AND_COMMIT preserves entries on identical pipeline reload`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    assertEquals(1, harness.readEntries().size)
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT, config)
    assertEquals("entry should survive reconcile", 1, harness.readEntries().size)
  }

  /** RECONCILE_AND_COMMIT with no prior pipeline is equivalent to VERIFY_AND_COMMIT. */
  @Test
  fun `126 - RECONCILE_AND_COMMIT without prior pipeline loads normally`() {
    val config = loadBasicTableConfig()
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT, config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    assertEquals(1, harness.readEntries().size)
  }

  /** RECONCILE_AND_COMMIT rejects if a populated table is missing from the new pipeline. */
  @Test
  fun `127 - RECONCILE_AND_COMMIT rejects when populated table removed`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    // Load a different pipeline that doesn't have the same table.
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "absent from the new pipeline") {
      sendPipelineAction(
        SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT,
        loadPassthroughConfig(),
      )
    }
  }

  /** RECONCILE_AND_COMMIT succeeds when tables exist but have no entries. */
  @Test
  fun `128 - RECONCILE_AND_COMMIT succeeds with empty tables and different pipeline`() {
    harness.loadPipeline(loadBasicTableConfig())
    // No entries installed — any pipeline change should be fine.
    sendPipelineAction(
      SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT,
      loadPassthroughConfig(),
    )
    // New pipeline should be active.
    assertNotNull(harness.getConfig())
  }

  /** RECONCILE_AND_COMMIT preserves PRE entries (multicast groups, clone sessions). */
  @Test
  fun `129 - RECONCILE_AND_COMMIT preserves PRE entries`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // Install a multicast group.
    val mcastGroup =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(
              P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                .setMulticastGroupId(1)
                .addReplicas(
                  P4RuntimeOuterClass.Replica.newBuilder().setPort(portToBytes(1)).setInstance(0)
                )
            )
        )
        .build()
    harness.installEntry(mcastGroup)
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT, config)
    // PRE entry should survive.
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setMulticastGroupEntry(
                  P4RuntimeOuterClass.MulticastGroupEntry.getDefaultInstance()
                )
            )
        )
        .build()
    val results = harness.readEntries(request)
    assertTrue("PRE entry should survive reconcile", results.isNotEmpty())
  }

  /** RECONCILE_AND_COMMIT preserves action profile members. */
  @Test
  fun `130 - RECONCILE_AND_COMMIT preserves action profile members`() {
    val config = loadConfig("e2e_tests/trace_tree/action_selector_3.txtpb")
    harness.loadPipeline(config)
    val profileId = config.p4Info.actionProfilesList.first().preamble.id
    val dropId = FourwardTestHarness.findAction(config, "drop").preamble.id
    val member = buildMemberEntity(actionProfileId = profileId, memberId = 1, actionId = dropId)
    harness.installEntry(member)
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT, config)
    val members = harness.readProfileMembers(actionProfileId = profileId)
    assertEquals("profile member should survive reconcile", 1, members.size)
  }

  /** RECONCILE_AND_COMMIT rejects when a populated table's schema changed. */
  @Test
  fun `131 - RECONCILE_AND_COMMIT rejects when populated table schema changed`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    // Build a modified config where the table's match field bitwidth differs.
    val table = config.p4Info.tablesList.first()
    val modifiedTable =
      table.toBuilder().setMatchFields(0, table.matchFieldsList.first().toBuilder().setBitwidth(32))
    val modifiedP4Info = config.p4Info.toBuilder().setTables(0, modifiedTable)
    val modifiedConfig = config.toBuilder().setP4Info(modifiedP4Info).build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "schema changed") {
      sendPipelineAction(
        SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT,
        modifiedConfig,
      )
    }
  }

  /** RECONCILE_AND_COMMIT preserves entries and forwarding still works. */
  @Test
  fun `132 - forwarding works after RECONCILE_AND_COMMIT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    // Packet with etherType 0x0800 should hit the table entry and be forwarded.
    val before = harness.simulatePacket(0, buildEthernetFrame(0x0800)).single().packetsList
    assertTrue("packet should be forwarded before reconcile", before.isNotEmpty())
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT, config)
    val after = harness.simulatePacket(0, buildEthernetFrame(0x0800)).single().packetsList
    assertTrue("packet should be forwarded after reconcile", after.isNotEmpty())
    assertEquals(
      "output port should match",
      before[0].dataplaneEgressPort,
      after[0].dataplaneEgressPort,
    )
  }

  /** RECONCILE_AND_COMMIT on identical pipeline updates the cookie. */
  @Test
  fun `133 - RECONCILE_AND_COMMIT updates cookie on identical pipeline`() {
    val config = loadBasicTableConfig()
    val oldCookie = ForwardingPipelineConfig.Cookie.newBuilder().setCookie(1).build()
    harness.loadPipeline(config, oldCookie)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    val newCookie = ForwardingPipelineConfig.Cookie.newBuilder().setCookie(2).build()
    sendPipelineAction(
      SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT,
      config,
      newCookie,
    )
    assertEquals("cookie should be updated", 2, harness.getConfig().config.cookie.cookie)
    assertEquals("entry should survive", 1, harness.readEntries().size)
  }

  /** RECONCILE_AND_COMMIT preserves modified default actions. */
  @Test
  fun `134 - RECONCILE_AND_COMMIT preserves modified default action`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val dropId = FourwardTestHarness.findAction(config, "drop").preamble.id
    harness.modifyEntry(FourwardTestHarness.buildDefaultActionEntity(table.preamble.id, dropId))
    // Verify the modified default is readable via is_default_action=true.
    val beforeDefaults = harness.readDefaultEntries(table.preamble.id)
    assertTrue("modified default should be readable", beforeDefaults.isNotEmpty())
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT, config)
    val afterDefaults = harness.readDefaultEntries(table.preamble.id)
    assertTrue("modified default should survive reconcile", afterDefaults.isNotEmpty())
    assertEquals(
      "default action should be drop",
      dropId,
      afterDefaults[0].tableEntry.action.action.actionId,
    )
  }

  /** RECONCILE_AND_COMMIT: populated tables preserved, empty changed tables tolerated. */
  @Test
  fun `135 - RECONCILE_AND_COMMIT mixed populated and empty tables`() {
    val config = loadMultiTableConfig()
    harness.loadPipeline(config)
    // Only install entries in the original exact table, leave ternary/LPM empty.
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    // Build a new config that keeps the exact table but changes the ternary table's bitwidth.
    // This should succeed because the ternary table has no entries.
    val p4Info = config.p4Info.toBuilder()
    val ternaryIdx = p4Info.tablesList.indexOfFirst { it.preamble.id == MULTI_TERNARY_TABLE_ID }
    val ternaryTable = p4Info.getTables(ternaryIdx)
    val modifiedTernary =
      ternaryTable
        .toBuilder()
        .setMatchFields(0, ternaryTable.matchFieldsList.first().toBuilder().setBitwidth(32))
    p4Info.setTables(ternaryIdx, modifiedTernary)
    val newConfig = config.toBuilder().setP4Info(p4Info).build()
    sendPipelineAction(SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT, newConfig)
    assertEquals("exact table entry should survive", 1, harness.readEntries().size)
  }

  // ---------------------------------------------------------------------------
  // Unsupported entity types (P4Runtime spec §9.6, §9.8, §15)
  // ---------------------------------------------------------------------------

  /** P4Runtime spec §9.6: reading an unknown ValueSetEntry returns empty. */
  @Test
  fun `81 - read unknown ValueSetEntry returns empty`() {
    harness.loadPipeline(loadBasicTableConfig())
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setValueSetEntry(P4RuntimeOuterClass.ValueSetEntry.newBuilder().setValueSetId(999))
        )
        .build()
    val results = harness.readEntries(request)
    assertEquals(0, results.size)
  }

  /** P4Runtime spec §9.9: reading an ExternEntry should fail with UNIMPLEMENTED. */
  @Test
  fun `82 - read ExternEntry rejected as UNIMPLEMENTED`() {
    harness.loadPipeline(loadBasicTableConfig())
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setExternEntry(P4RuntimeOuterClass.ExternEntry.newBuilder().setExternTypeId(1))
        )
        .build()
    assertGrpcError(Status.Code.UNIMPLEMENTED) { harness.readEntries(request) }
  }

  /** P4Runtime spec §9.8: reading a DigestEntry should fail with UNIMPLEMENTED. */
  @Test
  fun `83 - read DigestEntry rejected as UNIMPLEMENTED`() {
    harness.loadPipeline(loadBasicTableConfig())
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setDigestEntry(P4RuntimeOuterClass.DigestEntry.newBuilder().setDigestId(1))
        )
        .build()
    assertGrpcError(Status.Code.UNIMPLEMENTED) { harness.readEntries(request) }
  }

  /** P4Runtime spec §15: arbitration with a named role succeeds. */
  @Test
  fun `84 - arbitration with named role succeeds`() {
    harness.openStream().use { session ->
      val response = session.arbitrate(roleName = "sdn_controller")
      assertTrue(response.hasArbitration())
      assertEquals("sdn_controller", response.arbitration.role.name)
    }
  }

  /** P4Runtime spec §15: Role.config is rejected with UNIMPLEMENTED. */
  @Test
  fun `85 - arbitration with Role config rejected as UNIMPLEMENTED`() {
    assertGrpcError(Status.Code.UNIMPLEMENTED) {
      runBlocking {
        val request =
          StreamMessageRequest.newBuilder()
            .setArbitration(
              P4RuntimeOuterClass.MasterArbitrationUpdate.newBuilder()
                .setDeviceId(1)
                .setElectionId(Uint128.newBuilder().setHigh(0).setLow(1))
                .setRole(
                  P4RuntimeOuterClass.Role.newBuilder()
                    .setName("sdn_controller")
                    .setConfig(ProtoAny.getDefaultInstance())
                )
            )
            .build()
        harness.stub.streamChannel(flowOf(request)).collect {}
      }
    }
  }

  /** P4Runtime spec §15: different roles have independent primary elections. */
  @Test
  fun `86 - per-role independent primary election`() {
    harness.openStream().use { role1Primary ->
      harness.openStream().use { role2Primary ->
        // Both become primary for their respective roles.
        val r1 = role1Primary.arbitrate(electionId = 1, roleName = "role_a")
        val r2 = role2Primary.arbitrate(electionId = 1, roleName = "role_b")
        assertEquals(com.google.rpc.Code.OK_VALUE, r1.arbitration.status.code)
        assertEquals(com.google.rpc.Code.OK_VALUE, r2.arbitration.status.code)
      }
    }
  }

  /** P4Runtime spec §15: named-role controller cannot write entities outside its role. */
  @Test
  fun `87 - named-role write denied for non-matching entity`() {
    harness.loadPipeline(loadBasicTableConfig())
    // basic_table has no @p4runtime_role annotations → all entities belong to default role.
    harness.openStream().use { session -> session.arbitrate(roleName = "sdn_controller") }
    val entry = buildExactEntry(loadBasicTableConfig(), 1, 1)
    assertGrpcError(Status.Code.PERMISSION_DENIED, "role 'sdn_controller'") {
      harness.installEntry(entry, role = "sdn_controller")
    }
  }

  /** P4Runtime spec §15: wildcard read filters out entities not matching the controller's role. */
  @Test
  fun `88 - named-role wildcard read returns empty for non-matching entities`() {
    harness.loadPipeline(loadBasicTableConfig())
    // basic_table has no @p4runtime_role annotations → all entities belong to default role.
    // A named-role wildcard read returns empty (not PERMISSION_DENIED).
    harness.installEntry(buildExactEntry(loadBasicTableConfig(), 1, 1))
    val entries = harness.readTableEntries(0, role = "sdn_controller")
    assertTrue("wildcard read for non-matching role should return empty", entries.isEmpty())
  }

  /** P4Runtime spec §15: specific-entity read denied for non-matching role. */
  @Test
  fun `88a - named-role specific read denied for non-matching entity`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val tableId = config.p4Info.tablesList.first().preamble.id
    assertGrpcError(Status.Code.PERMISSION_DENIED, "role 'sdn_controller'") {
      harness.readTableEntries(tableId, role = "sdn_controller")
    }
  }

  /** P4Runtime spec §15: default-role controller has full access to all entities. */
  @Test
  fun `89 - default-role controller has full access`() {
    harness.loadPipeline(loadBasicTableConfig())
    // Default role (empty) can write and read everything.
    val entry = buildExactEntry(loadBasicTableConfig(), 1, 1)
    harness.installEntry(entry)
    val entries = harness.readEntries()
    assertFalse("should have entries", entries.isEmpty())
  }

  /** P4Runtime spec §5: writes rejected after all controllers for a role disconnect. */
  @Test
  fun `90 - write rejected after last controller disconnects`() {
    harness.loadPipeline(loadBasicTableConfig())
    // Arbitrate, then close the stream — no active primary remains.
    harness.openStream().use { session -> session.arbitrate() }
    val entry = buildExactEntry(loadBasicTableConfig(), 1, 1)
    assertGrpcError(Status.Code.PERMISSION_DENIED) {
      harness.installEntry(entry, electionId = uint128(low = 1))
    }
  }

  /** P4Runtime spec §15: re-arbitration with a different role updates old role's primary. */
  @Test
  fun `91 - role change on re-arbitration clears old role primary`() {
    harness.loadPipeline(loadBasicTableConfig())
    harness.openStream().use { session ->
      // Become primary for role_a.
      val r1 = session.arbitrate(roleName = "role_a")
      assertEquals(com.google.rpc.Code.OK_VALUE, r1.arbitration.status.code)
      // Switch to role_b — should clear role_a's primary.
      val r2 = session.arbitrate(roleName = "role_b")
      assertEquals(com.google.rpc.Code.OK_VALUE, r2.arbitration.status.code)
      // role_a should have no primary anymore. A write claiming role_a primary should fail.
      val entry = buildExactEntry(loadBasicTableConfig(), 1, 1)
      assertGrpcError(Status.Code.PERMISSION_DENIED) {
        harness.installEntry(entry, electionId = uint128(low = 1), role = "role_a")
      }
    }
  }

  /** P4Runtime spec §5: election_id=0 with a named role is a backup, cannot be primary. */
  @Test
  fun `92 - zero election_id with named role is backup`() {
    harness.openStream().use { session ->
      val response = session.arbitrate(electionId = 0, roleName = "sdn_controller")
      // Status should be ALREADY_EXISTS (non-primary) since election_id=0 cannot be primary.
      assertEquals(com.google.rpc.Code.ALREADY_EXISTS_VALUE, response.arbitration.status.code)
    }
  }

  // =========================================================================
  // Coverage-guided tests (Phase 4)
  // =========================================================================

  /** P4Runtime spec §7: UNSPECIFIED pipeline action must be rejected. */
  @Test
  fun `100 - UNSPECIFIED pipeline action returns INVALID_ARGUMENT`() {
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unrecognized") {
      sendPipelineAction(SetForwardingPipelineConfigRequest.Action.UNSPECIFIED)
    }
  }

  /** StreamChannel: digest ack is not supported and must be explicitly rejected. */
  @Test
  fun `101 - digest ack via stream returns UNIMPLEMENTED`() {
    assertGrpcError(Status.Code.UNIMPLEMENTED, "digest") {
      runBlocking {
        val request =
          StreamMessageRequest.newBuilder()
            .setDigestAck(P4RuntimeOuterClass.DigestListAck.newBuilder().setDigestId(1))
            .build()
        harness.stub.streamChannel(flowOf(request)).collect {}
      }
    }
  }

  /** P4Runtime spec §5: when primary self-demotes, backup receives a promotion notification. */
  @Test
  fun `102 - backup promoted when primary self-demotes`() {
    harness.openStream().use { streamA ->
      harness.openStream().use { streamB ->
        // A becomes primary with electionId=5.
        val a1 = streamA.arbitrate(electionId = 5)
        assertEquals(com.google.rpc.Code.OK_VALUE, a1.arbitration.status.code)
        // B is backup with electionId=3.
        val b1 = streamB.arbitrate(electionId = 3)
        assertEquals(com.google.rpc.Code.ALREADY_EXISTS_VALUE, b1.arbitration.status.code)
        // A self-demotes by re-arbitrating with electionId=1.
        val a2 = streamA.arbitrate(electionId = 1)
        assertEquals(
          "A should now be backup",
          com.google.rpc.Code.ALREADY_EXISTS_VALUE,
          a2.arbitration.status.code,
        )
        // B should receive a promotion notification.
        val promotion = streamB.receiveNext()
        assertNotNull("backup should receive promotion", promotion)
        assertTrue("should be arbitration", promotion!!.hasArbitration())
        assertEquals(
          "B should now be primary",
          com.google.rpc.Code.OK_VALUE,
          promotion.arbitration.status.code,
        )
      }
    }
  }

  /** P4Runtime spec §15: named-role entity access check covers action profile member. */
  @Test
  fun `103 - named-role write denied for action profile member`() {
    harness.loadPipeline(loadBasicTableConfig())
    val member =
      FourwardTestHarness.buildMemberEntity(actionProfileId = 1, memberId = 1, actionId = 1)
    assertGrpcError(Status.Code.PERMISSION_DENIED, "cannot access") {
      harness.installEntry(member, role = "sdn_controller")
    }
  }

  /** P4Runtime spec §15: named-role entity access check covers direct counter. */
  @Test
  fun `104 - named-role write denied for direct counter entity`() {
    harness.loadPipeline(loadBasicTableConfig())
    val directCounter =
      Entity.newBuilder()
        .setDirectCounterEntry(
          P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(1))
        )
        .build()
    assertGrpcError(Status.Code.PERMISSION_DENIED, "cannot access") {
      harness.installEntry(directCounter, role = "sdn_controller")
    }
  }

  /** P4Runtime spec §15: unscoped entities (counters, meters, registers) pass role checks. */
  @Test
  fun `105 - named-role read allows unscoped counter entity`() {
    harness.loadPipeline(loadConfigWithCounter())
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .setRole("sdn_controller")
        .addEntities(
          Entity.newBuilder()
            .setCounterEntry(P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(CTR_ID))
        )
        .build()
    val results = harness.readEntries(request)
    assertEquals("named-role read of unscoped counter should succeed", CTR_SIZE, results.size)
  }

  /** DataplaneService: InjectPacket returns both output packets and trace. */
  @Test
  fun `106 - InjectPacket returns output and trace`() {
    harness.loadPipeline(loadPassthroughConfig())
    val resp = harness.injectPacket(0, byteArrayOf(0x01, 0x02))
    assertTrue("should have output packets", resp.possibleOutcomesList.any { it.packetsCount > 0 })
    assertTrue("trace should be present", resp.hasTrace())
  }

  // =========================================================================
  // §6: P4Info message — ID allocation (scenario 107)
  // =========================================================================

  /** §6.3: P4Info IDs have an 8-bit type prefix; all tables share the same prefix, etc. */
  @Test
  fun `107 - p4info IDs have consistent type prefixes`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // All table IDs should share the same 8-bit prefix.
    val tablePrefixes = config.p4Info.tablesList.map { it.preamble.id ushr 24 }.toSet()
    assertEquals("all table IDs should share one prefix", 1, tablePrefixes.size)
    // All action IDs should share the same 8-bit prefix (different from tables).
    val actionPrefixes = config.p4Info.actionsList.map { it.preamble.id ushr 24 }.toSet()
    assertEquals("all action IDs should share one prefix", 1, actionPrefixes.size)
    // Table and action prefixes should differ.
    assertTrue(
      "table and action prefixes should differ",
      tablePrefixes.first() != actionPrefixes.first(),
    )
  }

  // =========================================================================
  // §8.1: Default-valued fields (scenarios 108-109)
  // =========================================================================

  /** §8.1: A zero-valued exact match field is valid and distinct from "unset". */
  @Test
  fun `108 - zero-valued exact match field accepted and round-trips`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0, port = 1)
    harness.installEntry(entry)
    val results = harness.readEntries()
    assertEquals(1, results.size)
    // The match value should read back as all-zero bytes, not be dropped.
    val readMatch = results[0].tableEntry.matchList.first().exact.value
    assertTrue("match value should be present", readMatch.size() > 0)
    assertTrue("match value should be all zeros", readMatch.toByteArray().all { it == 0.toByte() })
  }

  /** §8.1: An action parameter with value zero round-trips correctly. */
  @Test
  fun `109 - zero-valued action parameter round-trips`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // Port = 0 is a valid zero-valued parameter.
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 0)
    harness.installEntry(entry)
    val results = harness.readEntry(buildReadFilter(config, 0x0800))
    assertEquals(1, results.size)
    val param = results[0].tableEntry.action.action.paramsList.first()
    assertTrue("zero param should be present", param.value.size() > 0)
    assertTrue("param value should be zero", param.value.toByteArray().all { it == 0.toByte() })
  }

  // =========================================================================
  // §8.2: Read-write symmetry (scenarios 110-113)
  // =========================================================================

  /**
   * §8.3: Per the spec's read-write symmetry property, "the encoder of a P4Runtime request or reply
   * uses the shortest strings that fit the encoded integer values" — so reads return canonical form
   * regardless of the encoding the client wrote. The round-trip identity holds after canonicalizing
   * the written entry.
   */
  @Test
  fun `110 - table entry read-write symmetry`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    val readBack = harness.readEntry(buildReadFilter(config, 0x0800))
    assertEquals(1, readBack.size)
    val canonicalWritten = canonicalizeTableEntry(entry.tableEntry)
    val read = readBack[0].tableEntry
    assertEquals("table_id must match", canonicalWritten.tableId, read.tableId)
    assertEquals("match fields must match", canonicalWritten.matchList, read.matchList)
    assertEquals("action must match", canonicalWritten.action, read.action)
  }

  /** §8.2: Action profile member round-trips identically. */
  @Test
  fun `111 - action profile member read-write symmetry`() {
    val (profileId, dropId) = loadActionSelector()
    val member = buildMemberEntity(actionProfileId = profileId, memberId = 1, actionId = dropId)
    harness.installEntry(member)
    val readBack = harness.readProfileMembers(actionProfileId = profileId)
    assertEquals(1, readBack.size)
    val written = member.actionProfileMember
    val read = readBack[0].actionProfileMember
    assertEquals("profile ID must match", written.actionProfileId, read.actionProfileId)
    assertEquals("member ID must match", written.memberId, read.memberId)
    assertEquals("action must match", written.action, read.action)
  }

  /** §8.2: Action profile group round-trips identically. */
  @Test
  fun `112 - action profile group read-write symmetry`() {
    val (profileId, _) = loadActionSelector()
    val group = buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = listOf(1, 2))
    harness.installEntry(group)
    val readBack = harness.readProfileGroups(actionProfileId = profileId)
    assertEquals(1, readBack.size)
    val written = group.actionProfileGroup
    val read = readBack[0].actionProfileGroup
    assertEquals("profile ID must match", written.actionProfileId, read.actionProfileId)
    assertEquals("group ID must match", written.groupId, read.groupId)
    assertEquals("member count must match", written.membersCount, read.membersCount)
  }

  /** §8.2: Register entry round-trips identically. */
  @Test
  fun `113 - register entry read-write symmetry`() {
    harness.loadPipeline(loadConfigWithRegister())
    val entry =
      FourwardTestHarness.buildRegisterEntry(REG_ID, index = 0, value = 42, byteLen = REG_BYTEWIDTH)
    harness.modifyEntry(entry)
    val readBack = harness.readRegisterEntries(REG_ID)
    val read =
      checkNotNull(readBack.find { it.registerEntry.index.index == 0L }) {
        "register entry should be readable"
      }
    assertEquals("register value must match", entry.registerEntry.data, read.registerEntry.data)
  }

  // =========================================================================
  // §9.1.5: Preinitialized / const tables (scenarios 114-116)
  // =========================================================================

  /** §9.1.5: Const entries are immutable — INSERT rejected. */
  @Test
  fun `114 - INSERT into const table returns INVALID_ARGUMENT`() {
    val config = loadConstEntriesConfig()
    harness.loadPipeline(config)
    val constTable = checkNotNull(config.p4Info.tablesList.find { it.isConstTable })
    val entry =
      Entity.newBuilder()
        .setTableEntry(
          P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(constTable.preamble.id)
        )
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  /** §9.1.5: Const entries are readable via wildcard read. */
  @Test
  fun `115 - wildcard read includes const table entries`() {
    val config = loadConstEntriesConfig()
    harness.loadPipeline(config)
    val constTable = checkNotNull(config.p4Info.tablesList.find { it.isConstTable })
    val constEntries = harness.readTableEntries(constTable.preamble.id)
    assertTrue("const entries should appear in read", constEntries.isNotEmpty())
  }

  /** §9.1.5: MODIFY on const table entry is rejected. */
  @Test
  fun `116 - MODIFY const table entry returns INVALID_ARGUMENT`() {
    val config = loadConstEntriesConfig()
    harness.loadPipeline(config)
    val constTable = checkNotNull(config.p4Info.tablesList.find { it.isConstTable })
    val existing = harness.readTableEntries(constTable.preamble.id)
    assertTrue(existing.isNotEmpty())
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.modifyEntry(existing[0]) }
  }

  // =========================================================================
  // §9.1.8: Idle timeout (scenario 117)
  // =========================================================================

  /** §9.1.8: idle_timeout_ns is rejected — no wall-clock time in a reference simulator. */
  @Test
  fun `117 - idle_timeout_ns on table entry returns UNIMPLEMENTED`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val base = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entry =
      base.toBuilder().setTableEntry(base.tableEntry.toBuilder().setIdleTimeoutNs(1000000)).build()
    assertGrpcError(Status.Code.UNIMPLEMENTED) { harness.installEntry(entry) }
  }

  // =========================================================================
  // §9.2.2: Action profile group rules (scenarios 118-119)
  // =========================================================================

  /** §9.2.2: Empty group (zero members) is accepted. */
  @Test
  fun `118 - empty action profile group accepted`() {
    val (profileId, _) = loadActionSelector()
    val group = buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = emptyList())
    harness.installEntry(group)
    val results = harness.readProfileGroups(actionProfileId = profileId)
    assertEquals(1, results.size)
    assertEquals(0, results[0].actionProfileGroup.membersCount)
  }

  /** §9.2.2: Group member list is fully replaced on MODIFY. */
  @Test
  fun `119 - group modify replaces member list entirely`() {
    val (profileId, _) = loadActionSelector()
    val group =
      buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = listOf(1, 2, 3))
    harness.installEntry(group)
    val modified = buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = listOf(4))
    harness.modifyEntry(modified)
    val results = harness.readProfileGroups(actionProfileId = profileId)
    assertEquals(1, results.size)
    assertEquals(1, results[0].actionProfileGroup.membersCount)
    assertEquals(4, results[0].actionProfileGroup.membersList[0].memberId)
  }

  // =========================================================================
  // §10: Error reporting (scenarios 120-121)
  // =========================================================================

  /** §10: Per-update errors include canonical_code and message text. */
  @Test
  fun `120 - batch error details include canonical_code and message`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // Insert two entries: one valid, one with bad table_id. CONTINUE_ON_ERROR.
    val good = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val bad =
      Entity.newBuilder()
        .setTableEntry(P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(999999))
        .build()
    val request =
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .setAtomicity(WriteRequest.Atomicity.CONTINUE_ON_ERROR)
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(good))
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(bad))
        .build()
    val errors = assertBatchError { harness.writeRaw(request) }
    assertEquals("should have 2 per-update statuses", 2, errors.size)
    assertEquals(com.google.rpc.Code.OK_VALUE, errors[0].canonicalCode)
    assertTrue("bad update should have error code", errors[1].canonicalCode != 0)
    assertTrue("bad update should have error message", errors[1].message.isNotEmpty())
  }

  /** §10: Single-update write errors are wrapped as per-update batch errors. */
  @Test
  fun `121 - single-update error has structured details in trailers`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val bad =
      Entity.newBuilder()
        .setTableEntry(P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(999999))
        .build()
    val errors = assertBatchError { harness.installEntry(bad) }
    assertEquals(1, errors.size)
    assertTrue("should have non-OK code", errors[0].canonicalCode != 0)
  }

  /** §10/§13.3: Read batch errors use the same 1:1 structured details as Write. */
  @Test
  fun `121b - read batch error has structured details in trailers`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder().setTableEntry(P4RuntimeOuterClass.TableEntry.newBuilder()).build()
        )
        .addEntities(
          Entity.newBuilder()
            .setDigestEntry(P4RuntimeOuterClass.DigestEntry.newBuilder().setDigestId(1))
            .build()
        )
        .build()
    val errors = assertBatchError { harness.readEntries(request) }
    assertEquals("should have 2 per-read statuses", 2, errors.size)
    assertEquals(com.google.rpc.Code.OK_VALUE, errors[0].canonicalCode)
    assertEquals(com.google.rpc.Code.UNIMPLEMENTED_VALUE, errors[1].canonicalCode)
  }

  // =========================================================================
  // §12.1: Write batch ordering — cross-entity-type (scenario 122)
  // =========================================================================

  /** §12.1: Batch with member INSERT then group INSERT referencing it succeeds in order. */
  @Test
  fun `122 - cross-entity batch ordering preserves dependencies`() {
    val (profileId, dropId) = loadActionSelector()
    val member = buildMemberEntity(actionProfileId = profileId, memberId = 1, actionId = dropId)
    val group = buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = listOf(1))
    // Single batch: insert member, then insert group referencing that member.
    val request =
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(member))
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(group))
        .build()
    harness.writeRaw(request)
    assertEquals(1, harness.readProfileMembers(actionProfileId = profileId).size)
    assertEquals(1, harness.readProfileGroups(actionProfileId = profileId).size)
  }

  // =========================================================================
  // §13.3: Read batch — multiple entity types (scenarios 123-124)
  // =========================================================================

  /** §13.3: Single Read request with both table entries and action profile members. */
  @Test
  fun `123 - read batch with multiple entity types`() {
    val config = loadConfig("e2e_tests/trace_tree/action_selector_3.txtpb")
    harness.loadPipeline(config)
    val profileId = config.p4Info.actionProfilesList.first().preamble.id
    val dropId = FourwardTestHarness.findAction(config, "drop").preamble.id
    val table = config.p4Info.tablesList.first()
    val tableEntry =
      Entity.newBuilder()
        .setTableEntry(
          P4RuntimeOuterClass.TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(table.matchFieldsList.first().id)
                .setExact(
                  P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(
                      ByteString.copyFrom(
                        FourwardTestHarness.longToBytes(
                          0x0800,
                          (table.matchFieldsList.first().bitwidth + 7) / 8,
                        )
                      )
                    )
                )
            )
            .setAction(P4RuntimeOuterClass.TableAction.newBuilder().setActionProfileGroupId(1))
            .setPriority(0)
        )
        .build()
    val member = buildMemberEntity(actionProfileId = profileId, memberId = 1, actionId = dropId)
    harness.installEntry(member)
    val group = buildGroupEntity(actionProfileId = profileId, groupId = 1, memberIds = listOf(1))
    harness.installEntry(group)
    harness.installEntry(tableEntry)

    // Read both entity types in a single request.
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setTableEntry(
              P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(table.preamble.id)
            )
        )
        .addEntities(
          Entity.newBuilder()
            .setActionProfileMember(
              P4RuntimeOuterClass.ActionProfileMember.newBuilder().setActionProfileId(profileId)
            )
        )
        .build()
    val results = harness.readEntries(request)
    val hasTable = results.any { it.hasTableEntry() }
    val hasMembers = results.any { it.hasActionProfileMember() }
    assertTrue("response should contain table entries", hasTable)
    assertTrue("response should contain profile members", hasMembers)
  }

  /** §13.3: Wildcard read for counters and registers in a single request. */
  @Test
  fun `124 - read batch with counter and register entities`() {
    // Build a config with both counters and registers by stacking the synthetic p4info entities.
    val regConfig = loadConfigWithRegister()
    val counterP4Info =
      regConfig.p4Info
        .toBuilder()
        .addCounters(
          p4.config.v1.P4InfoOuterClass.Counter.newBuilder()
            .setPreamble(
              p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                .setId(CTR_ID)
                .setName("my_counter")
                .setAlias("my_counter")
            )
            .setSize(CTR_SIZE.toLong())
            .setSpec(
              p4.config.v1.P4InfoOuterClass.CounterSpec.newBuilder()
                .setUnit(p4.config.v1.P4InfoOuterClass.CounterSpec.Unit.BOTH)
            )
        )
    val combined = regConfig.toBuilder().setP4Info(counterP4Info).build()
    harness.loadPipeline(combined)

    // Write a register value.
    harness.modifyEntry(
      FourwardTestHarness.buildRegisterEntry(REG_ID, index = 0, value = 7, byteLen = REG_BYTEWIDTH)
    )

    // Read both counters and registers in a single request.
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setCounterEntry(P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(CTR_ID))
        )
        .addEntities(
          Entity.newBuilder()
            .setRegisterEntry(P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(REG_ID))
        )
        .build()
    val results = harness.readEntries(request)
    val hasCounters = results.any { it.hasCounterEntry() }
    val hasRegisters = results.any { it.hasRegisterEntry() }
    assertTrue("response should contain counter entries", hasCounters)
    assertTrue("response should contain register entries", hasRegisters)
  }

  // =========================================================================
  // §19: Versioning (scenario 125)
  // =========================================================================

  /** §19: Capabilities response includes a properly formatted version string. */
  @Test
  fun `125 - capabilities version follows semver format`() {
    val resp = harness.capabilities()
    assertTrue("version should be non-empty", resp.p4RuntimeApiVersion.isNotEmpty())
    // Version should contain at least a major.minor pattern.
    assertTrue(
      "version should match semver pattern: ${resp.p4RuntimeApiVersion}",
      resp.p4RuntimeApiVersion.matches(Regex("\\d+\\.\\d+.*")),
    )
  }

  // ---------------------------------------------------------------------------
  // Test helpers
  // ---------------------------------------------------------------------------

  private fun buildReadFilter(config: PipelineConfig, matchValue: Long): Entity =
    FourwardTestHarness.buildMatchFilter(config, matchValue)

  /** Sends a raw SetForwardingPipelineConfig — bypasses the harness to test validation paths. */
  private fun loadRawPipeline(config: ForwardingPipelineConfig.Builder) = runBlocking {
    val unused =
      harness.stub.setForwardingPipelineConfig(
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
          .setConfig(config)
          .build()
      )
  }

  /** Sends a SetForwardingPipelineConfig with a specific action. */
  private fun sendPipelineAction(
    action: SetForwardingPipelineConfigRequest.Action,
    config: PipelineConfig = loadBasicTableConfig(),
    cookie: ForwardingPipelineConfig.Cookie = ForwardingPipelineConfig.Cookie.getDefaultInstance(),
  ) = runBlocking {
    val fwdConfig =
      ForwardingPipelineConfig.newBuilder()
        .setP4Info(config.p4Info)
        .setP4DeviceConfig(config.device.toByteString())
        .setCookie(cookie)
    val unused =
      harness.stub.setForwardingPipelineConfig(
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setAction(action)
          .setConfig(fwdConfig)
          .build()
      )
  }

  companion object {
    private const val REG_ID = 500
    private const val REG_BITWIDTH = 32
    private const val REG_BYTEWIDTH = REG_BITWIDTH / 8
    private const val REG_SIZE = 4
    private const val CTR_ID = 600
    private const val CTR_SIZE = 4
    private const val MTR_ID = 700
    private const val MTR_SIZE = 4
    private const val DCTR_ID = 800
    private const val DMTR_ID = 900
    private const val MULTI_TERNARY_TABLE_ID = 1000
    private const val MULTI_LPM_TABLE_ID = 1001
  }
}
