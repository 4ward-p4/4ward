// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef P4RUNTIME_CC_FOURWARD_SERVER_H_
#define P4RUNTIME_CC_FOURWARD_SERVER_H_

// Treat 4ward like a native C++ library.
//
// FourwardServer is an RAII handle to a 4ward P4Runtime + Dataplane gRPC
// server running as a child process. `Start()` spawns it, blocks until it
// is accepting RPCs, and hands back a value that owns the subprocess, a
// shared gRPC channel, and factories for both service stubs. Destruction
// kills the subprocess. Your project sees a C++ API and a Bazel target;
// the server's implementation language never enters the picture.
//
// Example (`ASSIGN_OR_RETURN` is the common project-local macro that early-
// returns on a non-OK `absl::Status`; any equivalent works):
//
//     #include "p4runtime_cc/fourward_server.h"
//
//     absl::Status RunAgainstFourward() {
//       ASSIGN_OR_RETURN(fourward::FourwardServer server,
//                        fourward::FourwardServer::Start());
//       auto stub = server.NewP4RuntimeStub();
//       // ... drive the server via gRPC ...
//       return absl::OkStatus();
//     }
//
// Add this target to `deps`; nothing else is needed.

#include <sys/types.h>

#include <cstdint>
#include <memory>
#include <optional>
#include <string>

#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/time/time.h"
#include "grpcpp/channel.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4runtime/dataplane.grpc.pb.h"

namespace fourward {

// Packet-in/out CPU port configuration. Mirrors the Kotlin CpuPortConfig:
// Auto (infer from P4Info's controller_header, the default), Disabled (no
// CPU port — packet I/O rejected), or Override (use this specific port).
struct CpuPort {
  enum class Kind { kAuto, kDisabled, kOverride };

  static CpuPort Auto() { return {Kind::kAuto, 0}; }
  static CpuPort Disabled() { return {Kind::kDisabled, 0}; }
  static CpuPort Override(int port) { return {Kind::kOverride, port}; }

  Kind kind = Kind::kAuto;
  int port = 0;  // meaningful only when kind == kOverride
};

struct FourwardServerOptions {
  // P4Runtime device ID exposed by the server (the `--device-id` flag).
  uint64_t device_id = 1;

  // TCP port the server binds on. If unset, the kernel assigns an ephemeral
  // port — the recommended default, since it avoids collisions when multiple
  // servers run in parallel (e.g. in a test shard).
  std::optional<int> port = std::nullopt;

  // v1model drop-port override (the `--drop-port` flag). If unset, the
  // simulator's built-in default is used.
  std::optional<int> drop_port = std::nullopt;

  // CPU port configuration (the `--cpu-port` flag).
  CpuPort cpu_port = CpuPort::Auto();

  // Skip @refers_to referential integrity checking on Write RPCs.
  bool disable_refers_to_checking = false;

  // Skip @entry_restriction / @action_restriction checking on Write RPCs.
  bool disable_p4_constraints_checking = false;

  // Maximum time to wait for Start() to complete.
  absl::Duration startup_timeout = absl::Seconds(5);
};

class FourwardServer {
 public:
  // Forks a server subprocess and blocks until it is accepting gRPC calls.
  // Returns NotFoundError if the server binary is missing from runfiles,
  // DeadlineExceededError on startup timeout, and a canonical-errno-mapped
  // status on other lifecycle failures.
  static absl::StatusOr<FourwardServer> Start(
      FourwardServerOptions options = {});

  ~FourwardServer();

  FourwardServer(FourwardServer&& other) noexcept;
  FourwardServer& operator=(FourwardServer&& other) noexcept;
  FourwardServer(const FourwardServer&) = delete;
  FourwardServer& operator=(const FourwardServer&) = delete;

  // Stub factories for the two services the server hosts. The common way to
  // drive the server — `server.NewP4RuntimeStub()->Write(...)` etc.
  std::unique_ptr<p4::v1::P4Runtime::Stub> NewP4RuntimeStub() const {
    return p4::v1::P4Runtime::NewStub(channel_);
  }
  std::unique_ptr<fourward::Dataplane::Stub> NewDataplaneStub() const {
    return fourward::Dataplane::NewStub(channel_);
  }

  // Address suitable for grpc::CreateChannel, e.g. "localhost:42517".
  std::string Address() const { return absl::StrCat("localhost:", port_); }

  // TCP port the server is listening on.
  int Port() const { return port_; }

  // P4Runtime device ID exposed by the server.
  uint64_t DeviceId() const { return device_id_; }

  // Escape hatches. Not needed to drive the server — reach for these when
  // interoperating with third-party helpers or diagnosing a misbehaving
  // subprocess.
  //
  // Shared insecure channel to the server, suitable for helpers that accept
  // `shared_ptr<grpc::Channel>` directly (e.g. p4_pdpi).
  const std::shared_ptr<grpc::Channel>& Channel() const { return channel_; }
  pid_t Pid() const { return pid_; }

 private:
  FourwardServer(pid_t pid, int port, uint64_t device_id,
                 std::string scratch_dir,
                 std::shared_ptr<grpc::Channel> channel);

  // Kills the subprocess (SIGTERM → SIGKILL) and removes the scratch dir.
  void Shutdown();

  pid_t pid_ = -1;
  int port_ = 0;
  uint64_t device_id_ = 0;
  // Scratch directory holding the `--port-file`. Removed on Shutdown.
  std::string scratch_dir_;
  std::shared_ptr<grpc::Channel> channel_;
};

}  // namespace fourward

#endif  // P4RUNTIME_CC_FOURWARD_SERVER_H_
