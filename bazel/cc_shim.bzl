"""cc shim — wraps the CC toolchain compiler as `cc` for p4c preprocessing.

For sandboxed environments without a system `cc` on PATH: consumers append
the shim's parent dir to their subprocess's PATH as a fallback. For Starlark
actions, prefer `export -f cc` inside `run_shell` — no separate file needed.

WORKAROUND for https://github.com/p4lang/p4c/issues/5618: p4c hardcodes the
preprocessor binary; once p4c supports `--cc <path>`, pass the compiler path
directly and remove this shim.
"""

load("@rules_cc//cc/common:cc_common.bzl", "cc_common")

def _cc_shim_impl(ctx):
    exec_cc = ctx.attr._exec_cc_toolchain[cc_common.CcToolchainInfo]
    target_cc = ctx.attr._target_cc_toolchain[cc_common.CcToolchainInfo]
    ws = ctx.workspace_name

    def _resolve(cc):
        """Quoted shell expression for a compiler_executable path."""
        if cc.startswith("/"):
            return '"{}"'.format(cc)
        path = cc[len("external/"):] if cc.startswith("external/") else ws + "/" + cc
        return '"$RUNFILES/{}"'.format(path)

    exec_ref = _resolve(exec_cc.compiler_executable)
    target_ref = _resolve(target_cc.compiler_executable)

    # Subdir so the output basename can be literally `cc` without colliding
    # with the target name; callers take `.parent` to get a dir for PATH.
    shim = ctx.actions.declare_file(ctx.label.name + "/cc")

    # Resolve runfiles root, then probe the exec-platform compiler. If it
    # runs (same arch), use it; otherwise fall back to the target-platform
    # compiler (handles blaze test on a different-arch remote machine).
    # On same-platform builds both compilers are identical and the probe
    # succeeds instantly.
    content = "\n".join([
        "#!/bin/sh",
        'SHIM_DIR="$(cd "$(dirname "$0")" && pwd)"',
        'RUNFILES="${SHIM_DIR%/' + ws + '/*}"',
        "if {} -E /dev/null >/dev/null 2>&1; then".format(exec_ref),
        '  exec {} "$@"'.format(exec_ref),
        "else",
        '  exec {} "$@"'.format(target_ref),
        "fi",
        "",
    ])

    ctx.actions.write(output = shim, content = content, is_executable = True)

    return [DefaultInfo(
        files = depset([shim]),
        runfiles = ctx.runfiles(
            files = [shim],
            transitive_files = depset(
                transitive = [exec_cc.all_files, target_cc.all_files],
            ),
        ),
    )]

cc_shim = rule(
    implementation = _cc_shim_impl,
    attrs = {
        "_exec_cc_toolchain": attr.label(
            default = "@bazel_tools//tools/cpp:current_cc_toolchain",
            cfg = "exec",
            providers = [cc_common.CcToolchainInfo],
        ),
        "_target_cc_toolchain": attr.label(
            default = "@bazel_tools//tools/cpp:current_cc_toolchain",
            providers = [cc_common.CcToolchainInfo],
        ),
    },
)
