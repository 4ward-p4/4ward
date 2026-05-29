// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/output_capture.h"

#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <memory>
#include <string>
#include <utility>

namespace fourward {

std::unique_ptr<OutputCapture> OutputCapture::Start(int pipe_read_fd,
                                                    int tee_fd,
                                                    std::string tee_prefix) {
  auto capture = std::unique_ptr<OutputCapture>(
      new OutputCapture(pipe_read_fd, tee_fd, std::move(tee_prefix)));
  capture->thread_ = std::thread(&OutputCapture::ReadLoop, capture.get());
  return capture;
}

OutputCapture::OutputCapture(int pipe_read_fd, int tee_fd,
                             std::string tee_prefix)
    : pipe_read_fd_(pipe_read_fd),
      tee_fd_(tee_fd),
      tee_prefix_(std::move(tee_prefix)) {}

OutputCapture::~OutputCapture() {
  if (thread_.joinable()) thread_.join();
}

std::string OutputCapture::CapturedOutput() const {
  absl::MutexLock lock(mu_);
  return buffer_;
}

// Best-effort write — partial writes are acceptable for diagnostic output.
static void WriteAll(int fd, const char* data, size_t len) {
  (void)::write(fd, data, len);
}

void OutputCapture::ReadLoop() {
  char buf[4096];
  while (true) {
    ssize_t n = ::read(pipe_read_fd_, buf, sizeof(buf));
    if (n > 0) {
      if (tee_fd_ >= 0) {
        if (tee_prefix_.empty()) {
          WriteAll(tee_fd_, buf, n);
        } else {
          // Prepend the prefix at the start of each line so tee'd output is
          // clearly attributed. Scan for newlines and insert the prefix after
          // each one (and at the very start if we're at a line boundary).
          const char* p = buf;
          const char* end = buf + n;
          while (p < end) {
            if (tee_at_line_start_) {
              WriteAll(tee_fd_, tee_prefix_.data(), tee_prefix_.size());
              tee_at_line_start_ = false;
            }
            const char* nl = static_cast<const char*>(
                std::memchr(p, '\n', end - p));
            if (nl != nullptr) {
              WriteAll(tee_fd_, p, nl - p + 1);
              tee_at_line_start_ = true;
              p = nl + 1;
            } else {
              WriteAll(tee_fd_, p, end - p);
              p = end;
            }
          }
        }
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
