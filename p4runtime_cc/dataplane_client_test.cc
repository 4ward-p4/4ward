// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "p4runtime_cc/dataplane_client.h"

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
#include "absl/types/span.h"
#include "gtest/gtest.h"
#include "p4runtime/dataplane.pb.h"
#include "p4runtime_cc/fourward_server.h"

namespace fourward {
namespace {

class DataplaneClientTest : public ::testing::Test {
 protected:
  void SetUp() override {
    absl::StatusOr<FourwardServer> s = FourwardServer::Start();
    ASSERT_TRUE(s.ok()) << s.status();
    server_ = std::make_unique<FourwardServer>(*std::move(s));
  }

  std::unique_ptr<FourwardServer> server_;
};

TEST_F(DataplaneClientTest, ConstructAndDestroy) {
  DataplaneClient client(*server_);
}

TEST_F(DataplaneClientTest, SubscribeResultsConfirmsActiveAndCancelsCleanly) {
  DataplaneClient client(*server_);

  absl::StatusOr<ResultStream> stream = client.SubscribeResults();
  ASSERT_TRUE(stream.ok()) << stream.status();

  // No pipeline loaded — no results will arrive.
  absl::StatusOr<fourward::ProcessPacketResult> next =
      stream->Next(absl::Milliseconds(50));
  ASSERT_FALSE(next.ok());
  EXPECT_EQ(next.status().code(), absl::StatusCode::kDeadlineExceeded)
      << next.status();

  // Destruction must cancel the RPC and join the reader thread.
}

TEST_F(DataplaneClientTest, SubscribeResultsRespectsStartupTimeout) {
  DataplaneClient client(*server_);

  absl::StatusOr<ResultStream> stream =
      client.SubscribeResults(absl::Nanoseconds(1));
  ASSERT_FALSE(stream.ok());
  EXPECT_EQ(stream.status().code(), absl::StatusCode::kDeadlineExceeded)
      << stream.status();
}

TEST_F(DataplaneClientTest, InjectPacketDataplanePortDeadlinePropagated) {
  DataplaneClient client(*server_);

  absl::StatusOr<fourward::InjectPacketResponse> resp =
      client.InjectPacket(
          {.ingress_port = DataplanePort{.port = 0}, .payload = "x"},
          absl::Nanoseconds(1));
  ASSERT_FALSE(resp.ok());
  EXPECT_EQ(resp.status().code(), absl::StatusCode::kDeadlineExceeded)
      << resp.status();
}

TEST_F(DataplaneClientTest, InjectPacketP4RuntimePortDeadlinePropagated) {
  DataplaneClient client(*server_);

  absl::StatusOr<fourward::InjectPacketResponse> resp =
      client.InjectPacket(
          {.ingress_port = P4RuntimePort{.port = std::string("\x00\x01", 2)},
           .payload = "x"},
          absl::Nanoseconds(1));
  ASSERT_FALSE(resp.ok());
  EXPECT_EQ(resp.status().code(), absl::StatusCode::kDeadlineExceeded)
      << resp.status();
}

TEST_F(DataplaneClientTest, InjectPacketsAcceptsEmptyBatch) {
  DataplaneClient client(*server_);

  std::vector<InjectPacketArgs> empty;
  absl::Status status = client.InjectPackets(absl::MakeConstSpan(empty));
  EXPECT_TRUE(status.ok()) << status;
}

TEST_F(DataplaneClientTest, InjectPacketsNonEmptyBatchDeadlinePropagated) {
  DataplaneClient client(*server_);

  std::vector<InjectPacketArgs> batch = {
      {.ingress_port = DataplanePort{.port = 0}, .payload = "a"},
      {.ingress_port = DataplanePort{.port = 1}, .payload = "b"},
      {.ingress_port = P4RuntimePort{.port = std::string("\x00\x02", 2)},
       .payload = "c"},
  };
  absl::Status status =
      client.InjectPackets(absl::MakeConstSpan(batch), absl::Nanoseconds(1));
  ASSERT_FALSE(status.ok());
  EXPECT_EQ(status.code(), absl::StatusCode::kDeadlineExceeded) << status;
}

TEST_F(DataplaneClientTest, MoveConstructionPreservesStub) {
  DataplaneClient original(*server_);
  DataplaneClient moved = std::move(original);

  absl::StatusOr<ResultStream> stream = moved.SubscribeResults();
  EXPECT_TRUE(stream.ok()) << stream.status();
}

TEST_F(DataplaneClientTest, ResultStreamMoveConstructionPreservesStream) {
  DataplaneClient client(*server_);
  absl::StatusOr<ResultStream> original = client.SubscribeResults();
  ASSERT_TRUE(original.ok()) << original.status();

  ResultStream moved = *std::move(original);

  absl::StatusOr<fourward::ProcessPacketResult> next =
      moved.Next(absl::Milliseconds(50));
  ASSERT_FALSE(next.ok());
  EXPECT_EQ(next.status().code(), absl::StatusCode::kDeadlineExceeded)
      << next.status();
}

}  // namespace
}  // namespace fourward
