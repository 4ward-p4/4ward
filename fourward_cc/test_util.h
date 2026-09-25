// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_CC_TEST_UTIL_H_
#define FOURWARD_CC_TEST_UTIL_H_

#include <cstdint>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "fourward_cc/fourward_server.h"
#include "fourward_cc/grpc_util.h"
#include "grpcpp/client_context.h"
#include "p4/v1/p4runtime.grpc.pb.h"
#include "p4/v1/p4runtime.pb.h"
#include "rules_cc/cc/runfiles/runfiles.h"

namespace fourward {

using P4RuntimeStream = grpc::ClientReaderWriter<p4::v1::StreamMessageRequest,
                                                 p4::v1::StreamMessageResponse>;

// Reads a test data dependency whose path was injected by BUILD with
// `$(rlocationpath ...)`. Do not pass handwritten `_main/...` or apparent-repo
// paths here; those are not portable across GitHub, BCR consumers, and google3.
inline absl::StatusOr<std::string> ReadRunfileForTest(
    const std::string& rlocation) {
  std::string error;
  std::unique_ptr<rules_cc::cc::runfiles::Runfiles> runfiles(
      rules_cc::cc::runfiles::Runfiles::CreateForTest(&error));
  if (runfiles == nullptr) {
    return absl::InternalError(absl::StrCat("runfiles: ", error));
  }
  std::string path = runfiles->Rlocation(rlocation);
  if (path.empty()) {
    return absl::NotFoundError(
        absl::StrCat("not found in runfiles: ", rlocation));
  }
  std::ifstream file(path, std::ios::binary);
  if (!file) return absl::NotFoundError(absl::StrCat("cannot open: ", path));
  return std::string(std::istreambuf_iterator<char>(file),
                     std::istreambuf_iterator<char>());
}

inline absl::Status EstablishMasterArbitration(uint64_t device_id,
                                               P4RuntimeStream* stream) {
  p4::v1::StreamMessageRequest arbitration;
  arbitration.mutable_arbitration()->set_device_id(device_id);
  arbitration.mutable_arbitration()->mutable_election_id()->set_low(1);
  if (!stream->Write(arbitration)) {
    return absl::InternalError("failed to write arbitration request");
  }
  p4::v1::StreamMessageResponse response;
  if (!stream->Read(&response)) {
    return absl::InternalError("failed to read arbitration response");
  }
  return absl::OkStatus();
}

// Pushes a ForwardingPipelineConfig to the server via the P4Runtime API.
// Handles the arbitration handshake.
inline absl::Status PushPipeline(const FourwardServer& server,
                                 const p4::v1::ForwardingPipelineConfig& config,
                                 uint64_t device_id) {
  auto stub = server.NewP4RuntimeStub();

  grpc::ClientContext stream_ctx;
  auto stream = stub->StreamChannel(&stream_ctx);
  absl::Status arbitration =
      EstablishMasterArbitration(device_id, stream.get());
  if (!arbitration.ok()) return arbitration;

  grpc::ClientContext set_ctx;
  p4::v1::SetForwardingPipelineConfigRequest req;
  req.set_device_id(device_id);
  req.mutable_election_id()->set_low(1);
  req.set_action(p4::v1::SetForwardingPipelineConfigRequest::VERIFY_AND_COMMIT);
  *req.mutable_config() = config;
  p4::v1::SetForwardingPipelineConfigResponse resp;
  grpc::Status status = stub->SetForwardingPipelineConfig(&set_ctx, req, &resp);
  stream_ctx.TryCancel();
  return ToAbsl(status);
}

}  // namespace fourward

#endif  // FOURWARD_CC_TEST_UTIL_H_
