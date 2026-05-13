package fourward.e2e.p4runtimediff

import fourward.grpc.FourwardServer
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import java.util.concurrent.TimeUnit
import p4.v1.P4RuntimeGrpc

/**
 * Wraps an in-process 4ward [FourwardServer] for the diff harness, exposing the same gRPC surface
 * as [Bmv2P4RuntimeRunner]. Uses NettyChannelBuilder over loopback rather than the in-process
 * channel so transport behaviour is comparable to the BMv2 side.
 */
class FourwardP4RuntimeRunner(deviceId: Long, grpcPort: Int = allocatePort()) : P4RuntimeRunner {

  val server: FourwardServer = FourwardServer(port = grpcPort, deviceId = deviceId).start()
  val channel: ManagedChannel =
    NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build()
  override val stub: P4RuntimeGrpc.P4RuntimeBlockingStub = P4RuntimeGrpc.newBlockingStub(channel)

  override fun close() {
    channel.shutdown()
    if (!channel.awaitTermination(2, TimeUnit.SECONDS)) channel.shutdownNow()
    server.stop()
  }
}
