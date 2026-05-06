// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_P4RUNTIME_CC_FOURWARD_H_
#define FOURWARD_P4RUNTIME_CC_FOURWARD_H_

#include <cstdint>
#include <memory>
#include <optional>
#include <utility>
#include <vector>

#include "third_party/absl/container/flat_hash_map.h"
#include "third_party/absl/status/statusor.h"
#include "third_party/fourward/p4runtime/dataplane.grpc.pb.h"
#include "third_party/fourward/p4runtime_cc/fourward_server.h"
#include "third_party/p4_infra/packetlib/packetlib.proto.h"
#include "third_party/p4lang_PI/proto/p4/tmp/p4config.proto.h"
#include "third_party/pins_infra/p4_runtime/p4_runtime_session.h"
#include "third_party/pins_infra/tests/forwarding/packet_at_port.h"

namespace fourward {

class Fourward final {
 public:
  static absl::StatusOr<Fourward> Create(FourwardServerOptions options);

  // Allow move.
  Fourward(Fourward&&) = default;
  Fourward& operator=(Fourward&&) = default;

  // Disallow copy.
  Fourward(const Fourward&) = delete;
  Fourward& operator=(const Fourward&) = delete;

  ~Fourward() = default;

  // Returns the P4 Runtime session used to control the control plane.
  p4_runtime::P4RuntimeSession& P4RuntimeSession() {
    return *p4runtime_session_;
  }
  p4_runtime::P4RuntimeSession* P4RuntimeSessionPtr() {
    return p4runtime_session_.get();
  }

  // Returns the Dataplane stub used to communicate with the data plane.
  ::fourward::dataplane::Dataplane::Stub& DataplaneStub() {
    return *dataplane_stub_;
  }

  // Returns the device_id.
  uint32_t DeviceId() const { return server_.DeviceId(); }

  // Returns the CPU port, if any.
  int CpuPort() const { return forward_server_options_.cpu_port.port; }

  // Returns the drop port.
  std::optional<int> DropPort() const {
    return forward_server_options_.drop_port;
  }

  // Injects the given packet on the given dataplane port, and returns
  // all the packets that are sent by the dataplane as a result.
  absl::StatusOr<std::vector<pins::PacketAtPort>> SendPacket(
      const pins::PacketAtPort& packet);

  // Like the other `SendPacket` overload, but returns the output packets as
  // a parsed proto and keyed by egress port.
  absl::StatusOr<absl::flat_hash_map<int, packetlib::Packets>> SendPacket(
      int ingress_port, packetlib::Packet packet);

 private:
  Fourward(fourward::FourwardServer server,
           std::unique_ptr<p4_runtime::P4RuntimeSession> p4runtime_session,
           FourwardServerOptions options)
      : server_(std::move(server)),
        p4runtime_session_(std::move(p4runtime_session)),
        dataplane_stub_(server_.NewDataplaneStub()),
        forward_server_options_(options) {}

  fourward::FourwardServer server_;
  std::unique_ptr<p4_runtime::P4RuntimeSession> p4runtime_session_;
  std::unique_ptr<::fourward::dataplane::Dataplane::Stub> dataplane_stub_;
  FourwardServerOptions forward_server_options_;
};

// Sends a packet to Fourward and returns all possible non-deterministic
// outcomes.

absl::StatusOr<std::vector<std::vector<pins::PacketAtPort>>>
SendPacketToFourward(Fourward* wrapper, const pins::PacketAtPort& packet,
                     int min_samples,
                     std::optional<int> max_samples = std::nullopt);

}  // namespace fourward

#endif  // FOURWARD_P4RUNTIME_CC_FOURWARD__H_

