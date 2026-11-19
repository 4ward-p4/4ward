// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_CC_NETWORK_CLIENT_H_
#define FOURWARD_CC_NETWORK_CLIENT_H_

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
#include "fourward_cc/fourward_server.h"
#include "grpc/network.grpc.pb.h"
#include "grpc/network.pb.h"

namespace fourward {

struct NetworkEndpoint {
  enum class PortKind {
    kDataplane,
    kP4Runtime,
  };

  static NetworkEndpoint Dataplane(uint64_t device_id, uint32_t port);
  static NetworkEndpoint P4Runtime(uint64_t device_id, std::string port);

  uint64_t device_id = 0;
  PortKind port_kind = PortKind::kDataplane;
  uint32_t dataplane_port = 0;
  std::string p4rt_port;
};

struct NetworkLink {
  NetworkEndpoint a;
  NetworkEndpoint b;
};

struct InjectNetworkPacketArgs {
  NetworkEndpoint ingress;
  std::string_view payload;
  uint32_t max_hops = 64;
  int64_t tag = 0;
};

// C++ wrapper for the 4ward Network gRPC service.
//
// Use this when one 4ward server hosts multiple independent logical switches
// and a test needs packet traces across links between them. Single-switch tests
// should continue to use DataplaneClient directly.
class NetworkClient {
 public:
  explicit NetworkClient(const FourwardServer& server,
                         absl::Duration default_timeout = absl::Seconds(10));
  explicit NetworkClient(std::unique_ptr<Network::Stub> stub,
                         absl::Duration default_timeout = absl::Seconds(10));

  absl::Status AddLinks(const std::vector<NetworkLink>& links);
  absl::Status RemoveLinks(const std::vector<NetworkLink>& links);
  absl::StatusOr<std::vector<NetworkLink>> ListLinks();
  absl::StatusOr<InjectNetworkPacketResponse> InjectPacket(
      InjectNetworkPacketArgs args);

 private:
  std::unique_ptr<Network::Stub> stub_;
  absl::Duration default_timeout_;
};

}  // namespace fourward

#endif  // FOURWARD_CC_NETWORK_CLIENT_H_
