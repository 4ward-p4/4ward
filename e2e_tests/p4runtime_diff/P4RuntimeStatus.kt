package fourward.e2e.p4runtimediff

import com.google.rpc.Status as RpcStatus
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.p4.v1.Error

/** Returns the single per-item canonical status from a P4Runtime batch error, if present. */
fun StatusRuntimeException.singleBatchItemStatusCode(): Status.Code? {
  val trailers = trailers ?: return null
  val key = Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER)
  val bytes = trailers.get(key) ?: return null
  val rpcStatus = RpcStatus.parseFrom(bytes)
  if (rpcStatus.detailsCount != 1) return null
  val error = Error.parseFrom(rpcStatus.getDetails(0).value)
  return Status.Code.values().find { it.value() == error.canonicalCode }
}
