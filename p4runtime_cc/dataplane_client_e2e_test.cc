// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

// End-to-end tests for DataplaneClient against a live FourwardServer with a
// loaded P4 pipeline. Uses the passthrough program (always forwards to port 1)
// so results are deterministic without any table entries.

#include "p4runtime_cc/dataplane_client.h"

#include <cstdint>
#include <fstream>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/time/time.h"
#include "gtest/gtest.h"
#include "grpcpp/client_context.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4/v1/p4runtime.pb.h"
#include "p4runtime/dataplane.pb.h"
#include "p4runtime_cc/fourward_server.h"
#include "tools/cpp/runfiles/runfiles.h"

#ifndef PASSTHROUGH_PIPELINE_RLOCATION
#error "PASSTHROUGH_PIPELINE_RLOCATION must be set by the BUILD rule"
#endif

namespace fourward {
namespace {

using ::bazel::tools::cpp::runfiles::Runfiles;

absl::Status ToAbsl(const grpc::Status& s) {
  if (s.ok()) return absl::OkStatus();
  return absl::Status(static_cast<absl::StatusCode>(s.error_code()),
                      s.error_message());
}

absl::StatusOr<std::string> ReadRunfile(const std::string& rlocation) {
  std::string error;
  std::unique_ptr<Runfiles> runfiles(Runfiles::Create("", &error));
  if (runfiles == nullptr) {
    return absl::InternalError(absl::StrCat("runfiles: ", error));
  }
  std::string path = runfiles->Rlocation(rlocation);
  if (path.empty()) {
    return absl::NotFoundError(
        absl::StrCat("not found in runfiles: ", rlocation));
  }
  std::ifstream file(path, std::ios::binary);
  if (!file) {
    return absl::NotFoundError(absl::StrCat("cannot open: ", path));
  }
  return std::string(std::istreambuf_iterator<char>(file),
                     std::istreambuf_iterator<char>());
}

// Pushes a ForwardingPipelineConfig to the server via the P4Runtime API.
// Handles the arbitration handshake.
absl::Status PushPipeline(const FourwardServer& server,
                          const p4::v1::ForwardingPipelineConfig& config) {
  auto stub = server.NewP4RuntimeStub();

  // Establish master arbitration.
  grpc::ClientContext stream_ctx;
  auto stream = stub->StreamChannel(&stream_ctx);
  p4::v1::StreamMessageRequest arb_req;
  arb_req.mutable_arbitration()->set_device_id(server.DeviceId());
  arb_req.mutable_arbitration()->mutable_election_id()->set_low(1);
  if (!stream->Write(arb_req)) {
    return absl::InternalError("failed to write arbitration request");
  }
  p4::v1::StreamMessageResponse arb_resp;
  if (!stream->Read(&arb_resp)) {
    return absl::InternalError("failed to read arbitration response");
  }

  // Push pipeline.
  grpc::ClientContext set_ctx;
  p4::v1::SetForwardingPipelineConfigRequest req;
  req.set_device_id(server.DeviceId());
  req.mutable_election_id()->set_low(1);
  req.set_action(
      p4::v1::SetForwardingPipelineConfigRequest::VERIFY_AND_COMMIT);
  *req.mutable_config() = config;
  p4::v1::SetForwardingPipelineConfigResponse resp;
  grpc::Status status =
      stub->SetForwardingPipelineConfig(&set_ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);

  stream_ctx.TryCancel();
  return absl::OkStatus();
}

// A minimal Ethernet frame: dst=00:00:00:00:00:01, src=00:00:00:00:00:02,
// ethertype=0x0800, plus 46 zero bytes of payload to reach the 64-byte
// minimum.
std::string MakeEthernetFrame() {
  std::string frame(64, '\0');
  frame[5] = 0x01;   // dst
  frame[11] = 0x02;  // src
  frame[12] = 0x08;  // ethertype high
  return frame;
}

class DataplaneClientE2ETest : public ::testing::Test {
 protected:
  void SetUp() override {
    absl::StatusOr<FourwardServer> s = FourwardServer::Start();
    ASSERT_TRUE(s.ok()) << s.status();
    server_ = std::make_unique<FourwardServer>(*std::move(s));

    // Load the passthrough pipeline.
    absl::StatusOr<std::string> binpb =
        ReadRunfile(PASSTHROUGH_PIPELINE_RLOCATION);
    ASSERT_TRUE(binpb.ok()) << binpb.status();
    p4::v1::ForwardingPipelineConfig config;
    ASSERT_TRUE(config.ParseFromString(*binpb))
        << "failed to parse ForwardingPipelineConfig";
    absl::Status push = PushPipeline(*server_, config);
    ASSERT_TRUE(push.ok()) << push;
  }

  std::unique_ptr<FourwardServer> server_;
};

TEST_F(DataplaneClientE2ETest, InjectPacketReturnsTraceAndOutputs) {
  DataplaneClient client(*server_);

  absl::StatusOr<fourward::dataplane::InjectPacketResponse> resp =
      client.InjectPacket({
          .ingress_port = DataplanePort{.port = 0},
          .payload = MakeEthernetFrame(),
      });
  ASSERT_TRUE(resp.ok()) << resp.status();

  // Passthrough forwards every packet to port 1.
  ASSERT_EQ(resp->possible_outcomes_size(), 1);
  ASSERT_EQ(resp->possible_outcomes(0).packets_size(), 1);
  EXPECT_EQ(resp->possible_outcomes(0).packets(0).dataplane_egress_port(), 1u);
  EXPECT_FALSE(resp->trace().DebugString().empty());
}

TEST_F(DataplaneClientE2ETest, SubscribeResultsDeliversInjectedPacket) {
  DataplaneClient client(*server_);

  absl::StatusOr<ResultStream> stream = client.SubscribeResults();
  ASSERT_TRUE(stream.ok()) << stream.status();

  // Inject via the unary RPC; the result should also appear on the stream.
  absl::StatusOr<fourward::dataplane::InjectPacketResponse> inject =
      client.InjectPacket({
          .ingress_port = DataplanePort{.port = 0},
          .payload = MakeEthernetFrame(),
      });
  ASSERT_TRUE(inject.ok()) << inject.status();

  absl::StatusOr<fourward::dataplane::ProcessPacketResult> result =
      stream->Next();
  ASSERT_TRUE(result.ok()) << result.status();
  ASSERT_EQ(result->possible_outcomes_size(), 1);
  ASSERT_EQ(result->possible_outcomes(0).packets_size(), 1);
  EXPECT_EQ(result->possible_outcomes(0).packets(0).dataplane_egress_port(),
            1u);
  EXPECT_EQ(result->input_packet().dataplane_ingress_port(), 0u);
}

TEST_F(DataplaneClientE2ETest, InjectPacketsResultsDeliveredViaSubscribe) {
  DataplaneClient client(*server_);

  absl::StatusOr<ResultStream> stream = client.SubscribeResults();
  ASSERT_TRUE(stream.ok()) << stream.status();

  std::vector<InjectPacketArgs> batch = {
      {.ingress_port = DataplanePort{.port = 0},
       .payload = MakeEthernetFrame()},
      {.ingress_port = DataplanePort{.port = 2},
       .payload = MakeEthernetFrame()},
  };
  absl::Status status = client.InjectPackets(absl::MakeConstSpan(batch));
  ASSERT_TRUE(status.ok()) << status;

  // Both results should arrive.
  for (int i = 0; i < 2; ++i) {
    absl::StatusOr<fourward::dataplane::ProcessPacketResult> result =
        stream->Next();
    ASSERT_TRUE(result.ok()) << "result " << i << ": " << result.status();
    ASSERT_EQ(result->possible_outcomes_size(), 1);
    EXPECT_EQ(result->possible_outcomes(0).packets(0).dataplane_egress_port(),
              1u);
  }
}

}  // namespace
}  // namespace fourward
