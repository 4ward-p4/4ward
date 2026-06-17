// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/management_client.h"

#include <chrono>
#include <memory>
#include <utility>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
#include "grpc/management.pb.h"
#include "grpcpp/client_context.h"
#include "grpcpp/support/status.h"

namespace fourward {
namespace {

absl::Status ToAbsl(const grpc::Status& s) {
  if (s.ok()) return absl::OkStatus();
  return absl::Status(static_cast<absl::StatusCode>(s.error_code()),
                      s.error_message());
}

std::chrono::system_clock::time_point AbsoluteDeadline(
    absl::Duration relative) {
  return std::chrono::time_point_cast<std::chrono::system_clock::duration>(
      std::chrono::system_clock::now() + absl::ToChronoNanoseconds(relative));
}

}  // namespace

ManagementClient::ManagementClient(const FourwardServer& server,
                                   absl::Duration default_timeout)
    : ManagementClient(server.NewManagementStub(), default_timeout) {}

ManagementClient::ManagementClient(
    std::unique_ptr<FourwardManagement::Stub> stub,
    absl::Duration default_timeout)
    : stub_(std::move(stub)), default_timeout_(default_timeout) {}

absl::Status ManagementClient::CreateDevices(DeviceRange devices) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  CreateDevicesRequest req;
  req.set_first_device_id(devices.first_device_id);
  req.set_count(devices.count);
  CreateDevicesResponse resp;
  return ToAbsl(stub_->CreateDevices(&ctx, req, &resp));
}

absl::Status ManagementClient::DeleteDevices(DeviceRange devices) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  DeleteDevicesRequest req;
  req.set_first_device_id(devices.first_device_id);
  req.set_count(devices.count);
  DeleteDevicesResponse resp;
  return ToAbsl(stub_->DeleteDevices(&ctx, req, &resp));
}

absl::StatusOr<std::vector<uint64_t>> ManagementClient::ListDevices() {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  ListDevicesRequest req;
  ListDevicesResponse resp;
  grpc::Status status = stub_->ListDevices(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);
  return std::vector<uint64_t>(resp.device_ids().begin(), resp.device_ids().end());
}

}  // namespace fourward
