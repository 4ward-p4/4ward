/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

#include "p4c_backend/options.h"

#include <string>

namespace P4::FourWard {

FourWardOptions::FourWardOptions() {
  registerOption(
      "-o", "file",
      [this](const char* arg) {
        outputFile = arg;
        return true;
      },
      "write the compiled pipeline to <file> "
      "(.txtpb for text-format, .binpb for binary)");
  registerOption(
      "--format", "native|p4runtime",
      [this](const char* arg) {
        std::string fmt(arg);
        if (fmt == "native") {
          format = Format::kNative;
        } else if (fmt == "p4runtime") {
          format = Format::kP4runtime;
        } else {
          ::P4::error(
              "4ward: unknown format '%1%' (expected 'native' or 'p4runtime')",
              fmt);
          return false;
        }
        return true;
      },
      "output message type for -o: 'native' (4ward PipelineConfig) "
      "or 'p4runtime' (P4Runtime ForwardingPipelineConfig)");
  registerOption(
      "--out-p4info", "file",
      [this](const char* arg) {
        outP4Info = arg;
        return true;
      },
      "write p4.config.v1.P4Info to <file> "
      "(.txtpb for text-format, .binpb for binary)");
  registerOption(
      "--out-p4-device-config", "file",
      [this](const char* arg) {
        outP4DeviceConfig = arg;
        return true;
      },
      "write fourward.ir.DeviceConfig to <file> "
      "(.txtpb for text-format, .binpb for binary)");
}

}  // namespace P4::FourWard
