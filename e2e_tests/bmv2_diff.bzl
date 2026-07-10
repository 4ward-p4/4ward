"""Starlark macro for BMv2 differential tests.

Usage in e2e_tests/bmv2_diff/BUILD.bazel:

    load("//e2e_tests:bmv2_diff.bzl", "bmv2_diff_test_suite")

    bmv2_diff_test_suite(
        name = "bmv2_diff_test",
        tests = ["opassign1-bmv2", "flag_lost-bmv2", ...],
        local_tests = {
            "action_selector_3": "//e2e_tests/trace_tree",
        },
    )

For each test this creates:
  - a genrule compiling <name>.p4 → <name>.json using p4c-bm2-ss (BMv2 backend)
  - a genrule compiling <name>.p4 → <name>.txtpb using p4c-4ward (for P4Info)
  - a genrule copying <name>.stf
  - a single kt_jvm_test that runs all tests in one JVM, driving both the 4ward
    simulator and BMv2's simple_switch, and comparing output packets bit-for-bit.
"""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")
load("//bazel:fourward_pipeline.bzl", "fourward_pipeline")

def bmv2_diff_test_suite(name, tests, local_tests = {}, tags = [], includes = []):
    """Compiles corpus P4 files for both backends and runs differential tests.

    Args:
        name:        name of the batched kt_jvm_test target.
        tests:       list of p4c corpus test base names (e.g. "opassign1-bmv2").
                     Sources are taken from @p4c//testdata/p4_16_samples.
        local_tests: dict mapping test name → source package label for tests
                     with P4/STF files in the local repo (e.g. trace_tree tests).
        tags:        Bazel tags forwarded to the test.
        includes:    extra P4 file labels needed as #include dependencies.
    """
    data = [
        ":bmv2_driver",
    ]

    for test in tests:
        p4_src = "@p4c//testdata/p4_16_samples:" + test + ".p4"
        stf_src = "@p4c//testdata/p4_16_samples:" + test + ".stf"
        _add_test_genrules(test, p4_src, stf_src, includes, tags, data)

    for test, pkg in local_tests.items():
        p4_src = pkg + ":" + test + ".p4"
        stf_src = pkg + ":" + test + ".stf"
        _add_test_genrules(test, p4_src, stf_src, [], tags, data)

    kt_jvm_test(
        name = name,
        srcs = [
            "Bmv2DiffTest.kt",
            "Bmv2Runner.kt",
        ],
        test_class = "fourward.e2e.bmv2.Bmv2DiffTest",
        tags = tags,
        data = data,
        jvm_flags = [
            "-Dfourward.bmv2_diff_testcases=" +
            ",".join(sorted(tests + list(local_tests.keys()))),
        ],
        deps = [
            "//bazel:runfiles",
            "//simulator:ir_java_proto",
            "//simulator:p4info_java_proto",
            "//simulator:simulator_java_proto",
            "//simulator",
            "//stf",
            "@fourward_maven//:com_google_protobuf_protobuf_java",
            "@fourward_maven//:junit_junit",
        ],
    )

def _add_test_genrules(test, p4_src, stf_src, includes, tags, data):
    """Creates the BMv2 JSON, 4ward txtpb, and STF copy genrules for one test."""
    include_flags = "".join([
        " -I $$(dirname $(execpath " + inc + "))"
        for inc in includes
    ])

    # Compile to BMv2 JSON.
    native.genrule(
        name = test + "_json",
        srcs = [p4_src] + includes,
        outs = [test + ".json"],
        cmd = "$(execpath @p4c//:p4c_bmv2) -I $$(dirname $(execpath @p4c//p4include:core.p4))" + include_flags + " -o $@ $(execpath " + p4_src + ")",
        tags = tags,
        tools = [
            "@p4c//:p4c_bmv2",
            "@p4c//p4include:core.p4",
            "@p4c//p4include",
        ],
    )

    # Compile to 4ward PipelineConfig (needed for P4Info).
    fourward_pipeline(
        name = test,
        src = p4_src,
        out = test + ".txtpb",
        includes = includes,
        tags = tags,
    )

    native.genrule(
        name = test + "_stf",
        srcs = [stf_src],
        outs = [test + ".stf"],
        cmd = "cp $< $@",
        tags = tags,
    )

    data.extend([
        ":" + test + "_json",
        ":" + test,
        ":" + test + "_stf",
    ])
