package fourward.e2e.p4runtimediff

import java.io.Closeable
import java.net.ServerSocket
import p4.v1.P4RuntimeGrpc

/**
 * Uniform handle on a P4Runtime gRPC server, used by the differential harness to drive 4ward and
 * BMv2 with the same test code. Implementations: [FourwardP4RuntimeRunner] (in-process),
 * [Bmv2P4RuntimeRunner] (subprocess).
 */
interface P4RuntimeRunner : Closeable {
  val stub: P4RuntimeGrpc.P4RuntimeBlockingStub
}

/** Returns an OS-allocated free TCP port. Subject to TOCTOU but acceptable for tests. */
fun allocatePort(): Int = ServerSocket(0).use { it.localPort }
