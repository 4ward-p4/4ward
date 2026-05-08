# Parallel Packet Scaling

**Status: Phase 3 complete (PRs #554, #562, #567, #589). wcmp×128
parallel throughput improved +155% from baseline (1,501 → 3,825 pps),
efficiency from 45% to 72%. The four main wins: Long-backed BitVector
(#562), CompactFieldMap + proto builder pooling (#554), fork-point
resume (#567), and CompactFieldMap for StructVal fields (#589). See
"Phase 3 results" below for measurements.**

## North star

**Inter-packet parallelism should scale linearly with physical cores.**
For N cores, the parallel `InjectPackets` RPC should deliver N× the
throughput of a single-threaded run. Each packet is independent of every
other packet (modulo a few shared stateful externs, which have their own
lock-free data structures) — the algorithm is truly embarrassingly
parallel at the packet level. Anything well below linear is wasted
hardware.

This is the benchmark we want to hit:

```
throughput(N cores) = N × throughput(1 core)
```

Real-world overheads mean we won't hit exactly linear. On a well-optimized
CPU-bound JVM workload with heavy allocation, 85-90% on 16 cores is
ambitious but reachable; 50% is common without work. Anything more than
~10% off demands an explanation.

### Why inter-packet parallelism specifically

The simulator actually has two independent parallelism mechanisms:

- **Inter-packet parallelism**: multiple packets process on separate
  threads simultaneously. Each packet is an independent task with no
  shared state — genuinely embarrassingly parallel. Implemented by
  `InjectPackets` dispatching each packet to `ForkJoinPool.commonPool()`.
- **Intra-packet parallelism**: within a single packet, trace-tree fork
  branches (clone, multicast, action selector) run on separate threads.
  Branches start from a shared packet state that must be deep-copied
  per branch — parallelism, but not embarrassingly so. Implemented in
  `V1ModelArchitecture.buildTraceTree` via `parallelStream()`.

**The north star is about inter-packet parallelism only**, because:

1. It's the only genuinely embarrassingly parallel axis (no shared
   starting state between tasks).
2. It's the axis that matters for throughput-oriented workloads
   (DVaaS, replay testing, fuzz testing) — users who need to process
   many packets.
3. It composes cleanly with single-threaded reasoning: "one thread does
   one packet, N threads do N packets."

Intra-packet parallelism is an orthogonal optimization for a different
use case (single-packet latency — CLI, STF tests, playground) and is
out of scope for this document. Measurements below confirm that intra-
and inter-packet parallelism don't meaningfully compose — inter-packet
alone saturates the machine, so intra-packet on top adds nothing.

### Baseline methodology

"Single-thread" means exactly one core doing all the work for one packet
at a time. Both parallelism axes are disabled:

- **Inter-packet**: sequential `InjectPacket` calls (one packet at a time).
- **Intra-packet**: `parallelStream()` in `buildTraceTree` disabled via
  the `fourward.simulator.intraPacketParallelism=false` system property.

"Parallel" means many packets in flight via `InjectPackets`, with
intra-packet parallelism **also disabled** — apples-to-apples with the
single-thread baseline. The comparison measures pure inter-packet
speedup with no confounding from intra-packet behavior.

## Current state

Verified measurements on a 16 physical core / 32 SMT thread AMD Ryzen 9
7950X3D, SAI P4 middleblock, 10k routes. Both sides have intra-packet
parallelism disabled (`-Dfourward.simulator.intraPacketParallelism=false`).
Measured via the `profile focused` test in `DataplaneBenchmark`.

| Workload | Single-thread | Parallel | Speedup | **Efficiency vs 16 cores** | CPU utilization (parallel) |
|---|---|---|---|---|---|
| direct | 2,568 pps | 38,449 pps | **14.97×** | **94%** | 65% — idle headroom |
| wcmp×16+mirr | 664 pps | 5,624 pps | **8.47×** | **53%** | 91% — saturated |
| wcmp×128 | 207 pps | 1,501 pps | **7.25×** | **45%** | 81% — saturated |

**Direct L3 is essentially at linear scaling.** 14.97× on 16 physical
cores = 94% efficiency. The machine isn't even CPU-saturated (JVM
averaged 65% of total machine CPU), so the remaining 6% gap is dispatch
overhead (gRPC, coroutines, `ForkJoinPool` task submission serial
fraction), not compute. Not worth chasing.

**Fork-heavy workloads are at ~45-53% efficiency** and **efficiency
degrades with higher fork counts** (53% at 32 branches per packet, 45%
at 128). These workloads saturate the machine but each packet costs ~2×
more CPU under parallel load than under single-thread.

> **CPU utilization ≠ efficiency.** CPU utilization = how much of the
> machine is being used. Efficiency = speedup / theoretical max. Direct
> has low CPU utilization but high efficiency (finishes fast without
> needing all the cores). wcmp has high CPU utilization but low
> efficiency (uses all the cores but each packet costs more).

### Aside: intra-packet parallelism is orthogonal

Confirming the claim above, wcmp×128 throughput with intra-packet
parallelism toggled on and off:

| Mode | Intra-packet | Throughput | CPU util |
|---|---|---|---|
| Single-thread | OFF | 207 pps | 3.4% |
| Single-thread | ON | 633 pps | — |
| Parallel (inter-packet) | OFF | 1,501 pps | 81% |
| Parallel (inter-packet) | ON | 1,506 pps | 88% |

Under parallel load, intra-packet is a wash on throughput (1,506 vs
1,501 — noise) but burns ~7% more CPU. Under single-packet load, it's
a 3× latency win (633 vs 207). Keep it enabled by default — CLI, STF,
and playground users benefit, parallel users don't pay. The scaling
measurements above use intra-packet **off** on both sides to keep the
comparison clean.

## Why embarrassingly parallel isn't linear in practice

First, the theory. "Embarrassingly parallel" is a statement about the
algorithm: the tasks have no inter-task dependencies, no synchronization,
no shared state. That's true for packet processing (the snapshot is
read-only at the data-plane layer; each packet is independent).

But **the algorithm and the runtime live in different worlds**. The
algorithm doesn't know about the machine's shared physical resources,
and six of them could plausibly be in play. Bracketed verdicts below
are the results of measuring each mechanism directly — see Phase 1.5.

1. **The JVM allocator.** Allocations normally go into a per-thread TLAB
   (thread-local allocation buffer) — fast, no contention. But when a
   TLAB fills, the thread has to request a new one from the shared heap,
   which is a coordination point. At high allocation rates, TLAB refills
   become frequent and contention becomes visible.
   **[Refuted — see Phase 1.5 #1.]**

2. **L3 cache pressure.** Each physical core has private L1/L2; all
   cores share L3. Single-threaded, one packet's working set fits in
   L1/L2. With many threads running in parallel, their combined
   ephemeral working sets (HashMaps, BigIntegers, builders per thread)
   spill into L3, evicting each other. What was an L2 hit becomes an
   L3 hit (3× slower), or worse, a DRAM access (10× slower).
   **[Confirmed — the dominant mechanism. See Phase 1.5 #6, #7.]**

3. **Memory bandwidth.** DRAM has finite bandwidth. Fresh allocations
   touch new cache lines; at gigabytes per second of allocation across
   all threads, memory bandwidth becomes a real constraint.
   **[Contributory — shares root cause with (2). See Phase 1.5 #6, #7.]**

4. **Garbage collection.** G1GC steals CPU cycles for concurrent marking
   and evacuation. At high allocation rates, GC runs more often,
   reducing effective throughput even when pause times look small.
   **[Refuted — see Phase 1.5 #3, #5, #9.]**

5. **Cache coherency.** Even read-only data has coherency cost if
   writes happen nearby (false sharing). JVM object allocations are
   typically on fresh cache lines, so this is usually a non-issue — but
   it can bite.
   **[Refuted — see Phase 1.5 #8.]**

6. **SMT interference.** Two SMT threads on the same physical core
   share L1, L2, and execution units. For memory-bound work, they can
   slow each other down.
   **[Refuted — see Phase 1.5 #6.]**

**For CPU-bound JVM workloads with heavy allocation, 50-60% efficiency
on 16 cores is a normal outcome, not a failure.** The algorithm's
embarrassing parallelism is preserved in the *logical* decomposition —
each packet is still independent. But the *physical* execution shares
allocator, caches, and memory bandwidth across all threads.

This means: closing the gap to linear scaling requires reducing shared
resource pressure, not restructuring the algorithm. Specifically:

- **Reduce working-set size** → less L3 pressure, fewer DRAM misses
- **Reduce per-allocation size** → fewer cache lines touched per object

## Phase 1 profile findings

JFR CPU profiles tell us *where* time is spent, not *why*. The numbers
below are raw sampling observations; the Phase 1.5 deep-dive
reinterprets them using hardware counters.

Two JFR profile pairs: direct single-thread vs direct parallel, and
wcmp×128 single-thread vs wcmp×128 parallel. Both sides have intra-packet
parallelism disabled to get a clean apples-to-apples comparison (with it
on, sequential mode uses ~5 cores per packet, which confuses the diff).
The diffs tell completely different stories.

### Direct L3 — scales cleanly

**Dominant cost (same in both modes):** `TableStoreKt.toUnsignedLong(ByteString)`
at 65-73% of CPU. The O(n) linear scan over 10k LPM entries converts each
entry's match field bytes to a Long for comparison.

**Parallel scaling overhead:** `TableStore.lookup` +4.3% — modest cache
pressure from threads each scanning the same 10k-entry table. The
read-only table stays in L2/L3 well enough that contention is minimal.

**Why direct scales well:** no trace tree forks → no header/struct deep
copies → no allocation-heavy per-fork work. Each thread does the same
lookup work independently on the shared (read-only) snapshot.
Cache-friendly and allocator-friendly.

### wcmp×128 — allocation-adjacent hot spots

The workload has a 128-way action selector fork, producing 128 branches
per packet. Each branch must deep-copy the packet state (headers +
structs) so branches don't interfere with each other.

**Parallel scaling overhead (clean diff, both sides intra-packet parallelism OFF):**

| Delta | Frame | Source |
|---|---|---|
| **+7.7%** | `BigInteger.<init>(int[], int)` | Per-fork arithmetic intermediates |
| **+7.4%** | `HashMap.putVal` | Per-fork header/struct deep copy |
| **+2.9%** | `TraceEvent$Builder.buildPartial` | Per-fork trace event allocation |
| **+2.1%** | `BitVector.<init>(BigInteger, int)` | Related to `BigInteger` allocation |
| **+1.4%** | `HashMap.put` | Per-fork header/struct deep copy |
| +0.9% | `BitVector.concat` | Bit field operations |
| +0.7% | `BigInteger.<init>(int[])` | More BigInteger allocation |

**BigInteger allocation is the single largest overhead bucket at ~9%
combined.** HashMap put operations (`put` + `putVal`) are a close second
at ~9% combined. The overhead inside these frames totals **~23-25%**
of parallel-mode CPU — but Phase 1.5 shows those cycles are
DRAM-stall cycles, not allocator-fast-path cycles.

### What the profile diff pointed at

The hot methods in the parallel-overhead bucket are all
allocation-adjacent: `HashMap.putVal` is called from
`HeaderVal.deepCopy()` / `StructVal.deepCopy()`, which each fork branch
invokes to get an independent copy of packet state.
`BigInteger.<init>` is called from `BitVector` arithmetic, which fires
on every bit-field operation. At 128 fork branches per packet × 30-odd
active workers, the allocator is hit millions of times per second.

Taken at face value, this looks like **fork-induced allocation
pressure** — the fix would be "allocate less." But the profile alone
can't distinguish "`HashMap.putVal` is slow because the allocator is
contended" from "`HashMap.putVal` is slow because the new node's cache
line has to be fetched from DRAM." The first would be solved by
reducing allocation count; the second would be solved by reducing
working-set size. These are correlated but not identical, and the
right fix depends on which is actually happening.

Phase 1.5 answers that question.

## Phase 1.5: empirical deep-dive

**TL;DR.** Of the six candidate mechanisms, only one is confirmed:
**L3 capacity exhaustion**. Each thread's IPC collapses from 3.02 to
0.88 in parallel — same total instructions, unchanged L1/L2 hit rates,
but 3× more loads per packet miss all caches and reach DRAM. The
allocator-adjacent hot spots from the Phase 1 profile are
*memory-stall cycles* inside those methods, not allocator CPU cost.

Nine experiments on the same machine and workload (AMD Ryzen 9
7950X3D, SAI P4 middleblock, wcmp×128, intra-packet parallelism off on
both sides):

1. **JFR TLAB events** — `ObjectAllocationInNewTLAB` / `OutsideTLAB`
2. **JFR allocation rate** — bytes per packet, bytes per second
3. **JFR GC events** — pause share, young-gen sizing
4. **Scaling curve** — wcmp×128 at 1/2/4/8/16/32 workers
5. **Heap size sweep** — 2 GB / 4 GB / 8 GB / 16 GB
6. **CPU pinning** — physical-only, single-CCD, both CCDs
7. **`perf stat`** — IPC, cache hits, DRAM fills
8. **`perf c2c`** — false-sharing detection via HITM events
9. **GC collector swap** — G1 vs ParallelGC vs ZGC vs Shenandoah

### Results summary

| # | Mechanism | Status | Key evidence |
|---|---|---|---|
| 1 | TLAB contention | **Refuted** | Per-packet TLAB refills 4.1 → 8.1 (2×), but refill is an atomic bump — not a scaling bottleneck |
| 2 | **L3 / DRAM stalls** | **CONFIRMED** | **IPC 3.02 → 0.88** (−3.4×), **DRAM fills/packet 73K → 228K** (+3.1×) |
| 3 | Memory bandwidth | Contributory | Aggregate ~13 GB/s allocation + 3× DRAM traffic per packet; shares root cause with (2). See Phase 1.5 #7. |
| 4 | GC | **Refuted** | Pause share 1.8% of wall time; 2 GB ↔ 16 GB heap unchanged; best GC swap (ParallelGC) +1.9% |
| 5 | False sharing | **Refuted** | 850 HITM events / 874K samples = 0.1%; top contended line 11 HITMs |
| 6 | SMT interference | **Refuted** | Physical-only pinning (16 workers) 1436 pps vs unpinned 16-worker 1443 pps — within noise |

### The smoking gun: `perf stat`

Single-threaded wcmp×128 vs parallel wcmp×128, both intra-packet off:

| Metric | Sequential | Parallel | Δ |
|---|---|---|---|
| Total instructions | 886 B | 870 B | ~identical |
| **IPC** | **3.02** | **0.88** | **−3.4×** |
| Loads (dispatched) | 245 B | 238 B | ~same |
| L2 fills (L2→L1) | 15.4 B | 13.0 B | ~same |
| L3 (local CCX) fills | 2.10 B | 2.34 B | ~same |
| **DRAM fills (all)** | **0.74 B** | **2.28 B** | **+3.1×** |
| **DRAM fills / packet** | 73K | 228K | **+3.1×** |
| Cross-CCD fills | 0 | 0 | — |

Read the top row carefully: **the algorithm executes the same number
of total instructions** (886 B vs 870 B for the same 10,000 packets).
Parallel isn't doing more work; it's doing the same work much slower
per cycle. IPC collapses from 3.02 (near-ideal for Zen 4) to 0.88 —
the hallmark of a memory-stall-bound workload.

L1 and L2 hit rates are essentially unchanged between modes. What
*does* change is the small tail of loads that miss all caches: **3×
more per packet** reach DRAM. Each DRAM miss is roughly 200 cycles of
stall. `(228K − 73K) × 200 cycles ≈ 31M extra cycles per packet`, or
roughly 6–9 ms of per-core stall (depending on effective frequency).
Parallel per-packet core-time is ~16 ms vs sequential ~5 ms, so the
extra DRAM stalls account for the bulk of the 11 ms gap.

### The scaling curve

Running wcmp×128 at varying `ForkJoinPool.common.parallelism`, all via
the parallel (streaming `InjectPackets`) path so per-RPC overhead is
amortized across the batch and only pure compute is being measured
(this is why the 1-worker baseline below is 229 pps, not the 207 pps
from the Current state table — the latter uses unary `InjectPacket`
which pays per-call gRPC cost):

| Workers | pps | Speedup vs 1 | Efficiency |
|---|---|---|---|
| 1 | 229 | 1.00× | 100% |
| 2 | 446 | 1.95× | 97% |
| **4** | **858** | **3.75×** | **94%** |
| **8** | **1235** | **5.39×** | **67%** |
| 16 | 1443 | 6.30× | 39% |
| 32 (SMT) | 1538 | 6.72× | 21% |

The curve has a **clear knee between 4 and 8 workers** (94% → 67% in
one doubling). That's a hard resource ceiling, not a graceful
falloff. Doubling from 16 to 32 threads recovers only 95 pps — SMT
provides almost no benefit on this workload, which is exactly what
you'd expect when siblings are both waiting on DRAM instead of
competing for execution units.

### CCD topology

The 7950X3D has two CCDs: 8 cores each, each with its own 32 MB L3,
one with an additional 64 MB 3D V-cache stack (96 MB total on that
CCD). Pinning experiments:

| Config | Cores | Workers | pps |
|---|---|---|---|
| CCD0 only (CPUs 0–7) | 8 | 8 | 1227 |
| CCD1 only (CPUs 8–15) | 8 | 8 | 1206 |
| Both CCDs, physical only | 16 | 16 | 1436 |

Three observations, with the punchline first:

1. **The two CCDs don't scale additively, implicating DRAM bandwidth.**
   CCD0 alone 1227 + CCD1 alone 1206 "should" sum to ~2400. Running
   both together: 1436. The second CCD contributes only 17% of its
   standalone capacity once the first is active. The only *relevant*
   shared resource for this workload is the memory controller / DRAM
   bandwidth — which is exactly what a DRAM-bound diagnosis predicts.

2. **Saturation within a single CCD.** 8 workers on one CCD ≈ 8
   workers unpinned (1227 ≈ 1235 from the scaling curve). The
   bottleneck fills up at 8 threads on a single CCD — within one L3
   domain, we hit the ceiling long before we run out of cores.

3. **3D V-cache doesn't help this workload.** CCD0 and CCD1 perform
   identically (1227 vs 1206). If V-cache were buying us anything,
   the V-cache CCD should be noticeably faster. Either the working
   set already exceeds 96 MB, or the allocation churn pattern doesn't
   benefit from the extra capacity. Either way: not a factor.

Also: `ls_any_fills_from_sys.far_cache` is **zero** in both modes. No
cross-CCD coherency traffic. The OS scheduler keeps threads on their
home CCD. Inter-CCD contention is not the story.

### Reframed diagnosis

The extra cycles on the Phase 1 parallel-overhead diff *do* happen
inside `HashMap.putVal` and `BigInteger.<init>`. They are not
allocator fast-path cycles. They are memory-stall cycles: each
`putVal` at +7% on the CPU profile is spending hundreds of cycles
waiting for its new node's cache line to arrive from DRAM, because
the L3 has been evicted by other threads doing the same thing.

**The root cause is L3 capacity exhaustion under concurrent allocation
churn.** Each wcmp×128 packet allocates on the order of 12 MB of
ephemeral state (128 fork branches × deep-copied `HeaderVal` and
`StructVal` maps, plus `BigInteger` intermediates from every bit-field
operation). Single-threaded, that working set fits comfortably in L2/L3.
With 8+ threads each doing the same, combined working sets exceed L3
capacity — even on the V-cache CCD's 96 MB — and what used to be L3
hits become DRAM round-trips.

**Implication for Phase 2.** The right metric to minimize is
**per-thread working-set size**, not allocation count. These correlate
but they aren't the same:

- `HashMap` preallocation eliminates resize churn (fewer allocations)
  but leaves the final map size unchanged → small working-set effect.
- Copy-on-write `HeaderVal` / `StructVal` leaves allocation count
  roughly the same but **shares** the underlying map across fork
  branches → potentially order-of-magnitude working-set reduction on
  fork-heavy workloads.
- A `Long` fast path replaces `BigInteger` (~40 bytes of header + payload)
  with a primitive `long` (8 bytes, often register-resident after escape
  analysis) → shrinks every bit-field value by ~5×.

The highest-leverage changes are the ones that reduce how many bytes
each thread *touches* per packet. Allocation-count optimizations are
the secondary axis, valuable only to the degree that they translate
into footprint reduction.

> **Phase 2 postscript:** this framing turned out to be only half
> right. See the next section — the byte-footprint-reduction hypothesis
> was tested end-to-end in #522 and capped at +6.3%. The IPC / DRAM
> stalls Phase 1.5 measured are real, but they're driven by per-fork
> wrapper-object *churn* (allocation count × cache-line thrash), not
> by the byte count of the resident state. The diagnosis above
> correctly identified the cache-pressure symptom; the
> working-set-bytes framing was the wrong lever to pull.

## Phase 2: flat-buffer attempt (refuted direction)

Phase 1.5 identified "per-thread working-set bytes exceed L3" as the
bottleneck and pointed at shrinking per-packet state as the primary
lever. [smolkaj/4ward#522](https://github.com/smolkaj/4ward/pull/522)
tested that hypothesis end-to-end: replaced the heap-of-objects packet
state (many `HashMap`s across `HeaderVal` / `StructVal`) with one
contiguous `PacketBuffer` per packet plus thin views at static offsets.
The packet's field data dropped from ~12 MB of `HeaderVal` graph to
~540 bytes of bit-packed buffer. Fork became one `buffer.copyOf()` plus
a `rewire()` walk instead of a Value-tree `deepCopy`.

**Measured outcome** (AMD Ryzen 9 7950X3D, best 3-run mean):

| Workload | Baseline | Flat buffer + consolidation | Δ |
|---|---|---|---|
| wcmp×128 parallel | 1,528 pps | 1,625 pps | **+6.3%** |
| wcmp×128 sequential | 207 pps | 200 pps | within noise |
| direct parallel | 39,800 pps | 39,743 pps | within noise |

Short of the 2-3× target by a wide margin. A `skipDeepCopy()` oracle
benchmark in the same session capped the maximum possible win from
fork-copy elimination at ~16% — the 2-3× target was incompatible with
a data-layout-only change from the start.

**What the flat-buffer result means for the diagnosis.** The packet's
raw field bytes were never the bottleneck. What Phase 1.5's IPC
counters detected is real, but the cache pressure comes from:

- **Per-fork wrapper-object churn.** `HeaderVal`, `StructVal`,
  `HashMap`, and scope entries — ~55 Java object allocations per fork
  branch, mostly unchanged by consolidating the underlying byte
  storage. Each allocation touches a fresh cache line; the allocator,
  GC, and JIT traversals dominate the L3 footprint.
- **Per-read Value-wrapper allocation on the interpreter hot path.**
  Every `target.fields[name]` returns a `BitVal` / `IntVal` / `BoolVal`
  object. Absent a cache, fork branches re-allocate these for every
  field they read. The flat-buffer PR's `BufferBackedFieldMap` per-slot
  cache is what preserved parity on direct workloads — bypassing it in
  any follow-on optimization regressed by 8-14%.

**Refined diagnosis.** Shrinking per-packet byte footprint has a low
ceiling (the `skipDeepCopy` oracle, ~16%). The real lever is the
**per-fork wrapper-object graph** and the **per-read `Value` allocation
on the interpreter hot path**. Those are interpreter-level concerns,
not data-layout concerns — the existing `HeaderVal` / `StructVal` /
`HashMap` representation is fine scaffolding for the follow-ups that
actually move the needle.

**Specific optimisations tested and reverted in #522** (numbers in the
retrospective):

- Pre-resolved `FieldAccess` fast path that bypassed the per-slot cache
  → −14%.
- Copy-on-write on the per-slot cache → −2% (shared-flag check outweighs
  save; SAI branches write enough).
- Identity-based rewire dedup for aliased scope entries → −2%.
- Array-backed cache indexed by slot position → −8%.

These are catalogued so future attempts don't re-run them. See
`designs/flat_packet_buffer.md` for the full retrospective.

## Phase 2.5: empirical profile

After #522 we finally ran the async-profiler baseline the earlier
phases should have started with. This section is the evidence base
for the candidate ordering in "Path forward".

### Method

```sh
bazel test //grpc:DataplaneBenchmark \
  --test_output=streamed --test_timeout=600 \
  --jvmopt="-DprofileMode=parallel" \
  --jvmopt="-DprofileWorkload=wcmp128" \
  --jvmopt="-Dfourward.simulator.intraPacketParallelism=false" \
  --jvmopt="-XX:+UnlockDiagnosticVMOptions" \
  --jvmopt="-XX:+DebugNonSafepoints" \
  --jvmopt="-agentpath:/path/to/libasyncProfiler.so=start,event=cpu,file=wcmp128.collapsed,output=collapsed,interval=1ms"
```

AMD Ryzen 9 7950X3D, SAI middleblock, 10k packets, 199,907 samples
across ~6.8 s of wall time. Flame graph in collapsed-stack format;
analysis below is by leaf method and call-site.

### High-level phase breakdown

| Phase | % of CPU |
|---|---|
| `runControl` (ingress/egress interpretation, excl. `applyTable`) | **46.4%** |
| `applyTable` (table lookup + action execution under the applyTable frame) | **25.9%** |
| `deepCopy` (`HeaderVal` / `StructVal` / `Environment`) | **13.4%** |
| PacketBroker / dispatch | 3.7% |
| proto trace events | 2.7% |
| gRPC / coroutines | 1.1% |
| other | 6.6% |

Interpretation: interpretation (46%) + table lookup (26%) + fork-copy
(13%) = 85% of CPU on the three compute phases inside the simulator.
Dispatch / coroutines / proto combined are <8% — not a bottleneck.

### Top cost categories (by leaf method)

| Category | % of CPU | Notes |
|---|---|---|
| `java.math.BigInteger` ops | **19.6%** | `BitVector.<init>` alone is **13.5%** — the single biggest hot spot |
| `java.util.HashMap` ops | **21.9%** | `getNode` (6.5%), `putVal` (3%), `resize` (2.9%), `Node.<init>` (2.6%), plus `LinkedHashMap` |
| proto builders | 7.7% | `BranchEvent.buildPartial0` (2.1%), `TraceEvent.buildPartial` (1.4%), `setSourceInfo` (1.3%) |
| `TableStore` | 6.2% | `scoreEntry` (3.7%), `lookup` (1.7%) |
| Interpreter `exec*` / `eval*` | 5.5% | Small — most interpreter work is below in HashMap/BigInteger |
| Kotlin collections | 3.3% | iterator overhead |

### Single biggest hot spot: `BitVector.<init>` at 13.5% of CPU

```kotlin
init {
  require(value >= BigInteger.ZERO) { ... }
  require(value < BigInteger.TWO.pow(width)) { ... }
  val longValue: Long = if (width <= LONG_WIDTH) value.toLong() else 0L
}
```

`TWO.pow(width)` allocates a fresh `BigInteger` per call. The `<`
comparison shells out to `compareTo` → `bitLength`. Every `BitVal`
creation pays this. A `Long` fast path for widths ≤ 63 (most P4
fields in practice) would eliminate most of it.

### Caller breakdown for the two dominant categories

**HashMap callers:**

| Caller | samples | % of total |
|---|---|---|
| `HeaderVal.deepCopy` | 9,831 | 4.9% |
| `Interpreter.applyTable` | 7,743 | 3.9% |
| `StructVal.deepCopy` | 7,695 | 3.9% |
| `Interpreter.evalFieldAccess` | 5,377 | 2.7% |
| `Environment.lookup` / `.define` / `.update` | 6,630 | 3.3% |

Fork-copy alone costs ~9% via HashMap work — matches #522's
`skipDeepCopy()` oracle measurement to within noise.

**BigInteger callers:**

| Caller | samples | % of total |
|---|---|---|
| `BitVector.<init>` | 26,960 | **13.5%** |
| `BitVector.concat` / `.slice` / `.binaryOp` | ~9,000 | ~4.5% |
| `Interpreter.emitHeader` | 2,166 | 1.1% |
| `Interpreter.evalLiteral` | 1,381 | 0.7% |

Almost all BigInteger time is under `BitVector` — the other callers
are small. The `Long` fast path is the single change that matters.

### The table-lookup surprise

`applyTable` is 25.9% of CPU. An earlier version of this doc had
"optimising lookup for scaling reasons" explicitly off the table,
reasoning that direct-L3 scales cleanly so lookup can't be a scaling
culprit. That reasoning is formally correct *per-lookup* but misses
the wcmp multiplier: wcmp×128 fork branches re-execute the whole
ingress/egress pipeline from the post-parser snapshot, so each
packet does ~128× as many table lookups as direct. Even if
per-lookup cost is fine, the multiplier makes lookup the #2 single
category on fork-heavy workloads.

`TableStore.scoreEntry` alone is 3.7% — the ternary-match scoring
loop. An absolute-throughput-driven lookup optimisation is still
scope-creep for the scaling effort, but in the wcmp context it's a
real lever that was mis-classified.

### Sequential vs parallel diff (what's actually scaling-specific)

The initial Phase 2.5 analysis above looks at "where does parallel
CPU go?" — but "efficiency" is defined as
`parallel_pps / (16 × sequential_pps)`, so a uniform CPU-time
reduction speeds up both sides proportionally and leaves efficiency
unchanged. Faster baseline, same scaling cliff.

To isolate the scaling cost, the same async-profiler run was
repeated in sequential mode and the two profiles were diffed. For
each leaf method, `excess = parallel_share − sequential_share`. A
positive excess is CPU that *only* appears in parallel — the
cache-line thrash / allocator contention / GC pressure that Phase
1.5's `perf stat` detected via IPC collapse. A near-zero or
negative excess means the method scales cleanly — optimising it
speeds up absolute throughput but doesn't close the scaling gap.

**Aggregated excess by category** (positive = scaling-specific):

| Category | Excess (parallel − sequential share) | % of parallel CPU |
|---|---|---|
| `BigInteger` allocation (`bitLength`, `<init>`, `pow`, `compareTo`, `shiftRight`, ...) | **+11.7%** | 18.2% |
| `HashMap`/`LinkedHashMap` allocation (`Node.<init>`, `newNode`, `resize`, `putVal`, `findKey`) | **+10.4%** | 16.0% |
| Proto trace-event builders (`BranchEvent.buildPartial0`, `TraceEvent.setSourceInfo`, `MapField.newMapField`) | **+6.6%** | 8.2% |
| Bounds checks / iterator boxing (`Preconditions.checkFromToIndex`, `ArrayList$Itr`) | **+4.1%** | 5.9% |
| Other simulator | +1.7% | 3.3% |
| GC (`G1ParScanThreadState`) | +0.6% | 1.0% |

Total excess ~35% of parallel CPU — the headroom above
linearly-scaling compute that a scaling-targeted optimisation could
address.

**What scales *well* (reaches the parallel side at lower cost share
than sequential)** — optimising these is absolute-throughput work,
not efficiency work:

| Method | Ratio (par-share / seq-share) | Note |
|---|---|---|
| `TableStore.scoreEntry` | **0.28** | 3.7% par / 13.1% seq — scales excellently |
| `TableStore.lookup` | 0.52 | 1.7% par / 3.3% seq |
| `String.equals` | 0.52 | 2.9% par / 5.5% seq |
| `HashMap.getNode` | 0.73 | 6.5% par / 8.8% seq — read-only lookup scales well |

**Implication for the north star.** Roughly 35% of parallel CPU is
scaling-specific overhead. Fully eliminating it is the ceiling for
efficiency-targeted work. At the measured ~45% Phase 1 efficiency
baseline, collapsing that overhead would take efficiency to
~1/(1−0.35) × 45% ≈ 69%. Hitting the ≥80% north star realistically
requires that plus some absolute-compute speedup whose savings
fall disproportionately on the parallel side (e.g., allocation
reduction rather than arithmetic speedup).

**Caveat on absolute numbers.** The async-profiler run here lowers
observed sequential throughput (~102 pps vs Phase 1.5's 207 pps
without profiler) more than parallel throughput. The diff analysis
is robust to this because it uses *shares*, not absolute counts —
the profiler-induced cost is additive to both, and the shapes of
the top-leaf distributions stay intact — but rerun without profiler
for any absolute-efficiency claims.

## Path forward

**Discipline.** Performance work is bounded by simplicity. The project
rules are explicit: "correctness over performance" and "readability over
performance." An optimization that makes the code harder to read or
reason about is not worth it, even if the benchmark improves. Each step
must clear that bar before it lands.

**Approach.** Measure first, optimize second — same mantra that governed
the lock-free dataplane work (where we discovered the lock wasn't the
bottleneck after all). **Profile with async-profiler before committing
to a direction.** Phase 2 is a case study in what happens when you skip
that step: the flat-buffer rewrite was a week of work motivated by a
hypothesis a 15-minute flame graph would have put lower on the list.

**Approach, concretely.** Build a "what if this optimization were
free?" oracle benchmark before writing the optimization. In Phase 2's
case, `skipDeepCopy()` was a three-line change that would have capped
the project's upside at 16% in Phase 1. Use similar oracles (stub out
table lookup, stub out `evalExpr`) to bound the ceiling on any future
candidate before starting.

**Scope.** Direct L3 is already at 94% efficiency with no meaningful
work left to do. "Optimize" here means closing the wcmp×128 gap —
bringing 45% efficiency closer to linear.

**What to optimize for.** Two axes, scored independently — the
north star is scaling efficiency, but sequential speedup is still
valuable on its own merits (CLI, single-packet latency, raw
throughput on embarrassingly parallel workloads that already scale
well). Every candidate below gets both scores:

- **Efficiency lever** — does it reduce the allocation / contention
  costs that appear *disproportionately* in parallel mode (the
  "excess" in Phase 2.5's diff)? These move the scaling ratio.
- **Sequential speedup** — does it reduce baseline per-packet CPU?
  Speeds up both modes proportionally; doesn't improve efficiency
  by itself but benefits the CLI / single-packet latency and
  shortens test-suite wall time.

Candidate optimizations (scored by Phase 2.5 diff; rerun the
profile and the oracle before committing — ceilings are from this
specific profile on this specific hardware):

| # | Candidate | Efficiency lever? | Sequential speedup? | Ceiling |
|---|---|---|---|---|
| 1 | **`Long` fast path for narrow bit fields** | **Yes** — directly kills `BigInteger` allocation (+11.7% excess) and the `BigInteger.*` methods that dominate both the scaling diff AND the absolute profile | **Yes** — `BitVector.<init>` is 13.5% of absolute CPU | Efficiency: ~12%. Absolute: ~20-25%. |
| 2 | **Wrapper pooling / arena per fork** (thread-local scratch `HeaderVal` / `BufferBackedFieldMap` / scope HashMap) | **Yes** — directly kills `HashMap$Node.<init>`, `LinkedHashMap.newNode`, `HashMap.resize` (+10.4% excess combined) | Small — `HashMap.getNode` is read-only and scales fine; pooling benefits are mostly parallel-mode | Efficiency: ~10%. Absolute: ~5%. |
| 3 | **Persistent-map COW** for `HeaderVal` / `StructVal` | Partial — overlaps with (2); fork-copy is 13% of parallel CPU | Similar | Efficiency: ≤~10% (overlaps with (2), not additive). |
| 4 | **Trim proto trace-event construction** — build only what's actually read, lazy-init trace builders for ignored branches | **Yes** — builders are +6.6% excess (scales disproportionately with fork count) | Small — trace events are 2.7% of sequential CPU | Efficiency: ~5%. Absolute: ~3%. |
| 5 | **Pre-resolve `FieldAccess` with an interpreter-scoped cache** (corrected version of the #522 attempt) | Marginal — `HashMap.getNode` scales well (ratio 0.73); savings mostly absolute-throughput | **Yes** — `evalFieldAccess` HashMap is 2.7% of absolute CPU | Efficiency: ~1%. Absolute: ~3%. |
| 6 | **Optimise `TableStore.scoreEntry`** (ternary match inner loop) | **No** — scoreEntry scales *excellently* (ratio 0.28, 13% sequential → 4% parallel) | **Yes, large** — 3.7% parallel, 13% sequential | Efficiency: ~0. Absolute: ~5-10% depending on approach. |
| 7 | **`HashMap` preallocation in `deepCopy`** | Small — attacks `HashMap.resize` (+1.5% excess) | Small | Efficiency: ~2%. Absolute: ~2%. |

**Ordering interpretation.** If the goal is **scaling efficiency**,
rank (1) → (2) → (3) → (4) → (7), with (5) and (6) largely off the
efficiency table. If the goal is **absolute wcmp×128 throughput**
(still useful for test-suite time, replay scenarios), (1) and (6)
are the biggest single levers, (2)-(4) follow.

**The honest realization.** No single candidate gets to ≥80%
efficiency. The Phase 2.5 diff measured ~35% of parallel CPU as
scaling-specific excess; eliminating all of it would take the
measured 45% efficiency to ~69%, still short of the north star. The
remaining gap is per-core compute that scales cleanly plus residual
memory-bandwidth limits at >8 threads. Closing to 80% likely needs
(1)+(2)+(3) stacked, re-profiled between each landing (each change
shifts where the next bottleneck is).

**Before committing to anything:** the simplicity budget matters. The
lock-free dataplane PR was a clear simplification AND had measurable
value (correctness improvement). A performance-only PR that adds
complexity for a bounded gain needs a stronger justification: a concrete
DVaaS workload that's blocked by the current throughput.

**Explicitly off the table:**

- **Replacing `for (x in list)` with index-based loops.** Kotlin idiom
  is the for-loop; index loops are a readability regression. Iterator
  allocation is real but not the current bottleneck.
- ~~**Optimizing lookup (`toUnsignedLong`, hash index, LPM trie) for
  scaling reasons.**~~ **Removed by Phase 2.5.** This was originally
  off the table on the reasoning that direct-L3 scales cleanly, so
  per-lookup cost can't be a scaling culprit. That logic holds for
  direct; it doesn't for wcmp×128, where each packet does ~128×
  more lookups (fork branches re-run ingress/egress from the
  post-parser snapshot). Phase 2.5 measured `applyTable` at 26% of
  CPU. Lookup optimisation is now candidate (2) in "Path forward".
- **Disabling intra-packet parallelism.** Throughput-neutral in the
  parallel (inter-packet) mode (1,506 vs 1,501 pps — noise) but 3×
  regression in single-packet latency. Keep it for CLI/STF/playground
  users.

**Validation.** For every optimization, run the benchmark and verify:

1. **True single-thread throughput doesn't regress** — measured with
   intra-packet parallelism disabled (set
   `-Dfourward.simulator.intraPacketParallelism=false`).
2. **Parallel scaling efficiency improves** — the ratio
   `parallel_pps / (single_thread_pps × 16)` moves toward 1.0.
3. **No correctness regressions** — all tests still pass.

**Done when:** wcmp×128 parallel efficiency reaches ≥80% of linear on
16 physical cores. This is the north star; sequential speedup is
welcome but doesn't count toward it (a uniform CPU-time reduction
raises both single-thread and parallel pps proportionally and
leaves efficiency unchanged). Phase 2.5 evidence says hitting 80%
likely needs candidates (1)+(2)+(3) stacked — no single lever gets
there on its own.
## Phase 3: structural candidates

Phase 2.5's incremental work (Long-backed `BitVector`,
`CompactFieldMap`, proto builder pooling, map preallocation) moved
wcmp×128 efficiency from 45% to ~60% — a +15 pp gain from ~360
lines of code. But the profile is now diffuse: no single method
exceeds 7% of CPU. Further gains require structural changes to how
the interpreter executes fork branches, not more micro-optimization
of individual methods.

Three observations motivate the candidates below:

1. **The interpreter re-executes the full pipeline per fork branch.**
   128 branches × full ingress+egress pipeline walk. But branches
   share >95% of their state — only the selected action's ~5 field
   writes diverge. We pay full interpretation cost for a small delta.

2. **Fork-copy allocates a new value tree even though most values
   are never written.** Each branch copies ~200 field values; ~5
   actually change. `CompactFieldMap` made the copy cheaper (array
   copy vs HashMap node allocation), but it's still copying 200
   values when only 5 will diverge.

3. **Proto trace events are constructed 128× for mostly-identical
   content.** The pre-fork prefix events are deterministic replays;
   post-fork events differ only in the selected action's trace. Full
   proto messages are built for all of them.

### Candidates

| | Candidate | Ceiling | Effort | Best for |
|---|---|---|---|---|
| **A** | Fork-on-write overlay | ~5-7% CPU, several pp efficiency | Medium | Next incremental step toward 80% |
| **B** | Skip trace construction during fork prefix replay | ~6% CPU | Medium | Quick standalone win |
| **C** | Compiled instruction sequence (flatten the AST walk) | ~15-20% CPU | Very high | "One big bet" choice |
| **D** | Thread-local fork state pool | ~5-7% CPU | Medium | Alternative to A |
| **E** | Deferred trace serialization (lightweight internal events) | ~8-10% CPU | High | Stacks after A or D |
| **F** | Reactive / dataflow evaluation (re-evaluate only changed dependencies) | Huge (theoretical) | Very high | Research project |

### A. Fork-on-write overlay

Instead of deep-copying headers/structs at fork time, share the base
value tree across all branches. Each branch gets a lightweight
overlay map that intercepts writes. Reads check the overlay first,
then fall through to the shared base.

For SAI wcmp×128: each branch writes ~5 fields (nexthop, egress
port, MAC rewrites, TTL decrement) out of ~200 total. The overlay
per branch is ~5 entries vs ~200 copied values today. Fork-copy
becomes O(mutations) instead of O(total fields).

Implementation: a two-layer `OverlayFieldMap` wrapping the base
`CompactFieldMap`. The interpreter's mutation pattern
(`target.fields[name] = value`) stays unchanged; only the map
implementation underneath changes. `deepCopy` creates a new empty
overlay sharing the same base — no per-entry work.

This is architecturally simpler than persistent collections (no
trie, no structural sharing) while capturing the same fundamental
win: don't copy state that won't be written.

### B. Skip trace construction during fork prefix replay

Fork branch re-executions replay the first `prefixLength` events
deterministically (same control flow, same table results, same
trace output), then drop them via `levelEvents.drop(prefixLength)`.
Those events are constructed-then-discarded — pure waste.

Add a skip counter to `PacketContext`. Guard each
`addTraceEvent` / `addLightEvent` call site with a
`packetCtx.isTracing` check that returns false while the counter
is positive. When skipping, the event isn't constructed at all —
saves the proto builder + message allocation.

Requires changing the trace tree format: branch subtrees would
start at the fork point, not at event 0. Consumers (STF runner,
CLI formatter, web playground) need to handle the stripped format.
The pre-fork events are already in the outer-level trace; including
them again in each branch subtree is redundant information.

### C. Compiled instruction sequence

At pipeline load: walk the proto AST once and compile each
expression / statement into a flat `Array<Instruction>` where each
`Instruction` is a sealed class with pre-resolved field indices,
pre-computed literal values, and direct function references.
Runtime: iterate the array and dispatch on the instruction type.
No proto field access, no `evalExpr` recursion, no `hasFieldAccess`
checks, no `env.lookup` for known variables.

Eliminates the ~10% interpreter-dispatch cost AND the HashMap /
String.equals cost from field-name lookups (field accesses become
`fieldValues[precomputedIndex]`). Highest single-change ceiling of
any candidate.

Also the largest refactor — essentially rewriting the interpreter.
The existing interpreter (proto-walking, HashMap-backed) would
remain as a reference / fallback; the compiled path runs when the
pipeline's IR is fully resolvable.

### D. Thread-local fork state pool

Pre-allocate one complete value tree (Environment + all
HeaderVal / StructVal / CompactFieldMap instances) per worker
thread. At fork time: `System.arraycopy` the snapshot's field
values into the pool's arrays, rebind scope variables. No object
allocation per fork — just array copies and pointer reassignments.
After the branch completes: return the state to the pool.

Similar ceiling to (A) but a different tradeoff: no overlay-
dispatch overhead per read, but requires lifecycle management
(borrow / return protocol, reset between uses).

### E. Deferred trace serialization

Store trace events as lightweight Kotlin sealed classes
(`LightBranch`, `LightTableLookup`, etc.) throughout the fork path.
Convert to proto only at the final API boundary (gRPC
`InjectPacketResponse`, CLI trace output).

Differs from the experiment-B attempt (which regressed -10%)
because experiment B converted per-branch via `getEvents()`. This
candidate defers conversion to once-per-packet at the outermost
`buildTraceTree` call — 128× less conversion work.

Requires refactoring the internal `TraceTree` representation to
carry lightweight events, converting to proto only at serialization
time.

### F. Reactive / dataflow evaluation

Instead of re-executing the full pipeline per fork branch, track
which fields the selected action modifies and only re-evaluate
expressions that depend on those fields. Tables whose keys are
unchanged reuse the first execution's result; if-conditions on
unchanged values don't re-evaluate.

Highest theoretical ceiling (could make 128 branches nearly as
cheap as 1), but extremely complex — requires full dependency
tracking across the P4 program. Research-project scope.

### What we actually shipped (Phase 3 results)

**Fork-point resume (#567)** superseded both Candidate A (fork-on-write
overlay) and Candidate B (skip prefix events). Instead of optimizing
the deep copy (A) or skipping trace construction during replay (B), it
eliminates prefix replay entirely — each branch continues from the fork
point, producing only its own events. No prefix stripping, no replay
infrastructure, no `BranchMode` dispatch.

Measured on the same hardware (AMD Ryzen 9 7950X3D), same workload
(SAI P4, 10k routes, intra-packet parallelism off):

| Workload | Baseline | After Phase 3 | Sequential Δ | Parallel Δ |
|----------|----------|---------------|-------------|------------|
| wcmp×128 seq | 207 pps | 347 pps | **+68%** | — |
| wcmp×128 par | 1,501 pps | 3,825 pps | — | **+155%** |
| direct seq | 2,568 pps | ~2,500 pps | ≈0% | — |
| direct par | 38,449 pps | ~38,000 pps | — | ≈0% |

Direct L3 is unaffected (no forks, nothing to optimize). wcmp×128
sees the full benefit: +68% sequential, +155% parallel. Efficiency
improved from 45% to 72%.

**Remaining candidates.** C (compiled instruction sequence) and E
(deferred trace serialization) are still viable for further gains but
are higher effort.


## Non-goals

- **Beating BMv2 by more than we already do.** BMv2 is 6× slower on L3
  forwarding per the existing benchmark. Further widening the gap is
  nice, not required.
- **Optimizing at the expense of correctness or readability.** The
  project rule is "correctness over performance" and "readability over
  performance." An optimization that obfuscates the trace tree semantics
  or makes the simulator harder to reason about is not worth it.
- **Optimizing cold paths.** Pipeline loading, table writes, error
  handling — these are rare and their latency doesn't matter.

## Open questions

- **Why is parallel direct at 65% CPU utilization, not 100%?** The JVM
  has idle capacity that isn't being used — meaning the bottleneck isn't
  compute, it's dispatch (gRPC, coroutines, or `ForkJoinPool` task
  submission serial fraction). Not worth chasing — direct is already at
  94% efficiency despite leaving cores idle.
- ~~How much of the interpreter's per-fork cost is field access vs.
  table lookup vs. action dispatch?~~ **Answered by Phase 2.5.** See
  the "High-level phase breakdown" table: `runControl` (excl.
  `applyTable`) is 46%, `applyTable` is 26%, fork-copy is 13%.
  Within `applyTable`, lookup itself is ~10% and action execution
  is ~6%. Within `runControl`, the bulk of cost is
  `BitVector.<init>` (13.5%) and `HashMap` operations for field /
  scope access (~6%).
- **Does the `Long` fast path stack cleanly with a lookup
  optimisation, or do their gains overlap?** `BitVector.<init>`
  fires in both the interpreter and the lookup path (`scoreEntry`
  constructs `BitVector`s to compare keys). If (1) lands first,
  some of (2)'s cost goes away with it. Worth measuring (2)'s
  oracle after (1) is in, not before.
- **Why doesn't the 7950X3D's V-cache help?** Both CCDs measured
  identically despite one having 3× the L3 capacity. Possibilities: the
  working set genuinely exceeds 96 MB (but see Phase 2 — shrinking byte
  footprint didn't help, so the V-cache shouldn't have either);
  V-cache allocation policy disfavors short-lived allocation churn; or
  the test process was scheduled onto the non-V-cache CCD. Consistent
  with the refined diagnosis that cache-line churn, not total bytes,
  is the bottleneck.
