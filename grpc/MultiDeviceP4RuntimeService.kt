package fourward.grpc

import io.grpc.Status
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import p4.v1.P4RuntimeGrpcKt
import p4.v1.P4RuntimeOuterClass.CapabilitiesRequest
import p4.v1.P4RuntimeOuterClass.CapabilitiesResponse
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse
import p4.v1.P4RuntimeOuterClass.WriteRequest
import p4.v1.P4RuntimeOuterClass.WriteResponse

class MultiDeviceP4RuntimeService(private val registry: DeviceRegistry) :
  P4RuntimeGrpcKt.P4RuntimeCoroutineImplBase() {
  override suspend fun setForwardingPipelineConfig(
    request: SetForwardingPipelineConfigRequest
  ): SetForwardingPipelineConfigResponse =
    registry.get(request.deviceId).p4RuntimeService.setForwardingPipelineConfig(request)

  override suspend fun write(request: WriteRequest): WriteResponse =
    registry.get(request.deviceId).p4RuntimeService.write(request)

  override fun read(request: ReadRequest): Flow<ReadResponse> =
    registry.get(request.deviceId).p4RuntimeService.read(request)

  override suspend fun getForwardingPipelineConfig(
    request: GetForwardingPipelineConfigRequest
  ): GetForwardingPipelineConfigResponse =
    registry.get(request.deviceId).p4RuntimeService.getForwardingPipelineConfig(request)

  override suspend fun capabilities(request: CapabilitiesRequest): CapabilitiesResponse {
    // Capabilities are server-wide. For nonzero device_id, still validate that
    // the addressed logical device exists.
    if (request.deviceId != 0L) {
      registry.get(request.deviceId)
    }
    return CapabilitiesResponse.newBuilder()
      .setP4RuntimeApiVersion(P4RuntimeService.P4RUNTIME_API_VERSION)
      .build()
  }

  override fun streamChannel(requests: Flow<StreamMessageRequest>): Flow<StreamMessageResponse> =
    channelFlow {
      var device: DeviceContext? = null
      val forwarded = Channel<StreamMessageRequest>(Channel.UNLIMITED)
      var delegateStarted = false

      suspend fun bind(first: StreamMessageRequest) {
        if (first.updateCase != StreamMessageRequest.UpdateCase.ARBITRATION) {
          throw Status.FAILED_PRECONDITION.withDescription(
              "first StreamChannel message must be MasterArbitrationUpdate"
            )
            .asException()
        }
        val context = registry.get(first.arbitration.deviceId)
        device = context
        delegateStarted = true
        launch {
          context.p4RuntimeService.streamChannel(forwarded.receiveAsFlow()).collect { send(it) }
        }
      }

      try {
        requests.collect { msg ->
          val boundDevice = device
          if (boundDevice == null) {
            bind(msg)
          } else if (
            msg.updateCase == StreamMessageRequest.UpdateCase.ARBITRATION &&
              msg.arbitration.deviceId != boundDevice.deviceId
          ) {
            throw Status.FAILED_PRECONDITION.withDescription(
                "StreamChannel already bound to device_id ${boundDevice.deviceId}; " +
                  "got device_id ${msg.arbitration.deviceId}"
              )
              .asException()
          }
          forwarded.send(msg)
        }
        if (!delegateStarted) {
          throw Status.FAILED_PRECONDITION.withDescription(
              "StreamChannel closed before MasterArbitrationUpdate"
            )
            .asException()
        }
      } finally {
        forwarded.close()
      }
    }
}
