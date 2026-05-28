package fourward.simulator

import fourward.OutputPacket
import fourward.TraceTree

/**
 * Interface for architecture-specific pipeline behaviour.
 *
 * Different P4 architectures (v1model, PSA, PNA) share the same IR interpreter but differ in:
 * 1. Pipeline structure: which parsers and controls run and in what order.
 * 2. Standard metadata: the metadata struct passed between stages.
 * 3. Extern semantics: what clone(), resubmit(), recirculate() etc. actually do.
 *
 * Points 1 and 2 are driven by the [fourward.Architecture] proto in the loaded pipeline config.
 * Point 3 requires code here.
 *
 * To add a new architecture, implement this interface and register the implementation in
 * [Simulator].
 */
interface Architecture {

  /**
   * Processes a single packet through the full pipeline.
   *
   * The implementation is responsible for:
   * - Setting up the initial environment (standard metadata, header validity).
   * - Running each pipeline stage in the correct order.
   * - Handling architecture-specific operations (clone, resubmit, etc.).
   * - Returning the trace tree (with packet outcomes at leaves).
   */
  fun processPacket(ingressPort: UInt, payload: ByteArray, tableStore: TableStore): PipelineResult =
    processPacket(ingressPort, PacketBits.ofBytes(payload), tableStore)

  fun processPacket(ingressPort: UInt, packet: PacketBits, tableStore: TableStore): PipelineResult
}

/**
 * The result of running a packet through the pipeline.
 *
 * The [trace] tree carries the complete execution trace. Leaf nodes contain [PacketOutcome]s
 * (output packets or drops); fork nodes represent non-deterministic choice points.
 */
data class PipelineResult(val trace: TraceTree) {
  /** All possible outcome sets, derived from the trace tree's fork structure. */
  val possibleOutcomes: List<List<OutputPacket>> by lazy { collectPossibleOutcomes(trace) }
}
