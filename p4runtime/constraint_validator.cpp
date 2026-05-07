// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0
//
// Constraint validator subprocess for the 4ward P4Runtime server.
//
// Reads ConstraintRequest messages from stdin and writes ConstraintResponse
// messages to stdout, using 4-byte big-endian length-delimited framing
// (same as the simulator protocol).
//
// On LoadP4Info: parses P4Info into ConstraintInfo via p4-constraints.
// On ValidateEntry: checks a TableEntry against loaded constraints.

#include <cstdint>
#include <iostream>
#include <optional>
#include <string>
#include <utility>

#include "p4_constraints/backend/constraint_info.h"
#include "p4_constraints/backend/interpreter.h"
#include "p4runtime/constraint_validator.pb.h"

namespace {

// Reads a 4-byte big-endian length prefix from stdin, then reads that many
// bytes and parses them as a ConstraintRequest. Returns nullopt on EOF.
std::optional<fourward::constraints::ConstraintRequest> ReadRequest() {
  uint32_t length = 0;
  for (int i = 0; i < 4; ++i) {
    int byte = std::cin.get();
    if (byte == EOF) return std::nullopt;
    length = (length << 8) | static_cast<uint8_t>(byte);
  }
  std::string buffer(length, '\0');
  if (!std::cin.read(buffer.data(), length)) return std::nullopt;
  fourward::constraints::ConstraintRequest request;
  if (!request.ParseFromString(buffer)) return std::nullopt;
  return request;
}

// Writes a ConstraintResponse to stdout with a 4-byte big-endian length prefix.
void WriteResponse(const fourward::constraints::ConstraintResponse& response) {
  std::string bytes;
  response.SerializeToString(&bytes);
  uint32_t length = bytes.size();
  for (int i = 3; i >= 0; --i) {
    std::cout.put(static_cast<char>((length >> (i * 8)) & 0xFF));
  }
  std::cout.write(bytes.data(), bytes.size());
  std::cout.flush();
}

void WriteError(const std::string& message) {
  fourward::constraints::ConstraintResponse response;
  response.mutable_error()->set_message(message);
  WriteResponse(response);
}

}  // namespace

int main() {
  std::cerr << "constraint_validator starting" << std::endl;

  std::optional<p4_constraints::ConstraintInfo> constraint_info;

  while (auto request = ReadRequest()) {
    if (request->has_load_p4info()) {
      auto result =
          p4_constraints::P4ToConstraintInfo(request->load_p4info().p4info());
      if (!result.ok()) {
        WriteError(std::string(result.status().message()));
        continue;
      }
      constraint_info = std::move(*result);

      // Count tables and actions that have constraints.
      int constrained_tables = 0;
      int constrained_actions = 0;
      for (const auto& [id, table] : constraint_info->table_info_by_id) {
        if (table.constraint.has_value()) ++constrained_tables;
      }
      for (const auto& [id, action] : constraint_info->action_info_by_id) {
        if (action.constraint.has_value()) ++constrained_actions;
      }

      fourward::constraints::ConstraintResponse response;
      auto* load_response = response.mutable_load_p4info();
      load_response->set_constrained_tables(constrained_tables);
      load_response->set_constrained_actions(constrained_actions);
      WriteResponse(response);
    } else if (request->has_validate_entry()) {
      if (!constraint_info.has_value()) {
        WriteError("No P4Info loaded; send LoadP4Info first");
        continue;
      }
      auto reason = p4_constraints::ReasonEntryViolatesConstraint(
          request->validate_entry().entry(), *constraint_info);
      if (!reason.ok()) {
        WriteError(std::string(reason.status().message()));
        continue;
      }
      fourward::constraints::ConstraintResponse response;
      response.mutable_validate_entry()->set_violation(*reason);
      WriteResponse(response);
    } else {
      WriteError("Unknown request type");
    }
  }

  std::cerr << "constraint_validator exiting" << std::endl;
  return 0;
}
