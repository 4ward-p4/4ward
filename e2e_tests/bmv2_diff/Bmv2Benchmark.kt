package fourward.e2e.bmv2

import com.google.protobuf.TextFormat
import fourward.PipelineConfig
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Test
import p4.config.v1.P4InfoOuterClass

/**
 * BMv2 throughput benchmark on SAI P4 middleblock.
 *
 * Measures packets/sec through BMv2's simple_switch on the same SAI P4 program and workload as
 * [fourward.p4runtime.DataplaneBenchmark], enabling a head-to-head comparison.
 *
 * Run with: bazel test //e2e_tests/bmv2_diff:Bmv2Benchmark -c opt --test_output=streamed
 */
@Suppress("FunctionNaming", "MagicNumber")
class Bmv2Benchmark {

  @Test
  fun `bmv2 SAI P4 benchmark`() {
    val jsonPath = fourward.bazel.repoRoot.resolve("e2e_tests/sai_p4/sai_middleblock.json")
    val p4Info = loadP4Info("e2e_tests/sai_p4/sai_middleblock_bmv2.txtpb")
    val driverPath = fourward.bazel.repoRoot.resolve("e2e_tests/bmv2_diff/bmv2_driver")

    val cores = Runtime.getRuntime().availableProcessors()
    println()
    println("BMv2 SAI P4 middleblock benchmark ($cores cores)")
    println()

    // --- L3 forwarding (direct nexthop) ---
    println("L3 forwarding (VRF -> LPM -> nexthop -> rewrite):")
    println(HEADER)
    println(SEPARATOR)
    for (routes in listOf(100, 1_000, 10_000)) {
      val nexthops = minOf(routes, 100)
      BenchmarkRunner(driverPath, jsonPath, p4Info).use { runner ->
        runner.installL3(routes, nexthops, aclEntries = ACL_ENTRIES)
        runner.warmup(WARMUP_PACKETS)
        val pps = runner.benchmark(BENCHMARK_PACKETS)
        println("| %6d | %10.0f |".format(routes, pps))
      }
    }
    println(SEPARATOR)
    println()

    // --- WCMP x16 ---
    println("WCMP x16 (action selector, 16 members):")
    println(HEADER)
    println(SEPARATOR)
    BenchmarkRunner(driverPath, jsonPath, p4Info).use { runner ->
      runner.installL3(10_000, 100, wcmpMembers = 16, aclEntries = ACL_ENTRIES)
      runner.warmup(WARMUP_PACKETS)
      val pps = runner.benchmark(BENCHMARK_PACKETS)
      println("| %6d | %10.0f |".format(10_000, pps))
    }
    println(SEPARATOR)
  }

  // ===========================================================================
  // BenchmarkRunner — wraps bmv2_driver subprocess
  // ===========================================================================

  private class BenchmarkRunner(
    driverBinary: Path,
    jsonPath: Path,
    private val p4Info: P4InfoOuterClass.P4Info,
  ) : Closeable {
    private val process: Process
    private val writer: java.io.BufferedWriter
    private val reader: java.io.BufferedReader

    init {
      process =
        ProcessBuilder(driverBinary.toString(), jsonPath.toString())
          .redirectErrorStream(false)
          .start()
      writer = process.outputStream.bufferedWriter()
      reader = process.inputStream.bufferedReader()
      val ready = reader.readLine()
      check(ready == "READY") { "Expected READY from bmv2_driver, got: $ready" }
    }

    private fun send(cmd: String): String {
      writer.write(cmd)
      writer.newLine()
      writer.flush()
      val lines = mutableListOf<String>()
      while (true) {
        val line = reader.readLine() ?: break
        lines.add(line)
        val isTerminator =
          line.startsWith("OK") ||
            line.startsWith("ERROR") ||
            line == "DONE" ||
            line.startsWith("BENCHMARK")
        if (isTerminator) break
      }
      return lines.joinToString("\n")
    }

    private fun sendChecked(cmd: String) {
      val resp = send(cmd)
      check("OK" in resp || "BENCHMARK" in resp) { "$cmd failed: $resp" }
    }

    /** Install L3 forwarding entries (VRF + routes + nexthops + RIF + neighbor). */
    fun installL3(routes: Int, nexthops: Int, wcmpMembers: Int = 0, aclEntries: Int = 0) {
      // VRF
      sendChecked(
        "TABLE_ADD ingress.routing_lookup.vrf_table no_action ${encodeString("", VRF_BW)} =>"
      )

      // Router interface
      sendChecked(
        "TABLE_ADD ingress.routing_resolution.router_interface_table " +
          "ingress.routing_resolution.set_port_and_src_mac " +
          "${encodeString("rif-0", RIF_BW)} => " +
          "${encodePort(1)} $RIF_MAC_HEX"
      )

      // Neighbor
      sendChecked(
        "TABLE_ADD ingress.routing_resolution.neighbor_table " +
          "ingress.routing_resolution.set_dst_mac " +
          "${encodeString("rif-0", RIF_BW)} ${encodeIpv6(NEIGHBOR_ID)} => " +
          NEIGHBOR_MAC_HEX
      )

      // Nexthops
      for (i in 0 until nexthops) {
        sendChecked(
          "TABLE_ADD ingress.routing_resolution.nexthop_table " +
            "ingress.routing_resolution.set_ip_nexthop " +
            "${encodeString("nhop-$i", NEXTHOP_BW)} => " +
            "${encodeString("rif-0", RIF_BW)} ${encodeIpv6(NEIGHBOR_ID)}"
        )
      }

      // WCMP
      val useWcmp = wcmpMembers > 0
      if (useWcmp) {
        val profileName =
          p4Info.actionProfilesList
            .find { it.preamble.name.contains("wcmp_group_selector") }
            ?.preamble
            ?.name ?: error("wcmp_group_selector not found in P4Info")

        // Members
        for (i in 1..wcmpMembers) {
          sendChecked(
            "ACT_PROF_ADD_MEMBER $profileName set_nexthop_id " +
              encodeString("nhop-${(i - 1) % nexthops}", NEXTHOP_BW)
          )
        }

        // Group with all members
        sendChecked("ACT_PROF_CREATE_GROUP $profileName")
        for (i in 0 until wcmpMembers) {
          sendChecked("ACT_PROF_ADD_MEMBER_TO_GROUP $profileName $i 0")
        }

        // wcmp_group_table entry: group 0
        sendChecked(
          "TABLE_ADD_GROUP ingress.routing_resolution.wcmp_group_table 0 " +
            encodeString("wcmp-1", WCMP_BW)
        )

        // Routes → set_wcmp_group_id
        for (i in 0 until routes) {
          sendChecked(
            "TABLE_ADD ingress.routing_lookup.ipv4_table " +
              "ingress.routing_lookup.set_wcmp_group_id " +
              "${encodeString("", VRF_BW)} ${encodeIp(ipForRoute(i))}/32 => " +
              encodeString("wcmp-1", WCMP_BW)
          )
        }
      } else {
        // Routes → set_nexthop_id (direct)
        for (i in 0 until routes) {
          sendChecked(
            "TABLE_ADD ingress.routing_lookup.ipv4_table set_nexthop_id " +
              "${encodeString("", VRF_BW)} ${encodeIp(ipForRoute(i))}/32 => " +
              encodeString("nhop-${i % nexthops}", NEXTHOP_BW)
          )
        }
      }

      if (aclEntries > 0) {
        installAclEntries(aclEntries)
      }
    }

    /**
     * Installs ternary ACL entries across acl_ingress_table (50%) and acl_pre_ingress_table (50%).
     * None match test packets, forcing a worst-case full table scan.
     *
     * Field byte widths derived from the BMv2 JSON (see docs/PERFORMANCE.md):
     * - acl_ingress_table: 17 ternary keys (is_ip(1), is_ipv4(1), is_ipv6(1), ether_type(2),
     *   dst_mac(6), src_ip(4), dst_ip(4), src_ipv6(8), dst_ipv6(8), ttl(1), dscp(1), ecn(1),
     *   ip_protocol(1), icmpv6_type(1), l4_src_port(2), l4_dst_port(2), arp_tpa(4))
     * - acl_pre_ingress_table: 9 ternary keys (is_ip(1), is_ipv4(1), is_ipv6(1), src_mac(6),
     *   dst_ip(4), dst_ipv6(8), dscp(1), ecn(1), in_port(2))
     */
    private fun installAclEntries(count: Int) {
      val ingressCount = count / 2
      val preIngressCount = count - ingressCount

      // acl_ingress_table: match on src_ip + dst_ip, wildcard everything else.
      for (i in 0 until ingressCount) {
        val srcIp = "ac10%02x%02x".format(i / 256, i % 256)
        val dstIp = "ac11%02x%02x".format(i / 256, i % 256)
        // 17 ternary fields: value&&&mask for each.
        // Wildcard (00&&&00) for fields we don't care about.
        sendChecked(
          "TABLE_ADD ingress.acl_ingress.acl_ingress_table " +
            "ingress.acl_ingress.acl_forward " +
            "00&&&00 " + // is_ip (1 byte)
            "00&&&00 " + // is_ipv4 (1 byte)
            "00&&&00 " + // is_ipv6 (1 byte)
            "0000&&&0000 " + // ether_type (2 bytes)
            "000000000000&&&000000000000 " + // dst_mac (6 bytes)
            "${srcIp}&&&ffffffff " + // src_ip (4 bytes)
            "${dstIp}&&&ffffffff " + // dst_ip (4 bytes)
            "0000000000000000&&&0000000000000000 " + // src_ipv6 (8 bytes)
            "0000000000000000&&&0000000000000000 " + // dst_ipv6 (8 bytes)
            "00&&&00 " + // ttl (1 byte)
            "00&&&00 " + // dscp (1 byte)
            "00&&&00 " + // ecn (1 byte)
            "00&&&00 " + // ip_protocol (1 byte)
            "00&&&00 " + // icmpv6_type (1 byte)
            "0000&&&0000 " + // l4_src_port (2 bytes)
            "0000&&&0000 " + // l4_dst_port (2 bytes)
            "00000000&&&00000000 " + // arp_tpa (4 bytes)
            "=> priority ${i + 100}"
        )
      }

      // acl_pre_ingress_table: match on dst_ip, wildcard everything else.
      for (i in 0 until preIngressCount) {
        val dstIp = "ac12%02x%02x".format(i / 256, i % 256)
        // 9 ternary fields.
        sendChecked(
          "TABLE_ADD ingress.acl_pre_ingress.acl_pre_ingress_table " +
            "ingress.acl_pre_ingress.set_vrf " +
            "00&&&00 " + // is_ip (1 byte)
            "00&&&00 " + // is_ipv4 (1 byte)
            "00&&&00 " + // is_ipv6 (1 byte)
            "000000000000&&&000000000000 " + // src_mac (6 bytes)
            "${dstIp}&&&ffffffff " + // dst_ip (4 bytes)
            "0000000000000000&&&0000000000000000 " + // dst_ipv6 (8 bytes)
            "00&&&00 " + // dscp (1 byte)
            "00&&&00 " + // ecn (1 byte)
            "0000&&&0000 " + // in_port (2 bytes)
            "=> ${encodeString("", VRF_BW)} priority ${i + 100}"
        )
      }
    }

    fun warmup(count: Int) {
      // Use BENCHMARK command to avoid 50ms drain timeout per packet.
      val pkt = buildIpv4Packet(ipForRoute(0))
      send("BENCHMARK $count $count 0 $pkt")
    }

    fun benchmark(count: Int): Double {
      val pkt = buildIpv4Packet(ipForRoute(0))
      val resp = send("BENCHMARK $count $count 0 $pkt")
      val match = PPS_REGEX.find(resp) ?: error("Bad BENCHMARK response: $resp")
      return match.groupValues[1].toDouble()
    }

    override fun close() {
      writer.close()
      if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }
  }

  // ===========================================================================
  // Encoding helpers
  // ===========================================================================

  companion object {
    // BMv2 bitwidths (from bitwidths.p4 PLATFORM_BMV2 branch).
    private const val STRING_BW = 256 // 32 bytes
    private const val VRF_BW = STRING_BW
    private const val NEXTHOP_BW = STRING_BW
    private const val RIF_BW = STRING_BW
    private const val WCMP_BW = STRING_BW
    private const val PORT_BW = 9

    private const val WARMUP_PACKETS = 200
    private const val BENCHMARK_PACKETS = 1_000
    private const val ACL_ENTRIES = 500

    private const val RIF_MAC_HEX = "001122334455"
    private const val NEIGHBOR_MAC_HEX = "00aabbccddee"
    // IPv6 neighbor ID: ::1
    private val NEIGHBOR_ID = ByteArray(16) { if (it == 15) 1.toByte() else 0 }

    private val PPS_REGEX = Regex("""(\d+) pps""")
    private const val HEADER = "| Routes | packets/s  |"
    private const val SEPARATOR = "|--------|------------|"

    /** Encode a string as a zero-padded hex value of the given bitwidth. */
    private fun encodeString(s: String, bitwidth: Int): String {
      val bytes = s.toByteArray(Charsets.UTF_8)
      val totalBytes = (bitwidth + 7) / 8
      val padded = ByteArray(totalBytes)
      // Right-align the string bytes.
      System.arraycopy(bytes, 0, padded, totalBytes - bytes.size, bytes.size)
      return padded.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun encodePort(port: Int): String {
      val bytes = (PORT_BW + 7) / 8
      return "%0${bytes * 2}x".format(port)
    }

    private fun encodeIpv6(addr: ByteArray): String =
      addr.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun encodeIp(ip: ByteArray): String =
      ip.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun ipForRoute(i: Int): ByteArray =
      byteArrayOf(
        10,
        (i shr 16 and 0xFF).toByte(),
        (i shr 8 and 0xFF).toByte(),
        (i and 0xFF).toByte(),
      )

    private fun buildIpv4Packet(dstIp: ByteArray): String {
      val packet = ByteArray(34) // 14 ethernet + 20 IPv4
      // Dst MAC: unicast
      packet[0] = 0x00
      packet[1] = 0x01
      packet[2] = 0x02
      packet[3] = 0x03
      packet[4] = 0x04
      packet[5] = 0x05
      // Src MAC
      packet[6] = 0x00
      packet[7] = 0x0A
      packet[8] = 0x0B
      packet[9] = 0x0C
      packet[10] = 0x0D
      packet[11] = 0x0E
      // EtherType: IPv4
      packet[12] = 0x08
      packet[13] = 0x00
      // IPv4: version=4, IHL=5
      packet[14] = 0x45
      // Total length = 20
      packet[16] = 0x00
      packet[17] = 0x14
      // TTL=64, protocol=TCP
      packet[22] = 64
      packet[23] = 0x06
      // Src IP: 192.168.1.1
      packet[26] = 192.toByte()
      packet[27] = 168.toByte()
      packet[28] = 1
      packet[29] = 1
      // Dst IP
      System.arraycopy(dstIp, 0, packet, 30, 4)
      return packet.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun loadP4Info(repoRelativePath: String): P4InfoOuterClass.P4Info {
      val text = fourward.bazel.repoRoot.resolve(repoRelativePath).toFile().readText()
      val builder = PipelineConfig.newBuilder()
      TextFormat.merge(text, builder)
      return builder.build().p4Info
    }
  }
}
