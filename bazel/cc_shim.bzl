"""Emits an executable shim named `cc` forwarding to the CC toolchain compiler.

For sandboxed environments without a system `cc` on PATH: consumers append
the shim's parent dir to their subprocess's PATH as a fallback, and p4c (or
any tool that shells out to `cc`) finds one. For Starlark actions, prefer
`export -f cc` inside `run_shell` — no separate file needed.

WORKAROUND for https://github.com/p4lang/p4c/issues/5618: p4c hardcodes the
preprocessor binary (`cc` or `cpp`); once p4c supports `--cc <path>`, pass the
compiler path directly and remove the shim from PATH.
"""

load("@rules_cc//cc/common:cc_common.bzl", "cc_common")

def _compiler_shell_expr(cc):
    """Returns a quoted shell expression that resolves `compiler_executable` at runtime.

    On Linux, `compiler_executable` is an absolute path — use it directly.
    On macOS, it's a relative path (e.g. `external/<repo>/cc_wrapper.sh`) valid
    at the Bazel execroot but not in the runfiles tree, where external repos are
    top-level siblings of `_main/`. Resolve from the runfiles root at runtime.
    """
    if cc.startswith("/"):
        return '"{}"'.format(cc)
    runfiles_path = cc[len("external/"):] if cc.startswith("external/") else "_main/" + cc
    return '"$RUNFILES/{}"'.format(runfiles_path)

# Shell preamble that sets $RUNFILES from RUNFILES_DIR (set by Bazel/blaze
# test runners and `bazel run`). When all compiler paths are absolute (Linux),
# $RUNFILES is never referenced and the preamble is a harmless no-op.
_RUNFILES_PREAMBLE = 'RUNFILES="${RUNFILES_DIR:-}"\n'

def _cc_shim_impl(ctx):
    exec_cc = ctx.attr._exec_cc_toolchain[cc_common.CcToolchainInfo]
    target_cc = ctx.attr._target_cc_toolchain[cc_common.CcToolchainInfo]

    exec_path = exec_cc.compiler_executable
    target_path = target_cc.compiler_executable

    # Subdir so the output basename can be literally `cc` without colliding
    # with the target name; callers take `.parent` to get a dir for PATH.
    shim = ctx.actions.declare_file(ctx.label.name + "/cc")

    exec_ref = _compiler_shell_expr(exec_path)
    target_ref = _compiler_shell_expr(target_path)

    if exec_path == target_path:
        content = "\n".join([
            "#!/bin/sh",
            _RUNFILES_PREAMBLE.rstrip("\n"),
            "exec {cc} \"$@\"".format(cc = exec_ref),
            "",
        ])
    else:
        # Probe the exec-platform compiler with a no-op preprocess. If it
        # runs (same-arch), use it; otherwise fall back to the target-platform
        # compiler (handles blaze test on a different-arch remote machine).
        content = "\n".join([
            "#!/bin/sh",
            _RUNFILES_PREAMBLE.rstrip("\n"),
            "if {exec} -E /dev/null >/dev/null 2>&1; then".format(exec = exec_ref),
            "  exec {exec} \"$@\"".format(exec = exec_ref),
            "else",
            "  exec {target} \"$@\"".format(target = target_ref),
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
