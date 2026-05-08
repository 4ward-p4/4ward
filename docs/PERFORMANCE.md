# Dataplane Performance

This document covers **data plane** performance only: how fast 4ward
processes packets. All numbers below are packet throughput.

**Control plane** operations (table programming via `Write`, pipeline
loading via `SetForwardingPipelineConfig`) have not been measured or
optimized. They are expected to be slow — and that's fine. 4ward's
target use cases (testing, validation, trace exploration) are
data-plane-bound: the control plane sets up state once, then the data
plane processes thousands of packets. If control plane performance
matters for your use case, let us know.

4ward optimizes for correctness, observability, and extensibility — yet
achieves practical throughput for production test workloads.

## Benchmark setup

**Program.** SAI P4 middleblock — a realistic 30-table program with VRF
lookup, IPv4 LPM, WCMP action selectors, nexthop resolution, MAC
rewrite, ACL, and ingress cloning.

**Table entries.** 10k IPv4 /32 routes, 100 nexthops, router interface,
neighbor, VRF, and 500 ternary ACL entries split across
`acl_ingress_table` and `acl_pre_ingress_table`. None of the ACL entries
match the benchmark's test packets, forcing a worst-case full table scan
on every packet.

**Packet.** Ethernet + IPv4 (34 bytes) targeting a known route. Each
iteration cycles through destination IPs so different table entries are
exercised.

**Workloads.**

- **L3 forwarding** — VRF → LPM → nexthop → MAC rewrite. No forks. One
  output packet per input.
- **WCMP ×16** — 16-member action selector. 4ward explores all 16
  members as trace tree branches. One output packet per input (each
  branch produces one).
- **WCMP ×16 + mirror** — WCMP ×16 + ingress clone (copy-to-CPU). 32
  trace tree branches, 2 output packets per input.

**Machine.** AMD Ryzen 9 7950X3D (16 cores, 128 MB L3 V-Cache), OpenJDK
21, Linux 6.8. The large L3 cache may flatter cache-locality-sensitive
workloads compared to typical server hardware.

## 4ward results

Throughput in packets/sec (higher is better). 10k table entries + 500
ternary ACL entries.

| Workload | Sequential, 1 core | Sequential, 16 cores | Batch, 1 core | Batch, 16 cores |
|----------|--------------------|----------------------|---------------|-----------------|
| L3 forwarding | 2,500 | 2,600 | 2,600 | 29,000 |
| WCMP ×16 | 2,000 | 2,300 | 1,700 | 13,000 |
| WCMP ×16 + mirror | 1,400 | 1,700 | 1,100 | 9,000 |

- **Sequential** = `InjectPacket` (one packet at a time, wait for result).
- **Batch** = `InjectPackets` (1000 packets streamed concurrently).
- **1 core** = `ForkJoinPool.common.parallelism=1`. No parallelism.
- **16 cores** = `InjectPacket` parallelizes fork branches within each
  packet. `InjectPackets` adds cross-packet parallelism.

Reproducing:
```sh
bazel test //grpc:DataplaneBenchmark --test_output=streamed
```

## BMv2 comparison

Head-to-head against BMv2's `simple_switch` on the same SAI P4 program
with the same table entries: 10k LPM routes + 500 ternary ACL entries.

| Workload | BMv2 | 4ward, 1 core | 4ward, 16 cores |
|----------|------|---------------|-----------------|
| L3 forwarding | 4,500 | 2,500 | 29,000 |
| WCMP ×16 | 4,400 | 2,000 | 13,000 |

(packets/sec; higher is better)

BMv2 is faster on single-core sequential throughput — it's a mature C++
codebase compiled with `-O2` and doesn't build trace trees. 4ward's
concurrent mode (`InjectPackets`) pulls ahead by parallelizing across
packets and within trace tree forks.

WCMP ×16 sequential is additionally lower because the two simulators do
different amounts of work: BMv2 hashes to one action selector member per
packet. 4ward explores *all* 16 members, producing a complete trace
tree. That's 16× more computation per packet — the trace tree is the
point.

### Build flags

Following BMv2's own [performance
guide](https://github.com/p4lang/behavioral-model/blob/main/docs/performance.md),
BMv2 is compiled with `-O2` (`bazel test -c opt`). Build flags have a
"massive impact" on BMv2 performance per their docs.

BMv2 has two tracing mechanisms with different performance implications:

- **Per-packet trace logging** (`BM_LOG_DEBUG_ON`, `BM_LOG_TRACE_ON`):
  logs parser transitions, table lookups, and action execution for every
  packet. This is BMv2's analog of 4ward's trace trees — both simulators
  compute a detailed record of what happened to each packet. **Enabled**
  in our benchmark for a fair comparison.

- **Event logger** (`BM_ELOG_ON`): writes a binary event log to disk for
  offline analysis tools (`bm_nanomsg_events`). This is a disk-I/O
  side-channel with no 4ward equivalent. **Disabled** in our benchmark.

### Caveats

- **BMv2 doesn't produce trace trees.** Its per-packet logging is
  textual and not returned to the caller — it goes to spdlog. 4ward
  constructs a structured `TraceTree` proto that is part of the response.
  The overhead profiles are similar but not identical.

- **BMv2 is compiled C++; 4ward is JVM (Kotlin).** BMv2 benefits from
  ahead-of-time compilation and `-O2` optimization. 4ward runs on the
  JVM with JIT compilation. The JVM's warmup phase is excluded from
  measurements.

### Reproducing

```sh
# BMv2
bazel test //e2e_tests/bmv2_diff:Bmv2Benchmark -c opt --test_output=streamed

# 4ward
bazel test //grpc:DataplaneBenchmark --test_output=streamed
```

## Optimizations

Starting from an unoptimized baseline, twelve optimizations delivered a
**220× improvement** on the hardest workload (WCMP ×16 + mirror, batch,
16 cores) and **34× single-core sequential**.

| Workload | Baseline | Current (1 core) | Current (16 cores) |
|----------|----------|-------------------|--------------------|
| L3 forwarding | 1,400 | 2,500 | 29,000 |
| WCMP ×16 | 83 | 2,000 | 13,000 |
| WCMP ×16 + mirror | 41 | 1,400 | 9,000 |

(sequential packets/sec, except "16 cores" column which uses batch mode)

1. **Table lookup caching** (PR #382): cache pre-fork lookup results
   across action selector fork re-executions.
2. **Parser skip on fork re-execution** (PR #392, #397): snapshot
   post-parser state, restore instead of re-parsing.
3. **Long-lived Interpreter** (PR #400): split into outer class
   (config-derived maps) and lightweight `Execution` inner class.
4. **Parallel fork branches** (PR #406): `parallelStream` replaces
   the iterative work stack — code got simpler (-36 lines).
5. **Concurrent packet processing** (PR #409): `ReadWriteMutex` +
   `InjectPackets` streaming RPC for bulk injection.
6. **Long fast-path for table matching** (PR #422): `BitVector.longValue`
   \+ Long-based match for fields ≤ 63 bits. Zero heap allocation per
   comparison. BigInteger cache for wide fields (IPv6).
7. **Iterator elimination in table matching** (PR #429): indexed loops
   \+ HashMap replace iterator-heavy functional patterns in the hot loop.
8. **Array-indexed field lookup** (PR #430): field ID array replaces
   HashMap + String conversion in scoreEntry. Zero allocation per
   match field.
9. **Long-backed BitVector** (PR #562): fields ≤ 63 bits use a `Long`
   instead of `BigInteger`. Eliminates heap allocation for the
   majority of field operations.
10. **CompactFieldMap + proto builder pooling** (PR #554):
    array-backed header field map (copy = `Array.copyOf`) and reused
    `TraceEvent.Builder` per execution.
11. **Fork-point resume** (PR #567): instead of replaying the pipeline
    for each fork branch, captures state at the fork point and
    continues each branch from there. Eliminates prefix re-execution,
    prefix event stripping, and the entire replay infrastructure.
    v1model only — PSA/PNA still use replay.
12. **CompactFieldMap for StructVal fields** (PR #589): struct deep-copy
    via `Array.copyOf` instead of N HashMap Node allocations. Leaf
    values (BitVal, BoolVal) are immutable and safe to share; only
    mutable nested values (HeaderVal) are deep-copied.

**What didn't help** (tried and reverted):
- Caching `defaultValue()` templates — negligible impact.
- Persistent collections (`kotlinx.collections.immutable`) for
  copy-on-write — HAMT overhead cancelled the copy savings.
- Caching config-derived parser params — the filter operates on 3
  elements, invisible in a profile.
