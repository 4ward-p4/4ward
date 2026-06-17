package fourward.grpc

import io.grpc.Status
import java.nio.file.Path

data class DeviceSettings(
  val constraintValidatorBinary: Path? = null,
  val dropPortConfig: PortOverride? = null,
  val cpuPortConfig: CpuPortConfig = CpuPortConfig.Auto,
  val disableRefersToChecking: Boolean = false,
  val disableP4ConstraintsChecking: Boolean = false,
)

class DeviceRegistry(
  private val settings: DeviceSettings,
  private val defaultDeviceId: Long = P4RuntimeService.DEFAULT_DEVICE_ID,
  private val maxDevices: Int = DEFAULT_MAX_DEVICES,
  private val maxDevicesPerRequest: Int = DEFAULT_MAX_DEVICES_PER_REQUEST,
) : AutoCloseable {
  private val devices = sortedMapOf<Long, DeviceContext>()

  init {
    require(defaultDeviceId != 0L) { "default device ID must be nonzero" }
    devices[defaultDeviceId] = DeviceContext(defaultDeviceId, settings)
  }

  @Synchronized
  fun get(deviceId: Long): DeviceContext {
    requireValidDeviceId(deviceId)
    return devices[deviceId]
      ?: throw Status.NOT_FOUND.withDescription("unknown device_id $deviceId").asException()
  }

  fun defaultDevice(): DeviceContext = get(defaultDeviceId)

  @Synchronized
  fun createDevices(firstDeviceId: Long, count: Int) {
    val ids = deviceIds(firstDeviceId, count)
    val existing = ids.firstOrNull { devices.containsKey(it) }
    if (existing != null) {
      throw Status.ALREADY_EXISTS.withDescription("device_id $existing already exists")
        .asException()
    }
    if (devices.size + ids.size > maxDevices) {
      throw Status.RESOURCE_EXHAUSTED.withDescription(
          "creating ${ids.size} devices would exceed max live devices $maxDevices"
        )
        .asException()
    }
    val newDevices = ids.map { id -> id to DeviceContext(id, settings) }
    for ((id, device) in newDevices) {
      devices[id] = device
    }
  }

  fun deleteDevices(firstDeviceId: Long, count: Int) {
    val removed =
      synchronized(this) {
        val ids = deviceIds(firstDeviceId, count)
        val missing = ids.firstOrNull { !devices.containsKey(it) }
        if (missing != null) {
          throw Status.NOT_FOUND.withDescription("unknown device_id $missing").asException()
        }
        ids.map { devices.remove(it)!! }
      }
    for (device in removed) {
      device.closeDeleted()
    }
  }

  @Synchronized fun listDeviceIds(): List<Long> = devices.keys.toList()

  override fun close() {
    val existing =
      synchronized(this) {
        val snapshot = devices.values.toList()
        devices.clear()
        snapshot
      }
    for (device in existing) {
      device.close()
    }
  }

  private fun deviceIds(firstDeviceId: Long, count: Int): List<Long> {
    requireValidDeviceId(firstDeviceId)
    if (count <= 0) {
      throw Status.INVALID_ARGUMENT.withDescription("count must be positive").asException()
    }
    if (count > maxDevicesPerRequest) {
      throw Status.RESOURCE_EXHAUSTED.withDescription(
          "count $count exceeds max devices per request $maxDevicesPerRequest"
        )
        .asException()
    }
    val first = firstDeviceId.toULong()
    val countMinusOne = (count - 1).toUInt().toULong()
    if (ULong.MAX_VALUE - first < countMinusOne) {
      throw Status.INVALID_ARGUMENT.withDescription("device ID range overflows uint64")
        .asException()
    }
    return List(count) { (first + it.toUInt().toULong()).toLong() }
  }

  private fun requireValidDeviceId(deviceId: Long) {
    if (deviceId == 0L) {
      throw Status.INVALID_ARGUMENT.withDescription("device_id must be nonzero").asException()
    }
  }

  companion object {
    const val DEFAULT_MAX_DEVICES = 10_000
    const val DEFAULT_MAX_DEVICES_PER_REQUEST = 10_000
  }
}
