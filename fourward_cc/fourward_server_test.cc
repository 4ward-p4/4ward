// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/fourward_server.h"

#include <signal.h>
#include <sys/types.h>

#include <chrono>
#include <cstddef>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <thread>
#include <utility>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/time/clock.h"
#include "absl/time/time.h"
#include "gmock/gmock.h"
#include "grpcpp/client_context.h"
#include "grpcpp/support/status.h"
#include "gtest/gtest.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4/v1/p4runtime.pb.h"
#include "tools/cpp/runfiles/runfiles.h"

#ifndef PASSTHROUGH_PIPELINE_RLOCATION
#error "PASSTHROUGH_PIPELINE_RLOCATION must be set by the BUILD rule"
#endif

namespace fourward {
namespace {

using ::bazel::tools::cpp::runfiles::Runfiles;

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

TEST(FourwardServerTest, LargeMetadataSucceedsUnderDefaultLimits) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start();
  ASSERT_TRUE(server.ok()) << server.status();

  auto stub = server->NewP4RuntimeStub();
  p4::v1::CapabilitiesRequest req;
  p4::v1::CapabilitiesResponse resp;
  grpc::ClientContext ctx;

  // 1MB header is well above the default gRPC 8KB limit but below our 10MB.
  std::string large_value(1024 * 1024, 'a');
  ctx.AddMetadata("large-header", large_value);

  grpc::Status status = stub->Capabilities(&ctx, req, &resp);
  EXPECT_TRUE(status.ok()) << "Capabilities failed: code=" << status.error_code()
                           << " msg=" << status.error_message();
}

TEST(FourwardServerTest, TightMetadataLimitRejectsLargeHeader) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start({
      .max_metadata_size = 8192,
  });
  ASSERT_TRUE(server.ok()) << server.status();

  auto stub = server->NewP4RuntimeStub();
  p4::v1::CapabilitiesRequest req;
  p4::v1::CapabilitiesResponse resp;
  grpc::ClientContext ctx;

  // 16KB header exceeds the 8KB limit.
  std::string large_value(16384, 'a');
  ctx.AddMetadata("large-header", large_value);

  grpc::Status status = stub->Capabilities(&ctx, req, &resp);
  EXPECT_FALSE(status.ok());
}

TEST(FourwardServerTest, LargeMessageSucceedsUnderDefaultLimits) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start();
  ASSERT_TRUE(server.ok()) << server.status();

  auto stub = server->NewP4RuntimeStub();
  p4::v1::SetForwardingPipelineConfigRequest req;
  p4::v1::SetForwardingPipelineConfigResponse resp;
  grpc::ClientContext ctx;

  req.set_device_id(server->DeviceId());
  req.set_action(p4::v1::SetForwardingPipelineConfigRequest::VERIFY);
  // 5MB payload exceeds the default 4MB gRPC limit. The RPC will fail for
  // application-level reasons (garbage config), but it must not fail with
  // RESOURCE_EXHAUSTED — that would mean the size gate rejected it.
  req.mutable_config()->set_p4_device_config(
      std::string(5 * 1024 * 1024, 'a'));

  grpc::Status status =
      stub->SetForwardingPipelineConfig(&ctx, req, &resp);
  EXPECT_NE(status.error_code(), grpc::StatusCode::RESOURCE_EXHAUSTED)
      << "SetForwardingPipelineConfig hit message size limit: "
      << status.error_message();
}

TEST(FourwardServerTest, TightMessageLimitRejectsLargeMessage) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start({
      .max_receive_message_size = 1024 * 1024,
  });
  ASSERT_TRUE(server.ok()) << server.status();

  auto stub = server->NewP4RuntimeStub();
  p4::v1::SetForwardingPipelineConfigRequest req;
  p4::v1::SetForwardingPipelineConfigResponse resp;
  grpc::ClientContext ctx;

  req.set_device_id(server->DeviceId());
  req.set_action(p4::v1::SetForwardingPipelineConfigRequest::VERIFY);
  // 2MB payload exceeds the 1MB limit.
  req.mutable_config()->set_p4_device_config(
      std::string(2 * 1024 * 1024, 'a'));

  grpc::Status status =
      stub->SetForwardingPipelineConfig(&ctx, req, &resp);
  // The server rejects the message with RESOURCE_EXHAUSTED, but Netty may
  // close the stream before the error propagates to the client — so we only
  // assert the RPC fails, not the specific code.
  EXPECT_FALSE(status.ok())
      << "Expected RPC failure due to message size limit";
}

TEST(FourwardServerTest, CustomGrpcOptionsAccepted) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start({
      .max_metadata_size = 16 * 1024 * 1024,
      .max_receive_message_size = 8 * 1024 * 1024,
      .permit_keepalive_without_calls = false,
      .permit_keepalive_time_ms = 15000,
  });
  ASSERT_TRUE(server.ok()) << server.status();
  ExpectHealthy(*server);
}

// Appends a valid proto field to `proto` so the serialized message grows by
// `size` bytes of payload. Uses field 15 (wire type 2 / length-delimited)
// which is treated as an unknown field by any proto message type — the proto
// parser preserves it, inflating the serialized size on round-trip.
void AppendProtoPadding(std::string& proto, size_t size) {
  proto.push_back(static_cast<char>(0x7A));  // tag: (15 << 3) | 2
  size_t remaining = size;
  while (remaining >= 0x80) {
    proto.push_back(static_cast<char>((remaining & 0x7F) | 0x80));
    remaining >>= 7;
  }
  proto.push_back(static_cast<char>(remaining));
  proto.append(size, 'x');
}

// Pushes a ForwardingPipelineConfig to the server via the P4Runtime API.
// Establishes master arbitration first.
grpc::Status PushPipeline(
    const FourwardServer& server,
    const p4::v1::ForwardingPipelineConfig& config) {
  auto stub = server.NewP4RuntimeStub();

  grpc::ClientContext stream_ctx;
  auto stream = stub->StreamChannel(&stream_ctx);
  p4::v1::StreamMessageRequest arb_req;
  arb_req.mutable_arbitration()->set_device_id(server.DeviceId());
  arb_req.mutable_arbitration()->mutable_election_id()->set_low(1);
  stream->Write(arb_req);
  p4::v1::StreamMessageResponse arb_resp;
  stream->Read(&arb_resp);

  grpc::ClientContext set_ctx;
  p4::v1::SetForwardingPipelineConfigRequest req;
  req.set_device_id(server.DeviceId());
  req.mutable_election_id()->set_low(1);
  req.set_action(
      p4::v1::SetForwardingPipelineConfigRequest::VERIFY_AND_COMMIT);
  *req.mutable_config() = config;
  p4::v1::SetForwardingPipelineConfigResponse resp;
  grpc::Status status =
      stub->SetForwardingPipelineConfig(&set_ctx, req, &resp);

  stream_ctx.TryCancel();
  return status;
}

TEST(FourwardServerTest, LargeResponseSucceedsUnderDefaultLimits) {
  // Load the passthrough pipeline from runfiles and inflate p4_device_config
  // to >4MB with a valid proto unknown field. After pushing the padded config,
  // GetForwardingPipelineConfig returns it — producing a response that exceeds
  // gRPC's default 4MB receive limit. This verifies the client channel is
  // correctly configured with an unlimited receive message size.
  std::string error;
  std::unique_ptr<Runfiles> runfiles(Runfiles::Create("", &error));
  ASSERT_NE(runfiles, nullptr) << error;
  std::string path =
      runfiles->Rlocation(PASSTHROUGH_PIPELINE_RLOCATION);
  std::ifstream file(path, std::ios::binary);
  ASSERT_TRUE(file.good()) << "cannot open: " << path;
  std::string bytes((std::istreambuf_iterator<char>(file)),
                    std::istreambuf_iterator<char>());

  p4::v1::ForwardingPipelineConfig config;
  ASSERT_TRUE(config.ParseFromString(bytes));
  std::string padded_device_config = config.p4_device_config();
  AppendProtoPadding(padded_device_config, 5 * 1024 * 1024);
  config.set_p4_device_config(padded_device_config);

  absl::StatusOr<FourwardServer> server = FourwardServer::Start();
  ASSERT_TRUE(server.ok()) << server.status();

  grpc::Status push_status = PushPipeline(*server, config);
  ASSERT_TRUE(push_status.ok())
      << "Push failed: " << push_status.error_message();

  auto stub = server->NewP4RuntimeStub();
  grpc::ClientContext get_ctx;
  p4::v1::GetForwardingPipelineConfigRequest get_req;
  get_req.set_device_id(server->DeviceId());
  get_req.set_response_type(
      p4::v1::GetForwardingPipelineConfigRequest::ALL);
  p4::v1::GetForwardingPipelineConfigResponse get_resp;
  grpc::Status get_status =
      stub->GetForwardingPipelineConfig(&get_ctx, get_req, &get_resp);
  EXPECT_TRUE(get_status.ok())
      << "GetForwardingPipelineConfig failed on >4MB response: code="
      << get_status.error_code()
      << " msg=" << get_status.error_message();
}

TEST(FourwardServerTest, StdoutCapturesStartupMessage) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start();
  ASSERT_TRUE(server.ok()) << server.status();
  absl::SleepFor(absl::Milliseconds(100));
  std::string stdout_output = server->Stdout();
  EXPECT_THAT(stdout_output,
              ::testing::HasSubstr("P4Runtime server listening on port"));
  EXPECT_THAT(stdout_output,
              ::testing::HasSubstr(absl::StrCat(server->Port())));
}

TEST(FourwardServerTest, StderrInitiallyEmptyForHealthyServer) {
  absl::StatusOr<FourwardServer> server = FourwardServer::Start();
  ASSERT_TRUE(server.ok()) << server.status();
  absl::SleepFor(absl::Milliseconds(100));
  EXPECT_TRUE(server->Stderr().empty())
      << "unexpected stderr: " << server->Stderr();
}

TEST(FourwardServerTest, QuietModeSuppressesTeeButStillCaptures) {
  absl::StatusOr<FourwardServer> server =
      FourwardServer::Start({.quiet = true});
  ASSERT_TRUE(server.ok()) << server.status();
  absl::SleepFor(absl::Milliseconds(100));
  EXPECT_THAT(server->Stdout(),
              ::testing::HasSubstr("P4Runtime server listening on port"));
}

TEST(FourwardServerTest, CapturedOutputSurvivesMove) {
  absl::StatusOr<FourwardServer> original = FourwardServer::Start();
  ASSERT_TRUE(original.ok()) << original.status();
  absl::SleepFor(absl::Milliseconds(100));

  FourwardServer moved = *std::move(original);
  EXPECT_THAT(moved.Stdout(),
              ::testing::HasSubstr("P4Runtime server listening on port"));
}

}  // namespace
}  // namespace fourward
