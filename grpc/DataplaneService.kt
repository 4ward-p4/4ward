package fourward.grpc

import com.google.protobuf.ByteString
import fourward.DataplaneGrpcKt
import fourward.InjectPacketRequest
import fourward.InjectPacketResponse
import fourward.InjectPacketsResponse
import fourward.InputPacket
import fourward.Outcome
import fourward.OutputPacket
import fourward.PipelineConfig
import fourward.PrePacketHookInvocation
import fourward.PrePacketHookResponse
import fourward.ProcessPacketResult as ProcessPacketResultProto
import fourward.Reproducer
import fourward.SubscribeResultsRequest
import fourward.SubscribeResultsResponse
import fourward.SubscriptionActive
import fourward.TraceTree
import fourward.simulator.DataplanePort
import fourward.simulator.RegisterSeedDependency
import fourward.simulator.TableStore
import fourward.simulator.extractReproducerEntities
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Dataplane gRPC service: injects packets into the simulator and returns output packets with dual
 * port encoding (dataplane + P4Runtime) and P4RT-enriched traces.
 *
 * Packet processing (lock, hook, simulator, subscriber dispatch) is handled by [PacketBroker]. This
 * service is responsible for gRPC protocol: request/response translation, port resolution, trace
 * enrichment, and hook stream management.
 *
 * @param pipelineSnapshot provides the currently loaded pipeline state, or null if no pipeline is
 *   loaded.
 */
class DataplaneService(
  private val broker: PacketBroker,
  private val pipelineSnapshot: () -> PipelineSnapshot? = { null },
) : DataplaneGrpcKt.DataplaneCoroutineImplBase() {

  data class PipelineSnapshot(
    val config: PipelineConfig,
    val tableStore: TableStore,
    val typeTranslator: TypeTranslator?,
    val packetHeaderCodec: PacketHeaderCodec?,
  )

  override suspend fun injectPacket(request: InjectPacketRequest): InjectPacketResponse {
    val (enrichedResult, _) = processAndEnrich(request, "InjectPacket")
    return InjectPacketResponse.newBuilder()
      .addAllPossibleOutcomes(enrichedResult.proto.possibleOutcomesList)
      .setTrace(enrichedResult.proto.trace)
      .build()
  }

  override suspend fun getReproducer(request: InjectPacketRequest): Reproducer {
    if (pipelineSnapshot() == null) {
      throw Status.FAILED_PRECONDITION.withDescription(
          "No pipeline loaded — call SetForwardingPipelineConfig first"
        )
        .asException()
    }
    val (enrichedResult, pipeline) = processAndEnrich(request, "GetReproducer")
    // pipeline is non-null: the pre-check above rejects the no-pipeline case, and pipeline
    // unload between the check and here requires a concurrent SetForwardingPipelineConfig
    // that replaces the pipeline (not unloads it — there's no "unload" API).
    return Reproducer.newBuilder()
      .setPipelineConfig(pipeline!!.config)
      .addAllEntities(
        extractReproducerEntities(
          enrichedResult.rawTrace,
          enrichedResult.forwardingSnapshot,
          pipeline.tableStore,
          pipeline.config.device.staticEntries.updatesList,
          enrichedResult.registerSeedDependencies,
        )
      )
      .setResult(enrichedResult.proto)
      .build()
  }

  private class EnrichedResult(
    val rawTrace: TraceTree,
    val forwardingSnapshot: TableStore.ForwardingSnapshot,
    val registerSeedDependencies: List<RegisterSeedDependency>,
    val proto: ProcessPacketResultProto,
  )

  private suspend fun processAndEnrich(
    request: InjectPacketRequest,
    rpcName: String,
  ): Pair<EnrichedResult, PipelineSnapshot?> {
    val pipeline = pipelineSnapshot()
    val translator = pipeline?.typeTranslator
    val ingressPort = resolveIngressPort(request, translator)
    val payload = request.payload.toByteArray()
    // Translate anything thrown past this point into INTERNAL with a
    // description, so the client never sees a bare UNKNOWN. See #499.
    @Suppress("TooGenericExceptionCaught")
    try {
      val result = broker.processPacket(ingressPort, payload, request.tag)
      val enrichedResult =
        EnrichedResult(
          rawTrace = result.trace,
          forwardingSnapshot = result.forwardingSnapshot,
          registerSeedDependencies = result.registerSeedDependencies,
          proto =
            enrichResult(
              ingressPort,
              payload,
              request.tag,
              result.possibleOutcomes,
              result.trace,
              pipeline,
            ),
        )
      return enrichedResult to pipeline
    } catch (e: StatusException) {
      throw e // already has a proper status; don't rewrap.
    } catch (e: IllegalArgumentException) {
      val detail = listOfNotNull("$rpcName failed", e.message).joinToString(": ")
      throw Status.INVALID_ARGUMENT.withDescription(detail).withCause(e).asException()
    } catch (e: Exception) {
      val detail = listOfNotNull("$rpcName failed", e.message).joinToString(": ")
      throw Status.INTERNAL.withDescription(detail).withCause(e).asException()
    }
  }

  override suspend fun injectPackets(requests: Flow<InjectPacketRequest>): InjectPacketsResponse {
    val translator = pipelineSnapshot()?.typeTranslator
    broker.withHookOnce { processPacket ->
      requests.collect { request ->
        val port = resolveIngressPort(request, translator)
        val payload = request.payload.toByteArray()
        val tag = request.tag
        processPacket(port, payload, tag)
      }
    }
    return InjectPacketsResponse.getDefaultInstance()
  }

  override fun subscribeResults(request: SubscribeResultsRequest): Flow<SubscribeResultsResponse> =
    channelFlow {
      send(
        SubscribeResultsResponse.newBuilder()
          .setActive(SubscriptionActive.getDefaultInstance())
          .build()
      )

      val handle =
        broker.subscribe { subResult ->
          try {
            val result =
              enrichResult(
                subResult.ingressPort,
                subResult.packet.copySemanticBytes(),
                subResult.tag,
                subResult.possibleOutcomes,
                subResult.trace,
                pipelineSnapshot(),
              )
            // Packet processing waits here when the gRPC subscriber is slow: SubscribeResults is
            // the lossless result channel for batch InjectPackets callers.
            send(SubscribeResultsResponse.newBuilder().setResult(result).build())
          } catch (
            @Suppress("TooGenericExceptionCaught") // Any translation/encoding failure should
            e: Exception // terminate this subscription stream, not crash the packet sender.
          ) {
            close(subscribeResultsFailure(e))
          }
        }

      awaitClose { handle.unsubscribe() }
    }

  private fun subscribeResultsFailure(e: Exception): StatusException {
    if (e is StatusException) return e
    val detail = buildString {
      append(
        "SubscribeResults failed while enriching a packet result; check PacketIn " +
          "@controller_header metadata and emitted CPU-port payload"
      )
      e.message?.let {
        append(": ")
        append(it)
      }
    }
    return Status.INTERNAL.withDescription(detail).withCause(e).asException()
  }

  override fun registerPrePacketHook(
    requests: Flow<PrePacketHookResponse>
  ): Flow<PrePacketHookInvocation> = callbackFlow {
    val invocations = Channel<PrePacketHookInvocation>(Channel.RENDEZVOUS)
    val responses = Channel<PrePacketHookResponse>(Channel.RENDEZVOUS)
    val newHook = PacketBroker.Hook(invocations, responses)

    if (!broker.registerHook(newHook)) {
      throw Status.ALREADY_EXISTS.withDescription("A pre-packet hook is already registered")
        .asException()
    }

    // Synchronisation handshake: tell the client the hook is live. From the
    // client's perspective, every packet emitted *after* this message is
    // guaranteed to flow through the hook — no timing guesses needed.
    // Mirrors the SubscriptionActive sentinel that subscribeResults emits.
    //
    // The sentinel must be sent *before* the forwarder is launched. Sending it
    // through the `invocations` channel after launching the forwarder would be
    // racier, not safer: the broker may already be firing into the rendezvous
    // channel from another thread (the hook is live the moment registerHook
    // returns true), so a concurrent packet event could win the rendezvous
    // before our sentinel does. Bypassing the channel entirely keeps ordering
    // tied to lexical structure rather than thread races.
    send(
      PrePacketHookInvocation.newBuilder()
        .setRegistered(PrePacketHookInvocation.HookRegistered.getDefaultInstance())
        .build()
    )

    // Forward invocations from the internal channel to the gRPC stream.
    launch {
      for (invocation in invocations) {
        send(invocation)
      }
    }

    // Forward responses from the gRPC stream to the internal channel.
    launch { requests.collect { response -> responses.send(response) } }

    awaitClose {
      broker.deregisterHook()
      invocations.close()
      responses.close()
    }
  }

  private fun resolveIngressPort(
    request: InjectPacketRequest,
    translator: TypeTranslator?,
  ): DataplanePort =
    when (request.ingressPortCase) {
      InjectPacketRequest.IngressPortCase.DATAPLANE_INGRESS_PORT ->
        DataplanePort.fromProto(request.dataplaneIngressPort)
      InjectPacketRequest.IngressPortCase.P4RT_INGRESS_PORT ->
        (translator?.portTranslator
            ?: throw missingPortTranslation(request.p4RtIngressPort, translator))
          .p4rtToDataplane(request.p4RtIngressPort)
      InjectPacketRequest.IngressPortCase.INGRESSPORT_NOT_SET,
      null -> DataplanePort.fromProto(0)
    }
}

// Same FAILED_PRECONDITION covers two distinct remediations — "load a pipeline"
// vs. "use a pipeline whose port type has @p4runtime_translation" — so the
// message has to say which branch the caller is on.
private fun missingPortTranslation(
  requestedPort: ByteString,
  translator: TypeTranslator?,
): StatusException {
  val reason =
    if (translator == null) {
      "no pipeline is loaded — call SetForwardingPipelineConfig first"
    } else {
      "the loaded pipeline's port type has no @p4runtime_translation — compile " +
        "with a port type that carries the annotation (e.g. via v1model_sai.p4)"
    }
  return Status.FAILED_PRECONDITION.withDescription(
      "InjectPacket uses p4rt_ingress_port (0x${requestedPort.toHex()}), but $reason. " +
        "Alternatively, use dataplane_ingress_port (numeric) to bypass P4Runtime port translation."
    )
    .asException()
}

private fun enrichResult(
  ingressPort: DataplanePort,
  payload: ByteArray,
  tag: Long,
  possibleOutcomes: List<List<OutputPacket>>,
  trace: TraceTree,
  snapshot: DataplaneService.PipelineSnapshot?,
): ProcessPacketResultProto {
  val translator = snapshot?.typeTranslator
  val pt = translator?.portTranslator
  val codec = snapshot?.packetHeaderCodec
  return ProcessPacketResultProto.newBuilder()
    .setInputPacket(
      InputPacket.newBuilder()
        .setDataplaneIngressPort(ingressPort.protoValue)
        .apply { pt?.dataplaneToP4rt(ingressPort)?.let { setP4RtIngressPort(it) } }
        .setPayload(ByteString.copyFrom(payload))
        .setTag(tag)
    )
    .addAllPossibleOutcomes(
      possibleOutcomes.map { outcome ->
        Outcome.newBuilder()
          .addAllPackets(outcome.map { it.toDualEncoded(translator, codec) })
          .build()
      }
    )
    .setTrace(enrichTrace(trace, translator))
    .build()
}

private fun enrichTrace(trace: TraceTree, translator: TypeTranslator?): TraceTree =
  translator?.let { TraceEnricher.enrich(trace, it) } ?: trace

/**
 * Returns a copy of this [OutputPacket] enriched with P4Runtime fields: the translated port ID, and
 * for CPU-port outputs, the decoded [PacketIn][p4.v1.P4RuntimeOuterClass.PacketIn].
 */
private fun OutputPacket.toDualEncoded(
  translator: TypeTranslator?,
  codec: PacketHeaderCodec?,
): OutputPacket {
  val rawPayload = payload
  val pt = translator?.portTranslator
  val egressPort = DataplanePort.fromProto(dataplaneEgressPort)
  return OutputPacket.newBuilder()
    .setDataplaneEgressPort(dataplaneEgressPort)
    .setPayload(rawPayload)
    .apply {
      // Egress ports may come from P4 pipeline logic (e.g. the CPU port, hardcoded
      // by the architecture) that was never forward-allocated via a P4Runtime Write,
      // so a missing reverse mapping is expected — same as for ingress ports above.
      pt?.dataplaneToP4rt(egressPort)?.let { setP4RtEgressPort(it) }
      if (codec != null && egressPort == codec.cpuPort) {
        val rawPacketIn = codec.decodePacketIn(rawPayload)
        setPacketIn(translator?.translatePacketIn(rawPacketIn) ?: rawPacketIn)
      }
    }
    .build()
}
