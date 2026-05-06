/* round_trip.p4 — synthetic fixture exercising every entity type 4ward
 * supports that has translatable values, plus a few that don't (for
 * regression coverage of the pass-through cases).
 *
 * Read-write symmetry per P4Runtime spec §11.2: an entity read back must be
 * canonically equivalent to what was written. This fixture lets a single
 * test verify that property end-to-end for every entity type 4ward exposes:
 *
 *   - TABLE_ENTRY                          (translated match fields)
 *   - ACTION_PROFILE_MEMBER                (translated action params)
 *   - ACTION_PROFILE_GROUP                 (no translated content)
 *   - DIRECT_COUNTER_ENTRY                 (embeds TableEntry — match fields)
 *   - DIRECT_METER_ENTRY                   (embeds TableEntry — match fields)
 *   - COUNTER_ENTRY / METER_ENTRY          (no translated content)
 *   - REGISTER_ENTRY                       (no translated content)
 *   - PACKET_REPLICATION_ENGINE_ENTRY      (translated replica.port)
 *
 * VALUE_SET_ENTRY with translated values is omitted: the P4 language
 * doesn't allow `select` over a translated newtype directly, so a
 * `value_set<port_id_t>` can be declared but not matched against. The
 * translation gap exists in theory but is unreachable from any
 * compilable P4 source today.
 */

#include <core.p4>

// Use the SAI-style forked v1model so standard_metadata.ingress_port has the
// translated `port_id_t` type. This is what makes p4c-4ward derive
// port_type_name = "port_id_t" in the IR, which in turn causes the simulator
// to instantiate a PortTranslator. Without that, PRE replica ports pass
// through untranslated and the symmetry property holds vacuously.
#define PORT_BITWIDTH 9
#include "v1model_sai.p4"

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }
struct metadata_t {}

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

    // --- Externs that hold per-table translated state. ---
    direct_counter(CounterType.packets) translated_table_counter;
    direct_meter<bit<32>>(MeterType.bytes) translated_table_meter;

    // Indirect counters/meters/registers: no embedded table entry, no
    // translation required. Included to verify the pass-through path
    // remains lossless.
    counter(8, CounterType.packets) plain_counter;
    meter(8, MeterType.bytes) plain_meter;
    register<bit<32>>(8) plain_register;

    // Action profile: members carry translated action params via `forward`.
    action_profile(8) ap;

    action drop() { mark_to_drop(smeta); }
    action forward(port_id_t port) {
        smeta.egress_spec = port;
    }

    // Table whose match field is the translated type. Both direct counter
    // and direct meter attach here, so reading/writing
    // DIRECT_{COUNTER,METER}_ENTRY exercises translation of the embedded
    // TableEntry's match fields.
    table translated_table {
        key = { smeta.ingress_port : exact; }
        actions = { forward; drop; NoAction; }
        default_action = NoAction;
        counters = translated_table_counter;
        meters = translated_table_meter;
        implementation = ap;
        size = 16;
    }

    apply {
        translated_table.apply();
        plain_counter.count(0);
        bit<32> meter_color;
        plain_meter.execute_meter<bit<32>>(0, meter_color);
        bit<32> reg_val;
        plain_register.read(reg_val, 0);
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
