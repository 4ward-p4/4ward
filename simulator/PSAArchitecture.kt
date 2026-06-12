package fourward.simulator

import fourward.BehavioralConfig
import fourward.Continuation
import fourward.ContinuationKind
import fourward.DropReason
import fourward.ExternInstanceDecl
import fourward.PipelineStage
import fourward.TraceEvent
import fourward.TraceTree
import fourward.TypeDecl
import java.math.BigInteger

/**
 * PSA (Portable Switch Architecture) pipeline implementation.
 *
 * PSA is a two-pipeline architecture with separate ingress and egress stages. The egress pipeline
 * re-parses the deparsed packet, so header and metadata state is NOT shared between ingress and
 * egress — only the raw bytes cross the boundary.
 *
 * Pipeline structure: IngressParser → Ingress → IngressDeparser → (traffic manager) → EgressParser
 * → Egress → EgressDeparser
 *
 * Key differences from v1model:
 * - Ingress drops by default (`ostd.drop` starts `true`). To forward, call `send_to_port()`.
 * - Egress does NOT drop by default (`ostd.drop` starts `false`).
 * - Port width is `bit<32>` (not `bit<9>`).
 * - Each stage has distinct metadata structs (no shared `standard_metadata`).
 *
 * References:
 * - PSA spec: https://p4.org/p4-spec/docs/PSA.html
 * - BMv2 psa.p4: https://github.com/p4lang/p4c/blob/main/p4include/bmv2/psa.p4
 */
class PSAArchitecture(private val config: BehavioralConfig) : Architecture {

  // Pipeline-invariant state derived from [config], built once per loaded pipeline. All vals are
  // immutable after publication, so safe to read concurrently from any number of [processPacket]
  // threads without synchronization. Pre-resolving the pipeline stages here also fails fast on
  // misconfigured pipelines.
  private val interpreter: Interpreter = Interpreter(config)
  private val blockParams: Map<String, List<BlockParam>> = buildBlockParamsMap(config)
  private val typesByName: Map<String, TypeDecl> = config.typesList.associateBy { it.name }
  private val externInstances: Map<String, ExternInstanceDecl> = buildExternInstancesMap(config)
  private val ingressParser: PipelineStage = resolveStage(config, "PSA", "ingress_parser")
  private val ingress: PipelineStage = resolveStage(config, "PSA", "ingress")
  private val ingressDeparser: PipelineStage = resolveStage(config, "PSA", "ingress_deparser")
  private val egressParser: PipelineStage = resolveStage(config, "PSA", "egress_parser")
  private val egress: PipelineStage = resolveStage(config, "PSA", "egress")
  private val egressDeparser: PipelineStage = resolveStage(config, "PSA", "egress_deparser")

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
    val ingressParser: PipelineStage,
    val ingress: PipelineStage,
    val ingressDeparser: PipelineStage,
    val egressParser: PipelineStage,
    val egress: PipelineStage,
    val egressDeparser: PipelineStage,
  )

  override fun processPacket(
    ingressPort: UInt,
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
        ingressParser,
        ingress,
        ingressDeparser,
        egressParser,
        egress,
        egressDeparser,
      )

    val tree = processPacketRecursive(pipeline, packet, ingressPort, PACKET_PATH_NORMAL, depth = 0)
    return PipelineResult(tree)
  }

  /**
   * Runs one full ingress→egress pass and handles I2E/E2E clone forks and recirculation.
   *
   * For recirculate, calls itself recursively with the deparsed egress bytes looped back to
   * ingress. [depth] guards against infinite recirculation loops. [selectorMembers] carries forced
   * action selector member choices from prior fork re-executions.
   */
  private fun processPacketRecursive(
    pipeline: PipelineConfig,
    packet: PacketBits,
    ingressPort: UInt,
    packetPath: String,
    depth: Int,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): TraceTree {
    check(depth < MAX_RECIRCULATIONS) {
      "PSA recirculation depth exceeded ($MAX_RECIRCULATIONS) — possible infinite recirculate loop"
    }

    // === Ingress pipeline ===
    val ingress: IngressResult
    try {
      ingress = runIngressPipeline(pipeline, packet, ingressPort, packetPath, selectorMembers)
    } catch (fork: ActionSelectorFork) {
      return handleActionSelectorFork(fork, selectorMembers) { newSelectors ->
        processPacketRecursive(
          pipeline,
          packet,
          ingressPort,
          packetPath,
          depth = depth,
          selectorMembers = newSelectors,
        )
      }
    }

    // === End-of-ingress decisions (PSA spec §6.2, Table 4) ===
    // Priority: drop > resubmit > {I2E clone, multicast/unicast}.
    // I2E clone is independent of drop but suppressed by resubmit.

    if (ingress.dropped) {
      val i2eClones = buildI2EClones(pipeline, packet, ingress.output, selectorMembers)
      if (i2eClones.isNotEmpty()) {
        return buildContinuationsTree(ingress.events, i2eClones)
      }
      return buildDropTrace(ingress.events, ingress.dropReason)
    }

    // Resubmit: loop original bytes back to ingress. Suppresses I2E clone.
    val resubmit = (ingress.output?.fields?.get("resubmit") as? BoolVal)?.value == true
    if (resubmit) {
      val resubmitTree =
        processPacketRecursive(
          pipeline,
          packet,
          ingressPort,
          PACKET_PATH_RESUBMIT,
          depth = depth + 1,
        )
      return buildContinuationsTree(
        ingress.events,
        listOf(continuation(ContinuationKind.RESUBMIT, resubmitTree)),
      )
    }

    // === Traffic Manager: I2E clone + multicast/unicast ===
    val i2eClones = buildI2EClones(pipeline, packet, ingress.output, selectorMembers)
    val originalTree = buildOriginalEgressPath(pipeline, ingress, depth, selectorMembers)

    if (i2eClones.isNotEmpty()) {
      return buildContinuationsTree(
        ingress.events,
        listOf(continuation(ContinuationKind.ORIGINAL, originalTree)) + i2eClones,
      )
    }

    // No I2E clone: flatten ingress + original egress into a single trace.
    return TraceTree.newBuilder()
      .addAllEvents(ingress.events)
      .addAllEvents(originalTree.eventsList)
      .copyOutcome(originalTree)
      .build()
  }

  /** Builds the egress path for the original (non-cloned) packet: multicast or unicast. */
  private fun buildOriginalEgressPath(
    pipeline: PipelineConfig,
    ingress: IngressResult,
    depth: Int,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): TraceTree {
    val egressState = EgressState(pipeline, ingress.output)
    val multicastGroup =
      (ingress.output?.fields?.get("multicast_group") as? BitVal)?.bits?.value?.toInt() ?: 0

    if (multicastGroup != 0) {
      return multicastEgressTree(egressState, ingress, multicastGroup, depth, selectorMembers)
    }

    val egressPort =
      (ingress.output?.fields?.get("egress_port") as? BitVal)?.bits?.value?.toInt() ?: 0

    return runEgressWithPostProcessing(
      egressState,
      ingress.deparsedBytes,
      egressPort,
      0,
      PACKET_PATH_NORMAL_UNICAST,
      depth,
      selectorMembers,
    )
  }

  // ---------------------------------------------------------------------------
  // Ingress pipeline
  // ---------------------------------------------------------------------------

  /** Result of running the ingress pipeline (parser → control → drop check → deparser). */
  private class IngressResult(
    val events: List<TraceEvent>,
    val output: StructVal?,
    val deparsedBytes: ByteArray,
    val dropped: Boolean,
    val dropReason: DropReason = DropReason.MARK_TO_DROP,
  )

  private fun runIngressPipeline(
    pipeline: PipelineConfig,
    packet: PacketBits,
    ingressPort: UInt,
    packetPath: String,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): IngressResult {
    val ctx = PacketContext(packet)
    val env = Environment()
    val values = createDefaultValues(pipeline.config, pipeline.typesByName)

    initIngressMetadata(values, ingressPort, packetPath)
    val output = values["psa_ingress_output_metadata_t"] as? StructVal

    val interpreter =
      pipeline.interpreter.execution(
        pipeline.tableStore,
        ctx,
        selectorMembers,
        createPsaExternHandler(pipeline),
      )

    ctx.addTraceEvent(packetIngressEvent(ingressPort))

    // --- Ingress Parser ---
    bindStageParams(env, pipeline.ingressParser.blockName, pipeline.blockParams, values)
    runParserStage(interpreter, ctx, env, pipeline.ingressParser) { e ->
      (values["psa_ingress_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // An assert()/assume() failure anywhere in the pipeline drops the packet.
    try {
      // --- Ingress Control ---
      bindStageParams(env, pipeline.ingress.blockName, pipeline.blockParams, values)
      runControlStage(interpreter, ctx, env, pipeline.ingress)

      // --- Ingress drop check (PSA: drop=true by default) ---
      val dropped = (output?.fields?.get("drop") as? BoolVal)?.value != false
      if (dropped) {
        return IngressResult(ctx.getEvents(), output, byteArrayOf(), dropped = true)
      }

      // --- Ingress Deparser ---
      bindStageParams(env, pipeline.ingressDeparser.blockName, pipeline.blockParams, values)
      runControlStage(interpreter, ctx, env, pipeline.ingressDeparser)
    } catch (_: AssertionFailureException) {
      return IngressResult(
        ctx.getEvents(),
        output,
        byteArrayOf(),
        dropped = true,
        dropReason = DropReason.ASSERTION_FAILURE,
      )
    }

    val deparsedBytes = ctx.deparsedPayload()
    return IngressResult(ctx.getEvents(), output, deparsedBytes, dropped = false)
  }

  // ---------------------------------------------------------------------------
  // Traffic Manager: multicast
  // ---------------------------------------------------------------------------

  /** Builds a multicast fork TraceTree (without ingress events — caller prepends those). */
  private fun multicastEgressTree(
    egressState: EgressState,
    ingress: IngressResult,
    multicastGroup: Int,
    depth: Int,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): TraceTree {
    val group = egressState.pipeline.tableStore.getMulticastGroup(multicastGroup)
    if (group == null) {
      return buildDropTrace(listOf(multicastGroupMissEvent(multicastGroup)))
    }

    val lookupEvent = multicastGroupLookupEvent(multicastGroup, group.replicasCount)
    val replicas =
      group.replicasList.map { replica ->
        val port = replicaPort(replica)
        val subtree =
          runEgressWithPostProcessing(
            egressState,
            ingress.deparsedBytes,
            port,
            replica.instance,
            PACKET_PATH_NORMAL_MULTICAST,
            depth,
            selectorMembers,
          )
        continuation(
          ContinuationKind.MULTICAST_REPLICA,
          subtree,
          dataplaneEgressPort = port,
          instance = replica.instance,
        )
      }
    return buildContinuationsTree(listOf(lookupEvent), replicas)
  }

  // ---------------------------------------------------------------------------
  // Egress pipeline (runs once per unicast, once per multicast replica)
  // ---------------------------------------------------------------------------

  /** Shared state needed to run the egress pipeline. */
  private class EgressState(val pipeline: PipelineConfig, val ingressOutput: StructVal?)

  /**
   * Raw result from running the egress pipeline stages (before post-processing for clone/recirc).
   */
  private class EgressCoreResult(
    val events: List<TraceEvent>,
    val dropped: Boolean,
    val deparsedBytes: ByteArray,
    val output: StructVal?,
    val dropReason: DropReason = DropReason.MARK_TO_DROP,
  )

  /**
   * Runs the full egress pipeline (parser → control → drop check → deparser), then handles E2E
   * clone forks and recirculation.
   */
  private fun runEgressWithPostProcessing(
    state: EgressState,
    deparsedBytes: ByteArray,
    egressPort: Int,
    instance: Int,
    packetPath: String,
    depth: Int,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): TraceTree {
    val core: EgressCoreResult
    try {
      core =
        runEgressCore(
          state,
          PacketBits.ofBytes(deparsedBytes),
          egressPort,
          instance,
          packetPath,
          selectorMembers,
        )
    } catch (fork: ActionSelectorFork) {
      return handleActionSelectorFork(fork, selectorMembers) { newSelectors ->
        runEgressWithPostProcessing(
          state,
          deparsedBytes,
          egressPort,
          instance,
          packetPath,
          depth,
          newSelectors,
        )
      }
    }

    // E2E clone: uses deparsed bytes from this egress pass. Independent of drop decision.
    val e2eClones = buildE2EClones(state.pipeline, core, selectorMembers)

    if (core.dropped) {
      if (e2eClones.isNotEmpty()) {
        return buildContinuationsTree(core.events, e2eClones)
      }
      return buildDropTrace(core.events, core.dropReason)
    }

    // Recirculate: loop deparsed bytes back to ingress.
    val isRecirculate = egressPort.toUInt().toLong() == PSA_PORT_RECIRCULATE_LONG
    val outputTree =
      if (isRecirculate) {
        val recircTree =
          processPacketRecursive(
            state.pipeline,
            PacketBits.ofBytes(core.deparsedBytes),
            PSA_PORT_RECIRCULATE_UINT,
            PACKET_PATH_RECIRCULATE,
            depth = depth + 1,
          )
        buildContinuationsTree(
          core.events,
          listOf(continuation(ContinuationKind.RECIRCULATE, recircTree)),
        )
      } else {
        buildOutputTrace(core.events, egressPort, core.deparsedBytes)
      }

    if (e2eClones.isNotEmpty()) {
      return buildContinuationsTree(
        emptyList(),
        listOf(continuation(ContinuationKind.ORIGINAL, outputTree)) + e2eClones,
      )
    }

    return outputTree
  }

  /**
   * Runs the egress pipeline stages (parser → control → drop check → deparser) and returns raw
   * results for post-processing by the caller.
   */
  private fun runEgressCore(
    state: EgressState,
    packet: PacketBits,
    egressPort: Int,
    instance: Int,
    packetPath: String,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): EgressCoreResult {
    val p = state.pipeline
    val egressCtx = PacketContext(packet)
    val egressEnv = Environment()
    val egressValues = createDefaultValues(p.config, p.typesByName)

    initEgressMetadata(egressValues, egressPort, instance, packetPath, state.ingressOutput)
    val egressOutput = egressValues["psa_egress_output_metadata_t"] as? StructVal

    val egressInterpreter =
      p.interpreter.execution(p.tableStore, egressCtx, selectorMembers, createPsaExternHandler(p))

    // --- Egress Parser ---
    bindStageParams(egressEnv, p.egressParser.blockName, p.blockParams, egressValues)
    runParserStage(egressInterpreter, egressCtx, egressEnv, p.egressParser) { e ->
      (egressValues["psa_egress_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // An assert()/assume() failure anywhere in egress drops the packet.
    try {
      // --- Egress Control ---
      bindStageParams(egressEnv, p.egress.blockName, p.blockParams, egressValues)
      runControlStage(egressInterpreter, egressCtx, egressEnv, p.egress)
    } catch (_: AssertionFailureException) {
      return EgressCoreResult(
        egressCtx.getEvents(),
        dropped = true,
        byteArrayOf(),
        egressOutput,
        dropReason = DropReason.ASSERTION_FAILURE,
      )
    }

    // --- Egress drop check (PSA: egress does NOT drop by default) ---
    val dropped = (egressOutput?.fields?.get("drop") as? BoolVal)?.value == true

    // --- Egress Deparser (always runs — E2E clone uses deparsed bytes even for dropped pkts) ---
    bindStageParams(egressEnv, p.egressDeparser.blockName, p.blockParams, egressValues)
    runControlStage(egressInterpreter, egressCtx, egressEnv, p.egressDeparser)

    val outputBytes = egressCtx.deparsedPayload()
    return EgressCoreResult(egressCtx.getEvents(), dropped, outputBytes, egressOutput)
  }

  // ---------------------------------------------------------------------------
  // Clone branch construction
  // ---------------------------------------------------------------------------

  /**
   * Builds I2E clone continuations if the ingress output metadata requests cloning.
   *
   * I2E clones use the ORIGINAL input bytes (pre-ingress) — not the ingress-deparsed output. Each
   * replica in the clone session's multicast group produces a separate egress execution with
   * `packet_path = CLONE_I2E`.
   */
  private fun buildI2EClones(
    pipeline: PipelineConfig,
    originalPacket: PacketBits,
    ingressOutput: StructVal?,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): List<Continuation> =
    // I2E clones don't carry ingress output metadata to egress — egress gets a fresh EgressState
    // with no ingressOutput. Clones do not produce further clones (no chained cloning in PSA).
    buildClones(pipeline, ingressOutput, originalPacket, PACKET_PATH_CLONE_I2E, selectorMembers)

  /**
   * Builds E2E clone continuations if the egress output metadata requests cloning.
   *
   * E2E clones use the deparsed bytes from the current egress pass. Each replica produces a fresh
   * egress execution with `packet_path = CLONE_E2E`. Chained E2E cloning is suppressed (PSA spec:
   * behavior is implementation-specific; BMv2 suppresses it).
   */
  private fun buildE2EClones(
    pipeline: PipelineConfig,
    core: EgressCoreResult,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): List<Continuation> =
    buildClones(
      pipeline,
      core.output,
      PacketBits.ofBytes(core.deparsedBytes),
      PACKET_PATH_CLONE_E2E,
      selectorMembers,
    )

  /**
   * Shared clone continuation builder for both I2E and E2E cloning.
   *
   * Checks the clone flag in [cloneMetadata], looks up the clone session, and runs a fresh egress
   * for each replica. Clone egress runs via [runEgressCore] directly (no post-processing), which
   * prevents chained cloning.
   */
  private fun buildClones(
    pipeline: PipelineConfig,
    cloneMetadata: StructVal?,
    clonePacket: PacketBits,
    packetPath: String,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): List<Continuation> {
    if ((cloneMetadata?.fields?.get("clone") as? BoolVal)?.value != true) return emptyList()
    val sessionId =
      (cloneMetadata.fields["clone_session_id"] as? BitVal)?.bits?.value?.toInt()
        ?: return emptyList()
    val session = pipeline.tableStore.getCloneSession(sessionId) ?: return emptyList()
    val cloneState = EgressState(pipeline, ingressOutput = null)
    return session.replicasList.map { replica ->
      val port = replicaPort(replica)
      val subtree =
        runCloneEgress(cloneState, clonePacket, port, replica.instance, packetPath, selectorMembers)
      continuation(
        ContinuationKind.CLONE,
        subtree,
        dataplaneEgressPort = port,
        instance = replica.instance,
      )
    }
  }

  /**
   * Runs a single clone replica's egress pipeline, handling ActionSelectorFork.
   *
   * Clone continuations call [runEgressCore] directly (no post-processing) to prevent chained
   * cloning. But egress tables can still hit action selector groups, so we must catch the fork
   * here.
   */
  private fun runCloneEgress(
    cloneState: EgressState,
    clonePacket: PacketBits,
    egressPort: Int,
    instance: Int,
    packetPath: String,
    selectorMembers: Map<String, Int>,
  ): TraceTree {
    fun egressToTrace(result: EgressCoreResult): TraceTree =
      if (result.dropped) buildDropTrace(result.events, result.dropReason)
      else buildOutputTrace(result.events, egressPort, result.deparsedBytes)
    return try {
      egressToTrace(
        runEgressCore(cloneState, clonePacket, egressPort, instance, packetPath, selectorMembers)
      )
    } catch (fork: ActionSelectorFork) {
      handleActionSelectorFork(fork, selectorMembers) { newSelectors ->
        runCloneEgress(cloneState, clonePacket, egressPort, instance, packetPath, newSelectors)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata initialisation
  // ---------------------------------------------------------------------------

  private fun initIngressMetadata(
    values: Map<String, Value>,
    ingressPort: UInt,
    packetPath: String,
  ) {
    (values["psa_ingress_parser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("ingress_port", ingressPort.toLong())
      it.fields["packet_path"] = EnumVal(packetPath)
    }
    (values["psa_ingress_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("ingress_port", ingressPort.toLong())
      it.fields["packet_path"] = EnumVal(packetPath)
      it.fields["parser_error"] = ErrorVal.NO_ERROR
    }
    // PSA spec: ingress output metadata defaults to drop=true.
    (values["psa_ingress_output_metadata_t"] as? StructVal)?.let {
      it.fields["drop"] = BoolVal(true)
    }
  }

  private fun initEgressMetadata(
    values: Map<String, Value>,
    egressPort: Int,
    instance: Int,
    packetPath: String,
    ingressOutput: StructVal?,
  ) {
    val cos =
      (ingressOutput?.fields?.get("class_of_service") as? BitVal)?.bits?.value?.toLong() ?: 0
    // PSA ports are bit<32>; convert signed Int to unsigned Long for BitVector compatibility.
    val unsignedPort = egressPort.toUInt().toLong()
    (values["psa_egress_parser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("egress_port", unsignedPort)
      it.fields["packet_path"] = EnumVal(packetPath)
    }
    (values["psa_egress_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("class_of_service", cos)
      it.setBitField("egress_port", unsignedPort)
      it.fields["packet_path"] = EnumVal(packetPath)
      // PSA spec §6.5: instance identifies the replica within a multicast group.
      // Only set if the program declares the field (not all PSA programs use it).
      if (it.fields.containsKey("instance")) it.setBitField("instance", instance.toLong())
      it.fields["parser_error"] = ErrorVal.NO_ERROR
    }
    // PSA spec: egress output metadata defaults to drop=false.
    (values["psa_egress_output_metadata_t"] as? StructVal)?.let {
      it.fields["drop"] = BoolVal(false)
    }
    (values["psa_egress_deparser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("egress_port", unsignedPort)
    }
  }

  // Stage execution helpers (bindStageParams, runParserStage, runControlStage,
  // createDefaultValues, buildBlockParamsMap, buildExternInstancesMap) live in
  // ArchitectureHelpers.kt.

  // ---------------------------------------------------------------------------
  // PSA extern handler
  // ---------------------------------------------------------------------------

  /** PSA extern handler: free functions and extern object methods. */
  private fun createPsaExternHandler(pipeline: PipelineConfig): ExternHandler {
    // Per-pipeline InternetChecksum state: instance name → running ones' complement sum.
    // Fresh for each pipeline execution (ingress or egress).
    val checksumState = mutableMapOf<String, BigInteger>()
    return ExternHandler { call, eval ->
      when (call) {
        is ExternCall.FreeFunction ->
          when (call.name) {
            // send_to_port(inout ostd, in PortId_t port)
            // After midend, becomes: send_to_port(ostd, port) where ostd is the output metadata.
            "send_to_port" -> {
              val ostd = eval.evalArg(0) as StructVal
              val port = eval.evalArg(1) as BitVal
              ostd.fields["drop"] = BoolVal(false)
              ostd.fields["egress_port"] = port
              UnitVal
            }
            // multicast(inout ostd, in MulticastGroup_t group)
            // PSA spec §6.2: marks the packet for multicast replication.
            "multicast" -> {
              val ostd = eval.evalArg(0) as StructVal
              val group = eval.evalArg(1) as BitVal
              ostd.fields["drop"] = BoolVal(false)
              ostd.fields["multicast_group"] = group
              UnitVal
            }
            "ingress_drop",
            "egress_drop" -> {
              val ostd = eval.evalArg(0) as StructVal
              ostd.fields["drop"] = BoolVal(true)
              UnitVal
            }
            else -> error("PSA extern '${call.name}' is not implemented")
          }
        is ExternCall.Method ->
          handleCommonExternMethod(
            call,
            eval,
            pipeline.tableStore,
            pipeline.externInstances,
            checksumState,
            PSA_HASH_ALGORITHMS,
          )
            ?: error(
              "PSA extern method '${call.externType}.${call.method}' is not implemented" +
                " (on ${call.instanceName})"
            )
      }
    }
  }

  // Hash helpers (evalGetHash, hashDataArg, sumWords) live in Hash.kt.
  // Action selector fork handling (handleActionSelectorFork) and trace helpers
  // (buildContinuationsTree, buildChoiceTree) live in ArchitectureHelpers.kt and TraceHelpers.kt.

  private companion object {
    // PSA_PacketPath_t enum values (PSA spec §6.1).
    const val PACKET_PATH_NORMAL = "NORMAL"
    const val PACKET_PATH_NORMAL_UNICAST = "NORMAL_UNICAST"
    const val PACKET_PATH_NORMAL_MULTICAST = "NORMAL_MULTICAST"
    const val PACKET_PATH_CLONE_I2E = "CLONE_I2E"
    const val PACKET_PATH_CLONE_E2E = "CLONE_E2E"
    const val PACKET_PATH_RESUBMIT = "RESUBMIT"
    const val PACKET_PATH_RECIRCULATE = "RECIRCULATE"

    /** Maps PSA_HashAlgorithm_t enum members to the internal algorithm names used by Hash.kt. */
    val PSA_HASH_ALGORITHMS =
      mapOf(
        "IDENTITY" to "identity",
        "CRC16" to "crc16",
        "CRC32" to "crc32",
        "ONES_COMPLEMENT16" to "csum16",
      )

    /** PSA_PORT_RECIRCULATE (psa.p4: `const PortId_t PSA_PORT_RECIRCULATE = 32w0xfffffffa`). */
    const val PSA_PORT_RECIRCULATE_LONG = 0xFFFFFFFAL
    val PSA_PORT_RECIRCULATE_UINT = PSA_PORT_RECIRCULATE_LONG.toUInt()
  }
}
