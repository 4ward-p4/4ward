// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/output_capture.h"

#include <unistd.h>

#include <algorithm>
#include <memory>
#include <string>

#include "absl/strings/str_cat.h"
#include "absl/time/clock.h"
#include "absl/time/time.h"
#include "gtest/gtest.h"

namespace fourward {
namespace {

TEST(OutputCaptureTest, CapturesWrittenData) {
  int fds[2];
  ASSERT_EQ(::pipe(fds), 0);
  auto capture = OutputCapture::Start(fds[0], /*tee_fd=*/-1);

  const std::string data = "hello, world\n";
  ASSERT_EQ(::write(fds[1], data.data(), data.size()),
            static_cast<ssize_t>(data.size()));
  ::close(fds[1]);

  // The reader thread exits once the write-end is closed. Give it a moment.
  absl::SleepFor(absl::Milliseconds(50));
  EXPECT_EQ(capture->CapturedOutput(), data);
}

TEST(OutputCaptureTest, TeesDataToTargetFd) {
  int capture_pipe[2];
  ASSERT_EQ(::pipe(capture_pipe), 0);

  int tee_pipe[2];
  ASSERT_EQ(::pipe(tee_pipe), 0);

  auto capture =
      OutputCapture::Start(capture_pipe[0], /*tee_fd=*/tee_pipe[1]);

  const std::string data = "tee test\n";
  ASSERT_EQ(::write(capture_pipe[1], data.data(), data.size()),
            static_cast<ssize_t>(data.size()));
  ::close(capture_pipe[1]);

  absl::SleepFor(absl::Milliseconds(50));
  EXPECT_EQ(capture->CapturedOutput(), data);

  // Read from the tee target and verify the same data arrived.
  ::close(tee_pipe[1]);  // Close write-end so read sees EOF after data.
  // OutputCapture does not own the tee fd, but we need to close our copy
  // of the write-end to unblock the read below.
  char buf[64];
  ssize_t n = ::read(tee_pipe[0], buf, sizeof(buf));
  ASSERT_GT(n, 0);
  EXPECT_EQ(std::string(buf, n), data);
  ::close(tee_pipe[0]);
}

TEST(OutputCaptureTest, TeePrefixPrependedToEachLine) {
  int capture_pipe[2];
  ASSERT_EQ(::pipe(capture_pipe), 0);

  int tee_pipe[2];
  ASSERT_EQ(::pipe(tee_pipe), 0);

  auto capture = OutputCapture::Start(capture_pipe[0], /*tee_fd=*/tee_pipe[1],
                                      "[srv] ");

  const std::string data = "line one\nline two\n";
  ASSERT_EQ(::write(capture_pipe[1], data.data(), data.size()),
            static_cast<ssize_t>(data.size()));
  ::close(capture_pipe[1]);

  absl::SleepFor(absl::Milliseconds(50));

  // Captured buffer is raw — no prefix.
  EXPECT_EQ(capture->CapturedOutput(), data);

  // Tee target has the prefix on each line.
  ::close(tee_pipe[1]);
  char buf[256];
  ssize_t n = ::read(tee_pipe[0], buf, sizeof(buf));
  ASSERT_GT(n, 0);
  EXPECT_EQ(std::string(buf, n), "[srv] line one\n[srv] line two\n");
  ::close(tee_pipe[0]);
}

TEST(OutputCaptureTest, EmptyPipeReturnsEmptyString) {
  int fds[2];
  ASSERT_EQ(::pipe(fds), 0);
  auto capture = OutputCapture::Start(fds[0], /*tee_fd=*/-1);
  ::close(fds[1]);

  absl::SleepFor(absl::Milliseconds(50));
  EXPECT_TRUE(capture->CapturedOutput().empty());
}

TEST(OutputCaptureTest, IncrementalCaptureWhileWriteEndOpen) {
  int fds[2];
  ASSERT_EQ(::pipe(fds), 0);
  auto capture = OutputCapture::Start(fds[0], /*tee_fd=*/-1);

  // First chunk: write and verify CapturedOutput() returns it while the
  // write-end is still open (data still flowing).
  const std::string chunk1 = "chunk one\n";
  ASSERT_EQ(::write(fds[1], chunk1.data(), chunk1.size()),
            static_cast<ssize_t>(chunk1.size()));
  absl::SleepFor(absl::Milliseconds(50));
  EXPECT_EQ(capture->CapturedOutput(), chunk1);

  // Second chunk: verify CapturedOutput() now returns both chunks.
  const std::string chunk2 = "chunk two\n";
  ASSERT_EQ(::write(fds[1], chunk2.data(), chunk2.size()),
            static_cast<ssize_t>(chunk2.size()));
  absl::SleepFor(absl::Milliseconds(50));
  EXPECT_EQ(capture->CapturedOutput(), absl::StrCat(chunk1, chunk2));

  // Close the write-end and verify the final output matches.
  ::close(fds[1]);
  absl::SleepFor(absl::Milliseconds(50));
  EXPECT_EQ(capture->CapturedOutput(), absl::StrCat(chunk1, chunk2));
}

TEST(OutputCaptureTest, LargeDataCapturedCorrectly) {
  int fds[2];
  ASSERT_EQ(::pipe(fds), 0);
  auto capture = OutputCapture::Start(fds[0], /*tee_fd=*/-1);

  // 1 MB of data, written in chunks to avoid filling the pipe buffer and
  // blocking the writer (the reader thread drains concurrently).
  constexpr int kTotalBytes = 1 << 20;
  constexpr int kChunkSize = 4096;
  std::string chunk(kChunkSize, 'x');
  int written = 0;
  while (written < kTotalBytes) {
    int to_write =
        std::min(kChunkSize, kTotalBytes - written);
    ssize_t n = ::write(fds[1], chunk.data(), to_write);
    ASSERT_GT(n, 0) << "write failed at offset " << written;
    written += n;
  }
  ::close(fds[1]);

  absl::SleepFor(absl::Milliseconds(100));
  std::string captured = capture->CapturedOutput();
  EXPECT_EQ(static_cast<int>(captured.size()), kTotalBytes);
  EXPECT_EQ(captured, std::string(kTotalBytes, 'x'));
}

}  // namespace
}  // namespace fourward
