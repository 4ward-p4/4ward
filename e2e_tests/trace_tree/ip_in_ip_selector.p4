/* ip_in_ip_selector.p4 — action selector with an IP-in-IP (proto=4) packet.
 *
 * Parses Ethernet → outer IPv4 → inner IPv4 (when proto=4). Forwards based on
 * the outer destination address via a 2-member action selector, verifying that
 * 4ward explores both branches with the inner header intact.
 */

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
    ipv4_t     outer_ipv4;
    ipv4_t     inner_ipv4;
}

struct metadata_t {}

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            0x0800: parse_outer_ipv4;
            default: accept;
        }
    }
    state parse_outer_ipv4 {
        pkt.extract(hdr.outer_ipv4);
        transition select(hdr.outer_ipv4.protocol) {
            4: parse_inner_ipv4;
            default: accept;
        }
    }
    state parse_inner_ipv4 {
        pkt.extract(hdr.inner_ipv4);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t smeta) {

    action set_port(bit<9> port) { smeta.egress_spec = port; }
    action drop() { mark_to_drop(smeta); }

    action_selector(HashAlgorithm.crc16, 32w1024, 32w14) sel;

    table fwd {
        key = {
            hdr.outer_ipv4.dstAddr : lpm;
            hdr.outer_ipv4.srcAddr : selector;
        }
        actions     = { set_port; drop; }
        implementation = sel;
        default_action = drop();
    }

    apply {
        if (hdr.outer_ipv4.isValid()) {
            fwd.apply();
        }
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply {
        pkt.emit(hdr.ethernet);
        pkt.emit(hdr.outer_ipv4);
        pkt.emit(hdr.inner_ipv4);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
