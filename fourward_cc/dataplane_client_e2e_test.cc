// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

// End-to-end tests for DataplaneClient against a live FourwardServer with a
// loaded P4 pipeline. Most tests use the passthrough program (always forwards
// to port 1) so results are deterministic without any table entries. Tests
// that need controller-packet metadata load SAI explicitly.

#include "fourward_cc/dataplane_client.h"

#include <cstdint>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <string_view>
#include <utility>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/time/time.h"
#include "fourward_cc/dataplane_matchers.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "grpc/dataplane.pb.h"
#include "grpcpp/client_context.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4/v1/p4runtime.pb.h"
#include "fourward_cc/fourward_server.h"
#include "fourward_cc/management_client.h"
#include "tools/cpp/runfiles/runfiles.h"

#ifndef PASSTHROUGH_PIPELINE_RLOCATION
#error "PASSTHROUGH_PIPELINE_RLOCATION must be set by the BUILD rule"
#endif

#ifndef SAI_PIPELINE_RLOCATION
#error "SAI_PIPELINE_RLOCATION must be set by the BUILD rule"
#endif

namespace fourward {
namespace {

using ::bazel::tools::cpp::runfiles::Runfiles;

using P4RuntimeStream =
    grpc::ClientReaderWriter<p4::v1::StreamMessageRequest,
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
  if (!file) {
    return absl::NotFoundError(absl::StrCat("cannot open: ", path));
  }
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

// Pushes a ForwardingPipelineConfig to the server via the P4Runtime API.
// Handles the arbitration handshake.
absl::Status PushPipeline(const FourwardServer& server,
                          const p4::v1::ForwardingPipelineConfig& config,
                          uint64_t device_id) {
  auto stub = server.NewP4RuntimeStub();

  grpc::ClientContext stream_ctx;
  auto stream = stub->StreamChannel(&stream_ctx);
  absl::Status arbitration = EstablishMasterArbitration(device_id, stream.get());
  if (!arbitration.ok()) return arbitration;

  // Push pipeline.
  grpc::ClientContext set_ctx;
  p4::v1::SetForwardingPipelineConfigRequest req;
  req.set_device_id(device_id);
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

absl::Status PushPipeline(const FourwardServer& server,
                          const p4::v1::ForwardingPipelineConfig& config) {
  return PushPipeline(server, config, server.DeviceId());
}

const p4::config::v1::Table* FindTable(const p4::config::v1::P4Info& p4info,
                                       std::string_view alias) {
  for (const p4::config::v1::Table& table : p4info.tables()) {
    if (table.preamble().alias() == alias) return &table;
  }
  return nullptr;
}

const p4::config::v1::Action* FindAction(const p4::config::v1::P4Info& p4info,
                                         std::string_view name) {
  for (const p4::config::v1::Action& action : p4info.actions()) {
    if (action.preamble().name() == name) return &action;
  }
  return nullptr;
}

uint32_t MatchFieldId(const p4::config::v1::Table& table,
                      std::string_view name) {
  for (const p4::config::v1::MatchField& field : table.match_fields()) {
    if (field.name() == name) return field.id();
  }
  ADD_FAILURE() << "match field not found: " << name;
  return 0;
}

p4::v1::FieldMatch OptionalMatch(const p4::config::v1::Table& table,
                                 std::string_view field_name,
                                 std::string value) {
  p4::v1::FieldMatch match;
  match.set_field_id(MatchFieldId(table, field_name));
  match.mutable_optional()->set_value(std::move(value));
  return match;
}

p4::v1::FieldMatch TernaryMatch(const p4::config::v1::Table& table,
                                std::string_view field_name, std::string value,
                                std::string mask) {
  p4::v1::FieldMatch match;
  match.set_field_id(MatchFieldId(table, field_name));
  match.mutable_ternary()->set_value(std::move(value));
  match.mutable_ternary()->set_mask(std::move(mask));
  return match;
}

p4::v1::Entity BuildAclDropEntry(const p4::config::v1::P4Info& p4info) {
  const p4::config::v1::Table* table = FindTable(p4info, "acl_ingress_table");
  const p4::config::v1::Action* action = FindAction(p4info, "acl_drop");
  if (table == nullptr || action == nullptr) {
    ADD_FAILURE() << "SAI ACL drop table/action not found";
    return p4::v1::Entity();
  }

  p4::v1::Entity entity;
  p4::v1::TableEntry* entry = entity.mutable_table_entry();
  entry->set_table_id(table->preamble().id());
  *entry->add_match() = OptionalMatch(*table, "is_ipv4", std::string(1, '\1'));
  *entry->add_match() =
      TernaryMatch(*table, "dst_ip", std::string("\x0a\x00\x00\x01", 4),
                   std::string("\xff\xff\xff\xff", 4));
  entry->mutable_action()->mutable_action()->set_action_id(
      action->preamble().id());
  entry->set_priority(1);
  return entity;
}

absl::Status WriteInsert(const FourwardServer& server,
                         const p4::v1::Entity& entity) {
  auto stub = server.NewP4RuntimeStub();

  grpc::ClientContext stream_ctx;
  auto stream = stub->StreamChannel(&stream_ctx);
  absl::Status arbitration =
      EstablishMasterArbitration(server.DeviceId(), stream.get());
  if (!arbitration.ok()) return arbitration;

  p4::v1::WriteRequest req;
  req.set_device_id(server.DeviceId());
  req.mutable_election_id()->set_low(1);
  p4::v1::Update* update = req.add_updates();
  update->set_type(p4::v1::Update::INSERT);
  *update->mutable_entity() = entity;
  p4::v1::WriteResponse resp;
  grpc::ClientContext ctx;
  absl::Status status = ToAbsl(stub->Write(&ctx, req, &resp));
  stream_ctx.TryCancel();
  return status;
}

uint32_t PacketOutMetadataId(const p4::config::v1::P4Info& p4info,
                             std::string_view name) {
  for (const p4::config::v1::ControllerPacketMetadata& metadata :
       p4info.controller_packet_metadata()) {
    if (metadata.preamble().name() != "packet_out") continue;
    for (const p4::config::v1::ControllerPacketMetadata::Metadata& field :
         metadata.metadata()) {
      if (field.name() == name) return field.id();
    }
  }
  ADD_FAILURE() << "packet_out metadata not found: " << name;
  return 0;
}

p4::v1::PacketOut BuildSaiSubmitToEgressPacketOut(
    const p4::config::v1::P4Info& p4info, std::string payload) {
  p4::v1::PacketOut packet;
  packet.set_payload(std::move(payload));

  p4::v1::PacketMetadata* egress_port = packet.add_metadata();
  egress_port->set_metadata_id(PacketOutMetadataId(p4info, "egress_port"));
  egress_port->set_value("Ethernet1");

  p4::v1::PacketMetadata* submit_to_ingress = packet.add_metadata();
  submit_to_ingress->set_metadata_id(
      PacketOutMetadataId(p4info, "submit_to_ingress"));
  submit_to_ingress->set_value(std::string(1, '\0'));

  p4::v1::PacketMetadata* unused_pad = packet.add_metadata();
  unused_pad->set_metadata_id(PacketOutMetadataId(p4info, "unused_pad"));
  unused_pad->set_value(std::string(1, '\0'));
  return packet;
}

absl::Status SendPacketOut(const FourwardServer& server,
                           const p4::v1::PacketOut& packet_out) {
  auto stub = server.NewP4RuntimeStub();
  grpc::ClientContext ctx;
  auto stream = stub->StreamChannel(&ctx);

  absl::Status arbitration =
      EstablishMasterArbitration(server.DeviceId(), stream.get());
  if (!arbitration.ok()) return arbitration;

  p4::v1::StreamMessageRequest request;
  *request.mutable_packet() = packet_out;
  if (!stream->Write(request)) {
    return absl::InternalError("failed to write PacketOut");
  }
  stream->WritesDone();
  return ToAbsl(stream->Finish());
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

std::string MakeIpv4Packet() {
  std::string packet(34, '\0');
  // Ethernet header: dst=00:01:02:03:04:05, src=00:0a:0b:0c:0d:0e,
  // ethertype=0x0800.
  packet[1] = 0x01;
  packet[2] = 0x02;
  packet[3] = 0x03;
  packet[4] = 0x04;
  packet[5] = 0x05;
  packet[7] = 0x0a;
  packet[8] = 0x0b;
  packet[9] = 0x0c;
  packet[10] = 0x0d;
  packet[11] = 0x0e;
  packet[12] = 0x08;
  // IPv4 header: version=4, IHL=5, total length=20, TTL=64, protocol=TCP.
  packet[14] = 0x45;
  packet[17] = 20;
  packet[22] = 64;
  packet[23] = 0x06;
  packet[26] = static_cast<char>(192);
  packet[27] = static_cast<char>(168);
  packet[28] = 1;
  packet[29] = 1;
  packet[30] = 10;
  packet[33] = 1;
  return packet;
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

  absl::StatusOr<InjectPacketResponse> resp =
      client.InjectPacket(DataplanePort{0}, MakeEthernetFrame());
  ASSERT_TRUE(resp.ok()) << resp.status();
  EXPECT_THAT(*resp, ForwardsTo(1));
  EXPECT_FALSE(resp->trace().DebugString().empty());
}

TEST_F(DataplaneClientE2ETest, SubscribeResultsDeliversInjectedPacket) {
  DataplaneClient client(*server_);

  absl::StatusOr<ResultStream> stream = client.SubscribeResults();
  ASSERT_TRUE(stream.ok()) << stream.status();

  // Inject via the unary RPC; the result should also appear on the stream.
  absl::StatusOr<InjectPacketResponse> inject =
      client.InjectPacket(DataplanePort{0}, MakeEthernetFrame());
  ASSERT_TRUE(inject.ok()) << inject.status();

  absl::StatusOr<ProcessPacketResult> result = stream->Next();
  ASSERT_TRUE(result.ok()) << result.status();
  EXPECT_THAT(*result, ForwardsTo(1));
  EXPECT_THAT(*result, HasIngress(0));
}

TEST_F(DataplaneClientE2ETest, InjectPacketsResultsDeliveredViaSubscribe) {
  DataplaneClient client(*server_);

  absl::StatusOr<ResultStream> stream = client.SubscribeResults();
  ASSERT_TRUE(stream.ok()) << stream.status();

  PacketWriter writer = client.InjectPackets();
  ASSERT_TRUE(writer.Inject(DataplanePort{0}, MakeEthernetFrame()).ok());
  ASSERT_TRUE(writer.Inject(DataplanePort{2}, MakeEthernetFrame()).ok());
  absl::StatusOr<int> count = writer.Finish();
  ASSERT_TRUE(count.ok()) << count.status();
  ASSERT_EQ(*count, 2);

  for (int i = 0; i < *count; ++i) {
    absl::StatusOr<ProcessPacketResult> result = stream->Next();
    ASSERT_TRUE(result.ok()) << "result " << i << ": " << result.status();
    EXPECT_THAT(*result, ForwardsTo(1));
  }
}

TEST_F(DataplaneClientE2ETest, InjectPacketsBurstCanBeDrainedAfterInjection) {
  DataplaneClient client(*server_);

  absl::StatusOr<ResultStream> stream = client.SubscribeResults();
  ASSERT_TRUE(stream.ok()) << stream.status();

  constexpr int kPackets = 10'000;
  PacketWriter writer = client.InjectPackets();
  for (int i = 0; i < kPackets; ++i) {
    ASSERT_TRUE(writer.Inject(DataplanePort{0}, MakeEthernetFrame()).ok())
        << "packet " << i;
  }
  absl::StatusOr<int> count = writer.Finish();
  ASSERT_TRUE(count.ok()) << count.status();
  ASSERT_EQ(*count, kPackets);

  for (int i = 0; i < kPackets; ++i) {
    absl::StatusOr<ProcessPacketResult> result = stream->Next();
    ASSERT_TRUE(result.ok()) << "result " << i << ": " << result.status();
    EXPECT_THAT(*result, ForwardsTo(1));
  }
}

TEST_F(DataplaneClientE2ETest, ExplicitDeviceIdScopesStreamingDataplaneRpc) {
  constexpr uint64_t kSecondDeviceId = 2;
  ManagementClient management(*server_);
  ASSERT_TRUE(
      management.CreateDevices({.first_device_id = kSecondDeviceId, .count = 1}).ok());

  absl::StatusOr<std::string> binpb =
      ReadRunfile(PASSTHROUGH_PIPELINE_RLOCATION);
  ASSERT_TRUE(binpb.ok()) << binpb.status();
  p4::v1::ForwardingPipelineConfig config;
  ASSERT_TRUE(config.ParseFromString(*binpb))
      << "failed to parse ForwardingPipelineConfig";
  ASSERT_TRUE(PushPipeline(*server_, config, kSecondDeviceId).ok());

  DataplaneClient client(*server_, kSecondDeviceId);
  absl::StatusOr<ResultStream> stream = client.SubscribeResults();
  ASSERT_TRUE(stream.ok()) << stream.status();

  PacketWriter writer = client.InjectPackets();
  ASSERT_TRUE(writer.Inject(DataplanePort{7}, MakeEthernetFrame()).ok());
  absl::StatusOr<int> count = writer.Finish();
  ASSERT_TRUE(count.ok()) << count.status();
  ASSERT_EQ(*count, 1);

  absl::StatusOr<ProcessPacketResult> result = stream->Next();
  ASSERT_TRUE(result.ok()) << result.status();
  EXPECT_THAT(*result, ForwardsTo(1));
  EXPECT_THAT(*result, HasIngress(7));
}

TEST_F(DataplaneClientE2ETest,
       InjectPacketWithP4RuntimePortFailsWithoutTranslation) {
  DataplaneClient client(*server_);

  // The passthrough program has no @p4runtime_translation on its port type.
  // Injecting via P4RuntimePort must fail with FAILED_PRECONDITION.
  absl::StatusOr<InjectPacketResponse> resp =
      client.InjectPacket(
          P4RuntimePort{std::string("\x00\x01", 2)},
          MakeEthernetFrame());
  ASSERT_FALSE(resp.ok());
  EXPECT_EQ(resp.status().code(), absl::StatusCode::kFailedPrecondition)
      << resp.status();
}

class SaiPacketIoE2ETest : public ::testing::Test {
 protected:
  void SetUp() override {
    absl::StatusOr<FourwardServer> s = FourwardServer::Start();
    ASSERT_TRUE(s.ok()) << s.status();
    server_ = std::make_unique<FourwardServer>(*std::move(s));

    absl::StatusOr<std::string> binpb = ReadRunfile(SAI_PIPELINE_RLOCATION);
    ASSERT_TRUE(binpb.ok()) << binpb.status();
    ASSERT_TRUE(config_.ParseFromString(*binpb))
        << "failed to parse ForwardingPipelineConfig";
    absl::Status push = PushPipeline(*server_, config_);
    ASSERT_TRUE(push.ok()) << push;
  }

  std::unique_ptr<FourwardServer> server_;
  p4::v1::ForwardingPipelineConfig config_;
};

TEST_F(SaiPacketIoE2ETest, PacketOutSubmitToEgressBypassesIngress) {
  DataplaneClient client(*server_);
  absl::StatusOr<ResultStream> stream = client.SubscribeResults();
  ASSERT_TRUE(stream.ok()) << stream.status();

  absl::Status insert =
      WriteInsert(*server_, BuildAclDropEntry(config_.p4info()));
  ASSERT_TRUE(insert.ok()) << insert;

  const std::string payload = MakeIpv4Packet();
  absl::Status send = SendPacketOut(
      *server_, BuildSaiSubmitToEgressPacketOut(config_.p4info(), payload));
  ASSERT_TRUE(send.ok()) << send;

  absl::StatusOr<ProcessPacketResult> result = stream->Next();
  ASSERT_TRUE(result.ok()) << result.status();
  ASSERT_EQ(result->possible_outcomes_size(), 1);
  ASSERT_EQ(result->possible_outcomes(0).packets_size(), 1);
  const OutputPacket& output = result->possible_outcomes(0).packets(0);
  EXPECT_EQ(output.p4rt_egress_port(), "Ethernet1");
  EXPECT_EQ(output.payload().size(), payload.size());
  // The SAI pipeline recomputes the IPv4 checksum, so check the stable fields
  // that prove the PacketOut controller header did not leak into the payload.
  EXPECT_EQ(output.payload().substr(0, 12), payload.substr(0, 12));
  EXPECT_EQ(output.payload().substr(26, 8), payload.substr(26, 8));
}

}  // namespace
}  // namespace fourward
