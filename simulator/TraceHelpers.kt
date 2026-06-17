package fourward.simulator

import fourward.Choice
import fourward.Continuation
import fourward.ContinuationEvent
import fourward.Drop
import fourward.MulticastGroupLookupEvent
import fourward.OutputPacket
import fourward.PacketIngressEvent
import fourward.PipelineStage
import fourward.PipelineStageEvent
import fourward.Replication
import fourward.TraceEvent
import fourward.TraceTree

/** Builds a [TraceTree] representing a dropped packet with the given trace events. */
internal fun buildDropTrace(events: List<TraceEvent>, triggerId: Long = 0L): TraceTree {
  val drop = Drop.newBuilder().also { if (triggerId != 0L) it.setTrigger(triggerId) }.build()
  return TraceTree.newBuilder().addAllEvents(events).setDrop(drop).build()
}

/** Builds a [TraceTree] representing a packet output on the given port. */
internal fun buildOutputTrace(events: List<TraceEvent>, port: Int, payload: ByteArray): TraceTree {
  val output =
    OutputPacket.newBuilder()
      .setDataplaneEgressPort(port)
      .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
      .build()
  return TraceTree.newBuilder().addAllEvents(events).setOutput(output).build()
}

/**
 * Builds a [TraceTree] where all branches execute simultaneously (clone, multicast). [cause] is the
 * id of the CloneSessionLookupEvent or MulticastGroupLookupEvent that triggered the replication.
 */
internal fun buildReplicationTree(
  events: List<TraceEvent>,
  cause: Long,
  branches: List<TraceTree>,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setReplication(Replication.newBuilder().setCause(cause).addAllBranches(branches))
    .build()

/**
 * Builds a [TraceTree] where exactly one branch executes at runtime (action selector). [cause] is
 * the id of the TableLookupEvent for the selector table.
 */
internal fun buildChoiceTree(
  events: List<TraceEvent>,
  cause: Long,
  branches: List<TraceTree>,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setChoice(Choice.newBuilder().setCause(cause).addAllBranches(branches))
    .build()

/**
 * Builds a [TraceTree] where the same packet continues as another pass. Used for resubmit,
 * recirculate, and forward stage transitions. [cause] is the id of the event that triggered the
 * re-parse; 0 when absent (forward transitions).
 */
internal fun buildContinuationTree(
  events: List<TraceEvent>,
  cause: Long = 0L,
  next: TraceTree,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setContinuation(
      Continuation.newBuilder().also { if (cause != 0L) it.setCause(cause) }.setNext(next)
    )
    .build()

/**
 * Returns a copy of [tree] with its events replaced by [events], preserving the outcome. The single
 * source of truth for "copy a TraceTree with different events" — avoids duplicating the exhaustive
 * outcome dispatch in every caller.
 */
internal fun TraceTree.withEvents(events: List<TraceEvent>): TraceTree {
  val b = TraceTree.newBuilder().addAllEvents(events)
  when (outcomeCase) {
    TraceTree.OutcomeCase.REPLICATION -> b.setReplication(replication)
    TraceTree.OutcomeCase.CHOICE -> b.setChoice(choice)
    TraceTree.OutcomeCase.CONTINUATION -> b.setContinuation(continuation)
    TraceTree.OutcomeCase.OUTPUT -> b.setOutput(output)
    TraceTree.OutcomeCase.DROP -> b.setDrop(drop)
    TraceTree.OutcomeCase.OUTCOME_NOT_SET,
    null -> {}
  }
  return b.build()
}

/**
 * Prepends [prefix] events to [tree], returning a new [TraceTree] with the same outcome. Used when
 * flattening sequential pipeline stages into a single node.
 */
internal fun prependEvents(tree: TraceTree, prefix: List<TraceEvent>): TraceTree =
  if (prefix.isEmpty()) tree else tree.withEvents(prefix + tree.eventsList)

/** Creates a [TraceEvent] recording the packet's ingress port. */
internal fun packetIngressEvent(ingressPort: DataplanePort): TraceEvent =
  TraceEvent.newBuilder()
    .setPacketIngress(
      PacketIngressEvent.newBuilder().setDataplaneIngressPort(ingressPort.protoValue)
    )
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

/**
 * Creates a [TraceEvent] recording a resubmit or recirculate call — fired immediately before the
 * packet re-enters the pipeline. Anchors [Continuation.cause].
 */
internal fun continuationTriggerEvent(
  kind: ContinuationEvent.Kind,
  preservedFields: Map<String, String> = emptyMap(),
): TraceEvent =
  TraceEvent.newBuilder()
    .setContinuationTrigger(
      ContinuationEvent.newBuilder().setKind(kind).putAllPreservedFields(preservedFields)
    )
    .build()

/** Creates a [TraceEvent] recording a failed multicast group lookup. */
internal fun multicastGroupMissEvent(groupId: Int): TraceEvent =
  TraceEvent.newBuilder()
    .setMulticastGroupLookup(
      MulticastGroupLookupEvent.newBuilder().setMulticastGroupId(groupId).setGroupFound(false)
    )
    .build()
