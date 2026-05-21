// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_CC_DATAPLANE_CLIENT_H_
#define FOURWARD_CC_DATAPLANE_CLIENT_H_

// Convenience wrapper for the Dataplane gRPC service (dataplane.proto).
//
// Example:
//
//   ASSIGN_OR_RETURN(fourward::FourwardServer server,
//                    fourward::FourwardServer::Start());
//
//   // Use default 10s timeout for everything:
//   fourward::DataplaneClient dataplane(server);
//
//   // Or configure a longer default for slow networks:
//   fourward::DataplaneClient dataplane(server, absl::Seconds(30));
//
//   ASSIGN_OR_RETURN(auto response,
//                    dataplane.InjectPacket({
//                        .ingress_port = fourward::DataplanePort{.port = 0},
//                        .payload = "...",
//                    }));
//
//   // Override timeout per-call when needed:
//   dataplane.InjectPacket(args, absl::Seconds(1));

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <variant>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
#include "absl/types/span.h"
#include "fourward_cc/fourward_server.h"
#include "grpc/dataplane.grpc.pb.h"
#include "grpc/dataplane.pb.h"

namespace fourward {

struct DataplanePort {
  uint32_t port = 0;
};

// P4Runtime port ID — opaque bytes whose encoding depends on
// @p4runtime_translation. Requires a loaded pipeline with port translation.
struct P4RuntimePort {
  std::string port;
};

// Mirrors fourward.InjectPacketRequest with the proto's
// `ingress_port` oneof modeled as a std::variant.
struct InjectPacketArgs {
  std::variant<DataplanePort, P4RuntimePort> ingress_port;
  std::string payload;
};

// RAII handle for an open SubscribeResults stream. Destructor cancels and
// joins the reader thread.
//
// Thread-safe: concurrent Next() calls each receive a distinct result.
class ResultStream {
 public:
  ~ResultStream();
  ResultStream(ResultStream&&);
  ResultStream& operator=(ResultStream&&);
  ResultStream(const ResultStream&) = delete;
  ResultStream& operator=(const ResultStream&) = delete;

  // Blocks up to `timeout` for the next result. Returns
  // DeadlineExceededError on timeout, CancelledError when the stream ends.
  absl::StatusOr<fourward::ProcessPacketResult> Next(
      absl::Duration timeout = absl::Seconds(10));

 private:
  friend class DataplaneClient;
  class Impl;
  std::unique_ptr<Impl> impl_;
  explicit ResultStream(std::unique_ptr<Impl> impl);
};

// Ergonomic C++ client for the 4ward Dataplane gRPC service. Wraps the
// InjectPacket, InjectPackets, and SubscribeResults RPCs; names mirror
// dataplane.proto one-to-one. RegisterPrePacketHook is intentionally not
// wrapped — use the raw stub for advanced use cases.
//
// Thread-safe: each method uses its own ClientContext.
class DataplaneClient {
 public:
  explicit DataplaneClient(const FourwardServer& server,
                           absl::Duration default_timeout = absl::Seconds(10));
  explicit DataplaneClient(std::unique_ptr<fourward::Dataplane::Stub> stub,
                           absl::Duration default_timeout = absl::Seconds(10));

  ~DataplaneClient();
  DataplaneClient(DataplaneClient&&);
  DataplaneClient& operator=(DataplaneClient&&);
  DataplaneClient(const DataplaneClient&) = delete;
  DataplaneClient& operator=(const DataplaneClient&) = delete;

  absl::StatusOr<fourward::InjectPacketResponse> InjectPacket(
      const InjectPacketArgs& args,
      std::optional<absl::Duration> timeout = std::nullopt);

  // Results are delivered via SubscribeResults, not in the response.
  absl::Status InjectPackets(
      absl::Span<const InjectPacketArgs> args,
      std::optional<absl::Duration> timeout = std::nullopt);

  // Inject a packet and return a self-contained Reproducer for the
  // resulting trace.
  absl::StatusOr<fourward::Reproducer> ReproduceTrace(
      const InjectPacketArgs& args,
      std::optional<absl::Duration> timeout = std::nullopt);

  // Blocks until the server confirms the subscription is active. The
  // ResultStream is then long-lived; cancel via destruction.
  absl::StatusOr<ResultStream> SubscribeResults(
      std::optional<absl::Duration> startup_timeout = std::nullopt);

 private:
  absl::Duration ResolveTimeout(std::optional<absl::Duration> override) const {
    return override.value_or(default_timeout_);
  }

  std::unique_ptr<fourward::Dataplane::Stub> stub_;
  absl::Duration default_timeout_;
};

}  // namespace fourward

#endif  // FOURWARD_CC_DATAPLANE_CLIENT_H_
