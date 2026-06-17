package fourward.grpc

import fourward.CreateDevicesRequest
import fourward.CreateDevicesResponse
import fourward.DeleteDevicesRequest
import fourward.DeleteDevicesResponse
import fourward.FourwardManagementGrpcKt
import fourward.ListDevicesRequest
import fourward.ListDevicesResponse

class ManagementService(private val registry: DeviceRegistry) :
  FourwardManagementGrpcKt.FourwardManagementCoroutineImplBase() {
  override suspend fun createDevices(request: CreateDevicesRequest): CreateDevicesResponse {
    registry.createDevices(request.firstDeviceId, request.count)
    return CreateDevicesResponse.getDefaultInstance()
  }

  override suspend fun deleteDevices(request: DeleteDevicesRequest): DeleteDevicesResponse {
    registry.deleteDevices(request.firstDeviceId, request.count)
    return DeleteDevicesResponse.getDefaultInstance()
  }

  override suspend fun listDevices(request: ListDevicesRequest): ListDevicesResponse =
    ListDevicesResponse.newBuilder().addAllDeviceIds(registry.listDeviceIds()).build()
}
