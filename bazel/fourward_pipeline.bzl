"""Bazel rule for compiling P4 programs using p4c-4ward.

Usage:

    load("@fourward//bazel:fourward_pipeline.bzl", "fourward_pipeline")

    fourward_pipeline(
        name = "sai_middleblock",
        src = "//sai_p4/instantiations/google:middleblock.p4",
        out = "sai_middleblock.binpb",
        out_format = "p4runtime",
    )

The rule produces one or more proto files. Each output is independently
configurable; specify the outputs you need:

  - `out` + `out_format`: the combined pipeline config.
      - `out_format = "native"` (default): `fourward.PipelineConfig`
      - `out_format = "p4runtime"`: `p4.v1.ForwardingPipelineConfig`
  - `out_p4info`: standalone `p4.config.v1.P4Info`.
  - `out_p4_device_config`: standalone `fourward.DeviceConfig`
    (the payload 4ward places in `ForwardingPipelineConfig.p4_device_config`).

At least one output attribute must be set.

All output paths must end in `.txtpb` (text proto) or `.binpb` (binary
proto); any other extension is rejected at analysis time.
"""

load("@rules_cc//cc:find_cc_toolchain.bzl", "CC_TOOLCHAIN_TYPE", "find_cc_toolchain", "use_cc_toolchain")

_VALID_OUT_FORMATS = ["native", "p4runtime"]
_VALID_EXTENSIONS = (".txtpb", ".binpb")

# Each (attr_name, p4c_flag) pair maps an output attribute on the rule to the
# p4c-4ward CLI flag that produces that file. Keep in sync with p4c_backend/
# options.cpp.
_OUTPUT_SPECS = [
    ("out", "-o"),
    ("out_p4info", "--out-p4info"),
    ("out_p4_device_config", "--out-p4-device-config"),
]

def _fourward_pipeline_impl(ctx):
    outputs = []
    output_args = []
    for attr_name, flag in _OUTPUT_SPECS:
        file = getattr(ctx.outputs, attr_name)
        if file == None:
            continue
        if not file.basename.endswith(_VALID_EXTENSIONS):
            fail("fourward_pipeline: `{attr}` must end in .txtpb or .binpb, got '{name}'".format(
                attr = attr_name,
                name = file.basename,
            ))
        outputs.append(file)
        output_args += [flag, file.path]
    if not outputs:
        fail("fourward_pipeline: at least one of `{names}` must be set.".format(
            names = "`, `".join([name for name, _ in _OUTPUT_SPECS]),
        ))

    p4c_args = []
    for inc in ctx.files.includes:
        p4c_args += ["-I", inc.dirname]
    p4c_args += ["-I", ctx.file._p4include_anchor.dirname]

    # Workspace root of the target's repo, so `#include "path/from/root.p4"`
    # resolves in both in-repo builds AND BCR consumer builds (where our sources
    # live under external/<repo>+/). Avoids brittle `../../...` relative paths.
    p4c_args += ["-I", ctx.label.workspace_root or "."]
    for define in ctx.attr.defines:
        p4c_args.append("-D" + define)

    # `--format` modifies the `-o` output's message type; harmless when `-o`
    # is unset (p4c just ignores it).
    if ctx.attr.out_format == "p4runtime":
        p4c_args += ["--format", "p4runtime"]
    p4c_args += output_args
    p4c_args.append(ctx.file.src.path)

    inputs = depset(
        [ctx.file.src] + ctx.files.includes + ctx.files.extra_srcs,
        transitive = [ctx.attr._p4include[DefaultInfo].files],
    )

    # WORKAROUND for https://github.com/p4lang/p4c/issues/5618: p4c hardcodes
    # `popen("cc -E …")` for preprocessing with no flag to override the binary.
    # Define a shell function so the toolchain compiler answers to `cc`.
    # Once p4c supports `--cc <path>`, pass compiler_executable directly.
    cc_toolchain = find_cc_toolchain(ctx)
    p4c = ctx.executable._p4c_4ward
    ctx.actions.run_shell(
        outputs = outputs,
        inputs = inputs,
        command = """
            function cc () {{ "{cc}" "$@"; }}
            export -f cc
            "{p4c}" {p4c_args}
        """.format(
            cc = cc_toolchain.compiler_executable,
            p4c = p4c.path,
            p4c_args = " ".join(p4c_args),
        ),
        tools = depset(
            direct = [p4c],
            transitive = [cc_toolchain.all_files],
        ),
        use_default_shell_env = True,
        toolchain = CC_TOOLCHAIN_TYPE,
        mnemonic = "FourwardPipeline",
        progress_message = "Compiling %{input} to 4ward pipeline",
    )

    # Explicit `runfiles` is required by google3; matches @p4c//bazel:p4_library.bzl.
    return [DefaultInfo(
        files = depset(outputs),
        runfiles = ctx.runfiles(files = outputs),
    )]

fourward_pipeline = rule(
    implementation = _fourward_pipeline_impl,
    doc = "Compiles a P4 source file using p4c-4ward.",
    attrs = {
        "src": attr.label(
            mandatory = True,
            allow_single_file = [".p4"],
            doc = "P4 source file.",
        ),
        "out": attr.output(
            doc = "Combined pipeline config. Message type is controlled by " +
                  "`out_format`: `fourward.PipelineConfig` (native) or " +
                  "`p4.v1.ForwardingPipelineConfig` (p4runtime). Extension " +
                  "must be `.txtpb` or `.binpb`.",
        ),
        "out_format": attr.string(
            default = "native",
            doc = "Message type for `out`. " +
                  "`\"native\"` (default): `fourward.PipelineConfig`. " +
                  "`\"p4runtime\"`: `p4.v1.ForwardingPipelineConfig` " +
                  "(suitable for `SetForwardingPipelineConfig`). " +
                  "Ignored when `out` is unset.",
            values = _VALID_OUT_FORMATS,
        ),
        "out_p4info": attr.output(
            doc = "Standalone `p4.config.v1.P4Info` output. Extension must " +
                  "be `.txtpb` or `.binpb`.",
        ),
        "out_p4_device_config": attr.output(
            doc = "Standalone `fourward.DeviceConfig` output — the payload " +
                  "4ward places in `ForwardingPipelineConfig.p4_device_config`. " +
                  "Extension must be `.txtpb` or `.binpb`.",
        ),
        "includes": attr.label_list(
            allow_files = True,
            doc = "P4 files whose directories are added to the include path via -I. " +
                  "Useful for shadowing standard library files (e.g. a modified v1model.p4).",
        ),
        "extra_srcs": attr.label_list(
            allow_files = True,
            doc = "Additional file dependencies (e.g. filegroups) that don't need -I flags. " +
                  "Useful when the P4 source uses relative #include paths.",
        ),
        "defines": attr.string_list(
            doc = "Preprocessor defines passed to p4c via -D.",
        ),
        "_p4c_4ward": attr.label(
            default = "@fourward//p4c_backend:p4c-4ward",
            executable = True,
            cfg = "exec",
        ),
        "_p4include_anchor": attr.label(
            default = "@p4c//p4include:core.p4",
            allow_single_file = True,
        ),
        "_p4include": attr.label(
            default = "@p4c//p4include",
        ),
    },
    toolchains = use_cc_toolchain(),
)
