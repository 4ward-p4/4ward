package fourward.e2e.p4runtimediff

import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.netty.NettyChannelBuilder
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import p4.v1.P4RuntimeGrpc
import p4.v1.P4RuntimeOuterClass.CapabilitiesRequest

/**
 * Spawns BMv2's `simple_switch_grpc` as a subprocess and exposes a P4Runtime gRPC stub. Mirrors the
 * lifecycle of [fourward.e2e.bmv2.Bmv2Runner] but speaks P4Runtime over gRPC instead of bm_runtime
 * over stdin/stdout.
 *
 * The binary is built by `//e2e_tests/p4runtime_diff:simple_switch_grpc` and made available to
 * tests via `data` deps. CLI:
 *
 *     simple_switch_grpc --device-id <N> --no-p4 -i <port>@<iface>... \
 *         -- --grpc-server-addr 0.0.0.0:<P>
 *
 * The `--no-p4` flag tells the binary to start without an initial pipeline; the test sends
 * `SetForwardingPipelineConfig` over gRPC.
 */
class Bmv2P4RuntimeRunner(
  binary: Path,
  deviceId: Long,
  grpcPort: Int = allocatePort(),
  startupTimeoutSeconds: Long = 10,
) : P4RuntimeRunner {

  private val process: Process
  val channel: ManagedChannel
  override val stub: P4RuntimeGrpc.P4RuntimeBlockingStub

  init {
    require(binary.toFile().canExecute()) { "simple_switch_grpc not executable at $binary" }
    process =
      ProcessBuilder(
          binary.toString(),
          "--device-id",
          deviceId.toString(),
          "--no-p4",
          "--",
          "--grpc-server-addr",
          "0.0.0.0:$grpcPort",
        )
        .redirectErrorStream(true)
        .start()

    channel = NettyChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build()
    stub = P4RuntimeGrpc.newBlockingStub(channel)

    waitForGrpcReady(startupTimeoutSeconds)
  }

  private fun waitForGrpcReady(timeoutSeconds: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (System.nanoTime() < deadline) {
      if (!process.isAlive) {
        val output = process.inputStream.bufferedReader().readText()
        error("simple_switch_grpc exited before becoming ready:\n$output")
      }
      try {
        stub
          .withDeadlineAfter(POLL_DEADLINE_MS, TimeUnit.MILLISECONDS)
          .capabilities(CapabilitiesRequest.getDefaultInstance())
        return
      } catch (@Suppress("SwallowedException") _: StatusRuntimeException) {
        // Server not yet listening or still starting — retry until the deadline.
        Thread.sleep(POLL_INTERVAL_MS)
      }
    }
    error("simple_switch_grpc did not respond within $timeoutSeconds s")
  }

  override fun close() {
    channel.shutdown()
    if (!channel.awaitTermination(2, TimeUnit.SECONDS)) channel.shutdownNow()
    process.destroy()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
  }

  companion object {
    private const val POLL_DEADLINE_MS = 500L
    private const val POLL_INTERVAL_MS = 100L
  }
}
