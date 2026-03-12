# commons-graph Performance

Paired design: `storage.design.md`

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), JDK 8, G1GC with `-XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m`.
Each metric is the **median of 3 independent JVM invocations** per the benchmark reliability standards.

Run with:
```bash
./gradlew :graph:test --tests "*.Phase1BenchmarkTest" -PincludePerformanceTests --rerun
./gradlew :graph:test --tests "*.StoragePerformanceTest" -PincludePerformanceTests --rerun
./gradlew :graph:test --tests "*.GraphPerformanceTest" -PincludePerformanceTests --rerun
```

JVM flags: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m -XX:InitiatingHeapOccupancyPercent=45`

> **Note**: NativeStorageImpl uses row-oriented storage (`Map<NodeID, MutableMap<String, IValue>>`).
> P6-5 columnar optimization was reverted due to functional test incompatibility.

### Graph-Level Query Operations (10K nodes, 50K edges, 100K queries)

| Storage | getNode | getOutEdges | getChildren | getDescendants |
|---|---|---|---|---|
| NativeStorage | 31.21M | 7.04M | 4.61M | 323.4K |
| NativeConcurStorage | 33.58M | 7.78M | 4.18M | 144.9K |
| LayeredStorage | 53.27M | 7.72M | 4.51M | 508.4K |

### Graph Population (median ms)

| Storage | 10K/30K | 100K/300K |
|---|---|---|
| NativeStorage (MultipleGraph) | 5.0 | 108.8 |
| NativeConcurStorage (MultipleGraph) | 5.5 | 84.8 |
| LayeredStorage (MultipleGraph) | 4.9 | 82.7 |
| NativeStorage (SimpleGraph) | — | 104.6 |
| NativeConcurStorage (SimpleGraph) | — | 124.9 |
| LayeredStorage (SimpleGraph) | — | 100.8 |

### Storage-Level Operations

#### Node Operations

| Implementation | Lookup (500K ops) | Add 1M | Delete (2K from 10K) |
|---|---|---|---|
| NativeStorageImpl | 83.38M | 3.15M | 3.05M |
| NativeConcurStorageImpl | 60.37M | 3.49M | 3.14M |
| LayeredStorageImpl | 81.96M | 4.50M | 2.98M |

#### Edge Operations

| Implementation | Add 1M | Outgoing Query | Incoming Query |
|---|---|---|---|
| NativeStorageImpl | 2.11M | 27.78M | 75.03M |
| NativeConcurStorageImpl | 1.99M | 25.00M | 55.67M |
| LayeredStorageImpl | 1.79M | 46.57M | 43.31M |

> ⚠ Edge query numbers show high variance across JVM invocations (JIT cross-contamination).

#### Property Read/Write (200K ops on 50K nodes)

| Implementation | Read | Write |
|---|---|---|
| NativeStorageImpl | 22.14M | 18.89M |
| NativeConcurStorageImpl | 37.00M | 16.86M |
| LayeredStorageImpl | 43.84M | 18.20M |

> ⚠ Property read/write shows high variance (up to 2x) across JVM invocations due to JIT cross-contamination from sequential test execution in the same JVM.

#### Population (Storage-Level, median ms)

| Implementation | 10K/30K | 100K/300K | 1M/3M |
|---|---|---|---|
| NativeStorageImpl | 4.9 | 121.9 | 3106.2 |
| NativeConcurStorageImpl | 5.4 | 135.2 | 3174.2 |
| LayeredStorageImpl | 6.1 | 105.2 | 3396.8 |

#### Mixed Workload (50K iterations, median ms)

| Implementation | median ms |
|---|---|
| NativeStorageImpl | 17.6 |
| NativeConcurStorageImpl | 26.0 |
| LayeredStorageImpl | 23.0 |

### LayeredStorage Multi-Layer Query (100K queries, 10K nodes/layer)

| Layers | containsNode | getProps |
|---|---|---|
| 1 | 82.11M | 54.67M |
| 3 | 62.67M | 43.82M |
| 5 | 52.87M | 36.07M |
| 10 | 30.56M | 22.24M |

### Lattice Operations (5K nodes, 15K edges, 5 labels)

| Storage | assignLabels (ms) | filteredQuery (ops/s) | storeLattice (ms) |
|---|---|---|---|
| NativeStorage | 5.2 | 971.0K | 1.6 |
| NativeConcurStorage | 3.5 | 1.09M | 0.5 |
| LayeredStorage | 3.9 | 1.06M | 0.4 |

### Fixpoint State Access (P0, 50K nodes, 200K iterations)

| Approach | Read | Write |
|---|---|---|
| IStorage (HashMap) | 30.36M | 34.18M |
| Direct array (simulated) | 2060.96M | 169.40M |
| Speedup | 68x | 5x |

> P0 run 1 was discarded as outlier (>20% deviation: IStorage read 80.25M, Direct read 415.73M).

### Memory (50K nodes, 150K edges)

| Metric | MB |
|---|---|
| Storage only | 69.0 |
| Storage + graph | 69.6 |
| Graph overhead | 0.6 |

### Fixpoint Memory (P0, 100K nodes)

| Approach | MB |
|---|---|
| IStorage (HashMap per node) | 39.7 |
| Direct Array\<IValue\> | 3.4 |
| Savings | 11.6x |

---

## JDK 8 vs JDK 21 Comparison

Median of 3 independent JVM invocations per JDK version. Same hardware, same G1GC flags.
JDK 8: Azul Zulu 8.0.442 (HotSpot). JDK 21: Eclipse Temurin 21.0.6+7 (HotSpot).

> **Note**: Earlier benchmarks incorrectly used GraalVM CE 21.0.2 (Graal JIT) instead of HotSpot,
> producing misleading regressions on getNode (-26% to -34%) and ConcurrentHashMap lookup (-60%).
> The data below uses the correct HotSpot C2 JIT on both JDKs.

### Graph-Level Query Operations (10K nodes, 50K edges, 100K queries)

| Storage | Metric | JDK 8 | JDK 21 | Delta |
|---|---|---|---|---|
| NativeStorage | getNode | 31.21M | 40.25M | **+29%** |
| NativeStorage | getOutEdges | 7.04M | 8.21M | +17% |
| NativeStorage | getChildren | 4.61M | 4.29M | -7% |
| NativeStorage | getDescendants | 323.4K | 370.8K | +15% |
| NativeConcurStorage | getNode | 33.58M | 46.82M | **+39%** |
| NativeConcurStorage | getOutEdges | 7.78M | 7.92M | +2% |
| NativeConcurStorage | getChildren | 4.18M | 4.62M | +11% |
| NativeConcurStorage | getDescendants | 144.9K | 156.2K | +8% |
| LayeredStorage | getNode | 53.27M | 69.12M | **+30%** |
| LayeredStorage | getOutEdges | 7.72M | 8.16M | +6% |
| LayeredStorage | getChildren | 4.51M | 4.67M | +4% |
| LayeredStorage | getDescendants | 508.4K | 961.8K | **+89%** |

### Storage Node Operations

| Implementation | Metric | JDK 8 | JDK 21 | Delta |
|---|---|---|---|---|
| NativeStorageImpl | Lookup | 83.38M | 83.42M | +0% |
| NativeStorageImpl | Delete | 3.05M | 2.17M | **-29%** |
| NativeConcurStorageImpl | Lookup | 60.37M | 61.95M | +3% |
| NativeConcurStorageImpl | Delete | 3.14M | 3.14M | +0% |
| LayeredStorageImpl | Lookup | 81.96M | 84.79M | +3% |
| LayeredStorageImpl | Delete | 2.98M | 3.30M | +11% |

### Property Read/Write (200K ops on 50K nodes)

| Implementation | Metric | JDK 8 | JDK 21 | Delta |
|---|---|---|---|---|
| NativeStorageImpl | Read | 22.14M | 55.14M | **+149%** |
| NativeStorageImpl | Write | 18.89M | 14.43M | **-24%** |
| NativeConcurStorageImpl | Read | 37.00M | 16.33M | **-56%** |
| NativeConcurStorageImpl | Write | 16.86M | 9.76M | **-42%** |
| LayeredStorageImpl | Read | 43.84M | 15.84M | **-64%** |
| LayeredStorageImpl | Write | 18.20M | 7.72M | **-58%** |

> ⚠ Property read/write exhibits up to 2x variance on both JDKs due to JIT cross-contamination.
> NativeStorage read +149% vs Concur/Layered read -56%/-64% is not a JDK effect — it reflects
> JIT compilation order noise within the same JVM. These numbers are unreliable for JDK comparison.

### Population (median ms, lower = better)

| Implementation | Scale | JDK 8 | JDK 21 | Delta |
|---|---|---|---|---|
| NativeStorageImpl | 1M/3M | 3106.2 | 2218.3 | **-29%** |
| NativeConcurStorageImpl | 1M/3M | 3174.2 | 2233.6 | **-30%** |
| LayeredStorageImpl | 1M/3M | 3396.8 | 2249.2 | **-34%** |

### Graph Population (median ms, lower = better)

| Storage | JDK 8 | JDK 21 | Delta |
|---|---|---|---|
| NativeStorage (Multiple 100K/300K) | 108.8 | 91.7 | -16% |
| NativeConcurStorage (Multiple 100K/300K) | 84.8 | 77.2 | -9% |
| LayeredStorage (Multiple 100K/300K) | 82.7 | 80.4 | -3% |
| NativeStorage (Simple 100K/300K) | 104.6 | 98.5 | -6% |
| NativeConcurStorage (Simple 100K/300K) | 124.9 | 90.4 | **-28%** |
| LayeredStorage (Simple 100K/300K) | 100.8 | 85.0 | -16% |

### Memory (50K nodes, 150K edges)

| Metric | JDK 8 | JDK 21 | Delta |
|---|---|---|---|
| Storage only | 69.0 MB | 71.0 MB | +3% |
| IStorage per node (100K) | 39.7 MB | 40.4 MB | +2% |

### Fixpoint State Access (P0, 50K nodes, 200K iterations)

| Approach | Metric | JDK 8 | JDK 21 | Delta |
|---|---|---|---|---|
| IStorage | Read | 30.36M | 96.32M | **+217%** |
| IStorage | Write | 34.18M | 30.19M | -12% |
| Direct array | Read | 2060.96M | 402.92M | **-80%** |
| Direct array | Write | 169.40M | 104.20M | **-38%** |

> ⚠ P0 numbers are heavily affected by JIT noise. JDK 8 P0 run 1 was discarded as an outlier
> (IStorage read 80.25M vs median 30.36M). JDK 21 IStorage read (96.32M) is consistent with JDK 8's
> outlier run, suggesting all P0 variation is JIT-driven, not JDK-driven. Direct array read difference
> (-80%) reflects different JIT optimization of trivial array access patterns between JDK versions.

### JDK 21 Analysis Summary

**JDK 21 improvements (consistent across runs):**
- **getNode +29% to +39%** — HotSpot C2 JIT in JDK 21 produces better speculative inlining for HashMap.get and virtual dispatch paths
- **getDescendants +15% to +89%** — BFS traversal benefits from improved JIT optimization of ArrayDeque and iterator patterns
- **Bulk population 29-34% faster** — G1GC improvements in JDK 21 reduce pause overhead during bulk allocation
- **Graph population 3-28% faster** — consistent improvement across all storage types and graph types

**JDK 21 regressions:**
- **NativeStorage node delete -29%** — HashMap removal path regression (isolated to NativeStorage; other impls unchanged or improved)

**Inconclusive (JIT cross-contamination noise):**
- Property read/write: contradictory results (+149% on Native, -56% on Concur) indicate JIT compilation order, not JDK behavior
- P0 fixpoint: IStorage read +217% but this matches JDK 8's discarded outlier; likely JIT warm-up artifact
- Edge query: not re-tested in this round; known >2x variance

**Recommendation**: JDK 21 (HotSpot Temurin) is **recommended** over JDK 8 for this workload. The read-heavy hot paths (getNode, getOutEdges, getDescendants) are all faster, and bulk population shows consistent 29-34% improvement. The only regression (NativeStorage delete -29%) is on a cold path. Memory footprint is unchanged (+2-3%).

---

## HotSpot C2 vs GraalVM CE (JDK 21)

Both on JDK 21, same hardware, same G1GC flags. Median of 3 independent JVM invocations.
HotSpot C2: Eclipse Temurin 21.0.6+7. GraalVM CE: GraalVM Community 21.0.2+13-jvmci-23.1-b30.

### Graph-Level Query Operations (10K nodes, 50K edges, 100K queries)

| Storage | Metric | GraalVM CE | HotSpot C2 | Delta |
|---|---|---|---|---|
| NativeStorage | getNode | 23.05M | 40.25M | **+75%** |
| NativeStorage | getOutEdges | 7.90M | 8.21M | +4% |
| NativeStorage | getChildren | 4.04M | 4.29M | +6% |
| NativeConcurStorage | getNode | 22.12M | 46.82M | **+112%** |
| NativeConcurStorage | getOutEdges | 7.18M | 7.92M | +10% |
| NativeConcurStorage | getDescendants | 693.7K | 156.2K | **-77%** |
| LayeredStorage | getNode | 34.99M | 69.12M | **+97%** |
| LayeredStorage | getOutEdges | 5.50M | 8.16M | **+48%** |
| LayeredStorage | getDescendants | 861.5K | 961.8K | +12% |

### Storage Node Operations

| Implementation | Metric | GraalVM CE | HotSpot C2 | Delta |
|---|---|---|---|---|
| NativeStorageImpl | Lookup | 88.51M | 83.42M | -6% |
| NativeStorageImpl | Delete | 1.55M | 2.17M | **+40%** |
| NativeConcurStorageImpl | Lookup | 24.30M | 61.95M | **+155%** |
| LayeredStorageImpl | Lookup | 69.51M | 84.79M | **+22%** |

### Population (median ms, lower = better)

| Implementation | Scale | GraalVM CE | HotSpot C2 | Delta |
|---|---|---|---|---|
| NativeStorageImpl | 1M/3M | 2340.1 | 2218.3 | -5% |
| NativeConcurStorageImpl | 1M/3M | 2271.5 | 2233.6 | -2% |
| LayeredStorageImpl | 1M/3M | 2397.5 | 2249.2 | -6% |

### Analysis

**HotSpot C2 advantages:**
- **getNode +75% to +112%** — C2's speculative inlining of `HashMap.get` and virtual dispatch is significantly more effective than Graal CE's compilation strategy for this call pattern
- **ConcurrentHashMap single-threaded lookup +155%** — Graal CE severely underperforms on ConcurrentHashMap's VarHandle-based read path; C2 optimizes this to near-HashMap speed
- **LayeredStorage getOutEdges +48%** — UnionSet/LazyMergedMap view traversal benefits from C2's type profiling

**GraalVM CE advantages:**
- **NativeConcurStorage getDescendants +345%** (693.7K vs 156.2K) — Graal's partial escape analysis likely eliminates intermediate iterator/ArrayDeque allocations in BFS traversal more aggressively than C2
- **NativeStorage Lookup +6%** — marginal, within noise

**Conclusion**: For HashMap/ConcurrentHashMap-intensive graph workloads, HotSpot C2 is the clear winner. GraalVM CE's advantage on getDescendants (BFS traversal with heavy allocation) suggests Graal's partial escape analysis is superior for allocation-heavy loops, but this does not compensate for the 75-155% regression on the dominant hot paths. GraalVM Enterprise (Oracle GraalVM) may close the gap but was not tested.

---

## Key Improvements

| Phase | ID | Optimization | Target Metric | Delta |
|---|---|---|---|---|
| 1 | B8 | Redundant ID set removal | graph.containNode | +36% (34.01M -> 46.15M) |
| 1 | B8 | Redundant ID set removal | graph overhead memory | -87% (19.1 MB -> 2.5 MB) |
| 1 | P0 | Analysis state externalization | fixpoint read | 42-62x (array vs HashMap) |
| 1 | P0 | Analysis state externalization | fixpoint memory | 13.1x (45.0 MB -> 3.4 MB) |
| 1 | P4 | G1GC tuning | GC pause target | capped at 200ms |
| 2 | B5-B | Merge on freeze | containsNode (10 layers) | +2.4x (7.51M -> 18.00M) |
| 2 | B5-B | Merge on freeze | getProps (10 layers) | +4.2x (4.08M -> 17.00M) |
| 2 | B4-B | LazyMergedMap | property read | +2.6x (14.27M -> 37.19M) |
| 2 | B6-A | UnionSet for edge queries | outgoing edge query | +5.6x (3.93M -> 21.99M) |
| 2 | B6-A | UnionSet for edge queries | incoming edge query | +3.4x (4.24M -> 14.31M) |
| 2 | — | Combined Phase 2 | mixed workload | -14% latency (51.8 ms -> 44.4 ms) |
| 3 | B7 | NodeID intern pool | getNode (Layered) | +14% (21.34M -> 24.15M) |
| 3 | B1-A | EdgeID intern pool | getNode (NativeConcur) | +26% (15.64M -> 19.65M) |
| 3 | B3-A | Wrapper cache (read-only) | getNode (NativeConcur) | +56% (17.29M -> 27.01M) |
| 3 | B3-A | Wrapper cache (read-only) | getOutEdges (Layered) | +60% (4.07M -> 6.50M) |
| 3 | B3-A | Wrapper cache (read-only) | getDescendants (Layered) | +26% (714.2K -> 898.9K) |
| 5 | P5-3 | Redundant isClosed elimination | NativeStorage node delete | +10% (1.45M -> 1.59M) |
| 5 | P5-4 | Inlined containsNode checks | LayeredStorage property write | +7% (15.08M -> 16.08M) |
| 6 | P6-6 | LinkedList -> ArrayDeque in BFS | getDescendants (NativeStorage) | +27% (325.5K -> 412.5K) |
| 6 | P6-6 | LinkedList -> ArrayDeque in BFS | getDescendants (LayeredStorage) | +10% (667.3K -> 736.3K) |
| 6 | P6-5 | Columnar node property storage | — | Reverted (functional test incompatibility) |

---

## Completed Optimizations

### Phase 1: B8 — Redundant ID Set Removal

- **File(s)**: `AbcMultipleGraph.kt`
- **Change**: Removed graph-level `nodeIDs`/`edgeIDs` sets; all entity lookups delegate directly to `IStorage`.
- **Impact**: containNode +36%, memory overhead -87% (19.1 MB -> 2.5 MB at 50K nodes). Remaining graph->storage overhead comes from virtual dispatch and wrapper object allocation.

### Phase 1: P0 — Analysis State Externalization

- **File(s)**: `AnalysisStateStore.kt` (new standalone class)
- **Change**: `AnalysisStateStore<S>` provides direct array-indexed access, bypassing IStorage for fixpoint iteration.
- **Impact**: Read 42-62x faster, write 7-14x faster, memory 13.1x smaller vs IStorage HashMap. Zero coupling to IStorage.

### Phase 1: P4 — G1GC Tuning

- **File(s)**: `build.gradle.kts` (JVM flags only)
- **Change**: Applied `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m -XX:InitiatingHeapOccupancyPercent=45`.
- **Impact**: Caps GC pause target. Mitigation only — does not reduce object count.

### Phase 2: B5-B — Merge on Freeze

- **File(s)**: `LayeredStorageImpl.kt`
- **Change**: Each `freeze()` merges all frozen layers + active layer into a single frozen layer. Query depth is always O(1).
- **Impact**: At 10 layers: containsNode +2.4x, getProps +4.2x. Performance no longer degrades with layer count.

### Phase 2: B4-B — LazyMergedMap for Property Queries

- **File(s)**: `LayeredStorageImpl.kt`
- **Change**: `getNodeProperties`/`getEdgeProperties` return `LazyMergedMap(frozenProps, activeProps)` instead of copying into a new HashMap. Single-key `get(key)` is O(1) with zero allocation.
- **Impact**: Property read +2.6x (14.27M -> 37.19M). Write unchanged (+12%).

### Phase 2: B6-A — UnionSet for Edge Queries

- **File(s)**: `LayeredStorageImpl.kt`
- **Change**: `getOutgoingEdges`/`getIncomingEdges` return `UnionSet(frozenEdges, activeEdges)` instead of creating a new HashSet. Fast paths return backing set directly when one side is empty.
- **Impact**: Outgoing +5.6x, incoming +3.4x. LayeredStorage edge queries approach NativeStorage speed.

### Phase 3: B7 — NodeID Intern Pool

- **File(s)**: `NodeID.kt`
- **Change**: `NodeID.of(name)` returns cached instances via `ConcurrentHashMap`. Opt-in API — existing `NodeID()` constructor unchanged.
- **Impact**: getNode (Layered) +14%. Population overhead within JVM noise.

### Phase 3: B1-A — EdgeID Intern Pool

- **File(s)**: `EdgeID.kt`
- **Change**: `EdgeID.of(src, dst, type)` returns cached instances via `ConcurrentHashMap`, auto-interns NodeID fields. Opt-in API.
- **Impact**: getNode (NativeConcur) +26%. **Caveat**: population regression -29% to -42% at 0% cache hit rate. Use `EdgeID.of()` for read-heavy workloads only; use `EdgeID()` constructor for population.

### Phase 3: B3-A — Node/Edge Wrapper Cache (Read-Only)

- **File(s)**: `AbcMultipleGraph.kt`
- **Change**: `HashMap<NodeID, N>` and `HashMap<EdgeID, E>` caches for wrapper objects. Only read operations populate the cache; write operations bypass it. Cache invalidated on delete, cleared on close.
- **Impact**: getNode +13-56%, getOutEdges +45-60%, getDescendants +26%. Population improved -6% to -19% (reduced GC pressure).
- **Design decision**: Initial read+write caching caused +32% population regression. Read-only caching eliminated this.

### Round 5: P5-1 — NativeStorageImpl getNodeProperty/getEdgeProperty Override

- **File(s)**: `NativeStorageImpl.kt`
- **Change**: Added direct `getNodeProperty`/`getEdgeProperty` overrides that access `nodeProperties[id][name]` directly, avoiding default dispatch through `getNodeProperties(id)[name]` which re-enters `isClosed` check and full method dispatch. Mirrors existing pattern in `NativeConcurStorageImpl`.
- **Impact**: No measurable throughput change. Kept for consistency with `NativeConcurStorageImpl` and reduced dispatch overhead.

### Round 5: P5-3 — NativeStorageImpl Redundant isClosed Elimination

- **File(s)**: `NativeStorageImpl.kt`
- **Change**: Extracted `hasNode`/`hasEdge` internal helpers (direct `id in nodeProperties` check) and `ensureOpen()` guard. Compound operations use `hasNode`/`hasEdge` instead of `containsNode`/`containsEdge`, eliminating 1-3 redundant `isClosed` branch checks per call.
- **Impact**: Property read +5%, property write +5%, node delete +10%.

### Round 5: P5-4 — LayeredStorageImpl Redundant containsNode Elimination

- **File(s)**: `LayeredStorageImpl.kt`
- **Change**: Inlined existence checks in write paths (`setNodeProperties`, `addEdge`, `setEdgeProperties`, `deleteNode`, `deleteEdge`). Extracted `ensureNodeInActiveLayer` helper for frozen-to-active promotion. Eliminated up to 3 redundant storage lookups per write operation.
- **Impact**: Property write +7% (15.08M -> 16.08M), property read +1%.

---

## Evaluated & Rejected

| ID | Optimization | Result | Reason |
|---|---|---|---|
| B2-B | Schema array property storage | Property read -34% to -78%, write -32%, mixed -24% | Read path is hot path; compact array trades read performance for memory. Old code returns stored `MutableMap` directly (O(1)); schema arrays require view traversal or materialization O(properties). Population improved -17% to -25%. |
| B1-B | Compact ArrayList adjacency | Node delete -52% | ArrayList O(degree) linear scan per `remove(element)` vs HashSet O(1). Population +17%, edge add +27-41%, but delete regression is inherent. |
| P5-2 | NativeStorageImpl key interning | Skipped | Memory-only benefit (deduplicates property key strings). Hard to measure throughput impact. Adds complexity without measurable hot-path improvement. |
| P5-5 | Initial collection capacity | Node lookup -59% (51.9M -> 21.4M) | Changing `properties.toMutableMap()` (LinkedHashMap) to `HashMap(properties)` caused severe JIT regression on unrelated node lookup path. Reverted; LinkedHashMap iteration order preserved. |
| MapDB | Off-heap B-tree storage | ~3-6us serialize/deserialize per access | Serialization overhead negates GC benefit. Shifts GC pressure from Old Gen to Young Gen. 20-40x amplification on graph traversal paths. |
| RocksDB | Off-heap KV with prefix seek | ~100ns JNI/call overhead | Not needed — frozen layer is point-query only. Platform-specific native libs. |
| Chronicle Map | Off-heap HashMap with flyweight | mmap setup complexity | Overhead not justified for current use case. |
| P6-5 | Columnar node property storage | Perf improved but reverted | ColumnViewMap iteration order (HashSet) broke CsvWriter short-circuit evaluation in `addAll`. Functional test failures in NativeCsvIOImplTest. Reverted to row-oriented storage. |

---

## Candidates

_Empty — no active optimization round._

---

## Remaining Known Bottlenecks

### B2: Per-Entity MutableMap Property Storage

Both node and edge properties use row-oriented `Map<ID, MutableMap<String, IValue>>`. P6-5 columnar node storage was reverted due to CsvWriter compatibility issues. At scale (1M nodes x 3 properties + 3M edges x 3 properties), per-entity HashMap overhead is significant (~176B per entity map instance). Columnar storage remains a viable candidate if the CsvWriter iteration-order dependency is fixed first.

### B1-B: Integer Edge Indexing

Internal `Int edgeSeqId` index with `IntOpenHashSet` adjacency could save ~170MB/3M edges (48B -> 4B per ref). High complexity — requires `EdgeID<->Int` bidirectional map and eclipse-collections dependency.

### B3: Wrapper Object Allocation (Concurrency)

B3-A (HashMap cache) is implemented for single-threaded access. Flyweight cursor (B3-B) would achieve zero allocation but is unsafe under concurrency.

### B4-A: BitSet Layer Ownership

`BitSet` per layer for O(1) containsNode check. Requires node seqId mapping. Less critical now that B5-B (merge on freeze) limits query to at most 2 layers.

### Graph-Level Overhead

After B8 removal, remaining graph->storage overhead (~1.17x for containNode) comes from virtual dispatch and wrapper object allocation. B3-A partially addresses wrapper allocation.

---

## Key Insights

1. **Object count, not memory size, is the primary GC cost driver.** HashMap + IValue wrapping produces ~2-3 objects per property. At 30GB scale, this means ~282M objects causing 2-10s Full GC pauses.
2. **ByteArray is a GC leaf.** GC marks it live but traces zero internal references. Converting reference-dense object graphs into GC leaf nodes is the fundamental GC advantage of compact encoding.
3. **Compact memory structures trade read-path performance for memory savings.** Both B2-B (schema array) and B1-B (ArrayList adjacency) showed this pattern. When the read path is hot, this trade-off is not worth it.
4. **Cache on reads only, not writes.** B3-A initial implementation cached on both reads and writes, causing +32% population regression. Read-only caching eliminated regression while retaining +20-56% read speedup.
5. **Intern pools help reads, hurt population.** EdgeID intern pool (B1-A) shows +26% read improvement but -29% to -42% population regression at 0% cache hit rate. Use constructors for bulk creation, `.of()` for repeated access.
6. **Zero-allocation views dominate over copy-based merging.** LazyMergedMap (+2.6x), UnionSet (+3.4-5.6x), and merge-on-freeze (+2.4-4.2x) all achieve their gains by eliminating intermediate object allocation.
7. **Externalize hot-path state from general-purpose storage.** AnalysisStateStore achieved 42-62x read speedup by using direct array indexing instead of HashMap-based IStorage. The hottest code path should not go through generic abstractions.
8. **MapDB shifts GC pressure, doesn't eliminate it.** Every access creates temp ByteArray + IValue objects (Young Gen), while NativeStorage keeps long-lived objects in Old Gen. For traversal-intensive workloads, MapDB Young Gen pressure is higher.
9. **Cross-layer query cost is multiplicative.** LayeredStorage containsNode: 1 layer 35.9M -> 10 layers 7.12M (5x slowdown). Merge-on-freeze (B5-B) solves this architecturally by bounding layer count.
10. **Eclipse Collections is the likely next step for memory optimization.** Primitive-specialized open-addressing hash tables could achieve memory savings without the read-path regressions seen in B2-B and B1-B.
11. **Stored Map type affects JIT optimization paths.** Changing `toMutableMap()` (LinkedHashMap) to `HashMap()` in NativeStorageImpl caused -59% regression on unrelated node lookup. JIT inlines and specializes based on observed runtime types; changing the concrete Map type invalidates speculative optimizations across the entire call chain.
12. **Always measure the full benchmark matrix, not just the target metric.** A change to one storage operation can regress unrelated operations due to JIT compilation budget effects, cache pollution, or type profile invalidation. The decision rule: KEEP only if no metric regresses >10%.
13. **JIT cross-contamination produces unreliable numbers in sequential benchmarks.** Running multiple storage implementations in the same JVM (as our test suites do) shares JIT compilation budgets. Property read/write and edge query metrics showed up to 2x variance across 3 independent JVM invocations. Median of 3 runs mitigates but does not eliminate this noise. Separate JVM per implementation (or JMH `@Fork`) is required for precise comparisons.
14. **Performance optimizations must not break functional invariants.** P6-5 columnar storage showed throughput improvements but changed `getNodeProperties` iteration order from LinkedHashMap (insertion-ordered) to HashSet (arbitrary). This broke CsvWriter's short-circuit evaluation in `addAll`, causing 3 test failures. Always run the full functional test suite after performance changes.
15. **JIT compiler choice matters more than JDK version.** GraalVM CE 21 vs HotSpot C2 21 showed 75-155% difference on HashMap/ConcurrentHashMap hot paths — far larger than JDK 8 vs 21 differences (29-39%). Always verify which JIT compiler is active (Gradle toolchain may silently select GraalVM CE from SDKMAN). GraalVM CE excels at partial escape analysis (BFS traversal +345%) but underperforms on standard collection read paths.
