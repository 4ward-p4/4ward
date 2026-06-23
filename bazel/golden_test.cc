// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "bazel/golden_test.h"

#include <cstdlib>
#include <fstream>
#include <iterator>
#include <memory>
#include <optional>
#include <string>

#include "absl/flags/flag.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/string_view.h"
#include "gtest/gtest.h"
#include "tools/cpp/runfiles/runfiles.h"

ABSL_FLAG(std::optional<bool>, update_golden, std::nullopt,
          "Update golden files instead of comparing test output to them. Use "
          "--update_golden=true.");

namespace fourward::bazel {
namespace {

using ::bazel::tools::cpp::runfiles::Runfiles;

std::string ReadRunfileOrDie(absl::string_view rlocation) {
  std::string error;
  std::unique_ptr<Runfiles> runfiles(Runfiles::Create("", &error));
  if (runfiles == nullptr) {
    ADD_FAILURE() << "runfiles: " << error;
    return "";
  }
  std::string path = runfiles->Rlocation(std::string(rlocation));
  if (path.empty()) {
    ADD_FAILURE() << "not found in runfiles: " << rlocation;
    return "";
  }
  std::ifstream file(path, std::ios::binary);
  if (!file) {
    ADD_FAILURE() << "cannot open: " << path;
    return "";
  }
  return std::string(std::istreambuf_iterator<char>(file),
                     std::istreambuf_iterator<char>());
}

std::string SourceGoldenPath(absl::string_view package_dir,
                             absl::string_view golden_file_name) {
  const char* workspace = std::getenv("BUILD_WORKSPACE_DIRECTORY");
  if (workspace == nullptr) {
    ADD_FAILURE()
        << "BUILD_WORKSPACE_DIRECTORY is not set. Run via `bazel run`, not "
           "`bazel test`, to update goldens.";
    return "";
  }
  return absl::StrCat(workspace, "/", package_dir, "/", golden_file_name);
}

std::optional<bool> UpdateGolden() {
  return absl::GetFlag(FLAGS_update_golden);
}

}  // namespace

namespace internal {

void InitializeGoldenTestMain() { absl::SetFlag(&FLAGS_update_golden, false); }

}  // namespace internal

void ExpectMatchesGolden(const GoldenFile& golden, absl::string_view package_dir,
                         absl::string_view update_target,
                         absl::string_view actual) {
  const std::optional<bool> update_golden = UpdateGolden();
  ASSERT_TRUE(update_golden.has_value())
      << "Tests using //bazel:golden_test must depend on "
         "//bazel:golden_test_main instead of @googletest//:gtest_main so "
         "Abseil flags such as --update_golden are parsed.";

  if (*update_golden) {
    const std::string path = SourceGoldenPath(package_dir, golden.name);
    std::ofstream file(path, std::ios::binary);
    ASSERT_TRUE(file) << "cannot write golden: " << path;
    file << actual;
    return;
  }

  EXPECT_EQ(actual, ReadRunfileOrDie(golden.rlocation))
      << "Golden file mismatch: " << golden.name
      << "\nTo update: bazel run " << update_target
      << " -- --update_golden=true";
}

}  // namespace fourward::bazel
