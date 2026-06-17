package fourward.grpc

import fourward.simulator.Simulator
import io.grpc.Status
import kotlinx.coroutines.sync.Mutex

/** Per-device 4ward runtime state. */
class DeviceContext(val deviceId: Long, private val settings: DeviceSettings) : AutoCloseable {
  val simulator = Simulator()
  private val writeMutex = Mutex()
  val broker =
    PacketBroker(
      { ingressPort, packet -> simulator.processPacket(ingressPort, packet) },
      writeMutex,
    )
  val p4RuntimeService =
    P4RuntimeService(
      simulator,
      broker,
      settings.constraintValidatorBinary,
      writeMutex = writeMutex,
      deviceId = deviceId,
      cpuPortConfig = settings.cpuPortConfig,
      dropPortConfig = settings.dropPortConfig,
      disableRefersToChecking = settings.disableRefersToChecking,
      disableP4ConstraintsChecking = settings.disableP4ConstraintsChecking,
    )
  val dataplaneService =
    DataplaneService(broker) {
      val config = simulator.pipelineConfig ?: return@DataplaneService null
      val tableStore = simulator.tableStore ?: return@DataplaneService null
      DataplaneService.PipelineSnapshot(
        config,
        tableStore,
        p4RuntimeService.typeTranslator,
        p4RuntimeService.packetHeaderCodec,
      )
    }

  init {
    broker.readAllEntities = { p4RuntimeService.readAllEntities() }
    broker.readP4Info = { p4RuntimeService.p4Info() }
    broker.applyUpdates = { updates -> p4RuntimeService.applyHookUpdates(updates) }
  }

  fun closeDeleted() {
    p4RuntimeService.closeStreams(
      Status.NOT_FOUND.withDescription("device_id $deviceId was deleted").asException()
    )
    close()
  }

  override fun close() {
    p4RuntimeService.close()
  }
}
