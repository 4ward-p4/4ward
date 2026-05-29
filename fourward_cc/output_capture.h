// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_CC_OUTPUT_CAPTURE_H_
#define FOURWARD_CC_OUTPUT_CAPTURE_H_

#include <memory>
#include <string>

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
  class Impl;
  std::unique_ptr<Impl> impl_;
  explicit OutputCapture(std::unique_ptr<Impl> impl);
};

}  // namespace fourward

#endif  // FOURWARD_CC_OUTPUT_CAPTURE_H_
