package fourward.grpc

import fourward.DataplaneGrpcKt
import fourward.InjectPacketRequest
import fourward.InjectPacketResponse
import fourward.InjectPacketsResponse
import fourward.PrePacketHookInvocation
import fourward.PrePacketHookResponse
import fourward.Reproducer
import fourward.SubscribeResultsRequest
import fourward.SubscribeResultsResponse
import io.grpc.Status
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn

class MultiDeviceDataplaneService(
  private val registry: DeviceRegistry,
  private val defaultDeviceId: Long = P4RuntimeService.DEFAULT_DEVICE_ID,
) : DataplaneGrpcKt.DataplaneCoroutineImplBase() {
  override suspend fun injectPacket(request: InjectPacketRequest): InjectPacketResponse =
    service(request.deviceId).injectPacket(request)

  override suspend fun getReproducer(request: InjectPacketRequest): Reproducer =
    service(request.deviceId).getReproducer(request)

  override suspend fun injectPackets(requests: Flow<InjectPacketRequest>): InjectPacketsResponse =
    coroutineScope {
      val channel = requests.produceIn(this)
      val first =
        channel.receiveCatching().getOrNull()
          ?: return@coroutineScope InjectPacketsResponse.getDefaultInstance()
      val targetDeviceId = normalizedDeviceId(first.deviceId)
      val guardedRequests = flow {
        emit(first)
        for (request in channel) {
          val requestDeviceId = normalizedDeviceId(request.deviceId)
          if (requestDeviceId != targetDeviceId) {
            throw Status.INVALID_ARGUMENT.withDescription(
                "InjectPackets stream is bound to device_id $targetDeviceId; " +
                  "got device_id $requestDeviceId"
              )
              .asException()
          }
          emit(request)
        }
      }
      registry.get(targetDeviceId).dataplaneService.injectPackets(guardedRequests)
    }

  override fun subscribeResults(request: SubscribeResultsRequest): Flow<SubscribeResultsResponse> =
    service(request.deviceId).subscribeResults(request)

  override fun registerPrePacketHook(
    requests: Flow<PrePacketHookResponse>
  ): Flow<PrePacketHookInvocation> =
    // Pre-packet hooks are process-wide: this stream has no request envelope
    // where a client could put device_id.
    registry.defaultDevice().dataplaneService.registerPrePacketHook(requests)

  private fun service(deviceId: Long): DataplaneService =
    registry.get(normalizedDeviceId(deviceId)).dataplaneService

  private fun normalizedDeviceId(deviceId: Long): Long =
    if (deviceId == 0L) defaultDeviceId else deviceId
}
