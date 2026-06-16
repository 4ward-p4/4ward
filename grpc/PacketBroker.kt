package fourward.grpc

import fourward.OutputPacket
import fourward.PrePacketHookInvocation
import fourward.PrePacketHookResponse
import fourward.TraceTree
import fourward.simulator.DataplanePort
import fourward.simulator.PacketBits
import fourward.simulator.ProcessPacketResult
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import p4.v1.P4RuntimeOuterClass

/**
 * Fan-out layer between packet sources and the simulator.
 *
 * All callers — [DataplaneService.injectPacket], [P4RuntimeService] PacketOut — go through
 * [processPacket]. The broker:
 * 1. Fires the pre-packet hook if one is registered (acquiring the write mutex).
 * 2. Calls the simulator (lock-free — reads the published forwarding snapshot).
 * 3. Delivers the result to all subscribers.
 *
 * The hook ensures auxiliary entries (PRE clone sessions, etc.) are installed before every packet,
 * regardless of injection path.
 *
 * @param simulatorFn processes a single packet (wraps
 *   [fourward.simulator.Simulator.processPacket]).
 * @param writeMutex shared mutex with [P4RuntimeService] for serializing control-plane writes. Only
 *   acquired when a pre-packet hook is registered (to apply hook updates atomically).
 */
class PacketBroker(
  private val simulatorFn: (ingressPort: DataplanePort, packet: PacketBits) -> ProcessPacketResult,
  private val writeMutex: Mutex,
) {

  // ---------------------------------------------------------------------------
  // Pre-packet hook
  // ---------------------------------------------------------------------------

  /**
   * A registered hook: a pair of channels for server→client invocations and client→server
   * responses.
   */
  data class Hook(
    val invocations: Channel<PrePacketHookInvocation>,
    val responses: Channel<PrePacketHookResponse>,
  )

  private val hook = AtomicReference<Hook?>(null)

  /**
   * Atomically registers a hook. Returns true if successful, false if a hook is already registered.
   */
  fun registerHook(newHook: Hook): Boolean = hook.compareAndSet(null, newHook)

  /** Deregisters the current hook. */
  fun deregisterHook() {
    hook.set(null)
  }

  /**
   * Lambdas for building hook invocations and applying hook responses. Set by [FourwardServer]
   * after construction (avoids circular dependency with [P4RuntimeService]).
   */
  var readAllEntities: () -> List<P4RuntimeOuterClass.Entity> = { emptyList() }
  var readP4Info: () -> p4.config.v1.P4InfoOuterClass.P4Info? = { null }
  var applyUpdates: (List<P4RuntimeOuterClass.Update>) -> Unit = {}

  /**
   * Fires the pre-packet hook if one is registered. Must be called while holding the [writeMutex].
   * The hook may apply P4Runtime updates (via [applyUpdates]) which mutate forwarding state and
   * publish a new snapshot — all under the mutex.
   */
  private suspend fun fireHookIfRegistered() {
    val h = hook.get() ?: return
    val invocation =
      PrePacketHookInvocation.newBuilder()
        .setPacket(
          PrePacketHookInvocation.PacketEvent.newBuilder().addAllEntities(readAllEntities()).apply {
            readP4Info()?.let { setP4Info(it) }
          }
        )
        .build()
    h.invocations.send(invocation)
    val response = h.responses.receive()
    if (response.updatesList.isNotEmpty()) {
      applyUpdates(response.updatesList)
    }
  }

  // ---------------------------------------------------------------------------
  // Packet processing
  // ---------------------------------------------------------------------------

  /** Delivered to each [subscribe] subscriber for every processed packet. */
  class SubscriptionResult(
    val ingressPort: DataplanePort,
    val packet: PacketBits,
    val possibleOutcomes: List<List<OutputPacket>>,
    val trace: TraceTree,
    val tag: Long = 0,
  )

  /** Handle returned by [subscribe]; call [unsubscribe] to stop receiving results. */
  fun interface SubscriptionHandle {
    fun unsubscribe()
  }

  private val subscribers =
    java.util.concurrent.CopyOnWriteArrayList<suspend (SubscriptionResult) -> Unit>()

  /**
   * If a hook is registered, acquires the [writeMutex] and fires it. The hook may apply P4Runtime
   * updates that publish a new forwarding snapshot. No-op if no hook is registered.
   */
  private suspend fun fireHookUnderMutex() {
    if (hook.get() != null) {
      writeMutex.withLock { fireHookIfRegistered() }
    }
  }

  suspend fun processPacket(
    ingressPort: DataplanePort,
    payload: ByteArray,
    tag: Long = 0,
  ): ProcessPacketResult = processPacket(ingressPort, PacketBits.ofBytes(payload), tag)

  /**
   * Processes a packet: fires the hook (if registered), runs the simulator, and dispatches results
   * to subscribers. Lock-free on the hot path — the simulator reads from the published forwarding
   * snapshot. Only acquires the [writeMutex] when a hook is registered (to fire the hook and apply
   * its updates atomically).
   */
  suspend fun processPacket(
    ingressPort: DataplanePort,
    packet: PacketBits,
    tag: Long = 0,
  ): ProcessPacketResult {
    fireHookUnderMutex()
    val result = simulatorFn(ingressPort, packet)
    dispatchToSubscribers(ingressPort, packet, result, tag)
    return result
  }

  /**
   * Fires the hook once (if registered), then runs [block] with a lock-free packet processor.
   *
   * Used by [DataplaneService.injectPackets] to stream packets without buffering the entire batch.
   */
  suspend fun <T> withHookOnce(
    block: suspend (processor: suspend (DataplanePort, ByteArray, Long) -> Unit) -> T
  ): T {
    fireHookUnderMutex()
    return block { port, payload, tag ->
      val packet = PacketBits.ofBytes(payload)
      val result = simulatorFn(port, packet)
      dispatchToSubscribers(port, packet, result, tag)
    }
  }

  /**
   * Registers a subscriber that receives results for every processed packet.
   *
   * Subscriber delivery is lossless and backpressured: packet processing waits for each subscriber
   * callback to return. Keep subscriber callbacks lightweight or hand work off to a bounded queue
   * if they need to do slow I/O.
   */
  fun subscribe(callback: suspend (SubscriptionResult) -> Unit): SubscriptionHandle {
    subscribers.add(callback)
    return SubscriptionHandle { subscribers.remove(callback) }
  }

  private suspend fun dispatchToSubscribers(
    ingressPort: DataplanePort,
    packet: PacketBits,
    result: ProcessPacketResult,
    tag: Long = 0,
  ) {
    if (subscribers.isEmpty()) return
    val subResult =
      SubscriptionResult(ingressPort, packet, result.possibleOutcomes, result.trace, tag)
    for (subscriber in subscribers) {
      try {
        subscriber(subResult)
      } catch (
        @Suppress("TooGenericExceptionCaught") // Safety net: any subscriber failure must not
        e: Exception // crash the sender or block other subscribers.
      ) {
        logger.log(Level.WARNING, "Subscriber threw during packet dispatch", e)
      }
    }
  }

  private companion object {
    val logger: Logger = Logger.getLogger(PacketBroker::class.java.name)
  }
}
