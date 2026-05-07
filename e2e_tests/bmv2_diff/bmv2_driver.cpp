// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0
//
// BMv2 driver binary for differential testing.
//
// Drives a BMv2 simple_switch instance via its C++ API (no Thrift).
// Reads line-based commands from stdin, outputs results to stdout.
//
// Commands:
//   TABLE_ADD <table> <action> <match_fields...> => <params...> [priority <N>]
//   TABLE_ADD_MEMBER <table> <member_handle> <match...> [priority <N>]
//   TABLE_ADD_GROUP <table> <group_handle> <match...> [priority <N>]
//   TABLE_SET_DEFAULT <table> <action> [params...]
//   ACT_PROF_ADD_MEMBER <profile> <action> <params...>
//   ACT_PROF_CREATE_GROUP <profile>
//   ACT_PROF_ADD_MEMBER_TO_GROUP <profile> <member_handle> <group_handle>
//   ACT_PROF_REMOVE_MEMBER_FROM_GROUP <profile> <member_handle> <group_handle>
//   MIRRORING_ADD <session_id> <port>
//   MC_MGRP_CREATE <gid>
//   MC_NODE_CREATE <rid> <ports...>
//   MC_NODE_ASSOCIATE <gid> <node_handle>
//   PACKET <port> <hex_payload>
//   BENCHMARK <count> <expected_outputs> <port> <hex_payload>
//
// Output:
//   OUTPUT <port> <hex_payload>
//   DONE
//   BENCHMARK <count> packets, <received> outputs, <ms> ms, <pps> pps
//   ERROR <message>

#include <bm/bm_sim/options_parse.h>
#include <bm/bm_sim/simple_pre.h>
#include <bm/bm_sim/simple_pre_lag.h>
#include <bm/bm_sim/switch.h>
#include <simple_switch.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

using bm::ActionData;
using bm::MatchErrorCode;
using bm::MatchKeyParam;
// BMv2 handle types (both uint32_t under the hood).
using mbr_hdl_t = unsigned int;
using grp_hdl_t = unsigned int;

// Hex encoding/decoding helpers.
static std::string to_hex(const char* buf, int len) {
  std::ostringstream oss;
  for (int i = 0; i < len; i++)
    oss << std::hex << std::setw(2) << std::setfill('0')
        << (static_cast<unsigned>(buf[i]) & 0xff);
  return oss.str();
}

static int hex_nibble(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return 0;
}

static std::vector<char> from_hex(const std::string& hex) {
  std::vector<char> bytes;
  bytes.reserve(hex.size() / 2);
  for (size_t i = 0; i + 1 < hex.size(); i += 2) {
    bytes.push_back(
        static_cast<char>((hex_nibble(hex[i]) << 4) | hex_nibble(hex[i + 1])));
  }
  return bytes;
}

// Split a string on whitespace.
static std::vector<std::string> split(const std::string& s) {
  std::vector<std::string> tokens;
  std::istringstream iss(s);
  std::string tok;
  while (iss >> tok) tokens.push_back(tok);
  return tokens;
}

// Custom DevMgr for packet injection without pcap/raw sockets.
// Output is handled by Switch::set_transmit_fn() instead of through the DevMgr,
// because DevMgrIface::port_send() checks an internal port map that our stub
// port_add_() doesn't populate.
class CallbackDevMgr : public bm::DevMgrIface {
 public:
  CallbackDevMgr() { p_monitor = bm::PortMonitorIface::make_dummy(); }

  void inject(port_t port, const char* buf, int len) {
    if (handler_) handler_(port, buf, len, cookie_);
  }

 private:
  ReturnCode port_add_(const std::string& /*iface_name*/, port_t /*port_num*/,
                       const PortExtras& /*extras*/) override {
    return ReturnCode::SUCCESS;
  }

  ReturnCode port_remove_(port_t /*port_num*/) override {
    return ReturnCode::SUCCESS;
  }

  void transmit_fn_(port_t /*port*/, const char* /*buf*/,
                    int /*len*/) override {}

  void start_() override {}

  ReturnCode set_packet_handler_(const PacketHandler& handler,
                                 void* cookie) override {
    handler_ = handler;
    cookie_ = cookie;
    return ReturnCode::SUCCESS;
  }

  bool port_is_up_(port_t /*port*/) const override { return true; }

  std::map<port_t, PortInfo> get_port_info_() const override { return {}; }

  PacketHandler handler_{nullptr};
  void* cookie_{nullptr};
};

// Wait for the pipeline to drain after injecting a packet.
// SimpleSwitch processes packets asynchronously in worker threads.
// We wait until no new outputs arrive for a short period.
class OutputCollector {
 public:
  void add(int port, const char* buf, int len) {
    {
      std::lock_guard<std::mutex> lock(mu_);
      outputs_.emplace_back(port, std::string(buf, len));
      last_output_time_ = std::chrono::steady_clock::now();
    }
    cv_.notify_one();
  }

  // Wait for outputs to settle, then return them.
  std::vector<std::pair<int, std::string>> drain() {
    // Wait until no output has arrived for 50ms, with a max timeout of 500ms.
    auto start = std::chrono::steady_clock::now();
    std::unique_lock<std::mutex> lock(mu_);
    // Reset so the 50ms quiet period starts from now, not from a previous
    // packet's output time.
    last_output_time_ = start;
    while (true) {
      auto now = std::chrono::steady_clock::now();
      auto since_last = std::chrono::duration_cast<std::chrono::milliseconds>(
                            now - last_output_time_)
                            .count();
      auto total =
          std::chrono::duration_cast<std::chrono::milliseconds>(now - start)
              .count();
      if (since_last >= 50 || total >= 500) break;
      cv_.wait_for(lock, std::chrono::milliseconds(50));
    }
    auto result = std::move(outputs_);
    outputs_.clear();
    return result;
  }

 private:
  std::mutex mu_;
  std::condition_variable cv_;
  std::vector<std::pair<int, std::string>> outputs_;
  std::chrono::steady_clock::time_point last_output_time_ =
      std::chrono::steady_clock::now();
};

static OutputCollector collector;

// Parse a match key field. Supports:
//   exact: hex value (e.g. 0x0a000001 or plain decimal)
//   lpm:   value/prefix_len
//   ternary: value&&&mask
static MatchKeyParam parse_match_field(const std::string& tok) {
  // Ternary: value&&&mask
  auto ternary_pos = tok.find("&&&");
  if (ternary_pos != std::string::npos) {
    auto val = from_hex(tok.substr(0, ternary_pos));
    auto mask = from_hex(tok.substr(ternary_pos + 3));
    return MatchKeyParam(MatchKeyParam::Type::TERNARY,
                         std::string(val.begin(), val.end()),
                         std::string(mask.begin(), mask.end()));
  }
  // LPM: value/prefix_len
  auto slash_pos = tok.find('/');
  if (slash_pos != std::string::npos) {
    auto val = from_hex(tok.substr(0, slash_pos));
    int prefix_len = std::stoi(tok.substr(slash_pos + 1));
    return MatchKeyParam(MatchKeyParam::Type::LPM,
                         std::string(val.begin(), val.end()), prefix_len);
  }
  // Exact
  auto val = from_hex(tok);
  return MatchKeyParam(MatchKeyParam::Type::EXACT,
                       std::string(val.begin(), val.end()));
}

// Parse match keys and optional priority from tokens starting at `start`.
// Stops at "priority" keyword (consuming the next token as the value).
struct MatchAndPriority {
  std::vector<MatchKeyParam> keys;
  int bmv2_priority;  // negated for BMv2's lower-is-higher convention
};

static MatchAndPriority parse_match_and_priority(
    const std::vector<std::string>& tokens, size_t start) {
  std::vector<MatchKeyParam> keys;
  std::optional<int> priority;
  for (size_t i = start; i < tokens.size(); i++) {
    if (tokens[i] == "priority" && i + 1 < tokens.size()) {
      priority = std::stoi(tokens[i + 1]);
      break;
    }
    keys.push_back(parse_match_field(tokens[i]));
  }
  // BMv2 uses lower-value = higher-priority internally
  // (lookup_structures.cpp picks the matching entry with the minimum
  // priority field). The STF/P4Runtime convention is higher-value =
  // higher-priority. Negate to bridge the gap.
  int bmv2_priority = priority.has_value() ? -*priority : -1;
  return {std::move(keys), bmv2_priority};
}

// Parse action data parameters (hex strings after =>).
static ActionData parse_action_data(const std::vector<std::string>& params) {
  ActionData ad;
  for (const auto& p : params) {
    auto bytes = from_hex(p);
    ad.push_back_action_data(bm::Data(bytes.data(), bytes.size()));
  }
  return ad;
}

int main(int argc, char* argv[]) {
  if (argc < 2) {
    std::cerr << "Usage: bmv2_driver <json_config>\n";
    return 1;
  }

  std::string json_path = argv[1];

  // Create SimpleSwitch without going through OptionsParser (which tries to
  // parse argv and open network interfaces).
  auto sw = std::make_unique<SimpleSwitch>(/*enable_swap=*/false);

  // Register transmit callback — bypasses DevMgr::port_send() which requires
  // ports to be registered in the internal port map.
  sw->set_transmit_fn([](bm::DevMgrIface::port_t port, bm::packet_id_t /*id*/,
                         const char* buf,
                         int len) { collector.add(port, buf, len); });

  // Use our custom DevMgr for packet injection (no pcap/raw sockets).
  auto dev_mgr = std::make_unique<CallbackDevMgr>();
  auto* dev_mgr_ptr = dev_mgr.get();

  // Initialize from JSON config file.
  auto dummy_transport =
      std::shared_ptr<bm::TransportIface>(bm::TransportIface::make_dummy());

  // Build OptionsParser manually instead of parsing argv.
  bm::OptionsParser parser;
  parser.config_file_path = json_path;
  parser.console_logging = false;
  parser.no_p4 = false;

  int status =
      sw->init_from_options_parser(parser, dummy_transport, std::move(dev_mgr));
  if (status != 0) {
    std::cerr << "ERROR: init_from_options_parser failed: " << status << "\n";
    return 1;
  }

  sw->start_and_return();

  auto pre = sw->get_component<bm::McSimplePreLAG>();

  // Signal readiness.
  std::cout << "READY" << std::endl;

  // Command loop.
  std::string line;
  while (std::getline(std::cin, line)) {
    auto tokens = split(line);
    if (tokens.empty()) continue;

    const auto& cmd = tokens[0];

    if (cmd == "TABLE_ADD") {
      // TABLE_ADD <table> <action> <match...> => <params...> [priority <N>]
      if (tokens.size() < 3) {
        std::cout << "ERROR bad TABLE_ADD" << std::endl;
        continue;
      }
      auto table = tokens[1];
      auto action = tokens[2];

      // Find the => separator.
      size_t arrow_idx = 0;
      for (size_t i = 3; i < tokens.size(); i++) {
        if (tokens[i] == "=>") {
          arrow_idx = i;
          break;
        }
      }

      // Match keys: tokens[3..arrow_idx)
      std::vector<MatchKeyParam> match_keys;
      size_t match_end = arrow_idx > 0 ? arrow_idx : tokens.size();
      for (size_t i = 3; i < match_end; i++) {
        match_keys.push_back(parse_match_field(tokens[i]));
      }

      // Action params: tokens after => (excluding priority)
      std::vector<std::string> params;
      std::optional<int> priority;
      if (arrow_idx > 0) {
        for (size_t i = arrow_idx + 1; i < tokens.size(); i++) {
          if (tokens[i] == "priority" && i + 1 < tokens.size()) {
            priority = std::stoi(tokens[i + 1]);
            break;
          }
          params.push_back(tokens[i]);
        }
      }

      auto ad = parse_action_data(params);
      bm::entry_handle_t handle;
      // Negate priority for BMv2's lower-is-higher convention; see
      // parse_match_and_priority for details.
      int bmv2_priority = priority.has_value() ? -*priority : -1;
      auto rc = sw->mt_add_entry(0, table, match_keys, action, std::move(ad),
                                 &handle, bmv2_priority);
      if (rc != MatchErrorCode::SUCCESS) {
        std::cout << "ERROR TABLE_ADD failed: " << static_cast<int>(rc)
                  << std::endl;
      } else {
        std::cout << "OK" << std::endl;
      }

    } else if (cmd == "TABLE_SET_DEFAULT") {
      // TABLE_SET_DEFAULT <table> <action> [params...]
      if (tokens.size() < 3) {
        std::cout << "ERROR bad TABLE_SET_DEFAULT" << std::endl;
        continue;
      }
      auto table = tokens[1];
      auto action = tokens[2];
      std::vector<std::string> params(tokens.begin() + 3, tokens.end());
      auto ad = parse_action_data(params);
      auto rc = sw->mt_set_default_action(0, table, action, std::move(ad));
      if (rc != MatchErrorCode::SUCCESS) {
        std::cout << "ERROR TABLE_SET_DEFAULT failed: " << static_cast<int>(rc)
                  << std::endl;
      } else {
        std::cout << "OK" << std::endl;
      }

    } else if (cmd == "MIRRORING_ADD") {
      // MIRRORING_ADD <session_id> <port>
      if (tokens.size() < 3) {
        std::cout << "ERROR bad MIRRORING_ADD" << std::endl;
        continue;
      }
      int session_id = std::stoi(tokens[1]);
      int port = std::stoi(tokens[2]);
      SimpleSwitch::MirroringSessionConfig config = {};
      config.egress_port = port;
      config.egress_port_valid = true;
      sw->mirroring_add_session(session_id, config);
      std::cout << "OK" << std::endl;

    } else if (cmd == "MC_MGRP_CREATE") {
      // MC_MGRP_CREATE <gid>
      if (tokens.size() < 2) {
        std::cout << "ERROR bad MC_MGRP_CREATE" << std::endl;
        continue;
      }
      bm::McSimplePre::mgrp_hdl_t mgrp_hdl;
      auto rc = pre->mc_mgrp_create(std::stoi(tokens[1]), &mgrp_hdl);
      if (rc != bm::McSimplePre::McReturnCode::SUCCESS) {
        std::cout << "ERROR MC_MGRP_CREATE failed" << std::endl;
      } else {
        std::cout << "OK " << mgrp_hdl << std::endl;
      }

    } else if (cmd == "MC_NODE_CREATE") {
      // MC_NODE_CREATE <rid> <ports...>
      if (tokens.size() < 3) {
        std::cout << "ERROR bad MC_NODE_CREATE" << std::endl;
        continue;
      }
      int rid = std::stoi(tokens[1]);
      bm::McSimplePre::PortMap port_map;
      for (size_t i = 2; i < tokens.size(); i++) {
        int p = std::stoi(tokens[i]);
        if (p >= 0 && static_cast<size_t>(p) < port_map.size()) port_map[p] = 1;
      }
      bm::McSimplePre::l1_hdl_t l1_hdl;
      // McSimplePreLAG::mc_node_create requires a LagMap; pass empty (no LAG).
      bm::McSimplePreLAG::LagMap lag_map;
      auto rc = pre->mc_node_create(rid, port_map, lag_map, &l1_hdl);
      if (rc != bm::McSimplePre::McReturnCode::SUCCESS) {
        std::cout << "ERROR MC_NODE_CREATE failed" << std::endl;
      } else {
        std::cout << "OK " << l1_hdl << std::endl;
      }

    } else if (cmd == "MC_NODE_ASSOCIATE") {
      // MC_NODE_ASSOCIATE <mgrp_hdl> <node_handle>
      if (tokens.size() < 3) {
        std::cout << "ERROR bad MC_NODE_ASSOCIATE" << std::endl;
        continue;
      }
      auto mgrp_hdl =
          static_cast<bm::McSimplePre::mgrp_hdl_t>(std::stoi(tokens[1]));
      auto l1_hdl =
          static_cast<bm::McSimplePre::l1_hdl_t>(std::stoi(tokens[2]));
      auto rc = pre->mc_node_associate(mgrp_hdl, l1_hdl);
      if (rc != bm::McSimplePre::McReturnCode::SUCCESS) {
        std::cout << "ERROR MC_NODE_ASSOCIATE failed" << std::endl;
      } else {
        std::cout << "OK" << std::endl;
      }

    } else if (cmd == "ACT_PROF_ADD_MEMBER") {
      // ACT_PROF_ADD_MEMBER <profile> <action> <params...>
      // Returns: OK <member_handle>
      if (tokens.size() < 3) {
        std::cout << "ERROR bad ACT_PROF_ADD_MEMBER" << std::endl;
        continue;
      }
      auto profile = tokens[1];
      auto action = tokens[2];
      std::vector<std::string> params(tokens.begin() + 3, tokens.end());
      auto ad = parse_action_data(params);
      mbr_hdl_t mbr;
      auto rc =
          sw->mt_act_prof_add_member(0, profile, action, std::move(ad), &mbr);
      if (rc != MatchErrorCode::SUCCESS) {
        std::cout << "ERROR ACT_PROF_ADD_MEMBER failed: "
                  << static_cast<int>(rc) << std::endl;
      } else {
        std::cout << "OK " << mbr << std::endl;
      }

    } else if (cmd == "ACT_PROF_CREATE_GROUP") {
      // ACT_PROF_CREATE_GROUP <profile>
      // Returns: OK <group_handle>
      if (tokens.size() < 2) {
        std::cout << "ERROR bad ACT_PROF_CREATE_GROUP" << std::endl;
        continue;
      }
      auto profile = tokens[1];
      grp_hdl_t grp;
      auto rc = sw->mt_act_prof_create_group(0, profile, &grp);
      if (rc != MatchErrorCode::SUCCESS) {
        std::cout << "ERROR ACT_PROF_CREATE_GROUP failed: "
                  << static_cast<int>(rc) << std::endl;
      } else {
        std::cout << "OK " << grp << std::endl;
      }

    } else if (cmd == "ACT_PROF_ADD_MEMBER_TO_GROUP") {
      // ACT_PROF_ADD_MEMBER_TO_GROUP <profile> <member_handle> <group_handle>
      if (tokens.size() < 4) {
        std::cout << "ERROR bad ACT_PROF_ADD_MEMBER_TO_GROUP" << std::endl;
        continue;
      }
      auto profile = tokens[1];
      auto mbr = static_cast<mbr_hdl_t>(std::stoi(tokens[2]));
      auto grp = static_cast<grp_hdl_t>(std::stoi(tokens[3]));
      auto rc = sw->mt_act_prof_add_member_to_group(0, profile, mbr, grp);
      if (rc != MatchErrorCode::SUCCESS) {
        std::cout << "ERROR ACT_PROF_ADD_MEMBER_TO_GROUP failed: "
                  << static_cast<int>(rc) << std::endl;
      } else {
        std::cout << "OK" << std::endl;
      }

    } else if (cmd == "ACT_PROF_REMOVE_MEMBER_FROM_GROUP") {
      // ACT_PROF_REMOVE_MEMBER_FROM_GROUP <profile> <member_handle>
      //                                   <group_handle>
      if (tokens.size() < 4) {
        std::cout << "ERROR bad ACT_PROF_REMOVE_MEMBER_FROM_GROUP" << std::endl;
        continue;
      }
      auto profile = tokens[1];
      auto mbr = static_cast<mbr_hdl_t>(std::stoi(tokens[2]));
      auto grp = static_cast<grp_hdl_t>(std::stoi(tokens[3]));
      auto rc = sw->mt_act_prof_remove_member_from_group(0, profile, mbr, grp);
      if (rc != MatchErrorCode::SUCCESS) {
        std::cout << "ERROR ACT_PROF_REMOVE_MEMBER_FROM_GROUP failed: "
                  << static_cast<int>(rc) << std::endl;
      } else {
        std::cout << "OK" << std::endl;
      }

    } else if (cmd == "TABLE_ADD_MEMBER") {
      // TABLE_ADD_MEMBER <table> <member_handle> <match...> [priority <N>]
      if (tokens.size() < 3) {
        std::cout << "ERROR bad TABLE_ADD_MEMBER" << std::endl;
        continue;
      }
      auto table = tokens[1];
      auto mbr = static_cast<mbr_hdl_t>(std::stoi(tokens[2]));
      auto [match_keys, bmv2_priority] = parse_match_and_priority(tokens, 3);
      bm::entry_handle_t handle;
      auto rc = sw->mt_indirect_add_entry(0, table, match_keys, mbr, &handle,
                                          bmv2_priority);
      if (rc != MatchErrorCode::SUCCESS) {
        std::cout << "ERROR TABLE_ADD_MEMBER failed: " << static_cast<int>(rc)
                  << std::endl;
      } else {
        std::cout << "OK" << std::endl;
      }

    } else if (cmd == "TABLE_ADD_GROUP") {
      // TABLE_ADD_GROUP <table> <group_handle> <match...> [priority <N>]
      if (tokens.size() < 3) {
        std::cout << "ERROR bad TABLE_ADD_GROUP" << std::endl;
        continue;
      }
      auto table = tokens[1];
      auto grp = static_cast<grp_hdl_t>(std::stoi(tokens[2]));
      auto [match_keys, bmv2_priority] = parse_match_and_priority(tokens, 3);
      bm::entry_handle_t handle;
      auto rc = sw->mt_indirect_ws_add_entry(0, table, match_keys, grp, &handle,
                                             bmv2_priority);
      if (rc != MatchErrorCode::SUCCESS) {
        std::cout << "ERROR TABLE_ADD_GROUP failed: " << static_cast<int>(rc)
                  << std::endl;
      } else {
        std::cout << "OK" << std::endl;
      }

    } else if (cmd == "PACKET") {
      // PACKET <port> <hex_payload>
      if (tokens.size() < 3) {
        std::cout << "ERROR bad PACKET" << std::endl;
        continue;
      }
      int port = std::stoi(tokens[1]);
      auto payload = from_hex(tokens[2]);

      // Inject via the custom DevMgr.
      dev_mgr_ptr->inject(port, payload.data(), payload.size());

      // Wait for pipeline to process.
      auto outputs = collector.drain();

      for (const auto& [out_port, out_data] : outputs) {
        std::cout << "OUTPUT " << out_port << " "
                  << to_hex(out_data.data(), out_data.size()) << std::endl;
      }
      std::cout << "DONE" << std::endl;

    } else if (cmd == "BENCHMARK") {
      // BENCHMARK <count> <expected_outputs> <port> <hex_payload>
      // Injects <count> copies of the same packet and waits for
      // <expected_outputs> total output packets. Reports wall-clock
      // throughput without per-packet drain overhead.
      if (tokens.size() < 5) {
        std::cout << "ERROR bad BENCHMARK" << std::endl;
        continue;
      }
      int count = std::stoi(tokens[1]);
      int expected = std::stoi(tokens[2]);
      int port = std::stoi(tokens[3]);
      auto payload = from_hex(tokens[4]);

      // Drain any stale outputs.
      collector.drain();

      auto start = std::chrono::steady_clock::now();

      for (int i = 0; i < count; i++) {
        dev_mgr_ptr->inject(port, payload.data(), payload.size());
      }

      // Poll until we have all expected outputs (or timeout).
      int received = 0;
      auto deadline = start + std::chrono::seconds(30);
      while (received < expected &&
             std::chrono::steady_clock::now() < deadline) {
        auto batch = collector.drain();
        received += batch.size();
      }

      auto end = std::chrono::steady_clock::now();
      double elapsed_ms =
          std::chrono::duration_cast<std::chrono::microseconds>(end - start)
              .count() /
          1000.0;
      double pps = count / elapsed_ms * 1000;

      std::cout << "BENCHMARK " << count << " packets, " << received
                << " outputs, " << std::fixed << std::setprecision(1)
                << elapsed_ms << " ms, " << std::setprecision(0) << pps
                << " pps" << std::endl;

    } else {
      std::cout << "ERROR unknown command: " << cmd << std::endl;
    }
  }

  return 0;
}
