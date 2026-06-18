// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/network_client.h"

#include <cstdint>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "fourward_cc/fourward_server.h"
#include "fourward_cc/management_client.h"
#include "gmock/gmock.h"
#include "grpcpp/client_context.h"
#include "gtest/gtest.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4/v1/p4runtime.pb.h"
#include "tools/cpp/runfiles/runfiles.h"

#ifndef PASSTHROUGH_PIPELINE_RLOCATION
#error "PASSTHROUGH_PIPELINE_RLOCATION must be set by the BUILD rule"
#endif

namespace fourward {
namespace {

using ::bazel::tools::cpp::runfiles::Runfiles;
using P4RuntimeStream = grpc::ClientReaderWriter<p4::v1::StreamMessageRequest,
                                                 p4::v1::StreamMessageResponse>;

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
  if (!file) return absl::NotFoundError(absl::StrCat("cannot open: ", path));
  return std::string(std::istreambuf_iterator<char>(file),
                     std::istreambuf_iterator<char>());
}

absl::Status EstablishMasterArbitration(uint64_t device_id,
                                        P4RuntimeStream* stream) {
  p4::v1::StreamMessageRequest arbitration;
  arbitration.mutable_arbitration()->set_device_id(device_id);
  arbitration.mutable_arbitration()->mutable_election_id()->set_low(1);
  if (!stream->Write(arbitration)) {
    return absl::InternalError("failed to write arbitration request");
  }

  p4::v1::StreamMessageResponse response;
  if (!stream->Read(&response)) {
    return absl::InternalError("failed to read arbitration response");
  }
  return absl::OkStatus();
}

absl::Status PushPipeline(const FourwardServer& server,
                          const p4::v1::ForwardingPipelineConfig& config,
                          uint64_t device_id) {
  auto stub = server.NewP4RuntimeStub();

  grpc::ClientContext stream_ctx;
  auto stream = stub->StreamChannel(&stream_ctx);
  absl::Status arbitration =
      EstablishMasterArbitration(device_id, stream.get());
  if (!arbitration.ok()) return arbitration;

  grpc::ClientContext set_ctx;
  p4::v1::SetForwardingPipelineConfigRequest req;
  req.set_device_id(device_id);
  req.mutable_election_id()->set_low(1);
  req.set_action(p4::v1::SetForwardingPipelineConfigRequest::VERIFY_AND_COMMIT);
  *req.mutable_config() = config;
  p4::v1::SetForwardingPipelineConfigResponse resp;
  grpc::Status status = stub->SetForwardingPipelineConfig(&set_ctx, req, &resp);
  stream_ctx.TryCancel();
  return ToAbsl(status);
}

std::string MakeEthernetFrame() {
  std::string frame(64, '\0');
  frame[5] = 0x01;
  frame[11] = 0x02;
  frame[12] = 0x08;
  return frame;
}

class NetworkClientTest : public ::testing::Test {
 protected:
  void SetUp() override {
    absl::StatusOr<FourwardServer> s = FourwardServer::Start();
    ASSERT_TRUE(s.ok()) << s.status();
    server_ = std::make_unique<FourwardServer>(*std::move(s));
  }

  std::unique_ptr<FourwardServer> server_;
};

TEST_F(NetworkClientTest, AddsListsAndRemovesLinks) {
  ManagementClient management(*server_);
  ASSERT_TRUE(
      management.CreateDevices({.first_device_id = 2, .count = 1}).ok());

  NetworkClient network(*server_);
  std::vector<NetworkLink> links = {
      {.a = NetworkEndpoint::Dataplane(1, 1),
       .b = NetworkEndpoint::Dataplane(2, 0)},
  };
  ASSERT_TRUE(network.AddLinks(links).ok());

  absl::StatusOr<std::vector<NetworkLink>> listed = network.ListLinks();
  ASSERT_TRUE(listed.ok()) << listed.status();
  ASSERT_EQ(listed->size(), 1);
  EXPECT_EQ((*listed)[0].a.device_id, 1);
  EXPECT_EQ((*listed)[0].a.dataplane_port, 1);
  EXPECT_EQ((*listed)[0].b.device_id, 2);
  EXPECT_EQ((*listed)[0].b.dataplane_port, 0);

  ASSERT_TRUE(network.RemoveLinks(links).ok());
  absl::StatusOr<std::vector<NetworkLink>> empty = network.ListLinks();
  ASSERT_TRUE(empty.ok()) << empty.status();
  EXPECT_TRUE(empty->empty());
}

TEST_F(NetworkClientTest, InjectPacketTraversesTwoLogicalSwitches) {
  ManagementClient management(*server_);
  ASSERT_TRUE(
      management.CreateDevices({.first_device_id = 2, .count = 1}).ok());

  absl::StatusOr<std::string> binpb =
      ReadRunfile(PASSTHROUGH_PIPELINE_RLOCATION);
  ASSERT_TRUE(binpb.ok()) << binpb.status();
  p4::v1::ForwardingPipelineConfig config;
  ASSERT_TRUE(config.ParseFromString(*binpb));
  ASSERT_TRUE(PushPipeline(*server_, config, 1).ok());
  ASSERT_TRUE(PushPipeline(*server_, config, 2).ok());

  NetworkClient network(*server_);
  ASSERT_TRUE(network
                  .AddLinks({{.a = NetworkEndpoint::Dataplane(1, 1),
                              .b = NetworkEndpoint::Dataplane(2, 0)}})
                  .ok());

  absl::StatusOr<InjectNetworkPacketResponse> response = network.InjectPacket({
      .ingress = NetworkEndpoint::Dataplane(1, 0),
      .payload = MakeEthernetFrame(),
      .max_hops = 3,
  });
  ASSERT_TRUE(response.ok()) << response.status();

  ASSERT_EQ(response->possible_outcomes_size(), 1);
  ASSERT_EQ(response->possible_outcomes(0).packets_size(), 1);
  const NetworkEgressPacket& packet = response->possible_outcomes(0).packets(0);
  EXPECT_EQ(packet.egress().device_id(), 2);
  EXPECT_EQ(packet.egress().dataplane_port(), 1);

  ASSERT_EQ(response->trace().root().device_id(), 1);
  ASSERT_EQ(response->trace().root().traversals_size(), 1);
  ASSERT_TRUE(response->trace().root().traversals(0).has_next_hop());
  EXPECT_EQ(response->trace().root().traversals(0).next_hop().device_id(), 2);
}

}  // namespace
}  // namespace fourward
