"""Starlark macros for p4testgen-based STF tests.

Usage in e2e_tests/p4testgen/BUILD.bazel:

    load("//e2e_tests:p4testgen.bzl", "p4_testgen_suite", "p4_testgen_test")

    # Batch all passing tests into a single JVM:
    p4_testgen_suite(
        name = "p4testgen_suite_test",
        tests = ["opassign1-bmv2", "arith1-bmv2", ...],
        includes = {"arith1-bmv2": ["@p4c//testdata/p4_16_samples:arith-skeleton.p4"]},
    )

    # Individual tests for manual/special cases:
    p4_testgen_test(name = "header-stack-ops-bmv2", tags = ["manual"])

This creates:
  - per-test _p4testgen_stfs rules (p4testgen → tree artifact of .stf files)
  - per-test genrules compiling P4 → <name>.txtpb via p4c-4ward
  - either one batched kt_jvm_test (suite) or one kt_jvm_test per test
"""

load("@rules_cc//cc:find_cc_toolchain.bzl", "CC_TOOLCHAIN_TYPE", "find_cc_toolchain", "use_cc_toolchain")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")
load("//bazel:fourward_pipeline.bzl", "fourward_pipeline")

def _p4testgen_stfs_impl(ctx):
    cc_toolchain = find_cc_toolchain(ctx)
    p4testgen = ctx.executable._p4testgen

    out_dir = ctx.actions.declare_directory(ctx.label.name)
    include_flags = ["-I " + f.dirname for f in ctx.files.includes]
    define_flags = ["-D" + d for d in ctx.attr.defines]
    args = " ".join([
        "--target " + ctx.attr.target + " --arch " + ctx.attr.arch + " --test-backend stf",
        "--max-tests " + str(ctx.attr.max_tests),
        "--seed " + str(ctx.attr.seed),
        "-I " + ctx.file._core_p4.dirname,
        # Workspace root of the target's repo, so `#include "path/from/root.p4"`
        # resolves in both in-repo builds AND BCR consumer builds.
        "-I " + (ctx.label.workspace_root or "."),
    ] + include_flags + define_flags + [
        ctx.file.src_p4.path,
        "--out-dir " + out_dir.path,
    ])

    # WORKAROUND for https://github.com/p4lang/p4c/issues/5618: p4c hardcodes
    # `popen("cc -E …")` for preprocessing with no flag to override the binary.
    # Define a shell function so the toolchain compiler answers to `cc`.
    # Once p4c supports `--cc <path>`, pass compiler_executable directly.
    ctx.actions.run_shell(
        command = """
            function cc () {{ "{cc}" "$@"; }}
            export -f cc
            "{p4testgen}" {args}
        """.format(
            cc = cc_toolchain.compiler_executable,
            p4testgen = p4testgen.path,
            args = args,
        ),
        inputs = [ctx.file.src_p4] + ctx.files._p4include + ctx.files.includes,
        outputs = [out_dir],
        tools = depset(
            direct = [p4testgen],
            transitive = [cc_toolchain.all_files],
        ),
        toolchain = CC_TOOLCHAIN_TYPE,
        # The toolchain compiler (e.g. gcc) may need cc1 on PATH.
        use_default_shell_env = True,
    )
    return [DefaultInfo(files = depset([out_dir]))]

_p4testgen_stfs = rule(
    implementation = _p4testgen_stfs_impl,
    attrs = {
        "src_p4": attr.label(allow_single_file = [".p4"]),
        "includes": attr.label_list(allow_files = True),
        "defines": attr.string_list(default = []),
        "max_tests": attr.int(default = 0),
        "seed": attr.int(default = 0),
        "target": attr.string(default = "bmv2"),
        "arch": attr.string(default = "v1model"),
        "_p4testgen": attr.label(
            default = "@p4c//backends/p4tools:p4testgen",
            executable = True,
            cfg = "exec",
        ),
        "_core_p4": attr.label(
            default = "@p4c//p4include:core.p4",
            allow_single_file = True,
        ),
        "_p4include": attr.label(
            default = "@p4c//p4include",
        ),
    },
    toolchains = use_cc_toolchain(),
    fragments = ["cpp"],
)

def _p4_testgen_rules(name, src_p4, includes, max_tests, seed, tags, defines = [], target = "bmv2", arch = "v1model"):
    """Creates the stfs + txtpb build rules for a single P4 program.

    Returns:
        A list of data labels [stfs_target, pb_target] for the kt_jvm_test.
    """
    tags = tags + ["heavy"]
    stfs_name = name + "_stfs"

    _p4testgen_stfs(
        name = stfs_name,
        src_p4 = src_p4,
        includes = includes,
        defines = defines,
        max_tests = max_tests,
        seed = seed,
        target = target,
        arch = arch,
        tags = tags,
    )

    # Pass includes as extra_srcs (deps only, no -I flags) rather than
    # includes.  p4c resolves #include "..." relative to the source file,
    # so -I flags are redundant — and $(execpath) would fail on filegroups.
    fourward_pipeline(
        name = name,
        src = src_p4,
        out = name + ".txtpb",
        defines = defines,
        extra_srcs = includes,
        tags = tags,
    )

    return [":" + stfs_name, ":" + name]

def p4_testgen_test(name, src_p4 = None, includes = [], max_tests = 0, seed = 0, tags = [], defines = [], target = "bmv2", arch = "v1model"):
    """Generates p4testgen STF tests and runs them against the 4ward simulator.

    Creates a dedicated kt_jvm_test for a single P4 program. Use this for
    manual/special-case tests. For bulk passing tests, prefer p4_testgen_suite().

    Args:
        name: base name; also used to derive the src_p4 filename.
        src_p4: P4 source file (default: @p4c//testdata/p4_16_samples:<name>.p4).
        includes: extra P4 file labels needed as #include dependencies (e.g.
                  skeleton headers). Their directory is added to the include path.
        max_tests: upper bound on STF tests to generate (default: 0 = unlimited).
                   p4testgen explores paths until exhausted or the limit is hit.
        seed: random seed for p4testgen's path exploration (default: 0).
              Different seeds explore different execution paths.
        tags: Bazel tags forwarded to the test.
        defines: P4 preprocessor macros (-D flags) passed to p4c and p4testgen.
        target: p4testgen --target (e.g. "bmv2").
        arch: p4testgen --arch (e.g. "v1model").
    """
    if src_p4 == None:
        src_p4 = "@p4c//testdata/p4_16_samples:" + name + ".p4"

    data = _p4_testgen_rules(name, src_p4, includes, max_tests, seed, tags, defines, target, arch)

    kt_jvm_test(
        name = name + "_test",
        srcs = ["P4TestgenSuiteTest.kt"],
        test_class = "fourward.e2e.p4testgen.P4TestgenSuiteTest",
        tags = tags + ["heavy"],
        data = data,
        deps = [
            "//bazel:runfiles",
            "//stf",
            "@fourward_maven//:junit_junit",
        ],
    )

def p4_testgen_suite(name, tests, includes = {}, max_tests = {}, tags = [], target = "bmv2", arch = "v1model"):
    """Batches p4testgen STF tests for many P4 programs into a single JVM.

    Build-phase parallelism (p4testgen symbolic execution, p4c compilation) is
    unchanged — only test execution is batched into one kt_jvm_test.

    Args:
        name:      name of the batched kt_jvm_test target.
        tests:     list of P4 program base names (e.g. "opassign1-bmv2").
        includes:  dict mapping program names to lists of extra P4 include labels.
        max_tests: dict mapping program names to max-test limits.
        tags:      Bazel tags forwarded to the kt_jvm_test.
        target:    p4testgen --target applied to every program in the suite.
        arch:      p4testgen --arch applied to every program in the suite.
    """
    if not tests:
        return
    data = []

    for test in tests:
        src_p4 = "@p4c//testdata/p4_16_samples:" + test + ".p4"
        test_includes = includes.get(test, [])
        test_max_tests = max_tests.get(test, 0)
        data.extend(_p4_testgen_rules(test, src_p4, test_includes, test_max_tests, seed = 0, tags = tags, target = target, arch = arch))

    kt_jvm_test(
        name = name,
        srcs = ["P4TestgenSuiteTest.kt"],
        test_class = "fourward.e2e.p4testgen.P4TestgenSuiteTest",
        size = "large",  # 155 tests with Z3 constraint solving; needs 900s
        tags = tags + ["heavy"],
        data = data,
        deps = [
            "//bazel:runfiles",
            "//stf",
            "@fourward_maven//:junit_junit",
        ],
    )
