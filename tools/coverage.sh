#!/usr/bin/env bash
# WORKAROUND: This entire script exists because `bazel coverage //...` is
# broken for kt_jvm_test under Bazel 9 + Bzlmod.  Two upstream bugs conspire:
#
#   1. The JaCoCo metadata file uses `external/…` paths that don't resolve
#      under the Bzlmod `_main/` runfiles root.
#
#   2. JacocoLCOVFormatter silently filters out every source file because
#      `-paths-for-coverage.txt` contains workspace-relative paths
#      (e.g. `simulator/BitVector.kt`) while the class metadata reports
#      package-qualified paths (`fourward/simulator/BitVector.kt`).
#      See https://github.com/bazelbuild/bazel/issues/12159.
#
# When these are fixed, this script should collapse to:
#
#   bazel coverage //... --combined_report=lcov \
#     --instrumentation_filter='//simulator[/:],//grpc[/:]'
#   cp bazel-out/_coverage/lcov.dat coverage.lcov
#
# Until then, we work around both by:
#   • Building with --collect_code_coverage to get instrumented jars.
#   • Running each test binary directly to produce .exec probe data.
#   • Converting .exec → LCOV ourselves using JaCoCo's API (from the
#     deploy jar already in the Bazel cache) with a small inline helper
#     that bypasses the broken path filtering.
#
# Collects Kotlin test coverage for the simulator and grpc libraries.
#
# Coverage comes from two sources:
#   1. Unit tests (kt_jvm_test targets) — instrumented directly by JaCoCo.
#   2. E2e tests — the simulator subprocess inherits COVERAGE=1 and JaCoCo
#      flushes probe data on graceful shutdown (SIGTERM).
#
# Usage:
#   ./coverage.sh                                # run tests, produce LCOV
#   ./coverage.sh --html                         # also generate HTML report
#   ./coverage.sh --html --baseline B --diff D   # HTML with differential coverage
#   ./coverage.sh --min-coverage 80              # fail if coverage < 80%
#
# Options:
#   --html              Generate an HTML report (requires genhtml / lcov).
#   --baseline <file>   Baseline LCOV for differential coverage (genhtml --baseline-file).
#   --diff <file>       Unified diff for differential coverage (genhtml --diff-file).
#   --min-coverage <N>  Fail if line coverage percentage is below N (default: none).

set -euo pipefail

WANT_HTML=false
BASELINE_FILE=""
DIFF_FILE=""
MIN_COVERAGE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --html)         WANT_HTML=true; shift ;;
    --baseline)     BASELINE_FILE="${2:?--baseline requires a file argument}"; shift 2 ;;
    --diff)         DIFF_FILE="${2:?--diff requires a file argument}"; shift 2 ;;
    --min-coverage) MIN_COVERAGE="${2:?--min-coverage requires a number}"; shift 2 ;;
    *)              echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_DIR="${REPO_ROOT}/coverage-report"
COVERAGE_WORKDIR=$(mktemp -d)
trap 'rm -rf "${COVERAGE_WORKDIR}"' EXIT

# //simulator:FooTest → simulator/FooTest
target_to_bin_rel() {
  local t="${1#//}"
  echo "${t/://}"
}

# ── Discover test targets ────────────────────────────────────────────────────

TARGETS=$(bazel query 'kind(kt_jvm_test, //...) - attr(tags, "manual|heavy", //...)' 2>/dev/null)

# ── Build with coverage instrumentation ──────────────────────────────────────

echo "Building with coverage instrumentation..."
# shellcheck disable=SC2086
bazel build --collect_code_coverage \
  --instrumentation_filter='//simulator[/:],//grpc[/:]' \
  ${TARGETS}

BIN_DIR="$(bazel info bazel-bin)"

# ── Locate the JaCoCo deploy jar and JDK ─────────────────────────────────────

# Pick the runfiles of any kt_jvm_test target to find the tools.
SAMPLE_RUNFILES=""
for target in ${TARGETS}; do
  SAMPLE_RUNFILES="${BIN_DIR}/$(target_to_bin_rel "${target}").runfiles"
  [[ -d "${SAMPLE_RUNFILES}" ]] && break
done

if [[ ! -d "${SAMPLE_RUNFILES}" ]]; then
  echo "Error: no runfiles directory found for any test target." >&2
  exit 1
fi

JACOCO_JAR="${SAMPLE_RUNFILES}/rules_java++toolchains+remote_java_tools/java_tools/JacocoCoverage_jarjar_deploy.jar"
# Auto-detect the hermetic JDK directory (platform-dependent name).
JDK_HOME=""
for d in "${SAMPLE_RUNFILES}"/rules_java++toolchains+remotejdk21_*; do
  if [[ -x "${d}/bin/java" ]]; then
    JDK_HOME="${d}"
    break
  fi
done
JAVA="${JDK_HOME}/bin/java"
JAVAC="${JDK_HOME}/bin/javac"
JAR="${JDK_HOME}/bin/jar"

if [[ ! -f "${JACOCO_JAR}" ]]; then
  echo "Error: JaCoCo deploy jar not found at ${JACOCO_JAR}" >&2
  exit 1
fi
if [[ -z "${JDK_HOME}" || ! -x "${JAVA}" ]]; then
  echo "Error: JDK not found in ${SAMPLE_RUNFILES}/rules_java++toolchains+remotejdk21_*" >&2
  exit 1
fi

# ── Compile the exec-to-LCOV converter ───────────────────────────────────────
#
# This is a tiny Java program that uses JaCoCo's API to convert .exec probe
# data into LCOV format. It uses the no-arg JacocoLCOVFormatter constructor
# which disables the broken source-path filtering.

CONVERTER_SRC="${COVERAGE_WORKDIR}/ExecToLcov.java"
cat > "${CONVERTER_SRC}" << 'JAVA'
import com.google.testing.coverage.JacocoLCOVFormatter;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;

/**
 * Converts JaCoCo .exec probe data into LCOV format.
 *
 * Usage: ExecToLcov <output.dat> <exec1> [exec2 ...] -- <jar1> [jar2 ...]
 *
 * Reads .exec files, analyzes .class.uninstrumented entries from the jars,
 * and writes LCOV to the output file.
 */
public class ExecToLcov {
    private static final String UNINSTRUMENTED_SUFFIX = ".class.uninstrumented";

    public static void main(String[] args) throws Exception {
        String outputPath = args[0];
        List<String> execFiles = new ArrayList<>();
        List<String> jarFiles = new ArrayList<>();
        boolean seenSep = false;
        for (int i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) { seenSep = true; continue; }
            if (seenSep) jarFiles.add(args[i]); else execFiles.add(args[i]);
        }

        // Load execution data.
        ExecFileLoader loader = new ExecFileLoader();
        for (String execFile : execFiles) {
            try (var fis = new FileInputStream(execFile)) {
                loader.load(fis);
            }
        }

        // Analyze uninstrumented classes — single pass feeds both the
        // coverage analyzer and the branch-detail analyzer.
        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        com.google.testing.coverage.BranchDetailAnalyzer branchAnalyzer =
            new com.google.testing.coverage.BranchDetailAnalyzer(
                loader.getExecutionDataStore());

        Set<String> seen = new HashSet<>();
        for (String jarPath : jarFiles) {
            try (JarFile jar = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(UNINSTRUMENTED_SUFFIX) && seen.add(name)) {
                        byte[] bytes = jar.getInputStream(entry).readAllBytes();
                        analyzer.analyzeAll(new ByteArrayInputStream(bytes), name);
                        branchAnalyzer.analyzeAll(new ByteArrayInputStream(bytes), name);
                    }
                }
            }
        }

        IBundleCoverage bundle = builder.getBundle("coverage");
        Map<String, com.google.testing.coverage.BranchCoverageDetail> branchDetails =
            branchAnalyzer.getBranchDetails();

        // Write LCOV using the no-arg constructor (no path filtering).
        JacocoLCOVFormatter formatter = new JacocoLCOVFormatter();
        try (PrintWriter pw = new PrintWriter(
                java.nio.file.Files.newBufferedWriter(
                    java.nio.file.Path.of(outputPath),
                    java.nio.charset.StandardCharsets.UTF_8))) {
            org.jacoco.report.IReportVisitor visitor =
                formatter.createVisitor(pw, branchDetails);
            visitor.visitInfo(
                loader.getSessionInfoStore().getInfos(),
                loader.getExecutionDataStore().getContents());
            visitor.visitBundle(bundle,
                new org.jacoco.report.ISourceFileLocator() {
                    public Reader getSourceFile(String pkg, String name) { return null; }
                    public int getTabWidth() { return 4; }
                });
            visitor.visitEnd();
        }
    }
}
JAVA

echo "Compiling coverage converter..."
"${JAVAC}" -cp "${JACOCO_JAR}" \
  -d "${COVERAGE_WORKDIR}" \
  "${CONVERTER_SRC}"

# ── Run each test and collect per-test .exec ─────────────────────────────────
#
# Tests are independent (separate output dirs), so run them in parallel.
# GNU parallel or xargs -P would work, but a simple background-job approach
# avoids extra dependencies and keeps the output readable.

MAX_JOBS=$(nproc 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo 4)
PIDS=()
TARGET_NAMES=()
FAILED=0
for target in ${TARGETS}; do
  bin_rel="$(target_to_bin_rel "${target}")"
  bin_path="${BIN_DIR}/${bin_rel}"

  if [[ ! -x "${bin_path}" ]]; then
    echo "SKIP  ${target} (binary not found)"
    continue
  fi

  test_coverage_dir="${COVERAGE_WORKDIR}/${bin_rel}"
  mkdir -p "${test_coverage_dir}"

  # Throttle: wait for a slot when MAX_JOBS are already running.
  while [[ $(jobs -rp | wc -l) -ge ${MAX_JOBS} ]]; do
    wait -n 2>/dev/null || true
  done

  (
    JAVA_RUNFILES="${bin_path}.runfiles" \
    JAVA_COVERAGE_FILE="${test_coverage_dir}/jvcov.dat" \
    COVERAGE_DIR="${test_coverage_dir}" \
    COVERAGE=1 \
      "${bin_path}" >/dev/null 2>&1
  ) &
  PIDS+=("$!")
  TARGET_NAMES+=("${target}")
done

# Wait for all tests and report results.
for i in "${!PIDS[@]}"; do
  if wait "${PIDS[$i]}"; then
    echo "ok    ${TARGET_NAMES[$i]}"
  else
    echo "FAIL  ${TARGET_NAMES[$i]}"
    FAILED=1
  fi
done

if [[ ${FAILED} -ne 0 ]]; then
  echo "Warning: some tests failed; coverage data may be incomplete." >&2
fi

# Collect all .exec files produced by the parallel runs.
EXEC_FILES=()
while IFS= read -r -d '' ex; do
  [[ -s "${ex}" ]] && EXEC_FILES+=("${ex}")
done < <(find "${COVERAGE_WORKDIR}" -name 'jvcov*.exec' -print0)

if [[ ${#EXEC_FILES[@]} -eq 0 ]]; then
  echo "Error: no coverage data produced." >&2
  exit 1
fi

# ── Identify instrumented jars ───────────────────────────────────────────────

INSTRUMENTED_JARS=()
for jar in "${BIN_DIR}"/simulator/*.jar "${BIN_DIR}"/grpc/*.jar; do
  if "${JAR}" tf "${jar}" 2>/dev/null | grep -q '\.class\.uninstrumented$'; then
    INSTRUMENTED_JARS+=("${jar}")
  fi
done

if [[ ${#INSTRUMENTED_JARS[@]} -eq 0 ]]; then
  echo "Error: no instrumented jars found." >&2
  exit 1
fi

# ── Convert .exec → LCOV ────────────────────────────────────────────────────

MERGED="${COVERAGE_WORKDIR}/merged.lcov"

echo ""
echo "Converting .exec → LCOV (${#EXEC_FILES[@]} exec files, ${#INSTRUMENTED_JARS[@]} instrumented jars)..."
"${JAVA}" -cp "${COVERAGE_WORKDIR}:${JACOCO_JAR}" ExecToLcov \
  "${MERGED}" \
  "${EXEC_FILES[@]}" \
  -- \
  "${INSTRUMENTED_JARS[@]}"

# ── Post-process LCOV ────────────────────────────────────────────────────────
#
# 1. JaCoCo reports source files as package/Class.kt (e.g.
#    fourward/simulator/BitVector.kt).  Rewrite SF: lines to use the
#    workspace-relative path (simulator/BitVector.kt) so tools and editors
#    can find the actual source files.
#
# 2. JacocoLCOVFormatter omits LH:/LF: summary lines.  Add them so tools
#    like genhtml can display totals.

FIXED="${COVERAGE_WORKDIR}/fixed.lcov"
awk '
  /^SF:/ {
    sub(/^SF:fourward\//, "SF:")
    # Skip generated gRPC/proto sources — not our code.
    if ($0 ~ /^SF:(p4|sim|dataplane)\//) { skip = 1; next }
    skip = 0
    lh = 0; lf = 0
    print; next
  }
  skip { next }
  /^DA:/ {
    lf++
    split($0, a, ",")
    if (a[2]+0 > 0) lh++
    print; next
  }
  /^end_of_record/ {
    printf "LH:%d\nLF:%d\n", lh, lf
    total_lh += lh; total_lf += lf
    print; next
  }
  { print }
  END { printf "%d %d\n", total_lh, total_lf > "/dev/stderr" }
' "${MERGED}" > "${FIXED}" 2>"${COVERAGE_WORKDIR}/summary.txt"
mv "${FIXED}" "${MERGED}"

read -r total_lh total_lf < "${COVERAGE_WORKDIR}/summary.txt"

echo "LCOV report: ${MERGED}"
if [[ ${total_lf} -gt 0 ]]; then
  pct=$(( 100 * total_lh / total_lf ))
  echo "Line coverage: ${total_lh}/${total_lf} (${pct}%)"
fi

if [[ -n "${MIN_COVERAGE}" && ${total_lf} -gt 0 && ${pct} -lt ${MIN_COVERAGE} ]]; then
  echo "Error: line coverage ${pct}% is below minimum ${MIN_COVERAGE}%" >&2
  exit 1
fi

# ── Persist LCOV ─────────────────────────────────────────────────────────────

PERSISTENT="${REPO_ROOT}/coverage.lcov"
cp "${MERGED}" "${PERSISTENT}"
echo "Saved:        ${PERSISTENT}"

# ── HTML report (optional) ───────────────────────────────────────────────────

GENHTML_ARGS=(--quiet)
[[ -n "${BASELINE_FILE}" ]] && GENHTML_ARGS+=(--baseline-file "${BASELINE_FILE}")
if [[ -n "${DIFF_FILE}" ]]; then
  # Strip git's a/ b/ prefixes so paths match LCOV SF: lines.
  STRIPPED_DIFF="${COVERAGE_WORKDIR}/stripped.diff"
  sed 's|^--- a/|--- |; s|^+++ b/|+++ |' "${DIFF_FILE}" > "${STRIPPED_DIFF}"
  # genhtml resolves diff paths to absolute but keeps LCOV SF: paths relative
  # (path), and line-number shifts between baseline and current cause unmapped
  # TLA categories (unmapped). Suppress both — diff coverage numbers are
  # computed separately by diff-coverage.sh and are unaffected.
  GENHTML_ARGS+=(--diff-file "${STRIPPED_DIFF}" --ignore-errors path,unmapped)
fi

if command -v genhtml >/dev/null 2>&1; then
  rm -rf "${REPORT_DIR}"
  genhtml "${PERSISTENT}" --output-directory "${REPORT_DIR}" "${GENHTML_ARGS[@]}"

  # Replace cryptic TLA abbreviations with readable labels in the generated
  # HTML. Only matches text between > and < so CSS classes are preserved.
  # Uses -i.bak + delete for BSD/GNU sed portability (BSD requires an
  # argument to -i; GNU treats a separate '' as a filename).
  find "${REPORT_DIR}" -name '*.html' -exec sed -i.bak \
      -e 's/>CBC</>Covered</g'           -e 's/>GBC</>Newly covered</g' \
      -e 's/>UBC</>Not covered</g'       -e 's/>LBC</>Coverage lost</g' \
      -e 's/>GNC</>New \&amp; covered</g'    -e 's/>UNC</>New \&amp; not covered</g' \
      -e 's/>EUB</>Excluded (not covered)</g' -e 's/>ECB</>Excluded (covered)</g' \
      -e 's/>DUB</>Deleted (not covered)</g'  -e 's/>DCB</>Deleted (covered)</g' \
      -e 's/>UIC</>Included (not covered)</g' -e 's/>GIC</>Included (covered)</g' \
      {} +
  find "${REPORT_DIR}" -name '*.bak' -delete

  echo "HTML report:  ${REPORT_DIR}/index.html"
elif "${WANT_HTML}"; then
  echo "Error: genhtml not found (install lcov: brew install lcov)" >&2
  exit 1
fi
