// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "p4runtime_cc/fourward_wrapper.h"

#include <memory>
#include <utility>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "grpcpp/client_context.h"
#include "p4runtime/dataplane.grpc.pb.h"
#include "p4runtime/dataplane.pb.h"

namespace fourward {
namespace {

absl::Status GrpcToAbsl(const grpc::Status& s) {
  if (s.ok()) return absl::OkStatus();
  return absl::Status(static_cast<absl::StatusCode>(s.error_code()),
                      s.error_message());
}

}  // namespace

absl::StatusOr<Fourward> Fourward::Create(FourwardServerOptions options) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start(options);
  if (!server.ok()) return std::move(server).status();
  return Fourward(std::move(*server), options);
}

absl::StatusOr<std::vector<PacketAtPort>> Fourward::SendPacket(
    const PacketAtPort& packet) {
  fourward::dataplane::InjectPacketRequest request;
  request.set_dataplane_ingress_port(packet.port);
  request.set_payload(packet.data);

  grpc::ClientContext context;
  fourward::dataplane::InjectPacketResponse response;
  absl::Status status =
      GrpcToAbsl(DataplaneStub().InjectPacket(&context, request, &response));
  if (!status.ok()) return status;

  std::vector<PacketAtPort> received;
  if (!response.possible_outcomes().empty()) {
    for (const auto& out : response.possible_outcomes(0).packets()) {
      received.push_back(PacketAtPort{
          .port = static_cast<int>(out.dataplane_egress_port()),
          .data = out.payload(),
      });
    }
  }
  return received;
}

absl::StatusOr<std::vector<std::vector<PacketAtPort>>> SendPacketToFourward(
    Fourward& fourward, const PacketAtPort& packet) {
  fourward::dataplane::InjectPacketRequest request;
  request.set_dataplane_ingress_port(packet.port);
  request.set_payload(packet.data);

  grpc::ClientContext context;
  fourward::dataplane::InjectPacketResponse response;
  absl::Status status = GrpcToAbsl(
      fourward.DataplaneStub().InjectPacket(&context, request, &response));
  if (!status.ok()) return status;

  std::vector<std::vector<PacketAtPort>> result;
  for (const auto& outcome : response.possible_outcomes()) {
    std::vector<PacketAtPort> entry;
    for (const auto& out : outcome.packets()) {
      entry.push_back(PacketAtPort{
          .port = static_cast<int>(out.dataplane_egress_port()),
          .data = out.payload(),
      });
    }
    result.push_back(std::move(entry));
  }
  return result;
}

}  // namespace fourward
