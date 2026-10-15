package fourward.grpc

import com.google.protobuf.ByteString
import fourward.AddLinksRequest
import fourward.AddLinksResponse
import fourward.HopLimitExceeded
import fourward.InjectNetworkPacketRequest
import fourward.InjectNetworkPacketResponse
import fourward.InjectPacketRequest
import fourward.LinkTraversal
import fourward.ListLinksRequest
import fourward.ListLinksResponse
import fourward.NetworkEgressPacket
import fourward.NetworkGrpcKt
import fourward.NetworkHop
import fourward.NetworkOutcome
import fourward.NetworkPort
import fourward.NetworkTrace
import fourward.OutputPacket
import fourward.RemoveLinksRequest
import fourward.RemoveLinksResponse
import fourward.TraceTree
import io.grpc.Status

class NetworkService(
  private val registry: DeviceRegistry,
  private val topology: NetworkTopology = NetworkTopology(registry),
) : NetworkGrpcKt.NetworkCoroutineImplBase() {

  override suspend fun addLinks(request: AddLinksRequest): AddLinksResponse {
    topology.addLinks(request.linksList)
    return AddLinksResponse.getDefaultInstance()
  }

  override suspend fun removeLinks(request: RemoveLinksRequest): RemoveLinksResponse {
    topology.removeLinks(request.linksList)
    return RemoveLinksResponse.getDefaultInstance()
  }

  override suspend fun listLinks(request: ListLinksRequest): ListLinksResponse =
    ListLinksResponse.newBuilder().addAllLinks(topology.listLinks()).build()

  override suspend fun injectPacket(
    request: InjectNetworkPacketRequest
  ): InjectNetworkPacketResponse {
    if (request.maxHops == 0) {
      throw Status.INVALID_ARGUMENT.withDescription("max_hops must be positive").asException()
    }
    val ingress = Endpoint.fromProto(request.ingress)
    val snapshot = topology.snapshot()
    val root = simulate(ingress, request.payload, request.maxHops, request.tag, snapshot)
    return InjectNetworkPacketResponse.newBuilder()
      .setTrace(NetworkTrace.newBuilder().setRoot(root.hop))
      .addAllPossibleOutcomes(
        root.possibleOutcomes.map { NetworkOutcome.newBuilder().addAllPackets(it).build() }
      )
      .build()
  }

  private suspend fun simulate(
    ingress: Endpoint,
    payload: ByteString,
    remainingHops: Int,
    tag: Long,
    topology: TopologySnapshot,
  ): SimulatedHop {
    val device = registry.get(ingress.deviceId)
    val packetResult =
      device.dataplaneService.processForNetwork(injectRequest(ingress, payload, tag))
    val trace = analyzeTrace(packetResult.trace)
    val hop =
      NetworkHop.newBuilder()
        .setDeviceId(ingress.deviceId)
        .setIngress(ingress.toProto())
        .setSwitchTrace(packetResult.trace)
    val outcomesByOutputId = mutableMapOf<Long, List<List<NetworkEgressPacket>>>()
    for (leaf in trace.leaves) {
      val result = traversal(ingress.deviceId, leaf, remainingHops, topology)
      hop.addTraversals(result.traversal)
      outcomesByOutputId[leaf.id] = result.possibleOutcomes
    }
    val possibleOutcomes =
      trace.outcomes.flatMap { outputIds ->
        combine(outputIds.map { id -> outcomesByOutputId.getValue(id) })
      }
    return SimulatedHop(hop.build(), possibleOutcomes.distinctBy { it.outcomeIdentity() })
  }

  private suspend fun traversal(
    deviceId: Long,
    leaf: OutputLeaf,
    remainingHops: Int,
    topology: TopologySnapshot,
  ): TraversalResult {
    val matches = matchingPeers(deviceId, leaf.output, topology)
    val builder = LinkTraversal.newBuilder().setSourceOutputId(leaf.id).setSourceOutput(leaf.output)
    return when (matches.size) {
      0 -> {
        val egress =
          NetworkEgressPacket.newBuilder()
            .setEgress(networkEgressPort(deviceId, leaf.output))
            .setPayload(leaf.output.payload)
            .build()
        TraversalResult(
          builder.setNetworkEgress(egress).build(),
          possibleOutcomes = listOf(listOf(egress)),
        )
      }
      1 -> {
        val next = matches.single()
        if (remainingHops <= 1) {
          TraversalResult(
            builder
              .setHopLimitExceeded(HopLimitExceeded.newBuilder().setNextIngress(next.toProto()))
              .build(),
            possibleOutcomes = listOf(emptyList()),
          )
        } else {
          val nextHop = simulate(next, leaf.output.payload, remainingHops - 1, 0, topology)
          TraversalResult(
            builder.setNextHop(nextHop.hop).build(),
            possibleOutcomes = nextHop.possibleOutcomes,
          )
        }
      }
      else -> {
        val details = matches.joinToString { it.describe() }
        throw Status.FAILED_PRECONDITION.withDescription(
            "output from device_id $deviceId matches multiple configured links: $details"
          )
          .asException()
      }
    }
  }

  private fun injectRequest(
    ingress: Endpoint,
    payload: ByteString,
    tag: Long,
  ): InjectPacketRequest {
    val builder = InjectPacketRequest.newBuilder().setPayload(payload).setTag(tag)
    when (val port = ingress.port) {
      is PortKey.Dataplane -> builder.setDataplaneIngressPort(port.value)
      is PortKey.P4Runtime -> builder.setP4RtIngressPort(port.value)
    }
    return builder.build()
  }

  private fun matchingPeers(
    deviceId: Long,
    output: OutputPacket,
    topology: TopologySnapshot,
  ): List<Endpoint> {
    val candidates = buildList {
      add(Endpoint(deviceId, PortKey.Dataplane(output.dataplaneEgressPort)))
      if (!output.p4RtEgressPort.isEmpty) {
        add(Endpoint(deviceId, PortKey.P4Runtime(output.p4RtEgressPort)))
      }
    }
    return candidates.mapNotNull(topology::peer)
  }

  private fun networkEgressPort(deviceId: Long, output: OutputPacket): NetworkPort =
    if (output.p4RtEgressPort.isEmpty) {
      Endpoint(deviceId, PortKey.Dataplane(output.dataplaneEgressPort)).toProto()
    } else {
      Endpoint(deviceId, PortKey.P4Runtime(output.p4RtEgressPort)).toProto()
    }
}

private data class SimulatedHop(
  val hop: NetworkHop,
  val possibleOutcomes: List<List<NetworkEgressPacket>>,
)

private data class TraversalResult(
  val traversal: LinkTraversal,
  val possibleOutcomes: List<List<NetworkEgressPacket>>,
)

private data class TraceAnalysis(val leaves: List<OutputLeaf>, val outcomes: List<List<Long>>)

private data class OutputLeaf(val id: Long, val output: OutputPacket)

private fun analyzeTrace(trace: TraceTree): TraceAnalysis {
  val leaves = mutableListOf<OutputLeaf>()
  var nextId = 1L
  fun collect(tree: TraceTree): List<List<Long>> =
    when (tree.outcomeCase) {
      TraceTree.OutcomeCase.OUTPUT -> {
        val id = nextId++
        leaves.add(OutputLeaf(id, tree.output))
        listOf(listOf(id))
      }
      TraceTree.OutcomeCase.DROP,
      TraceTree.OutcomeCase.OUTCOME_NOT_SET,
      null -> listOf(emptyList())
      TraceTree.OutcomeCase.CONTINUATION -> collect(tree.continuation.next)
      TraceTree.OutcomeCase.CHOICE -> tree.choice.branchesList.flatMap(::collect)
      TraceTree.OutcomeCase.REPLICATION ->
        combine(tree.replication.branchesList.map { branch -> collect(branch) })
    }
  return TraceAnalysis(leaves, collect(trace))
}

private fun <T> combine(parts: List<List<List<T>>>): List<List<T>> {
  var outcomes = listOf(emptyList<T>())
  for (part in parts) {
    outcomes = outcomes.flatMap { prefix -> part.map { suffix -> prefix + suffix } }
  }
  return outcomes
}

private fun List<NetworkEgressPacket>.outcomeIdentity(): Map<Pair<NetworkPort, ByteString>, Int> =
  groupingBy { it.egress to it.payload }.eachCount()
