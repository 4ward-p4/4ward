// fuzzer_table.p4 — v1model program for P4 fuzzer testing.
//
// Exercises multiple match kinds (exact, ternary, LPM) and an action selector.
// All tables and action refs have @proto_id annotations required by PDPI.

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

header ipv4_t {
    bit<4>  version;
    bit<4>  ihl;
    bit<8>  diffserv;
    bit<16> totalLen;
    bit<16> identification;
    bit<3>  flags;
    bit<13> fragOffset;
    bit<8>  ttl;
    bit<8>  protocol;
    bit<16> hdrChecksum;
    bit<32> srcAddr;
    bit<32> dstAddr;
}

struct headers_t {
    ethernet_t ethernet;
    ipv4_t ipv4;
}

struct metadata_t {}

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            0x0800: parse_ipv4;
            default: accept;
        }
    }
    state parse_ipv4 {
        pkt.extract(hdr.ipv4);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t smeta) {

    @proto_id(1)
    action drop() { mark_to_drop(smeta); }

    @proto_id(2)
    action forward(bit<9> port) { smeta.egress_spec = port; }

    // Exact-match table.
    table ethertype_table {
        key = { hdr.ethernet.etherType : exact; }
        actions = {
            @proto_id(1) forward;
            @proto_id(2) drop;
        }
        default_action = drop();
        size = 1024;
    }

    // Ternary-match table.
    table acl_table {
        key = {
            hdr.ethernet.etherType : ternary;
            hdr.ethernet.dstAddr   : ternary;
        }
        actions = {
            @proto_id(1) forward;
            @proto_id(2) drop;
        }
        default_action = drop();
        size = 512;
    }

    // LPM table.
    table ipv4_lpm {
        key = { hdr.ipv4.dstAddr : lpm; }
        actions = {
            @proto_id(1) forward;
            @proto_id(2) drop;
        }
        default_action = drop();
        size = 256;
    }

    apply {
        ethertype_table.apply();
        if (hdr.ipv4.isValid()) {
            ipv4_lpm.apply();
        }
        acl_table.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply {
        pkt.emit(hdr.ethernet);
        pkt.emit(hdr.ipv4);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
