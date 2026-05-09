package fourward.simulator

import fourward.Drop
import fourward.DropReason
import fourward.Fork
import fourward.ForkBranch
import fourward.ForkReason
import fourward.PacketIngressEvent
import fourward.PacketOutcome
import fourward.PipelineStage
import fourward.PipelineStageEvent
import fourward.TraceEvent
import fourward.TraceTree

/**
 * Whether a fork's branches all happen (parallel) or only one happens (alternative).
 * - **Parallel** (clone, multicast, resubmit, recirculate): all branches execute simultaneously.
 *   Output packets are the union of all branch outputs.
 * - **Alternative** (action selector): exactly one branch executes at runtime. Each branch is a
 *   "possible world" — a distinct possible outcome set.
 */
enum class ForkMode {
  PARALLEL,
  ALTERNATIVE,
}

/**
 * Maps a [ForkReason] to its [ForkMode].
 *
 * Exhaustive — adding a new [ForkReason] without updating this function is a compile error.
 */
fun forkModeOf(reason: ForkReason): ForkMode =
  when (reason) {
    ForkReason.ACTION_SELECTOR -> ForkMode.ALTERNATIVE
    ForkReason.CLONE,
    ForkReason.MULTICAST,
    ForkReason.RESUBMIT,
    ForkReason.RECIRCULATE -> ForkMode.PARALLEL
    ForkReason.FORK_REASON_UNSPECIFIED,
    ForkReason.UNRECOGNIZED -> error("unexpected fork reason: $reason")
  }

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

/** Builds a [TraceTree] with a fork outcome from accumulated events and branches. */
internal fun buildForkTree(
  events: List<TraceEvent>,
  reason: ForkReason,
  branches: List<ForkBranch>,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setForkOutcome(Fork.newBuilder().setReason(reason).addAllBranches(branches))
    .build()
