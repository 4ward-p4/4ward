// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "third_party/fourward/p4runtime_cc/fourward.h"

#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "third_party/absl/container/flat_hash_map.h"
#include "third_party/absl/status/status.h"
#include "third_party/absl/status/statusor.h"
#include "third_party/fourward/p4runtime/dataplane.grpc.pb.h"
#include "third_party/fourward/p4runtime/dataplane.pb.h"
#include "third_party/fourward/p4runtime_cc/fourward_server.h"
#include "third_party/gloop/util/status/status_macros.h"
#include "third_party/grpc/include/grpcpp/client_context.h"
#include "third_party/p4_infra/packetlib/packetlib.h"
#include "third_party/p4_infra/packetlib/packetlib.proto.h"
#include "third_party/pins_infra/p4_runtime/p4_runtime_session.h"
#include "third_party/pins_infra/tests/forwarding/packet_at_port.h"

namespace fourward {

absl::StatusOr<Fourward> Fourward::Create(FourwardServerOptions options) {
  ASSIGN_OR_RETURN(FourwardServer server, FourwardServer::Start(options));

  p4_runtime::P4RuntimeSessionOptionalArgs session_args;
  session_args.role = "";
  ASSIGN_OR_RETURN(std::unique_ptr<p4_runtime::P4RuntimeSession> session,
                   p4_runtime::P4RuntimeSession::Create(
                       server.NewP4RuntimeStub(), server.DeviceId(),
                       p4_runtime::P4RuntimeSessionOptionalArgs{
                           // Use the default role for full pipeline access.
                           .role = "",
                       }));

  return Fourward(std::move(server), std::move(session), options);
}

absl::StatusOr<std::vector<pins::PacketAtPort>> Fourward::SendPacket(
    const pins::PacketAtPort& packet) {
  fourward::dataplane::InjectPacketRequest request;
  request.set_dataplane_ingress_port(packet.port);
  request.set_payload(packet.data);

  grpc::ClientContext context;
  fourward::dataplane::InjectPacketResponse response;
  absl::Status status =
      DataplaneStub().InjectPacket(&context, request, &response);
  if (!status.ok()) {
    return status;
  }

  std::vector<pins::PacketAtPort> received_packets;
  if (!response.possible_outcomes().empty()) {
    for (const auto& out_packet : response.possible_outcomes(0).packets()) {
      received_packets.push_back(pins::PacketAtPort{
          .port = static_cast<int>(out_packet.dataplane_egress_port()),
          .data = out_packet.payload(),
      });
    }
  }
  return received_packets;
}

absl::StatusOr<absl::flat_hash_map<int, packetlib::Packets>>
Fourward::SendPacket(int ingress_port, packetlib::Packet packet) {
  absl::flat_hash_map<int, packetlib::Packets> packets_by_port;

  ASSIGN_OR_RETURN(std::string data, packetlib::RawSerializePacket(packet));
  ASSIGN_OR_RETURN(std::vector<pins::PacketAtPort> outputs,
                   SendPacket(pins::PacketAtPort{
                       .port = ingress_port,
                       .data = std::move(data),
                   }));
  for (const auto& [port, payload] : outputs) {
    *packets_by_port[port].add_packets() = packetlib::ParsePacket(payload);
  }
  return packets_by_port;
}

absl::StatusOr<std::vector<std::vector<pins::PacketAtPort>>>
SendPacketToFourward(Fourward* wrapper, const pins::PacketAtPort& packet,
                     int min_samples, std::optional<int> max_samples) {
  fourward::dataplane::InjectPacketRequest request;
  request.set_dataplane_ingress_port(packet.port);
  request.set_payload(packet.data);

  grpc::ClientContext context;
  fourward::dataplane::InjectPacketResponse response;
  RETURN_IF_ERROR(
      wrapper->DataplaneStub().InjectPacket(&context, request, &response));

  std::vector<std::vector<pins::PacketAtPort>> result;
  for (const auto& outcome : response.possible_outcomes()) {
    std::vector<pins::PacketAtPort> entry;
    for (const auto& out_packet : outcome.packets()) {
      entry.push_back(pins::PacketAtPort{
          .port = static_cast<int>(out_packet.dataplane_egress_port()),
          .data = out_packet.payload(),
      });
    }
    result.push_back(std::move(entry));
  }
  return result;
}

}  // namespace fourward
