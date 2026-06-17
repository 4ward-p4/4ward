// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_CC_MANAGEMENT_CLIENT_H_
#define FOURWARD_CC_MANAGEMENT_CLIENT_H_

#include <cstdint>
#include <memory>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
#include "fourward_cc/fourward_server.h"
#include "grpc/management.grpc.pb.h"

namespace fourward {

// C++ wrapper for the 4ward management gRPC service.
//
// Most tests never need this: FourwardServer starts one default device. Use
// ManagementClient only when one server should host multiple logical devices,
// each addressed by the P4Runtime `device_id` field. This is useful for
// network-scale tests where one 4ward process emulates many independent
// switches.
class ManagementClient {
 public:
  struct DeviceRange {
    // First nonzero logical device ID in the contiguous range.
    uint64_t first_device_id;
    // Number of contiguous device IDs in the range.
    uint32_t count;
  };

  explicit ManagementClient(const FourwardServer& server,
                            absl::Duration default_timeout = absl::Seconds(10));
  explicit ManagementClient(std::unique_ptr<FourwardManagement::Stub> stub,
                            absl::Duration default_timeout = absl::Seconds(10));

  // Create/delete contiguous logical devices. Prefer designated initializers so
  // call sites spell out the range semantics:
  //   client.CreateDevices({.first_device_id = 2, .count = 100});
  absl::Status CreateDevices(DeviceRange devices);
  absl::Status DeleteDevices(DeviceRange devices);

  // Returns the currently live logical device IDs, including the default
  // device.
  absl::StatusOr<std::vector<uint64_t>> ListDevices();

 private:
  std::unique_ptr<FourwardManagement::Stub> stub_;
  absl::Duration default_timeout_;
};

}  // namespace fourward

#endif  // FOURWARD_CC_MANAGEMENT_CLIENT_H_
