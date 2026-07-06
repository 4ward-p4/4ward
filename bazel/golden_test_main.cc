// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "bazel/golden_test.h"

#include "absl/flags/parse.h"
#include "gtest/gtest.h"

int main(int argc, char** argv) {
  // Must run before ParseCommandLine: it sets the --update_golden default that a
  // command-line --update_golden=... then overrides.
  fourward::bazel::internal::InitializeGoldenTestMain();
  testing::InitGoogleTest(&argc, argv);
  absl::ParseCommandLine(argc, argv);
  return RUN_ALL_TESTS();
}
