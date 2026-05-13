package(default_visibility = ["//visibility:public"])

load("@com_github_grpc_grpc//bazel:cc_grpc_library.bzl", "cc_grpc_library")

proto_library(
    name = "gnmi_proto",
    srcs = ["gnmi.proto"],
    deps = [
        "@com_google_protobuf//:any_proto",
        "@com_google_protobuf//:descriptor_proto",
    ],
)

cc_proto_library(
    name = "gnmi_cc_proto",
    deps = [":gnmi_proto"],
)

cc_grpc_library(
    name = "gnmi_cc_grpc",
    srcs = [":gnmi_proto"],
    grpc_only = True,
    deps = [":gnmi_cc_proto"],
)
