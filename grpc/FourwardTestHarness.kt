package fourward.grpc

import com.google.protobuf.ByteString
import com.google.rpc.Status as RpcStatus
import fourward.DataplaneGrpcKt.DataplaneCoroutineStub
import fourward.InjectPacketRequest
import fourward.InjectPacketResponse
import fourward.PacketSet
import fourward.PipelineConfig
import fourward.simulator.Simulator
import fourward.simulator.portToBytes
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import p4.v1.P4RuntimeGrpcKt.P4RuntimeCoroutineStub
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.CapabilitiesRequest
import p4.v1.P4RuntimeOuterClass.CapabilitiesResponse
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate
import p4.v1.P4RuntimeOuterClass.PacketOut
import p4.v1.P4RuntimeOuterClass.ReadRequest
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
 * In-process test harness for the P4Runtime gRPC service.
 *
 * Starts an in-process gRPC server backed by a real [Simulator] and provides typed helpers for
 * common operations. Uses grpc-java's InProcessTransport so no real network ports are needed.
 */
class FourwardTestHarness(
  constraintValidatorBinary: Path? = null,
  dropPortOverride: PortOverride? = null,
  cpuPortConfig: CpuPortConfig = CpuPortConfig.Auto,
  disableRefersToChecking: Boolean = false,
  disableP4ConstraintsChecking: Boolean = false,
) : Closeable {

  private val serverName = InProcessServerBuilder.generateName()
  private val simulator = Simulator()
  private val writeMutex = kotlinx.coroutines.sync.Mutex()
  private val broker = PacketBroker(simulator::processPacket, writeMutex)
  private val service =
    P4RuntimeService(
      simulator,
      broker,
      constraintValidatorBinary,
      writeMutex = writeMutex,
      cpuPortConfig = cpuPortConfig,
      dropPortConfig = dropPortOverride,
      disableRefersToChecking = disableRefersToChecking,
      disableP4ConstraintsChecking = disableP4ConstraintsChecking,
    )
  private val dataplaneService =
    DataplaneService(broker) {
      val config = simulator.pipelineConfig ?: return@DataplaneService null
      val tableStore = simulator.tableStore ?: return@DataplaneService null
      DataplaneService.PipelineSnapshot(config, tableStore, service.typeTranslator)
    }

  init {
    broker.readAllEntities = { service.readAllEntities() }
    broker.readP4Info = { service.p4Info() }
  }

  private val executor = java.util.concurrent.Executors.newCachedThreadPool()

  private val server =
    InProcessServerBuilder.forName(serverName)
      // A multi-threaded executor is required for bidirectional streaming RPCs
      // (e.g. RegisterPrePacketHook) where the server suspends on a rendezvous
      // channel while waiting for the client response.
      .executor(executor)
      .addService(service)
      .addService(dataplaneService)
      .build()
      .start()

  val channel: ManagedChannel = InProcessChannelBuilder.forName(serverName).build()

  val stub: P4RuntimeCoroutineStub = P4RuntimeCoroutineStub(channel)
  private val dataplaneStub: DataplaneCoroutineStub = DataplaneCoroutineStub(channel)

  // ---------------------------------------------------------------------------
  // Pipeline management
  // ---------------------------------------------------------------------------

  /** Converts a [PipelineConfig] to the gRPC [ForwardingPipelineConfig] wire format. */
  fun buildForwardingPipelineConfig(
    config: PipelineConfig,
    cookie: ForwardingPipelineConfig.Cookie = ForwardingPipelineConfig.Cookie.getDefaultInstance(),
  ): ForwardingPipelineConfig =
    ForwardingPipelineConfig.newBuilder()
      .setP4Info(config.p4Info)
      .setP4DeviceConfig(config.device.toByteString())
      .setCookie(cookie)
      .build()

  fun loadPipeline(
    config: PipelineConfig,
    cookie: ForwardingPipelineConfig.Cookie = ForwardingPipelineConfig.Cookie.getDefaultInstance(),
  ): SetForwardingPipelineConfigResponse = runBlocking {
    stub.setForwardingPipelineConfig(
      SetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
        .setConfig(buildForwardingPipelineConfig(config, cookie))
        .build()
    )
  }

  fun loadPipeline(configPath: Path): SetForwardingPipelineConfigResponse {
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(configPath.toFile().readText(), builder)
    return loadPipeline(builder.build())
  }

  fun getConfig(
    responseType: GetForwardingPipelineConfigRequest.ResponseType =
      GetForwardingPipelineConfigRequest.ResponseType.ALL
  ): GetForwardingPipelineConfigResponse = runBlocking {
    stub.getForwardingPipelineConfig(
      GetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setResponseType(responseType)
        .build()
    )
  }

  fun capabilities(): CapabilitiesResponse = runBlocking {
    stub.capabilities(CapabilitiesRequest.getDefaultInstance())
  }

  // ---------------------------------------------------------------------------
  // Dataplane
  // ---------------------------------------------------------------------------

  /** Injects a packet via the InjectPacket RPC. Returns outputs + trace. */
  fun injectPacket(ingressPort: Int, payload: ByteArray, tag: Long = 0): InjectPacketResponse =
    runBlocking {
      dataplaneStub.injectPacket(
        InjectPacketRequest.newBuilder()
          .setDataplaneIngressPort(ingressPort)
          .setPayload(ByteString.copyFrom(payload))
          .setTag(tag)
          .build()
      )
    }

  /** Injects a packet and returns a self-contained Reproducer for the trace. */
  fun getReproducer(ingressPort: Int, payload: ByteArray): fourward.Reproducer = runBlocking {
    dataplaneStub.getReproducer(
      InjectPacketRequest.newBuilder()
        .setDataplaneIngressPort(ingressPort)
        .setPayload(ByteString.copyFrom(payload))
        .build()
    )
  }

  /** Injects a packet using a P4Runtime port ID. Returns outputs + trace. */
  fun injectPacketP4rt(p4rtPort: ByteString, payload: ByteArray): InjectPacketResponse =
    runBlocking {
      dataplaneStub.injectPacket(
        InjectPacketRequest.newBuilder()
          .setP4RtIngressPort(p4rtPort)
          .setPayload(ByteString.copyFrom(payload))
          .build()
      )
    }

  /** Injects a packet and returns the possible outcome sets. */
  fun simulatePacket(ingressPort: Int, payload: ByteArray): List<PacketSet> =
    injectPacket(ingressPort, payload).possibleOutcomesList

  /** Injects multiple packets concurrently via the streaming InjectPackets RPC. */
  fun injectPackets(packets: List<Pair<Int, ByteArray>>) = runBlocking {
    dataplaneStub.injectPackets(
      kotlinx.coroutines.flow.flow {
        for ((port, payload) in packets) {
          emit(
            InjectPacketRequest.newBuilder()
              .setDataplaneIngressPort(port)
              .setPayload(ByteString.copyFrom(payload))
              .build()
          )
        }
      }
    )
  }

  // ---------------------------------------------------------------------------
  // Table entry management
  // ---------------------------------------------------------------------------

  fun installEntry(entity: Entity, electionId: Uint128? = null, role: String = ""): WriteResponse =
    writeEntity(Update.Type.INSERT, entity, electionId, role)

  fun modifyEntry(entity: Entity, electionId: Uint128? = null, role: String = ""): WriteResponse =
    writeEntity(Update.Type.MODIFY, entity, electionId, role)

  fun deleteEntry(entity: Entity, electionId: Uint128? = null, role: String = ""): WriteResponse =
    writeEntity(Update.Type.DELETE, entity, electionId, role)

  /** Sends a raw [WriteRequest] — use for testing request-level fields like atomicity. */
  fun writeRaw(request: WriteRequest): WriteResponse = runBlocking { stub.write(request) }

  /** Builds a [WriteRequest] with multiple entities of the same update type. */
  fun buildBatchRequest(type: Update.Type, entities: List<Entity>): WriteRequest =
    WriteRequest.newBuilder()
      .setDeviceId(1)
      .apply {
        for (entity in entities) addUpdates(Update.newBuilder().setType(type).setEntity(entity))
      }
      .build()

  private fun writeEntity(
    type: Update.Type,
    entity: Entity,
    electionId: Uint128?,
    role: String = "",
  ): WriteResponse = runBlocking {
    stub.write(
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .apply {
          if (electionId != null) setElectionId(electionId)
          if (role.isNotEmpty()) setRole(role)
        }
        .addUpdates(Update.newBuilder().setType(type).setEntity(entity))
        .build()
    )
  }

  /** Wildcard read: returns all non-default table entries (table_id=0). */
  fun readEntries(): List<Entity> = readTableEntries(0)

  /** Reads default entries (is_default_action=true) from a single table or all tables. */
  fun readDefaultEntries(tableId: Int = 0): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setTableEntry(TableEntry.newBuilder().setTableId(tableId).setIsDefaultAction(true))
        )
        .build()
    )

  /** Per-table read: returns entries from a single table (or all tables if tableId is 0). */
  fun readTableEntries(tableId: Int, role: String = ""): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .apply { if (role.isNotEmpty()) setRole(role) }
        .addEntities(Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(tableId)))
        .build()
    )

  /** Per-entry read: uses the entity's match fields as a filter. */
  fun readEntry(entity: Entity): List<Entity> =
    readEntries(ReadRequest.newBuilder().setDeviceId(1).addEntities(entity).build())

  fun readEntries(request: ReadRequest): List<Entity> = runBlocking {
    val entities = mutableListOf<Entity>()
    stub.read(request).collect { response -> entities.addAll(response.entitiesList) }
    entities
  }

  /** Reads action profile members, optionally filtered by profile ID. */
  fun readProfileMembers(actionProfileId: Int = 0): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setActionProfileMember(
              p4.v1.P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(actionProfileId)
            )
        )
        .build()
    )

  /** Reads action profile groups, optionally filtered by profile ID. */
  fun readProfileGroups(actionProfileId: Int = 0): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setActionProfileGroup(
              p4.v1.P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                .setActionProfileId(actionProfileId)
            )
        )
        .build()
    )

  /** Reads register entries, filtered by register ID. */
  fun readRegisterEntries(registerId: Int): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setRegisterEntry(
              p4.v1.P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(registerId)
            )
        )
        .build()
    )

  /** Reads counter entries, filtered by counter ID. */
  fun readCounterEntries(counterId: Int): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setCounterEntry(
              p4.v1.P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(counterId)
            )
        )
        .build()
    )

  /** Reads meter entries, filtered by meter ID. */
  fun readMeterEntries(meterId: Int): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setMeterEntry(p4.v1.P4RuntimeOuterClass.MeterEntry.newBuilder().setMeterId(meterId))
        )
        .build()
    )

  /** Reads direct counter entries for all entries in the given table. */
  fun readDirectCounterEntries(tableId: Int): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setDirectCounterEntry(
              p4.v1.P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
                .setTableEntry(
                  p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(tableId)
                )
            )
        )
        .build()
    )

  /** Reads direct meter entries for all entries in the given table. */
  fun readDirectMeterEntries(tableId: Int): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setDirectMeterEntry(
              p4.v1.P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
                .setTableEntry(
                  p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(tableId)
                )
            )
        )
        .build()
    )

  // ---------------------------------------------------------------------------
  // StreamChannel helpers
  // ---------------------------------------------------------------------------

  /** Opens a persistent bidirectional stream. Callers should use [Closeable.use] for cleanup. */
  fun openStream(): StreamSession = StreamSession()

  /**
   * A persistent bidirectional StreamChannel session.
   *
   * Call [arbitrate] once to become master, then [sendPacket] for each packet. The session
   * maintains a single gRPC stream, avoiding re-arbitration per packet.
   */
  inner class StreamSession : Closeable {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val requestChannel = Channel<StreamMessageRequest>(Channel.UNLIMITED)
    private val responseChannel = Channel<StreamMessageResponse>(Channel.UNLIMITED)

    private val job =
      scope.launch {
        stub.streamChannel(requestChannel.consumeAsFlow()).collect { responseChannel.send(it) }
      }

    fun arbitrate(
      deviceId: Long = 1,
      electionId: Long = 1,
      roleName: String = "",
    ): StreamMessageResponse = runBlocking {
      val arb =
        MasterArbitrationUpdate.newBuilder()
          .setDeviceId(deviceId)
          .setElectionId(Uint128.newBuilder().setHigh(0).setLow(electionId))
      if (roleName.isNotEmpty()) {
        arb.setRole(P4RuntimeOuterClass.Role.newBuilder().setName(roleName))
      }
      requestChannel.send(StreamMessageRequest.newBuilder().setArbitration(arb).build())
      withTimeout(STREAM_TIMEOUT_MS) { responseChannel.receive() }
    }

    /** Sends a fully-constructed PacketOut and waits for a response. */
    fun sendPacketOut(
      packetOut: PacketOut,
      timeoutMs: Long = STREAM_TIMEOUT_MS,
    ): StreamMessageResponse? = runBlocking {
      requestChannel.send(StreamMessageRequest.newBuilder().setPacket(packetOut).build())
      withTimeoutOrNull(timeoutMs) { responseChannel.receive() }
    }

    @Suppress("MagicNumber")
    fun sendPacket(
      payload: ByteArray,
      ingressPort: Int = 0,
      timeoutMs: Long = STREAM_TIMEOUT_MS,
    ): StreamMessageResponse? = runBlocking {
      requestChannel.send(
        StreamMessageRequest.newBuilder()
          .setPacket(
            PacketOut.newBuilder()
              .setPayload(ByteString.copyFrom(payload))
              .addMetadata(
                p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
                  .setMetadataId(1) // ingress_port
                  .setValue(portToBytes(ingressPort))
              )
          )
          .build()
      )
      withTimeoutOrNull(timeoutMs) { responseChannel.receive() }
    }

    /** Sends an arbitrary stream message and waits for a response. */
    fun sendRaw(msg: StreamMessageRequest): StreamMessageResponse? = runBlocking {
      requestChannel.send(msg)
      withTimeoutOrNull(STREAM_TIMEOUT_MS) { responseChannel.receive() }
    }

    /** Receives the next unsolicited message (e.g., demotion/promotion notification). */
    fun receiveNext(): StreamMessageResponse? = runBlocking {
      withTimeoutOrNull(STREAM_TIMEOUT_MS) { responseChannel.receive() }
    }

    override fun close() {
      // Close the request channel to signal end-of-stream, then wait for the server-side
      // disconnect handler (handleDisconnect) to complete before returning. Without this,
      // tests that close a stream and immediately check arbitration state can race against
      // the async disconnect processing.
      requestChannel.close()
      runBlocking { withTimeout(STREAM_TIMEOUT_MS) { job.join() } }
      scope.cancel()
    }

    /** Cancels the stream coroutine without the polite request-channel close (cf. [close]). */
    fun cancelAbruptly() {
      scope.cancel()
    }
  }

  override fun close() {
    channel.shutdownNow()
    server.shutdownNow()
    executor.shutdownNow()
    service.close()
  }

  companion object {
    private const val STREAM_TIMEOUT_MS = 5000L

    /** Short timeout for calls where no response is expected (avoids 5s waits in tests). */
    const val NO_RESPONSE_TIMEOUT_MS = 500L

    /** Builds a [Uint128] from high and low parts. */
    fun uint128(high: Long = 0, low: Long): Uint128 =
      Uint128.newBuilder().setHigh(high).setLow(low).build()

    /**
     * Asserts that [block] throws a [StatusException] with the expected gRPC [code]. Optionally
     * checks that the error description contains [messageContains].
     */
    inline fun assertGrpcError(
      code: Status.Code,
      messageContains: String? = null,
      block: () -> Unit,
    ) {
      try {
        block()
        throw AssertionError("expected gRPC error $code but call succeeded")
      } catch (e: StatusException) {
        // Resolve the actual error code and message. For single-update batches,
        // unwrap the per-update p4.v1.Error (P4Runtime spec §12.3).
        val actualCode: Status.Code
        val actualMessage: String?
        val batchError =
          if (e.status.code == Status.Code.UNKNOWN) extractBatchErrors(e)?.singleOrNull() else null
        if (batchError != null) {
          actualCode =
            Status.Code.values().find { it.value() == batchError.canonicalCode } ?: e.status.code
          actualMessage = batchError.message
        } else {
          actualCode = e.status.code
          actualMessage = e.status.description
        }
        if (code != actualCode) {
          throw AssertionError("expected gRPC status $code but got $actualCode", e)
        }
        if (
          messageContains != null &&
            actualMessage?.contains(messageContains, ignoreCase = true) != true
        ) {
          throw AssertionError(
            "expected message containing '$messageContains' but got '$actualMessage'",
            e,
          )
        }
      }
    }

    /** Extracts per-update [P4RuntimeOuterClass.Error] details from a batch UNKNOWN error. */
    fun extractBatchErrors(e: StatusException): List<P4RuntimeOuterClass.Error>? {
      val trailers = e.trailers ?: return null
      val key =
        io.grpc.Metadata.Key.of("grpc-status-details-bin", io.grpc.Metadata.BINARY_BYTE_MARSHALLER)
      val bytes = trailers.get(key) ?: return null
      val rpcStatus = RpcStatus.parseFrom(bytes)
      return rpcStatus.detailsList.map { any -> P4RuntimeOuterClass.Error.parseFrom(any.value) }
    }

    /**
     * Builds a read-filter entity: table_id + exact match key, no action. Useful as a ReadRequest
     * filter for per-entry reads.
     */
    @Suppress("MagicNumber")
    fun buildMatchFilter(config: PipelineConfig, matchValue: Long): Entity {
      val table = config.p4Info.tablesList.first()
      val matchField = table.matchFieldsList.first()
      val fieldMatch =
        p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
          .setFieldId(matchField.id)
          .setExact(
            p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(matchValue, (matchField.bitwidth + 7) / 8)))
          )
          .build()
      return Entity.newBuilder()
        .setTableEntry(
          p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(fieldMatch)
        )
        .build()
    }

    /**
     * Builds a table entry for the basic_table fixture: exact match on the table's first match
     * field → forward(port).
     */
    @Suppress("MagicNumber")
    fun buildExactEntry(config: PipelineConfig, matchValue: Long, port: Int): Entity {
      val p4info = config.p4Info
      val table = p4info.tablesList.first()
      val forwardAction = p4info.actionsList.find { it.preamble.name.contains("forward") }!!

      val filter = buildMatchFilter(config, matchValue)
      val actionParam =
        p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(forwardAction.paramsList.first().id)
          .setValue(
            ByteString.copyFrom(
              longToBytes(port.toLong(), (forwardAction.paramsList.first().bitwidth + 7) / 8)
            )
          )
          .build()

      return Entity.newBuilder()
        .setTableEntry(
          filter.tableEntry
            .toBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(actionParam)
                )
            )
        )
        .build()
    }

    /** Loads a PipelineConfig from a Bazel runfiles-relative text proto path. */
    fun loadConfig(relativePath: String): PipelineConfig {
      val path = fourward.bazel.repoRoot.resolve(relativePath)
      val builder = PipelineConfig.newBuilder()
      com.google.protobuf.TextFormat.merge(path.toFile().readText(), builder)
      return builder.build()
    }

    /** Finds a table by alias in p4info, or throws with a clear error. */
    fun findTable(config: PipelineConfig, alias: String) =
      config.p4Info.tablesList.find { it.preamble.alias == alias }
        ?: error("table '$alias' not found in p4info")

    /** Finds an action by alias in p4info, or throws with a clear error. */
    fun findAction(config: PipelineConfig, alias: String) =
      config.p4Info.actionsList.find { it.preamble.alias == alias }
        ?: error("action '$alias' not found in p4info")

    /** Finds a match field ID by name in a table, or throws with a clear error. */
    fun matchFieldId(table: p4.config.v1.P4InfoOuterClass.Table, name: String): Int =
      table.matchFieldsList.find { it.name == name }?.id
        ?: error("match field '$name' not found in table '${table.preamble.alias}'")

    /** Finds an action param ID by name, or throws with a clear error. */
    fun paramId(action: p4.config.v1.P4InfoOuterClass.Action, name: String): Int =
      action.paramsList.find { it.name == name }?.id
        ?: error("param '$name' not found in action '${action.preamble.alias}'")

    /** Builds a minimal Ethernet frame: dst=FF:FF:FF:FF:FF:FF src=00:00:00:00:00:01 + etherType. */
    @Suppress("MagicNumber")
    fun buildEthernetFrame(etherType: Int): ByteArray {
      val frame = ByteArray(18) // 14-byte header + 4 bytes payload
      for (i in 0 until 6) frame[i] = 0xFF.toByte()
      frame[11] = 0x01
      frame[12] = (etherType shr 8).toByte()
      frame[13] = (etherType and 0xFF).toByte()
      frame[14] = 0xDE.toByte()
      frame[15] = 0xAD.toByte()
      frame[16] = 0xBE.toByte()
      frame[17] = 0xEF.toByte()
      return frame
    }

    /** Encodes a long value as unsigned big-endian bytes with the given byte length. */
    fun longToBytes(value: Long, byteLen: Int): ByteArray {
      val bytes = ByteArray(byteLen)
      for (i in 0 until byteLen) {
        bytes[byteLen - 1 - i] = (value shr (i * 8) and 0xFF).toByte()
      }
      return bytes
    }

    /** Builds an Entity wrapping an ActionProfileMember with a single action param. */
    fun buildMemberEntity(actionProfileId: Int, memberId: Int, actionId: Int): Entity =
      Entity.newBuilder()
        .setActionProfileMember(
          p4.v1.P4RuntimeOuterClass.ActionProfileMember.newBuilder()
            .setActionProfileId(actionProfileId)
            .setMemberId(memberId)
            .setAction(p4.v1.P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId))
        )
        .build()

    /** Builds an Entity wrapping an ActionProfileGroup with the given member IDs. */
    fun buildGroupEntity(actionProfileId: Int, groupId: Int, memberIds: List<Int>): Entity =
      Entity.newBuilder()
        .setActionProfileGroup(
          p4.v1.P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
            .setActionProfileId(actionProfileId)
            .setGroupId(groupId)
            .addAllMembers(
              memberIds.map { mid ->
                p4.v1.P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                  .setMemberId(mid)
                  .setWeight(1)
                  .build()
              }
            )
        )
        .build()

    /**
     * Builds a default-action Entity for the given table. Used to MODIFY/INSERT/DELETE defaults.
     */
    fun buildDefaultActionEntity(tableId: Int, actionId: Int? = null): Entity {
      val builder =
        P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(tableId).setIsDefaultAction(true)
      if (actionId != null) {
        builder.setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId))
        )
      }
      return Entity.newBuilder().setTableEntry(builder).build()
    }

    /** Builds an Entity wrapping a RegisterEntry. */
    @Suppress("MagicNumber")
    fun buildRegisterEntry(registerId: Int, index: Long, value: Long, byteLen: Int = 4): Entity =
      Entity.newBuilder()
        .setRegisterEntry(
          p4.v1.P4RuntimeOuterClass.RegisterEntry.newBuilder()
            .setRegisterId(registerId)
            .setIndex(p4.v1.P4RuntimeOuterClass.Index.newBuilder().setIndex(index))
            .setData(
              p4.v1.P4DataOuterClass.P4Data.newBuilder()
                .setBitstring(ByteString.copyFrom(longToBytes(value, byteLen)))
            )
        )
        .build()

    /** Builds an Entity wrapping a CounterEntry. */
    fun buildCounterEntry(
      counterId: Int,
      index: Long,
      byteCount: Long = 0,
      packetCount: Long = 0,
    ): Entity =
      Entity.newBuilder()
        .setCounterEntry(
          p4.v1.P4RuntimeOuterClass.CounterEntry.newBuilder()
            .setCounterId(counterId)
            .setIndex(p4.v1.P4RuntimeOuterClass.Index.newBuilder().setIndex(index))
            .setData(
              p4.v1.P4RuntimeOuterClass.CounterData.newBuilder()
                .setByteCount(byteCount)
                .setPacketCount(packetCount)
            )
        )
        .build()

    /** Builds an Entity wrapping a MeterEntry. */
    fun buildMeterEntry(
      meterId: Int,
      index: Long,
      cir: Long = 0,
      cburst: Long = 0,
      pir: Long = 0,
      pburst: Long = 0,
    ): Entity =
      Entity.newBuilder()
        .setMeterEntry(
          p4.v1.P4RuntimeOuterClass.MeterEntry.newBuilder()
            .setMeterId(meterId)
            .setIndex(p4.v1.P4RuntimeOuterClass.Index.newBuilder().setIndex(index))
            .setConfig(
              p4.v1.P4RuntimeOuterClass.MeterConfig.newBuilder()
                .setCir(cir)
                .setCburst(cburst)
                .setPir(pir)
                .setPburst(pburst)
            )
        )
        .build()

    /** Builds a multicast group Entity with replicas on the given ports. */
    fun buildMulticastGroup(groupId: Int, ports: List<Int>): Entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(
              P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                .setMulticastGroupId(groupId)
                .addAllReplicas(
                  ports.mapIndexed { idx, port ->
                    P4RuntimeOuterClass.Replica.newBuilder()
                      .setPort(ByteString.copyFrom(longToBytes(port.toLong(), 2)))
                      .setInstance(idx)
                      .build()
                  }
                )
            )
        )
        .build()
  }
}
