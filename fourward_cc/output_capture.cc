// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/output_capture.h"

#include <unistd.h>

#include <cerrno>
#include <memory>
#include <string>

namespace fourward {

std::unique_ptr<OutputCapture> OutputCapture::Start(int pipe_read_fd,
                                                    int tee_fd) {
  auto capture = std::unique_ptr<OutputCapture>(
      new OutputCapture(pipe_read_fd, tee_fd));
  capture->thread_ = std::thread(&OutputCapture::ReadLoop, capture.get());
  return capture;
}

OutputCapture::OutputCapture(int pipe_read_fd, int tee_fd)
    : pipe_read_fd_(pipe_read_fd), tee_fd_(tee_fd) {}

OutputCapture::~OutputCapture() {
  if (thread_.joinable()) thread_.join();
}

std::string OutputCapture::CapturedOutput() const {
  absl::MutexLock lock(mu_);
  return buffer_;
}

void OutputCapture::ReadLoop() {
  char buf[4096];
  while (true) {
    ssize_t n = ::read(pipe_read_fd_, buf, sizeof(buf));
    if (n > 0) {
      if (tee_fd_ >= 0) {
        // Best-effort tee — partial writes are acceptable for diagnostic
        // output; we don't retry on EINTR because the tee target (typically
        // the parent's stdout/stderr) is not critical.
        (void)::write(tee_fd_, buf, n);
      }
      absl::MutexLock lock(mu_);
      buffer_.append(buf, n);
    } else if (n == 0) {
      break;  // EOF.
    } else if (errno != EINTR) {
      break;  // Real error.
    }
    // EINTR: a signal interrupted the read — retry.
  }
  ::close(pipe_read_fd_);
}

}  // namespace fourward
