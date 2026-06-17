package fourward.simulator

import fourward.BehavioralConfig
import fourward.ContinuationEvent
import fourward.ExternInstanceDecl
import fourward.PipelineStage
import fourward.TraceTree
import fourward.TypeDecl
import java.math.BigInteger

/**
 * PNA (Portable NIC Architecture) pipeline implementation.
 *
 * PNA is a single-pipeline architecture for network interface cards. Unlike PSA's two-pipeline
 * (ingress + egress) structure, PNA runs a single main pipeline: PreControl → MainParser →
 * MainControl → MainDeparser.
 *
 * Key differences from PSA:
 * - Single-pass: no separate egress pipeline.
 * - Drop/forward via free functions: `drop_packet()` and `send_to_port()` (not ostd fields).
 * - Direction-aware: packets have a direction (NET_TO_HOST or HOST_TO_NET).
 * - Recirculation loops back to main parser (not to a separate ingress).
 *
 * References:
 * - PNA spec: https://p4.org/p4-spec/docs/PNA.html
 * - p4c pna.p4: https://github.com/p4lang/p4c/blob/main/p4include/pna.p4
 */
class PNAArchitecture(private val config: BehavioralConfig) : Architecture {

  // Pipeline-invariant state derived from [config], built once per loaded pipeline. All vals are
  // immutable after publication, so safe to read concurrently from any number of [processPacket]
  // threads without synchronization. Pre-resolving the pipeline stages here also fails fast on
  // misconfigured pipelines.
  private val interpreter: Interpreter = Interpreter(config)
  private val blockParams: Map<String, List<BlockParam>> = buildBlockParamsMap(config)
  private val typesByName: Map<String, TypeDecl> = config.typesList.associateBy { it.name }
  private val externInstances: Map<String, ExternInstanceDecl> = buildExternInstancesMap(config)
  private val mainParser: PipelineStage = resolveStage(config, "PNA", "main_parser")
  private val preControl: PipelineStage = resolveStage(config, "PNA", "pre_control")
  private val mainControl: PipelineStage = resolveStage(config, "PNA", "main_control")
  private val mainDeparser: PipelineStage = resolveStage(config, "PNA", "main_deparser")

  /**
   * Per-call binding: pipeline invariants (held by the architecture) plus the live [TableStore].
   */
  @Suppress("LongParameterList")
  private class PipelineConfig(
    val config: BehavioralConfig,
    val tableStore: TableStore,
    val blockParams: Map<String, List<BlockParam>>,
    val typesByName: Map<String, TypeDecl>,
    val externInstances: Map<String, ExternInstanceDecl>,
    val interpreter: Interpreter,
    val mainParser: PipelineStage,
    val preControl: PipelineStage,
    val mainControl: PipelineStage,
    val mainDeparser: PipelineStage,
  )

  /**
   * Mutable forwarding state shared between extern handler and pipeline logic.
   *
   * PNA uses "last writer wins" semantics for `drop_packet()` / `send_to_port()`. The default
   * behavior is DROP — if neither function is called, the packet is dropped.
   */
  private class ForwardingState {
    var dropped: Boolean = true
    var destPort: Int = 0
    var recirculate: Boolean = false
    /** Mirror session ID requested by `mirror_packet()`, or null if no mirror. */
    var mirrorSessionId: Int? = null
    /** Tracks the currently executing stage for extern call validation. */
    var currentStage: String = ""
  }

  override fun processPacket(
    ingressPort: DataplanePort,
    packet: PacketBits,
    tableStore: TableStore,
  ): PipelineResult {
    val pipeline =
      PipelineConfig(
        config,
        tableStore,
        blockParams,
        typesByName,
        externInstances,
        interpreter,
        mainParser,
        preControl,
        mainControl,
        mainDeparser,
      )

    val tree =
      processPacketRecursive(
        pipeline,
        packet,
        ingressPort,
        PACKET_PATH_FROM_NET_PORT,
        passNumber = 0,
        depth = 0,
      )
    return PipelineResult(tree)
  }

  /**
   * Runs one full PNA pass: PreControl → MainParser → MainControl → MainDeparser.
   *
   * For recirculate, calls itself recursively with the deparsed bytes looped back to main_parser.
   * [depth] guards against infinite recirculation loops. [selectorMembers] carries forced action
   * selector member choices from prior fork re-executions.
   */
  @Suppress("LongParameterList")
  private fun processPacketRecursive(
    pipeline: PipelineConfig,
    packet: PacketBits,
    ingressPort: DataplanePort,
    packetPath: String,
    passNumber: Int,
    depth: Int,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): TraceTree {
    check(depth < MAX_RECIRCULATIONS) {
      "PNA recirculation depth exceeded ($MAX_RECIRCULATIONS) — possible infinite recirculate loop"
    }

    val ctx = PacketContext(packet)
    val env = Environment()
    val values = createDefaultValues(pipeline.config, pipeline.typesByName)
    val forwardingState = ForwardingState()

    initMetadata(values, ingressPort, passNumber)

    val checksumState = mutableMapOf<String, BigInteger>()
    val interpreter =
      pipeline.interpreter.execution(
        pipeline.tableStore,
        ctx,
        selectorMembers,
        createPnaExternHandler(pipeline, forwardingState, checksumState),
      )

    ctx.addTraceEvent(packetIngressEvent(ingressPort))

    // --- Main Parser ---
    // Runs before PreControl so that parser_error is available in pre_input_metadata.
    // This matches the DPDK SoftNIC execution order (parser → pre-control → main-control).
    bindStageParams(env, pipeline.mainParser.blockName, pipeline.blockParams, values)
    runParserStage(interpreter, ctx, env, pipeline.mainParser) { e ->
      (values["pna_pre_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
      (values["pna_main_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // --- PreControl ---
    // The PRE stage is minimally specified in PNA (decrypt is TBD). Run it as a normal control.
    forwardingState.currentStage = "pre_control"
    bindStageParams(env, pipeline.preControl.blockName, pipeline.blockParams, values)
    runControlStage(interpreter, ctx, env, pipeline.preControl)

    try {
      // --- Main Control ---
      forwardingState.currentStage = "main_control"
      bindStageParams(env, pipeline.mainControl.blockName, pipeline.blockParams, values)
      runControlStage(interpreter, ctx, env, pipeline.mainControl)
    } catch (_: AssertionFailureException) {
      return buildDropTrace(ctx.getEvents(), ctx.lastEventIdWhere { it.hasAssertion() })
    } catch (fork: ActionSelectorFork) {
      return handleActionSelectorFork(fork, selectorMembers) { newSelectors ->
        processPacketRecursive(
          pipeline,
          packet,
          ingressPort,
          packetPath,
          passNumber = passNumber,
          depth = depth,
          selectorMembers = newSelectors,
        )
      }
    }

    // --- Post-control forwarding decision ---
    val hasMirror = forwardingState.mirrorSessionId != null

    if (forwardingState.dropped && !forwardingState.recirculate && !hasMirror) {
      return buildDropTrace(ctx.getEvents())
    }

    // --- Main Deparser ---
    // Always run the deparser when mirror is requested (even if dropped), because PNA mirrors
    // the deparsed packet (post-modification), matching DPDK SoftNIC behavior.
    bindStageParams(env, pipeline.mainDeparser.blockName, pipeline.blockParams, values)
    runControlStage(interpreter, ctx, env, pipeline.mainDeparser)
    val deparsedBytes = ctx.deparsedPayload()

    val mirrorTrees = buildMirrorTrees(pipeline, deparsedBytes, forwardingState)

    if (forwardingState.dropped && !forwardingState.recirculate) {
      // Dropped: if mirrors were requested they still go out; original is silently dropped.
      return if (mirrorTrees.isEmpty()) {
        buildDropTrace(ctx.getEvents())
      } else {
        buildReplicationTree(ctx.getEvents(), branches = mirrorTrees)
      }
    }

    if (forwardingState.recirculate) {
      val recircCauseId = ctx.lastEventIdWhere { it.hasContinuationTrigger() }
      val recircTree =
        processPacketRecursive(
          pipeline,
          PacketBits.ofBytes(deparsedBytes),
          ingressPort,
          PACKET_PATH_FROM_NET_RECIRCULATED,
          passNumber = passNumber + 1,
          depth = depth + 1,
        )
      // Mirror is independent of recirculation — emit both if requested.
      return if (mirrorTrees.isEmpty()) {
        buildContinuationTree(ctx.getEvents(), cause = recircCauseId, next = recircTree)
      } else {
        val continuationBranch = buildContinuationTree(emptyList(), next = recircTree)
        buildReplicationTree(
          ctx.getEvents(),
          cause = recircCauseId,
          listOf(continuationBranch) + mirrorTrees,
        )
      }
    }

    val originalTree = buildOutputTrace(ctx.getEvents(), forwardingState.destPort, deparsedBytes)
    return if (mirrorTrees.isEmpty()) {
      originalTree
    } else {
      buildReplicationTree(emptyList(), branches = listOf(originalTree) + mirrorTrees)
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata initialisation
  // ---------------------------------------------------------------------------

  private fun initMetadata(
    values: Map<String, Value>,
    ingressPort: DataplanePort,
    passNumber: Int,
  ) {
    val loopedback = passNumber > 0
    val direction = DIRECTION_NET_TO_HOST
    val portLong = ingressPort.unsignedLong
    // PassNumber_t is bit<3> (pna.p4), so truncate to 3 bits to avoid overflow.
    val passTruncated = (passNumber and 0x7).toLong()

    (values["pna_pre_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("input_port", portLong)
      it.fields["parser_error"] = ErrorVal.NO_ERROR
      it.fields["direction"] = EnumVal(direction)
      if (it.fields.containsKey("pass")) it.setBitField("pass", passTruncated)
      it.fields["loopedback"] = BoolVal(loopedback)
    }
    (values["pna_pre_output_metadata_t"] as? StructVal)?.let {
      it.fields["decrypt"] = BoolVal(false)
    }
    (values["pna_main_parser_input_metadata_t"] as? StructVal)?.let {
      it.fields["direction"] = EnumVal(direction)
      if (it.fields.containsKey("pass")) it.setBitField("pass", passTruncated)
      it.fields["loopedback"] = BoolVal(loopedback)
      it.setBitField("input_port", portLong)
    }
    (values["pna_main_input_metadata_t"] as? StructVal)?.let {
      it.fields["direction"] = EnumVal(direction)
      if (it.fields.containsKey("pass")) it.setBitField("pass", passTruncated)
      it.fields["loopedback"] = BoolVal(loopedback)
      if (it.fields.containsKey("timestamp")) it.setBitField("timestamp", 0L)
      it.fields["parser_error"] = ErrorVal.NO_ERROR
      if (it.fields.containsKey("class_of_service")) it.setBitField("class_of_service", 0L)
      it.setBitField("input_port", portLong)
    }
    (values["pna_main_output_metadata_t"] as? StructVal)?.let {
      if (it.fields.containsKey("class_of_service")) it.setBitField("class_of_service", 0L)
    }
  }

  // ---------------------------------------------------------------------------
  // PNA extern handler
  // ---------------------------------------------------------------------------

  /**
   * PNA extern handler: free functions and extern object methods.
   *
   * PNA's forwarding externs (`drop_packet`, `send_to_port`, `recirculate`) are free functions that
   * mutate [forwardingState] directly, unlike PSA which uses output metadata fields.
   */
  private fun createPnaExternHandler(
    pipeline: PipelineConfig,
    forwardingState: ForwardingState,
    checksumState: MutableMap<String, BigInteger>,
  ): ExternHandler = ExternHandler { call, eval ->
    when (call) {
      is ExternCall.FreeFunction -> handlePnaFreeFunction(call, eval, pipeline, forwardingState)
      is ExternCall.Method -> handlePnaMethod(call, eval, pipeline, checksumState)
    }
  }

  /** Handles PNA free-function externs (forwarding, mirroring, add-on-miss). */
  private fun handlePnaFreeFunction(
    call: ExternCall.FreeFunction,
    eval: ExternEvaluator,
    pipeline: PipelineConfig,
    forwardingState: ForwardingState,
  ): Value =
    when (call.name) {
      // PNA spec (pna.p4 §562-570): drop_packet and send_to_port are the "forwarding"
      // set — calling one overwrites the other (last writer wins). recirculate is
      // independent and NOT part of this set.
      "drop_packet" -> {
        // PNA spec: "Invoking drop_packet() is supported only within the main control."
        require(forwardingState.currentStage == "main_control") {
          "drop_packet() called in ${forwardingState.currentStage}, " +
            "but PNA only supports it within main_control"
        }
        forwardingState.dropped = true
        UnitVal
      }
      "send_to_port" -> {
        val port = eval.evalArg(0) as BitVal
        forwardingState.dropped = false
        forwardingState.destPort = port.bits.value.toInt()
        UnitVal
      }
      "mirror_packet" -> {
        val sessionId = (eval.evalArg(1) as BitVal).bits.value.toInt()
        forwardingState.mirrorSessionId = sessionId
        UnitVal
      }
      "recirculate" -> {
        eval.addTraceEvent(continuationTriggerEvent(ContinuationEvent.Kind.RECIRCULATE))
        forwardingState.recirculate = true
        UnitVal
      }
      "SelectByDirection" -> {
        val direction = (eval.evalArg(0) as EnumVal).member
        if (direction == DIRECTION_NET_TO_HOST) eval.evalArg(1) else eval.evalArg(2)
      }
      "add_entry" -> execAddEntry(eval, pipeline)
      // Stub: returns 0. Real NICs would allocate unique per-flow IDs, but a fixed value
      // suffices for STF testing where flow ID values are not checked.
      "allocate_flow_id" -> BitVal(BitVector(BigInteger.ZERO, 32))
      // No-op: the simulator has no real timers, so expiration is not modeled.
      "set_entry_expire_time" -> UnitVal
      "restart_expire_timer" -> UnitVal
      else -> error("PNA extern '${call.name}' is not implemented")
    }

  /**
   * Implements PNA's `add_entry` extern: inserts a table entry from the data plane.
   *
   * The extern signature (from pna.p4):
   * ```
   * extern bool add_entry<T>(string action_name, in T action_params,
   *                          in ExpireTimeProfileId_t expire_time_profile_id);
   * ```
   *
   * The table to insert into is determined by the most recent table miss (the table with
   * `add_on_miss = true` whose default action called `add_entry`). The match key values come from
   * that same miss. After the midend, action_params may be a single value (for actions with one
   * parameter) or a struct (for actions with multiple parameters).
   */
  private fun execAddEntry(eval: ExternEvaluator, pipeline: PipelineConfig): BoolVal {
    val missCtx = eval.lastTableMiss() ?: error("add_entry called but no table miss has occurred")
    val actionAlias = (eval.evalArg(0) as StringVal).value
    val actionName =
      pipeline.tableStore.resolveActionForTable(missCtx.tableName, actionAlias)
        ?: error("add_entry: unknown action '$actionAlias'")

    // The action_params argument (arg 1) is either a single value (one-param actions)
    // or a struct (multi-param actions, lowered to a tuple by the midend).
    val paramsValue = eval.evalArg(1)
    val actionParams: List<Value> =
      when (paramsValue) {
        is StructVal -> paramsValue.fields.values.toList()
        else -> listOf(paramsValue)
      }

    val success =
      pipeline.tableStore.addEntry(missCtx.tableName, missCtx.keyValues, actionName, actionParams)
    return BoolVal(success)
  }

  /** Handles PNA extern method calls (Register, Hash, Counter, Meter, Checksum, Digest). */
  private fun handlePnaMethod(
    call: ExternCall.Method,
    eval: ExternEvaluator,
    pipeline: PipelineConfig,
    checksumState: MutableMap<String, BigInteger>,
  ): Value =
    handleCommonExternMethod(
      call,
      eval,
      pipeline.tableStore,
      pipeline.externInstances,
      checksumState,
      PNA_HASH_ALGORITHMS,
    )
      ?: error(
        "PNA extern method '${call.externType}.${call.method}' is not implemented" +
          " (on ${call.instanceName})"
      )

  // ---------------------------------------------------------------------------
  // Mirror branch construction
  // ---------------------------------------------------------------------------

  /**
   * Builds mirror output subtrees if `mirror_packet()` was called during main control.
   *
   * PNA mirrors the deparsed packet (post-modification), matching DPDK SoftNIC behavior. Each
   * replica in the clone session outputs the deparsed bytes on the replica's egress port. Unlike
   * PSA clones (which run through a separate egress pipeline), PNA mirror replicas emit directly
   * because PNA has no separate egress stage for mirrored packets to traverse.
   */
  private fun buildMirrorTrees(
    pipeline: PipelineConfig,
    deparsedBytes: ByteArray,
    forwardingState: ForwardingState,
  ): List<TraceTree> {
    val sessionId = forwardingState.mirrorSessionId ?: return emptyList()
    val session = pipeline.tableStore.getCloneSession(sessionId) ?: return emptyList()
    return session.replicasList.map { replica ->
      buildOutputTrace(emptyList(), replicaPort(replica), deparsedBytes)
    }
  }

  private companion object {
    // PNA_Direction_t enum values (pna.p4).
    const val DIRECTION_NET_TO_HOST = "NET_TO_HOST"

    // PNA_PacketPath_t enum values (pna.p4).
    const val PACKET_PATH_FROM_NET_PORT = "FROM_NET_PORT"
    const val PACKET_PATH_FROM_NET_RECIRCULATED = "FROM_NET_RECIRCULATED"

    /**
     * Maps PNA_HashAlgorithm_t enum members to the internal algorithm names used by Hash.kt.
     *
     * PNA's hash algorithm enum has the same values as PSA's, plus TARGET_DEFAULT. The simulator
     * treats TARGET_DEFAULT as identity because it has no target-specific hash pipeline.
     */
    val PNA_HASH_ALGORITHMS =
      mapOf(
        "IDENTITY" to "identity",
        "CRC16" to "crc16",
        "CRC32" to "crc32",
        "ONES_COMPLEMENT16" to "csum16",
        "TARGET_DEFAULT" to "identity",
      )
  }
}
