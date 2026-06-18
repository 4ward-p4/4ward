/* resubmit_preserve_meta.p4 — verify ContinuationEvent.preserved_fields.
 *
 * First ingress pass (instance_type == 0): sets meta.tag = 0xBEEF and calls
 * resubmit_preserving_field_list(0). The simulator should emit a ContinuationEvent
 * with preserved_fields["tag"] = "48879" (0xBEEF in decimal).
 *
 * Second pass (instance_type == RESUBMIT = 6): tag is restored from the field list;
 * it's written into etherType so the preserved value is visible in the output packet.
 */

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }

struct metadata_t {
    @field_list(0)
    bit<16> tag;
}

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        pkt.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t smeta) {
    apply {
        smeta.egress_spec = 1;
        if (smeta.instance_type == 0) {
            meta.tag = 16w0xBEEF;
            resubmit_preserving_field_list(0);
        } else {
            // Second pass: write preserved tag into etherType for observability.
            hdr.ethernet.etherType = meta.tag;
        }
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) {
    apply {}
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
