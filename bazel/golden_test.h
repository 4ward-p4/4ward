// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef BAZEL_GOLDEN_TEST_H_
#define BAZEL_GOLDEN_TEST_H_

#include <string>

#include "absl/strings/string_view.h"

namespace fourward::bazel {

struct GoldenFile {
  absl::string_view name;
  absl::string_view rlocation;
};

// Compares `actual` to a BUILD-provided runfile. Pass `--update_golden=true`
// to write the actual text back to the source tree:
//
//   bazel run <update_target> -- --update_golden=true
void ExpectMatchesGolden(const GoldenFile& golden,
                         absl::string_view package_dir,
                         absl::string_view update_target,
                         absl::string_view actual);

namespace internal {

// Called by //bazel:golden_test_main before tests run. This sets the default
// value for --update_golden and lets ExpectMatchesGolden detect tests that
// accidentally link @googletest//:gtest_main instead.
void InitializeGoldenTestMain();

}  // namespace internal

}  // namespace fourward::bazel

#endif  // BAZEL_GOLDEN_TEST_H_
