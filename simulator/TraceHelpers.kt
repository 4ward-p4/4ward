package fourward.simulator

import fourward.Alternative
import fourward.Choice
import fourward.Continuation
import fourward.ContinuationKind
import fourward.Continuations
import fourward.Drop
import fourward.DropReason
import fourward.MulticastGroupLookupEvent
import fourward.PacketIngressEvent
import fourward.PacketOutcome
import fourward.PipelineStage
import fourward.PipelineStageEvent
import fourward.TraceEvent
import fourward.TraceTree

/** Builds a [TraceTree] representing a dropped packet with the given trace events and reason. */
internal fun buildDropTrace(
  events: List<TraceEvent>,
  reason: DropReason = DropReason.MARK_TO_DROP,
): TraceTree {
  val outcome = PacketOutcome.newBuilder().setDrop(Drop.newBuilder().setReason(reason)).build()
  return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
}

/** Builds a [TraceTree] representing a packet output on the given port. */
internal fun buildOutputTrace(events: List<TraceEvent>, port: Int, payload: ByteArray): TraceTree {
  val output =
    fourward.OutputPacket.newBuilder()
      .setDataplaneEgressPort(port)
      .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
      .build()
  val outcome = PacketOutcome.newBuilder().setOutput(output).build()
  return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
}

/** Creates a [TraceEvent] recording the packet's ingress port. */
internal fun packetIngressEvent(ingressPort: UInt): TraceEvent =
  TraceEvent.newBuilder()
    .setPacketIngress(PacketIngressEvent.newBuilder().setDataplaneIngressPort(ingressPort.toInt()))
    .build()

/** Creates a [TraceEvent] marking the entry/exit of a pipeline stage. */
internal fun stageEvent(
  stage: PipelineStage,
  direction: PipelineStageEvent.Direction,
  dataplanePort: Int? = null,
): TraceEvent =
  TraceEvent.newBuilder()
    .setPipelineStage(
      PipelineStageEvent.newBuilder()
        .setStageName(stage.name)
        .setStageKind(stage.kind)
        .setDirection(direction)
        .apply { if (dataplanePort != null) setDataplanePort(dataplanePort) }
    )
    .build()

/** Creates a [TraceEvent] recording a successful multicast group lookup. */
internal fun multicastGroupLookupEvent(groupId: Int, replicaCount: Int): TraceEvent =
  TraceEvent.newBuilder()
    .setMulticastGroupLookup(
      MulticastGroupLookupEvent.newBuilder()
        .setMulticastGroupId(groupId)
        .setGroupFound(true)
        .setReplicaCount(replicaCount)
    )
    .build()

/** Creates a [TraceEvent] recording a failed multicast group lookup. */
internal fun multicastGroupMissEvent(groupId: Int): TraceEvent =
  TraceEvent.newBuilder()
    .setMulticastGroupLookup(
      MulticastGroupLookupEvent.newBuilder().setMulticastGroupId(groupId).setGroupFound(false)
    )
    .build()

/**
 * Builds a [TraceTree] that ends a pipeline pass by continuing in all the given ways (AND-node).
 */
internal fun buildContinuationsTree(
  events: List<TraceEvent>,
  continuations: List<Continuation>,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setContinuations(Continuations.newBuilder().addAllContinuations(continuations))
    .build()

/** Builds a [TraceTree] that explores all alternatives of a non-deterministic choice (OR-node). */
internal fun buildChoiceTree(events: List<TraceEvent>, alternatives: List<Alternative>): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setChoice(Choice.newBuilder().addAllAlternatives(alternatives))
    .build()

/**
 * Creates a [Continuation] of [kind]. For copy kinds (clone, mirror, multicast replica),
 * [dataplaneEgressPort] and [instance] identify the replica.
 */
internal fun continuation(
  kind: ContinuationKind,
  subtree: TraceTree,
  dataplaneEgressPort: Int? = null,
  instance: Int? = null,
): Continuation =
  Continuation.newBuilder()
    .setKind(kind)
    .setSubtree(subtree)
    .apply {
      if (dataplaneEgressPort != null) setDataplaneEgressPort(dataplaneEgressPort)
      if (instance != null) setInstance(instance)
    }
    .build()

/** Copies the outcome of [from] into this builder; leaves it unset if [from] has none. */
internal fun TraceTree.Builder.copyOutcome(from: TraceTree): TraceTree.Builder = apply {
  when (from.outcomeCase) {
    TraceTree.OutcomeCase.PACKET_OUTCOME -> setPacketOutcome(from.packetOutcome)
    TraceTree.OutcomeCase.CONTINUATIONS -> setContinuations(from.continuations)
    TraceTree.OutcomeCase.CHOICE -> setChoice(from.choice)
    TraceTree.OutcomeCase.OUTCOME_NOT_SET,
    null -> {}
  }
}
