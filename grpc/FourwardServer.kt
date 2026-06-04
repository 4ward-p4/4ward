package fourward.grpc

import fourward.simulator.Simulator
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex

/** Wraps a P4Runtime + Dataplane gRPC server backed by a 4ward [Simulator]. */
class FourwardServer(
  private val port: Int = DEFAULT_PORT,
  private val deviceId: Long = P4RuntimeService.DEFAULT_DEVICE_ID,
  dropPortOverride: PortOverride? = null,
  cpuPortConfig: CpuPortConfig = CpuPortConfig.Auto,
  private val disableRefersToChecking: Boolean = false,
  private val disableP4ConstraintsChecking: Boolean = false,
  private val maxMetadataSize: Int = DEFAULT_MAX_METADATA_SIZE,
  private val maxReceiveMessageSize: Int = DEFAULT_MAX_RECEIVE_MESSAGE_SIZE,
  private val permitKeepaliveWithoutCalls: Boolean = DEFAULT_PERMIT_KEEPALIVE_WITHOUT_CALLS,
  private val permitKeepaliveTimeMs: Long = DEFAULT_PERMIT_KEEPALIVE_TIME_MS,
) {

  /**
   * The underlying simulator. Exposed for pre-configuration (loading pipelines, installing entries)
   * before the server starts accepting RPCs.
   */
  val simulator = Simulator()
  private val writeMutex = Mutex()
  private val broker =
    PacketBroker(
      { ingressPort, packet -> simulator.processPacket(ingressPort, packet) },
      writeMutex,
    )
  private val service =
    P4RuntimeService(
      simulator,
      broker,
      writeMutex = writeMutex,
      deviceId = deviceId,
      cpuPortConfig = cpuPortConfig,
      dropPortConfig = dropPortOverride,
      disableRefersToChecking = disableRefersToChecking,
      disableP4ConstraintsChecking = disableP4ConstraintsChecking,
    )
  private val dataplaneService =
    DataplaneService(broker) {
      val config = simulator.pipelineConfig ?: return@DataplaneService null
      val tableStore = simulator.tableStore ?: return@DataplaneService null
      DataplaneService.PipelineSnapshot(
        config,
        tableStore,
        service.typeTranslator,
        service.packetHeaderCodec,
      )
    }

  init {
    // Wire P4RuntimeService lambdas into the broker for hook support.
    broker.readAllEntities = { service.readAllEntities() }
    broker.readP4Info = { service.p4Info() }
    broker.applyUpdates = { updates -> service.applyHookUpdates(updates) }
  }

  private lateinit var server: Server

  fun start(): FourwardServer {
    val inboundMessageSize =
      if (maxReceiveMessageSize == -1) Int.MAX_VALUE else maxReceiveMessageSize
    server =
      NettyServerBuilder.forPort(port)
        // Without a dedicated executor, gRPC-Java runs RPC handlers on Netty's
        // I/O event loop threads. Suspended Kotlin coroutines (e.g. a StreamChannel
        // awaiting the next client message) can prevent other RPCs on the same
        // HTTP/2 connection from being dispatched.
        .executor(Executors.newCachedThreadPool())
        .maxInboundMetadataSize(maxMetadataSize)
        .maxInboundMessageSize(inboundMessageSize)
        .permitKeepAliveWithoutCalls(permitKeepaliveWithoutCalls)
        .permitKeepAliveTime(permitKeepaliveTimeMs, TimeUnit.MILLISECONDS)
        .addService(service)
        .addService(dataplaneService)
        .build()
        .start()
    Runtime.getRuntime().addShutdownHook(Thread { stop() })
    return this
  }

  fun stop() {
    if (::server.isInitialized) {
      server.shutdown()
    }
  }

  fun blockUntilShutdown() {
    server.awaitTermination()
  }

  fun port(): Int = server.port

  companion object {
    const val DEFAULT_PORT = 9559
    const val DEFAULT_MAX_METADATA_SIZE = 10 * 1024 * 1024 // 10MB
    const val DEFAULT_MAX_RECEIVE_MESSAGE_SIZE = -1 // unlimited
    const val DEFAULT_PERMIT_KEEPALIVE_WITHOUT_CALLS = true
    const val DEFAULT_PERMIT_KEEPALIVE_TIME_MS = 0L
  }
}

fun main(args: Array<String>) {
  // Netty's MacAddressUtil logs a WARNING to stderr when it can't find a usable
  // hardware address (common in sandboxed test environments). Providing a fixed
  // machine ID suppresses the lookup entirely.
  System.setProperty("io.netty.machineId", "00:00:00:00:00:00:00:00")

  val port = requireFlag(args, "--port", String::toIntOrNull) ?: FourwardServer.DEFAULT_PORT
  val deviceId =
    requireFlag(args, "--device-id", String::toLongOrNull) ?: P4RuntimeService.DEFAULT_DEVICE_ID
  val dropPort = flagValue(args, "--drop-port")?.let(PortOverride::fromFlag)
  val cpuPortConfig = CpuPortConfig.fromFlag(flagValue(args, "--cpu-port"))
  val portFile = flagValue(args, "--port-file")?.let(Path::of)
  val disableRefersToChecking = flagPresent(args, "--disable-refers-to-checking")
  val disableP4ConstraintsChecking = flagPresent(args, "--disable-p4-constraints-checking")
  val maxMetadataSize =
    requireFlag(args, "--max-metadata-size", String::toIntOrNull)
      ?: FourwardServer.DEFAULT_MAX_METADATA_SIZE
  val maxReceiveMessageSize =
    requireFlag(args, "--max-receive-message-size", String::toIntOrNull)
      ?: FourwardServer.DEFAULT_MAX_RECEIVE_MESSAGE_SIZE
  val permitKeepaliveWithoutCalls =
    requireFlag(args, "--permit-keepalive-without-calls", String::toBooleanStrictOrNull)
      ?: FourwardServer.DEFAULT_PERMIT_KEEPALIVE_WITHOUT_CALLS
  val permitKeepaliveTimeMs =
    requireFlag(args, "--permit-keepalive-time-ms", String::toLongOrNull)
      ?: FourwardServer.DEFAULT_PERMIT_KEEPALIVE_TIME_MS

  val server =
    FourwardServer(
        port,
        deviceId,
        dropPort,
        cpuPortConfig,
        disableRefersToChecking = disableRefersToChecking,
        disableP4ConstraintsChecking = disableP4ConstraintsChecking,
        maxMetadataSize = maxMetadataSize,
        maxReceiveMessageSize = maxReceiveMessageSize,
        permitKeepaliveWithoutCalls = permitKeepaliveWithoutCalls,
        permitKeepaliveTimeMs = permitKeepaliveTimeMs,
      )
      .start()
  println("P4Runtime server listening on port ${server.port()}")

  // Machine-readable readiness signal for embedders. Write the port to a temp
  // file and rename into place atomically so a concurrent reader never sees a
  // partial value. See fourward_cc/fourward_server.h for the embedding API.
  portFile?.let { writePortFileAtomic(it, server.port()) }

  server.blockUntilShutdown()
}

private fun writePortFileAtomic(path: Path, port: Int) {
  val tmp = path.resolveSibling("${path.fileName}.tmp")
  Files.writeString(tmp, port.toString())
  Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private fun flagValue(args: Array<String>, flag: String): String? =
  args.find { it.startsWith("$flag=") }?.substringAfter("=")

private fun flagPresent(args: Array<String>, flag: String): Boolean = args.any { it == flag }

private fun <T> requireFlag(args: Array<String>, flag: String, parse: (String) -> T?): T? {
  val raw = flagValue(args, flag) ?: return null
  return parse(raw) ?: error("invalid $flag value: $raw")
}
