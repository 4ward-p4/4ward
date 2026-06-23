// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "fourward_cc/dataplane_matchers.h"

#include "gtest/gtest.h"

namespace fourward {
namespace {

TEST(DataplaneMatchersFlagTest, GtestMainHonorsMatcherTraceFlag) {
  EXPECT_EQ(internal::GetMatcherTraceMode(), internal::MatcherTraceMode::kFull);
}

}  // namespace
}  // namespace fourward
