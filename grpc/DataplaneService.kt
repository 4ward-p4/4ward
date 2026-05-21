package fourward.grpc

import com.google.protobuf.ByteString
import fourward.DataplaneGrpcKt
import fourward.InjectPacketRequest
import fourward.InjectPacketResponse
import fourward.InjectPacketsResponse
import fourward.InputPacket
import fourward.OutputPacket
import fourward.PacketSet
import fourward.PipelineConfig
import fourward.PrePacketHookInvocation
import fourward.PrePacketHookResponse
import fourward.ProcessPacketResult as ProcessPacketResultProto
import fourward.Reproducer
import fourward.SubscribeResultsRequest
import fourward.SubscribeResultsResponse
import fourward.SubscriptionActive
import fourward.TraceTree
import fourward.simulator.TableStore
import fourward.simulator.extractReproducerEntities
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
  )

  override suspend fun injectPacket(request: InjectPacketRequest): InjectPacketResponse {
    val (enrichedResult, _) = processAndEnrich(request, "InjectPacket")
    return InjectPacketResponse.newBuilder()
      .addAllPossibleOutcomes(enrichedResult.possibleOutcomes)
      .setTrace(enrichedResult.trace)
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
        )
      )
      .setResult(enrichedResult.toProcessPacketResult())
      .build()
  }

  private class EnrichedResult(
    val ingressPort: Int,
    val payload: ByteArray,
    val rawTrace: TraceTree,
    val forwardingSnapshot: TableStore.ForwardingSnapshot,
    val trace: TraceTree,
    val possibleOutcomes: List<PacketSet>,
  ) {
    fun toProcessPacketResult(): ProcessPacketResultProto =
      ProcessPacketResultProto.newBuilder()
        .setInputPacket(
          InputPacket.newBuilder()
            .setDataplaneIngressPort(ingressPort)
            .setPayload(ByteString.copyFrom(payload))
        )
        .setTrace(trace)
        .addAllPossibleOutcomes(possibleOutcomes)
        .build()
  }

  private fun processAndEnrich(
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
      val result = broker.processPacket(ingressPort, payload)
      val pt = translator?.portTranslator
      val enrichedResult =
        EnrichedResult(
          ingressPort = ingressPort,
          payload = payload,
          rawTrace = result.trace,
          forwardingSnapshot = result.forwardingSnapshot,
          trace = enrichTrace(result.trace, translator),
          possibleOutcomes =
            result.possibleOutcomes.map { world ->
              PacketSet.newBuilder().addAllPackets(world.map { it.toDualEncoded(pt) }).build()
            },
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
      val futures = mutableListOf<java.util.concurrent.ForkJoinTask<*>>()
      kotlinx.coroutines.runBlocking {
        requests.collect { request ->
          val port = resolveIngressPort(request, translator)
          val payload = request.payload.toByteArray()
          futures.add(
            java.util.concurrent.ForkJoinPool.commonPool().submit { processPacket(port, payload) }
          )
        }
      }
      futures.forEach { it.join() }
    }
    return InjectPacketsResponse.getDefaultInstance()
  }

  override fun subscribeResults(request: SubscribeResultsRequest): Flow<SubscribeResultsResponse> =
    callbackFlow {
      send(
        SubscribeResultsResponse.newBuilder()
          .setActive(SubscriptionActive.getDefaultInstance())
          .build()
      )

      val handle =
        broker.subscribe { subResult ->
          try {
            val translator = pipelineSnapshot()?.typeTranslator
            val pt = translator?.portTranslator
            val result =
              ProcessPacketResultProto.newBuilder()
                .setInputPacket(
                  InputPacket.newBuilder()
                    .setDataplaneIngressPort(subResult.ingressPort)
                    .apply {
                      // Ingress ports may come from InjectPacket.dataplane_ingress_port (raw int,
                      // never forward-allocated), so a missing reverse mapping is expected.
                      pt?.dataplaneToP4rt(subResult.ingressPort)?.let { setP4RtIngressPort(it) }
                    }
                    .setPayload(ByteString.copyFrom(subResult.payload))
                )
                .addAllPossibleOutcomes(
                  subResult.possibleOutcomes.map { world ->
                    PacketSet.newBuilder().addAllPackets(world.map { it.toDualEncoded(pt) }).build()
                  }
                )
                .setTrace(enrichTrace(subResult.trace, translator))
                .build()
            // Close the stream if the channel is full or closed — silently dropping a packet
            // result would violate the SubscribeResults contract.
            val sendResult =
              trySend(SubscribeResultsResponse.newBuilder().setResult(result).build())
            if (sendResult.isFailure) close(sendResult.exceptionOrNull())
          } catch (
            @Suppress("TooGenericExceptionCaught") // Any translation/encoding failure should
            e: Exception // terminate this subscription stream, not crash the packet sender.
          ) {
            close(e)
          }
        }

      awaitClose { handle.unsubscribe() }
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

  private fun resolveIngressPort(request: InjectPacketRequest, translator: TypeTranslator?): Int =
    when (request.ingressPortCase) {
      InjectPacketRequest.IngressPortCase.DATAPLANE_INGRESS_PORT -> request.dataplaneIngressPort
      InjectPacketRequest.IngressPortCase.P4RT_INGRESS_PORT ->
        (translator?.portTranslator
            ?: throw missingPortTranslation(request.p4RtIngressPort, translator))
          .p4rtToDataplane(request.p4RtIngressPort)
      InjectPacketRequest.IngressPortCase.INGRESSPORT_NOT_SET,
      null -> 0
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

private fun enrichTrace(trace: TraceTree, translator: TypeTranslator?): TraceTree =
  translator?.let { TraceEnricher.enrich(trace, it) } ?: trace

/**
 * Returns a copy of this [OutputPacket] enriched with the P4Runtime port ID.
 *
 * When a [PortTranslator] is present, every egress port must have a reverse mapping — either from
 * an explicit `@p4runtime_translation_mappings` entry or auto-allocated by a prior P4Runtime Write.
 */
private fun OutputPacket.toDualEncoded(pt: PortTranslator?): OutputPacket =
  OutputPacket.newBuilder()
    .setDataplaneEgressPort(dataplaneEgressPort)
    .apply {
      if (pt != null) {
        val p4rtPort =
          pt.dataplaneToP4rt(dataplaneEgressPort)
            ?: error(
              "No P4Runtime port name for dataplane egress port $dataplaneEgressPort. " +
                "Ensure the port has a @p4runtime_translation_mappings entry or was " +
                "auto-allocated via a prior P4Runtime Write."
            )
        setP4RtEgressPort(p4rtPort)
      }
    }
    .setPayload(payload)
    .build()
