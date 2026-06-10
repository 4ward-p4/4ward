// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_CC_FOURWARD_SERVER_H_
#define FOURWARD_CC_FOURWARD_SERVER_H_

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
//     #include "fourward_cc/fourward_server.h"
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
#include <utility>

#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/time/time.h"
#include "grpc/dataplane.grpc.pb.h"
#include "grpcpp/channel.h"
#include "p4/v1/p4runtime.grpc.pb.h"

namespace fourward {

class OutputCapture;

// A port override: either a raw dataplane port number or a P4Runtime port
// name that gets resolved at pipeline load time.
struct PortOverride {
  enum class Kind { kDataplane, kP4rt };

  static PortOverride Dataplane(int port) {
    return {Kind::kDataplane, port, ""};
  }
  static PortOverride P4rt(std::string name) {
    return {Kind::kP4rt, 0, std::move(name)};
  }

  std::string ToFlagValue() const {
    return kind == Kind::kDataplane ? absl::StrCat(port) : p4rt_name;
  }

  Kind kind;
  int port = 0;           // meaningful only when kind == kDataplane
  std::string p4rt_name;  // meaningful only when kind == kP4rt
};

// Packet-in/out CPU port configuration. Mirrors the Kotlin CpuPortConfig:
// Auto (infer from P4Info's controller_header, the default), Disabled (no
// CPU port — packet I/O rejected), or Override (use this specific port).
struct CpuPort {
  enum class Kind { kAuto, kDisabled, kOverride };

  static CpuPort Auto() {
    return {Kind::kAuto, {}};
  }
  static CpuPort Disabled() {
    return {Kind::kDisabled, {}};
  }
  static CpuPort Override(int port) {
    return {Kind::kOverride, PortOverride::Dataplane(port)};
  }
  static CpuPort Override(std::string p4rt_name) {
    return {Kind::kOverride, PortOverride::P4rt(std::move(p4rt_name))};
  }

  Kind kind = Kind::kAuto;
  PortOverride port_override;  // meaningful only when kind == kOverride
};

struct FourwardServerOptions {
  // P4Runtime device ID exposed by the server (the `--device-id` flag).
  uint64_t device_id = 1;

  // TCP port the server binds on. If unset, the kernel assigns an ephemeral
  // port — the recommended default, since it avoids collisions when multiple
  // servers run in parallel (e.g. in a test shard).
  std::optional<int> port = std::nullopt;

  // v1model drop-port override (the `--drop-port` flag). Accepts a raw
  // dataplane port number or a P4RT port name (resolved at pipeline load).
  std::optional<PortOverride> drop_port = std::nullopt;

  // CPU port configuration (the `--cpu-port` flag).
  CpuPort cpu_port = CpuPort::Auto();

  // Skip @refers_to referential integrity checking on Write RPCs.
  bool disable_refers_to_checking = false;

  // Skip @entry_restriction / @action_restriction checking on Write RPCs.
  bool disable_p4_constraints_checking = false;

  // Maximum gRPC metadata (header) size in bytes. Large P4Runtime batch errors
  // return per-entity status details that easily exceed the default 8KB limit,
  // causing Netty to reset the HTTP/2 stream with PROTOCOL_ERROR.
  int max_metadata_size = 10 * 1024 * 1024;  // 10MB

  // Maximum inbound gRPC message size in bytes. -1 means unlimited. Prevents
  // client failures under the default 4MB limit when reading large flow tables
  // via P4Runtime Read.
  int max_receive_message_size = -1;

  // Server-side keepalive permissions. Permissive defaults allow aggressive
  // SDN controller keepalive pings without triggering Netty ping strikes.
  bool permit_keepalive_without_calls = true;
  int permit_keepalive_time_ms = 0;

  // Tee captured stdout/stderr to the parent's original fds so server output
  // appears in the test log. Disable to capture without console noise.
  bool tee = true;

  // Maximum time to wait for Start() to complete. JVM cold starts can
  // exceed 5s under CI load, so the default is generous.
  absl::Duration startup_timeout = absl::Seconds(15);
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

  // Subprocess stdout/stderr captured since Start(). Thread-safe; may be
  // called while the server is still running to observe partial output.
  std::string Stdout() const;
  std::string Stderr() const;

 private:
  FourwardServer(pid_t pid, int port, uint64_t device_id,
                 std::string scratch_dir,
                 std::shared_ptr<grpc::Channel> channel,
                 std::unique_ptr<OutputCapture> stdout_capture,
                 std::unique_ptr<OutputCapture> stderr_capture);

  // Kills the subprocess (SIGTERM → SIGKILL) and removes the scratch dir.
  void Shutdown();

  pid_t pid_ = -1;
  int port_ = 0;
  uint64_t device_id_ = 0;
  // Scratch directory holding the `--port-file`. Removed on Shutdown.
  std::string scratch_dir_;
  std::shared_ptr<grpc::Channel> channel_;
  std::unique_ptr<OutputCapture> stdout_capture_;
  std::unique_ptr<OutputCapture> stderr_capture_;
};

}  // namespace fourward

#endif  // FOURWARD_CC_FOURWARD_SERVER_H_
