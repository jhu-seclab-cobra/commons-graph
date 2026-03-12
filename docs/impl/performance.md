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
| property read | 37.19M |
| property write | 15.77M |
| outgoing edge query | 21.99M |
| incoming edge query | 14.31M |
| getProps (1 layer) | 30.85M |
| getProps (10 layers) | 17.00M |

### NativeStorageImpl Operations

| Operation | ops/s |
|---|---|
| Property Read | 45.62M |
| Property Write | 17.58M |
| Edge Add 1M | 1.77M |
| Node Delete | 1.59M |
| Population 100K/300K | 182.1 ms |
| Population 1M/3M | 3663.6 ms |

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

### P6-1: AbcMultipleGraph.getAncestors ArrayList -> ArrayDeque (Bug fix)

- **File(s)**: `AbcMultipleGraph.kt`
- **Hypothesis**: `getAncestors` uses `mutableListOf(of)` (ArrayList) with `stack.removeAt(0)` — O(n) per removal for BFS. `getDescendants` already uses `LinkedList` with `removeFirst()` — O(1). Replacing with `ArrayDeque` gives O(1) `removeFirst()` without LinkedList per-node object overhead. At depth D with branching factor B, total removals = O(B^D); current cost O(B^(2D)), fixed cost O(B^D).
- **Risk**: Low — algorithm fix, no API change.
- **Priority**: Bug fix — correctness of time complexity.
- **Cross-metric concern**: None — isolated to one method, no storage layer changes.

### P6-2: Benchmark NodeID Pre-allocation

- **File(s)**: `StoragePerformanceTest.kt`, `OptimizationBenchmarkTest.kt`, `GraphPerformanceTest.kt`
- **Hypothesis**: Some benchmarks create `NodeID("n$i")` inside measured loops. Pre-allocating `Array(nodeCount) { NodeID("n$it") }` and indexing by `i % nodeCount` gives pure storage-operation measurements without allocation noise.
- **Risk**: Low — test-only change.
- **Priority**: Medium — prerequisite for accurate P6-5 evaluation.
- **Cross-metric concern**: None — test infrastructure only.

### P6-3: NativeStorageImpl.metaNames Defensive Copy Elimination

- **File(s)**: `NativeStorageImpl.kt`
- **Hypothesis**: `metaNames` getter returns `metaProperties.keys` directly instead of `.toSet()` copy. Consistent with `nodeIDs`/`edgeIDs` which already return `keys` directly.
- **Risk**: Low — single line change, off hot path.
- **Priority**: Low — consistency fix.
- **Cross-metric concern**: None — not benchmarked, not on hot path.

### P6-4: NativeStorageImpl.deleteNode Inline Edge Cleanup

- **File(s)**: `NativeStorageImpl.kt`
- **Hypothesis**: `deleteNode` currently iterates connected edges and calls `deleteEdge(it)` per edge, each re-checking `ensureOpen()` + `hasEdge()`. Inlining the edge removal eliminates N redundant checks where N = node degree.
- **Risk**: Low — internal refactor, same semantics.
- **Priority**: Low — `deleteNode` is not a hot path.
- **Cross-metric concern**: None — isolated to delete path.

### P6-5: Columnar Property Storage (NativeStorageImpl)

- **File(s)**: `NativeStorageImpl.kt`
- **Hypothesis**: Replace per-entity `MutableMap<String, IValue>` with per-property-name columns.

  Current (row-oriented):
  ```
  nodeProperties: Map<NodeID, MutableMap<String, IValue>>
  edgeProperties: Map<EdgeID, MutableMap<String, IValue>>
  ```
  Each entity has its own LinkedHashMap (~176B overhead: 48B object header + 128B backing array at default capacity 16).

  Proposed (column-oriented):
  ```
  nodeSet: MutableSet<NodeID>
  nodeColumns: HashMap<String, HashMap<NodeID, IValue>>
  edgeColumns: HashMap<String, HashMap<EdgeID, IValue>>
  ```

  **Object analysis at 1M nodes x 5 properties:**

  | Metric | Row-oriented | Column-oriented | Delta |
  |---|---|---|---|
  | HashMap instances | 1M + 1 | 5 + 1 | **-99.9999%** |
  | Total entries | ~6M | ~6M | same |
  | Memory overhead | ~368MB | ~192MB | **-48%** |
  | GC-traced objects | ~7M | ~6M | -14% |

  **Per-API cross-metric impact analysis:**

  | API | Current | Columnar | Expected Impact |
  |---|---|---|---|
  | `getNodeProperty(id, name)` | `nodeProperties[id]!![name]` — 2 lookups | `nodeColumns[name]?.get(id)` — 2 lookups (outer <=20 keys) | **Neutral**: same O(1), outer map is tiny and L1-cacheable |
  | `getNodeProperties(id)` | returns stored `MutableMap` directly — zero alloc | returns `ColumnViewMap(id, nodeColumns)` — 1 object alloc | **Minor regression possible**: allocates view; but NOT the hot path |
  | `containsNode(id)` | `id in nodeProperties` | `id in nodeSet` | **Neutral**: both O(1) HashMap lookup |
  | `addNode(id, props)` | creates LinkedHashMap per entity | `nodeSet.add(id)` + scatter into columns | **Improvement**: eliminates per-entity map allocation |
  | `deleteNode(id)` | `nodeProperties.remove(id)` — O(1) | `nodeSet.remove(id)` + iterate all columns — O(P) | **Minor regression**: P typically <=20; edge cascade dominates |
  | `setNodeProperties(id, props)` | `container[key] = value` | `nodeColumns[key]!![id] = value` | **Neutral**: same number of map operations |
  | `nodeIDs` | `nodeProperties.keys` | `nodeSet` | **Neutral**: direct set reference |

  **Cross-metric regression risk assessment:**

  P5-5 showed that changing `toMutableMap()` (LinkedHashMap) to `HashMap()` caused -59% regression on *unrelated* node lookup (Key Insight #11). Columnar storage changes:
  1. The type returned by `getNodeProperties` — from direct `MutableMap` to a view object. Callers iterating the returned map will see different JIT profiles.
  2. The internal storage structure — JIT may specialize based on observed map types.
  3. `LayeredStorageImpl` delegates to `NativeStorageImpl` — columnar changes propagate through all layered operations.

  **Mitigation strategy:**
  - Implement for node properties first (not edges). Measure full matrix before proceeding.
  - `getNodeProperties` returns lightweight `ColumnViewMap` with `get(key)` delegating to `nodeColumns[key]?.get(id)`. Cold path (`entries`/`size`) materializes lazily.
  - If view causes regression, fall back to HashMap copy (trades allocation for JIT stability).
  - Benchmark must cover **all** metrics: property read/write, containsNode, population, node delete, edge queries, mixed workload, and LayeredStorage operations.

  **Key difference from rejected B2-B:** B2-B regressed `getNodeProperty` from O(1) to O(P) due to array scan. Columnar keeps `getNodeProperty` at O(1) via two HashMap lookups. Memory saving comes from eliminating per-entity HashMap structure overhead, not from changing the lookup algorithm.

- **Risk**: High — fundamental storage restructure. P5-5 JIT precedent.
- **Priority**: High — directly attacks B2 bottleneck (~176MB savings at 1M nodes). Addresses Key Insight #1.
- **Go/no-go gate**: KEEP only if **no metric regresses >10%** across full benchmark suite.

### P6-6: getDescendants LinkedList -> ArrayDeque (Consistency fix)

- **File(s)**: `AbcMultipleGraph.kt`
- **Hypothesis**: `getDescendants(of, edgeCond)` at line 298 uses `LinkedList<NodeID>()` as BFS queue. `getAncestors` already uses `ArrayDeque` (after P6-1). `LinkedList` allocates a `Node` object per element (~32B), while `ArrayDeque` uses a single backing array. The label-filtered variants `getDescendants(of, label, cond)` and `getAncestors(of, label, cond)` at lines 310-346 also use `LinkedList`.
- **Risk**: Low — same pattern as P6-1.
- **Priority**: Low — consistency with P6-1. getDescendants benchmarked at 898.9K ops/s; minor improvement from reduced allocation.
- **Cross-metric concern**: None — isolated to traversal methods.

---

## Remaining Known Bottlenecks

### B2: Per-Entity MutableMap Property Storage

`NativeStorageImpl` stores `Map<NodeID, MutableMap<String, IValue>>`. Each node/edge holds its own `MutableMap`, incurring ~180B structure overhead per entity + 32B per HashMap.Node entry. At 1M nodes x 5 properties: ~540MB. Schema array (B2-B) was rejected due to read regression. Columnar storage (P6-5) is the next candidate.

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
