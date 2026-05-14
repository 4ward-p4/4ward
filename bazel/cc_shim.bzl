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

def _compiler_shell_expr(cc, workspace_name):
    """Returns a quoted shell expression that resolves `compiler_executable` at runtime.

    On Linux, `compiler_executable` is an absolute path — use it directly.
    On macOS (and google3), it's a relative path valid at the Bazel execroot but
    not in the runfiles tree. Resolve from $RUNFILES at runtime.
    """
    if cc.startswith("/"):
        return '"{}"'.format(cc)
    if cc.startswith("external/"):
        runfiles_path = cc[len("external/"):]
    else:
        runfiles_path = workspace_name + "/" + cc
    return '"$RUNFILES/{}"'.format(runfiles_path)

def _runfiles_preamble(workspace_name):
    """Shell preamble that resolves the runfiles root into $RUNFILES.

    Tries RUNFILES_DIR env var first (set by Bazel/blaze), then falls back to
    stripping the workspace name from the shim's own path. When all compiler
    paths are absolute, $RUNFILES is never referenced and this is a no-op.
    """
    return "\n".join([
        'if [ -n "${RUNFILES_DIR:-}" ]; then',
        '  RUNFILES="$RUNFILES_DIR"',
        "else",
        '  SHIM_DIR="$(cd "$(dirname "$0")" && pwd)"',
        '  RUNFILES="${SHIM_DIR%/' + workspace_name + '/*}"',
        "fi",
        "",
    ])

def _cc_shim_impl(ctx):
    exec_cc = ctx.attr._exec_cc_toolchain[cc_common.CcToolchainInfo]
    target_cc = ctx.attr._target_cc_toolchain[cc_common.CcToolchainInfo]

    exec_path = exec_cc.compiler_executable
    target_path = target_cc.compiler_executable
    ws = ctx.workspace_name

    # Subdir so the output basename can be literally `cc` without colliding
    # with the target name; callers take `.parent` to get a dir for PATH.
    shim = ctx.actions.declare_file(ctx.label.name + "/cc")

    preamble = _runfiles_preamble(ws)
    exec_ref = _compiler_shell_expr(exec_path, ws)
    target_ref = _compiler_shell_expr(target_path, ws)

    if exec_path == target_path:
        content = "\n".join([
            "#!/bin/sh",
            preamble.rstrip("\n"),
            "exec {cc} \"$@\"".format(cc = exec_ref),
            "",
        ])
    else:
        # Probe the exec-platform compiler with a no-op preprocess. If it
        # runs (same-arch), use it; otherwise fall back to the target-platform
        # compiler (handles blaze test on a different-arch remote machine).
        content = "\n".join([
            "#!/bin/sh",
            preamble.rstrip("\n"),
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
