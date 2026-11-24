// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

// Runs the sonic-pins P4 fuzzer against a 4ward P4Runtime server.
//
// Three layers of validation:
// 1. Spec oracle: validates response status codes against P4Runtime spec.
// 2. Read-back: periodically reads all entries and verifies they match the
//    SwitchState mirror.
// 3. Crash detection: any unhandled exception or server crash is a failure.

#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "absl/log/log.h"
#include "absl/random/random.h"
#include "absl/strings/str_cat.h"
#include "absl/types/span.h"
#include "fourward_cc/fourward_server.h"
#include "google/rpc/code.pb.h"
#include "grpcpp/security/credentials.h"
#include "gtest/gtest.h"
#include "gutil/status_matchers.h"
#include "lib/p4rt/p4rt_port.h"
#include "p4/v1/p4runtime.pb.h"
#include "p4_fuzzer/annotation_util.h"
#include "p4_fuzzer/fuzz_util.h"
#include "p4_fuzzer/fuzzer.pb.h"
#include "p4_fuzzer/fuzzer_config.h"
#include "p4_fuzzer/oracle_util.h"
#include "p4_fuzzer/switch_state.h"
#include "p4_infra/p4_runtime/p4_runtime_session.h"
#include "p4_infra/p4_runtime/p4_runtime_session_extras.h"
#include "tools/cpp/runfiles/runfiles.h"

namespace fourward {
namespace {

using ::bazel::tools::cpp::runfiles::Runfiles;
using ::p4_fuzzer::AnnotatedWriteRequest;
using ::p4_fuzzer::FuzzWriteRequest;
using ::p4_fuzzer::FuzzerConfig;
using ::p4_fuzzer::RemoveAnnotations;
using ::p4_fuzzer::SwitchState;
using ::p4_fuzzer::WriteRequestOracle;
using ::p4_runtime::P4RuntimeSession;

constexpr int kFuzzerIterations = 10000;
constexpr int kReadBackInterval = 500;

p4::v1::ForwardingPipelineConfig LoadPipeline() {
  std::string error;
  std::unique_ptr<Runfiles> runfiles(Runfiles::Create("", &error));
  CHECK(runfiles != nullptr) << error;
  std::string path = runfiles->Rlocation(PIPELINE_RLOCATION);
  std::ifstream file(path, std::ios::binary);
  CHECK(file.good()) << "cannot open: " << path;
  std::string content((std::istreambuf_iterator<char>(file)),
                      std::istreambuf_iterator<char>());
  p4::v1::ForwardingPipelineConfig config;
  CHECK(config.ParseFromString(content)) << "failed to parse pipeline";
  return config;
}

bool ShouldSkipUpdate(const p4_fuzzer::AnnotatedUpdate& update) {
  // MODIFY: oracle crashes (upstream b/126750297).
  if (update.pi().type() == p4::v1::Update::MODIFY) return true;
  // Mutated DELETEs: oracle validates action/match fields on DELETEs, but
  // the spec says DELETE only needs the key (§9.1). 4ward correctly skips
  // action validation on DELETE; the oracle incorrectly flags this.
  if (update.pi().type() == p4::v1::Update::DELETE &&
      update.mutations_size() > 0)
    return true;
  return false;
}

TEST(P4FuzzerTest, FuzzWriteRequestsAgainstFourward) {
  ASSERT_OK_AND_ASSIGN(FourwardServer server, FourwardServer::Start());

  p4_runtime::P4RuntimeSessionOptionalArgs session_args;
  session_args.role = "";
  ASSERT_OK_AND_ASSIGN(
      auto session,
      P4RuntimeSession::Create(server.Address(),
                               grpc::InsecureChannelCredentials(),
                               server.DeviceId(), session_args));

  auto pipeline = LoadPipeline();
  ASSERT_OK(p4_runtime::SetMetadataAndSetForwardingPipelineConfig(
      session.get(),
      p4::v1::SetForwardingPipelineConfigRequest::VERIFY_AND_COMMIT,
      pipeline));

  p4_fuzzer::ConfigParams params;
  params.ports =
      pins_test::P4rtPortId::MakeVectorFromOpenConfigEncodings({1, 2, 3});
  params.role = "";
  params.mutate_update_probability = 0.1;
  ASSERT_OK_AND_ASSIGN(auto config,
                       FuzzerConfig::Create(pipeline.p4info(), params));
  SwitchState switch_state(config.GetIrP4Info());
  absl::BitGen gen;

  int num_updates = 0;
  int num_oracle_failures = 0;
  int num_readback_checks = 0;

  for (int i = 0; i < kFuzzerIterations; ++i) {
    if (i % 1000 == 0) LOG(INFO) << "Fuzzer iteration " << i;

    AnnotatedWriteRequest annotated_request =
        FuzzWriteRequest(&gen, config, switch_state);

    // Filter updates the oracle can't handle correctly.
    AnnotatedWriteRequest filtered_request;
    for (const auto& update : annotated_request.updates()) {
      if (!ShouldSkipUpdate(update)) {
        *filtered_request.add_updates() = update;
      }
    }
    annotated_request = filtered_request;

    p4::v1::WriteRequest request = RemoveAnnotations(annotated_request);
    if (request.updates_size() == 0) continue;
    num_updates += request.updates_size();

    ASSERT_OK_AND_ASSIGN(
        auto response,
        p4_runtime::SendPiUpdatesAndReturnPerUpdateStatus(*session,
                                                         request.updates()));
    ASSERT_TRUE(response.has_rpc_response())
        << "RPC-level error: " << response.DebugString();
    ASSERT_EQ(response.rpc_response().statuses().size(),
              request.updates_size());

    // Layer 1: spec oracle.
    std::vector<pdpi::IrUpdateStatus> statuses(
        response.rpc_response().statuses().begin(),
        response.rpc_response().statuses().end());
    auto problems = WriteRequestOracle(config.GetIrP4Info(), annotated_request,
                                       absl::MakeSpan(statuses), switch_state);
    if (problems.has_value()) {
      num_oracle_failures++;
      for (const std::string& problem : *problems) {
        ADD_FAILURE() << "Oracle failure at iteration " << i << ": " << problem;
      }
    }

    // Update switch state with successful writes.
    for (int j = 0; j < request.updates_size(); ++j) {
      if (statuses[j].code() == google::rpc::Code::OK) {
        ASSERT_OK(switch_state.ApplyUpdate(request.updates(j)));
      }
    }

    // Layer 2: periodic read-back verification.
    if ((i + 1) % kReadBackInterval == 0) {
      num_readback_checks++;
      ASSERT_OK_AND_ASSIGN(
          auto entries, p4_runtime::ReadPiTableEntriesSorted(*session));
      auto readback_status = switch_state.AssertEntriesAreEqualToState(entries);
      ASSERT_OK(readback_status)
          << "Read-back mismatch at iteration " << i << ": "
          << readback_status.message();
      LOG(INFO) << "Read-back check " << num_readback_checks
                << " passed (" << entries.size() << " entries).";
    }
  }

  // Final read-back check.
  num_readback_checks++;
  ASSERT_OK_AND_ASSIGN(
      auto final_entries, p4_runtime::ReadPiTableEntriesSorted(*session));
  ASSERT_OK(switch_state.AssertEntriesAreEqualToState(final_entries))
      << "Final read-back mismatch.";

  LOG(INFO) << "Fuzzer complete: " << kFuzzerIterations << " iterations, "
            << num_updates << " updates, " << num_oracle_failures
            << " oracle failures, " << num_readback_checks
            << " read-back checks passed.";
  EXPECT_EQ(num_oracle_failures, 0);
}

}  // namespace
}  // namespace fourward
