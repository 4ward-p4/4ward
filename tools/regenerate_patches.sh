#!/usr/bin/env bash
# Regenerate the Bazel patches for behavioral_model and p4lang_pi from
# upstream commits. Source of truth for what each patch contains.
#
# Why: hand-editing patch files is error-prone — off-by-one hunk counts,
# trailing-newline confusion, doubled content via symlinks. This script
# fetches clean upstream into `a/`, applies our edits to `b/` deterministically,
# and writes `diff -ruN a b` to bazel/<name>.patch.
#
# The whole-file additions (BUILD.bazel, MODULE.bazel, gnmi BUILD) live as
# snippets in bazel/snippets/ and are copied verbatim — that's the source
# of truth for those files. In-place edits are inline below as sed/python
# invocations grouped by purpose with === [section] === comments.
#
# Usage: ./tools/regenerate_patches.sh
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"

# Pinned upstream commits — keep in sync with MODULE.bazel.
BM_COMMIT="6c7c93e5484e069c539b5c990bf37c531599894a"
PI_COMMIT="51805c0108cb49e85e4812dd05bb6693b1f48f85"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

snapshot() {
  local name="$1" repo="$2" commit="$3" init_submodules="${4:-no}"
  echo "Fetching $name@$commit..."
  git clone --quiet "$repo" "$WORKDIR/$name-a"
  git -C "$WORKDIR/$name-a" checkout --quiet "$commit"
  if [[ "$init_submodules" == "yes" ]]; then
    git -C "$WORKDIR/$name-a" submodule --quiet update --init --recursive
  fi
  rm -rf "$WORKDIR/$name-a/.git"
  cp -r "$WORKDIR/$name-a" "$WORKDIR/$name-b"
}

write_patch() {
  local name="$1" out
  out="$REPO/bazel/$name.patch"
  # `diff -r` follows symlinks. PI's proto/gnmi is a symlink to
  # proto/openconfig/gnmi/proto/gnmi; if both BUILD additions appear in the
  # patch, applying it concatenates the file. Strip the symlinked path —
  # the canonical target is preserved.
  # Strip the per-file timestamps `diff -ruN` emits — they would churn the
  # checked-in patch on every regeneration without changing what gets applied.
  ( cd "$WORKDIR" && diff -ruN "$name-a" "$name-b" 2>/dev/null \
      | sed -E -e "s|^--- $name-a/([^[:space:]]+).*|--- a/\1|" \
              -e "s|^\+\+\+ $name-b/([^[:space:]]+).*|+++ b/\1|" \
              -e "s|^diff -ruN $name-a/([^ ]+) $name-b/|diff -ruN a/\1 b/|" \
      | python3 -c '
import re, sys
s = sys.stdin.read()
s = re.sub(r"diff -ruN a/proto/gnmi/BUILD.*?(?=\ndiff -ruN |\Z)", "", s, flags=re.DOTALL)
sys.stdout.write(s)
' \
      > "$out" ) || true
  echo "Wrote $out ($(wc -l < "$out") lines)"
}

# Replace a unique block in a file, asserting the original block exists.
replace_block() {
  python3 - "$@" <<'PY'
import sys
file, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
with open(file) as f:
    s = f.read()
if old not in s:
    sys.exit(f'expected block not found in {file}')
with open(file, 'w') as f:
    f.write(s.replace(old, new, 1))
PY
}

snapshot behavioral_model https://github.com/p4lang/behavioral-model.git "$BM_COMMIT"
snapshot p4lang_pi https://github.com/p4lang/PI.git "$PI_COMMIT" yes

# ===========================================================================
# behavioral_model edits
# ===========================================================================
bm_b="$WORKDIR/behavioral_model-b"

# === [Native Bazel build for bm_sim + simple_switch_lib + diff-harness libs] ===
cp "$REPO/bazel/snippets/behavioral_model.BUILD.bazel" "$bm_b/BUILD.bazel"
cp "$REPO/bazel/snippets/behavioral_model.MODULE.bazel" "$bm_b/MODULE.bazel"

# === [arm64 macOS DynamicBitset specialization] ===
# unsigned long is 64-bit but distinct from uint64_t (unsigned long long) on
# arm64 macOS — DynamicBitset::Block is unsigned long, so this specialization
# is needed to avoid linker errors.
replace_block "$bm_b/include/bm/bm_sim/dynamic_bitset.h" \
'      find_lowest_bit<uint32_t>(sw);
}

class DynamicBitset {' \
'      find_lowest_bit<uint32_t>(sw);
}

#if defined(__APPLE__) && defined(__aarch64__)
template<>
inline int find_lowest_bit<unsigned long>(unsigned long v) {
  return find_lowest_bit<uint64_t>(static_cast<uint64_t>(v));
}
#endif

class DynamicBitset {'

# ===========================================================================
# p4lang_pi edits
# ===========================================================================
pi_b="$WORKDIR/p4lang_pi-b"

# === [Bzlmod shim: alias PI's WORKSPACE-style @com_* to our deps] ===
cp "$REPO/bazel/snippets/p4lang_pi.MODULE.bazel" "$pi_b/MODULE.bazel"

# === [Build PI's bundled gnmi.proto as cc_grpc_library] ===
# Write through the symlink target; diff sees both paths, the dedup in
# write_patch() above keeps only the canonical one.
cp "$REPO/bazel/snippets/p4lang_pi.gnmi.BUILD" "$pi_b/proto/openconfig/gnmi/proto/gnmi/BUILD"
sed -i 's|@com_github_openconfig_gnmi//:gnmi_cc_grpc|//proto/gnmi:gnmi_cc_grpc|' \
    "$pi_b/proto/server/BUILD"

# === [Use non-deprecated @p4runtime//proto/p4/v1: target labels] ===
for f in "$pi_b/proto/BUILD" "$pi_b/proto/p4info/BUILD" \
         "$pi_b/proto/frontend/BUILD" "$pi_b/proto/server/BUILD"; do
  sed -i \
    -e 's|@com_github_p4lang_p4runtime//:p4runtime_cc_grpc|@com_github_p4lang_p4runtime//proto/p4/v1:p4runtime_cc_grpc|g' \
    -e 's|@com_github_p4lang_p4runtime//:p4runtime_cc_proto|@com_github_p4lang_p4runtime//proto/p4/v1:p4runtime_cc_proto|g' \
    -e 's|@com_github_p4lang_p4runtime//:p4info_cc_proto|@com_github_p4lang_p4runtime//proto/p4/config/v1:p4info_cc_proto|g' \
    "$f"
done

# === [proto/server: includes = ["."] so consumers find <PI/proto/pi_server.h>] ===
replace_block "$pi_b/proto/server/BUILD" \
'    hdrs = ["PI/proto/pi_server.h", "pi_server_testing.h", "gnmi.h"],
    copts = ["-DUSE_ABSL=1"],
    deps = ' \
'    hdrs = ["PI/proto/pi_server.h", "pi_server_testing.h", "gnmi.h"],
    includes = ["."],
    copts = ["-DUSE_ABSL=1"],
    deps = '

# === [abseil 20240116+ requires ABSL_-prefixed thread-annotation macros] ===
sed -i -E 's/\b(SCOPED_LOCKABLE|SHARED_LOCK_FUNCTION|EXCLUSIVE_LOCK_FUNCTION|UNLOCK_FUNCTION)\b/ABSL_\1/g' \
    "$pi_b/proto/server/shared_mutex.h"

# === [PI bundles fmt 3.x; libstdc++ 14 removed the deprecated 2-arg allocate] ===
sed -i 's|this->allocate(new_capacity, FMT_NULL)|this->allocate(new_capacity)|' \
    "$pi_b/proto/third_party/fmt/format.h"

# ===========================================================================
# Diff and write.
# ===========================================================================
write_patch behavioral_model
write_patch p4lang_pi

echo "Done. Verify with:"
echo "  bazel test //e2e_tests/p4runtime_diff:P4RuntimeDiffScenariosTest"
