// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_CC_OUTPUT_CAPTURE_H_
#define FOURWARD_CC_OUTPUT_CAPTURE_H_

#include <memory>
#include <string>
#include <thread>  // NOLINT(build/c++11)

#include "absl/base/thread_annotations.h"
#include "absl/synchronization/mutex.h"

namespace fourward {

// Reads from a pipe fd in a background thread, appending to an in-memory
// buffer and optionally teeing to a target fd. Not movable or copyable —
// own via unique_ptr.
class OutputCapture {
 public:
  // Creates an OutputCapture that reads from `pipe_read_fd` (takes ownership,
  // closes on destruction) and optionally tees to `tee_fd` (-1 to suppress).
  // Starts the reader thread immediately.
  static std::unique_ptr<OutputCapture> Start(int pipe_read_fd, int tee_fd);

  ~OutputCapture();

  OutputCapture(const OutputCapture&) = delete;
  OutputCapture& operator=(const OutputCapture&) = delete;
  OutputCapture(OutputCapture&&) = delete;
  OutputCapture& operator=(OutputCapture&&) = delete;

  // Returns a snapshot of captured output so far. Thread-safe.
  std::string CapturedOutput() const;

 private:
  OutputCapture(int pipe_read_fd, int tee_fd);

  void ReadLoop();

  const int pipe_read_fd_;
  const int tee_fd_;
  std::thread thread_;
  mutable absl::Mutex mu_;
  std::string buffer_ ABSL_GUARDED_BY(mu_);
};

}  // namespace fourward

#endif  // FOURWARD_CC_OUTPUT_CAPTURE_H_
