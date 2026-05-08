package fourward.grpc

import com.google.protobuf.ByteString
import fourward.PipelineConfig
import fourward.TranslationEntry
import fourward.TypeTranslation
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Dataplane performance benchmark for SAI P4 middleblock.
 *
 * Measures per-packet latency through the gRPC InjectPacket RPC at configurable table scale. This
 * is Track 10 Phase 1: establishing a baseline so we know where time goes before optimizing.
 *
 * Three configurations exercise increasingly realistic workloads:
 * 1. **Direct nexthop** — L3 forwarding only (VRF → LPM → nexthop → rewrite).
 * 2. **WCMP** — routes point to action profile groups (action selector fork in trace tree).
 * 3. **WCMP + mirror** — adds ACL copy-to-CPU (clone fork in trace tree), producing two output
 *    packets per input.
 *
 * All scale points run in a single test to avoid JVM warmup artifacts across JUnit methods. A
 * dedicated warmup phase runs ~500 packets before any measurement begins, ensuring JIT compilation
 * is complete.
 *
 * Run with: bazel test //grpc:DataplaneBenchmark --test_output=streamed
 */
@Suppress("FunctionNaming", "MagicNumber")
class DataplaneBenchmark {

  /**
   * Single-workload, single-mode profile test for measuring parallel scaling.
   *
   * The main [`dataplane benchmark`] sweeps many scale points, which is good for a comprehensive
   * snapshot but hard to profile — each workload only runs for ~1s, below the JFR/async-profiler
   * sampling settle time. This test runs one (workload, mode) combination for many thousands of
   * packets, long enough for steady-state profiling.
   *
   * Usage:
   * ```
   * bazel build //grpc:DataplaneBenchmark -c opt
   * ./bazel-bin/grpc/DataplaneBenchmark \
   *     --jvm_flag=-DprofileWorkload=wcmp128 \
   *     --jvm_flag=-DprofileMode=parallel \
   *     --jvm_flag=-Dfourward.simulator.intraPacketParallelism=false \
   *     --jvm_flag=-XX:StartFlightRecording=filename=/tmp/profile.jfr,settings=profile
   * ```
   *
   * Parameters (JVM system properties):
   * - `profileMode`: `sequential` (one packet at a time via InjectPacket) or `parallel` (many
   *   packets via InjectPackets). If unset, the test is skipped.
   * - `profileWorkload`: `direct` (L3 forwarding, no forks), `wcmp16mirr` (16-way WCMP + mirror
   *   clone), or `wcmp128` (128-way WCMP, DVaaS-realistic). Default: `wcmp128`.
   *
   * For clean scaling measurements, set `-Dfourward.simulator.intraPacketParallelism=false` on both
   * sides — see `designs/parallel_packet_scaling.md`.
   */
  @Test
  fun `profile focused`() {
    val mode = System.getProperty("profileMode") ?: return // skip unless explicitly invoked
    val workload = System.getProperty("profileWorkload") ?: "wcmp128"
    println("=== Profile: workload=$workload mode=$mode ===")

    // JIT warmup on a throwaway pipeline.
    val warmupHarness = createHarness()
    installEntries(warmupHarness, routeCount = 100, nexthopCount = 100)
    val warmupPacket = buildIpv4Packet(dstIp = ipForRoute(0))
    repeat(JIT_WARMUP_PACKETS) {
      warmupHarness.injectPacket(ingressPort = 0, payload = warmupPacket)
    }
    warmupHarness.close()

    val sp =
      when (workload) {
        "direct" -> ScalePoint("direct", routes = 10_000, nexthops = 100, aclEntries = ACL_ENTRIES)
        "wcmp16mirr" ->
          ScalePoint(
            "wcmp×16+mirr",
            routes = 10_000,
            nexthops = 100,
            wcmpMembers = 16,
            mirror = true,
            aclEntries = ACL_ENTRIES,
          )
        "wcmp128" ->
          ScalePoint(
            "wcmp×128",
            routes = 10_000,
            nexthops = 100,
            wcmpMembers = 128,
            aclEntries = ACL_ENTRIES,
          )
        else -> error("unknown profileWorkload: $workload (expected direct, wcmp16mirr, wcmp128)")
      }

    val harness = createHarness()
    installEntries(harness, sp.routes, 100, sp.wcmpMembers, sp.mirror, sp.aclEntries)
    val warmupPkt = buildIpv4Packet(dstIp = ipForRoute(0))
    repeat(SCALE_WARMUP_PACKETS) { harness.injectPacket(ingressPort = 0, payload = warmupPkt) }

    // Packet count scales inversely with per-packet cost so each run takes ~10-200s.
    val totalPackets =
      when (workload) {
        "direct" -> 500_000
        "wcmp16mirr" -> 50_000
        "wcmp128" -> 10_000
        else -> 50_000
      }
    val packets =
      (0 until totalPackets).map { i -> 0 to buildIpv4Packet(dstIp = ipForRoute(i % sp.routes)) }

    val startNs = System.nanoTime()
    when (mode) {
      "sequential" ->
        for ((port, payload) in packets) {
          harness.injectPacket(ingressPort = port, payload = payload)
        }
      "parallel" ->
        // Chunk to avoid any per-stream limits in InjectPackets at very large batches.
        for (batch in packets.chunked(10_000)) harness.injectPackets(batch)
      else -> error("unknown profileMode: $mode (expected sequential or parallel)")
    }
    val elapsedMs = (System.nanoTime() - startNs) / NS_PER_MS
    val pps = totalPackets / elapsedMs * 1000
    println("$workload $mode: $totalPackets packets in %.0f ms = %.0f pps".format(elapsedMs, pps))
    harness.close()
  }

  @Test
  fun `dataplane benchmark`() {
    if (System.getProperty("profileMode") != null) return // skip when running the focused test
    // --- JIT warmup on a throwaway pipeline ---
    val warmupHarness = createHarness()
    installEntries(warmupHarness, routeCount = 100, nexthopCount = 100)
    val warmupPacket = buildIpv4Packet(dstIp = ipForRoute(0))
    repeat(JIT_WARMUP_PACKETS) {
      warmupHarness.injectPacket(ingressPort = 0, payload = warmupPacket)
    }
    warmupHarness.close()

    val scalePoints =
      listOf(
        // All scale points include 500 ternary ACL entries across acl_ingress_table and
        // acl_pre_ingress_table (none match test packets → worst-case full scan).
        // --- Direct nexthop (L3 only) ---
        ScalePoint("direct", routes = 0, aclEntries = ACL_ENTRIES),
        ScalePoint("direct", routes = 100, aclEntries = ACL_ENTRIES),
        ScalePoint("direct", routes = 1_000, aclEntries = ACL_ENTRIES),
        ScalePoint("direct", routes = 4_000, aclEntries = ACL_ENTRIES),
        ScalePoint("direct", routes = 10_000, nexthops = 100, aclEntries = ACL_ENTRIES),
        // --- WCMP (action selector → trace tree fork) ---
        ScalePoint(
          "wcmp×4",
          routes = 10_000,
          nexthops = 100,
          wcmpMembers = 4,
          aclEntries = ACL_ENTRIES,
        ),
        ScalePoint(
          "wcmp×16",
          routes = 10_000,
          nexthops = 100,
          wcmpMembers = 16,
          aclEntries = ACL_ENTRIES,
        ),
        // --- WCMP + mirror (clone fork → 2 output packets) ---
        ScalePoint(
          "wcmp×16+mirr",
          routes = 10_000,
          nexthops = 100,
          wcmpMembers = 16,
          mirror = true,
          aclEntries = ACL_ENTRIES,
        ),
      )

    val cores = Runtime.getRuntime().availableProcessors()
    val jsonResults = mutableListOf<String>()

    println()
    println("$cores cores available")
    println()
    println("Sequential (InjectPacket, one at a time):")
    println(HEADER)
    println(SEPARATOR)

    for (sp in scalePoints) {
      val result = measureScalePoint(sp)
      println(
        "| %-12s | %6d | %6d | %8.3f | %8.3f | %8.3f | %10.0f |"
          .format(
            sp.label,
            sp.routes,
            result.totalEntries,
            result.p50Ms,
            result.p99Ms,
            result.meanMs,
            result.throughput,
          )
      )
      jsonResults.add(
        """{"mode":"sequential","config":"${sp.label}","routes":${sp.routes},""" +
          """"entries":${result.totalEntries},"p50_ms":%.3f,"p99_ms":%.3f,"""
            .format(result.p50Ms, result.p99Ms) +
          """"mean_ms":%.3f,"packets_per_sec":%.0f}""".format(result.meanMs, result.throughput)
      )
    }

    println(SEPARATOR)
    println()

    // --- Parallel benchmark: InjectPackets distributes across cores via ForkJoinPool ---
    val parallelPoints =
      listOf(
        ScalePoint("direct", routes = 10_000, nexthops = 100, aclEntries = ACL_ENTRIES),
        ScalePoint(
          "wcmp×16",
          routes = 10_000,
          nexthops = 100,
          wcmpMembers = 16,
          aclEntries = ACL_ENTRIES,
        ),
        ScalePoint(
          "wcmp×16+mirr",
          routes = 10_000,
          nexthops = 100,
          wcmpMembers = 16,
          mirror = true,
          aclEntries = ACL_ENTRIES,
        ),
      )

    println("Parallel (InjectPackets, $BENCHMARK_PACKETS packets across cores):")
    println(PARALLEL_HEADER)
    println(PARALLEL_SEPARATOR)

    for (sp in parallelPoints) {
      val result = measureParallel(sp)
      println(
        "| %-12s | %6d | %10.0f | %10.1f |".format(sp.label, sp.routes, result.first, result.second)
      )
      jsonResults.add(
        """{"mode":"parallel","config":"${sp.label}","routes":${sp.routes},""" +
          """"packets_per_sec":%.0f,"elapsed_ms":%.1f}""".format(result.first, result.second)
      )
    }

    println(PARALLEL_SEPARATOR)
    println()

    // Write JSON for machine consumption.
    val jsonDir = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR")
    if (jsonDir != null) {
      val jsonFile = java.io.File(jsonDir, "benchmark.json")
      jsonFile.writeText(
        """{"cores":$cores,"packets":$BENCHMARK_PACKETS,"acl_entries":$ACL_ENTRIES,""" +
          """"results":[${jsonResults.joinToString(",")}]}"""
      )
      println("JSON results written to ${jsonFile.absolutePath}")
    }
  }

  // ===========================================================================
  // Benchmark core
  // ===========================================================================

  private data class ScalePoint(
    val label: String,
    val routes: Int,
    val nexthops: Int = routes,
    val wcmpMembers: Int = 0,
    val mirror: Boolean = false,
    val aclEntries: Int = 0,
  )

  private data class BenchmarkResult(
    val totalEntries: Int,
    val p50Ms: Double,
    val p99Ms: Double,
    val meanMs: Double,
    val throughput: Double,
  )

  /** Measures total throughput by sending all packets at once via InjectPackets. */
  private fun measureParallel(sp: ScalePoint): Pair<Double, Double> =
    createHarness().use { harness ->
      val actualNexthops = minOf(sp.nexthops, maxOf(sp.routes, 1))
      installEntries(harness, sp.routes, actualNexthops, sp.wcmpMembers, sp.mirror, sp.aclEntries)

      val warmupPkt = buildIpv4Packet(dstIp = ipForRoute(0))
      repeat(SCALE_WARMUP_PACKETS) { harness.injectPacket(ingressPort = 0, payload = warmupPkt) }

      val packets =
        (0 until BENCHMARK_PACKETS).map { i ->
          0 to buildIpv4Packet(dstIp = ipForRoute(i % maxOf(sp.routes, 1)))
        }

      val startNs = System.nanoTime()
      harness.injectPackets(packets)
      val elapsedMs = (System.nanoTime() - startNs) / NS_PER_MS
      val throughput = BENCHMARK_PACKETS / elapsedMs * 1000
      throughput to elapsedMs
    }

  private fun measureScalePoint(sp: ScalePoint): BenchmarkResult =
    createHarness().use { harness ->
      val actualNexthops = minOf(sp.nexthops, maxOf(sp.routes, 1))
      val entryCount =
        installEntries(harness, sp.routes, actualNexthops, sp.wcmpMembers, sp.mirror, sp.aclEntries)

      val warmupPkt = buildIpv4Packet(dstIp = ipForRoute(0))
      repeat(SCALE_WARMUP_PACKETS) { harness.injectPacket(ingressPort = 0, payload = warmupPkt) }

      val latencies = LongArray(BENCHMARK_PACKETS)
      for (i in 0 until BENCHMARK_PACKETS) {
        val pkt = buildIpv4Packet(dstIp = ipForRoute(i % maxOf(sp.routes, 1)))
        val start = System.nanoTime()
        harness.injectPacket(ingressPort = 0, payload = pkt)
        latencies[i] = System.nanoTime() - start
      }

      latencies.sort()
      BenchmarkResult(
        totalEntries = entryCount,
        p50Ms = latencies[latencies.size / 2] / NS_PER_MS,
        p99Ms = latencies[(latencies.size * 0.99).toInt()] / NS_PER_MS,
        meanMs = latencies.average() / NS_PER_MS,
        throughput = NS_PER_MS * 1000 / latencies.average(),
      )
    }

  // ===========================================================================
  // Pipeline setup
  // ===========================================================================

  private fun createHarness(): FourwardTestHarness {
    val harness = FourwardTestHarness()
    var config = FourwardTestHarness.loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
    config = withPortMappings(config, mapOf("0" to 0, "1" to 1, CPU_PORT.toString() to CPU_PORT))
    harness.loadPipeline(config)
    this.config = config
    return harness
  }

  private lateinit var config: PipelineConfig

  // ===========================================================================
  // Table entry installation
  // ===========================================================================

  /**
   * Installs a realistic SAI P4 forwarding configuration. Returns the total entry count.
   *
   * Base chain (always): VRF + router_interface + neighbor + nexthops + IPv4 /32 routes.
   *
   * WCMP ([wcmpMembers] > 0): creates action profile members and groups; routes reference groups
   * via `action_profile_group_id` instead of direct `set_nexthop_id`.
   *
   * Mirror ([mirror] = true): installs ACL `acl_copy` + `ingress_clone_table` + clone session 255 →
   * CPU port. Every packet produces a clone fork in the trace tree (2 output packets).
   */
  private fun installEntries(
    harness: FourwardTestHarness,
    routeCount: Int,
    nexthopCount: Int,
    wcmpMembers: Int = 0,
    mirror: Boolean = false,
    aclEntries: Int = 0,
  ): Int {
    if (routeCount == 0) return 0
    var count = 0

    // --- Shared infrastructure: VRF + router_interface + neighbor ---
    val vrfTable = findTable("vrf_table")
    val noAction = findAction("no_action")
    harness.installEntry(buildEntry(vrfTable, noAction, listOf(exactMatch(vrfTable, "vrf_id", ""))))
    count++

    val rifTable = findTable("router_interface_table")
    val setPortAndSrcMac = findAction("set_port_and_src_mac")
    harness.installEntry(
      buildEntry(
        rifTable,
        setPortAndSrcMac,
        listOf(exactMatch(rifTable, "router_interface_id", "rif-0")),
        listOf(
          stringParam(setPortAndSrcMac, "port", "1"),
          bytesParam(setPortAndSrcMac, "src_mac", RIF_MAC),
        ),
      )
    )
    count++

    val neighborTable = findTable("neighbor_table")
    val setDstMac = findAction("set_dst_mac")
    harness.installEntry(
      buildEntry(
        neighborTable,
        setDstMac,
        listOf(
          exactMatch(neighborTable, "router_interface_id", "rif-0"),
          exactMatch(neighborTable, "neighbor_id", NEIGHBOR_ID),
        ),
        listOf(bytesParam(setDstMac, "dst_mac", NEIGHBOR_MAC)),
      )
    )
    count++

    // --- Nexthops ---
    val nexthopTable = findTable("nexthop_table")
    val setIpNexthop = findAction("set_ip_nexthop")
    for (batch in (0 until nexthopCount).chunked(BATCH_SIZE)) {
      val entities =
        batch.map { i ->
          buildEntry(
            nexthopTable,
            setIpNexthop,
            listOf(exactMatch(nexthopTable, "nexthop_id", "nhop-$i")),
            listOf(
              stringParam(setIpNexthop, "router_interface_id", "rif-0"),
              bytesParam(setIpNexthop, "neighbor_id", NEIGHBOR_ID),
            ),
          )
        }
      harness.writeRaw(harness.buildBatchRequest(Update.Type.INSERT, entities))
      count += entities.size
    }

    // --- WCMP: action profile members + group + wcmp_group_table entry ---
    val useWcmp = wcmpMembers > 0
    if (useWcmp) {
      val wcmpSelector =
        config.p4Info.actionProfilesList.find { it.preamble.alias == "wcmp_group_selector" }!!
      val profileId = wcmpSelector.preamble.id
      val setNexthopId = findAction("set_nexthop_id")

      // Members: each points to a nexthop (round-robin).
      for (memberId in 1..wcmpMembers) {
        harness.installEntry(
          Entity.newBuilder()
            .setActionProfileMember(
              P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(profileId)
                .setMemberId(memberId)
                .setAction(
                  Action.newBuilder()
                    .setActionId(setNexthopId.preamble.id)
                    .addParams(
                      stringParam(
                        setNexthopId,
                        "nexthop_id",
                        "nhop-${(memberId - 1) % nexthopCount}",
                      )
                    )
                )
            )
            .build()
        )
        count++
      }

      // Group with all members.
      harness.installEntry(
        Entity.newBuilder()
          .setActionProfileGroup(
            P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
              .setActionProfileId(profileId)
              .setGroupId(1)
              .addAllMembers(
                (1..wcmpMembers).map { mid ->
                  P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                    .setMemberId(mid)
                    .setWeight(1)
                    .build()
                }
              )
          )
          .build()
      )
      count++

      // wcmp_group_table entry: wcmp_group_id → action profile group 1.
      val wcmpTable = findTable("wcmp_group_table")
      harness.installEntry(
        Entity.newBuilder()
          .setTableEntry(
            TableEntry.newBuilder()
              .setTableId(wcmpTable.preamble.id)
              .addMatch(exactMatch(wcmpTable, "wcmp_group_id", "wcmp-1"))
              .setAction(P4RuntimeOuterClass.TableAction.newBuilder().setActionProfileGroupId(1))
          )
          .build()
      )
      count++
    }

    // --- IPv4 routes ---
    val ipv4Table = findTable("ipv4_table")
    if (useWcmp) {
      // Routes use set_wcmp_group_id; wcmp_group_table resolves the group.
      val setWcmpGroupId = findAction("set_wcmp_group_id")
      for (batch in (0 until routeCount).chunked(BATCH_SIZE)) {
        val entities =
          batch.map { i ->
            buildEntry(
              ipv4Table,
              setWcmpGroupId,
              listOf(
                exactMatch(ipv4Table, "vrf_id", ""),
                lpmMatch(ipv4Table, "ipv4_dst", ipForRoute(i), prefixLen = 32),
              ),
              listOf(stringParam(setWcmpGroupId, "wcmp_group_id", "wcmp-1")),
            )
          }
        harness.writeRaw(harness.buildBatchRequest(Update.Type.INSERT, entities))
        count += entities.size
      }
    } else {
      val setNexthopId = findAction("set_nexthop_id")
      for (batch in (0 until routeCount).chunked(BATCH_SIZE)) {
        val entities =
          batch.map { i ->
            buildEntry(
              ipv4Table,
              setNexthopId,
              listOf(
                exactMatch(ipv4Table, "vrf_id", ""),
                lpmMatch(ipv4Table, "ipv4_dst", ipForRoute(i), prefixLen = 32),
              ),
              listOf(stringParam(setNexthopId, "nexthop_id", "nhop-${i % nexthopCount}")),
            )
          }
        harness.writeRaw(harness.buildBatchRequest(Update.Type.INSERT, entities))
        count += entities.size
      }
    }

    // --- Mirror: ACL copy + clone session ---
    if (mirror) {
      count += installMirror(harness)
    }

    // --- ACL entries (ternary — worst case for O(n) table scan) ---
    if (aclEntries > 0) {
      count += installAclEntries(harness, aclEntries)
    }

    return count
  }

  /**
   * Installs [count] ternary ACL entries across acl_ingress_table (50%) and acl_pre_ingress_table
   * (50%). None match the benchmark's test packets, forcing a full table scan on every packet
   * (worst case).
   */
  @Suppress("MagicNumber")
  private fun installAclEntries(harness: FourwardTestHarness, count: Int): Int {
    val ingressCount = count / 2
    val preIngressCount = count - ingressCount
    var installed = 0

    // acl_ingress_table: ternary on src_ip + dst_ip
    val ingressTable = findTable("acl_ingress_table")
    val aclFwd = findAction("acl_forward")
    for (batch in (0 until ingressCount).chunked(BATCH_SIZE)) {
      val entities =
        batch.map { i ->
          buildEntry(
            ingressTable,
            aclFwd,
            listOf(
              optionalMatch(ingressTable, "is_ipv4", byteArrayOf(1)),
              ternaryMatch(
                ingressTable,
                "src_ip",
                byteArrayOf(172.toByte(), 16, (i / 256).toByte(), (i % 256).toByte()),
                byteArrayOf(-1, -1, -1, -1),
              ),
              ternaryMatch(
                ingressTable,
                "dst_ip",
                byteArrayOf(172.toByte(), 17, (i / 256).toByte(), (i % 256).toByte()),
                byteArrayOf(-1, -1, -1, -1),
              ),
            ),
            priority = i + 1,
          )
        }
      harness.writeRaw(harness.buildBatchRequest(Update.Type.INSERT, entities))
      installed += entities.size
    }

    // acl_pre_ingress_table: ternary on dst_mac + dst_ip
    val preIngressTable = findTable("acl_pre_ingress_table")
    val setVrf = findAction("set_vrf")
    for (batch in (0 until preIngressCount).chunked(BATCH_SIZE)) {
      val entities =
        batch.map { i ->
          buildEntry(
            preIngressTable,
            setVrf,
            listOf(
              optionalMatch(preIngressTable, "is_ipv4", byteArrayOf(1)),
              ternaryMatch(
                preIngressTable,
                "dst_ip",
                byteArrayOf(172.toByte(), 18, (i / 256).toByte(), (i % 256).toByte()),
                byteArrayOf(-1, -1, -1, -1),
              ),
            ),
            params = listOf(stringParam(setVrf, "vrf_id", "")),
            priority = i + 1,
          )
        }
      harness.writeRaw(harness.buildBatchRequest(Update.Type.INSERT, entities))
      installed += entities.size
    }

    return installed
  }

  /**
   * Installs ACL copy-to-CPU: `acl_ingress_table` match-all → `acl_copy`, `ingress_clone_table`
   * marked_to_copy → clone session 255 → CPU port. Returns the number of entries installed.
   */
  private fun installMirror(harness: FourwardTestHarness): Int {
    // ACL ingress: copy all IPv4 packets.
    val aclTable = findTable("acl_ingress_table")
    val aclCopy = findAction("acl_copy")
    harness.installEntry(
      buildEntry(
        aclTable,
        aclCopy,
        matches = listOf(optionalMatch(aclTable, "is_ipv4", byteArrayOf(1))),
        params = listOf(bytesParam(aclCopy, "qos_queue", byteArrayOf(0))),
        priority = 1,
      )
    )

    // ingress_clone_table: marked_to_copy=1 → clone session 255.
    val cloneTable = findTable("ingress_clone_table")
    val ingressClone = findAction("ingress_clone")
    harness.installEntry(
      buildEntry(
        cloneTable,
        ingressClone,
        matches =
          listOf(
            exactMatch(cloneTable, "marked_to_copy", byteArrayOf(1)),
            exactMatch(cloneTable, "marked_to_mirror", byteArrayOf(0)),
          ),
        params =
          listOf(
            bytesParam(
              ingressClone,
              "clone_session",
              FourwardTestHarness.longToBytes(CLONE_SESSION_ID.toLong(), 4),
            )
          ),
        priority = 1,
      )
    )

    // Clone session 255 → CPU port.
    harness.installEntry(
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setCloneSessionEntry(
              P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                .setSessionId(CLONE_SESSION_ID)
                .addReplicas(
                  P4RuntimeOuterClass.Replica.newBuilder()
                    .setPort(ByteString.copyFromUtf8(CPU_PORT.toString()))
                    .setInstance(1)
                )
            )
        )
        .build()
    )

    return 3
  }

  // ===========================================================================
  // P4Runtime helpers
  // ===========================================================================

  private fun findTable(alias: String) = FourwardTestHarness.findTable(config, alias)

  private fun findAction(alias: String) = FourwardTestHarness.findAction(config, alias)

  private fun matchFieldId(table: P4InfoOuterClass.Table, name: String) =
    FourwardTestHarness.matchFieldId(table, name)

  private fun paramId(action: P4InfoOuterClass.Action, name: String) =
    FourwardTestHarness.paramId(action, name)

  private fun buildEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    matches: List<FieldMatch>,
    params: List<Action.Param> = emptyList(),
    priority: Int = 0,
  ): Entity {
    val entry =
      TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addAllMatch(matches)
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(Action.newBuilder().setActionId(action.preamble.id).addAllParams(params))
        )
    if (priority > 0) entry.setPriority(priority)
    return Entity.newBuilder().setTableEntry(entry).build()
  }

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value)))
      .build()

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: String,
  ): FieldMatch = exactMatch(table, fieldName, value.toByteArray(Charsets.UTF_8))

  private fun optionalMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setOptional(FieldMatch.Optional.newBuilder().setValue(ByteString.copyFrom(value)))
      .build()

  private fun ternaryMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
    mask: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setTernary(
        FieldMatch.Ternary.newBuilder()
          .setValue(ByteString.copyFrom(value))
          .setMask(ByteString.copyFrom(mask))
      )
      .build()

  private fun lpmMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
    prefixLen: Int,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setLpm(
        FieldMatch.LPM.newBuilder().setValue(ByteString.copyFrom(value)).setPrefixLen(prefixLen)
      )
      .build()

  private fun stringParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: String,
  ): Action.Param =
    Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFromUtf8(value))
      .build()

  private fun bytesParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: ByteArray,
  ): Action.Param =
    Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFrom(value))
      .build()

  // ===========================================================================
  // Packet construction
  // ===========================================================================

  private fun buildIpv4Packet(dstIp: ByteArray): ByteArray {
    val packet = ByteArray(ETHERNET_HEADER_LEN + IPV4_HEADER_LEN)
    System.arraycopy(UNICAST_MAC, 0, packet, 0, MAC_LEN)
    System.arraycopy(SRC_MAC, 0, packet, MAC_LEN, MAC_LEN)
    packet[12] = 0x08.toByte()
    packet[13] = 0x00.toByte()
    packet[14] = 0x45.toByte() // version=4, IHL=5
    packet[16] = 0x00.toByte()
    packet[17] = IPV4_HEADER_LEN.toByte()
    packet[22] = 64.toByte() // TTL
    packet[23] = 0x06.toByte() // TCP
    System.arraycopy(SRC_IP, 0, packet, SRC_IP_OFFSET, 4)
    System.arraycopy(dstIp, 0, packet, DST_IP_OFFSET, 4)
    return packet
  }

  companion object {
    private const val CPU_PORT = 510
    private const val CLONE_SESSION_ID = 255
    private const val JIT_WARMUP_PACKETS = 500
    private const val SCALE_WARMUP_PACKETS = 100
    private const val BENCHMARK_PACKETS = 1_000
    private const val BATCH_SIZE = 500
    private const val ACL_ENTRIES = 500
    private const val NS_PER_MS = 1_000_000.0

    private const val MAC_LEN = 6
    private const val ETHERNET_HEADER_LEN = 14
    private const val IPV4_HEADER_LEN = 20
    private const val SRC_IP_OFFSET = 26
    private const val DST_IP_OFFSET = 30

    private val NEIGHBOR_ID = ByteArray(16) { if (it == 15) 1 else 0 }
    private val NEIGHBOR_MAC =
      byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
    private val RIF_MAC = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val UNICAST_MAC = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
    private val SRC_MAC = byteArrayOf(0x00, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E)
    private val SRC_IP = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)

    private const val HEADER =
      "| Config       | Routes | Entries |  p50 ms  |  p99 ms  | mean ms  | packets/s  |"
    private const val SEPARATOR =
      "|--------------|--------|---------|----------|----------|----------|------------|"
    private const val PARALLEL_HEADER = "| Config       | Routes | packets/s  | elapsed ms |"
    private const val PARALLEL_SEPARATOR = "|--------------|--------|------------|------------|"

    /** Maps route index i to a /32 destination: 10.{b1}.{b2}.{b3}. */
    private fun ipForRoute(i: Int): ByteArray =
      byteArrayOf(
        10,
        (i shr 16 and 0xFF).toByte(),
        (i shr 8 and 0xFF).toByte(),
        (i and 0xFF).toByte(),
      )

    private fun withPortMappings(config: PipelineConfig, ports: Map<String, Int>): PipelineConfig {
      val portTranslation =
        TypeTranslation.newBuilder().setTypeName("port_id_t").setAutoAllocate(true)
      for ((name, dpValue) in ports) {
        portTranslation.addEntries(
          TranslationEntry.newBuilder().setSdnStr(name).setDataplaneValue(encodeMinWidth(dpValue))
        )
      }
      return config
        .toBuilder()
        .setDevice(config.device.toBuilder().addTranslations(portTranslation))
        .build()
    }
  }
}
