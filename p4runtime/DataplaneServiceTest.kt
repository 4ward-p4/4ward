package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.dataplane.DataplaneGrpcKt.DataplaneCoroutineStub
import fourward.dataplane.InjectPacketRequest
import fourward.dataplane.PrePacketHookInvocation
import fourward.dataplane.PrePacketHookResponse
import fourward.dataplane.SubscribeResultsRequest
import fourward.dataplane.SubscribeResultsResponse
import fourward.p4runtime.P4RuntimeTestHarness.Companion.assertGrpcError
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildEthernetFrame
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildExactEntry
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
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

class DataplaneServiceTest {

  private lateinit var harness: P4RuntimeTestHarness

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
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
        timeoutMs = P4RuntimeTestHarness.NO_RESPONSE_TIMEOUT_MS,
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
  // Subscriber-falls-behind close (regression for the silent-drop bug)
  // =========================================================================

  @Test
  fun `SubscribeResults flow closes when subscriber falls behind`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)

    val collected = java.util.concurrent.atomic.AtomicInteger(0)
    val ready = CompletableDeferred<Unit>()
    val collectJob =
      launch(Dispatchers.IO) {
        // Any exception is expected here — the stream closes abruptly on overflow.
        @Suppress("TooGenericExceptionCaught")
        try {
          stub.subscribeResults(SubscribeResultsRequest.getDefaultInstance()).collect {
            if (it.hasActive()) {
              ready.complete(Unit)
            } else {
              collected.incrementAndGet()
              delay(50) // slow consumer: forces the buffer to fill on bursty input
            }
          }
        } catch (_: Exception) {}
      }

    withTimeout(5000) { ready.await() }

    // 200 packets is well above the typical callbackFlow buffer (~64).
    repeat(200) { i -> harness.injectPacket(ingressPort = 0, payload = byteArrayOf(i.toByte())) }

    withTimeout(20_000) { collectJob.join() }

    assertTrue("collector should have received at least some items", collected.get() > 0)
    assertTrue(
      "flow should close before all 200 items delivered " +
        "(overflow surfaced); got ${collected.get()}",
      collected.get() < 200,
    )
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
