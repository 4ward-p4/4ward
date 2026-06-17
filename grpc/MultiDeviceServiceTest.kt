package fourward.grpc

import com.google.protobuf.ByteString
import fourward.CreateDevicesRequest
import fourward.DataplaneGrpcKt.DataplaneCoroutineStub
import fourward.DeleteDevicesRequest
import fourward.FourwardManagementGrpcKt.FourwardManagementCoroutineStub
import fourward.InjectPacketRequest
import fourward.ListDevicesRequest
import fourward.grpc.FourwardTestHarness.Companion.assertGrpcError
import fourward.grpc.FourwardTestHarness.Companion.loadConfig
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.Closeable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeGrpcKt.P4RuntimeCoroutineStub
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Uint128

class MultiDeviceServiceTest {
  private lateinit var harness: Harness

  @Before
  fun setUp() {
    harness = Harness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  @Test
  fun `startup creates default device`() = runBlocking {
    assertEquals(listOf(1L), harness.listDevices())
  }

  @Test
  fun `CreateDevices creates contiguous range atomically`() = runBlocking {
    harness.createDevices(2, 3)

    assertEquals(listOf(1L, 2L, 3L, 4L), harness.listDevices())

    assertGrpcError(Status.Code.ALREADY_EXISTS, "device_id 3") { harness.createDevices(3, 1) }
    assertEquals(
      "failed create leaves registry unchanged",
      listOf(1L, 2L, 3L, 4L),
      harness.listDevices(),
    )
  }

  @Test
  fun `DeleteDevices deletes contiguous range atomically`() = runBlocking {
    harness.createDevices(2, 3)

    assertGrpcError(Status.Code.NOT_FOUND, "device_id 5") { harness.deleteDevices(3, 3) }
    assertEquals(
      "failed delete leaves registry unchanged",
      listOf(1L, 2L, 3L, 4L),
      harness.listDevices(),
    )

    harness.deleteDevices(2, 2)
    assertEquals(listOf(1L, 4L), harness.listDevices())
  }

  @Test
  fun `CreateDevices enforces resource limits`() {
    DeviceRegistry(DeviceSettings(), maxDevices = 2, maxDevicesPerRequest = 2).use { registry ->
      assertGrpcError(Status.Code.RESOURCE_EXHAUSTED, "max live devices") {
        registry.createDevices(2, 2)
      }
    }
    DeviceRegistry(DeviceSettings(), maxDevices = 10, maxDevicesPerRequest = 1).use { registry ->
      assertGrpcError(Status.Code.RESOURCE_EXHAUSTED, "max devices per request") {
        registry.createDevices(2, 2)
      }
    }
  }

  @Test
  fun `P4Runtime routes pipeline state by device_id`() = runBlocking {
    val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
    harness.createDevices(2, 1)
    harness.loadPipeline(deviceId = 2, config = config)

    assertTrue(harness.getConfig(deviceId = 2).config.hasP4Info())
    assertGrpcError(Status.Code.FAILED_PRECONDITION, "No pipeline loaded") {
      harness.getConfig(deviceId = 1)
    }
    assertGrpcError(Status.Code.NOT_FOUND, "unknown device_id 999") {
      harness.getConfig(deviceId = 999)
    }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "device_id must be nonzero") {
      harness.getConfig(deviceId = 0)
    }
  }

  @Test
  fun `P4Runtime StreamChannel rejects device_id zero`() {
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "device_id must be nonzero") {
      runBlocking { harness.streamChannel(flowOf(arbitration(deviceId = 0))).toList() }
    }
  }

  @Test
  fun `DeleteDevices closes active P4Runtime streams`() = runBlocking {
    supervisorScope {
      harness.createDevices(2, 1)
      val requests = Channel<StreamMessageRequest>(Channel.UNLIMITED)
      val responses = Channel<StreamMessageResponse>(Channel.UNLIMITED)
      val collector = async {
        try {
          harness.streamChannel(requests.consumeAsFlow()).collect { responses.send(it) }
        } finally {
          responses.close()
        }
      }

      try {
        requests.send(arbitration(deviceId = 2))
        assertTrue(withTimeout(STREAM_TIMEOUT_MS) { responses.receive() }.hasArbitration())

        harness.deleteDevices(2, 1)

        try {
          collector.await()
          fail("expected deleted device stream to fail")
        } catch (e: StatusException) {
          assertEquals(Status.Code.NOT_FOUND, e.status.code)
          assertTrue(e.status.description!!.contains("device_id 2 was deleted"))
        }
      } finally {
        requests.close()
      }
    }
  }

  @Test
  fun `Dataplane device_id zero uses default device`() = runBlocking {
    val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
    harness.createDevices(2, 1)
    harness.loadPipeline(deviceId = 1, config = config)
    val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte())

    val defaulted = harness.injectPacket(deviceId = 0, ingressPort = 0, payload = payload)
    assertEquals(ByteString.copyFrom(payload), defaulted.outputPayload())

    assertGrpcError(Status.Code.INTERNAL, "InjectPacket failed") {
      harness.injectPacket(deviceId = 2, ingressPort = 0, payload = payload)
    }
  }

  @Test
  fun `InjectPackets rejects mixed device streams`() = runBlocking {
    harness.loadPipeline(
      deviceId = 1,
      config = loadConfig("e2e_tests/passthrough/passthrough.txtpb"),
    )

    assertGrpcError(Status.Code.INVALID_ARGUMENT, "bound to device_id 1") {
      runBlocking {
        harness.injectPackets(
          flow {
            emit(injectPacketRequest(deviceId = 0))
            emit(injectPacketRequest(deviceId = 2))
          }
        )
      }
    }
  }

  @Test
  fun `Read routes by device_id`() = runBlocking {
    val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
    harness.createDevices(2, 1)
    harness.loadPipeline(deviceId = 2, config = config)

    assertEquals(emptyList<Entity>(), harness.readTableEntries(deviceId = 2))
    assertGrpcError(Status.Code.FAILED_PRECONDITION, "No pipeline loaded") {
      harness.readTableEntries(deviceId = 1)
    }
  }

  private fun fourward.InjectPacketResponse.outputPayload(): ByteString =
    possibleOutcomesList.single().getPackets(0).payload

  private fun arbitration(deviceId: Long): StreamMessageRequest =
    StreamMessageRequest.newBuilder()
      .setArbitration(
        MasterArbitrationUpdate.newBuilder()
          .setDeviceId(deviceId)
          .setElectionId(Uint128.newBuilder().setLow(1))
      )
      .build()

  private fun injectPacketRequest(deviceId: Long): InjectPacketRequest =
    InjectPacketRequest.newBuilder()
      .setDeviceId(deviceId)
      .setDataplaneIngressPort(0)
      .setPayload(ByteString.copyFrom(byteArrayOf(0xCA.toByte(), 0xFE.toByte())))
      .build()

  companion object {
    private const val STREAM_TIMEOUT_MS = 5000L
  }

  private class Harness : Closeable {
    private val registry = DeviceRegistry(DeviceSettings())
    private val serverName = InProcessServerBuilder.generateName()
    private val executor = java.util.concurrent.Executors.newCachedThreadPool()
    private val server =
      InProcessServerBuilder.forName(serverName)
        .executor(executor)
        .addService(MultiDeviceP4RuntimeService(registry))
        .addService(MultiDeviceDataplaneService(registry))
        .addService(ManagementService(registry))
        .build()
        .start()

    private val channel: ManagedChannel = InProcessChannelBuilder.forName(serverName).build()
    private val p4rt = P4RuntimeCoroutineStub(channel)
    private val dataplane = DataplaneCoroutineStub(channel)
    private val management = FourwardManagementCoroutineStub(channel)

    suspend fun createDevices(firstDeviceId: Long, count: Int) {
      management.createDevices(
        CreateDevicesRequest.newBuilder().setFirstDeviceId(firstDeviceId).setCount(count).build()
      )
    }

    suspend fun deleteDevices(firstDeviceId: Long, count: Int) {
      management.deleteDevices(
        DeleteDevicesRequest.newBuilder().setFirstDeviceId(firstDeviceId).setCount(count).build()
      )
    }

    suspend fun listDevices(): List<Long> =
      management.listDevices(ListDevicesRequest.getDefaultInstance()).deviceIdsList

    suspend fun loadPipeline(deviceId: Long, config: fourward.PipelineConfig) {
      p4rt.setForwardingPipelineConfig(
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(deviceId)
          .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
          .setConfig(
            ForwardingPipelineConfig.newBuilder()
              .setP4Info(config.p4Info)
              .setP4DeviceConfig(config.device.toByteString())
          )
          .build()
      )
    }

    suspend fun getConfig(deviceId: Long) =
      p4rt.getForwardingPipelineConfig(
        GetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(deviceId)
          .setResponseType(GetForwardingPipelineConfigRequest.ResponseType.ALL)
          .build()
      )

    suspend fun injectPacket(deviceId: Long, ingressPort: Int, payload: ByteArray) =
      dataplane.injectPacket(
        InjectPacketRequest.newBuilder()
          .setDeviceId(deviceId)
          .setDataplaneIngressPort(ingressPort)
          .setPayload(ByteString.copyFrom(payload))
          .build()
      )

    suspend fun injectPackets(requests: Flow<InjectPacketRequest>) {
      dataplane.injectPackets(requests)
    }

    fun streamChannel(requests: Flow<StreamMessageRequest>): Flow<StreamMessageResponse> =
      p4rt.streamChannel(requests)

    suspend fun readTableEntries(deviceId: Long): List<Entity> {
      val entities = mutableListOf<Entity>()
      p4rt
        .read(
          ReadRequest.newBuilder()
            .setDeviceId(deviceId)
            .addEntities(Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(0)))
            .build()
        )
        .toList()
        .forEach { entities.addAll(it.entitiesList) }
      return entities
    }

    override fun close() {
      channel.shutdownNow()
      server.shutdownNow()
      executor.shutdownNow()
      registry.close()
    }
  }
}
