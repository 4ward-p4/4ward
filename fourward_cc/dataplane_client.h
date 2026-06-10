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
//   fourward::DataplaneClient dataplane(server);
//
//   ASSIGN_OR_RETURN(auto response,
//                    dataplane.InjectPacket(
//                        fourward::DataplanePort{0}, "..."));
//
//   // Streaming injection:
//   auto writer = dataplane.InjectPackets();
//   writer.Inject(fourward::DataplanePort{0}, payload1);
//   writer.Inject(fourward::DataplanePort{1}, payload2);
//   ASSIGN_OR_RETURN(int count, writer.Finish());

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
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

struct Tag {
  int64_t value = 0;
};

// RAII handle for a client-streaming InjectPackets RPC. Destructor calls
// Finish() if not already called — so like Finish(), it blocks until the
// server has processed every injected packet, unless a `total_timeout` was
// passed to InjectPackets().
//
// Not thread-safe: callers must serialize Inject/Finish calls.
class PacketWriter {
 public:
  ~PacketWriter();
  PacketWriter(PacketWriter &&);
  PacketWriter &operator=(PacketWriter &&);
  PacketWriter(const PacketWriter &) = delete;
  PacketWriter &operator=(const PacketWriter &) = delete;

  absl::Status Inject(DataplanePort ingress_port, std::string_view payload,
                      Tag tag = {});
  absl::Status Inject(P4RuntimePort ingress_port, std::string_view payload,
                      Tag tag = {});

  // Signals end-of-stream, blocks until the server has processed every
  // injected packet, and returns the number of packets injected.
  absl::StatusOr<int> Finish();

 private:
  friend class DataplaneClient;
  class Impl;
  std::unique_ptr<Impl> impl_;
  explicit PacketWriter(std::unique_ptr<Impl> impl);
};

// RAII handle for an open SubscribeResults stream. Destructor cancels and
// joins the reader thread.
//
// Thread-safe: concurrent Next() calls each receive a distinct result.
class ResultStream {
 public:
  ~ResultStream();
  ResultStream(ResultStream &&);
  ResultStream &operator=(ResultStream &&);
  ResultStream(const ResultStream &) = delete;
  ResultStream &operator=(const ResultStream &) = delete;

  // Blocks up to `timeout` for the next result. Returns
  // DeadlineExceededError on timeout, CancelledError when the stream ends.
  absl::StatusOr<ProcessPacketResult> Next(
      absl::Duration timeout = absl::Seconds(10));

 private:
  friend class DataplaneClient;
  class Impl;
  std::unique_ptr<Impl> impl_;
  explicit ResultStream(std::unique_ptr<Impl> impl);
};

// Ergonomic C++ client for the 4ward Dataplane gRPC service. Wraps the
// InjectPacket, InjectPackets, SubscribeResults, and GetReproducer RPCs;
// names mirror dataplane.proto one-to-one. RegisterPrePacketHook is
// intentionally not wrapped — use the raw stub for advanced use cases.
//
// Thread-safe: each method uses its own ClientContext.
class DataplaneClient {
 public:
  explicit DataplaneClient(const FourwardServer &server,
                           absl::Duration default_timeout = absl::Seconds(10));
  explicit DataplaneClient(std::unique_ptr<Dataplane::Stub> stub,
                           absl::Duration default_timeout = absl::Seconds(10));

  ~DataplaneClient();
  DataplaneClient(DataplaneClient &&);
  DataplaneClient &operator=(DataplaneClient &&);
  DataplaneClient(const DataplaneClient &) = delete;
  DataplaneClient &operator=(const DataplaneClient &) = delete;

  absl::StatusOr<InjectPacketResponse> InjectPacket(DataplanePort ingress_port,
                                                    std::string_view payload,
                                                    Tag tag = {});
  absl::StatusOr<InjectPacketResponse> InjectPacket(P4RuntimePort ingress_port,
                                                    std::string_view payload,
                                                    Tag tag = {});

  // Returns a writer for streaming packet injection. Results are delivered
  // via SubscribeResults, not inline.
  //
  // By default the stream has no deadline (see PacketWriter); it is
  // deliberately exempt from `default_timeout`, which cannot know the burst
  // size (#760). Callers who can bound their burst may pass `total_timeout`
  // to bound the entire stream — every Inject(), server-side processing,
  // and Finish().
  PacketWriter InjectPackets(
      std::optional<absl::Duration> total_timeout = std::nullopt);

  // Blocks until the server confirms the subscription is active. The
  // ResultStream is then long-lived; cancel via destruction.
  absl::StatusOr<ResultStream> SubscribeResults(
      std::optional<absl::Duration> startup_timeout = std::nullopt);

  absl::StatusOr<Reproducer> GetReproducer(DataplanePort ingress_port,
                                           std::string_view payload,
                                           Tag tag = {});
  absl::StatusOr<Reproducer> GetReproducer(P4RuntimePort ingress_port,
                                           std::string_view payload,
                                           Tag tag = {});

 private:
  std::unique_ptr<Dataplane::Stub> stub_;
  absl::Duration default_timeout_;
};

}  // namespace fourward

#endif  // FOURWARD_CC_DATAPLANE_CLIENT_H_
