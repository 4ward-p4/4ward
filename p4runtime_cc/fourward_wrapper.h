// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_P4RUNTIME_CC_FOURWARD_WRAPPER_H_
#define FOURWARD_P4RUNTIME_CC_FOURWARD_WRAPPER_H_

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "absl/status/statusor.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4runtime/dataplane.grpc.pb.h"
#include "p4runtime_cc/fourward_server.h"

namespace fourward {

// A packet represented as raw bytes on a given dataplane port.
struct PacketAtPort {
  int port = 0;
  std::string data;
};

class Fourward final {
 public:
  static absl::StatusOr<Fourward> Create(FourwardServerOptions options = {});

  // Allow move.
  Fourward(Fourward&&) = default;
  Fourward& operator=(Fourward&&) = default;

  // Disallow copy.
  Fourward(const Fourward&) = delete;
  Fourward& operator=(const Fourward&) = delete;

  ~Fourward() = default;

  // Returns a new P4Runtime stub for control-plane operations (pushing a
  // pipeline config, writing table entries, etc.).
  std::unique_ptr<p4::v1::P4Runtime::Stub> NewP4RuntimeStub() const {
    return server_.NewP4RuntimeStub();
  }

  // Returns the Dataplane stub for direct packet-injection RPCs.
  ::fourward::dataplane::Dataplane::Stub& DataplaneStub() {
    return *dataplane_stub_;
  }

  // Returns the device_id configured for the server.
  uint64_t DeviceId() const { return server_.DeviceId(); }

  // Returns the CPU port number. Zero when using CpuPort::Auto() or
  // CpuPort::Disabled(); the overridden value when using CpuPort::Override().
  int CpuPort() const { return fourward_server_options_.cpu_port.port; }

  // Returns the drop port, if any.
  std::optional<int> DropPort() const {
    return fourward_server_options_.drop_port;
  }

  // Injects packet on the given dataplane port and returns all packets
  // emitted by the dataplane as a result. When the P4 program is
  // non-deterministic, only one possible outcome is returned; use
  // SendPacketToFourward() to enumerate all outcomes.
  absl::StatusOr<std::vector<PacketAtPort>> SendPacket(
      const PacketAtPort& packet);

 private:
  Fourward(FourwardServer server, FourwardServerOptions options)
      : server_(std::move(server)),
        dataplane_stub_(server_.NewDataplaneStub()),
        fourward_server_options_(options) {}

  FourwardServer server_;
  std::unique_ptr<::fourward::dataplane::Dataplane::Stub> dataplane_stub_;
  FourwardServerOptions fourward_server_options_;
};

// Injects packet into fourward and returns every non-deterministic outcome
// observed in the server response. Each element of the returned vector is one
// possible set of output packets. For deterministic programs, exactly one
// outcome is returned; for non-deterministic ones, the server explores all
// reachable branches and returns them all.
absl::StatusOr<std::vector<std::vector<PacketAtPort>>> SendPacketToFourward(
    Fourward& fourward, const PacketAtPort& packet);

}  // namespace fourward

#endif  // FOURWARD_P4RUNTIME_CC_FOURWARD_WRAPPER_H_
