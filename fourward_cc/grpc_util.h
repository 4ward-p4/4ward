// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_CC_GRPC_UTIL_H_
#define FOURWARD_CC_GRPC_UTIL_H_

#include <chrono>

#include "absl/status/status.h"
#include "absl/time/clock.h"
#include "absl/time/time.h"
#include "grpcpp/support/status.h"

namespace fourward {

inline absl::Status ToAbsl(const grpc::Status& s) {
  if (s.ok()) return absl::OkStatus();
  return absl::Status(static_cast<absl::StatusCode>(s.error_code()),
                      s.error_message());
}

inline std::chrono::system_clock::time_point AbsoluteDeadline(
    absl::Duration relative) {
  return absl::ToChronoTime(absl::Now() + relative);
}

}  // namespace fourward

#endif  // FOURWARD_CC_GRPC_UTIL_H_
