@file:Suppress("UnusedPrivateProperty")

package fourward.grpc

import com.google.protobuf.ByteString
import fourward.AddLinksRequest
import fourward.CreateDevicesRequest
import fourward.FourwardManagementGrpcKt.FourwardManagementCoroutineStub
import fourward.InjectNetworkPacketRequest
import fourward.Link
import fourward.ListLinksRequest
import fourward.NetworkGrpcKt.NetworkCoroutineStub
import fourward.NetworkPort
import fourward.PipelineConfig
import fourward.RemoveLinksRequest
import fourward.grpc.FourwardTestHarness.Companion.assertGrpcError
import fourward.grpc.FourwardTestHarness.Companion.buildEthernetFrame
import fourward.grpc.FourwardTestHarness.Companion.buildMulticastGroup
import fourward.grpc.FourwardTestHarness.Companion.findAction
import fourward.grpc.FourwardTestHarness.Companion.findTable
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import fourward.grpc.FourwardTestHarness.Companion.longToBytes
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeGrpcKt.P4RuntimeCoroutineStub
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.ActionProfileGroup
import p4.v1.P4RuntimeOuterClass.ActionProfileMember
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.TableAction
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest

class NetworkServiceTest {
  private lateinit var harness: Harness

  @Before
  fun setUp() {
    harness = Harness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  @Test
  fun `AddLinks RemoveLinks and ListLinks are atomic and strict`() = runBlocking {
    harness.createDevices(2, 1)
    val link = link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 0))

    assertGrpcError(Status.Code.INVALID_ARGUMENT, "appears more than once in request") {
      harness.addLinks(
        link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 0)),
        link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 1)),
      )
    }

    harness.addLinks(link)
    assertEquals(listOf(link), harness.listLinks())

    assertGrpcError(Status.Code.ALREADY_EXISTS, "already linked") { harness.addLinks(link) }
    assertEquals("duplicate add leaves topology unchanged", listOf(link), harness.listLinks())

    val unlinkedEndpoint = link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 7))
    assertGrpcError(Status.Code.NOT_FOUND, "is not linked") {
      harness.removeLinks(unlinkedEndpoint)
    }
    assertEquals("failed remove leaves topology unchanged", listOf(link), harness.listLinks())

    assertGrpcError(Status.Code.INVALID_ARGUMENT, "appears more than once in request") {
      harness.removeLinks(link, link)
    }
    assertEquals("duplicate remove leaves topology unchanged", listOf(link), harness.listLinks())

    val mismatchedPeer = link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 1))
    harness.addLinks(link(dp(deviceId = 2, port = 1), dp(deviceId = 1, port = 0)))
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "does not match configured topology") {
      harness.removeLinks(mismatchedPeer)
    }
    assertEquals("failed remove leaves topology unchanged", 2, harness.listLinks().size)

    harness.removeLinks(link)
    harness.removeLinks(link(dp(deviceId = 2, port = 1), dp(deviceId = 1, port = 0)))
    assertEquals(emptyList<Link>(), harness.listLinks())
  }

  @Test
  fun `dataplane link traverses two switches and returns a network trace`() = runBlocking {
    harness.createDevices(2, 1)
    harness.addLinks(link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 0)))
    harness.loadPipeline(1, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.loadPipeline(2, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))

    val response = harness.inject(dp(deviceId = 1, port = 0), maxHops = 3)

    assertEquals(1L, response.trace.root.deviceId)
    val firstTraversal = response.trace.root.traversalsList.single()
    assertTrue(firstTraversal.hasNextHop())
    assertEquals(2L, firstTraversal.nextHop.deviceId)
    val secondTraversal = firstTraversal.nextHop.traversalsList.single()
    assertTrue(secondTraversal.hasNetworkEgress())
    assertEquals(dp(deviceId = 2, port = 1), secondTraversal.networkEgress.egress)
    assertEquals(
      listOf(2L),
      response.possibleOutcomesList.single().packetsList.map { it.egress.deviceId },
    )
  }

  @Test
  fun `output without a matching link becomes network egress`() = runBlocking {
    harness.loadPipeline(1, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))

    val response = harness.inject(dp(deviceId = 1, port = 0), maxHops = 2)

    val traversal = response.trace.root.traversalsList.single()
    assertTrue(traversal.hasNetworkEgress())
    assertEquals(dp(deviceId = 1, port = 1), traversal.networkEgress.egress)
    assertEquals(
      listOf(dp(deviceId = 1, port = 1)),
      response.possibleOutcomesList.single().packetsList.map { it.egress },
    )
  }

  @Test
  fun `P4Runtime link uses translated ports`() = runBlocking {
    harness.createDevices(2, 1)
    val config = loadConfig("e2e_tests/translated_port/translated_port.txtpb")
    harness.loadPipeline(1, config)
    harness.loadPipeline(2, config)
    harness.write(1, translatedForwardingEntry(config, portName = "Ethernet1"))
    harness.write(2, translatedForwardingEntry(config, portName = "Ethernet1"))
    harness.addLinks(
      link(p4rt(deviceId = 1, port = "Ethernet1"), p4rt(deviceId = 2, port = "Ethernet0"))
    )

    val response = harness.inject(p4rt(deviceId = 1, port = "Ethernet0"), maxHops = 3)

    val egress = response.possibleOutcomesList.single().packetsList.single().egress
    assertEquals(p4rt(deviceId = 2, port = "Ethernet1"), egress)
  }

  @Test
  fun `P4Runtime topology can be configured before pipelines are loaded`() = runBlocking {
    harness.createDevices(2, 1)
    val link = link(p4rt(deviceId = 1, port = "Ethernet1"), p4rt(deviceId = 2, port = "Ethernet0"))

    harness.addLinks(link)
    assertEquals(listOf(link), harness.listLinks())

    assertGrpcError(Status.Code.FAILED_PRECONDITION, "no pipeline is loaded") {
      harness.inject(p4rt(deviceId = 1, port = "Ethernet0"), maxHops = 2)
    }
  }

  @Test
  fun `output matching dataplane and P4Runtime links fails loudly`() = runBlocking {
    harness.createDevices(2, 2)
    val config = loadConfig("e2e_tests/translated_port/translated_port.txtpb")
    harness.loadPipeline(1, config)
    harness.loadPipeline(2, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.loadPipeline(3, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.write(1, translatedForwardingEntry(config, portName = "Ethernet1"))
    harness.addLinks(
      link(dp(deviceId = 1, port = 0), dp(deviceId = 2, port = 0)),
      link(p4rt(deviceId = 1, port = "Ethernet1"), dp(deviceId = 3, port = 0)),
    )

    assertGrpcError(Status.Code.FAILED_PRECONDITION, "matches multiple configured links") {
      harness.inject(p4rt(deviceId = 1, port = "Ethernet0"), maxHops = 2)
    }
  }

  @Test
  fun `hop limit stops traversal deterministically`() = runBlocking {
    harness.createDevices(2, 1)
    harness.loadPipeline(1, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.loadPipeline(2, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.addLinks(link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 0)))

    val response = harness.inject(dp(deviceId = 1, port = 0), maxHops = 1)

    val traversal = response.trace.root.traversalsList.single()
    assertTrue(traversal.hasHopLimitExceeded())
    assertEquals(dp(deviceId = 2, port = 0), traversal.hopLimitExceeded.nextIngress)
    assertTrue(response.possibleOutcomesList.single().packetsList.isEmpty())
  }

  @Test
  fun `replication follows all output leaves in one possible outcome`() = runBlocking {
    harness.createDevices(2, 2)
    harness.loadPipeline(1, loadConfig("e2e_tests/trace_tree/multicast.txtpb"))
    harness.loadPipeline(2, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.loadPipeline(3, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.write(1, buildMulticastGroup(groupId = 1, ports = listOf(1, 2)))
    harness.addLinks(
      link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 0)),
      link(dp(deviceId = 1, port = 2), dp(deviceId = 3, port = 0)),
    )

    val response = harness.inject(dp(deviceId = 1, port = 0), maxHops = 3)

    assertEquals("replication is one possible outcome", 1, response.possibleOutcomesCount)
    assertEquals(
      listOf(2L, 3L),
      response.possibleOutcomesList.single().packetsList.map { it.egress.deviceId }.sorted(),
    )
  }

  @Test
  fun `replication records distinct traversals over the same link`() = runBlocking {
    harness.createDevices(2, 1)
    harness.loadPipeline(1, loadConfig("e2e_tests/trace_tree/multicast.txtpb"))
    harness.loadPipeline(2, loadConfig("e2e_tests/passthrough/passthrough.txtpb"))
    harness.write(1, buildMulticastGroup(groupId = 1, ports = listOf(1, 1)))
    harness.addLinks(link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 0)))

    val response = harness.inject(dp(deviceId = 1, port = 0), maxHops = 3)

    assertEquals(2, response.trace.root.traversalsCount)
    assertEquals(listOf(1L, 2L), response.trace.root.traversalsList.map { it.sourceOutputId })
    assertEquals(listOf(2L, 2L), response.trace.root.traversalsList.map { it.nextHop.deviceId })
    assertEquals("replication is still one possible outcome", 1, response.possibleOutcomesCount)
    assertEquals(2, response.possibleOutcomesList.single().packetsCount)
    assertEquals(
      listOf(dp(deviceId = 2, port = 1), dp(deviceId = 2, port = 1)),
      response.possibleOutcomesList.single().packetsList.map { it.egress },
    )
  }

  @Test
  fun `choice branches remain separate possible outcomes`() = runBlocking {
    harness.createDevices(2, 2)
    val selector = loadConfig("e2e_tests/trace_tree/action_selector_3.txtpb")
    val passthrough = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
    harness.loadPipeline(1, selector)
    harness.loadPipeline(2, passthrough)
    harness.loadPipeline(3, passthrough)
    harness.installSelectorGroup(deviceId = 1, config = selector, memberPorts = listOf(1, 2))
    harness.addLinks(
      link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 0)),
      link(dp(deviceId = 1, port = 2), dp(deviceId = 3, port = 0)),
    )

    val response = harness.inject(dp(deviceId = 1, port = 0), maxHops = 3)

    assertEquals(
      "each selector member is a separate possible outcome",
      2,
      response.possibleOutcomesCount,
    )
    assertEquals(
      listOf(listOf(2L), listOf(3L)),
      response.possibleOutcomesList.map { outcome ->
        outcome.packetsList.map { it.egress.deviceId }
      },
    )
  }

  @Test
  fun `choice branches with identical network egress collapse to one possible outcome`() =
    runBlocking {
      harness.createDevices(2, 1)
      val selector = loadConfig("e2e_tests/trace_tree/action_selector_3.txtpb")
      val passthrough = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
      harness.loadPipeline(1, selector)
      harness.loadPipeline(2, passthrough)
      harness.installSelectorGroup(deviceId = 1, config = selector, memberPorts = listOf(1, 1))
      harness.addLinks(link(dp(deviceId = 1, port = 1), dp(deviceId = 2, port = 0)))

      val response = harness.inject(dp(deviceId = 1, port = 0), maxHops = 3)

      assertEquals("trace preserves both selector branches", 2, response.trace.root.traversalsCount)
      assertEquals("identical network outcomes collapse", 1, response.possibleOutcomesCount)
      assertEquals(
        listOf(dp(deviceId = 2, port = 1)),
        response.possibleOutcomesList.single().packetsList.map { it.egress },
      )
    }

  private class Harness : Closeable {
    private val registry = DeviceRegistry(DeviceSettings())
    private val serverName = InProcessServerBuilder.generateName()
    private val executor = java.util.concurrent.Executors.newCachedThreadPool()
    private val server =
      InProcessServerBuilder.forName(serverName)
        .executor(executor)
        .addService(MultiDeviceP4RuntimeService(registry))
        .addService(MultiDeviceDataplaneService(registry))
        .addService(ManagementService(registry))
        .addService(NetworkService(registry))
        .build()
        .start()

    private val channel: ManagedChannel = InProcessChannelBuilder.forName(serverName).build()
    private val p4rt = P4RuntimeCoroutineStub(channel)
    private val management = FourwardManagementCoroutineStub(channel)
    private val network = NetworkCoroutineStub(channel)

    suspend fun createDevices(firstDeviceId: Long, count: Int) {
      management.createDevices(
        CreateDevicesRequest.newBuilder().setFirstDeviceId(firstDeviceId).setCount(count).build()
      )
    }

    suspend fun loadPipeline(deviceId: Long, config: PipelineConfig) {
      val unused =
        p4rt.setForwardingPipelineConfig(
          SetForwardingPipelineConfigRequest.newBuilder()
            .setDeviceId(deviceId)
            .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
            .setConfig(
              ForwardingPipelineConfig.newBuilder()
                .setP4Info(config.p4Info)
                .setP4DeviceConfig(config.device.toByteString())
            )
            .build()
        )
    }

    suspend fun write(deviceId: Long, entity: Entity) {
      val unused =
        p4rt.write(
          WriteRequest.newBuilder()
            .setDeviceId(deviceId)
            .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(entity))
            .build()
        )
    }

    suspend fun addLinks(vararg links: Link) {
      network.addLinks(AddLinksRequest.newBuilder().addAllLinks(links.asList()).build())
    }

    suspend fun removeLinks(vararg links: Link) {
      network.removeLinks(RemoveLinksRequest.newBuilder().addAllLinks(links.asList()).build())
    }

    suspend fun listLinks(): List<Link> =
      network.listLinks(ListLinksRequest.getDefaultInstance()).linksList

    suspend fun inject(
      ingress: NetworkPort,
      maxHops: Int,
      payload: ByteArray = buildEthernetFrame(etherType = 0x0800),
    ) =
      network.injectPacket(
        InjectNetworkPacketRequest.newBuilder()
          .setIngress(ingress)
          .setPayload(ByteString.copyFrom(payload))
          .setMaxHops(maxHops)
          .build()
      )

    suspend fun installSelectorGroup(
      deviceId: Long,
      config: PipelineConfig,
      memberPorts: List<Int>,
    ) {
      val schema = SelectorSchema.discover(config.p4Info)
      for ((index, port) in memberPorts.withIndex()) {
        write(deviceId, selectorMember(schema, memberId = index + 1, port = port))
      }
      write(
        deviceId,
        selectorGroup(schema, groupId = 1, memberIds = (1..memberPorts.size).toList()),
      )
      write(deviceId, selectorTableEntry(schema, groupId = 1))
    }

    override fun close() {
      channel.shutdownNow()
      server.shutdownNow()
      executor.shutdownNow()
      registry.close()
    }
  }

  private data class SelectorSchema(
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
    private fun dp(deviceId: Long, port: Int): NetworkPort =
      NetworkPort.newBuilder().setDeviceId(deviceId).setDataplanePort(port).build()

    private fun p4rt(deviceId: Long, port: String): NetworkPort =
      NetworkPort.newBuilder()
        .setDeviceId(deviceId)
        .setP4RtPort(ByteString.copyFromUtf8(port))
        .build()

    private fun link(a: NetworkPort, b: NetworkPort): Link =
      Link.newBuilder().setA(a).setB(b).build()

    private fun translatedForwardingEntry(config: PipelineConfig, portName: String): Entity {
      val table = findTable(config, "forwarding")
      val action = findAction(config, "forward")
      return Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              FieldMatch.newBuilder()
                .setFieldId(FourwardTestHarness.matchFieldId(table, "hdr.ethernet.ether_type"))
                .setExact(
                  FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0x0800, 2)))
                )
            )
            .setAction(
              TableAction.newBuilder()
                .setAction(
                  Action.newBuilder()
                    .setActionId(action.preamble.id)
                    .addParams(
                      Action.Param.newBuilder()
                        .setParamId(FourwardTestHarness.paramId(action, "port"))
                        .setValue(ByteString.copyFromUtf8(portName))
                    )
                )
            )
        )
        .build()
    }

    private fun selectorMember(schema: SelectorSchema, memberId: Int, port: Int): Entity =
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

    private fun selectorGroup(schema: SelectorSchema, groupId: Int, memberIds: List<Int>): Entity =
      Entity.newBuilder()
        .setActionProfileGroup(
          ActionProfileGroup.newBuilder()
            .setActionProfileId(schema.profileId)
            .setGroupId(groupId)
            .setMaxSize(memberIds.size)
            .addAllMembers(
              memberIds.map { memberId ->
                ActionProfileGroup.Member.newBuilder().setMemberId(memberId).setWeight(1).build()
              }
            )
        )
        .build()

    private fun selectorTableEntry(schema: SelectorSchema, groupId: Int): Entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(schema.tableId)
            .addMatch(
              FieldMatch.newBuilder()
                .setFieldId(schema.matchFieldId)
                .setExact(
                  FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(ByteArray(6) { 0xff.toByte() }))
                )
            )
            .setAction(TableAction.newBuilder().setActionProfileGroupId(groupId))
        )
        .build()
  }
}
