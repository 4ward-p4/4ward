#!/usr/bin/env bash
# Developer task runner. Run `./tools/dev.sh help` to see available commands.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cmd_help() {
  cat <<'EOF'
Usage: ./dev.sh <command>

Commands:
  build           Build everything
  test            Run all tests
  fmt             Auto-format all files
  lint            Run all linters
  coverage        Run tests with coverage, open report
  diff-coverage   Compute incremental coverage from a diff + LCOV file
  benchmark       Run dataplane performance benchmark
  help            Show this help
EOF
}

cmd_build() {
  bazel build //... "$@"
}

# Extra args are passed through, e.g. `./dev.sh test //simulator:BitVectorTest`.
cmd_test() {
  bazel test //... "$@"
}

cmd_fmt() {
  exec "${REPO_ROOT}/tools/format.sh" "$@"
}

cmd_lint() {
  exec "${REPO_ROOT}/tools/lint.sh" "$@"
}

cmd_coverage() {
  exec "${REPO_ROOT}/tools/coverage.sh" "$@"
}

cmd_diff_coverage() {
  exec "${REPO_ROOT}/tools/diff-coverage.sh" "$@"
}

cmd_benchmark() {
  bazel test //grpc:DataplaneBenchmark \
    --test_output=streamed \
    --nocache_test_results \
    "$@"
}

# Dispatch.
case "${1:-help}" in
  build)        shift; cmd_build "$@" ;;
  test)         shift; cmd_test "$@" ;;
  fmt|format)   shift; cmd_fmt "$@" ;;
  lint)         shift; cmd_lint "$@" ;;
  coverage|cov)  shift; cmd_coverage "$@" ;;
  diff-coverage) shift; cmd_diff_coverage "$@" ;;
  benchmark|bench) shift; cmd_benchmark "$@" ;;
  help|--help|-h) cmd_help ;;
  *)
    echo "Unknown command: $1" >&2
    echo "Run './dev.sh help' for available commands." >&2
    exit 1
    ;;
esac
