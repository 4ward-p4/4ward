// packet_length.p4 — egress sees standard_metadata.packet_length adjusted by
// ingress setValid()/setInvalid(), as in BMv2 simple_switch.
//
// BMv2 keeps the packet length in a per-packet register that add_header /
// remove_header (setValid / setInvalid) adjust by the header's size, and
// reloads standard_metadata.packet_length from it at egress entry. SAI P4
// relies on this to compute encap lengths (e.g. gre_encap.p4 after vlan_untag
// pops a VLAN tag in ingress).
//
// Ingress pops an optional VLAN tag (-4 bytes) and adds an 8-byte len header
// (+8 bytes). The len header records the packet_length seen by ingress and by
// egress, so the STF expectations pin down both values.

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
  bit<48> dst_addr;
  bit<48> src_addr;
  bit<16> ether_type;
}

// Like SAI P4, the VLAN header includes the ether_type that follows the tag.
header vlan_t {
  bit<16> tci;
  bit<16> ether_type;
}

header len_t {
  bit<32> ingress_packet_length;
  bit<32> egress_packet_length;
}

struct headers_t {
  ethernet_t ethernet;
  vlan_t vlan;
  len_t len;
}

struct metadata_t {
  bit<32> ingress_packet_length;
}

parser MyParser(packet_in pkt, out headers_t hdr, inout metadata_t meta,
                inout standard_metadata_t standard_metadata) {
  state start {
    pkt.extract(hdr.ethernet);
    transition select(hdr.ethernet.ether_type) {
      0x8100: parse_vlan;
      default: accept;
    }
  }
  state parse_vlan {
    pkt.extract(hdr.vlan);
    transition accept;
  }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) {
  apply {}
}

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t standard_metadata) {
  apply {
    meta.ingress_packet_length = standard_metadata.packet_length;
    if (hdr.vlan.isValid()) {
      hdr.ethernet.ether_type = hdr.vlan.ether_type;
      hdr.vlan.setInvalid();  // -4 bytes.
      // Already invalid: must not subtract again.
      hdr.vlan.setInvalid();
    }
    hdr.len.setValid();  // +8 bytes.
    // Already valid: must not add again.
    hdr.len.setValid();
    standard_metadata.egress_spec = 1;
  }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t standard_metadata) {
  apply {
    hdr.len.ingress_packet_length = meta.ingress_packet_length;
    hdr.len.egress_packet_length = standard_metadata.packet_length;
  }
}

control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) {
  apply {}
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
  apply {
    pkt.emit(hdr.ethernet);
    pkt.emit(hdr.vlan);
    pkt.emit(hdr.len);
  }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(),
         MyComputeChecksum(), MyDeparser()) main;
