# commons-graph Performance

Paired design: `storage.design.md`

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), JDK 21, G1GC with `-XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m`.

Run with:
```bash
./gradlew :graph:test --tests "*.Phase1BenchmarkTest" -PincludePerformanceTests
./gradlew :graph:test --tests "*.StoragePerformanceTest" -PincludePerformanceTests
./gradlew :graph:test --tests "*.OptimizationBenchmarkTest" -PincludePerformanceTests
```

JVM flags: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m -XX:InitiatingHeapOccupancyPercent=45`

### Graph-Level Operations (50K nodes, 150K edges)

| Operation | ops/s |
|---|---|
| graph.containNode | 46.15M |
| graph.getNode (NativeStorage) | 15.80M |
| graph.getNode (NativeConcurStorage) | 27.01M |
| graph.getNode (LayeredStorage) | 24.15M |
| graph.getOutgoingEdges (NativeStorage) | 5.88M |
| graph.getOutgoingEdges (LayeredStorage) | 6.50M |
| graph.getDescendants (LayeredStorage) | 898.9K |

### LayeredStorage Operations (50K nodes, 200K ops)

| Operation | ops/s |
|---|---|
| containsNode (1 layer) | 32.55M |
| containsNode (10 layers) | 18.00M |
| property read | 46.92M |
| property write | 23.00M |
| outgoing edge query | 21.99M |
| incoming edge query | 14.31M |
| getProps (1 layer) | 30.85M |
| getProps (10 layers) | 17.00M |

### NativeStorageImpl Operations

| Operation | ops/s |
|---|---|
| Property Read | 46.58M |
| Property Write | 18.91M |
| Edge Add 1M | 2.24M |
| Node Delete | 2.57M |
| Node Add 1M | 3.35M |
| Population 100K/300K | 149.0 ms |
| Population 1M/3M | 3149.7 ms |

### Fixpoint State Access (P0)

| Approach | Read | Write |
|---|---|---|
| IStorage (HashMap) | 38-44M ops/s | 11-24M ops/s |
| AnalysisStateStore (array) | 1800-2059M ops/s | 163-173M ops/s |

### Memory (50K nodes, 150K edges)

| Metric | MB |
|---|---|
| Storage only | 89.6 |
| Storage + graph | 92.1 |
| Graph overhead | 2.5 |

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
| 6 | P6-5 | Columnar node property storage | NativeStorage property write | +7.6% (17.58M -> 18.91M) |
| 6 | P6-5 | Columnar node property storage | NativeStorage edge add 1M | +26.6% (1.77M -> 2.24M) |
| 6 | P6-5 | Columnar node property storage | NativeStorage node delete | +61.6% (1.59M -> 2.57M) |
| 6 | P6-5 | Columnar node property storage | NativeStorage population 1M/3M | -14.0% latency (3663.6ms -> 3149.7ms) |
| 6 | P6-5 | Columnar node property storage | LayeredStorage property read | +26.2% (37.19M -> 46.92M) |
| 6 | P6-5 | Columnar node property storage | LayeredStorage property write | +45.8% (15.77M -> 23.00M) |

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

---

## Candidates

Round 6 — Structural storage optimizations and traversal fixes.

Sort order: bug fixes first, then by priority (high -> low), within same priority prefer lower risk.

### ~~P6-1: AbcMultipleGraph.getAncestors ArrayList -> ArrayDeque (Bug fix)~~ — Pre-existing

Already implemented. `getAncestors` at line 280 already uses `ArrayDeque`.

### ~~P6-3: NativeStorageImpl.metaNames Defensive Copy Elimination~~ — Pre-existing

Already implemented. `metaNames` at line 194 already returns `metaProperties.keys` directly.

### ~~P6-4: NativeStorageImpl.deleteNode Inline Edge Cleanup~~ — Pre-existing

Already implemented. `deleteNode` at line 100 already inlines edge removal without intermediate ArrayList or per-edge `deleteEdge` calls.

### ~~P6-2: Benchmark NodeID Pre-allocation~~ — Pre-existing

Already implemented. `StoragePerformanceTest`, `Phase1BenchmarkTest`, `OptimizationBenchmarkTest`, and `GraphPerformanceTest` all use pre-allocated `nodeIdPool` arrays.

### ~~P6-6: getDescendants LinkedList -> ArrayDeque~~ — KEEP

- **File(s)**: `AbcMultipleGraph.kt`
- **Change**: Replaced `LinkedList` with `ArrayDeque` in `getDescendants(of, edgeCond)`, `getDescendants(of, label, cond)`, `getAncestors(of, label, cond)`, and `Label.ancestors`. Removed unused `java.util.LinkedList` import.
- **Impact**: getDescendants (NativeStorage) +27% (325.5K -> 412.5K), getDescendants (LayeredStorage) +10% (667.3K -> 736.3K). No regression on other metrics (population, getNode, lattice ops all within JVM noise).

### ~~P6-5: Columnar Property Storage (NativeStorageImpl)~~ — KEEP

- **File(s)**: `NativeStorageImpl.kt`
- **Change**: Replaced row-oriented `nodeProperties: Map<NodeID, MutableMap<String, IValue>>` with column-oriented `nodeSet: MutableSet<NodeID>` + `nodeColumns: HashMap<String, HashMap<NodeID, IValue>>`. One HashMap per property name instead of one per node. `getNodeProperties` returns a lightweight `ColumnViewMap` that reads columns lazily. `getNodeProperty` does two direct HashMap lookups. Edge properties remain row-oriented.
- **Impact**: All metrics improved or neutral. No regression >10%.
  - NativeStorage property read +2.1% (45.62M -> 46.58M)
  - NativeStorage property write +7.6% (17.58M -> 18.91M)
  - NativeStorage edge add 1M +26.6% (1.77M -> 2.24M)
  - NativeStorage node delete +61.6% (1.59M -> 2.57M)
  - NativeStorage population 1M/3M -14.0% latency (3663.6ms -> 3149.7ms)
  - LayeredStorage property read +26.2% (37.19M -> 46.92M)
  - LayeredStorage property write +45.8% (15.77M -> 23.00M)
  - Node lookup, edge queries, mixed workload: within JVM noise
- **Key difference from rejected B2-B**: B2-B changed the lookup algorithm (O(1) -> O(P)). Columnar keeps `getNodeProperty` at O(1) via two HashMap lookups. Gains come from eliminating per-entity HashMap overhead (~176B per node) without changing the access pattern.

---

## Remaining Known Bottlenecks

### B2: Per-Entity MutableMap Property Storage (Partially resolved)

Node properties now use columnar storage (P6-5), eliminating per-node HashMap overhead. **Edge properties still use row-oriented `Map<EdgeID, MutableMap<String, IValue>>`**. At 3M edges x 3 properties, edge property maps contribute ~540MB. Columnar edge storage is the next candidate if further memory reduction is needed.

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
