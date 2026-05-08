package fourward.simulator

import com.google.protobuf.ByteString
import fourward.TraceTree

/**
 * A network of P4 switches connected by point-to-point links.
 *
 * Composes N [Simulator] instances with a [NetworkTopology], forwarding packets across links using
 * the existing [Simulator.processPacket] API. Individual simulators are unchanged — the network
 * layer is purely additive.
 *
 * Packet delivery is instant. A [MAX_HOP_COUNT] limit prevents infinite loops from routing
 * misconfigurations.
 *
 * **Concurrency:** [addSwitch] is single-writer (callers must serialize against other [addSwitch]
 * calls and against [processPacket]). [processPacket] is safe to call concurrently from any number
 * of threads once topology setup is complete, inheriting [Simulator.processPacket]'s concurrency
 * contract. The switch map uses [java.util.concurrent.ConcurrentHashMap] so that the read path is
 * JMM-safe even if a future refactor relaxes the setup ordering.
 */
class NetworkSimulator(topology: NetworkTopology) {

  private val simulators = java.util.concurrent.ConcurrentHashMap<String, Simulator>()

  /** Bidirectional link lookup: endpoint → endpoint. */
  private val linkMap: Map<Endpoint, Endpoint> = buildMap {
    for (link in topology.links) {
      require(link.a !in this) { "port ${link.a} is connected to multiple links" }
      require(link.b !in this) { "port ${link.b} is connected to multiple links" }
      put(link.a, link.b)
      put(link.b, link.a)
    }
  }

  /**
   * Adds a switch to the network and returns its [Simulator] for configuration (loading pipelines,
   * installing table entries).
   */
  fun addSwitch(id: String): Simulator {
    val sim = Simulator()
    require(simulators.putIfAbsent(id, sim) == null) { "switch '$id' already exists" }
    return sim
  }

  /**
   * Processes a packet through the network. Returns a [NetworkHop] tree capturing the full
   * cross-switch journey.
   */
  fun processPacket(switchId: String, ingressPort: Int, payload: ByteArray): NetworkHop {
    return processHop(switchId, ingressPort, ByteString.copyFrom(payload), hopCount = 0)
  }

  private fun processHop(
    switchId: String,
    ingressPort: Int,
    payload: ByteString,
    hopCount: Int,
  ): NetworkHop {
    check(hopCount < MAX_HOP_COUNT) {
      "switch '$switchId': hop limit ($MAX_HOP_COUNT) exceeded — routing loop?"
    }

    val sim = checkNotNull(simulators[switchId]) { "unknown switch: '$switchId'" }
    val result = sim.processPacket(ingressPort, payload.toByteArray())

    // TODO: handle alternative forks (action selectors) across switches.
    // See docs/LIMITATIONS.md, "Network simulation".
    check(result.possibleOutcomes.size <= 1) {
      "switch '$switchId': multiple possible outcomes (action selectors) not yet supported"
    }
    val outputs = result.possibleOutcomes.firstOrNull() ?: emptyList()

    val edgeOutputs = mutableListOf<EdgeOutput>()
    val nextHops = mutableListOf<NetworkHop>()

    for (output in outputs) {
      val dst = linkMap[Endpoint(switchId, output.dataplaneEgressPort)]
      if (dst != null) {
        nextHops.add(processHop(dst.switchId, dst.port, output.payload, hopCount + 1))
      } else {
        edgeOutputs.add(EdgeOutput(switchId, output.dataplaneEgressPort, output.payload))
      }
    }

    return NetworkHop(
      switchId = switchId,
      ingressPort = ingressPort,
      ingressPayload = payload,
      trace = result.trace,
      edgeOutputs = edgeOutputs,
      nextHops = nextHops,
    )
  }

  companion object {
    const val MAX_HOP_COUNT = 64
  }
}

/** A switch port: (switch ID, port number). */
data class Endpoint(val switchId: String, val port: Int) {
  override fun toString(): String = "$switchId:$port"
}

/** A point-to-point link between two switch ports. Bidirectional. */
data class Link(val a: Endpoint, val b: Endpoint)

/** A set of switches connected by point-to-point links. */
data class NetworkTopology(val links: List<Link>)

/** A packet that exited the network on an edge port (a port not connected to any link). */
data class EdgeOutput(val switchId: String, val egressPort: Int, val payload: ByteString)

/**
 * One switch processing one packet in the network trace tree.
 *
 * Each hop records what the switch received ([ingressPayload]), the full per-switch [trace] tree,
 * and the results: [edgeOutputs] for packets leaving the network, and [nextHops] for packets
 * forwarded to other switches via internal links. The tree branches when packets fan out
 * (multicast, clone) — one child hop per copy.
 */
data class NetworkHop(
  val switchId: String,
  val ingressPort: Int,
  val ingressPayload: ByteString,
  val trace: TraceTree,
  val edgeOutputs: List<EdgeOutput>,
  val nextHops: List<NetworkHop>,
)
