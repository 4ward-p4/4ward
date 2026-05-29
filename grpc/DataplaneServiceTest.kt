package fourward.grpc

import com.google.protobuf.ByteString
import fourward.DataplaneGrpcKt.DataplaneCoroutineStub
import fourward.InjectPacketRequest
import fourward.PrePacketHookInvocation
import fourward.PrePacketHookResponse
import fourward.SubscribeResultsRequest
import fourward.SubscribeResultsResponse
import fourward.e2e.compileInlineP4
import fourward.grpc.FourwardTestHarness.Companion.assertGrpcError
import fourward.grpc.FourwardTestHarness.Companion.buildEthernetFrame
import fourward.grpc.FourwardTestHarness.Companion.buildExactEntry
import fourward.grpc.FourwardTestHarness.Companion.buildMulticastGroup
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import io.grpc.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4Types

class DataplaneServiceTest {

  private lateinit var harness: FourwardTestHarness

  @Before
  fun setUp() {
    harness = FourwardTestHarness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  private fun loadPassthroughConfig() = loadConfig("e2e_tests/passthrough/passthrough.txtpb")

  private fun loadBasicTableConfig() = loadConfig("e2e_tests/basic_table/basic_table.txtpb")

  // =========================================================================
  // InjectPacket
  // =========================================================================

  @Test
  fun `InjectPacket returns outputs and trace`() {
    harness.loadPipeline(loadPassthroughConfig())
    val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
    val response = harness.injectPacket(ingressPort = 0, payload = payload)

    assertEquals(
      "passthrough produces 1 output",
      1,
      response.possibleOutcomesList.single().packetsCount,
    )
    assertTrue("trace should be present", response.hasTrace())
    assertEquals(
      "output payload matches input",
      ByteString.copyFrom(payload),
      response.possibleOutcomesList.single().getPackets(0).payload,
    )
  }

  @Test
  fun `InjectPacket with table entries forwards to correct port`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    val payload = buildEthernetFrame(etherType = 0x0800)
    val response = harness.injectPacket(ingressPort = 0, payload = payload)

    assertEquals("expected 1 output", 1, response.possibleOutcomesList.single().packetsCount)
    assertEquals(
      "should exit on port 1",
      1,
      response.possibleOutcomesList.single().getPackets(0).dataplaneEgressPort,
    )
  }

  @Test
  fun `InjectPacket with no matching entry produces no outputs`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // No table entries installed — default action is drop.
    val payload = buildEthernetFrame(etherType = 0x0800)
    val response = harness.injectPacket(ingressPort = 0, payload = payload)

    assertEquals(
      "dropped packet produces no outputs",
      0,
      response.possibleOutcomesList.single().packetsCount,
    )
  }

  // =========================================================================
  // GetReproducer
  // =========================================================================

  @Test
  fun `GetReproducer is self-contained`() {
    val config = loadPassthroughConfig()
    harness.loadPipeline(config)
    val payload = byteArrayOf(0x01)
    val reproducer = harness.getReproducer(ingressPort = 0, payload = payload)

    assertEquals("pipeline config", config, reproducer.pipelineConfig)
    assertTrue("result", reproducer.hasResult())
    assertEquals("ingress port", 0, reproducer.result.inputPacket.dataplaneIngressPort)
    assertEquals("payload", ByteString.copyFrom(payload), reproducer.result.inputPacket.payload)
    assertTrue("trace", reproducer.result.hasTrace())
    assertTrue("possible outcomes", reproducer.result.possibleOutcomesCount > 0)
  }

  @Test
  fun `GetReproducer contains matched table entry`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    val payload = buildEthernetFrame(etherType = 0x0800)
    val reproducer = harness.getReproducer(ingressPort = 0, payload = payload)

    val tableEntities = reproducer.entitiesList.filter { it.hasTableEntry() }
    assertTrue("reproducer should contain the matched entry", tableEntities.isNotEmpty())
  }

  @Test
  fun `GetReproducer excludes entries on table miss`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val payload = buildEthernetFrame(etherType = 0x0800)
    val reproducer = harness.getReproducer(ingressPort = 0, payload = payload)

    assertTrue("reproducer should have no entities on miss", reproducer.entitiesList.isEmpty())
  }

  @Test
  fun `GetReproducer fails without loaded pipeline`() {
    assertGrpcError(Status.Code.FAILED_PRECONDITION) {
      harness.getReproducer(ingressPort = 0, payload = byteArrayOf(0x01))
    }
  }

  @Test
  fun `GetReproducer round-trip produces same trace as InjectPacket`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    val payload = buildEthernetFrame(etherType = 0x0800)
    val injectResponse = harness.injectPacket(ingressPort = 0, payload = payload)
    val reproducer = harness.getReproducer(ingressPort = 0, payload = payload)

    assertEquals("trace should match InjectPacket", injectResponse.trace, reproducer.result.trace)
    assertEquals(
      "possible outcomes should match InjectPacket",
      injectResponse.possibleOutcomesList,
      reproducer.result.possibleOutcomesList,
    )
  }

  @Test
  fun `GetReproducer includes multicast group entities`() {
    val config = loadConfig("e2e_tests/trace_tree/multicast.txtpb")
    harness.loadPipeline(config)
    harness.installEntry(buildMulticastGroup(groupId = 1, ports = listOf(1, 2, 3)))

    val payload = buildEthernetFrame(etherType = 0x0800)
    val reproducer = harness.getReproducer(ingressPort = 0, payload = payload)

    val preEntities = reproducer.entitiesList.filter { it.hasPacketReplicationEngineEntry() }
    assertTrue("reproducer should contain multicast group", preEntities.isNotEmpty())
    assertEquals(
      1,
      preEntities.first().packetReplicationEngineEntry.multicastGroupEntry.multicastGroupId,
    )
  }

  @Test
  fun `GetReproducer extracts only the multicast group used by the trace`() {
    val config = loadConfig("e2e_tests/trace_tree/multicast.txtpb")
    harness.loadPipeline(config)
    // The P4 program hardcodes mcast_grp = 1. Install group 1 (used) and group 99 (unused).
    harness.installEntry(buildMulticastGroup(groupId = 1, ports = listOf(1, 2, 3)))
    harness.installEntry(buildMulticastGroup(groupId = 99, ports = listOf(4, 5)))

    val payload = buildEthernetFrame(etherType = 0x0800)
    val reproducer = harness.getReproducer(ingressPort = 0, payload = payload)

    val mcastEntities =
      reproducer.entitiesList
        .filter { it.hasPacketReplicationEngineEntry() }
        .map { it.packetReplicationEngineEntry.multicastGroupEntry.multicastGroupId }
    assertEquals("should contain only group 1", listOf(1), mcastEntities)
  }

  @Test
  fun `GetReproducer includes modified default action`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val noAction = config.p4Info.actionsList.find { it.preamble.name == "NoAction" }!!
    harness.modifyEntry(
      FourwardTestHarness.buildDefaultActionEntity(table.preamble.id, noAction.preamble.id)
    )

    val payload = buildEthernetFrame(etherType = 0x0800)
    val reproducer = harness.getReproducer(ingressPort = 0, payload = payload)

    val defaultEntities =
      reproducer.entitiesList.filter { it.hasTableEntry() && it.tableEntry.isDefaultAction }
    assertTrue("reproducer should contain modified default", defaultEntities.isNotEmpty())
  }

  // =========================================================================
  // SubscribeResults
  // =========================================================================

  @Test
  fun `SubscribeResults first message is SubscriptionActive`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)
    val first = stub.subscribeResults(SubscribeResultsRequest.getDefaultInstance()).first()
    assertTrue("first message should be SubscriptionActive", first.hasActive())
  }

  @Test
  fun `SubscribeResults delivers result after injection`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)
    val (job, results) = subscribeAndAwaitActive(stub)

    harness.injectPacket(ingressPort = 0, payload = byteArrayOf(0x01))

    val result = withTimeout(5000) { results.receive() }
    assertTrue("expected ProcessPacketResult", result.hasResult())
    assertEquals(0, result.result.inputPacket.dataplaneIngressPort)
    assertEquals(ByteString.copyFrom(byteArrayOf(0x01)), result.result.inputPacket.payload)

    job.cancel()
  }

  @Test
  fun `SubscribeResults echoes tag from InjectPacket`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)
    val (job, results) = subscribeAndAwaitActive(stub)

    harness.injectPacket(ingressPort = 0, payload = byteArrayOf(0x01), tag = 42)

    val result = withTimeout(5000) { results.receive() }
    assertEquals(42L, result.result.inputPacket.tag)

    job.cancel()
  }

  // =========================================================================
  // Cross-source SubscribeResults
  // =========================================================================

  @Test
  fun `SubscribeResults receives results from both InjectPacket and PacketOut`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)
    val (job, results) = subscribeAndAwaitActive(stub)

    // Source 1: InjectPacket via DataplaneService.
    harness.injectPacket(ingressPort = 0, payload = byteArrayOf(0xAA.toByte()))

    // Source 2: PacketOut via P4RuntimeService StreamChannel.
    // Passthrough has no @controller_header, so no PacketIn is produced — but the packet
    // still flows through the broker and should reach the SubscribeResults subscriber.
    harness.openStream().use { session ->
      session.arbitrate()
      session.sendPacket(
        byteArrayOf(0xBB.toByte()),
        timeoutMs = FourwardTestHarness.NO_RESPONSE_TIMEOUT_MS,
      )
    }

    val collected = withTimeout(5000) { listOf(results.receive(), results.receive()) }
    assertTrue("should be a result", collected[0].hasResult())
    assertTrue("should be a result", collected[1].hasResult())

    job.cancel()
  }

  // =========================================================================
  // Concurrency
  // =========================================================================

  @Test
  fun `concurrent InjectPacket calls all produce correct results`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    // Demonstrates concurrent processing is a supported use case. Cannot deterministically
    // catch JMM races on x86 (TSO hides most reordering), so this is documentation-by-test,
    // not a regression test for the underlying race fixed in the PR — that lives in the
    // architecture-level invariants, audited at review time.
    val count = 100

    val results =
      (0 until count).map { i ->
        async(Dispatchers.IO) {
          harness.injectPacket(ingressPort = 0, payload = byteArrayOf(i.toByte()))
        }
      }

    val responses = results.map { it.await() }
    assertEquals("all $count should complete", count, responses.size)
    for (response in responses) {
      assertEquals(
        "each should produce 1 output",
        1,
        response.possibleOutcomesList.single().packetsCount,
      )
    }
  }

  @Test
  fun `SubscribeResults receives all results from concurrent injections`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)
    val count = 5
    val (job, results) = subscribeAndAwaitActive(stub)

    val injections =
      (0 until count).map { i ->
        async(Dispatchers.IO) {
          harness.injectPacket(ingressPort = 0, payload = byteArrayOf(i.toByte()))
        }
      }
    for (j in injections) j.await()

    withTimeout(10_000) {
      repeat(count) {
        val msg = results.receive()
        assertTrue("should be a result", msg.hasResult())
      }
    }

    job.cancel()
  }

  // =========================================================================
  // Subscriber backpressure
  // =========================================================================

  @Test
  fun `SubscribeResults preserves results when subscriber falls behind`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)

    val collected = java.util.concurrent.atomic.AtomicInteger(0)
    val ready = CompletableDeferred<Unit>()
    val collectJob =
      launch(Dispatchers.IO) {
        stub.subscribeResults(SubscribeResultsRequest.getDefaultInstance()).collect {
          if (it.hasActive()) {
            ready.complete(Unit)
          } else {
            collected.incrementAndGet()
            delay(50) // slow consumer: forces backpressure on bursty input
          }
        }
      }

    withTimeout(5000) { ready.await() }

    // 200 packets is well above the typical callbackFlow buffer (~64).
    repeat(200) { i -> harness.injectPacket(ingressPort = 0, payload = byteArrayOf(i.toByte())) }

    withTimeout(20_000) { while (collected.get() < 200) delay(50) }
    collectJob.cancel()

    assertEquals("all results should be preserved", 200, collected.get())
  }

  // =========================================================================
  // Dual port encoding
  // =========================================================================

  @Test
  fun `InjectPacket with P4RT port fails without pipeline`() {
    val p4rtPort = ByteString.copyFrom(byteArrayOf(0, 0, 0, 1))
    assertGrpcError(Status.Code.FAILED_PRECONDITION) {
      harness.injectPacketP4rt(p4rtPort, byteArrayOf(0x01))
    }
  }

  @Test
  fun `response output packets have no P4RT port without translation`() {
    harness.loadPipeline(loadPassthroughConfig())
    val response = harness.injectPacket(ingressPort = 0, payload = byteArrayOf(0x01))

    assertEquals(
      "passthrough produces 1 output",
      1,
      response.possibleOutcomesList.single().packetsCount,
    )
    val output = response.possibleOutcomesList.single().getPackets(0)
    assertTrue(
      "p4rt_egress_port should be empty without translation",
      output.p4RtEgressPort.isEmpty,
    )
  }

  // Positive dual-encoding tests (P4RT port injection and response population) are in
  // SaiP4E2ETest, which uses a program with @controller_header and @p4runtime_translation.

  @Test
  @Suppress("MagicNumber")
  fun `unmapped egress port omits P4RT port but preserves PacketIn enrichment`() {
    val baseConfig =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      @controller_header("packet_out")
      header packet_out_t { bit<9> egress_port; }

      @controller_header("packet_in")
      header packet_in_t { bit<9> ingress_port; bit<9> target_egress_port; }

      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { packet_out_t pkt_out; packet_in_t pkt_in; ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply { sm.egress_spec = 510; }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply {
          h.pkt_in.setValid();
          h.pkt_in.ingress_port = sm.ingress_port;
          h.pkt_in.target_egress_port = sm.egress_port;
        }
      }
      control D(packet_out pkt, in headers_t h) {
        apply { pkt.emit(h.pkt_in); pkt.emit(h.eth); }
      }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    // Inject port translation: pretend the port type has @p4runtime_translation.
    // This creates a PortTranslator with an empty translation table, so
    // dataplaneToP4rt(510) returns null and p4rt_egress_port is omitted.
    val config =
      baseConfig
        .toBuilder()
        .setDevice(
          baseConfig.device
            .toBuilder()
            .setBehavioral(
              baseConfig.device.behavioral
                .toBuilder()
                .setArchitecture(
                  baseConfig.device.behavioral.architecture.toBuilder().setPortTypeName("port_id_t")
                )
            )
        )
        .setP4Info(
          baseConfig.p4Info
            .toBuilder()
            .setTypeInfo(
              P4Types.P4TypeInfo.newBuilder()
                .putNewTypes(
                  "port_id_t",
                  P4Types.P4NewTypeSpec.newBuilder()
                    .setTranslatedType(
                      P4Types.P4NewTypeTranslation.newBuilder()
                        .setUri("")
                        .setSdnString(P4Types.P4NewTypeTranslation.SdnString.getDefaultInstance())
                    )
                    .build(),
                )
            )
        )
        .build()

    harness.loadPipeline(config)
    val payload = buildEthernetFrame(etherType = 0x0800)
    val response = harness.injectPacket(ingressPort = 0, payload = payload)

    val output = response.possibleOutcomesList.single().getPackets(0)
    assertEquals("should egress on CPU port", 510, output.dataplaneEgressPort)
    assertTrue(
      "p4rt_egress_port should be empty (no mapping for CPU port)",
      output.p4RtEgressPort.isEmpty,
    )
    assertTrue("should have packet_in enrichment", output.hasPacketIn())
  }

  @Test
  @Suppress("MagicNumber")
  fun `SubscribeResults survives unmapped egress port`() = runBlocking {
    val baseConfig =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      @controller_header("packet_out")
      header packet_out_t { bit<9> egress_port; }

      @controller_header("packet_in")
      header packet_in_t { bit<9> ingress_port; bit<9> target_egress_port; }

      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { packet_out_t pkt_out; packet_in_t pkt_in; ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply { sm.egress_spec = 510; }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply {
          h.pkt_in.setValid();
          h.pkt_in.ingress_port = sm.ingress_port;
          h.pkt_in.target_egress_port = sm.egress_port;
        }
      }
      control D(packet_out pkt, in headers_t h) {
        apply { pkt.emit(h.pkt_in); pkt.emit(h.eth); }
      }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    val config =
      baseConfig
        .toBuilder()
        .setDevice(
          baseConfig.device
            .toBuilder()
            .setBehavioral(
              baseConfig.device.behavioral
                .toBuilder()
                .setArchitecture(
                  baseConfig.device.behavioral.architecture.toBuilder().setPortTypeName("port_id_t")
                )
            )
        )
        .setP4Info(
          baseConfig.p4Info
            .toBuilder()
            .setTypeInfo(
              P4Types.P4TypeInfo.newBuilder()
                .putNewTypes(
                  "port_id_t",
                  P4Types.P4NewTypeSpec.newBuilder()
                    .setTranslatedType(
                      P4Types.P4NewTypeTranslation.newBuilder()
                        .setUri("")
                        .setSdnString(P4Types.P4NewTypeTranslation.SdnString.getDefaultInstance())
                    )
                    .build(),
                )
            )
        )
        .build()

    harness.loadPipeline(config)
    val stub = DataplaneCoroutineStub(harness.channel)
    val (job, results) = subscribeAndAwaitActive(stub)

    harness.injectPacket(ingressPort = 0, payload = buildEthernetFrame(etherType = 0x0800))

    val msg = withTimeout(5000) { results.receive() }
    assertTrue("should be a result", msg.hasResult())
    val output = msg.result.possibleOutcomesList.single().getPackets(0)
    assertEquals("should egress on CPU port", 510, output.dataplaneEgressPort)
    assertTrue("p4rt_egress_port should be empty", output.p4RtEgressPort.isEmpty)
    assertTrue("should have packet_in enrichment", output.hasPacketIn())

    job.cancel()
  }

  // =========================================================================
  // PacketIn enrichment
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `CPU-port output has PacketIn enrichment without translation`() {
    // Vanilla v1model with @controller_header("packet_in") but no @p4runtime_translation.
    // The program always forwards to CPU port 510.
    val config =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      @controller_header("packet_out")
      header packet_out_t { bit<9> egress_port; }

      @controller_header("packet_in")
      header packet_in_t { bit<9> ingress_port; bit<9> target_egress_port; }

      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { packet_out_t pkt_out; packet_in_t pkt_in; ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply { sm.egress_spec = 510; }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply {
          h.pkt_in.setValid();
          h.pkt_in.ingress_port = sm.ingress_port;
          h.pkt_in.target_egress_port = sm.egress_port;
        }
      }
      control D(packet_out pkt, in headers_t h) {
        apply { pkt.emit(h.pkt_in); pkt.emit(h.eth); }
      }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    val testHarness = FourwardTestHarness()
    testHarness.use {
      it.loadPipeline(config)
      val payload = buildEthernetFrame(etherType = 0x0800)
      val response = it.injectPacket(ingressPort = 7, payload = payload)

      val output = response.possibleOutcomesList.single().getPackets(0)
      assertEquals("should egress on CPU port", 510, output.dataplaneEgressPort)
      assertTrue("should have packet_in", output.hasPacketIn())

      val packetIn = output.packetIn
      assertEquals(
        "PacketIn payload should match original (controller header stripped)",
        payload.size,
        packetIn.payload.size(),
      )
      assertEquals(
        "PacketIn payload bytes should match",
        ByteString.copyFrom(payload),
        packetIn.payload,
      )
      assertTrue("PacketIn should have metadata", packetIn.metadataCount > 0)
      assertTrue("p4rt_egress_port should be empty (no translation)", output.p4RtEgressPort.isEmpty)
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `PacketIn enrichment preserves payload with non-byte-aligned controller header`() {
    // 5-bit @controller_header("packet_in") — not byte-aligned. Stripping must use bit-level
    // extraction, not byte slicing, to avoid shifting the payload or introducing pad bits.
    val config =
      compileInlineP4(
        """
      #include <core.p4>
      #include <v1model.p4>

      @controller_header("packet_out")
      header packet_out_t { bit<5> tag; }

      @controller_header("packet_in")
      header packet_in_t { bit<5> tag; }

      header ethernet_t { bit<48> dst; bit<48> src; bit<16> etype; }
      struct headers_t { packet_out_t pkt_out; packet_in_t pkt_in; ethernet_t eth; }
      struct meta_t {}

      parser P(packet_in pkt, out headers_t hdr, inout meta_t m, inout standard_metadata_t sm) {
        state start { pkt.extract(hdr.eth); transition accept; }
      }
      control VC(inout headers_t h, inout meta_t m) { apply {} }
      control CC(inout headers_t h, inout meta_t m) { apply {} }
      control Ig(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply { sm.egress_spec = 510; }
      }
      control Eg(inout headers_t h, inout meta_t m, inout standard_metadata_t sm) {
        apply { h.pkt_in.setValid(); h.pkt_in.tag = 0; }
      }
      control D(packet_out pkt, in headers_t h) {
        apply { pkt.emit(h.pkt_in); pkt.emit(h.eth); }
      }
      V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
      """
      )

    val testHarness = FourwardTestHarness()
    testHarness.use {
      it.loadPipeline(config)
      val payload = buildEthernetFrame(etherType = 0x0806)
      val response = it.injectPacket(ingressPort = 0, payload = payload)

      val output = response.possibleOutcomesList.single().getPackets(0)
      assertEquals("should egress on CPU port", 510, output.dataplaneEgressPort)
      assertTrue("should have packet_in", output.hasPacketIn())

      assertEquals(
        "PacketIn payload size should match original (no padding, no missing bits)",
        payload.size,
        output.packetIn.payload.size(),
      )
      assertEquals(
        "PacketIn payload should be byte-for-byte identical to original",
        ByteString.copyFrom(payload),
        output.packetIn.payload,
      )
    }
  }

  // =========================================================================
  // Pre-packet hook
  // =========================================================================

  @Test
  fun `RegisterPrePacketHook first message is HookRegistered`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val dataplaneStub = DataplaneCoroutineStub(harness.channel)
    val responseChannel = Channel<PrePacketHookResponse>(UNLIMITED)
    val first = dataplaneStub.registerPrePacketHook(responseChannel.consumeAsFlow()).first()
    assertTrue("first message should be HookRegistered", first.hasRegistered())
  }

  @Test
  fun `RegisterPrePacketHook rejects second registration with ALREADY_EXISTS`() = runBlocking {
    // Pins the invariant that the sentinel send() must come *after* the
    // broker.registerHook() success check — otherwise a duplicate registrant
    // would receive a HookRegistered before being told ALREADY_EXISTS.
    harness.loadPipeline(loadPassthroughConfig())
    val dataplaneStub = DataplaneCoroutineStub(harness.channel)
    val (firstHook, _) = registerHookAndAwaitReady(dataplaneStub)

    assertGrpcError(Status.Code.ALREADY_EXISTS, "already registered") {
      val secondResponses = Channel<PrePacketHookResponse>(UNLIMITED)
      runBlocking { dataplaneStub.registerPrePacketHook(secondResponses.consumeAsFlow()).first() }
    }

    firstHook.cancel()
  }

  @Test
  fun `pre-packet hook fires before InjectPacket`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val dataplaneStub = DataplaneCoroutineStub(harness.channel)
    val (hookJob, packetEvents) = registerHookAndAwaitReady(dataplaneStub)

    val packet = buildEthernetFrame(42)
    dataplaneStub.injectPacket(
      InjectPacketRequest.newBuilder()
        .setDataplaneIngressPort(0)
        .setPayload(ByteString.copyFrom(packet))
        .build()
    )

    val event = withTimeout(5000) { packetEvents.receive() }
    assertTrue("entities should be empty before any writes", event.entitiesList.isEmpty())

    hookJob.cancel()
  }

  @Test
  fun `pre-packet hook invocation carries installed entities`() = runBlocking {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))

    val dataplaneStub = DataplaneCoroutineStub(harness.channel)
    val (hookJob, packetEvents) = registerHookAndAwaitReady(dataplaneStub)

    val packet = buildEthernetFrame(42)
    dataplaneStub.injectPacket(
      InjectPacketRequest.newBuilder()
        .setDataplaneIngressPort(0)
        .setPayload(ByteString.copyFrom(packet))
        .build()
    )

    val event = withTimeout(5000) { packetEvents.receive() }
    assertTrue(
      "hook invocation should carry the installed table entry",
      event.entitiesList.any { it.hasTableEntry() },
    )

    hookJob.cancel()
  }

  @Test
  fun `pre-packet hook fires before PacketOut`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val dataplaneStub = DataplaneCoroutineStub(harness.channel)
    val (hookJob, packetEvents) = registerHookAndAwaitReady(dataplaneStub)

    val stream = harness.openStream()
    stream.arbitrate()
    val packet = buildEthernetFrame(42)
    stream.sendPacket(packet, ingressPort = 0, timeoutMs = 100)

    withTimeout(5000) { packetEvents.receive() }

    hookJob.cancel()
    stream.close()
  }

  /**
   * Registers a pre-packet hook and suspends until the server confirms registration via the
   * `registered` sentinel on [PrePacketHookInvocation]. After this returns, packets emitted by the
   * test are guaranteed to flow through the hook — no timing-based guesses needed. The returned
   * channel yields packet events only; the registration sentinel is consumed here.
   */
  private suspend fun CoroutineScope.registerHookAndAwaitReady(
    dataplaneStub: DataplaneCoroutineStub
  ): Pair<Job, Channel<PrePacketHookInvocation.PacketEvent>> {
    val packetEvents = Channel<PrePacketHookInvocation.PacketEvent>(10)
    val responseChannel = Channel<PrePacketHookResponse>(UNLIMITED)
    val ready = CompletableDeferred<Unit>()

    val hookJob =
      launch(Dispatchers.IO) {
        dataplaneStub.registerPrePacketHook(responseChannel.consumeAsFlow()).collect { invocation ->
          when (invocation.eventCase) {
            PrePacketHookInvocation.EventCase.REGISTERED -> {
              ready.complete(Unit)
            }
            PrePacketHookInvocation.EventCase.PACKET -> {
              packetEvents.send(invocation.packet)
              responseChannel.send(PrePacketHookResponse.getDefaultInstance())
            }
            PrePacketHookInvocation.EventCase.EVENT_NOT_SET ->
              fail("server emitted PrePacketHookInvocation with no event set")
          }
        }
      }

    withTimeout(5000) { ready.await() }
    return hookJob to packetEvents
  }

  /**
   * Subscribes to `subscribeResults` and consumes the [SubscriptionActive] sentinel that the server
   * emits as the first message. After this returns, packets injected by the test are guaranteed to
   * be observed by the subscriber — no timing-based guesses needed. The returned channel yields the
   * post-sentinel events.
   */
  private suspend fun CoroutineScope.subscribeAndAwaitActive(
    stub: DataplaneCoroutineStub
  ): Pair<Job, Channel<SubscribeResultsResponse>> {
    val results = Channel<SubscribeResultsResponse>(UNLIMITED)
    val job = launch {
      stub.subscribeResults(SubscribeResultsRequest.getDefaultInstance()).collect {
        results.send(it)
      }
    }
    val first = withTimeout(5000) { results.receive() }
    assertTrue("first message should be SubscriptionActive", first.hasActive())
    return job to results
  }
}
