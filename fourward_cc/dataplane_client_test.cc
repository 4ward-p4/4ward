// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/dataplane_client.h"

#include <memory>
#include <string>
#include <utility>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
#include "gtest/gtest.h"
#include "grpc/dataplane.pb.h"
#include "fourward_cc/fourward_server.h"

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
  absl::StatusOr<ProcessPacketResult> next =
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

TEST_F(DataplaneClientTest, InjectPacketRespectsDefaultTimeout) {
  DataplaneClient client(*server_, absl::Nanoseconds(1));

  absl::StatusOr<InjectPacketResponse> resp =
      client.InjectPacket(DataplanePort{0}, "x");
  ASSERT_FALSE(resp.ok());
  EXPECT_EQ(resp.status().code(), absl::StatusCode::kDeadlineExceeded)
      << resp.status();
}

TEST_F(DataplaneClientTest, InjectPacketP4RuntimePortRespectsDefaultTimeout) {
  DataplaneClient client(*server_, absl::Nanoseconds(1));

  absl::StatusOr<InjectPacketResponse> resp =
      client.InjectPacket(P4RuntimePort{std::string("\x00\x01", 2)}, "x");
  ASSERT_FALSE(resp.ok());
  EXPECT_EQ(resp.status().code(), absl::StatusCode::kDeadlineExceeded)
      << resp.status();
}

TEST_F(DataplaneClientTest, InjectPacketsWriterFinishesCleanly) {
  DataplaneClient client(*server_);

  PacketWriter writer = client.InjectPackets();
  absl::StatusOr<int> count = writer.Finish();
  ASSERT_TRUE(count.ok()) << count.status();
  EXPECT_EQ(*count, 0);
}

TEST_F(DataplaneClientTest, InjectPacketsRespectsDefaultTimeout) {
  DataplaneClient client(*server_, absl::Nanoseconds(1));

  PacketWriter writer = client.InjectPackets();
  absl::Status inject = writer.Inject(DataplanePort{0}, "a");
  // Either the inject or finish will surface the deadline error.
  absl::StatusOr<int> finish = writer.Finish();
  EXPECT_TRUE(!inject.ok() || !finish.ok())
      << "inject: " << inject << ", finish: " << finish.status();
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

  absl::StatusOr<ProcessPacketResult> next =
      moved.Next(absl::Milliseconds(50));
  ASSERT_FALSE(next.ok());
  EXPECT_EQ(next.status().code(), absl::StatusCode::kDeadlineExceeded)
      << next.status();
}

}  // namespace
}  // namespace fourward
