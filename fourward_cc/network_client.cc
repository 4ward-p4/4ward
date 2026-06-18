// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/network_client.h"

#include <memory>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/time/time.h"
#include "fourward_cc/grpc_util.h"
#include "grpc/network.grpc.pb.h"
#include "grpc/network.pb.h"
#include "grpcpp/client_context.h"
#include "grpcpp/support/status.h"

namespace fourward {
namespace {

NetworkPort ToProto(const NetworkEndpoint& endpoint) {
  NetworkPort proto;
  proto.set_device_id(endpoint.device_id);
  switch (endpoint.port_kind) {
    case NetworkEndpoint::PortKind::kDataplane:
      proto.set_dataplane_port(endpoint.dataplane_port);
      return proto;
    case NetworkEndpoint::PortKind::kP4Runtime:
      proto.set_p4rt_port(endpoint.p4rt_port);
      return proto;
  }
}

Link ToProto(const NetworkLink& link) {
  Link proto;
  *proto.mutable_a() = ToProto(link.a);
  *proto.mutable_b() = ToProto(link.b);
  return proto;
}

absl::StatusOr<NetworkEndpoint> FromProto(const NetworkPort& proto) {
  switch (proto.port_case()) {
    case NetworkPort::kDataplanePort:
      return NetworkEndpoint::Dataplane(proto.device_id(),
                                        proto.dataplane_port());
    case NetworkPort::kP4RtPort:
      return NetworkEndpoint::P4Runtime(proto.device_id(), proto.p4rt_port());
    case NetworkPort::PORT_NOT_SET:
      return absl::InvalidArgumentError(absl::StrCat(
          "network endpoint for device ", proto.device_id(), " has no port"));
  }
}

absl::StatusOr<NetworkLink> FromProto(const Link& proto) {
  absl::StatusOr<NetworkEndpoint> a = FromProto(proto.a());
  if (!a.ok()) return a.status();
  absl::StatusOr<NetworkEndpoint> b = FromProto(proto.b());
  if (!b.ok()) return b.status();
  return NetworkLink{.a = std::move(*a), .b = std::move(*b)};
}

}  // namespace

NetworkEndpoint NetworkEndpoint::Dataplane(uint64_t device_id, uint32_t port) {
  return NetworkEndpoint{.device_id = device_id,
                         .port_kind = PortKind::kDataplane,
                         .dataplane_port = port};
}

NetworkEndpoint NetworkEndpoint::P4Runtime(uint64_t device_id,
                                           std::string port) {
  return NetworkEndpoint{.device_id = device_id,
                         .port_kind = PortKind::kP4Runtime,
                         .p4rt_port = std::move(port)};
}

NetworkClient::NetworkClient(const FourwardServer& server,
                             absl::Duration default_timeout)
    : NetworkClient(server.NewNetworkStub(), default_timeout) {}

NetworkClient::NetworkClient(std::unique_ptr<Network::Stub> stub,
                             absl::Duration default_timeout)
    : stub_(std::move(stub)), default_timeout_(default_timeout) {}

absl::Status NetworkClient::AddLinks(const std::vector<NetworkLink>& links) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  AddLinksRequest req;
  for (const NetworkLink& link : links) {
    *req.add_links() = ToProto(link);
  }
  AddLinksResponse resp;
  return ToAbsl(stub_->AddLinks(&ctx, req, &resp));
}

absl::Status NetworkClient::RemoveLinks(const std::vector<NetworkLink>& links) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  RemoveLinksRequest req;
  for (const NetworkLink& link : links) {
    *req.add_links() = ToProto(link);
  }
  RemoveLinksResponse resp;
  return ToAbsl(stub_->RemoveLinks(&ctx, req, &resp));
}

absl::StatusOr<std::vector<NetworkLink>> NetworkClient::ListLinks() {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  ListLinksRequest req;
  ListLinksResponse resp;
  grpc::Status status = stub_->ListLinks(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);

  std::vector<NetworkLink> links;
  links.reserve(resp.links_size());
  for (const Link& link : resp.links()) {
    absl::StatusOr<NetworkLink> parsed = FromProto(link);
    if (!parsed.ok()) return parsed.status();
    links.push_back(std::move(*parsed));
  }
  return links;
}

absl::StatusOr<InjectNetworkPacketResponse> NetworkClient::InjectPacket(
    InjectNetworkPacketArgs args) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  InjectNetworkPacketRequest req;
  *req.mutable_ingress() = ToProto(args.ingress);
  req.set_payload(args.payload.data(), args.payload.size());
  req.set_max_hops(args.max_hops);
  req.set_tag(args.tag);

  InjectNetworkPacketResponse resp;
  grpc::Status status = stub_->InjectPacket(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);
  return resp;
}

}  // namespace fourward
