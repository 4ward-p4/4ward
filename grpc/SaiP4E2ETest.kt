package fourward.grpc

import com.google.protobuf.ByteString
import fourward.DataplaneGrpcKt.DataplaneCoroutineStub
import fourward.PipelineConfig
import fourward.SubscribeResultsRequest
import fourward.SubscribeResultsResponse
import fourward.TranslationEntry
import fourward.TypeTranslation
import io.grpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * End-to-end test of the P4Runtime server with SAI P4 (middleblock).
 *
 * SAI P4 uses `@p4runtime_translation("", string)` on all ID types (vrf_id_t, nexthop_id_t,
 * router_interface_id_t, port_id_t, etc.). String SDN values are UTF-8-encoded in the proto `bytes`
 * field. This test verifies that the full stack — p4c-4ward compilation, simulator loading,
 * P4Runtime Write/Read with sdn_string translation — works end-to-end.
 */
class SaiP4E2ETest {

  private lateinit var harness: FourwardTestHarness
  private lateinit var config: PipelineConfig

  @Before
  fun setUp() {
    harness = FourwardTestHarness()
    config = FourwardTestHarness.loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
    // Pin P4RT ↔ dataplane port mappings for architecture-defined and test-specific ports.
    // On a real switch, these come from platform config; in tests, we pin them so auto-allocation
    // (for table entry ports like "Ethernet0") doesn't conflict with ports that must map to
    // specific dataplane values (CPU port 510, multicast replica ports 0/1).
    config =
      withPortMappings(
        config,
        mapOf(
          "0" to 0,
          "1" to 1,
          MIRROR_PORT.toString() to MIRROR_PORT,
          CPU_PORT.toString() to CPU_PORT,
        ),
      )
    harness.loadPipeline(config)
  }

  @After
  fun tearDown() {
    harness.close()
  }

  // =========================================================================
  // Pipeline loading
  // =========================================================================

  @Test
  fun `SAI P4 middleblock pipeline loads successfully`() {
    // Pipeline was loaded in setUp — verify p4info has the expected tables.
    val tableNames = config.p4Info.tablesList.map { it.preamble.alias }
    assertTrue("vrf_table should be present", "vrf_table" in tableNames)
    assertTrue("ipv4_table should be present", "ipv4_table" in tableNames)
    assertTrue("nexthop_table should be present", "nexthop_table" in tableNames)
    assertTrue("router_interface_table should be present", "router_interface_table" in tableNames)
    assertTrue("neighbor_table should be present", "neighbor_table" in tableNames)
  }

  @Test
  fun `pipeline config has VRF translation mapping from annotation`() {
    // The SAI P4 source declares @p4runtime_translation_mappings({ {"", 0} }) on vrf_id_t.
    // The p4c-4ward backend extracts this into DeviceConfig.translations.
    val translations = config.device.translationsList
    assertTrue("expected at least one translation", translations.isNotEmpty())

    val vrfTranslation = translations.find { it.typeName == "vrf_id_t" }
    assertTrue("expected translation for vrf_id_t", vrfTranslation != null)
    assertTrue("expected auto_allocate=true", vrfTranslation!!.autoAllocate)
    assertEquals("expected one explicit entry", 1, vrfTranslation.entriesCount)

    val entry = vrfTranslation.entriesList[0]
    assertEquals("SDN value should be empty string", "", entry.sdnStr)
    // Dataplane value is 0 encoded as 2 bytes (VRF_BITWIDTH=10 → 2 bytes).
    assertEquals("dataplane value should be 2 bytes", 2, entry.dataplaneValue.size())
    assertTrue(
      "dataplane value should be all zeros",
      entry.dataplaneValue.toByteArray().all { it == 0.toByte() },
    )
  }

  @Test
  fun `p4info has sdn_string translated types`() {
    val translatedTypes = config.p4Info.typeInfo.newTypesMap
    val stringTypes =
      translatedTypes.filter { (_, spec) ->
        spec.hasTranslatedType() && spec.translatedType.hasSdnString()
      }
    // SAI P4 declares 8 string-translated types (all use URI "").
    assertTrue(
      "expected at least 8 sdn_string types, got ${stringTypes.size}",
      stringTypes.size >= 8,
    )
    assertTrue("vrf_id_t should be sdn_string", "vrf_id_t" in stringTypes)
    assertTrue("nexthop_id_t should be sdn_string", "nexthop_id_t" in stringTypes)
    assertTrue("port_id_t should be sdn_string", "port_id_t" in stringTypes)
  }

  // =========================================================================
  // VRF table: sdn_string match field
  // =========================================================================

  @Test
  fun `vrf_table entry with string vrf_id round-trips`() {
    val vrfEntry = buildVrfEntry("vrf-1")
    harness.installEntry(vrfEntry)

    val vrfTable = findTable("vrf_table")
    val entities = harness.readTableEntries(vrfTable.preamble.id)
    assertEquals("expected one vrf_table entry", 1, entities.size)

    // The match field (vrf_id) should round-trip as a UTF-8 string.
    val readMatch = entities[0].tableEntry.matchList.first()
    assertEquals("vrf-1", readMatch.exact.value.toStringUtf8())
  }

  @Test
  fun `multiple vrf_table entries with different string IDs`() {
    harness.installEntry(buildVrfEntry("vrf-1"))
    harness.installEntry(buildVrfEntry("vrf-2"))
    harness.installEntry(buildVrfEntry("vrf-3"))

    val vrfTable = findTable("vrf_table")
    val entities = harness.readTableEntries(vrfTable.preamble.id)
    assertEquals("expected three vrf_table entries", 3, entities.size)

    val vrfIds = entities.map { it.tableEntry.matchList.first().exact.value.toStringUtf8() }.toSet()
    assertEquals(setOf("vrf-1", "vrf-2", "vrf-3"), vrfIds)
  }

  // =========================================================================
  // IPv4 routing: string vrf_id match + set_nexthop_id action param
  // =========================================================================

  @Test
  fun `ipv4_table entry with translated vrf_id and nexthop_id round-trips`() {
    // Install prerequisite entries: VRF + full nexthop chain (@refers_to enforcement).
    harness.installEntry(buildVrfEntry("vrf-10"))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet0"))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry("nhop-1", "rif-1"))

    // Install an IPv4 route: vrf_id="vrf-10", ipv4_dst=10.0.0.0/8 → set_nexthop_id("nhop-1").
    val ipv4Entry = buildIpv4RouteEntry("vrf-10", "nhop-1")
    harness.installEntry(ipv4Entry)

    val ipv4Table = findTable("ipv4_table")
    val entities = harness.readTableEntries(ipv4Table.preamble.id)
    assertEquals("expected one ipv4_table entry", 1, entities.size)

    val entry = entities[0].tableEntry

    // Verify vrf_id match field round-trips as string.
    val vrfFieldId = matchFieldId(ipv4Table, "vrf_id")
    val vrfMatch = entry.matchList.find { it.fieldId == vrfFieldId }!!
    assertEquals("vrf-10", vrfMatch.exact.value.toStringUtf8())

    // Verify set_nexthop_id action param round-trips as string.
    val action = entry.action.action
    val setNexthopId = findAction("set_nexthop_id")
    assertEquals(setNexthopId.preamble.id, action.actionId)
    val nexthopParamId = paramId(setNexthopId, "nexthop_id")
    val nexthopParam = action.paramsList.find { it.paramId == nexthopParamId }!!
    assertEquals("nhop-1", nexthopParam.value.toStringUtf8())
  }

  // =========================================================================
  // Nexthop table: string match + action params with translated types
  // =========================================================================

  @Test
  fun `nexthop_table entry with set_ip_nexthop round-trips`() {
    // Install prerequisites: RIF + neighbor (@refers_to enforcement).
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet0"))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))

    val nexthopEntry = buildNexthopEntry("nhop-1", "rif-1")
    harness.installEntry(nexthopEntry)

    val nexthopTable = findTable("nexthop_table")
    val entities = harness.readTableEntries(nexthopTable.preamble.id)
    assertEquals("expected one nexthop_table entry", 1, entities.size)

    val entry = entities[0].tableEntry

    // Verify nexthop_id match round-trips as string.
    val nexthopMatch = entry.matchList.first()
    assertEquals("nhop-1", nexthopMatch.exact.value.toStringUtf8())

    // Verify router_interface_id action param round-trips as string.
    val action = entry.action.action
    val setIpNexthop = findAction("set_ip_nexthop")
    val rifParamId = paramId(setIpNexthop, "router_interface_id")
    val rifParam = action.paramsList.find { it.paramId == rifParamId }!!
    assertEquals("rif-1", rifParam.value.toStringUtf8())
  }

  // =========================================================================
  // Router interface table: port_id_t in action param (the SAI "port as string" pattern)
  // =========================================================================

  @Test
  fun `router_interface_table entry with port_id_t param round-trips`() {
    val rifEntry = buildRouterInterfaceEntry("rif-1", "Ethernet0")
    harness.installEntry(rifEntry)

    val rifTable = findTable("router_interface_table")
    val entities = harness.readTableEntries(rifTable.preamble.id)
    assertEquals("expected one router_interface_table entry", 1, entities.size)

    val entry = entities[0].tableEntry

    // router_interface_id match (string).
    val rifMatch = entry.matchList.first()
    assertEquals("rif-1", rifMatch.exact.value.toStringUtf8())

    // port param (port_id_t, string) and src_mac param (48-bit, not translated).
    val action = entry.action.action
    val setPortAndSrcMac = findAction("set_port_and_src_mac")
    val portParamId = paramId(setPortAndSrcMac, "port")
    val portParam = action.paramsList.find { it.paramId == portParamId }!!
    assertEquals("Ethernet0", portParam.value.toStringUtf8())
  }

  // =========================================================================
  // @refers_to: referential integrity enforcement
  // =========================================================================

  @Test
  fun `ipv4_table insert with missing vrf_id reference is rejected`() {
    // ipv4_table.vrf_id @refers_to(vrf_table, vrf_id) — no VRF installed.
    FourwardTestHarness.assertGrpcError(Status.Code.INVALID_ARGUMENT, "@refers_to") {
      harness.installEntry(buildIpv4RouteEntry(vrfId = "nonexistent-vrf", nexthopId = "nhop-1"))
    }
  }

  @Test
  fun `ipv4_table insert succeeds after referenced vrf and nexthop are installed`() {
    // Install all prerequisites in dependency order.
    harness.installEntry(buildVrfEntry("vrf-1"))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet0"))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry("nhop-1", "rif-1"))
    // Now the references exist — insert should succeed.
    harness.installEntry(buildIpv4RouteEntry(vrfId = "vrf-1", nexthopId = "nhop-1"))
  }

  @Test
  fun `nexthop set_ip_nexthop with missing router_interface_id is rejected`() {
    // set_ip_nexthop.router_interface_id @refers_to(router_interface_table, router_interface_id).
    FourwardTestHarness.assertGrpcError(Status.Code.INVALID_ARGUMENT, "@refers_to") {
      harness.installEntry(buildNexthopEntry("nhop-1", "nonexistent-rif"))
    }
  }

  @Test
  fun `nexthop set_ip_nexthop succeeds after referenced entries are installed`() {
    // RIF → neighbor → nexthop (dependency order).
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet0"))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry("nhop-1", "rif-1"))
  }

  @Test
  fun `ipv4_multicast set_multicast_group_id with missing group is rejected`() {
    // Install default VRF (needed for vrf_id match field), but not the multicast group.
    harness.installEntry(buildVrfEntry(""))
    val mcastTable = findTable("ipv4_multicast_table")
    val setMcastGroup = findAction("set_multicast_group_id")
    FourwardTestHarness.assertGrpcError(Status.Code.INVALID_ARGUMENT, "multicast") {
      harness.installEntry(
        buildEntry(
          mcastTable,
          setMcastGroup,
          matches =
            listOf(
              exactMatch(mcastTable, "vrf_id", ""),
              exactMatch(mcastTable, "ipv4_dst", byteArrayOf(224.toByte(), 0, 0, 1)),
            ),
          params = listOf(bytesParam(setMcastGroup, "multicast_group_id", byteArrayOf(0, 1))),
        )
      )
    }
  }

  @Test
  fun `ipv4_multicast set_multicast_group_id succeeds after group is installed`() {
    harness.installEntry(buildVrfEntry(""))
    installMulticastGroup(groupId = 1, p4rtPorts = listOf("0", "1"))
    val mcastTable = findTable("ipv4_multicast_table")
    val setMcastGroup = findAction("set_multicast_group_id")
    harness.installEntry(
      buildEntry(
        mcastTable,
        setMcastGroup,
        matches =
          listOf(
            exactMatch(mcastTable, "vrf_id", ""),
            exactMatch(mcastTable, "ipv4_dst", byteArrayOf(224.toByte(), 0, 0, 1)),
          ),
        params = listOf(bytesParam(setMcastGroup, "multicast_group_id", byteArrayOf(0, 1))),
      )
    )
  }

  @Test
  fun `delete bypasses refers_to check`() {
    // Install full chain so insert succeeds.
    harness.installEntry(buildVrfEntry("vrf-del"))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet0"))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry("nhop-1", "rif-1"))
    val ipv4Entry = buildIpv4RouteEntry(vrfId = "vrf-del", nexthopId = "nhop-1")
    harness.installEntry(ipv4Entry)

    // Delete the VRF first — no reverse-reference check on DELETE.
    harness.deleteEntry(buildVrfEntry("vrf-del"))
    // Delete the ipv4 entry — DELETE skips @refers_to.
    harness.deleteEntry(ipv4Entry)
  }

  // =========================================================================
  // Packet forwarding
  // =========================================================================

  @Test
  fun `IPv4 packet is forwarded with correct MAC rewrites and TTL decrement`() {
    // Install routing entries in dependency order (@refers_to enforcement).
    harness.installEntry(buildVrfEntry(""))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet1", RIF_MAC))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry(nexthopId = "nhop-1", routerInterfaceId = "rif-1"))
    harness.installEntry(buildIpv4RouteEntry(vrfId = "", nexthopId = "nhop-1"))

    // Build and send an IPv4 packet.
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val outputs = harness.simulatePacket(ingressPort = 0, payload = packet).single().packetsList

    assertEquals("expected exactly one output packet", 1, outputs.size)
    val output = outputs[0].payload.toByteArray()

    // dst_mac rewritten to neighbor MAC.
    assertBytesEqual("dst_mac", NEIGHBOR_MAC, output, 0)
    // src_mac rewritten to RIF MAC.
    assertBytesEqual("src_mac", RIF_MAC, output, MAC_LEN)
    // TTL decremented by 1.
    assertEquals("TTL should be decremented", 63, output[TTL_OFFSET].toInt() and 0xFF)
    // IP addresses unchanged.
    assertBytesEqual("src_ip", SRC_IP, output, SRC_IP_OFFSET)
    assertBytesEqual("dst_ip", DST_IP, output, DST_IP_OFFSET)
  }

  // =========================================================================
  // Port translation: stock v1model has no port newtype
  // =========================================================================

  // The forked v1model (v1model_sai.p4) declares port_id_t as a newtype with
  // @p4runtime_translation on standard_metadata.ingress_port, enabling dual
  // port encoding in the DataplaneService.

  private fun installForwardingEntries() {
    harness.installEntry(buildVrfEntry(""))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet1", RIF_MAC))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry(nexthopId = "nhop-1", routerInterfaceId = "rif-1"))
    harness.installEntry(buildIpv4RouteEntry(vrfId = "", nexthopId = "nhop-1"))
  }

  @Test
  fun `InjectPacket response has dual port encoding`() {
    installForwardingEntries()
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val response = harness.injectPacket(ingressPort = 0, payload = packet)

    assertEquals("expected 1 output", 1, response.possibleOutcomesList.single().packetsCount)
    val output = response.possibleOutcomesList.single().getPackets(0)
    assertTrue("dataplane port should be set", output.dataplaneEgressPort >= 0)
    // SAI P4 uses sdn_string ports — verify the P4RT value is a valid UTF-8 string, not just
    // non-empty bytes. This catches encoding bugs where raw dataplane values leak through.
    assertTrue("p4rt_egress_port should be populated", !output.p4RtEgressPort.isEmpty)
    val p4rtStr = output.p4RtEgressPort.toStringUtf8()
    assertEquals(
      "p4rt_egress_port should be 'Ethernet1' (from routing chain)",
      "Ethernet1",
      p4rtStr,
    )
  }

  @Test
  fun `InjectPacket with P4RT port forwards correctly`() {
    installForwardingEntries()
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val p4rtPort = ByteString.copyFromUtf8("Ethernet0")
    val response = harness.injectPacketP4rt(p4rtPort, packet)

    assertEquals("expected 1 output", 1, response.possibleOutcomesList.single().packetsCount)
    val output = response.possibleOutcomesList.single().getPackets(0)
    assertTrue("output should have p4rt port", !output.p4RtEgressPort.isEmpty)
    assertEquals(
      "p4rt_egress_port should be 'Ethernet1' (from routing chain)",
      "Ethernet1",
      output.p4RtEgressPort.toStringUtf8(),
    )
  }

  @Test
  fun `CPU-port output has P4RT encoding from Replica port`() {
    installRoutingChain()
    installAclEntry(findAction("acl_trap"))
    installCopyToCpuCloneSession()

    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val result = harness.injectPacket(ingressPort = 0, payload = packet)

    val cpuOutput =
      result.possibleOutcomesList
        .flatMap { it.packetsList }
        .find { it.dataplaneEgressPort == CPU_PORT }
    assertNotNull("expected CPU-port output from ACL trap", cpuOutput)
    // The clone session uses Replica.port (bytes) with P4RT value "510", which
    // forward-allocates 510 in the PortTranslator. Verify the reverse mapping works.
    assertEquals(CPU_PORT.toString(), cpuOutput!!.p4RtEgressPort.toStringUtf8())
  }

  @Test
  fun `CPU-port output has PacketIn enrichment with stripped payload and metadata`() {
    installRoutingChain()
    installAclEntry(findAction("acl_trap"))
    installCopyToCpuCloneSession()

    val originalPacket = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val result = harness.injectPacket(ingressPort = 0, payload = originalPacket)

    val cpuOutput =
      result.possibleOutcomesList
        .flatMap { it.packetsList }
        .find { it.dataplaneEgressPort == CPU_PORT }
    assertNotNull("expected CPU-port output from ACL trap", cpuOutput)
    assertTrue("CPU-port output should have packet_in", cpuOutput!!.hasPacketIn())

    val packetIn = cpuOutput.packetIn
    assertEquals(
      "PacketIn payload should be the original packet (controller header stripped)",
      originalPacket.size,
      packetIn.payload.size(),
    )
    assertBytesEqual("PacketIn dst_mac", UNICAST_MAC, packetIn.payload.toByteArray(), 0)
    assertBytesEqual("PacketIn src_mac", SRC_MAC, packetIn.payload.toByteArray(), MAC_LEN)
    assertTrue("PacketIn should have metadata", packetIn.metadataCount > 0)

    // Metadata is decoded from the deparsed packet_in header and P4RT-translated. The packet
    // ingressed on port "0" and was routed toward "Ethernet1" before the ACL trapped it, so that
    // is the egress the program recorded in target_egress_port. Crucially it is NOT the CPU port
    // "510" the packet physically left on — see issue #767: 4ward must report the program's
    // intended egress, decoded generically from the header, not reconstructed from egress state.
    assertEquals("PacketIn ingress_port", "0", packetInMetaValue(packetIn, "ingress_port"))
    assertEquals(
      "PacketIn target_egress_port should be the intended routed egress, not the CPU port",
      "Ethernet1",
      packetInMetaValue(packetIn, "target_egress_port"),
    )
  }

  @Test
  fun `PacketIn enrichment matches P4Runtime stream PacketIn`() {
    installRoutingChain()
    installAclEntry(findAction("acl_trap"))
    installCopyToCpuCloneSession()

    // Inject via InjectPacket with a P4Runtime stream open. The same injection produces
    // both the enriched OutputPacket (in the InjectPacket response) and a PacketIn on the
    // stream — letting us compare the two representations from the exact same packet.
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    harness.openStream().use { session ->
      session.arbitrate()
      val result = harness.injectPacket(ingressPort = 0, payload = packet)

      val cpuOutput =
        result.possibleOutcomesList
          .flatMap { it.packetsList }
          .find { it.dataplaneEgressPort == CPU_PORT }
      assertNotNull("expected CPU-port output", cpuOutput)
      val enrichedPacketIn = cpuOutput!!.packetIn

      val response = session.receiveNext()
      assertNotNull("stream should deliver PacketIn", response)
      val streamPacketIn = response!!.packet

      assertEquals("payload should match", streamPacketIn.payload, enrichedPacketIn.payload)
      assertEquals(
        "metadata should match",
        streamPacketIn.metadataList,
        enrichedPacketIn.metadataList,
      )
    }
  }

  @Test
  fun `non-CPU-port output has no PacketIn enrichment`() {
    installForwardingEntries()
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val result = harness.injectPacket(ingressPort = 0, payload = packet)

    val output = result.possibleOutcomesList.single().getPackets(0)
    assertTrue("non-CPU output should not have packet_in", !output.hasPacketIn())
  }

  @Test
  fun `deprecated Replica egress_port with egress_rid delivers PacketIn via CPU`() {
    installRoutingChain()
    installAclEntry(findAction("acl_trap"))
    installIngressCloneTableEntry()
    // Port 99 has no P4RT mapping, but with egress_rid correctly set from the
    // replica's instance field, packet_io.p4 recognises the clone as a
    // PacketIn (instance_type==1 && egress_rid==1) and delivers it to the
    // CPU — the unmapped port 99 is never reached as a regular output.
    installCloneSessionDeprecated(COPY_TO_CPU_SESSION_ID, egressPort = 99)

    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    harness.injectPacket(ingressPort = 0, payload = packet)
  }

  @Test
  fun `InjectPacket trace has P4RT-enriched ports`() {
    installForwardingEntries()
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val response = harness.injectPacket(ingressPort = 0, payload = packet)

    assertTrue("trace should be present", response.hasTrace())
    val trace = response.trace

    // Ingress port event should have P4RT encoding.
    val ingressEvent = trace.eventsList.firstOrNull { it.hasPacketIngress() }
    assertNotNull("should have ingress event", ingressEvent)
    assertTrue(
      "ingress port should have P4RT encoding",
      !ingressEvent!!.packetIngress.p4RtIngressPort.isEmpty,
    )

    // Output packet in the trace outcome should have P4RT encoding.
    assertTrue("should have output", trace.hasOutput())
    assertTrue("egress port should have P4RT encoding", !trace.output.p4RtEgressPort.isEmpty)
  }

  @Test
  fun `InjectPacket trace has P4RT-enriched table entries`() {
    installForwardingEntries()
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val response = harness.injectPacket(ingressPort = 0, payload = packet)

    val trace = response.trace
    val tableLookups = trace.eventsList.filter { it.hasTableLookup() && it.tableLookup.hit }
    assertTrue("should have at least one table hit", tableLookups.isNotEmpty())

    val enrichedLookup = tableLookups.firstOrNull { it.tableLookup.hasP4RtMatchedEntry() }
    assertNotNull(
      "at least one table hit should have a P4RT-enriched matched entry",
      enrichedLookup,
    )
  }

  // =========================================================================
  // PacketIO: PacketOut via StreamChannel
  // =========================================================================

  @Test
  fun `PacketOut with submit_to_ingress=0 exits on data-plane port, no PacketIn`() {
    // The packet bypasses ingress and exits on Ethernet1 — a data-plane port, not the CPU port.
    // Only CPU-port outputs become PacketIn. Data-plane outputs do not.
    harness.openStream().use { session ->
      session.arbitrate()
      val response =
        session.sendPacketOut(
          buildCpuPacketOut(submitToIngress = false),
          timeoutMs = FourwardTestHarness.NO_RESPONSE_TIMEOUT_MS,
        )
      assertNull("data-plane output should not become PacketIn", response)
    }
  }

  @Test
  fun `PacketOut with submit_to_ingress=0 bypasses ingress and exits on egress_port`() =
    runBlocking {
      // Install routing entries that forward to Ethernet1. If ingress is truly bypassed,
      // the packet still exits on Ethernet1 (from the PacketOut header), but via a
      // different code path: the routing chain sets egress_spec via table lookup, while
      // bypass sets it directly from packet_out_header.egress_port.
      //
      // To distinguish: also install an ACL drop rule. If ingress runs, the ACL drops
      // the packet. If ingress is bypassed, the packet exits on egress_port.
      installRoutingChain()
      installAclEntry(findAction("acl_drop"))

      val stub = DataplaneCoroutineStub(harness.channel)
      val (job, results) = subscribeAndAwaitActive(stub)
      try {
        val packetOut = buildCpuPacketOut(submitToIngress = false)

        harness.openStream().use { session ->
          session.arbitrate()
          session.sendPacketOut(packetOut, timeoutMs = FourwardTestHarness.NO_RESPONSE_TIMEOUT_MS)

          val msg = withTimeout(SUBSCRIBE_RESULT_TIMEOUT_MS) { results.receive() }
          assertTrue("should be a result", msg.hasResult())
          val outputs = msg.result.possibleOutcomesList.single().packetsList
          assertEquals(
            "submit_to_ingress=0 should bypass ingress (ACL drop) and exit on egress_port",
            1,
            outputs.size,
          )
          assertEquals(
            "should exit on the PacketOut's egress_port",
            "Ethernet1",
            outputs[0].p4RtEgressPort.toStringUtf8(),
          )
          val expectedPayload = packetOut.payload.toByteArray()
          val actualPayload = outputs[0].payload.toByteArray()
          assertEquals(
            "PacketOut controller header bits must not leak into output payload",
            expectedPayload.size,
            actualPayload.size,
          )
          assertBytesEqual("dst_mac", UNICAST_MAC, actualPayload, 0)
          assertBytesEqual("src_mac", SRC_MAC, actualPayload, MAC_LEN)
          assertBytesEqual("src_ip", SRC_IP, actualPayload, SRC_IP_OFFSET)
          assertBytesEqual("dst_ip", DST_IP, actualPayload, DST_IP_OFFSET)
        }
      } finally {
        job.cancelAndJoin()
      }
    }

  @Test
  fun `PacketOut with invalid translated metadata returns StreamError and keeps stream open`() {
    config =
      withPortMappings(
        FourwardTestHarness.loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb"),
        mapOf(
          "0" to 0,
          "1" to 1,
          MIRROR_PORT.toString() to MIRROR_PORT,
          CPU_PORT.toString() to CPU_PORT,
        ),
        autoAllocate = false,
      )
    harness.loadPipeline(config)
    val packetOutMeta =
      config.p4Info.controllerPacketMetadataList.find { it.preamble.name == "packet_out" }!!
    val egressPortId = packetOutMeta.metadataList.find { it.name == "egress_port" }!!.id
    val badPacketOut =
      buildCpuPacketOut(submitToIngress = false)
        .toBuilder()
        .removeMetadata(0)
        .addMetadata(
          0,
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(egressPortId)
            .setValue(ByteString.copyFromUtf8("Ethernet999"))
            .build(),
        )
        .build()

    harness.openStream().use { session ->
      session.arbitrate()
      val response = session.sendPacketOut(badPacketOut)
      assertNotNull("invalid PacketOut should produce StreamError", response)
      assertTrue("response should be StreamError", response!!.hasError())
      assertEquals(com.google.rpc.Code.INVALID_ARGUMENT_VALUE, response.error.canonicalCode)
      assertTrue("details should use packet_out oneof", response.error.hasPacketOut())

      val stillOpen = session.sendRaw(P4RuntimeOuterClass.StreamMessageRequest.getDefaultInstance())
      assertNotNull("stream should remain usable after PacketOut StreamError", stillOpen)
      assertTrue("follow-up response should be StreamError", stillOpen!!.hasError())
      assertEquals(com.google.rpc.Code.INVALID_ARGUMENT_VALUE, stillOpen.error.canonicalCode)
    }
  }

  // =========================================================================
  // PacketIO: ACL trap/copy → PacketIn via StreamChannel
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `ACL trap produces PacketIn with properly translated port metadata`() {
    // Full routing chain so the packet has a destination before ACL intervenes.
    installRoutingChain()
    // ACL: trap packets to dst_ip=10.0.0.1 → copy to CPU + drop original.
    installAclEntry(findAction("acl_trap"))
    // Clone infrastructure: marked_to_copy → clone session 255 → CPU port "510".
    installCopyToCpuCloneSession()

    // PacketOut with submit_to_ingress=1: enters ingress, gets trapped by ACL.
    harness.openStream().use { session ->
      session.arbitrate()
      val packetOut = buildCpuPacketOut(submitToIngress = true)
      val response = session.sendPacketOut(packetOut)
      assertNotNull("ACL trap should produce PacketIn on StreamChannel", response)
      assertTrue("response should be PacketIn", response!!.hasPacket())

      // PacketIn metadata is decoded from the deparsed header and P4RT-translated to port names.
      // This packet was submitted to ingress via PacketOut, so it ingressed on the CPU port "510".
      // It routed toward "Ethernet1" before the ACL trapped it, so target_egress_port is the
      // intended egress — not the CPU port "510" it physically left on (issue #767).
      val packetIn = response.packet
      assertEquals("PacketIn ingress_port", "510", packetInMetaValue(packetIn, "ingress_port"))
      assertEquals(
        "PacketIn target_egress_port should be the intended routed egress, not the CPU port",
        "Ethernet1",
        packetInMetaValue(packetIn, "target_egress_port"),
      )
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `ACL trap PacketIn payload does not include controller header bytes`() {
    installRoutingChain()
    installAclEntry(findAction("acl_trap"))
    installCopyToCpuCloneSession()

    val originalPacket = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    harness.openStream().use { session ->
      session.arbitrate()
      val packetOut = buildCpuPacketOut(submitToIngress = true)
      val response = session.sendPacketOut(packetOut)
      assertNotNull("ACL trap should produce PacketIn", response)

      val packetIn = response!!.packet
      val payload = packetIn.payload.toByteArray()
      // The deparser emits a @controller_header("packet_in") before the Ethernet frame.
      // The P4Runtime server must strip it — the payload should be the original packet.
      assertEquals(
        "PacketIn payload should match original packet size (no controller header)",
        originalPacket.size,
        payload.size,
      )
      // The I2E clone uses post-parser (pre-ingress) headers, so the Ethernet and IP
      // fields are unmodified. The IPv4 checksum may differ because v1model's
      // ComputeChecksum stage recomputes it.
      assertBytesEqual("dst_mac", UNICAST_MAC, payload, 0)
      assertBytesEqual("src_mac", SRC_MAC, payload, MAC_LEN)
      assertBytesEqual("src_ip", SRC_IP, payload, SRC_IP_OFFSET)
      assertBytesEqual("dst_ip", DST_IP, payload, DST_IP_OFFSET)
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `ACL copy produces both outputs but only CPU clone becomes PacketIn`() {
    installRoutingChain()
    // ACL: copy packets to CPU without dropping (forward normally + clone to CPU).
    installAclEntry(findAction("acl_copy"))
    installCopyToCpuCloneSession()

    // Via InjectPacket: verify both data-plane and CPU outputs exist.
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val result = harness.injectPacket(ingressPort = 0, payload = packet)
    assertTrue(
      "acl_copy should produce multiple outputs (forwarded + CPU clone)",
      result.possibleOutcomesList.single().packetsCount >= 2,
    )
    assertTrue(
      "expected CPU-port output",
      result.possibleOutcomesList.single().packetsList.any { it.dataplaneEgressPort == CPU_PORT },
    )
    assertTrue(
      "expected data-plane output",
      result.possibleOutcomesList.single().packetsList.any { it.dataplaneEgressPort != CPU_PORT },
    )

    // Via StreamChannel: only the CPU clone becomes PacketIn.
    harness.openStream().use { session ->
      session.arbitrate()
      val packetOut = buildCpuPacketOut(submitToIngress = true)
      val response = session.sendPacketOut(packetOut)
      assertNotNull("CPU clone should become PacketIn", response)
      assertTrue("response should be PacketIn", response!!.hasPacket())
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `InjectPacket that egresses on CPU port produces PacketIn on StreamChannel`() {
    installRoutingChain()
    installAclEntry(findAction("acl_trap"))
    installCopyToCpuCloneSession()

    // Open StreamChannel first so the PacketIn subscription is active.
    harness.openStream().use { session ->
      session.arbitrate()
      // Inject via DataplaneService — NOT PacketOut. The ACL trap clones to CPU port.
      val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
      harness.injectPacket(ingressPort = 0, payload = packet)
      // The CPU-port output should become PacketIn on the active stream.
      val response = session.receiveNext()
      assertNotNull("data-plane injection should produce PacketIn via ACL trap", response)
      assertTrue("response should be PacketIn", response!!.hasPacket())
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `multiple streams each receive PacketIn (P4Runtime spec 16_1)`() {
    installRoutingChain()
    installAclEntry(findAction("acl_trap"))
    installCopyToCpuCloneSession()

    // Two controllers with different election IDs — both should receive PacketIn per §16.1.
    harness.openStream().use { session1 ->
      session1.arbitrate(electionId = 2)
      harness.openStream().use { session2 ->
        session2.arbitrate(electionId = 1)

        // Inject via DataplaneService. Both streams should observe PacketIn.
        val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
        harness.injectPacket(ingressPort = 0, payload = packet)

        val response1 = session1.receiveNext()
        val response2 = session2.receiveNext()
        assertNotNull("primary stream should receive PacketIn", response1)
        assertNotNull("backup stream should receive PacketIn", response2)
        assertTrue("primary response should be PacketIn", response1!!.hasPacket())
        assertTrue("backup response should be PacketIn", response2!!.hasPacket())
      }
    }
  }

  // =========================================================================
  // ACL ingress: drop
  // =========================================================================

  @Test
  fun `ACL ingress drop prevents packet from being forwarded`() {
    // Install normal routing so the packet would be forwarded without ACL.
    installRoutingChain()

    // Install ACL entry matching dst_ip=10.0.0.1 → acl_drop.
    val aclTable = findTable("acl_ingress_table")
    val aclDrop = findAction("acl_drop")
    harness.installEntry(
      buildEntry(
        aclTable,
        aclDrop,
        matches =
          listOf(
            optionalMatch(aclTable, "is_ipv4", byteArrayOf(1)),
            ternaryMatch(aclTable, "dst_ip", DST_IP, byteArrayOf(-1, -1, -1, -1)),
          ),
        priority = 1,
      )
    )

    // Send packet — should be dropped by ACL.
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val outputs = harness.simulatePacket(ingressPort = 0, payload = packet).single().packetsList
    assertEquals("packet should be dropped by ACL", 0, outputs.size)
  }

  // =========================================================================
  // ACL ingress: redirect to nexthop
  // =========================================================================

  @Test
  fun `ACL redirect to nexthop overrides normal routing`() {
    // Install normal routing: dst 10.0.0.0/8 → nhop-1 → rif-1 → Ethernet1.
    installRoutingChain()

    // Install a second path in dependency order: rif-2 → neighbor → nhop-2.
    harness.installEntry(buildRouterInterfaceEntry("rif-2", "Ethernet2", ALT_RIF_MAC))
    harness.installEntry(buildNeighborEntry("rif-2", NEIGHBOR_ID, ALT_NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry("nhop-2", "rif-2"))

    // ACL: redirect packets to dst_ip=10.0.0.1 → nhop-2 (overriding the route).
    val aclTable = findTable("acl_ingress_table")
    val redirectAction = findAction("redirect_to_nexthop")
    harness.installEntry(
      buildEntry(
        aclTable,
        redirectAction,
        matches =
          listOf(
            optionalMatch(aclTable, "is_ipv4", byteArrayOf(1)),
            ternaryMatch(aclTable, "dst_ip", DST_IP, byteArrayOf(-1, -1, -1, -1)),
          ),
        params = listOf(stringParam(redirectAction, "nexthop_id", "nhop-2")),
        priority = 1,
      )
    )

    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val outputs = harness.simulatePacket(ingressPort = 0, payload = packet).single().packetsList

    assertEquals("expected exactly one output packet", 1, outputs.size)
    val output = outputs[0].payload.toByteArray()
    // dst_mac should be ALT_NEIGHBOR_MAC (from nhop-2 path), not NEIGHBOR_MAC.
    assertBytesEqual("dst_mac from redirect path", ALT_NEIGHBOR_MAC, output, 0)
    assertBytesEqual("src_mac from redirect path", ALT_RIF_MAC, output, MAC_LEN)
  }

  // =========================================================================
  // IPv4 multicast: packet replication
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `IPv4 multicast replicates packet to multiple ports`() {
    // Install prerequisites: default VRF + multicast group (@refers_to enforcement).
    harness.installEntry(buildVrfEntry(""))
    installMulticastGroup(groupId = 1, p4rtPorts = listOf("0", "1"))

    // Install multicast route via ipv4_multicast_table (EXACT match on ipv4_dst).
    val mcastTable = findTable("ipv4_multicast_table")
    val setMcastGroup = findAction("set_multicast_group_id")
    val mcastDstIp = byteArrayOf(224.toByte(), 0, 0, 1)
    harness.installEntry(
      buildEntry(
        mcastTable,
        setMcastGroup,
        matches =
          listOf(
            exactMatch(mcastTable, "vrf_id", ""),
            exactMatch(mcastTable, "ipv4_dst", mcastDstIp),
          ),
        params = listOf(bytesParam(setMcastGroup, "multicast_group_id", byteArrayOf(0, 1))),
      )
    )

    // Send a multicast-destined packet (224.0.0.1) with IPv4 multicast MAC.
    // SAI P4 gates ipv4_multicast_table on IS_IPV4_MULTICAST_MAC(dst_addr).
    val packet =
      buildIpv4Packet(dstMac = MULTICAST_MAC, srcMac = SRC_MAC, ttl = 64, dstIp = mcastDstIp)
    val outputs = harness.simulatePacket(ingressPort = 2, payload = packet).single().packetsList

    assertTrue(
      "expected at least 2 output packets for multicast, got ${outputs.size}",
      outputs.size >= 2,
    )
  }

  // =========================================================================
  // WCMP: action selector member and group round-trip
  // =========================================================================

  @Test
  fun `WCMP action profile members and groups round-trip through SAI P4`() {
    // Find the WCMP action selector profile ID.
    val wcmpSelector =
      config.p4Info.actionProfilesList.find { it.preamble.alias == "wcmp_group_selector" }!!
    val actionProfileId = wcmpSelector.preamble.id

    // Install prerequisite entries: nexthops referenced by action profile members.
    // Dependency chain: router_interface → neighbor → nexthop.
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet1", RIF_MAC))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry(nexthopId = "nhop-a", routerInterfaceId = "rif-1"))
    harness.installEntry(buildNexthopEntry(nexthopId = "nhop-b", routerInterfaceId = "rif-1"))

    // Install two action profile members pointing to different nexthops.
    val setNexthop = findAction("set_nexthop_id")
    harness.installEntry(
      buildActionProfileMember(actionProfileId, memberId = 1, setNexthop, "nhop-a")
    )
    harness.installEntry(
      buildActionProfileMember(actionProfileId, memberId = 2, setNexthop, "nhop-b")
    )

    // Install a WCMP group with both members.
    harness.installEntry(buildActionProfileGroup(actionProfileId, groupId = 1, listOf(1, 2)))

    // Read back and verify.
    val members = harness.readProfileMembers(actionProfileId)
    assertEquals("expected 2 profile members", 2, members.size)

    val groups = harness.readProfileGroups(actionProfileId)
    assertEquals("expected 1 profile group", 1, groups.size)
    assertEquals(2, groups[0].actionProfileGroup.membersCount)

    // Verify members carry the translated nexthop_id params.
    val nexthopParam = paramId(setNexthop, "nexthop_id")
    val memberNexthops =
      members.map { m ->
        m.actionProfileMember.action.paramsList
          .find { it.paramId == nexthopParam }
          ?.value
          ?.toStringUtf8()
      }
    assertTrue("nhop-a should be in members", "nhop-a" in memberNexthops)
    assertTrue("nhop-b should be in members", "nhop-b" in memberNexthops)
  }

  // =========================================================================
  // Mirroring with VLAN + IPFIX encapsulation
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `mirrored packet has VLAN and IPFIX encapsulation headers prepended`() {
    // 1. Mirror session: mirror-1 → encap with VLAN + IPv6 + UDP + IPFIX + PSAMP.
    //    Must be installed before the ACL entry due to @refers_to constraint.
    val mirrorSessionTable = findTable("mirror_session_table")
    val mirrorAction = findAction("mirror_with_vlan_tag_and_ipfix_encapsulation")
    val mirrorEncapSrcMac = byteArrayOf(0x00, 0x08, 0x08, 0x08, 0x08, 0x08)
    val mirrorEncapDstMac = byteArrayOf(0x01, 0x09, 0x09, 0x09, 0x09, 0x09)
    harness.installEntry(
      buildEntry(
        mirrorSessionTable,
        mirrorAction,
        matches = listOf(exactMatch(mirrorSessionTable, "mirror_session_id", "mirror-1")),
        params =
          listOf(
            stringParam(mirrorAction, "monitor_port", MIRROR_PORT.toString()),
            stringParam(mirrorAction, "monitor_failover_port", MIRROR_PORT.toString()),
            bytesParam(mirrorAction, "mirror_encap_src_mac", mirrorEncapSrcMac),
            bytesParam(mirrorAction, "mirror_encap_dst_mac", mirrorEncapDstMac),
            bytesParam(mirrorAction, "mirror_encap_vlan_id", byteArrayOf(0x01, 0x23)),
            bytesParam(
              mirrorAction,
              "mirror_encap_src_ip",
              ByteArray(16) { if (it == 15) 1 else 0 },
            ),
            bytesParam(
              mirrorAction,
              "mirror_encap_dst_ip",
              ByteArray(16) { if (it == 15) 2 else 0 },
            ),
            bytesParam(mirrorAction, "mirror_encap_udp_src_port", byteArrayOf(0x12, 0x34)),
            bytesParam(mirrorAction, "mirror_encap_udp_dst_port", byteArrayOf(0x12, 0x83.toByte())),
          ),
      )
    )

    // 2. ACL: mark IPv4 packets for mirroring with mirror_session_id="mirror-1".
    val mirrorAclTable = findTable("acl_ingress_mirror_and_redirect_table")
    val aclMirror = findAction("acl_mirror")
    harness.installEntry(
      buildEntry(
        mirrorAclTable,
        aclMirror,
        matches = listOf(optionalMatch(mirrorAclTable, "is_ipv4", byteArrayOf(1))),
        params = listOf(stringParam(aclMirror, "mirror_session_id", "mirror-1")),
        priority = 1,
      )
    )

    // 3. Ingress clone table: marked_to_mirror=true, mirror_egress_port=MIRROR_PORT
    //    → clone_preserving_field_list with session MIRROR_CLONE_SESSION_ID.
    val cloneTable = findTable("ingress_clone_table")
    val ingressClone = findAction("ingress_clone")
    harness.installEntry(
      buildEntry(
        cloneTable,
        ingressClone,
        matches =
          listOf(
            exactMatch(cloneTable, "marked_to_copy", byteArrayOf(0)),
            exactMatch(cloneTable, "marked_to_mirror", byteArrayOf(1)),
            optionalMatch(cloneTable, "mirror_egress_port", MIRROR_PORT.toString()),
          ),
        params =
          listOf(
            bytesParam(
              ingressClone,
              "clone_session",
              FourwardTestHarness.longToBytes(MIRROR_CLONE_SESSION_ID.toLong(), 4),
            )
          ),
        priority = 1,
      )
    )

    // 4. Clone session → replica on MIRROR_PORT with instance=MIRRORING (2).
    installCloneSession(MIRROR_CLONE_SESSION_ID, MIRROR_PORT.toString(), REPLICA_INSTANCE_MIRRORING)

    // Inject an IPv4 packet and check the mirrored output.
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val result = harness.injectPacket(ingressPort = 0, payload = packet)
    val outputs = result.possibleOutcomesList.single().packetsList
    val mirroredPacket =
      checkNotNull(outputs.find { it.dataplaneEgressPort == MIRROR_PORT }) {
        "expected a mirrored packet on port $MIRROR_PORT"
      }

    val mirroredBytes = mirroredPacket.payload.toByteArray()
    // The mirrored packet should be: Ethernet(14) + VLAN(4) + IPv6(40) + UDP(8) + IPFIX(16) +
    // PSAMP(28) + original_packet. Encap overhead = 110 bytes.
    val encapOverhead = 14 + 4 + 40 + 8 + 16 + 28
    assertEquals(
      "mirrored packet should be original + encap overhead",
      packet.size + encapOverhead,
      mirroredBytes.size,
    )

    // Verify encap Ethernet dst/src MAC.
    assertBytesEqual("encap dst_mac", mirrorEncapDstMac, mirroredBytes, 0)
    assertBytesEqual("encap src_mac", mirrorEncapSrcMac, mirroredBytes, 6)
    // EtherType = 0x8100 (802.1Q VLAN).
    assertEquals("encap ethertype high", 0x81.toByte(), mirroredBytes[12])
    assertEquals("encap ethertype low", 0x00.toByte(), mirroredBytes[13])

    // VLAN ethertype = 0x86DD (IPv6) at offset 16.
    assertEquals("vlan ethertype high", 0x86.toByte(), mirroredBytes[16])
    assertEquals("vlan ethertype low", 0xDD.toByte(), mirroredBytes[17])

    // IPv6 version nibble at offset 18.
    assertEquals("ipv6 version", 0x60.toByte(), (mirroredBytes[18].toInt() and 0xF0).toByte())

    // Original packet's Ethernet header starts at offset encapOverhead.
    // The IPv4 checksum may differ (recomputed by v1model's ComputeChecksum), so compare
    // only the Ethernet header and IP src/dst addresses.
    assertBytesEqual("original dst_mac", UNICAST_MAC, mirroredBytes, encapOverhead)
    assertBytesEqual("original src_mac", SRC_MAC, mirroredBytes, encapOverhead + MAC_LEN)
    assertEquals("original ethertype high", 0x08.toByte(), mirroredBytes[encapOverhead + 12])
    assertEquals("original ethertype low", 0x00.toByte(), mirroredBytes[encapOverhead + 13])
    assertBytesEqual("original src_ip", SRC_IP, mirroredBytes, encapOverhead + SRC_IP_OFFSET)
    assertBytesEqual("original dst_ip", DST_IP, mirroredBytes, encapOverhead + DST_IP_OFFSET)
  }

  // =========================================================================
  // Role-based access control (§15)
  //
  // SAI P4 tables declare @p4runtime_role("sdn_controller") (most tables) or
  // @p4runtime_role("packet_replication_engine_manager") (ingress_clone_table).
  // =========================================================================

  @Test
  fun `named-role controller can write and read entities matching its role`() {
    val eid = FourwardTestHarness.uint128(low = 1)
    // Keep stream open for the duration of the test so the controller stays primary.
    harness.openStream().use { session ->
      session.arbitrate(roleName = "sdn_controller")
      // vrf_table has @p4runtime_role("sdn_controller") — write should succeed.
      harness.installEntry(buildVrfEntry("vrf-role"), electionId = eid, role = "sdn_controller")

      val vrfTable = findTable("vrf_table")
      val entries =
        harness.readTableEntries(vrfTable.preamble.id, role = "sdn_controller").filter {
          !it.tableEntry.isDefaultAction
        }
      assertEquals("expected one vrf_table entry", 1, entries.size)
      assertEquals("vrf-role", entries[0].tableEntry.matchList.first().exact.value.toStringUtf8())
    }
  }

  @Test
  fun `wrong-role controller cannot write to another role's table`() {
    val eid = FourwardTestHarness.uint128(low = 1)
    harness.openStream().use { session ->
      session.arbitrate(roleName = "packet_replication_engine_manager")
      // vrf_table belongs to "sdn_controller", not "packet_replication_engine_manager".
      FourwardTestHarness.assertGrpcError(Status.Code.PERMISSION_DENIED, "role") {
        harness.installEntry(
          buildVrfEntry("vrf-denied"),
          electionId = eid,
          role = "packet_replication_engine_manager",
        )
      }
    }
  }

  @Test
  fun `wildcard read with role returns only matching tables`() {
    val eid = FourwardTestHarness.uint128(low = 1)
    harness.openStream().use { session ->
      session.arbitrate(roleName = "sdn_controller")
      harness.installEntry(buildVrfEntry("vrf-wildcard"), electionId = eid, role = "sdn_controller")

      // Wildcard read (table_id=0) with role="sdn_controller" should return entries
      // only from sdn_controller tables, filtering out other roles.
      val roleMap = RoleMap.create(config.p4Info)
      val entities = harness.readTableEntries(0, role = "sdn_controller")
      for (entity in entities) {
        val tableId = entity.tableEntry.tableId
        val table = config.p4Info.tablesList.find { it.preamble.id == tableId }!!
        assertEquals(
          "table '${table.preamble.alias}' should belong to sdn_controller",
          "sdn_controller",
          roleMap.role(tableId),
        )
      }
    }
  }

  // =========================================================================
  // Delete: verify translated entries can be removed
  // =========================================================================

  @Test
  fun `delete removes translated entry and read returns empty`() {
    val vrfEntry = buildVrfEntry("vrf-to-delete")
    harness.installEntry(vrfEntry)

    val vrfTable = findTable("vrf_table")
    assertEquals(1, harness.readTableEntries(vrfTable.preamble.id).size)

    harness.deleteEntry(vrfEntry)
    assertEquals(0, harness.readTableEntries(vrfTable.preamble.id).size)
  }

  // =========================================================================
  // Translation coverage: remaining SAI P4 tables
  //
  // These round-trip tests verify that P4Runtime Write/Read with sdn_string
  // translation works for every SAI P4 table, not just the 10 tables covered
  // by the forwarding and referential-integrity tests above.
  // =========================================================================

  // --- IPv6 routing ---

  @Test
  @Suppress("MagicNumber")
  fun `ipv6_table entry with translated vrf_id and nexthop_id round-trips`() {
    harness.installEntry(buildVrfEntry("vrf-v6"))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet0"))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry("nhop-v6", "rif-1"))

    val table = findTable("ipv6_table")
    val action = findAction("set_nexthop_id")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches =
          listOf(
            exactMatch(table, "vrf_id", "vrf-v6"),
            lpmMatch(table, "ipv6_dst", IPV6_2001_DB8, prefixLen = 32),
          ),
        params = listOf(stringParam(action, "nexthop_id", "nhop-v6")),
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one ipv6_table entry", 1, entities.size)
    val entry = entities[0].tableEntry
    val vrfFieldId = matchFieldId(table, "vrf_id")
    assertEquals(
      "vrf-v6",
      entry.matchList.find { it.fieldId == vrfFieldId }!!.exact.value.toStringUtf8(),
    )
    val nexthopParamId = paramId(action, "nexthop_id")
    assertEquals(
      "nhop-v6",
      entry.action.action.paramsList.find { it.paramId == nexthopParamId }!!.value.toStringUtf8(),
    )
  }

  @Test
  @Suppress("MagicNumber")
  fun `ipv6_multicast_table entry with translated vrf_id round-trips`() {
    harness.installEntry(buildVrfEntry(""))
    installMulticastGroup(groupId = 2, p4rtPorts = listOf("0", "1"))

    val table = findTable("ipv6_multicast_table")
    val action = findAction("set_multicast_group_id")
    val ipv6Mcast = byteArrayOf(0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches = listOf(exactMatch(table, "vrf_id", ""), exactMatch(table, "ipv6_dst", ipv6Mcast)),
        params = listOf(bytesParam(action, "multicast_group_id", byteArrayOf(0, 2))),
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one ipv6_multicast_table entry", 1, entities.size)
    val vrfFieldId = matchFieldId(table, "vrf_id")
    assertEquals(
      "",
      entities[0]
        .tableEntry
        .matchList
        .find { it.fieldId == vrfFieldId }!!
        .exact
        .value
        .toStringUtf8(),
    )
  }

  // --- Tunneling ---

  @Test
  @Suppress("MagicNumber")
  fun `tunnel_table entry with translated tunnel_id and router_interface_id round-trips`() {
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet0"))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))

    val table = findTable("tunnel_table")
    val action = findAction("mark_for_p2p_tunnel_encap")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches = listOf(exactMatch(table, "tunnel_id", "tunnel-1")),
        params =
          listOf(
            bytesParam(action, "encap_src_ip", IPV6_2001_DB8),
            bytesParam(action, "encap_dst_ip", NEIGHBOR_ID),
            stringParam(action, "router_interface_id", "rif-1"),
          ),
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one tunnel_table entry", 1, entities.size)
    val entry = entities[0].tableEntry
    assertEquals("tunnel-1", entry.matchList.first().exact.value.toStringUtf8())
    val rifParamId = paramId(action, "router_interface_id")
    assertEquals(
      "rif-1",
      entry.action.action.paramsList.find { it.paramId == rifParamId }!!.value.toStringUtf8(),
    )
  }

  @Test
  @Suppress("MagicNumber")
  fun `ipv6_tunnel_termination_table entry with ternary IPv6 round-trips`() {
    val table = findTable("ipv6_tunnel_termination_table")
    val action = findAction("tunnel_decap")
    val dstIpv6 = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    val srcIpv6 = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
    val mask128 = ByteArray(16) { 0xff.toByte() }
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches =
          listOf(
            ternaryMatch(table, "dst_ipv6", dstIpv6, mask128),
            ternaryMatch(table, "src_ipv6", srcIpv6, mask128),
          ),
        priority = 1,
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one ipv6_tunnel_termination_table entry", 1, entities.size)
  }

  // --- VLAN ---

  @Test
  @Suppress("MagicNumber")
  fun `vlan_table and vlan_membership_table with translated port_id round-trip`() {
    val vlanTable = findTable("vlan_table")
    val noAction = findAction("no_action")
    harness.installEntry(
      buildEntry(
        vlanTable,
        noAction,
        matches = listOf(exactMatch(vlanTable, "vlan_id", byteArrayOf(0, 100))),
      )
    )

    val memberTable = findTable("vlan_membership_table")
    val taggedAction = findAction("make_tagged_member")
    harness.installEntry(
      buildEntry(
        memberTable,
        taggedAction,
        matches =
          listOf(
            exactMatch(memberTable, "vlan_id", byteArrayOf(0, 100)),
            exactMatch(memberTable, "port", "Ethernet0"),
          ),
      )
    )

    // port should round-trip as string (port_id_t is sdn_string).
    val entities = harness.readTableEntries(memberTable.preamble.id)
    assertEquals("expected one vlan_membership_table entry", 1, entities.size)
    val portFieldId = matchFieldId(memberTable, "port")
    assertEquals(
      "Ethernet0",
      entities[0]
        .tableEntry
        .matchList
        .find { it.fieldId == portFieldId }!!
        .exact
        .value
        .toStringUtf8(),
    )
  }

  @Test
  fun `disable_ingress_vlan_checks_table accepts wildcard entry`() {
    val table = findTable("disable_ingress_vlan_checks_table")
    val action = findAction("disable_ingress_vlan_checks")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches = listOf(lpmMatch(table, "dummy_match", byteArrayOf(0), prefixLen = 0)),
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one disable_ingress_vlan_checks_table entry", 1, entities.size)
  }

  @Test
  fun `disable_egress_vlan_checks_table accepts wildcard entry`() {
    val table = findTable("disable_egress_vlan_checks_table")
    val action = findAction("disable_egress_vlan_checks")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches = listOf(lpmMatch(table, "dummy_match", byteArrayOf(0), prefixLen = 0)),
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one disable_egress_vlan_checks_table entry", 1, entities.size)
  }

  // --- L3 admission ---

  @Test
  fun `l3_admit_table entry with ternary dst_mac and translated in_port round-trips`() {
    val table = findTable("l3_admit_table")
    val action = findAction("admit_to_l3")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches =
          listOf(
            ternaryMatch(
              table,
              "dst_mac",
              byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
              byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00),
            ),
            optionalMatch(table, "in_port", "Ethernet0"),
          ),
        priority = 1,
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one l3_admit_table entry", 1, entities.size)
    val inPortId = matchFieldId(table, "in_port")
    assertEquals(
      "Ethernet0",
      entities[0]
        .tableEntry
        .matchList
        .find { it.fieldId == inPortId }!!
        .optional
        .value
        .toStringUtf8(),
    )
  }

  // --- Mirroring ---

  @Test
  @Suppress("MagicNumber")
  fun `mirror_session_table entry with translated IDs round-trips`() {
    val table = findTable("mirror_session_table")
    val action = findAction("mirror_with_vlan_tag_and_ipfix_encapsulation")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches = listOf(exactMatch(table, "mirror_session_id", "mirror-1")),
        params =
          listOf(
            stringParam(action, "monitor_port", "Ethernet0"),
            stringParam(action, "monitor_failover_port", "Ethernet1"),
            bytesParam(
              action,
              "mirror_encap_src_mac",
              byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55),
            ),
            bytesParam(action, "mirror_encap_dst_mac", NEIGHBOR_MAC),
            bytesParam(action, "mirror_encap_vlan_id", byteArrayOf(0, 100)),
            bytesParam(action, "mirror_encap_src_ip", IPV6_2001_DB8),
            bytesParam(action, "mirror_encap_dst_ip", NEIGHBOR_ID),
            bytesParam(action, "mirror_encap_udp_src_port", byteArrayOf(0x13, 0x88.toByte())),
            bytesParam(action, "mirror_encap_udp_dst_port", byteArrayOf(0x13, 0x89.toByte())),
          ),
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one mirror_session_table entry", 1, entities.size)
    val entry = entities[0].tableEntry
    assertEquals("mirror-1", entry.matchList.first().exact.value.toStringUtf8())
    val portParamId = paramId(action, "monitor_port")
    assertEquals(
      "Ethernet0",
      entry.action.action.paramsList.find { it.paramId == portParamId }!!.value.toStringUtf8(),
    )
  }

  // --- Multicast rewrites ---

  @Test
  fun `multicast_router_interface_table entry with translated port round-trips`() {
    val table = findTable("multicast_router_interface_table")
    val action = findAction("set_multicast_src_mac")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches =
          listOf(
            exactMatch(table, "multicast_replica_port", "Ethernet0"),
            exactMatch(table, "multicast_replica_instance", byteArrayOf(0, 1)),
          ),
        params = listOf(bytesParam(action, "src_mac", RIF_MAC)),
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one multicast_router_interface_table entry", 1, entities.size)
    val portFieldId = matchFieldId(table, "multicast_replica_port")
    assertEquals(
      "Ethernet0",
      entities[0]
        .tableEntry
        .matchList
        .find { it.fieldId == portFieldId }!!
        .exact
        .value
        .toStringUtf8(),
    )
  }

  // --- ACL egress ---

  @Test
  fun `acl_egress_table entry with translated out_port round-trips`() {
    val table = findTable("acl_egress_table")
    val action = findAction("acl_drop")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches =
          listOf(
            optionalMatch(table, "is_ip", byteArrayOf(1)),
            optionalMatch(table, "out_port", "Ethernet0"),
          ),
        priority = 1,
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one acl_egress_table entry", 1, entities.size)
    val outPortId = matchFieldId(table, "out_port")
    assertEquals(
      "Ethernet0",
      entities[0]
        .tableEntry
        .matchList
        .find { it.fieldId == outPortId }!!
        .optional
        .value
        .toStringUtf8(),
    )
  }

  // --- ACL ingress variants ---

  @Test
  fun `acl_ingress_mirror_and_redirect_table with translated nexthop_id round-trips`() {
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet0"))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry("nhop-redirect", "rif-1"))

    val table = findTable("acl_ingress_mirror_and_redirect_table")
    val action = findAction("redirect_to_nexthop")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches = listOf(optionalMatch(table, "is_ipv4", byteArrayOf(1))),
        params = listOf(stringParam(action, "nexthop_id", "nhop-redirect")),
        priority = 1,
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one acl_ingress_mirror_and_redirect_table entry", 1, entities.size)
    val nexthopParamId = paramId(action, "nexthop_id")
    assertEquals(
      "nhop-redirect",
      entities[0]
        .tableEntry
        .action
        .action
        .paramsList
        .find { it.paramId == nexthopParamId }!!
        .value
        .toStringUtf8(),
    )
  }

  @Test
  @Suppress("MagicNumber")
  fun `acl_ingress_security_table entry round-trips`() {
    val table = findTable("acl_ingress_security_table")
    val action = findAction("acl_forward")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches =
          listOf(
            optionalMatch(table, "is_ipv4", byteArrayOf(1)),
            ternaryMatch(table, "src_ip", byteArrayOf(10, 0, 0, 0), byteArrayOf(-1, 0, 0, 0)),
          ),
        priority = 1,
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one acl_ingress_security_table entry", 1, entities.size)
  }

  // --- Ingress cloning ---

  @Test
  @Suppress("MagicNumber")
  fun `ingress_clone_table entry with translated mirror_egress_port round-trips`() {
    val table = findTable("ingress_clone_table")
    val action = findAction("ingress_clone")
    harness.installEntry(
      buildEntry(
        table,
        action,
        matches =
          listOf(
            exactMatch(table, "marked_to_copy", byteArrayOf(1)),
            exactMatch(table, "marked_to_mirror", byteArrayOf(0)),
            optionalMatch(table, "mirror_egress_port", "Ethernet0"),
          ),
        params = listOf(bytesParam(action, "clone_session", byteArrayOf(0, 0, 0, 1))),
        priority = 1,
      )
    )

    val entities = harness.readTableEntries(table.preamble.id)
    assertEquals("expected one ingress_clone_table entry", 1, entities.size)
    val mirrorPortId = matchFieldId(table, "mirror_egress_port")
    assertEquals(
      "Ethernet0",
      entities[0]
        .tableEntry
        .matchList
        .find { it.fieldId == mirrorPortId }!!
        .optional
        .value
        .toStringUtf8(),
    )
  }

  // =========================================================================
  // p4info lookup helpers — delegates to FourwardTestHarness.Companion
  // =========================================================================

  private fun findTable(alias: String) = FourwardTestHarness.findTable(config, alias)

  private fun findAction(alias: String) = FourwardTestHarness.findAction(config, alias)

  private fun matchFieldId(table: P4InfoOuterClass.Table, name: String) =
    FourwardTestHarness.matchFieldId(table, name)

  private fun paramId(action: P4InfoOuterClass.Action, name: String) =
    FourwardTestHarness.paramId(action, name)

  // =========================================================================
  // Match field and action param builders
  // =========================================================================

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value)))
      .build()

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: String,
  ): FieldMatch = exactMatch(table, fieldName, value.toByteArray(Charsets.UTF_8))

  private fun optionalMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setOptional(FieldMatch.Optional.newBuilder().setValue(ByteString.copyFrom(value)))
      .build()

  private fun optionalMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: String,
  ): FieldMatch = optionalMatch(table, fieldName, value.toByteArray(Charsets.UTF_8))

  private fun ternaryMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
    mask: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setTernary(
        FieldMatch.Ternary.newBuilder()
          .setValue(ByteString.copyFrom(value))
          .setMask(ByteString.copyFrom(mask))
      )
      .build()

  private fun lpmMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
    prefixLen: Int,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setLpm(
        FieldMatch.LPM.newBuilder().setValue(ByteString.copyFrom(value)).setPrefixLen(prefixLen)
      )
      .build()

  private fun stringParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: String,
  ): p4.v1.P4RuntimeOuterClass.Action.Param =
    p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFromUtf8(value))
      .build()

  private fun bytesParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: ByteArray,
  ): p4.v1.P4RuntimeOuterClass.Action.Param =
    p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFrom(value))
      .build()

  // =========================================================================
  // Table entry builders
  // =========================================================================

  /** Builds a table entry with the given matches, action params, and optional priority. */
  private fun buildEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    matches: List<FieldMatch>,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
    priority: Int = 0,
  ): Entity {
    val entry =
      TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addAllMatch(matches)
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(action.preamble.id)
                .addAllParams(params)
            )
        )
    if (priority > 0) entry.setPriority(priority)
    return Entity.newBuilder().setTableEntry(entry).build()
  }

  /** Builds a vrf_table entry: exact match on vrf_id (string) → no_action. */
  private fun buildVrfEntry(vrfId: String): Entity {
    val table = findTable("vrf_table")
    val action = findAction("no_action")
    return buildEntry(table, action, matches = listOf(exactMatch(table, "vrf_id", vrfId)))
  }

  /**
   * Builds a router_interface_table entry:
   * - match: router_interface_id (string, exact)
   * - action: set_port_and_src_mac(port: string, src_mac: 48-bit MAC)
   */
  private fun buildRouterInterfaceEntry(
    rifId: String,
    port: String,
    srcMac: ByteArray = RIF_MAC,
  ): Entity {
    val table = findTable("router_interface_table")
    val action = findAction("set_port_and_src_mac")
    return buildEntry(
      table,
      action,
      matches = listOf(exactMatch(table, "router_interface_id", rifId)),
      params = listOf(stringParam(action, "port", port), bytesParam(action, "src_mac", srcMac)),
    )
  }

  /**
   * Builds an ipv4_table entry:
   * - match: vrf_id (string, exact) + ipv4_dst 10.0.0.0/8 (LPM)
   * - action: set_nexthop_id(nexthop_id: string)
   */
  private fun buildIpv4RouteEntry(vrfId: String, nexthopId: String): Entity {
    val table = findTable("ipv4_table")
    val action = findAction("set_nexthop_id")
    return buildEntry(
      table,
      action,
      matches =
        listOf(
          exactMatch(table, "vrf_id", vrfId),
          lpmMatch(table, "ipv4_dst", byteArrayOf(10, 0, 0, 0), prefixLen = 8),
        ),
      params = listOf(stringParam(action, "nexthop_id", nexthopId)),
    )
  }

  /**
   * Builds a nexthop_table entry:
   * - match: nexthop_id (string, exact)
   * - action: set_ip_nexthop(router_interface_id: string, neighbor_id: IPv6)
   */
  private fun buildNexthopEntry(
    nexthopId: String,
    routerInterfaceId: String,
    neighborId: ByteArray = NEIGHBOR_ID,
  ): Entity {
    val table = findTable("nexthop_table")
    val action = findAction("set_ip_nexthop")
    return buildEntry(
      table,
      action,
      matches = listOf(exactMatch(table, "nexthop_id", nexthopId)),
      params =
        listOf(
          stringParam(action, "router_interface_id", routerInterfaceId),
          bytesParam(action, "neighbor_id", neighborId),
        ),
    )
  }

  /**
   * Builds a neighbor_table entry:
   * - match: router_interface_id (string, exact) + neighbor_id (IPv6, exact)
   * - action: set_dst_mac(dst_mac: 48-bit MAC)
   */
  @Suppress("SameParameterValue")
  private fun buildNeighborEntry(rifId: String, neighborId: ByteArray, dstMac: ByteArray): Entity {
    val table = findTable("neighbor_table")
    val action = findAction("set_dst_mac")
    return buildEntry(
      table,
      action,
      matches =
        listOf(
          exactMatch(table, "router_interface_id", rifId),
          exactMatch(table, "neighbor_id", neighborId),
        ),
      params = listOf(bytesParam(action, "dst_mac", dstMac)),
    )
  }

  // =========================================================================
  // Packet builders and assertions
  // =========================================================================

  /** Builds a minimal Ethernet + IPv4 packet (no payload beyond the IP header). */
  @Suppress("SameParameterValue", "MagicNumber")
  private fun buildIpv4Packet(
    dstMac: ByteArray,
    srcMac: ByteArray,
    ttl: Int,
    srcIp: ByteArray = SRC_IP,
    dstIp: ByteArray = DST_IP,
  ): ByteArray {
    val packet = ByteArray(ETHERNET_HEADER_LEN + IPV4_HEADER_LEN)
    // Ethernet header: dst_mac + src_mac + ethertype (0x0800 = IPv4).
    System.arraycopy(dstMac, 0, packet, 0, MAC_LEN)
    System.arraycopy(srcMac, 0, packet, MAC_LEN, MAC_LEN)
    packet[12] = 0x08.toByte()
    packet[13] = 0x00.toByte()
    // IPv4 header (20 bytes, no options).
    packet[14] = 0x45.toByte() // version=4, IHL=5
    // total length = 20
    packet[16] = 0x00.toByte()
    packet[17] = IPV4_HEADER_LEN.toByte()
    packet[22] = ttl.toByte()
    packet[23] = 0x06.toByte() // protocol = TCP (arbitrary, not checked)
    // Checksum left as 0 — SAI P4 doesn't verify ingress checksums.
    System.arraycopy(srcIp, 0, packet, SRC_IP_OFFSET, 4)
    System.arraycopy(dstIp, 0, packet, DST_IP_OFFSET, 4)
    return packet
  }

  /** Builds a PacketOut with SAI P4 metadata for CPU-port injection. */
  @Suppress("MagicNumber")
  private fun buildCpuPacketOut(submitToIngress: Boolean): P4RuntimeOuterClass.PacketOut {
    val packetOutMeta =
      config.p4Info.controllerPacketMetadataList.find { it.preamble.name == "packet_out" }!!
    val egressPortId = packetOutMeta.metadataList.find { it.name == "egress_port" }!!.id
    val submitToIngressId = packetOutMeta.metadataList.find { it.name == "submit_to_ingress" }!!.id
    val unusedPadId = packetOutMeta.metadataList.find { it.name == "unused_pad" }!!.id

    return P4RuntimeOuterClass.PacketOut.newBuilder()
      .setPayload(
        ByteString.copyFrom(buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64))
      )
      .addMetadata(
        P4RuntimeOuterClass.PacketMetadata.newBuilder()
          .setMetadataId(egressPortId)
          .setValue(ByteString.copyFromUtf8("Ethernet1"))
      )
      .addMetadata(
        P4RuntimeOuterClass.PacketMetadata.newBuilder()
          .setMetadataId(submitToIngressId)
          .setValue(ByteString.copyFrom(byteArrayOf(if (submitToIngress) 1 else 0)))
      )
      .addMetadata(
        P4RuntimeOuterClass.PacketMetadata.newBuilder()
          .setMetadataId(unusedPadId)
          .setValue(ByteString.copyFrom(byteArrayOf(0)))
      )
      .build()
  }

  /** Installs the standard routing chain in @refers_to dependency order. */
  private fun installRoutingChain() {
    harness.installEntry(buildVrfEntry(""))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet1", RIF_MAC))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
    harness.installEntry(buildNexthopEntry(nexthopId = "nhop-1", routerInterfaceId = "rif-1"))
    harness.installEntry(buildIpv4RouteEntry(vrfId = "", nexthopId = "nhop-1"))
  }

  /** Installs an ACL ingress entry matching dst_ip=10.0.0.1 with the given action. */
  @Suppress("MagicNumber")
  private fun installAclEntry(action: P4InfoOuterClass.Action) {
    val aclTable = findTable("acl_ingress_table")
    harness.installEntry(
      buildEntry(
        aclTable,
        action,
        matches =
          listOf(
            optionalMatch(aclTable, "is_ipv4", byteArrayOf(1)),
            ternaryMatch(aclTable, "dst_ip", DST_IP, byteArrayOf(-1, -1, -1, -1)),
          ),
        params =
          when {
            action.paramsList.isEmpty() -> emptyList()
            action.paramsList.any { it.name == "qos_queue" } ->
              listOf(bytesParam(action, "qos_queue", byteArrayOf(0)))
            else -> error("unsupported ACL action params: ${action.paramsList.map { it.name }}")
          },
        priority = 1,
      )
    )
  }

  /**
   * Installs the clone infrastructure for copy-to-CPU:
   * 1. ingress_clone_table entry matching marked_to_copy=true → clone session 255.
   * 2. CloneSessionEntry 255 → CPU port 510.
   */
  @Suppress("MagicNumber")
  private fun installCopyToCpuCloneSession() {
    installIngressCloneTableEntry()
    installCloneSession(COPY_TO_CPU_SESSION_ID, CPU_PORT.toString())
  }

  /** Installs the ingress_clone_table entry that triggers clone3() with session 255. */
  @Suppress("MagicNumber")
  private fun installIngressCloneTableEntry() {
    val cloneTable = findTable("ingress_clone_table")
    val ingressClone = findAction("ingress_clone")
    harness.installEntry(
      buildEntry(
        cloneTable,
        ingressClone,
        matches =
          listOf(
            exactMatch(cloneTable, "marked_to_copy", byteArrayOf(1)),
            exactMatch(cloneTable, "marked_to_mirror", byteArrayOf(0)),
          ),
        params =
          listOf(
            bytesParam(
              ingressClone,
              "clone_session",
              FourwardTestHarness.longToBytes(COPY_TO_CPU_SESSION_ID.toLong(), 4),
            )
          ),
        priority = 1,
      )
    )
  }

  /**
   * Installs a PRE clone session entry with a single replica.
   *
   * Uses `Replica.port` (bytes, P4RT v1.4+) with a P4RT-encoded string value. This
   * forward-allocates the port in the PortTranslator, enabling reverse translation for PacketIn
   * metadata and `p4rt_egress_port` in InjectPacket responses.
   */
  @Suppress("MagicNumber", "SameParameterValue")
  private fun installCloneSession(sessionId: Int, p4rtPort: String, instance: Int = 1) {
    val entry =
      P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
        .setSessionId(sessionId)
        .addReplicas(
          P4RuntimeOuterClass.Replica.newBuilder()
            .setPort(ByteString.copyFromUtf8(p4rtPort))
            .setInstance(instance)
        )
        .build()
    harness.installEntry(
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder().setCloneSessionEntry(entry)
        )
        .build()
    )
  }

  /**
   * Installs a clone session using the deprecated `Replica.egress_port` (int32) field, which
   * bypasses port translation. Used to test the strict error path: outputs on ports without a P4RT
   * reverse mapping should fail loudly.
   */
  @Suppress("MagicNumber", "SameParameterValue", "DEPRECATION")
  private fun installCloneSessionDeprecated(sessionId: Int, egressPort: Int) {
    val entry =
      P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
        .setSessionId(sessionId)
        .addReplicas(
          P4RuntimeOuterClass.Replica.newBuilder().setEgressPort(egressPort).setInstance(1)
        )
        .build()
    harness.installEntry(
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder().setCloneSessionEntry(entry)
        )
        .build()
    )
  }

  /** Installs a PRE multicast group with one replica per P4RT port string. */
  @Suppress("MagicNumber")
  private fun installMulticastGroup(groupId: Int, p4rtPorts: List<String>) {
    val entry =
      P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
        .setMulticastGroupId(groupId)
        .addAllReplicas(
          p4rtPorts.mapIndexed { idx, port ->
            P4RuntimeOuterClass.Replica.newBuilder()
              .setPort(ByteString.copyFromUtf8(port))
              .setInstance(idx)
              .build()
          }
        )
        .build()
    val preEntity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(entry)
        )
        .build()
    harness.installEntry(preEntity)
  }

  /** Builds an action profile member for the WCMP selector. */
  private fun buildActionProfileMember(
    actionProfileId: Int,
    memberId: Int,
    action: P4InfoOuterClass.Action,
    nexthopId: String,
  ): Entity =
    Entity.newBuilder()
      .setActionProfileMember(
        P4RuntimeOuterClass.ActionProfileMember.newBuilder()
          .setActionProfileId(actionProfileId)
          .setMemberId(memberId)
          .setAction(
            P4RuntimeOuterClass.Action.newBuilder()
              .setActionId(action.preamble.id)
              .addParams(stringParam(action, "nexthop_id", nexthopId))
          )
      )
      .build()

  /** Builds an action profile group for the WCMP selector. */
  private fun buildActionProfileGroup(
    actionProfileId: Int,
    groupId: Int,
    memberIds: List<Int>,
  ): Entity =
    Entity.newBuilder()
      .setActionProfileGroup(
        P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
          .setActionProfileId(actionProfileId)
          .setGroupId(groupId)
          .addAllMembers(
            memberIds.map { mid ->
              P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                .setMemberId(mid)
                .setWeight(1)
                .build()
            }
          )
      )
      .build()

  /** Asserts that [expected] bytes match [actual] starting at [offset]. */
  private fun assertBytesEqual(label: String, expected: ByteArray, actual: ByteArray, offset: Int) {
    for (i in expected.indices) {
      assertEquals("$label byte $i mismatch", expected[i], actual[offset + i])
    }
  }

  /** Looks up the metadata field name for a packet_in metadata ID from p4info. */
  private fun findPacketInMetadataName(metadataId: Int): String? {
    val packetIn =
      config.p4Info.controllerPacketMetadataList.find { it.preamble.name == "packet_in" }
        ?: return null
    return packetIn.metadataList.find { it.id == metadataId }?.name
  }

  /** Returns the P4RT (string) value of the named `packet_in` metadata field. */
  private fun packetInMetaValue(packetIn: P4RuntimeOuterClass.PacketIn, name: String): String =
    packetIn.metadataList
      .first { findPacketInMetadataName(it.metadataId) == name }
      .value
      .toStringUtf8()

  companion object {
    private const val SUBSCRIBE_RESULT_TIMEOUT_MS = 20_000L

    // CPU port = 2^9 - 2 = 510 for 9-bit ports (SAI P4 ids.h: SAI_P4_CPU_PORT).
    private const val CPU_PORT = 510
    private const val COPY_TO_CPU_SESSION_ID = 255
    private const val MIRROR_PORT = 5
    private const val MIRROR_CLONE_SESSION_ID = 100
    // ids.h: SAI_P4_REPLICA_INSTANCE_MIRRORING
    private const val REPLICA_INSTANCE_MIRRORING = 2

    /**
     * Adds explicit port_id_t translation entries to the pipeline config (hybrid mode: explicit
     * pins + auto-allocate the rest). This mirrors what a real switch does via platform config —
     * architecture-defined ports like the CPU port need fixed P4RT ↔ dataplane mappings that
     * auto-allocation alone can't provide.
     */
    private fun withPortMappings(
      config: PipelineConfig,
      ports: Map<String, Int>,
      autoAllocate: Boolean = true,
    ): PipelineConfig {
      val portTranslation =
        TypeTranslation.newBuilder().setTypeName("port_id_t").setAutoAllocate(autoAllocate)
      for ((name, dpValue) in ports) {
        portTranslation.addEntries(
          TranslationEntry.newBuilder().setSdnStr(name).setDataplaneValue(encodeMinWidth(dpValue))
        )
      }
      return config
        .toBuilder()
        .setDevice(config.device.toBuilder().addTranslations(portTranslation))
        .build()
    }

    private const val MAC_LEN = 6
    private const val ETHERNET_HEADER_LEN = 14
    private const val IPV4_HEADER_LEN = 20
    private const val TTL_OFFSET = 22 // Ethernet(14) + IPv4 byte 8
    private const val SRC_IP_OFFSET = 26 // Ethernet(14) + IPv4 byte 12
    private const val DST_IP_OFFSET = 30 // Ethernet(14) + IPv4 byte 16

    // ::1 — used as neighbor_id in nexthop and neighbor table entries.
    private val NEIGHBOR_ID = ByteArray(16) { if (it == 15) 1 else 0 }

    private val NEIGHBOR_MAC =
      byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
    private val ALT_NEIGHBOR_MAC =
      byteArrayOf(0x00, 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    private val RIF_MAC = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val ALT_RIF_MAC = byteArrayOf(0x00, 0x22, 0x33, 0x44, 0x55, 0x66)
    private val UNICAST_MAC = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)

    // IPv4 multicast MAC for 224.0.0.1: 01:00:5E:00:00:01 (bit 23 = 0).
    // SAI P4 checks IS_IPV4_MULTICAST_MAC(dst_addr) = addr[47:24]==0x01005E && addr[23:23]==0.
    private val MULTICAST_MAC = byteArrayOf(0x01, 0x00, 0x5E, 0x00, 0x00, 0x01)
    private val SRC_MAC = byteArrayOf(0x00, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E)
    private val SRC_IP = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
    private val DST_IP = byteArrayOf(10, 0, 0, 1)

    // 2001:db8:: — used as IPv6 prefix in routing and tunnel tests.
    @Suppress("MagicNumber")
    private val IPV6_2001_DB8 =
      byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private suspend fun CoroutineScope.subscribeAndAwaitActive(
      stub: DataplaneCoroutineStub
    ): Pair<Job, Channel<SubscribeResultsResponse>> {
      val results = Channel<SubscribeResultsResponse>(UNLIMITED)
      val job = launch {
        stub.subscribeResults(SubscribeResultsRequest.getDefaultInstance()).collect {
          results.send(it)
        }
      }
      val first = withTimeout(SUBSCRIBE_RESULT_TIMEOUT_MS) { results.receive() }
      assertTrue("first message should be SubscriptionActive", first.hasActive())
      return job to results
    }
  }
}
