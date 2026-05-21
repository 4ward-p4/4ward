// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/dataplane_client.h"

#include <chrono>
#include <deque>
#include <memory>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <variant>

#include "absl/base/thread_annotations.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/synchronization/mutex.h"
#include "absl/time/time.h"
#include "absl/types/span.h"
#include "grpcpp/client_context.h"
#include "grpcpp/support/status.h"
#include "grpcpp/support/sync_stream.h"
#include "grpc/dataplane.grpc.pb.h"
#include "grpc/dataplane.pb.h"
#include "fourward_cc/fourward_server.h"

namespace fourward {
namespace {

absl::Status ToAbsl(const grpc::Status& s) {
  if (s.ok()) return absl::OkStatus();
  return absl::Status(static_cast<absl::StatusCode>(s.error_code()),
                      s.error_message());
}

std::chrono::system_clock::time_point AbsoluteDeadline(
    absl::Duration relative) {
  // time_point_cast: macOS system_clock uses microseconds, not nanoseconds.
  return std::chrono::time_point_cast<std::chrono::system_clock::duration>(
      std::chrono::system_clock::now() + absl::ToChronoNanoseconds(relative));
}

template <class... Ts>
struct overloaded : Ts... { using Ts::operator()...; };

fourward::InjectPacketRequest ToProto(
    const InjectPacketArgs& args) {
  fourward::InjectPacketRequest req;
  std::visit(overloaded{
                 [&](const DataplanePort& p) {
                   req.set_dataplane_ingress_port(p.port);
                 },
                 [&](const P4RuntimePort& p) {
                   req.set_p4rt_ingress_port(p.port);
                 },
             },
             args.ingress_port);
  req.set_payload(args.payload);
  return req;
}

}  // namespace

DataplaneClient::DataplaneClient(const FourwardServer& server,
                                 absl::Duration default_timeout)
    : DataplaneClient(server.NewDataplaneStub(), default_timeout) {}

DataplaneClient::DataplaneClient(
    std::unique_ptr<fourward::Dataplane::Stub> stub,
    absl::Duration default_timeout)
    : stub_(std::move(stub)), default_timeout_(default_timeout) {}

DataplaneClient::~DataplaneClient() = default;
DataplaneClient::DataplaneClient(DataplaneClient&&) = default;
DataplaneClient& DataplaneClient::operator=(DataplaneClient&&) =
    default;

absl::StatusOr<fourward::InjectPacketResponse>
DataplaneClient::InjectPacket(const InjectPacketArgs& args,
                              std::optional<absl::Duration> timeout) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(ResolveTimeout(timeout)));
  fourward::InjectPacketRequest req = ToProto(args);
  fourward::InjectPacketResponse resp;
  grpc::Status status = stub_->InjectPacket(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);
  return resp;
}

absl::StatusOr<fourward::Reproducer>
DataplaneClient::ReproduceTrace(const InjectPacketArgs& args,
                                std::optional<absl::Duration> timeout) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(ResolveTimeout(timeout)));
  fourward::InjectPacketRequest req = ToProto(args);
  fourward::Reproducer resp;
  grpc::Status status = stub_->ReproduceTrace(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);
  return resp;
}

absl::Status DataplaneClient::InjectPackets(
    absl::Span<const InjectPacketArgs> args,
    std::optional<absl::Duration> timeout) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(ResolveTimeout(timeout)));
  fourward::InjectPacketsResponse resp;
  std::unique_ptr<
      grpc::ClientWriter<fourward::InjectPacketRequest>>
      writer = stub_->InjectPackets(&ctx, &resp);
  for (const InjectPacketArgs& arg : args) {
    fourward::InjectPacketRequest req = ToProto(arg);
    if (!writer->Write(req)) {
      return ToAbsl(writer->Finish());
    }
  }
  writer->WritesDone();
  return ToAbsl(writer->Finish());
}

class ResultStream::Impl {
 public:
  static absl::StatusOr<std::unique_ptr<Impl>> Create(
      fourward::Dataplane::Stub* stub,
      absl::Duration startup_timeout);

  ~Impl();

  Impl(const Impl&) = delete;
  Impl& operator=(const Impl&) = delete;

  absl::StatusOr<fourward::ProcessPacketResult> Next(
      absl::Duration timeout);

 private:
  Impl() = default;
  void ReadLoop();

  enum class State { kRunning, kFinished };

  bool StartupSettled() const ABSL_EXCLUSIVE_LOCKS_REQUIRED(mu_) {
    return sentinel_received_ || state_ == State::kFinished;
  }
  bool QueueOrFinished() const ABSL_EXCLUSIVE_LOCKS_REQUIRED(mu_) {
    return !queue_.empty() || state_ == State::kFinished;
  }

  std::unique_ptr<grpc::ClientContext> context_;
  std::unique_ptr<
      grpc::ClientReader<fourward::SubscribeResultsResponse>>
      reader_;
  std::thread thread_;

  absl::Mutex mu_;
  State state_ ABSL_GUARDED_BY(mu_) = State::kRunning;
  bool sentinel_received_ ABSL_GUARDED_BY(mu_) = false;
  std::deque<fourward::ProcessPacketResult> queue_
      ABSL_GUARDED_BY(mu_);
  absl::Status final_status_ ABSL_GUARDED_BY(mu_) =
      absl::InternalError("stream not yet finished");
};

absl::StatusOr<std::unique_ptr<ResultStream::Impl>> ResultStream::Impl::Create(
    fourward::Dataplane::Stub* stub,
    absl::Duration startup_timeout) {
  std::unique_ptr<Impl> impl(new Impl);
  impl->context_ = std::make_unique<grpc::ClientContext>();
  fourward::SubscribeResultsRequest req;
  impl->reader_ = stub->SubscribeResults(impl->context_.get(), req);

  Impl* raw = impl.get();
  impl->thread_ = std::thread([raw] { raw->ReadLoop(); });

  bool timed_out;
  {
    absl::MutexLock lock(&impl->mu_);
    timed_out = !impl->mu_.AwaitWithTimeout(
        absl::Condition(impl.get(), &Impl::StartupSettled),
        startup_timeout);
    if (timed_out) impl->context_->TryCancel();
    // Sentinel received — the stream is (or was) live. Return it even if the
    // reader thread has already finished; Next() will drain any queued results
    // and then report the terminal status.
    if (impl->sentinel_received_) return impl;
  }

  impl->thread_.join();
  if (timed_out) {
    return absl::DeadlineExceededError(absl::StrCat(
        "SubscribeResults: server did not send SubscriptionActive within ",
        absl::FormatDuration(startup_timeout)));
  }
  absl::MutexLock lock(&impl->mu_);
  return impl->final_status_;
}

ResultStream::Impl::~Impl() {
  if (context_ != nullptr) context_->TryCancel();
  if (thread_.joinable()) thread_.join();
}

void ResultStream::Impl::ReadLoop() {
  fourward::SubscribeResultsResponse first;
  if (!reader_->Read(&first) || !first.has_active()) {
    grpc::Status status = reader_->Finish();
    absl::MutexLock lock(&mu_);
    state_ = State::kFinished;
    // Distinguish protocol violation (clean close without sentinel) from
    // actual RPC errors.
    if (status.ok()) {
      final_status_ = absl::InternalError(
          "SubscribeResults: stream closed before SubscriptionActive");
    } else {
      final_status_ = ToAbsl(status);
    }
    return;
  }
  {
    absl::MutexLock lock(&mu_);
    sentinel_received_ = true;
  }

  fourward::SubscribeResultsResponse resp;
  while (reader_->Read(&resp)) {
    if (!resp.has_result()) continue;
    absl::MutexLock lock(&mu_);
    queue_.push_back(std::move(*resp.mutable_result()));
  }
  grpc::Status status = reader_->Finish();
  absl::MutexLock lock(&mu_);
  state_ = State::kFinished;
  final_status_ = ToAbsl(status);
}

absl::StatusOr<fourward::ProcessPacketResult>
ResultStream::Impl::Next(absl::Duration timeout) {
  absl::MutexLock lock(&mu_);
  if (!mu_.AwaitWithTimeout(absl::Condition(this, &Impl::QueueOrFinished),
                            timeout)) {
    return absl::DeadlineExceededError(absl::StrCat(
        "ResultStream::Next: no result within ", absl::FormatDuration(timeout)));
  }
  if (!queue_.empty()) {
    fourward::ProcessPacketResult result = std::move(queue_.front());
    queue_.pop_front();
    return result;
  }
  if (final_status_.ok()) {
    return absl::CancelledError(
        "ResultStream::Next: server closed the stream");
  }
  return final_status_;
}

ResultStream::~ResultStream() = default;
ResultStream::ResultStream(ResultStream&&) = default;
ResultStream& ResultStream::operator=(ResultStream&&) = default;
ResultStream::ResultStream(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}

absl::StatusOr<fourward::ProcessPacketResult> ResultStream::Next(
    absl::Duration timeout) {
  return impl_->Next(timeout);
}

absl::StatusOr<ResultStream> DataplaneClient::SubscribeResults(
    std::optional<absl::Duration> startup_timeout) {
  absl::StatusOr<std::unique_ptr<ResultStream::Impl>> impl =
      ResultStream::Impl::Create(stub_.get(), ResolveTimeout(startup_timeout));
  if (!impl.ok()) return impl.status();
  return ResultStream(*std::move(impl));
}

}  // namespace fourward
