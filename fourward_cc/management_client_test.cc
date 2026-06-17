// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/management_client.h"

#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
#include "fourward_cc/fourward_server.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"

namespace fourward {
namespace {

using ::testing::ElementsAre;

class ManagementClientTest : public ::testing::Test {
 protected:
  void SetUp() override {
    absl::StatusOr<FourwardServer> s = FourwardServer::Start();
    ASSERT_TRUE(s.ok()) << s.status();
    server_ = std::make_unique<FourwardServer>(*std::move(s));
  }

  std::unique_ptr<FourwardServer> server_;
};

TEST_F(ManagementClientTest, ListsDefaultDevice) {
  ManagementClient client(*server_);

  absl::StatusOr<std::vector<uint64_t>> devices = client.ListDevices();
  ASSERT_TRUE(devices.ok()) << devices.status();
  EXPECT_THAT(*devices, ElementsAre(server_->DeviceId()));
}

TEST_F(ManagementClientTest, CreatesAndDeletesContiguousDevices) {
  ManagementClient client(*server_);

  EXPECT_TRUE(client.CreateDevices({.first_device_id = 2, .count = 3}).ok());
  absl::StatusOr<std::vector<uint64_t>> created = client.ListDevices();
  ASSERT_TRUE(created.ok()) << created.status();
  EXPECT_THAT(*created, ElementsAre(1, 2, 3, 4));

  absl::Status duplicate = client.CreateDevices({.first_device_id = 3, .count = 1});
  EXPECT_EQ(duplicate.code(), absl::StatusCode::kAlreadyExists) << duplicate;

  EXPECT_TRUE(client.DeleteDevices({.first_device_id = 2, .count = 2}).ok());
  absl::StatusOr<std::vector<uint64_t>> remaining = client.ListDevices();
  ASSERT_TRUE(remaining.ok()) << remaining.status();
  EXPECT_THAT(*remaining, ElementsAre(1, 4));
}

TEST_F(ManagementClientTest, RejectsDeviceIdZero) {
  ManagementClient client(*server_);

  absl::Status status = client.CreateDevices({.first_device_id = 0, .count = 1});
  EXPECT_EQ(status.code(), absl::StatusCode::kInvalidArgument) << status;
}

}  // namespace
}  // namespace fourward
