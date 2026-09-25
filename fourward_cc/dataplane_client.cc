// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/dataplane_client.h"

#include <deque>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <utility>

#include "absl/base/thread_annotations.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/synchronization/mutex.h"
#include "absl/time/time.h"
#include "fourward_cc/fourward_server.h"
#include "fourward_cc/grpc_util.h"
#include "grpc/dataplane.grpc.pb.h"
#include "grpc/dataplane.pb.h"
#include "grpcpp/client_context.h"
#include "grpcpp/support/status.h"
#include "grpcpp/support/sync_stream.h"

namespace fourward {
namespace {

InjectPacketRequest MakeRequest(uint64_t device_id, DataplanePort ingress_port,
                                std::string_view payload, Tag tag) {
  InjectPacketRequest req;
  req.set_device_id(device_id);
  req.set_dataplane_ingress_port(ingress_port.port);
  req.set_payload(payload.data(), payload.size());
  req.set_tag(tag.value);
  return req;
}

InjectPacketRequest MakeRequest(uint64_t device_id, P4RuntimePort ingress_port,
                                std::string_view payload, Tag tag) {
  InjectPacketRequest req;
  req.set_device_id(device_id);
  req.set_p4rt_ingress_port(std::move(ingress_port.port));
  req.set_payload(payload.data(), payload.size());
  req.set_tag(tag.value);
  return req;
}

}  // namespace

// -- DataplaneClient ----------------------------------------------------------

DataplaneClient::DataplaneClient(const FourwardServer &server,
                                 absl::Duration default_timeout)
    : DataplaneClient(server.NewDataplaneStub(), server.DeviceId(),
                      default_timeout) {}

DataplaneClient::DataplaneClient(const FourwardServer &server,
                                 uint64_t device_id,
                                 absl::Duration default_timeout)
    : DataplaneClient(server.NewDataplaneStub(), device_id, default_timeout) {}

DataplaneClient::DataplaneClient(std::unique_ptr<Dataplane::Stub> stub,
                                 absl::Duration default_timeout)
    : DataplaneClient(std::move(stub), 0, default_timeout) {}

DataplaneClient::DataplaneClient(std::unique_ptr<Dataplane::Stub> stub,
                                 uint64_t device_id,
                                 absl::Duration default_timeout)
    : stub_(std::move(stub)),
      device_id_(device_id),
      default_timeout_(default_timeout) {}

DataplaneClient::~DataplaneClient() = default;
DataplaneClient::DataplaneClient(DataplaneClient &&) = default;
DataplaneClient &DataplaneClient::operator=(DataplaneClient &&) = default;

absl::StatusOr<InjectPacketResponse> DataplaneClient::InjectPacket(
    DataplanePort ingress_port, std::string_view payload, Tag tag) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  InjectPacketRequest req = MakeRequest(device_id_, ingress_port, payload, tag);
  InjectPacketResponse resp;
  grpc::Status status = stub_->InjectPacket(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);
  return resp;
}

absl::StatusOr<InjectPacketResponse> DataplaneClient::InjectPacket(
    P4RuntimePort ingress_port, std::string_view payload, Tag tag) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  InjectPacketRequest req =
      MakeRequest(device_id_, std::move(ingress_port), payload, tag);
  InjectPacketResponse resp;
  grpc::Status status = stub_->InjectPacket(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);
  return resp;
}

absl::StatusOr<Reproducer> DataplaneClient::GetReproducer(
    DataplanePort ingress_port, std::string_view payload, Tag tag) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  InjectPacketRequest req = MakeRequest(device_id_, ingress_port, payload, tag);
  Reproducer resp;
  grpc::Status status = stub_->GetReproducer(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);
  return resp;
}

absl::StatusOr<Reproducer> DataplaneClient::GetReproducer(
    P4RuntimePort ingress_port, std::string_view payload, Tag tag) {
  grpc::ClientContext ctx;
  ctx.set_deadline(AbsoluteDeadline(default_timeout_));
  InjectPacketRequest req =
      MakeRequest(device_id_, std::move(ingress_port), payload, tag);
  Reproducer resp;
  grpc::Status status = stub_->GetReproducer(&ctx, req, &resp);
  if (!status.ok()) return ToAbsl(status);
  return resp;
}

// -- PacketWriter -------------------------------------------------------------

class PacketWriter::Impl {
 public:
  static std::unique_ptr<Impl> Create(
      Dataplane::Stub *stub, uint64_t device_id,
      std::unique_ptr<grpc::ClientContext> context) {
    std::unique_ptr<Impl> impl(new Impl);
    impl->device_id_ = device_id;
    impl->context_ = std::move(context);
    impl->writer_ = stub->InjectPackets(impl->context_.get(), &impl->response_);
    return impl;
  }

  absl::Status Inject(DataplanePort ingress_port, std::string_view payload,
                      Tag tag) {
    return Inject(MakeRequest(device_id_, ingress_port, payload, tag));
  }

  absl::Status Inject(P4RuntimePort ingress_port, std::string_view payload,
                      Tag tag) {
    return Inject(
        MakeRequest(device_id_, std::move(ingress_port), payload, tag));
  }

  absl::Status Inject(InjectPacketRequest req) {
    if (finished_) {
      return finished_status_.ok()
                 ? absl::FailedPreconditionError("PacketWriter is finished")
                 : finished_status_;
    }
    if (!writer_->Write(req)) return DoFinish();
    ++count_;
    return absl::OkStatus();
  }

  absl::StatusOr<int> Finish() {
    if (finished_) {
      if (!finished_status_.ok()) return finished_status_;
      return count_;
    }
    writer_->WritesDone();
    absl::Status status = DoFinish();
    if (!status.ok()) return status;
    return count_;
  }

 private:
  Impl() = default;

  absl::Status DoFinish() {
    finished_ = true;
    finished_status_ = ToAbsl(writer_->Finish());
    return finished_status_;
  }

  std::unique_ptr<grpc::ClientContext> context_;
  InjectPacketsResponse response_;
  std::unique_ptr<grpc::ClientWriter<InjectPacketRequest>> writer_;
  uint64_t device_id_ = 0;
  bool finished_ = false;
  int count_ = 0;
  absl::Status finished_status_;
};

PacketWriter DataplaneClient::InjectPackets(
    std::optional<absl::Duration> total_timeout) {
  // No deadline unless the caller opted in — see the header for the
  // rationale (#760).
  auto context = std::make_unique<grpc::ClientContext>();
  if (total_timeout.has_value()) {
    context->set_deadline(AbsoluteDeadline(*total_timeout));
  }
  return PacketWriter(PacketWriter::Impl::Create(stub_.get(), device_id_,
                                                 std::move(context)));
}

PacketWriter::~PacketWriter() {
  if (impl_ != nullptr) (void)impl_->Finish();
}
PacketWriter::PacketWriter(PacketWriter &&) = default;
PacketWriter &PacketWriter::operator=(PacketWriter &&) = default;
PacketWriter::PacketWriter(std::unique_ptr<Impl> impl)
    : impl_(std::move(impl)) {}

absl::Status PacketWriter::Inject(DataplanePort ingress_port,
                                  std::string_view payload, Tag tag) {
  return impl_->Inject(ingress_port, payload, tag);
}

absl::Status PacketWriter::Inject(P4RuntimePort ingress_port,
                                  std::string_view payload, Tag tag) {
  return impl_->Inject(std::move(ingress_port), payload, tag);
}

absl::StatusOr<int> PacketWriter::Finish() { return impl_->Finish(); }

// -- ResultStream -------------------------------------------------------------

class ResultStream::Impl {
 public:
  static absl::StatusOr<std::unique_ptr<Impl>> Create(
      Dataplane::Stub *stub, uint64_t device_id,
      absl::Duration startup_timeout);

  ~Impl();

  Impl(const Impl &) = delete;
  Impl &operator=(const Impl &) = delete;

  absl::StatusOr<ProcessPacketResult> Next(absl::Duration timeout);

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
  std::unique_ptr<grpc::ClientReader<SubscribeResultsResponse>> reader_;
  std::thread thread_;

  absl::Mutex mu_;
  State state_ ABSL_GUARDED_BY(mu_) = State::kRunning;
  bool sentinel_received_ ABSL_GUARDED_BY(mu_) = false;
  std::deque<ProcessPacketResult> queue_ ABSL_GUARDED_BY(mu_);
  absl::Status final_status_ ABSL_GUARDED_BY(mu_) =
      absl::InternalError("stream not yet finished");
};

absl::StatusOr<std::unique_ptr<ResultStream::Impl>> ResultStream::Impl::Create(
    Dataplane::Stub *stub, uint64_t device_id,
    absl::Duration startup_timeout) {
  std::unique_ptr<Impl> impl(new Impl);
  impl->context_ = std::make_unique<grpc::ClientContext>();
  SubscribeResultsRequest req;
  req.set_device_id(device_id);
  impl->reader_ = stub->SubscribeResults(impl->context_.get(), req);

  Impl *raw = impl.get();
  impl->thread_ = std::thread([raw] { raw->ReadLoop(); });

  bool timed_out;
  {
    absl::MutexLock lock(impl->mu_);
    timed_out = !impl->mu_.AwaitWithTimeout(
        absl::Condition(impl.get(), &Impl::StartupSettled), startup_timeout);
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
  absl::MutexLock lock(impl->mu_);
  return impl->final_status_;
}

ResultStream::Impl::~Impl() {
  if (context_ != nullptr) context_->TryCancel();
  if (thread_.joinable()) thread_.join();
}

void ResultStream::Impl::ReadLoop() {
  SubscribeResultsResponse first;
  if (!reader_->Read(&first) || !first.has_active()) {
    grpc::Status status = reader_->Finish();
    absl::MutexLock lock(mu_);
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
    absl::MutexLock lock(mu_);
    sentinel_received_ = true;
  }

  SubscribeResultsResponse resp;
  while (reader_->Read(&resp)) {
    if (!resp.has_result()) continue;
    absl::MutexLock lock(mu_);
    queue_.push_back(std::move(*resp.mutable_result()));
  }
  grpc::Status status = reader_->Finish();
  absl::MutexLock lock(mu_);
  state_ = State::kFinished;
  final_status_ = ToAbsl(status);
}

absl::StatusOr<ProcessPacketResult> ResultStream::Impl::Next(
    absl::Duration timeout) {
  absl::MutexLock lock(mu_);
  if (!mu_.AwaitWithTimeout(absl::Condition(this, &Impl::QueueOrFinished),
                            timeout)) {
    return absl::DeadlineExceededError(
        absl::StrCat("ResultStream::Next: no result within ",
                     absl::FormatDuration(timeout)));
  }
  if (!queue_.empty()) {
    ProcessPacketResult result = std::move(queue_.front());
    queue_.pop_front();
    return result;
  }
  if (final_status_.ok()) {
    return absl::CancelledError("ResultStream::Next: server closed the stream");
  }
  return final_status_;
}

ResultStream::~ResultStream() = default;
ResultStream::ResultStream(ResultStream &&) = default;
ResultStream &ResultStream::operator=(ResultStream &&) = default;
ResultStream::ResultStream(std::unique_ptr<Impl> impl)
    : impl_(std::move(impl)) {}

absl::StatusOr<ProcessPacketResult> ResultStream::Next(absl::Duration timeout) {
  return impl_->Next(timeout);
}

absl::StatusOr<ResultStream> DataplaneClient::SubscribeResults(
    std::optional<absl::Duration> startup_timeout) {
  absl::StatusOr<std::unique_ptr<ResultStream::Impl>> impl =
      ResultStream::Impl::Create(stub_.get(), device_id_,
                                 startup_timeout.value_or(default_timeout_));
  if (!impl.ok()) return impl.status();
  return ResultStream(*std::move(impl));
}

}  // namespace fourward
