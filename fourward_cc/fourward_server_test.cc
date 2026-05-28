// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/fourward_server.h"

#include <signal.h>
#include <sys/types.h>

#include <chrono>
#include <thread>
#include <utility>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/time/time.h"
#include "grpcpp/client_context.h"
#include "grpcpp/support/status.h"
#include "gtest/gtest.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4/v1/p4runtime.pb.h"

namespace fourward {
namespace {

// Issues a Capabilities RPC and asserts it succeeds. This proves the server
// is not just TCP-listening but actually serving gRPC. Capabilities is used
// rather than GetForwardingPipelineConfig because the latter requires a
// pipeline to be loaded (FAILED_PRECONDITION otherwise), which is orthogonal
// to the "is the server up" question under test.
void ExpectHealthy(const FourwardServer& server) {
  auto stub = server.NewP4RuntimeStub();
  p4::v1::CapabilitiesRequest req;
  p4::v1::CapabilitiesResponse resp;
  grpc::ClientContext ctx;
  grpc::Status status = stub->Capabilities(&ctx, req, &resp);
  EXPECT_TRUE(status.ok()) << "Capabilities failed: code=" << status.error_code()
                           << " msg=" << status.error_message();
  EXPECT_FALSE(resp.p4runtime_api_version().empty());
}

TEST(FourwardServerTest, StartExposesLiveGrpcEndpoint) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start();
  ASSERT_TRUE(server.ok()) << server.status();

  EXPECT_GT(server->Port(), 0);
  EXPECT_EQ(server->Address(), absl::StrCat("localhost:", server->Port()));
  EXPECT_EQ(server->DeviceId(), 1u);
  EXPECT_GT(server->Pid(), 0);
  EXPECT_NE(server->Channel(), nullptr);
  EXPECT_NE(server->NewP4RuntimeStub(), nullptr);
  EXPECT_NE(server->NewDataplaneStub(), nullptr);

  ExpectHealthy(*server);
}

TEST(FourwardServerTest, LargeHeaderSucceeds) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start();
  ASSERT_TRUE(server.ok()) << server.status();

  auto stub = server->NewP4RuntimeStub();
  p4::v1::CapabilitiesRequest req;
  p4::v1::CapabilitiesResponse resp;
  grpc::ClientContext ctx;

  // 5MB header is well above default 8KB but below our 10MB limit.
  std::string large_value(5 * 1024 * 1024, 'a');
  ctx.AddMetadata("large-header", large_value);

  grpc::Status status = stub->Capabilities(&ctx, req, &resp);
  EXPECT_TRUE(status.ok()) << "Capabilities failed with large header: code=" << status.error_code()
                           << " msg=" << status.error_message();
}

TEST(FourwardServerTest, CustomDeviceIdFlowsThroughToP4Runtime) {
  absl::StatusOr<FourwardServer> server =
      FourwardServer::Start({.device_id = 42});
  ASSERT_TRUE(server.ok()) << server.status();
  EXPECT_EQ(server->DeviceId(), 42u);
  ExpectHealthy(*server);
}

TEST(FourwardServerTest, ParallelServersGetDistinctEphemeralPorts) {
  absl::StatusOr<FourwardServer> a = FourwardServer::Start();
  absl::StatusOr<FourwardServer> b = FourwardServer::Start();
  ASSERT_TRUE(a.ok()) << a.status();
  ASSERT_TRUE(b.ok()) << b.status();
  EXPECT_NE(a->Port(), b->Port());
  ExpectHealthy(*a);
  ExpectHealthy(*b);
}

TEST(FourwardServerTest, DestructionKillsSubprocess) {
  pid_t pid;
  {
    absl::StatusOr<FourwardServer> server = FourwardServer::Start();
    ASSERT_TRUE(server.ok()) << server.status();
    pid = server->Pid();
    ExpectHealthy(*server);
  }

  // The server had `Shutdown()` called in the destructor. Poll waitid(NOWAIT)
  // so we don't race the reap; once it returns ECHILD the process is gone.
  // (Our own child was already waitpid()'d inside Shutdown; this probe just
  // confirms the kernel has no such PID anymore.)
  auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
  while (std::chrono::steady_clock::now() < deadline) {
    if (::kill(pid, 0) != 0) return;  // process gone — success.
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  FAIL() << "server pid " << pid << " was still alive 5s after destruction";
}

TEST(FourwardServerTest, MoveConstructionPreservesOwnership) {
  absl::StatusOr<FourwardServer> original = FourwardServer::Start();
  ASSERT_TRUE(original.ok()) << original.status();
  pid_t original_pid = original->Pid();
  int original_port = original->Port();

  FourwardServer moved = *std::move(original);

  EXPECT_EQ(moved.Pid(), original_pid);
  EXPECT_EQ(moved.Port(), original_port);
  ExpectHealthy(moved);
}

TEST(FourwardServerTest, MoveAssignmentKillsOldAndAdoptsNew) {
  absl::StatusOr<FourwardServer> a = FourwardServer::Start();
  absl::StatusOr<FourwardServer> b = FourwardServer::Start();
  ASSERT_TRUE(a.ok()) << a.status();
  ASSERT_TRUE(b.ok()) << b.status();
  pid_t pid_a = a->Pid();
  pid_t pid_b = b->Pid();
  ASSERT_NE(pid_a, pid_b);

  // Move-assign b into a: a's old subprocess must be killed, b's must live
  // and serve RPCs through the moved-to wrapper.
  *a = *std::move(b);

  EXPECT_EQ(a->Pid(), pid_b);
  ExpectHealthy(*a);

  auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
  while (std::chrono::steady_clock::now() < deadline) {
    if (::kill(pid_a, 0) != 0) return;
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  FAIL() << "old subprocess pid " << pid_a
         << " should have been killed by move-assign";
}

TEST(FourwardServerTest, DropAndCpuPortFlagsAcceptedByServer) {
  // This is mostly a smoke test — the wrapper passes the flags through, and
  // the server comes up. Deeper validation of drop/cpu port semantics belongs
  // in the Kotlin-side tests.
  absl::StatusOr<FourwardServer> with_drop =
      FourwardServer::Start({.drop_port = PortOverride::Dataplane(511)});
  ASSERT_TRUE(with_drop.ok()) << with_drop.status();
  ExpectHealthy(*with_drop);

  absl::StatusOr<FourwardServer> cpu_disabled =
      FourwardServer::Start({.cpu_port = CpuPort::Disabled()});
  ASSERT_TRUE(cpu_disabled.ok()) << cpu_disabled.status();
  ExpectHealthy(*cpu_disabled);

  absl::StatusOr<FourwardServer> cpu_override =
      FourwardServer::Start({.cpu_port = CpuPort::Override(192)});
  ASSERT_TRUE(cpu_override.ok()) << cpu_override.status();
  ExpectHealthy(*cpu_override);
}

TEST(FourwardServerTest, DisableCheckingFlagsAcceptedByServer) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start(
      {.disable_refers_to_checking = true,
       .disable_p4_constraints_checking = true});
  ASSERT_TRUE(server.ok()) << server.status();
  ExpectHealthy(*server);
}

TEST(FourwardServerTest, StartupTimeoutYieldsDeadlineExceeded) {
  // A 1-nanosecond timeout is unreachable: even if the JVM had booted
  // instantly the port file poll loop cannot observe it that fast. The
  // wrapper must surface DEADLINE_EXCEEDED rather than hanging or
  // returning OK with a bogus port.
  absl::StatusOr<FourwardServer> server =
      FourwardServer::Start({.startup_timeout = absl::Nanoseconds(1)});
  ASSERT_FALSE(server.ok());
  EXPECT_EQ(server.status().code(), absl::StatusCode::kDeadlineExceeded)
      << server.status();
}

}  // namespace
}  // namespace fourward
