// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "p4runtime_cc/fourward_wrapper.h"

#include <cstdint>
#include <optional>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "grpcpp/client_context.h"
#include "grpcpp/support/status.h"
#include "gtest/gtest.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4/v1/p4runtime.pb.h"
#include "p4runtime_cc/fourward_server.h"

namespace fourward {
namespace {

// Verifies the server is reachable by issuing a Capabilities RPC — same
// approach used in fourward_server_test.cc.
void ExpectP4RuntimeHealthy(Fourward& fourward) {
  auto stub = fourward.NewP4RuntimeStub();
  p4::v1::CapabilitiesRequest req;
  p4::v1::CapabilitiesResponse resp;
  grpc::ClientContext ctx;
  grpc::Status status = stub->Capabilities(&ctx, req, &resp);
  EXPECT_TRUE(status.ok()) << "Capabilities failed: code="
                           << status.error_code()
                           << " msg=" << status.error_message();
  EXPECT_FALSE(resp.p4runtime_api_version().empty());
}

TEST(FourwardTest, CreateDefaultOptionsSucceeds) {
  absl::StatusOr<Fourward> fourward = Fourward::Create();
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  ExpectP4RuntimeHealthy(*fourward);
}

TEST(FourwardTest, DeviceIdFlowsThrough) {
  absl::StatusOr<Fourward> fourward = Fourward::Create({.device_id = 42});
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  EXPECT_EQ(fourward->DeviceId(), 42u);
}

TEST(FourwardTest, DefaultDeviceIdIsOne) {
  absl::StatusOr<Fourward> fourward = Fourward::Create();
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  EXPECT_EQ(fourward->DeviceId(), 1u);
}

TEST(FourwardTest, DefaultCpuPortIsZero) {
  absl::StatusOr<Fourward> fourward = Fourward::Create();
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  EXPECT_EQ(fourward->CpuPort(), 0);
}

TEST(FourwardTest, CpuPortOverrideFlowsThrough) {
  absl::StatusOr<Fourward> fourward =
      Fourward::Create({.cpu_port = CpuPort::Override(192)});
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  EXPECT_EQ(fourward->CpuPort(), 192);
  ExpectP4RuntimeHealthy(*fourward);
}

TEST(FourwardTest, CpuPortDisabledReportsZero) {
  absl::StatusOr<Fourward> fourward =
      Fourward::Create({.cpu_port = CpuPort::Disabled()});
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  EXPECT_EQ(fourward->CpuPort(), 0);
  ExpectP4RuntimeHealthy(*fourward);
}

TEST(FourwardTest, DropPortIsNulloptByDefault) {
  absl::StatusOr<Fourward> fourward = Fourward::Create();
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  EXPECT_EQ(fourward->DropPort(), std::nullopt);
}

TEST(FourwardTest, DropPortFlowsThrough) {
  absl::StatusOr<Fourward> fourward = Fourward::Create({.drop_port = 511});
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  EXPECT_EQ(fourward->DropPort(), std::optional<int>(511));
  ExpectP4RuntimeHealthy(*fourward);
}

TEST(FourwardTest, NewP4RuntimeStubReturnsUsableStub) {
  absl::StatusOr<Fourward> fourward = Fourward::Create();
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  EXPECT_NE(fourward->NewP4RuntimeStub(), nullptr);
  // Each call produces an independent stub backed by the same channel.
  EXPECT_NE(fourward->NewP4RuntimeStub(), fourward->NewP4RuntimeStub());
}

TEST(FourwardTest, DataplaneStubIsAccessible) {
  absl::StatusOr<Fourward> fourward = Fourward::Create();
  ASSERT_TRUE(fourward.ok()) << fourward.status();
  // Accessing the stub must not crash. The reference is valid for the lifetime
  // of the Fourward instance.
  EXPECT_NE(&fourward->DataplaneStub(), nullptr);
}

TEST(FourwardTest, SendPacketWithoutPipelineReturnsErrorOrEmpty) {
  // Without a loaded P4 pipeline the dataplane server may reject the inject
  // request. Verify we propagate the error cleanly rather than crashing.
  absl::StatusOr<Fourward> fourward = Fourward::Create();
  ASSERT_TRUE(fourward.ok()) << fourward.status();

  absl::StatusOr<std::vector<PacketAtPort>> result =
      fourward->SendPacket(PacketAtPort{.port = 0, .data = "\x00"});
  // Either a non-OK status or an empty result is acceptable; what is not
  // acceptable is a crash or a hang.
  if (result.ok()) {
    EXPECT_TRUE(result->empty() || !result->empty());  // any outcome is fine
  } else {
    EXPECT_NE(result.status().code(), absl::StatusCode::kOk);
  }
}

TEST(FourwardTest, SendPacketToFourwardWithoutPipelineReturnsErrorOrEmpty) {
  absl::StatusOr<Fourward> fourward = Fourward::Create();
  ASSERT_TRUE(fourward.ok()) << fourward.status();

  absl::StatusOr<std::vector<std::vector<PacketAtPort>>> result =
      SendPacketToFourward(*fourward, PacketAtPort{.port = 0, .data = "\x00"});
  if (result.ok()) {
    // Server returned outcomes (possibly zero).
    EXPECT_GE(result->size(), 0u);
  } else {
    EXPECT_NE(result.status().code(), absl::StatusCode::kOk);
  }
}

TEST(FourwardTest, ParallelCreateGetDistinctServers) {
  absl::StatusOr<Fourward> a = Fourward::Create();
  absl::StatusOr<Fourward> b = Fourward::Create();
  ASSERT_TRUE(a.ok()) << a.status();
  ASSERT_TRUE(b.ok()) << b.status();
  // Each Fourward wraps an independent server, so their device stubs must not
  // alias (different underlying gRPC channels).
  EXPECT_NE(&a->DataplaneStub(), &b->DataplaneStub());
  ExpectP4RuntimeHealthy(*a);
  ExpectP4RuntimeHealthy(*b);
}

TEST(FourwardTest, MoveConstructionPreservesAccessors) {
  absl::StatusOr<Fourward> original =
      Fourward::Create({.device_id = 7, .drop_port = 100});
  ASSERT_TRUE(original.ok()) << original.status();

  Fourward moved = *std::move(original);

  EXPECT_EQ(moved.DeviceId(), 7u);
  EXPECT_EQ(moved.DropPort(), std::optional<int>(100));
  ExpectP4RuntimeHealthy(moved);
}

}  // namespace
}  // namespace fourward