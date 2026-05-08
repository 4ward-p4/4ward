package fourward.cli

import fourward.grpc.FourwardServer
import fourward.stf.StfFile
import fourward.stf.installStfEntries
import fourward.stf.loadPipelineConfig
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * `4ward network serve test.nstf` — start P4Runtime servers for a multi-switch network.
 *
 * Parses a `.nstf` file, starts one P4Runtime gRPC server per switch (pre-loaded with pipeline
 * configs and table entries from the .nstf), prints a port map, and blocks until shutdown.
 * Controllers connect to individual switches via standard P4Runtime RPCs.
 *
 * Each switch operates independently — cross-switch packet forwarding is not wired through
 * P4Runtime in this version. Use `4ward network <test.nstf>` for cross-switch simulation.
 */
fun networkServe(nstfPath: Path, basePort: Int): Int {
  val nstf =
    try {
      NetworkStf.parse(nstfPath)
    } catch (e: FileNotFoundException) {
      System.err.println("error: ${e.message}")
      return ExitCode.USAGE_ERROR
    } catch (e: NoSuchFileException) {
      System.err.println("error: $nstfPath: no such file")
      return ExitCode.USAGE_ERROR
    } catch (e: IllegalArgumentException) {
      System.err.println("error: $nstfPath: ${e.message}")
      return ExitCode.USAGE_ERROR
    } catch (e: IllegalStateException) {
      System.err.println("error: $nstfPath: ${e.message}")
      return ExitCode.USAGE_ERROR
    }

  val servers =
    try {
      startNetworkServers(nstf, basePort)
    } catch (e: NetworkServeException) {
      System.err.println("error: ${e.message}")
      return e.exitCode
    }

  println("Network P4Runtime servers started:")
  for ((idx, sw) in nstf.switches.withIndex()) {
    println("  ${sw.id} -> localhost:${servers[idx].port()} (device_id=${idx + 1})")
  }
  println("\nPress Ctrl+C to stop.")

  servers.first().blockUntilShutdown()
  return ExitCode.SUCCESS
}

/** Starts one P4Runtime server per switch, pre-loaded with pipelines and entries. */
fun startNetworkServers(nstf: NetworkStf, basePort: Int): List<FourwardServer> {
  val servers = mutableListOf<FourwardServer>()

  for ((idx, sw) in nstf.switches.withIndex()) {
    // Port 0 = ephemeral (each server gets its own OS-assigned port).
    val port = if (basePort == 0) 0 else basePort + idx
    val server = FourwardServer(port = port, deviceId = idx.toLong() + 1)
    // Register for cleanup before any fallible work below, so a failure
    // tears down this server along with the previously-built ones.
    servers.add(server)

    @Suppress("TooGenericExceptionCaught") // uniform CLI error for any setup failure
    try {
      val config = loadPipelineConfig(sw.pipelinePath)
      server.simulator.loadPipeline(config)
      if (sw.stfPath != null) {
        val stf = StfFile.parse(sw.stfPath)
        installStfEntries(server.simulator, stf, config.p4Info)
      }
      val inlineLines = nstf.inlineEntries[sw.id]
      if (inlineLines != null) {
        val stf = StfFile.parse(inlineLines)
        installStfEntries(server.simulator, stf, config.p4Info)
      }
    } catch (e: Exception) {
      servers.forEach { it.stop() }
      throw NetworkServeException("switch '${sw.id}': ${e.message}", ExitCode.INTERNAL_ERROR, e)
    }

    @Suppress("TooGenericExceptionCaught") // gRPC bind failure etc. — any throw should tear down
    try {
      server.start()
    } catch (e: Exception) {
      servers.forEach { it.stop() }
      throw NetworkServeException(
        "switch '${sw.id}': failed to start on port $port: ${e.message}",
        ExitCode.INTERNAL_ERROR,
        e,
      )
    }
  }

  return servers
}

class NetworkServeException(message: String, val exitCode: Int, cause: Throwable) :
  RuntimeException(message, cause)
