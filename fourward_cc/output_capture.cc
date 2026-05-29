// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/output_capture.h"

#include <unistd.h>

#include <memory>
#include <string>
#include <thread>  // NOLINT(build/c++11)
#include <utility>

#include "absl/base/thread_annotations.h"
#include "absl/synchronization/mutex.h"

namespace fourward {

class OutputCapture::Impl {
 public:
  Impl(int pipe_read_fd, int tee_fd)
      : pipe_read_fd_(pipe_read_fd), tee_fd_(tee_fd) {}

  void StartThread() {
    thread_ = std::thread(&Impl::ReadLoop, this);
  }

  ~Impl() {
    if (thread_.joinable()) thread_.join();
  }

  std::string CapturedOutput() const {
    absl::MutexLock lock(mu_);
    return buffer_;
  }

 private:
  void ReadLoop() {
    char buf[4096];
    while (true) {
      ssize_t n = ::read(pipe_read_fd_, buf, sizeof(buf));
      if (n <= 0) break;  // EOF or error.
      if (tee_fd_ >= 0) {
        // Best-effort tee — partial writes are acceptable for diagnostic
        // output; we don't retry on EINTR because the tee target (typically
        // the parent's stdout/stderr) is not critical.
        (void)::write(tee_fd_, buf, n);
      }
      absl::MutexLock lock(mu_);
      buffer_.append(buf, n);
    }
    ::close(pipe_read_fd_);
  }

  const int pipe_read_fd_;
  const int tee_fd_;
  std::thread thread_;
  mutable absl::Mutex mu_;
  std::string buffer_ ABSL_GUARDED_BY(mu_);
};

std::unique_ptr<OutputCapture> OutputCapture::Start(int pipe_read_fd,
                                                    int tee_fd) {
  auto impl = std::make_unique<Impl>(pipe_read_fd, tee_fd);
  impl->StartThread();
  return std::unique_ptr<OutputCapture>(
      new OutputCapture(std::move(impl)));
}

OutputCapture::OutputCapture(std::unique_ptr<Impl> impl)
    : impl_(std::move(impl)) {}

OutputCapture::~OutputCapture() = default;

std::string OutputCapture::CapturedOutput() const {
  return impl_->CapturedOutput();
}

}  // namespace fourward
