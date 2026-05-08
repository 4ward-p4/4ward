package fourward.p4runtime

import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.TextFormat
import com.google.rpc.Code
import fourward.DeviceConfig
import fourward.OutputPacket
import fourward.PipelineConfig
import fourward.simulator.Simulator
import fourward.simulator.WriteResult
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import p4.v1.P4RuntimeGrpcKt
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.CapabilitiesRequest
import p4.v1.P4RuntimeOuterClass.CapabilitiesResponse
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate
import p4.v1.P4RuntimeOuterClass.PacketIn
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Uint128
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest
import p4.v1.P4RuntimeOuterClass.WriteResponse

/**
 * Compares two [Uint128] values as unsigned 128-bit integers. Returns negative if [a] < [b], zero
 * if equal, positive if [a] > [b].
 */
fun compareUint128(a: Uint128, b: Uint128): Int {
  val highCmp = a.high.toULong().compareTo(b.high.toULong())
  if (highCmp != 0) return highCmp
  return a.low.toULong().compareTo(b.low.toULong())
}

/**
 * P4Runtime gRPC service backed by a 4ward [Simulator].
 *
 * Supports multi-controller arbitration per P4Runtime spec §5: the controller with the highest
 * non-zero election_id is primary and may write; all controllers may read. Demotion notifications
 * are sent when a higher election_id displaces the current primary, and automatic promotion occurs
 * when the primary disconnects.
 */
class P4RuntimeService(
  private val simulator: Simulator,
  private val broker: PacketBroker,
  private val constraintValidatorBinary: Path? = null,
  /**
   * Serializes control-plane writes (Write RPC, SetForwardingPipelineConfig, hook updates).
   * Data-plane reads are lock-free — they read from the published [TableStore.snapshot]. Shared
   * with [PacketBroker] so that pre-packet hook updates are atomic with other writes.
   */
  private val writeMutex: Mutex = Mutex(),
  private val deviceId: Long = DEFAULT_DEVICE_ID,
  private val cpuPortConfig: CpuPortConfig = CpuPortConfig.Auto,
  private val disableRefersToChecking: Boolean = false,
  private val disableP4ConstraintsChecking: Boolean = false,
) : P4RuntimeGrpcKt.P4RuntimeCoroutineImplBase(), Closeable {

  /** Bundled pipeline state — atomically swapped on pipeline load to avoid torn reads. */
  private data class PipelineState(
    val config: PipelineConfig,
    val cookie: ForwardingPipelineConfig.Cookie,
    val typeTranslator: TypeTranslator?,
    val writeValidator: WriteValidator,
    val referenceValidator: ReferenceValidator?,
    val constraintValidator: ConstraintValidator?,
    val packetHeaderCodec: PacketHeaderCodec?,
    val entityReader: EntityReader,
    val roleMap: RoleMap,
  )

  @Volatile private var pipeline: PipelineState? = null

  /** Type translator for the currently loaded pipeline, or null if unavailable. */
  val typeTranslator: TypeTranslator?
    get() = pipeline?.typeTranslator

  // Only accessed under writeMutex; @Volatile not needed.
  private var savedPipeline: PipelineState? = null

  // ---------------------------------------------------------------------------
  // Arbitration state (P4Runtime spec §5)
  // ---------------------------------------------------------------------------

  /** Per-stream controller state, tracked for demotion/promotion notifications. */
  private data class ControllerStream(
    val roleName: String, // empty = default role
    val electionId: Uint128,
    val notifications: SendChannel<StreamMessageResponse>,
  )

  /** Per-role arbitration state. Each role has its own independent primary election. */
  private data class RoleElection(
    val primaryElectionId: Uint128? = null,
    val arbitrationOccurred: Boolean = false,
  )

  private val arbitrationMutex = Mutex()
  private val controllers = mutableMapOf<Any, ControllerStream>()

  // Per-role election state: key is role name (empty = default role).
  // Modified under arbitrationMutex, but read without it by requirePrimaryOrNoArbitration
  // (which runs under the write lock). ConcurrentHashMap ensures safe concurrent reads.
  private val roleElections = java.util.concurrent.ConcurrentHashMap<String, RoleElection>()

  private fun requirePipeline(): PipelineState =
    pipeline ?: throw Status.FAILED_PRECONDITION.withDescription(NO_PIPELINE_MESSAGE).asException()

  // ---------------------------------------------------------------------------
  // SetForwardingPipelineConfig
  // ---------------------------------------------------------------------------

  override suspend fun setForwardingPipelineConfig(
    request: SetForwardingPipelineConfigRequest
  ): SetForwardingPipelineConfigResponse =
    writeMutex.withLock {
      requireDeviceId(request.deviceId)
      requirePrimaryOrNoArbitration(request.electionId, request.role)
      when (request.action) {
        SetForwardingPipelineConfigRequest.Action.VERIFY ->
          verifyPipeline(request.config).also { it.constraintValidator?.close() }
        SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT ->
          commitPipeline(verifyPipeline(request.config))
        SetForwardingPipelineConfigRequest.Action.VERIFY_AND_SAVE -> {
          savedPipeline?.constraintValidator?.close()
          savedPipeline = verifyPipeline(request.config)
        }
        SetForwardingPipelineConfigRequest.Action.COMMIT -> {
          val saved =
            savedPipeline
              ?: throw Status.FAILED_PRECONDITION.withDescription(
                  "COMMIT requires a previously saved pipeline (VERIFY_AND_SAVE)"
                )
                .asException()
          commitPipeline(saved)
          savedPipeline = null
        }
        SetForwardingPipelineConfigRequest.Action.RECONCILE_AND_COMMIT ->
          reconcileAndCommit(request.config)
        SetForwardingPipelineConfigRequest.Action.UNSPECIFIED,
        SetForwardingPipelineConfigRequest.Action.UNRECOGNIZED ->
          throw Status.INVALID_ARGUMENT.withDescription(
              "unrecognized SetForwardingPipelineConfig action ${request.actionValue} " +
                "(valid values: VERIFY (1), VERIFY_AND_COMMIT (2), " +
                "VERIFY_AND_SAVE (3), COMMIT (4), RECONCILE_AND_COMMIT (5))"
            )
            .asException()
      }
      SetForwardingPipelineConfigResponse.getDefaultInstance()
    }

  /**
   * Decodes the opaque [p4_device_config] bytes as a [DeviceConfig], accepting either binary or
   * text-format protobuf. Lets callers paste a text-format DeviceConfig into a hand-edited `.txtpb`
   * ForwardingPipelineConfig without first re-serializing it as binary.
   */
  private fun parseDeviceConfig(bytes: ByteString): DeviceConfig {
    val binaryError =
      try {
        return DeviceConfig.parseFrom(bytes)
      } catch (e: InvalidProtocolBufferException) {
        e
      }
    try {
      val builder = DeviceConfig.newBuilder()
      TextFormat.merge(bytes.toStringUtf8(), builder)
      return builder.build()
    } catch (e: TextFormat.ParseException) {
      throw Status.INVALID_ARGUMENT.withDescription(
          "p4_device_config is not a valid DeviceConfig proto (expected serialized " +
            "fourward.DeviceConfig in binary or text format). " +
            "binary parse: ${binaryError.message}; text parse: ${e.message}"
        )
        .withCause(e)
        .asException()
    }
  }

  /**
   * Validates the forwarding pipeline config and builds a [PipelineState] without activating it.
   * Used by VERIFY, VERIFY_AND_COMMIT, and VERIFY_AND_SAVE.
   */
  private fun verifyPipeline(fwdConfig: ForwardingPipelineConfig): PipelineState {
    if (!fwdConfig.hasP4Info() || fwdConfig.p4DeviceConfig.isEmpty) {
      throw Status.INVALID_ARGUMENT.withDescription(
          "ForwardingPipelineConfig must include p4info and p4_device_config"
        )
        .asException()
    }

    val deviceConfig = parseDeviceConfig(fwdConfig.p4DeviceConfig)

    val pipelineConfig =
      PipelineConfig.newBuilder().setP4Info(fwdConfig.p4Info).setDevice(deviceConfig).build()

    val typeTranslator =
      TypeTranslator.create(
        fwdConfig.p4Info,
        deviceConfig.translationsList,
        portTypeName = deviceConfig.behavioral.architecture.portTypeName,
      )

    return PipelineState(
      config = pipelineConfig,
      cookie = fwdConfig.cookie,
      typeTranslator = typeTranslator,
      writeValidator = WriteValidator(pipelineConfig.p4Info),
      referenceValidator =
        if (disableRefersToChecking) null else ReferenceValidator.create(fwdConfig.p4Info),
      constraintValidator =
        if (disableP4ConstraintsChecking) {
          null
        } else {
          constraintValidatorBinary?.let {
            try {
              ConstraintValidator.create(fwdConfig.p4Info, it)
            } catch (e: ConstraintValidatorException) {
              throw Status.INTERNAL.withDescription("constraint validation failed: ${e.message}")
                .withCause(e)
                .asException()
            }
          }
        },
      packetHeaderCodec =
        when (cpuPortConfig) {
          is CpuPortConfig.Disabled -> null
          is CpuPortConfig.Auto ->
            PacketHeaderCodec.create(fwdConfig.p4Info, deviceConfig.behavioral)
          is CpuPortConfig.Override ->
            PacketHeaderCodec.create(fwdConfig.p4Info, deviceConfig.behavioral, cpuPortConfig.port)
        },
      // Placeholder — EntityReader needs simulator name maps that are only
      // available after loadPipeline; commitPipeline creates the real one.
      entityReader = EntityReader.EMPTY,
      roleMap = RoleMap.create(fwdConfig.p4Info),
    )
  }

  /**
   * RECONCILE_AND_COMMIT: load a new pipeline while preserving forwarding state.
   *
   * Checks that every table with installed entries is schema-compatible (same match fields and
   * action set) in the new pipeline. Tables without entries can change freely. Non-table state
   * (counters, registers, meters) is reset; PRE entries and action profiles are preserved.
   */
  private fun reconcileAndCommit(fwdConfig: ForwardingPipelineConfig) {
    val newState = verifyPipeline(fwdConfig)
    val oldState = pipeline
    if (oldState == null) {
      commitPipeline(newState)
      return
    }

    // Identical pipeline: update P4Runtime-layer state (cookie, validators) without touching
    // the simulator. This is the common case for DVaaS-style reloads.
    if (oldState.config == newState.config) {
      oldState.constraintValidator?.close()
      pipeline = newState.copy(entityReader = oldState.entityReader)
      return
    }

    val populatedAliases = simulator.populatedTableAliases
    val oldTablesByAlias = oldState.config.p4Info.tablesList.associateBy { it.preamble.alias }
    val newTablesByAlias = newState.config.p4Info.tablesList.associateBy { it.preamble.alias }

    // Check schema compatibility for every table that has entries installed.
    for (alias in populatedAliases) {
      val oldTable = oldTablesByAlias[alias]
      val newTable = newTablesByAlias[alias]
      if (newTable == null) {
        throw Status.INVALID_ARGUMENT.withDescription(
            "RECONCILE_AND_COMMIT: table '$alias' has entries but is absent from the new pipeline"
          )
          .asException()
      }
      if (oldTable != null && !tablesSchemaCompatible(oldTable, newTable)) {
        throw Status.INVALID_ARGUMENT.withDescription(
            "RECONCILE_AND_COMMIT: table '$alias' has entries but its schema changed"
          )
          .asException()
      }
    }

    activatePipeline(newState) { simulator.loadPipelinePreservingEntries(it) }
  }

  /**
   * Two tables are schema-compatible if they have the same match fields (same IDs, bitwidths, and
   * match types) and the same set of action references (by ID). Assumes tables are already matched
   * by p4info alias; only verifies field and action compatibility.
   */
  private fun tablesSchemaCompatible(
    old: p4.config.v1.P4InfoOuterClass.Table,
    new: p4.config.v1.P4InfoOuterClass.Table,
  ): Boolean {
    if (old.matchFieldsList.size != new.matchFieldsList.size) return false
    val oldFields = old.matchFieldsList.sortedBy { it.id }
    val newFields = new.matchFieldsList.sortedBy { it.id }
    for ((o, n) in oldFields.zip(newFields)) {
      if (o.id != n.id || o.bitwidth != n.bitwidth || o.matchType != n.matchType) return false
    }
    val oldActions = old.actionRefsList.map { it.id }.toSet()
    val newActions = new.actionRefsList.map { it.id }.toSet()
    return oldActions == newActions
  }

  /** Loads a verified pipeline into the simulator and activates it. */
  private fun commitPipeline(state: PipelineState) {
    activatePipeline(state) { simulator.loadPipeline(it) }
  }

  /**
   * Common tail for [commitPipeline] and [reconcileAndCommit]: loads the pipeline into the
   * simulator via [load], creates an [EntityReader] from the simulator's post-load name maps, and
   * atomically swaps the pipeline state.
   */
  private fun activatePipeline(state: PipelineState, load: (PipelineConfig) -> Unit) {
    try {
      load(state.config)
    } catch (e: IllegalArgumentException) {
      throw Status.INVALID_ARGUMENT.withDescription("simulator rejected pipeline: ${e.message}")
        .withCause(e)
        .asException()
    }
    pipeline?.constraintValidator?.close()
    val entityReader =
      EntityReader.create(state.config.p4Info, simulator.tableNameById, simulator.actionNameById)
    pipeline = state.copy(entityReader = entityReader)
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /**
   * Applies P4Runtime updates from the pre-packet hook. Called by [PacketBroker] while holding the
   * [writeMutex]. Uses the default role (full access) since these are server-internal updates.
   */
  fun applyHookUpdates(updates: List<Update>) {
    val state = requirePipeline()
    writeAtomic(updates, state, roleName = "")
    simulator.publishSnapshot()
  }

  /** Returns the P4Info from the loaded pipeline, or null if no pipeline is loaded. */
  fun p4Info(): p4.config.v1.P4InfoOuterClass.P4Info? = pipeline?.config?.p4Info

  /**
   * Returns all P4Runtime entities currently installed. Called by [DataplaneService] while holding
   * the write lock, so no additional locking is needed.
   */
  fun readAllEntities(): List<Entity> {
    val state = pipeline ?: return emptyList()
    val tableEntities =
      state.entityReader.readTableEntities(TableEntry.getDefaultInstance(), simulator)
    val preEntities =
      simulator.readEntries(
        listOf(
          Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.getDefaultInstance()
            )
            .build()
        )
      )
    val result = mutableListOf<Entity>()
    result.addAll(tableEntities)
    result.addAll(preEntities)
    return result
  }

  override suspend fun write(request: WriteRequest): WriteResponse =
    writeMutex.withLock {
      requireDeviceId(request.deviceId)
      val state = requirePipeline()
      val roleName = request.role // empty = default role
      requirePrimaryOrNoArbitration(request.electionId, roleName)
      when (request.atomicity) {
        WriteRequest.Atomicity.CONTINUE_ON_ERROR ->
          writeContinueOnError(request.updatesList, state, roleName)
        WriteRequest.Atomicity.UNRECOGNIZED ->
          throw Status.INVALID_ARGUMENT.withDescription(
              "unrecognized write atomicity ${request.atomicityValue} " +
                "(valid values: CONTINUE_ON_ERROR (1), ROLLBACK_ON_ERROR (2), DATAPLANE_ATOMIC (3))"
            )
            .asException()
        // P4Runtime spec §12.2: ROLLBACK_ON_ERROR and DATAPLANE_ATOMIC both guarantee
        // all-or-none semantics. DATAPLANE_ATOMIC additionally requires data-plane atomicity,
        // which we get for free because publishSnapshot() is an atomic pointer swap.
        WriteRequest.Atomicity.ROLLBACK_ON_ERROR,
        WriteRequest.Atomicity.DATAPLANE_ATOMIC -> writeAtomic(request.updatesList, state, roleName)
      }
    }

  /**
   * CONTINUE_ON_ERROR (P4Runtime spec §12.2): attempt all updates, report per-update status.
   *
   * Per §12.3: if any update fails, the gRPC status is UNKNOWN with one [P4RuntimeOuterClass.Error]
   * per update packed into `google.rpc.Status.details`.
   */
  private fun writeContinueOnError(
    updates: List<Update>,
    state: PipelineState,
    roleName: String,
  ): WriteResponse {
    val errors = ArrayList<P4RuntimeOuterClass.Error>(updates.size)
    var hasError = false
    for (rawUpdate in updates) {
      try {
        processUpdate(rawUpdate, state, roleName)
        errors.add(
          P4RuntimeOuterClass.Error.newBuilder()
            .setCanonicalCode(OK_CODE)
            .setSpace(P4RT_ERROR_SPACE)
            .setCode(OK_CODE)
            .build()
        )
      } catch (e: StatusException) {
        hasError = true
        errors.add(
          P4RuntimeOuterClass.Error.newBuilder()
            .setCanonicalCode(e.status.code.value())
            .setMessage(e.status.description ?: "")
            .setSpace(P4RT_ERROR_SPACE)
            .setCode(e.status.code.value())
            .build()
        )
      }
    }
    // Publish even on partial failure — successful updates must be visible to subsequent reads.
    simulator.publishSnapshot()
    if (hasError) {
      throw buildBatchError(errors)
    }
    return WriteResponse.getDefaultInstance()
  }

  /**
   * ROLLBACK_ON_ERROR / DATAPLANE_ATOMIC (P4Runtime spec §12.2): all-or-none semantics.
   *
   * Snapshots write-state before the batch and restores it if any update fails.
   */
  private fun writeAtomic(
    updates: List<Update>,
    state: PipelineState,
    roleName: String,
  ): WriteResponse {
    val checkpoint = simulator.snapshot()
    try {
      for (rawUpdate in updates) {
        processUpdate(rawUpdate, state, roleName)
      }
    } catch (e: StatusException) {
      simulator.restore(checkpoint)
      simulator.publishSnapshot()
      throw e
    }
    simulator.publishSnapshot()
    return WriteResponse.getDefaultInstance()
  }

  /** Validates and applies a single update. Throws [StatusException] on failure. */
  private fun processUpdate(rawUpdate: Update, state: PipelineState, roleName: String) {
    rejectUnsupportedEntity(rawUpdate.entity)
    requireEntityAccess(rawUpdate.entity, state.roleMap, roleName)
    // Validate against p4info before type translation so SDN-visible values
    // are checked at canonical widths (P4Runtime spec §8.3, §9.1).
    state.writeValidator.validate(rawUpdate)
    val canonicalUpdate = canonicalizeBytestrings(rawUpdate)
    val translator = state.typeTranslator?.takeIf { it.hasTranslations }
    val update =
      try {
        translator?.translateForWrite(canonicalUpdate) ?: canonicalUpdate
      } catch (e: TranslationException) {
        throw Status.INVALID_ARGUMENT.withDescription("type translation failed: ${e.message}")
          .withCause(e)
          .asException()
      }

    // Validate @refers_to referential integrity after translation so values
    // are in dataplane form (matching what's stored in the simulator).
    state.referenceValidator?.validate(
      update,
      simulator::hasTableEntryWithFieldValue,
      simulator::hasMulticastGroup,
    )

    // Validate constraints before forwarding to the simulator.
    // Skip DELETE — you can always remove an entry regardless of constraints.
    val validator = state.constraintValidator
    if (validator != null && update.entity.hasTableEntry() && update.type != Update.Type.DELETE) {
      val violation = validator.validateEntry(update.entity.tableEntry)
      if (violation != null) {
        throw Status.INVALID_ARGUMENT.withDescription(violation).asException()
      }
    }

    when (val result = simulator.writeEntry(update)) {
      is WriteResult.Success -> {}
      is WriteResult.AlreadyExists ->
        throw Status.ALREADY_EXISTS.withDescription(result.message).asException()
      is WriteResult.NotFound ->
        throw Status.NOT_FOUND.withDescription(result.message).asException()
      is WriteResult.InvalidArgument ->
        throw Status.INVALID_ARGUMENT.withDescription(result.message).asException()
      is WriteResult.ResourceExhausted ->
        throw Status.RESOURCE_EXHAUSTED.withDescription(result.message).asException()
    }
  }

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  override fun read(request: ReadRequest): Flow<ReadResponse> = flow {
    // Reads are lock-free — they read from the published snapshot. A concurrent write may
    // publish a new snapshot between entity reads, so two entities in the same Read RPC could
    // see different forwarding states. The P4Runtime spec does not require cross-entity read
    // atomicity, so this is acceptable.
    requireDeviceId(request.deviceId)
    val state = requirePipeline()
    val roleName = request.role // empty = default role
    // Table entries are assembled by EntityReader (P4Runtime presentation layer);
    // all other entity types are read directly from the simulator.
    val entities =
      request.entitiesList.flatMap { entity ->
        rejectUnsupportedEntity(entity)
        // For specific (non-wildcard) entities, check access upfront so the controller
        // gets a clear PERMISSION_DENIED. For wildcards, results are filtered post-read.
        requireEntityAccess(entity, state.roleMap, roleName)
        try {
          if (entity.hasTableEntry()) {
            state.entityReader.readTableEntities(entity.tableEntry, simulator)
          } else {
            simulator.readEntries(listOf(entity))
          }
        } catch (e: IllegalStateException) {
          throw Status.INTERNAL.withDescription("read failed: ${e.message}")
            .withCause(e)
            .asException()
        }
      }
    // Filter results by role for named-role controllers. This handles wildcard reads
    // (where the request entity doesn't carry a specific ID) by returning only entities
    // the controller's role can access.
    val filtered =
      if (roleName.isNotEmpty()) filterByRole(entities, state.roleMap, roleName) else entities
    if (filtered.isNotEmpty()) {
      val translated =
        state.typeTranslator
          ?.takeIf { it.hasTranslations }
          ?.let { t -> filtered.map { t.translateForRead(it) } } ?: filtered
      emit(ReadResponse.newBuilder().addAllEntities(translated).build())
    }
  }

  // ---------------------------------------------------------------------------
  // StreamChannel
  // ---------------------------------------------------------------------------

  override fun streamChannel(requests: Flow<StreamMessageRequest>): Flow<StreamMessageResponse> =
    channelFlow {
      val streamId = Any()
      val notifications = Channel<StreamMessageResponse>(Channel.UNLIMITED)

      // Forward async notifications (demotion/promotion) to the stream output.
      launch { for (msg in notifications) send(msg) }

      // Subscribe to the broker so that ANY packet egressing on the CPU port — regardless of
      // injection source (PacketOut, InjectPacket, etc.) — becomes PacketIn on this stream.
      // Per P4Runtime spec §16.1, PacketIn is sent to all controllers with an open stream.
      val packetInHandle =
        broker.subscribe { subResult ->
          try {
            for (response in
              buildPacketInResponses(subResult.possibleOutcomes.flatten(), subResult.ingressPort)) {
              trySend(response)
            }
          } catch (
            @Suppress("TooGenericExceptionCaught") // Any translation/encoding failure should
            e: Exception // terminate this P4RT stream, not crash the packet sender.
          ) {
            close(
              Status.INTERNAL.withDescription("PacketIn translation failed: ${e.message}")
                .withCause(e)
                .asException()
            )
          }
        }

      try {
        requests.collect { msg ->
          when (msg.updateCase) {
            StreamMessageRequest.UpdateCase.ARBITRATION ->
              send(handleArbitration(streamId, msg.arbitration, notifications))
            StreamMessageRequest.UpdateCase.PACKET -> handlePacketOut(msg.packet)
            StreamMessageRequest.UpdateCase.DIGEST_ACK ->
              throw Status.UNIMPLEMENTED.withDescription(DIGEST_NOT_SUPPORTED).asException()
            // P4Runtime spec §16: unrecognized stream messages get an error response.
            StreamMessageRequest.UpdateCase.OTHER,
            StreamMessageRequest.UpdateCase.UPDATE_NOT_SET,
            null ->
              send(
                StreamMessageResponse.newBuilder()
                  .setError(
                    P4RuntimeOuterClass.StreamError.newBuilder()
                      .setCanonicalCode(Code.INVALID_ARGUMENT_VALUE)
                      .setMessage("unrecognized stream message")
                      .setOther(P4RuntimeOuterClass.StreamOtherError.getDefaultInstance())
                  )
                  .build()
              )
          }
        }
      } finally {
        packetInHandle.unsubscribe()
        notifications.close()
        // Disconnect cleanup must complete even if the stream coroutine is being cancelled —
        // otherwise the controller stays in `controllers` and blocks role primary recomputation.
        withContext(NonCancellable) { handleDisconnect(streamId) }
      }
    }

  /**
   * Processes a PacketOut: translates metadata, serializes the packet header, and runs the packet
   * through the broker. PacketIn delivery is handled by the broker subscription in [streamChannel],
   * not here. The broker handles locking and the pre-packet hook.
   */
  private fun handlePacketOut(packet: p4.v1.P4RuntimeOuterClass.PacketOut) {
    val state = pipeline ?: return
    val translator = state.typeTranslator?.takeIf { it.hasTranslations }
    val codec = state.packetHeaderCodec
    val packetOut = translator?.translatePacketOut(packet) ?: packet

    // If the pipeline has a packet_out header, serialize metadata into a binary
    // header and prepend it. The parser expects the packet to arrive on the CPU
    // port with the header prepended.
    val (ingressPort, payload) =
      if (codec != null) {
        val header = codec.serializePacketOut(packetOut.metadataList)
        codec.cpuPort to (header + packetOut.payload.toByteArray())
      } else {
        extractIngressPort(packetOut.metadataList) to packetOut.payload.toByteArray()
      }

    try {
      broker.processPacket(ingressPort, payload)
    } catch (e: IllegalStateException) {
      throw Status.INTERNAL.withDescription("PacketOut processing failed: ${e.message}")
        .withCause(e)
        .asException()
    }
  }

  /**
   * Builds PacketIn [StreamMessageResponse]s for any outputs egressing on the CPU port. Returns an
   * empty list if there is no pipeline, no codec (`@controller_header`), or no CPU-port outputs.
   */
  private fun buildPacketInResponses(
    outputPackets: List<OutputPacket>,
    ingressPort: Int,
  ): List<StreamMessageResponse> {
    val state = pipeline ?: return emptyList()
    val codec = state.packetHeaderCodec ?: return emptyList()
    val translator = state.typeTranslator?.takeIf { it.hasTranslations }
    val cpuPort = codec.cpuPort
    return outputPackets
      .filter { it.dataplaneEgressPort == cpuPort }
      .map { outputPacket ->
        val metadata = codec.buildPacketInMetadata(ingressPort, outputPacket.dataplaneEgressPort)
        val rawPacketIn =
          PacketIn.newBuilder().setPayload(outputPacket.payload).addAllMetadata(metadata).build()
        StreamMessageResponse.newBuilder()
          .setPacket(translator?.translatePacketIn(rawPacketIn) ?: rawPacketIn)
          .build()
      }
  }

  /** Extracts ingress port from PacketOut metadata, defaulting to port 0. */
  private fun extractIngressPort(metadata: List<p4.v1.P4RuntimeOuterClass.PacketMetadata>): Int {
    val portMeta = metadata.find { it.metadataId == INGRESS_PORT_METADATA_ID }
    return portMeta?.value?.toUnsignedInt() ?: 0
  }

  // ---------------------------------------------------------------------------
  // GetForwardingPipelineConfig
  // ---------------------------------------------------------------------------

  override suspend fun getForwardingPipelineConfig(
    request: GetForwardingPipelineConfigRequest
  ): GetForwardingPipelineConfigResponse {
    requireDeviceId(request.deviceId)
    val state = requirePipeline()

    val fwdConfig = ForwardingPipelineConfig.newBuilder().setCookie(state.cookie)
    when (request.responseType) {
      GetForwardingPipelineConfigRequest.ResponseType.ALL -> {
        fwdConfig.setP4Info(state.config.p4Info)
        fwdConfig.setP4DeviceConfig(state.config.device.toByteString())
      }
      GetForwardingPipelineConfigRequest.ResponseType.UNRECOGNIZED ->
        throw Status.INVALID_ARGUMENT.withDescription(
            "unrecognized response type ${request.responseTypeValue} " +
              "(valid values: ALL (0), COOKIE_ONLY (1), " +
              "P4INFO_AND_COOKIE (2), DEVICE_CONFIG_AND_COOKIE (3))"
          )
          .asException()
      GetForwardingPipelineConfigRequest.ResponseType.COOKIE_ONLY -> {}
      GetForwardingPipelineConfigRequest.ResponseType.P4INFO_AND_COOKIE -> {
        fwdConfig.setP4Info(state.config.p4Info)
      }
      GetForwardingPipelineConfigRequest.ResponseType.DEVICE_CONFIG_AND_COOKIE -> {
        fwdConfig.setP4DeviceConfig(state.config.device.toByteString())
      }
    }

    return GetForwardingPipelineConfigResponse.newBuilder().setConfig(fwdConfig).build()
  }

  // ---------------------------------------------------------------------------
  // Capabilities
  // ---------------------------------------------------------------------------

  override suspend fun capabilities(request: CapabilitiesRequest): CapabilitiesResponse =
    CapabilitiesResponse.newBuilder().setP4RuntimeApiVersion(P4RUNTIME_API_VERSION).build()

  /**
   * Ensures the requester is the primary controller for the given role, or that no arbitration has
   * occurred for that role.
   *
   * P4Runtime spec §5, §15: only the primary (highest non-zero election_id) for a given role may
   * write. If no arbitration has occurred for the role, writes are allowed for backward
   * compatibility with single-controller clients.
   */
  private fun requirePrimaryOrNoArbitration(requestElectionId: Uint128, roleName: String = "") {
    val election = roleElections[roleName]
    if (election == null || !election.arbitrationOccurred) return
    val primary = election.primaryElectionId
    if (primary == null || requestElectionId != primary) {
      val id =
        primary?.let { if (it.high == 0L) "${it.low}" else "(${it.high}, ${it.low})" } ?: "none"
      val roleDesc = if (roleName.isEmpty()) "default role" else "role '$roleName'"
      throw Status.PERMISSION_DENIED.withDescription(
          "only the primary controller for $roleDesc (election_id=$id) may write"
        )
        .asException()
    }
  }

  // ---------------------------------------------------------------------------
  // Arbitration logic (P4Runtime spec §5)
  // ---------------------------------------------------------------------------

  /**
   * Processes a MasterArbitrationUpdate from a stream, updating controller tracking and sending
   * demotion/promotion notifications as needed.
   *
   * P4Runtime spec §15: each role has its own independent primary election.
   */
  private suspend fun handleArbitration(
    streamId: Any,
    arbitration: MasterArbitrationUpdate,
    notifications: SendChannel<StreamMessageResponse>,
  ): StreamMessageResponse {
    // P4Runtime spec §15: Role.config is target-specific policy we don't support.
    if (arbitration.role.hasConfig()) {
      throw Status.UNIMPLEMENTED.withDescription(ROLE_CONFIG_NOT_SUPPORTED).asException()
    }
    val roleName = arbitration.role.name // empty = default role
    val incomingId = arbitration.electionId

    return arbitrationMutex.withLock {
      // If this controller was previously arbitrating under a different role, recompute
      // the old role's primary before overwriting the entry.
      val previousEntry = controllers[streamId]
      if (previousEntry != null && previousEntry.roleName != roleName) {
        recomputeRolePrimary(previousEntry.roleName, excludeStreamId = streamId)
      }

      val election = roleElections.getOrPut(roleName) { RoleElection() }
      val oldPrimaryId = election.primaryElectionId
      controllers[streamId] = ControllerStream(roleName, incomingId, notifications)

      val newPrimaryId = computePrimary(roleName)
      roleElections[roleName] =
        election.copy(arbitrationOccurred = true, primaryElectionId = newPrimaryId)

      // If primary changed, send demotion/promotion notifications to other streams with this role.
      if (newPrimaryId != oldPrimaryId) {
        notifyRoleChanges(roleName, streamId, oldPrimaryId, newPrimaryId)
      }

      val isPrimary =
        !isZeroElectionId(incomingId) && newPrimaryId != null && incomingId == newPrimaryId
      buildArbitrationResponse(arbitration.deviceId, incomingId, isPrimary, roleName)
    }
  }

  /** Handles stream disconnect: removes the controller and promotes the next primary if needed. */
  private suspend fun handleDisconnect(streamId: Any) {
    arbitrationMutex.withLock {
      val removed = controllers.remove(streamId) ?: return
      recomputeRolePrimary(removed.roleName)
    }
  }

  /**
   * Recomputes the primary for [roleName] after a controller leaves or changes role, sends
   * promotion notifications, and cleans up empty role elections.
   *
   * When [excludeStreamId] is set, the controller is still in [controllers] but about to switch
   * roles, so it is excluded from the primary computation for the old role.
   */
  private fun recomputeRolePrimary(roleName: String, excludeStreamId: Any? = null) {
    val election = roleElections[roleName] ?: return
    val oldPrimaryId = election.primaryElectionId
    val newPrimaryId = computePrimary(roleName, excludeStreamId)
    roleElections[roleName] = election.copy(primaryElectionId = newPrimaryId)

    // If the primary changed, notify the promoted controller.
    if (oldPrimaryId != null && newPrimaryId != oldPrimaryId && newPrimaryId != null) {
      for ((id, ctrl) in controllers) {
        if (id != excludeStreamId && ctrl.roleName == roleName && ctrl.electionId == newPrimaryId) {
          ctrl.notifications.trySend(
            buildArbitrationResponse(deviceId, ctrl.electionId, true, roleName)
          )
        }
      }
    }
  }

  /** Returns the highest non-zero election_id among controllers with the given role, or null. */
  private fun computePrimary(roleName: String, excludeStreamId: Any? = null): Uint128? =
    controllers.entries
      .filter { (id, ctrl) -> id != excludeStreamId && ctrl.roleName == roleName }
      .map { it.value.electionId }
      .filter { !isZeroElectionId(it) }
      .maxWithOrNull(Comparator { a, b -> compareUint128(a, b) })

  /** Sends demotion/promotion notifications to other streams with the same role. */
  private fun notifyRoleChanges(
    roleName: String,
    excludeStreamId: Any,
    oldPrimaryId: Uint128?,
    newPrimaryId: Uint128?,
  ) {
    for ((id, ctrl) in controllers) {
      if (id == excludeStreamId || ctrl.roleName != roleName) continue
      val wasPrimary = oldPrimaryId != null && ctrl.electionId == oldPrimaryId
      val isNowPrimary = newPrimaryId != null && ctrl.electionId == newPrimaryId
      when {
        wasPrimary && !isNowPrimary ->
          ctrl.notifications.trySend(
            buildArbitrationResponse(deviceId, ctrl.electionId, false, roleName)
          )
        !wasPrimary && isNowPrimary ->
          ctrl.notifications.trySend(
            buildArbitrationResponse(deviceId, ctrl.electionId, true, roleName)
          )
      }
    }
  }

  private fun buildArbitrationResponse(
    deviceId: Long,
    electionId: Uint128,
    isPrimary: Boolean,
    roleName: String = "",
  ): StreamMessageResponse {
    val arb =
      MasterArbitrationUpdate.newBuilder()
        .setDeviceId(deviceId)
        .setElectionId(electionId)
        .setStatus(
          com.google.rpc.Status.newBuilder()
            .setCode(if (isPrimary) Code.OK_VALUE else Code.ALREADY_EXISTS_VALUE)
        )
    if (roleName.isNotEmpty()) {
      arb.setRole(P4RuntimeOuterClass.Role.newBuilder().setName(roleName))
    }
    return StreamMessageResponse.newBuilder().setArbitration(arb).build()
  }

  /** Rejects requests targeting a different device (P4Runtime spec §6.3). */
  private fun requireDeviceId(requestDeviceId: Long) {
    if (requestDeviceId != deviceId) {
      throw Status.NOT_FOUND.withDescription(
          "unknown device_id $requestDeviceId (this device is $deviceId)"
        )
        .asException()
    }
  }

  // ---------------------------------------------------------------------------
  // Unsupported feature guards
  // ---------------------------------------------------------------------------

  /**
   * Checks that the controller's role grants access to the entity.
   *
   * Default-role controllers (empty `roleName`) have access to all entities. Named-role controllers
   * can only access entities whose `@p4runtime_role` matches their role.
   *
   * Wildcard requests (entity ID = 0) are skipped here — results are filtered post-read by
   * [filterByRole] instead.
   */
  private fun requireEntityAccess(
    entity: P4RuntimeOuterClass.Entity,
    roleMap: RoleMap,
    roleName: String,
  ) {
    if (roleName.isEmpty()) return // default role = full access
    val entityId =
      entityIdForRole(entity) ?: return // unknown entity type — let later validation handle it
    if (entityId == 0) return // wildcard — filtering happens post-read
    val entityRole = roleMap.role(entityId)
    if (entityRole != roleName) {
      val entityDesc = entityRole ?: "default role"
      throw Status.PERMISSION_DENIED.withDescription(
          "role '$roleName' cannot access entity belonging to $entityDesc"
        )
        .asException()
    }
  }

  /**
   * Filters read results to only include entities the controller's role can access.
   *
   * Used for wildcard reads where the request entity doesn't specify a particular ID.
   */
  private fun filterByRole(
    entities: List<P4RuntimeOuterClass.Entity>,
    roleMap: RoleMap,
    roleName: String,
  ): List<P4RuntimeOuterClass.Entity> =
    entities.filter { entity ->
      val id = entityIdForRole(entity) ?: return@filter true // unscoped types (counters, etc.)
      roleMap.role(id) == roleName
    }

  /**
   * Extracts the p4info ID to use for role checking.
   *
   * For table entries and direct resources, this is the table ID (since `@p4runtime_role` is on
   * tables). For action profiles, this is the action profile ID (which inherits its role from its
   * associated tables in [RoleMap]). Returns null for entity types that don't have role annotations
   * (standalone counters, meters, registers, PRE entries).
   */
  private fun entityIdForRole(entity: P4RuntimeOuterClass.Entity): Int? =
    when (entity.entityCase) {
      P4RuntimeOuterClass.Entity.EntityCase.TABLE_ENTRY -> entity.tableEntry.tableId
      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_MEMBER ->
        entity.actionProfileMember.actionProfileId
      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_GROUP ->
        entity.actionProfileGroup.actionProfileId
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_COUNTER_ENTRY ->
        entity.directCounterEntry.tableEntry.tableId
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_METER_ENTRY ->
        entity.directMeterEntry.tableEntry.tableId
      // Standalone counters, meters, registers, PRE, and unimplemented types: no role annotations.
      P4RuntimeOuterClass.Entity.EntityCase.COUNTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.METER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.REGISTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.VALUE_SET_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIGEST_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.EXTERN_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.ENTITY_NOT_SET,
      null -> null
    }

  /** Rejects entity types that are documented but not implemented. */
  private fun rejectUnsupportedEntity(entity: P4RuntimeOuterClass.Entity) {
    when (entity.entityCase) {
      P4RuntimeOuterClass.Entity.EntityCase.DIGEST_ENTRY ->
        throw Status.UNIMPLEMENTED.withDescription(DIGEST_NOT_SUPPORTED).asException()
      P4RuntimeOuterClass.Entity.EntityCase.EXTERN_ENTRY ->
        throw Status.UNIMPLEMENTED.withDescription(EXTERN_ENTRY_NOT_SUPPORTED).asException()
      // All other entity types are handled via the normal write/read path.
      P4RuntimeOuterClass.Entity.EntityCase.TABLE_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_MEMBER,
      P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_GROUP,
      P4RuntimeOuterClass.Entity.EntityCase.COUNTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_COUNTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.METER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.DIRECT_METER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.REGISTER_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.VALUE_SET_ENTRY,
      P4RuntimeOuterClass.Entity.EntityCase.ENTITY_NOT_SET,
      null -> {}
    }
  }

  // ---------------------------------------------------------------------------
  // Batch error reporting (P4Runtime spec §12.3)
  // ---------------------------------------------------------------------------

  /**
   * Builds a gRPC UNKNOWN error with per-update [P4RuntimeOuterClass.Error] details.
   *
   * The `google.rpc.Status` is attached via the standard `grpc-status-details-bin` trailer so
   * clients can extract per-update results (P4Runtime spec §10, §12.3).
   */
  private fun buildBatchError(errors: List<P4RuntimeOuterClass.Error>): StatusException {
    val failedCount = errors.count { it.canonicalCode != OK_CODE }
    val totalCount = errors.size
    val failedDetails =
      errors
        .withIndex()
        .filter { it.value.canonicalCode != OK_CODE }
        .joinToString("; ") { (i, e) -> "update #${i + 1}: ${e.message}" }
    val message = "$failedCount of $totalCount updates failed: $failedDetails"
    val rpcStatus =
      com.google.rpc.Status.newBuilder().setCode(Code.UNKNOWN_VALUE).setMessage(message)
    for (error in errors) {
      rpcStatus.addDetails(ProtoAny.pack(error))
    }
    val metadata = Metadata()
    metadata.put(STATUS_DETAILS_KEY, rpcStatus.build().toByteArray())
    return Status.UNKNOWN.withDescription(message).asException(metadata)
  }

  override fun close() {
    pipeline?.constraintValidator?.close()
    savedPipeline?.constraintValidator?.close()
  }

  companion object {
    internal const val DEFAULT_DEVICE_ID = 1L

    // Well-known metadata ID for the fallback (no @controller_header) PacketOut path.
    private const val INGRESS_PORT_METADATA_ID = 1

    // Matches the p4runtime proto version declared in MODULE.bazel.
    private const val P4RUNTIME_API_VERSION = "1.5.0"

    private const val NO_PIPELINE_MESSAGE =
      "no pipeline loaded; call SetForwardingPipelineConfig first"

    private const val DIGEST_NOT_SUPPORTED =
      "digest is not supported; the simulator does not implement digest extern generation"
    private const val EXTERN_ENTRY_NOT_SUPPORTED =
      "ExternEntry is not supported; use the dedicated entity types " +
        "(TableEntry, CounterEntry, etc.) instead"
    private const val ROLE_CONFIG_NOT_SUPPORTED =
      "Role.config is not supported; use @p4runtime_role annotations in p4info instead"

    private const val OK_CODE = Code.OK_VALUE

    // P4Runtime spec §12.3: the error space for P4Runtime-specific errors.
    private const val P4RT_ERROR_SPACE = "p4.v1"

    private fun isZeroElectionId(id: Uint128): Boolean = id.high == 0L && id.low == 0L

    // Standard gRPC binary trailer for rich error details (P4Runtime spec §10).
    private val STATUS_DETAILS_KEY: Metadata.Key<ByteArray> =
      Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER)
  }
}
