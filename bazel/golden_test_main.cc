// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "bazel/golden_test.h"

#include "absl/flags/parse.h"
#include "gtest/gtest.h"

int main(int argc, char** argv) {
  testing::InitGoogleTest(&argc, argv);
  fourward::bazel::internal::InitializeGoldenTestMain();
  absl::ParseCommandLine(argc, argv);
  return RUN_ALL_TESTS();
}
