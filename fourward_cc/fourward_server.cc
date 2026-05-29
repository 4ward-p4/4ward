// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/fourward_server.h"

#include <signal.h>
#include <spawn.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <charconv>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

#include "absl/cleanup/cleanup.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/str_format.h"
#include "absl/time/clock.h"
#include "absl/time/time.h"
#include "grpcpp/create_channel.h"
#include "grpcpp/security/credentials.h"
#include "grpcpp/support/channel_arguments.h"
#include "tools/cpp/runfiles/runfiles.h"

#ifndef FOURWARD_SERVER_RLOCATION
#error "FOURWARD_SERVER_RLOCATION must be set by the BUILD rule"
#endif

extern char** environ;

namespace fourward {
namespace {

using ::bazel::tools::cpp::runfiles::Runfiles;

// Runfile path of the server binary, baked in from BUILD via
// `$(rlocationpath //grpc:fourward_server)`. Has the canonical repo
// name (e.g. `_main/...` or `fourward+/...`) appropriate to the current
// build.
constexpr char kServerRunfile[] = FOURWARD_SERVER_RLOCATION;

// Flag names passed to the Kotlin server, kept in one place so the drift
// between wrapper and server is easy to audit.
constexpr char kFlagPort[] = "--port=";
constexpr char kFlagDeviceId[] = "--device-id=";
constexpr char kFlagPortFile[] = "--port-file=";
constexpr char kFlagDropPort[] = "--drop-port=";
constexpr char kFlagCpuPort[] = "--cpu-port=";
constexpr char kCpuPortNoneValue[] = "none";
constexpr char kFlagDisableRefersToChecking[] = "--disable-refers-to-checking";
constexpr char kFlagDisableP4ConstraintsChecking[] =
    "--disable-p4-constraints-checking";
constexpr char kFlagMaxMetadataSize[] = "--max-metadata-size=";
constexpr char kFlagMaxReceiveMessageSize[] = "--max-receive-message-size=";
constexpr char kFlagPermitKeepaliveWithoutCalls[] =
    "--permit-keepalive-without-calls=";
constexpr char kFlagPermitKeepaliveTimeMs[] = "--permit-keepalive-time-ms=";

// Creates a unique scratch directory under $TEST_TMPDIR (honored by Bazel
// test shards) or /tmp.
absl::StatusOr<std::string> MakeScratchDir() {
  const char* base = std::getenv("TEST_TMPDIR");
  if (base == nullptr || *base == '\0') base = "/tmp";
  std::string tmpl = absl::StrCat(base, "/fourward-server-XXXXXX");
  std::vector<char> buf(tmpl.begin(), tmpl.end());
  buf.push_back('\0');
  if (mkdtemp(buf.data()) == nullptr) {
    return absl::ErrnoToStatus(errno, "mkdtemp");
  }
  return std::string(buf.data());
}

// Best-effort recursive removal of `path`; errors are swallowed because
// cleanup runs from destructors and we've already done our job if the
// process was reaped.
void RemoveScratchDir(const std::string& path) {
  if (path.empty()) return;
  std::error_code ec;
  std::filesystem::remove_all(path, ec);
}

absl::StatusOr<int> ReadPortFile(const std::string& path) {
  std::ifstream in(path);
  std::string contents((std::istreambuf_iterator<char>(in)),
                       std::istreambuf_iterator<char>());
  int port = 0;
  auto [ptr, ec] = std::from_chars(contents.data(),
                                   contents.data() + contents.size(), port);
  if (ec != std::errc() || port <= 0 || port > 65535) {
    return absl::InternalError(
        absl::StrCat("port file at ", path, " has invalid contents: '",
                     contents, "'"));
  }
  return port;
}

// Waits for `path` to exist (meaning: the server has bound and published its
// port) or `deadline` to elapse. Returns DeadlineExceededError on timeout.
// The child's exit is checked on each poll so we don't hang forever if the
// server crashed before writing the file.
absl::Status WaitForPortFile(const std::string& path, pid_t child_pid,
                             absl::Time deadline) {
  constexpr absl::Duration kPoll = absl::Milliseconds(25);
  while (absl::Now() < deadline) {
    struct stat st;
    if (::stat(path.c_str(), &st) == 0 && st.st_size > 0) {
      return absl::OkStatus();
    }

    int status = 0;
    pid_t waited = ::waitpid(child_pid, &status, WNOHANG);
    if (waited == child_pid) {
      return absl::InternalError(absl::StrFormat(
          "server subprocess exited before reporting its port "
          "(pid=%d, status=0x%x). Check server stderr for a stack trace.",
          child_pid, status));
    }

    absl::SleepFor(kPoll);
  }
  return absl::DeadlineExceededError(absl::StrCat(
      "server did not publish its port to ", path, " before the timeout"));
}

// Reaps `pid` with a bounded wait; returns true if the process is gone.
bool TryReap(pid_t pid, absl::Duration budget) {
  absl::Time deadline = absl::Now() + budget;
  do {
    int status = 0;
    pid_t waited = ::waitpid(pid, &status, WNOHANG);
    if (waited == pid) return true;
    if (waited < 0 && errno == ECHILD) return true;
    absl::SleepFor(absl::Milliseconds(20));
  } while (absl::Now() < deadline);
  return false;
}

// Kills the process group led by `pid` (SIGTERM then SIGKILL) and reaps the
// child. Safe to call when `pid <= 0`. The 4ward server holds no persistent
// state (see AGENTS.md invariant #5), so a 1s SIGTERM grace is ample; the
// remaining 2s covers SIGKILL reap after the escalation.
void KillAndReap(pid_t pid) {
  if (pid <= 0) return;
  ::killpg(pid, SIGTERM);
  if (!TryReap(pid, absl::Seconds(1))) {
    ::killpg(pid, SIGKILL);
    TryReap(pid, absl::Seconds(2));
  }
}

}  // namespace

absl::StatusOr<FourwardServer> FourwardServer::Start(
    FourwardServerOptions options) {
  std::string runfiles_error;
  // `FOURWARD_SERVER_RLOCATION` is a canonical path already, so we use the
  // `Create(argv0, error)` overload rather than the one that takes
  // `BAZEL_CURRENT_REPOSITORY` — the latter is only needed for resolving
  // apparent names, and skipping it keeps the wrapper portable to
  // environments that don't inject that macro (e.g. google3).
  std::unique_ptr<Runfiles> runfiles(Runfiles::Create("", &runfiles_error));
  if (runfiles == nullptr) {
    return absl::InternalError(
        absl::StrCat("failed to resolve runfiles: ", runfiles_error));
  }
  std::string server_path = runfiles->Rlocation(kServerRunfile);
  if (server_path.empty()) {
    return absl::NotFoundError(absl::StrCat(
        "4ward P4Runtime server binary not found in runfiles (expected ",
        kServerRunfile, ")"));
  }

  absl::StatusOr<std::string> scratch = MakeScratchDir();
  if (!scratch.ok()) return std::move(scratch).status();
  std::string port_file = absl::StrCat(*scratch, "/port");

  // One guard owns every in-flight resource until Start() commits. On any
  // early return (spawn failure, port-file timeout, malformed port, …) the
  // guard kills the child and removes the scratch dir. On success we cancel
  // it and transfer ownership of scratch to the FourwardServer instance.
  pid_t pid = -1;
  absl::Cleanup guard = [&] {
    KillAndReap(pid);
    RemoveScratchDir(*scratch);
  };

  std::vector<std::string> args = {
      server_path,
      absl::StrCat(kFlagPort, options.port.value_or(0)),
      absl::StrCat(kFlagDeviceId, options.device_id),
      absl::StrCat(kFlagPortFile, port_file),
  };
  if (options.drop_port.has_value()) {
    args.push_back(absl::StrCat(kFlagDropPort, options.drop_port->ToFlagValue()));
  }
  switch (options.cpu_port.kind) {
    case CpuPort::Kind::kAuto:
      break;  // Default; omit the flag.
    case CpuPort::Kind::kDisabled:
      args.push_back(absl::StrCat(kFlagCpuPort, kCpuPortNoneValue));
      break;
    case CpuPort::Kind::kOverride:
      args.push_back(absl::StrCat(
          kFlagCpuPort, options.cpu_port.port_override.ToFlagValue()));
      break;
  }
  if (options.disable_refers_to_checking) {
    args.push_back(kFlagDisableRefersToChecking);
  }
  if (options.disable_p4_constraints_checking) {
    args.push_back(kFlagDisableP4ConstraintsChecking);
  }
  args.push_back(
      absl::StrCat(kFlagMaxMetadataSize, options.max_metadata_size));
  args.push_back(absl::StrCat(kFlagMaxReceiveMessageSize,
                               options.max_receive_message_size));
  args.push_back(absl::StrCat(kFlagPermitKeepaliveWithoutCalls,
                               options.permit_keepalive_without_calls
                                   ? "true"
                                   : "false"));
  args.push_back(absl::StrCat(kFlagPermitKeepaliveTimeMs,
                               options.permit_keepalive_time_ms));
  std::vector<char*> argv;
  argv.reserve(args.size() + 1);
  for (auto& a : args) argv.push_back(a.data());
  argv.push_back(nullptr);

  // Put the server into its own process group so SIGTERM to the group fans
  // out to any JVM grandchildren (e.g. native Netty helpers) without
  // touching the parent. posix_spawn with POSIX_SPAWN_SETPGROUP + pgroup=0
  // is the portable way to request that.
  posix_spawnattr_t attr;
  if (int rc = posix_spawnattr_init(&attr); rc != 0) {
    return absl::ErrnoToStatus(rc, "posix_spawnattr_init");
  }
  absl::Cleanup attr_destroy = [&] { posix_spawnattr_destroy(&attr); };
  if (int rc = posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETPGROUP);
      rc != 0) {
    return absl::ErrnoToStatus(rc, "posix_spawnattr_setflags");
  }
  if (int rc = posix_spawnattr_setpgroup(&attr, 0); rc != 0) {
    return absl::ErrnoToStatus(rc, "posix_spawnattr_setpgroup");
  }

  if (int rc = posix_spawn(&pid, server_path.c_str(), /*file_actions=*/nullptr,
                           &attr, argv.data(), environ);
      rc != 0) {
    return absl::ErrnoToStatus(rc, "posix_spawn");
  }

  absl::Time deadline = absl::Now() + options.startup_timeout;
  if (absl::Status s = WaitForPortFile(port_file, pid, deadline); !s.ok()) {
    return s;
  }
  absl::StatusOr<int> port = ReadPortFile(port_file);
  if (!port.ok()) return std::move(port).status();

  grpc::ChannelArguments channel_args;
  channel_args.SetInt("grpc.max_metadata_size", options.max_metadata_size);
  channel_args.SetMaxReceiveMessageSize(options.max_receive_message_size);
  auto channel = grpc::CreateCustomChannel(absl::StrCat("localhost:", *port),
                                           grpc::InsecureChannelCredentials(),
                                           channel_args);

  std::move(guard).Cancel();
  return FourwardServer(pid, *port, options.device_id, std::move(*scratch),
                        std::move(channel));
}

FourwardServer::FourwardServer(pid_t pid, int port, uint64_t device_id,
                               std::string scratch_dir,
                               std::shared_ptr<grpc::Channel> channel)
    : pid_(pid),
      port_(port),
      device_id_(device_id),
      scratch_dir_(std::move(scratch_dir)),
      channel_(std::move(channel)) {}

FourwardServer::FourwardServer(FourwardServer&& other) noexcept
    : pid_(std::exchange(other.pid_, -1)),
      port_(other.port_),
      device_id_(other.device_id_),
      scratch_dir_(std::move(other.scratch_dir_)),
      channel_(std::move(other.channel_)) {}

FourwardServer& FourwardServer::operator=(FourwardServer&& other) noexcept {
  if (this != &other) {
    Shutdown();
    pid_ = std::exchange(other.pid_, -1);
    port_ = other.port_;
    device_id_ = other.device_id_;
    scratch_dir_ = std::move(other.scratch_dir_);
    channel_ = std::move(other.channel_);
  }
  return *this;
}

FourwardServer::~FourwardServer() { Shutdown(); }

void FourwardServer::Shutdown() {
  // Drop our channel reference before killing the subprocess so the channel
  // has a chance to finalize cleanly rather than noticing the socket die
  // from under it.
  channel_.reset();
  KillAndReap(pid_);
  pid_ = -1;
  RemoveScratchDir(scratch_dir_);
  scratch_dir_.clear();
}

}  // namespace fourward
