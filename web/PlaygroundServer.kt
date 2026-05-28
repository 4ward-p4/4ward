package fourward.web

import fourward.grpc.DataplaneService
import fourward.grpc.P4RuntimeService
import fourward.grpc.PacketBroker
import fourward.grpc.PortOverride
import fourward.simulator.Simulator
import io.grpc.netty.NettyServerBuilder
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

/**
 * 4ward Playground — combined gRPC + HTTP server.
 *
 * Runs two servers in the same process sharing a single [Simulator]:
 * - **gRPC** (port 9559): standard P4Runtime + Dataplane services for CLI/controller use.
 * - **HTTP** (port 8080): REST API + web UI for the interactive playground.
 */
fun main(args: Array<String>) {
  val httpPort = flagValue(args, "--http-port")?.toIntOrNull() ?: WebServer.DEFAULT_HTTP_PORT
  val grpcPort =
    flagValue(args, "--grpc-port")?.toIntOrNull() ?: fourward.grpc.FourwardServer.DEFAULT_PORT
  val staticDir = flagValue(args, "--static-dir")?.let { Path.of(it) }

  val dropPort = flagValue(args, "--drop-port")?.let(PortOverride::fromFlag)
  val cpuPortConfig = fourward.grpc.CpuPortConfig.fromFlag(flagValue(args, "--cpu-port"))

  val simulator = Simulator()
  val writeMutex = kotlinx.coroutines.sync.Mutex()
  val broker =
    PacketBroker(
      { ingressPort, payload, payloadBitLength ->
        simulator.processPacket(ingressPort, payload, payloadBitLength)
      },
      writeMutex,
    )
  val service =
    P4RuntimeService(
      simulator,
      broker,
      writeMutex = writeMutex,
      cpuPortConfig = cpuPortConfig,
      dropPortConfig = dropPort,
    )
  val dataplaneService =
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
  broker.readAllEntities = { service.readAllEntities() }
  broker.readP4Info = { service.p4Info() }
  broker.applyUpdates = { updates -> service.applyHookUpdates(updates) }

  // Start gRPC server.
  val grpcServer =
    NettyServerBuilder.forPort(grpcPort)
      .addService(service)
      .addService(dataplaneService)
      .build()
      .start()

  // Start HTTP server.
  val webServer =
    WebServer(simulator = simulator, service = service, httpPort = httpPort, staticDir = staticDir)
      .start()

  val url = "http://localhost:$httpPort"
  println("4ward Playground")
  println("  Web UI:   $url")
  println("  gRPC:     localhost:${grpcServer.port}")
  if (staticDir != null) println("  Static:   $staticDir")

  // Open browser automatically.
  try {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
  } catch (_: Exception) {
    // Best-effort — headless environments won't have a desktop.
  }

  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        webServer.stop()
        grpcServer.shutdown()
        service.close()
      }
    )

  grpcServer.awaitTermination()
}

private fun flagValue(args: Array<String>, flag: String): String? =
  args.find { it.startsWith("$flag=") }?.substringAfter("=")
