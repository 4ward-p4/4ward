#!/usr/bin/env bash
# Runs all linters:
#   - formatting check (clang-format, buildifier, ktfmt)
#   - clang-tidy on C++ sources (via Bazel aspect)
#   - cpplint on C++ sources (Google style rules clang-tidy doesn't cover)
#   - duplicate-srcs check across kt_jvm_library targets
#   - detekt on Kotlin sources
#
# Runs all checks even if an earlier one fails, so you see all issues at once.
#
# Set SKIP_CLANG_TIDY=1 / SKIP_CPPLINT=1 to skip those steps (CI sets these
# when no C++ files were modified).
#
# Usage:
#   ./tools/lint.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

rc=0

echo "Checking formatting..."
"${REPO_ROOT}/tools/format.sh" --check || rc=1

if [[ "${SKIP_CLANG_TIDY:-}" == "1" ]]; then
  echo "Skipping clang-tidy (SKIP_CLANG_TIDY=1)."
else
  echo "Running clang-tidy..."
  bazel build //p4c_backend/... --config=clang-tidy || rc=1
fi

if [[ "${SKIP_CPPLINT:-}" == "1" ]]; then
  echo "Skipping cpplint (SKIP_CPPLINT=1)."
else
  echo "Running cpplint..."
  CPP_SOURCES=()
  while IFS= read -r f; do CPP_SOURCES+=("$f"); done < <(
    cd "$REPO_ROOT" && git ls-files '*.cpp' '*.h' '*.cc'
  )
  if [[ ${#CPP_SOURCES[@]} -gt 0 ]]; then
    (cd "$REPO_ROOT" && bazel run @pip//cpplint -- "${CPP_SOURCES[@]/#/$REPO_ROOT/}") \
      || rc=1
  fi
fi

# TODO(buf-edition-2024): Re-enable buf lint/breaking once buf supports
# edition 2024. Tracked in https://github.com/smolkaj/4ward/pull/4.

echo "Checking for deprecated Bazel deps..."
# `bazel query` returns targets whose `deprecation` attribute is non-empty
# and that are reachable from anything in //... . We filter out toolchain
# constant targets under @bazel_tools, which legitimately carry deprecation
# messages unrelated to user code.
deprecated=$(bazel query --keep_going \
  'attr("deprecation", ".", deps(//...)) except @bazel_tools//...' 2>/dev/null || true)
if [[ -n "$deprecated" ]]; then
  echo "ERROR: Build targets depend on deprecated Bazel labels. Use the suggested successor:"
  while IFS= read -r target; do
    [[ -z "$target" ]] && continue
    successor=$(bazel query --output=build "$target" 2>/dev/null \
      | sed -n 's/.*deprecation = "\(.*\)".*/  \1/p')
    printf '  %s\n%s\n' "$target" "${successor:-  (no hint)}"
  done <<< "$deprecated"
  rc=1
fi

echo "Checking for source files compiled into multiple kt_jvm_library targets..."
if targets=$(bazel query 'kind("kt_jvm_library", //...)' 2>/dev/null); then
  duplicates=$(
    echo "$targets" | while read -r t; do
      bazel query "labels(srcs, $t)" 2>/dev/null
    done | sort | uniq -d
  )
  if [[ -n "$duplicates" ]]; then
    echo "ERROR: Source files compiled into multiple kt_jvm_library targets:"
    echo "$duplicates"
    rc=1
  fi
else
  echo "WARNING: could not enumerate kt_jvm_library targets; skipping duplicate-srcs check"
  echo "$targets"
  rc=1
fi

echo "Running detekt..."
bazel run //:detekt -- \
  --input "${REPO_ROOT}/simulator,${REPO_ROOT}/p4runtime,${REPO_ROOT}/e2e_tests,${REPO_ROOT}/cli,${REPO_ROOT}/web" \
  --config "${REPO_ROOT}/detekt.yml" \
  --build-upon-default-config || rc=1

exit $rc
