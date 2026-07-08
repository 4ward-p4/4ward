package fourward.simulator

import fourward.Choice
import fourward.Continuation
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
internal fun buildDropTrace(events: List<TraceEvent>, causeId: Long? = null): TraceTree {
  val drop = Drop.newBuilder().also { if (causeId != null) it.setCauseId(causeId) }.build()
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
 * id of the CloneSessionLookupEvent or MulticastGroupLookupEvent that triggered the replication, or
 * null when there is no triggering event (e.g. PNA mirror_packet).
 */
internal fun buildReplicationTree(
  events: List<TraceEvent>,
  branches: List<TraceTree>,
  cause: Long? = null,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setReplication(
      Replication.newBuilder()
        .also { if (cause != null) it.setCauseId(cause) }
        .addAllBranches(branches)
    )
    .build()

/**
 * Builds a [TraceTree] where exactly one branch executes at runtime (action selector). [cause] is
 * the id of the TableLookupEvent for the selector table.
 */
internal fun buildChoiceTree(
  events: List<TraceEvent>,
  cause: Long?,
  branches: List<TraceTree>,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setChoice(
      Choice.newBuilder().also { if (cause != null) it.setCauseId(cause) }.addAllBranches(branches)
    )
    .build()

/**
 * Builds a [TraceTree] where the same packet continues as another pass. Used for resubmit and
 * recirculate. [kind] must be RESUBMIT or RECIRCULATE.
 */
internal fun buildContinuationTree(
  events: List<TraceEvent>,
  kind: Continuation.Kind,
  preservedFields: Map<String, String> = emptyMap(),
  next: TraceTree,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setContinuation(
      Continuation.newBuilder().setKind(kind).putAllPreservedFields(preservedFields).setNext(next)
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

/**
 * Assigns trace-global event ids and rewrites outcome causes to the new ids.
 *
 * Fork branches and continuations are often built from fresh [PacketContext]s, so their events can
 * legitimately start with branch-local ids while the architecture is assembling the tree. This is
 * the boundary where the simulator turns that internal representation into the public trace
 * contract: every [TraceEvent.id] is unique across the recursive [TraceTree], and every cause still
 * points to the event in the same node that originally triggered the outcome.
 */
internal fun normalizeTraceEventIds(tree: TraceTree): TraceTree =
  TraceEventIdNormalizer().normalize(tree)

private class TraceEventIdNormalizer {
  private var nextEventId = 1L

  fun normalize(tree: TraceTree): TraceTree = normalizeNode(tree)

  private fun normalizeNode(node: TraceTree): TraceTree {
    val eventIdMap = mutableMapOf<Long, Long>()
    val normalized = TraceTree.newBuilder()
    for (event in node.eventsList) {
      val newId = nextEventId++
      eventIdMap[event.id] = newId
      normalized.addEvents(event.toBuilder().setId(newId))
    }

    // Outcome causes are node-local by construction: Replication/Choice/Drop cause references an
    // event in the node's own events list, not an event in a child subtree.
    fun remapCause(cause: Long): Long =
      checkNotNull(eventIdMap[cause]) { "trace outcome cause $cause does not reference this node" }

    when (node.outcomeCase) {
      TraceTree.OutcomeCase.REPLICATION -> {
        val replication = node.replication.toBuilder().clearBranches()
        if (node.replication.hasCauseId())
          replication.setCauseId(remapCause(node.replication.causeId))
        for (branch in node.replication.branchesList) {
          replication.addBranches(normalizeNode(branch))
        }
        normalized.setReplication(replication)
      }
      TraceTree.OutcomeCase.CHOICE -> {
        val choice = node.choice.toBuilder().clearBranches()
        if (node.choice.hasCauseId()) choice.setCauseId(remapCause(node.choice.causeId))
        for (branch in node.choice.branchesList) {
          choice.addBranches(normalizeNode(branch))
        }
        normalized.setChoice(choice)
      }
      TraceTree.OutcomeCase.CONTINUATION ->
        normalized.setContinuation(
          node.continuation.toBuilder().setNext(normalizeNode(node.continuation.next))
        )
      TraceTree.OutcomeCase.OUTPUT -> normalized.setOutput(node.output)
      TraceTree.OutcomeCase.DROP -> {
        val drop = node.drop.toBuilder()
        if (node.drop.hasCauseId()) drop.setCauseId(remapCause(node.drop.causeId))
        normalized.setDrop(drop)
      }
      TraceTree.OutcomeCase.OUTCOME_NOT_SET,
      null -> {}
    }
    return normalized.build()
  }
}

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

/** Creates a [TraceEvent] recording a failed multicast group lookup. */
internal fun multicastGroupMissEvent(groupId: Int): TraceEvent =
  TraceEvent.newBuilder()
    .setMulticastGroupLookup(
      MulticastGroupLookupEvent.newBuilder().setMulticastGroupId(groupId).setGroupFound(false)
    )
    .build()
