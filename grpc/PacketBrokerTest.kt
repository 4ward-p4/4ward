package fourward.grpc

import fourward.OutputPacket
import fourward.PrePacketHookInvocation
import fourward.PrePacketHookResponse
import fourward.TraceTree
import fourward.simulator.ProcessPacketResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketBrokerTest {

  private fun outputPacket(egressPort: Int, payload: ByteArray = byteArrayOf()) =
    OutputPacket.newBuilder()
      .setDataplaneEgressPort(egressPort)
      .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
      .build()

  private fun result(vararg outputs: OutputPacket) =
    ProcessPacketResult(
      TraceTree.getDefaultInstance(),
      listOf(outputs.toList()),
      fourward.simulator.TableStore().snapshot,
    )

  private fun fakeProcessor(
    vararg results: Pair<Int, ProcessPacketResult>
  ): (Int, ByteArray, Int) -> ProcessPacketResult {
    val map = results.toMap()
    return { port, _, _ -> map[port] ?: result() }
  }

  private fun broker(vararg results: Pair<Int, ProcessPacketResult>) =
    PacketBroker(fakeProcessor(*results), kotlinx.coroutines.sync.Mutex())

  @Test
  fun `processPacket returns outputs and trace`() {
    val expected = result(outputPacket(1, byteArrayOf(0xAB.toByte())))
    val broker = broker(0 to expected)

    val actual = broker.processPacket(0, byteArrayOf(0x01))
    assertEquals(expected.possibleOutcomes, actual.possibleOutcomes)
  }

  @Test
  fun `processPacket forwards explicit payload bit length`() {
    var capturedBitLength = -1
    val broker =
      PacketBroker(
        { _, _, payloadBitLength ->
          capturedBitLength = payloadBitLength
          result()
        },
        kotlinx.coroutines.sync.Mutex(),
      )

    broker.processPacket(0, byteArrayOf(0xA0.toByte()), payloadBitLength = 4)

    assertEquals(4, capturedBitLength)
  }

  @Test
  fun `subscriber receives result with input and outputs`() {
    val outputs = listOf(outputPacket(1))
    val broker = broker(0 to result(outputPacket(1)))

    val received = mutableListOf<PacketBroker.SubscriptionResult>()
    broker.subscribe { received.add(it) }

    broker.processPacket(0, byteArrayOf(0x01))

    assertEquals(1, received.size)
    assertEquals(0, received[0].ingressPort)
    assertEquals(listOf(outputs), received[0].possibleOutcomes)
  }

  @Test
  fun `multiple subscribers each receive results`() {
    val broker = broker(0 to result(outputPacket(1)))

    val received1 = mutableListOf<PacketBroker.SubscriptionResult>()
    val received2 = mutableListOf<PacketBroker.SubscriptionResult>()
    broker.subscribe { received1.add(it) }
    broker.subscribe { received2.add(it) }

    broker.processPacket(0, byteArrayOf())

    assertEquals(1, received1.size)
    assertEquals(1, received2.size)
  }

  @Test
  fun `no subscribers does not cause errors`() {
    val broker = broker(0 to result(outputPacket(1)))
    broker.processPacket(0, byteArrayOf())
  }

  @Test
  fun `unsubscribe stops delivery`() {
    val broker = broker(0 to result(outputPacket(1)))

    val received = mutableListOf<PacketBroker.SubscriptionResult>()
    val handle = broker.subscribe { received.add(it) }

    broker.processPacket(0, byteArrayOf())
    assertEquals(1, received.size)

    handle.unsubscribe()
    broker.processPacket(0, byteArrayOf())
    assertEquals("no new result after unsubscribe", 1, received.size)
  }

  @Test
  fun `subscriber receives tag from processPacket`() {
    val broker = broker(0 to result(outputPacket(1)))
    val received = mutableListOf<PacketBroker.SubscriptionResult>()
    broker.subscribe { received.add(it) }

    broker.processPacket(0, byteArrayOf(), tag = 42)

    assertEquals(1, received.size)
    assertEquals(42L, received[0].tag)
  }

  @Test
  fun `throwing subscriber does not crash caller or block other subscribers`() {
    val broker = broker(0 to result(outputPacket(1)))

    val received = mutableListOf<PacketBroker.SubscriptionResult>()
    broker.subscribe { throw IllegalStateException("boom") }
    broker.subscribe { received.add(it) }

    // The caller should get the result even though the first subscriber threw.
    val callerResult = broker.processPacket(0, byteArrayOf())
    assertEquals(1, callerResult.possibleOutcomes.single().size)

    // The second subscriber should still receive the result.
    assertEquals(1, received.size)
  }

  // ---------------------------------------------------------------------------
  // Hook tests
  // ---------------------------------------------------------------------------

  /**
   * Registers a hook on the broker that auto-responds with empty updates. Returns the number of
   * times the hook was invoked.
   */
  private fun registerAutoRespondHook(broker: PacketBroker): AtomicInteger {
    val hookCount = AtomicInteger(0)
    val invocations = Channel<PrePacketHookInvocation>(Channel.UNLIMITED)
    val responses = Channel<PrePacketHookResponse>(Channel.UNLIMITED)
    assertTrue(broker.registerHook(PacketBroker.Hook(invocations, responses)))

    // Background: drain invocations and auto-respond.
    Thread {
        runBlocking {
          // Consume each invocation from the channel; the value
          // itself is unused — only the side effect matters.
          @Suppress("UnusedPrivateProperty")
          for (invocation in invocations) {
            hookCount.incrementAndGet()
            responses.send(PrePacketHookResponse.getDefaultInstance())
          }
        }
      }
      .apply {
        isDaemon = true
        start()
      }

    return hookCount
  }

  @Test
  fun `processPacket fires hook when registered`() {
    val broker = broker(0 to result(outputPacket(1)))
    val hookCount = registerAutoRespondHook(broker)

    broker.processPacket(0, byteArrayOf())

    assertEquals("hook should fire once per processPacket", 1, hookCount.get())
  }

  @Test
  fun `processPacket fires hook on every call`() {
    val broker = broker(0 to result(outputPacket(1)))
    val hookCount = registerAutoRespondHook(broker)

    broker.processPacket(0, byteArrayOf())
    broker.processPacket(0, byteArrayOf())
    broker.processPacket(0, byteArrayOf())

    assertEquals("hook should fire once per processPacket", 3, hookCount.get())
  }

  @Test
  fun `processPacket works without hook`() {
    val expected = result(outputPacket(1))
    val broker = broker(0 to expected)

    // No hook registered — should still work.
    val actual = broker.processPacket(0, byteArrayOf())
    assertEquals(expected.possibleOutcomes, actual.possibleOutcomes)
  }

  @Test
  fun `withHookOnce fires hook exactly once for multiple packets`() {
    val broker = broker(0 to result(outputPacket(1)))
    val hookCount = registerAutoRespondHook(broker)

    broker.withHookOnce { processPacket ->
      processPacket(0, byteArrayOf(), 0)
      processPacket(0, byteArrayOf(), 0)
      processPacket(0, byteArrayOf(), 0)
    }

    assertEquals("hook should fire exactly once for the batch", 1, hookCount.get())
  }

  @Test
  fun `withHookOnce without hook does not fire hook`() {
    val broker = broker(0 to result(outputPacket(1)))
    // No hook registered.
    val processed = AtomicInteger(0)

    broker.withHookOnce { processPacket ->
      processPacket(0, byteArrayOf(), 0)
      processed.incrementAndGet()
    }

    assertEquals(1, processed.get())
  }

  @Test
  fun `registerHook fails if hook already registered`() {
    val broker = broker(0 to result())
    val hook1 = PacketBroker.Hook(Channel(Channel.UNLIMITED), Channel(Channel.UNLIMITED))
    val hook2 = PacketBroker.Hook(Channel(Channel.UNLIMITED), Channel(Channel.UNLIMITED))

    assertTrue("first registration should succeed", broker.registerHook(hook1))
    assertFalse("second registration should fail", broker.registerHook(hook2))
  }

  @Test
  fun `deregisterHook allows re-registration`() {
    val broker = broker(0 to result())
    val hook1 = PacketBroker.Hook(Channel(Channel.UNLIMITED), Channel(Channel.UNLIMITED))
    val hook2 = PacketBroker.Hook(Channel(Channel.UNLIMITED), Channel(Channel.UNLIMITED))

    assertTrue(broker.registerHook(hook1))
    broker.deregisterHook()
    assertTrue("should succeed after deregister", broker.registerHook(hook2))
  }

  @Test
  fun `hook applyUpdates is called when response has updates`() {
    val broker = broker(0 to result(outputPacket(1)))
    val appliedUpdates = mutableListOf<p4.v1.P4RuntimeOuterClass.Update>()
    broker.applyUpdates = { updates -> appliedUpdates.addAll(updates) }

    val invocations = Channel<PrePacketHookInvocation>(Channel.UNLIMITED)
    val responses = Channel<PrePacketHookResponse>(Channel.UNLIMITED)
    broker.registerHook(PacketBroker.Hook(invocations, responses))

    // Respond with one update.
    val update =
      p4.v1.P4RuntimeOuterClass.Update.newBuilder()
        .setType(p4.v1.P4RuntimeOuterClass.Update.Type.INSERT)
        .build()
    Thread {
        runBlocking {
          invocations.receive()
          responses.send(PrePacketHookResponse.newBuilder().addUpdates(update).build())
        }
      }
      .apply {
        isDaemon = true
        start()
      }

    broker.processPacket(0, byteArrayOf())

    assertEquals("applyUpdates should receive the update", 1, appliedUpdates.size)
    assertEquals(p4.v1.P4RuntimeOuterClass.Update.Type.INSERT, appliedUpdates[0].type)
  }
}
