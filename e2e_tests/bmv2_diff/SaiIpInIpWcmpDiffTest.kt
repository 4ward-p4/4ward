package fourward.e2e.bmv2

import fourward.simulator.Simulator
import fourward.stf.StfFile
import fourward.stf.hex
import fourward.stf.installStfEntries
import fourward.stf.loadPipelineConfig
import org.junit.Assert
import org.junit.Test

/**
 * Differential test: IP-in-IP forwarding through a 2-member WCMP group on SAI P4 middleblock.
 *
 * Verifies that 4ward and BMv2 produce identical outputs (one per WCMP member) when forwarding an
 * IP-in-IP packet (outer IPv4 with proto=4 wrapping inner IPv4) through an action selector.
 *
 * The test installs routing entries inline (no STF file on disk) and uses the pre-compiled
 * sai_middleblock BMv2 artifacts. 4ward explores all WCMP paths via fork-all; BMv2 uses round-robin
 * exploration (one packet per member with the group temporarily reduced to 1).
 */
class SaiIpInIpWcmpDiffTest {

  @Test
  fun `sai ip-in-ip wcmp routing matches bmv2`() {
    val repoRoot = fourward.bazel.repoRoot
    val jsonPath = repoRoot.resolve("e2e_tests/sai_p4/sai_middleblock.json")
    val configPath = repoRoot.resolve("e2e_tests/sai_p4/sai_middleblock_bmv2.txtpb")
    val driverBinary = repoRoot.resolve("e2e_tests/bmv2_diff/bmv2_driver")

    val config = loadPipelineConfig(configPath)
    val stf = StfFile.parse(STF_LINES)

    // --- Run through 4ward ---
    val fourwardOutputs = mutableListOf<Pair<Int, ByteArray>>()
    val sim = Simulator()
    sim.loadPipeline(config)
    installStfEntries(sim, stf, config.p4Info)
    for (packet in stf.packets) {
      val result = sim.processPacket(packet.ingressPort, packet.payload)
      // Flatten all possible outcomes — each WCMP branch is a separate outcome.
      for (pkt in result.possibleOutcomes.flatten()) {
        fourwardOutputs.add(pkt.dataplaneEgressPort to pkt.payload.toByteArray())
      }
    }

    // --- Run through BMv2 ---
    val bmv2Outputs = mutableListOf<Pair<Int, ByteArray>>()
    Bmv2Runner(driverBinary, jsonPath, config.p4Info).use { bmv2 ->
      bmv2.installEntries(stf)
      for (packet in stf.packets) {
        // sendPacketExploring sends the packet once per WCMP member, temporarily reducing
        // the group to a single member so BMv2's hash always selects it.
        bmv2Outputs.addAll(bmv2.sendPacketExploring(packet.ingressPort, packet.payload))
      }
    }

    // --- Compare outputs ---
    // Sort by (port, payload hex) for deterministic comparison — cross-port ordering is
    // unspecified by both simulators.
    val sortKey: (Pair<Int, ByteArray>) -> String = { (port, payload) ->
      "%04d:%s".format(port, payload.hex())
    }
    val fourwardSorted = fourwardOutputs.sortedBy(sortKey)
    val bmv2Sorted = bmv2Outputs.sortedBy(sortKey)

    val mismatches = mutableListOf<String>()
    if (fourwardSorted.size != bmv2Sorted.size) {
      mismatches.add("Output count mismatch: 4ward=${fourwardSorted.size}, bmv2=${bmv2Sorted.size}")
    }
    for (i in 0 until minOf(fourwardSorted.size, bmv2Sorted.size)) {
      val (fPort, fPayload) = fourwardSorted[i]
      val (bPort, bPayload) = bmv2Sorted[i]
      if (fPort != bPort || !fPayload.contentEquals(bPayload)) {
        mismatches.add(
          "Output $i differs:\n" +
            "  4ward: port=$fPort payload=${fPayload.hex()}\n" +
            "  bmv2:  port=$bPort payload=${bPayload.hex()}"
        )
      }
    }

    if (mismatches.isNotEmpty()) {
      Assert.fail(mismatches.joinToString("\n"))
    }
  }

  companion object {
    // STF entries for a minimal IP-in-IP WCMP routing scenario:
    //   - Two router interfaces (port 1 and port 2) with distinct src/dst MACs.
    //   - A 2-member WCMP group (equal weight) pointing to the two interfaces.
    //   - An LPM route for 10.0.0.1/32 in the default VRF via the WCMP group.
    //
    // The l3_admit_table entry matches the packet's dst_mac exactly; the in_port
    // field is OPTIONAL and omitted here, which means "match any in_port" (wildcard).
    // In BMv2, P4 OPTIONAL compiles to ternary, so the absent field is encoded as
    // a wildcard ternary (all-zeros value + all-zeros mask) by Bmv2Runner.
    private val STF_LINES =
      listOf(
        // Admit the test packet to L3 routing by its dst_mac.
        "add l3_admit_table 1 dst_mac:0x000102030405&&&0xffffffffffff admit_to_l3()",
        // Two router interfaces: each sets the egress port and rewrites src_mac.
        "add router_interface_table router_interface_id:0x01 set_port_and_src_mac(port:0x01, src_mac:0xaabbccddee01)",
        "add router_interface_table router_interface_id:0x02 set_port_and_src_mac(port:0x02, src_mac:0xaabbccddee02)",
        // Neighbor (ARP) entries: (RIF, neighbor_id) → dst_mac rewrite.
        "add neighbor_table router_interface_id:0x01 neighbor_id:0xfe800000000000000000000000000001 set_dst_mac(dst_mac:0x112233445501)",
        "add neighbor_table router_interface_id:0x02 neighbor_id:0xfe800000000000000000000000000002 set_dst_mac(dst_mac:0x112233445502)",
        // Nexthop entries: each nexthop points to a RIF + neighbor.
        "add nexthop_table nexthop_id:0x01 set_ip_nexthop(router_interface_id:0x01, neighbor_id:0xfe800000000000000000000000000001)",
        "add nexthop_table nexthop_id:0x02 set_ip_nexthop(router_interface_id:0x02, neighbor_id:0xfe800000000000000000000000000002)",
        // WCMP: 2 members, 1 group (group IDs are 1-based in P4Runtime; 0 is reserved).
        "member wcmp_group_selector 0 set_nexthop_id nexthop_id=0x01",
        "member wcmp_group_selector 1 set_nexthop_id nexthop_id=0x02",
        "group wcmp_group_selector 1 0 1",
        "add wcmp_group_table wcmp_group_id:0x01 group=1",
        // LPM route: 10.0.0.1/32 in default VRF (id=0) → WCMP group.
        "add ipv4_table vrf_id:0x00 ipv4_dst:0x0a000001/32 set_wcmp_group_id(wcmp_group_id:0x01)",
        // IP-in-IP test packet: outer IPv4 (proto=4, dst=10.0.0.1, ttl=64) encapsulating
        // inner IPv4 (proto=6/TCP). Ingress on port 0 with dst_mac=00:01:02:03:04:05
        // (unicast MAC → triggers L3 routing after l3_admit_table hit).
        "packet 0 000102030405000a0b0c0d0e08004500002800000000400466d00a0000020a00000145000014000000004006f790c0a80101c0a80102",
      )
  }
}
