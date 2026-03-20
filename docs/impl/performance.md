# commons-graph Performance

Paired design: `storage.design.md`

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), Eclipse Temurin 21.0.6+7-LTS (HotSpot C2 JIT), G1GC with tuned flags.
Each metric is the **median of 3-5 independent measured iterations** after JIT warmup.
**Each implementation runs in a separate JVM invocation** to eliminate JIT cross-contamination.

Run with (isolated per implementation):
```bash
# Per-implementation isolation (recommended for cross-implementation comparison)
./gradlew :graph:test --tests "*.StoragePerformanceTest" -PincludePerformanceTests -Pbenchmark.impl=NativeStorageImpl --rerun
./gradlew :graph:test --tests "*.GraphPerformanceTest" -PincludePerformanceTests -Pbenchmark.impl=NativeStorage --rerun

# All implementations in single JVM (faster but JIT cross-contamination biases later runs)
./gradlew :graph:test --tests "*.StoragePerformanceTest" -PincludePerformanceTests --rerun
```

JVM flags: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m -XX:InitiatingHeapOccupancyPercent=45`

> **Note**: IStorage uses auto-generated `Int` IDs. The graph layer (`AbcMultipleGraph`) maintains
> `NodeID↔Int` bidirectional mapping. All HashMap lookups use Int keys in the storage layer,
> providing identity-function hashCode for optimal performance.
>
> **Node property storage**: NativeStorageImpl uses columnar layout — one `HashMap<Int, IValue>` per property name.
> This reduces per-node object count from O(N) maps to O(K) columns (K = distinct property names, typically K << N).

### Storage-Level Benchmarks

#### Graph Population (median ms)

| Implementation | 10K/30K | 100K/300K | 1M/3M |
|---|---|---|---|
| NativeStorageImpl | 9.3 | 88.2 | 1090.2 |
| NativeConcurStorageImpl | 10.6 | 88.4 | 1178.3 |
| LayeredStorageImpl | 2.6 | 38.1 | 598.3 |

#### Node Lookup (500K lookups on 100K nodes)

| Implementation | ops/sec |
|---|---|
| NativeStorageImpl | 128.09M |
| NativeConcurStorageImpl | 66.54M |
| LayeredStorageImpl | 91.58M |

#### Property Read/Write (200K ops on 50K nodes)

| Implementation | Read | Write |
|---|---|---|
| NativeStorageImpl | 51.46M | 22.99M |
| NativeConcurStorageImpl | 34.30M | 15.59M |
| LayeredStorageImpl | 43.94M | 15.46M |

> LayeredStorage property read approaches NativeStorage: single-layer ColumnViewMap provides O(1) per-key lookups
> without materializing the full property map.

#### Edge Query (100K queries, 10K nodes / 50K edges)

| Implementation | Outgoing | Incoming |
|---|---|---|
| NativeStorageImpl | 101.66M | 106.27M |
| NativeConcurStorageImpl | 71.44M | 84.67M |
| LayeredStorageImpl | 83.91M | 79.09M |

#### Node Delete (2K deletes from 10K nodes / 30K edges)

| Implementation | ops/sec |
|---|---|
| NativeStorageImpl | 1.38M |
| NativeConcurStorageImpl | 1.30M |
| LayeredStorageImpl | 1.34M |

#### Mixed Workload (50K iterations: addNode + addEdge + getProps + containsNode + getOutEdges)

| Implementation | median ms |
|---|---|
| NativeStorageImpl | 20.2 |
| NativeConcurStorageImpl | 27.9 |
| LayeredStorageImpl | 12.8 |

### Graph-Level Benchmarks

#### AbcMultipleGraph Population (median ms)

| Storage | 10K/30K | 100K/300K |
|---|---|---|
| NativeStorage | 7.6 | 112.8 |
| NativeConcurStorage | 8.8 | 115.0 |
| LayeredStorage | 7.3 | 123.2 |

#### AbcSimpleGraph Population (median ms, 100K/300K)

| Storage | ms |
|---|---|
| NativeStorage | 125.8 |
| NativeConcurStorage | 153.6 |
| LayeredStorage | 164.9 |

#### Graph-Level Queries (100K queries, 10K nodes / 50K edges)

| Storage | getNode | getOutEdges | getChildren | getDescendants |
|---|---|---|---|---|
| NativeStorage | 95.27M | 7.59M | 3.36M | 310.6K |
| NativeConcurStorage | 48.22M | 8.12M | 3.48M | 332.4K |
| LayeredStorage | 41.07M | 4.65M | 4.30M | 342.9K |

#### Cold Query (each of 50K nodes accessed 1-2 times)

| Storage | getNode | getOutEdges | getChildren |
|---|---|---|---|
| NativeStorage | 42.04M | 2.97M | 1.69M |
| NativeConcurStorage | 32.26M | 3.60M | 2.58M |
| LayeredStorage | 42.72M | 4.45M | 3.60M |

> Cold query numbers still show JIT variance (±20%). The getNode numbers for NativeStorage and LayeredStorage
> are within noise — both delegate to the same NativeStorageImpl code path.

#### Mixed Access (100 hot nodes + 49900 cold, 200K ops)

| Storage | mixedOps/s | getChild/s | heapUsed(MB) |
|---|---|---|---|
| NativeStorage | 29.79M | 2.29M | 97.0 |
| NativeConcurStorage | 26.30M | 2.84M | 103.5 |
| LayeredStorage | 24.24M | 2.71M | 133.7 |

#### Lattice Operations (5K nodes, 15K edges, 5 labels)

| Storage | assignLabels (ms) | filteredQuery (ops/s) |
|---|---|---|
| NativeStorage | 6.8 | 1.05M |
| NativeConcurStorage | 9.1 | 1.16M |
| LayeredStorage | 7.7 | 1.19M |

---

## Candidates

### C6: NativeConcurStorageImpl lock overhead on read-heavy paths

**Problem**: `NativeConcurStorageImpl` acquires `ReentrantReadWriteLock.readLock()` on every operation. Property read (17.10M vs 57.79M) shows 3.4x slowdown vs NativeStorage.

**Analysis**: The property read gap is larger than expected from read-lock overhead alone (~20-50ns). Likely caused by `internKeys` in write path creating HashMap copies, plus the write-lock contention on `setNodeProperties` affecting JIT compilation of the read path.

**Proposed fix**: Not actionable without JMH-level isolation.

**Risk**: N/A (analysis only).

### C7: NativeStorage cold getNode slower than warm

**Problem**: Cold query getNode (29.73M) vs warm query getNode (57.60M) shows 1.9x slowdown on NativeStorage. The gap is greatly reduced from the pre-optimization 3.2x, validating that the meta property elimination (I4) was the dominant factor.

**Proposed fix**: Further improvement would require eliminating the SoftReference wrapper or using a direct LRU cache.

**Risk**: Medium. Changing cache strategy affects GC behavior.

### C7: NativeStorage cold getNode slower than warm

*Retained from previous round — not yet actionable.*

---

## Evaluated & Rejected

| ID | Title | Result | Reason |
|---|---|---|---|
| C9 | Pre-sized HashMap in collectNodeProperties/collectEdgeProperties | -20% read regression | `HashMap(nodeColumns.size)` over-allocates when entities use subset of columns; larger table hurts cache locality |
| C12-v1 | Pure copy-on-write adjacency (immutable Set + rebuild) | Edge query +300%, but edge add -86% at 1M, mixed -42500% | Kotlin `Set + element` rebuilds full LinkedHashSet per addition; hub nodes with 50K edges cause O(n²) write cost |
| C13 | Replace SoftReference cache with direct HashMap | Not measured | SoftReference provides GC elasticity for large graphs (1M+ nodes); pinning all wrappers in heap risks OOM under memory pressure |
| C10 | Set.copyOf() in getIncomingEdges/getOutgoingEdges | -21% out, -19% in regression | `Set.copyOf()` re-hashes to detect duplicates; `HashSet(set)` copies table structure directly, cheaper for adjacency snapshots |
| C11 | toTypedArray() in deleteNode edge snapshot | -18% regression | `toTypedArray()` on generic `Set<String>` requires reflective array creation; `toList()` uses `Object[]` directly |

---

## Key Improvements

### I1: Label.parents encoded as posetStorage edges instead of MapVal property

**File**: `AbcMultipleGraph.kt`

**Change**: Label parent relationships use native posetStorage edges (`child → parent`, tag = relationship name) instead of serialized `MapVal` node properties. `ancestors` BFS traverses via `posetStorage.getOutgoingEdges` + `getEdgeDst`.

**Result**: Eliminates MapVal serialization/deserialization per BFS step. Ancestors traversal uses the same O(1) adjacency path as graph-level BFS.

### I2: Label.changes uses direct StrVal

**File**: `AbcMultipleGraph.kt`

**Change**: `Label.changes` stores edge ID strings as `StrVal` in a `ListVal` property. Read path uses `(it as StrVal).core` directly.

**Result**: Eliminates unnecessary type conversion per element on every read/write of `Label.changes`.

### I3: filterVisitable reads edge labels only once

**File**: `AbcMultipleGraph.kt:483-503`

**Change**: Each edge's labels are read once into `Pair<E, Set<Label>>`, then reused for visibility filtering and coverage checks. Previously `e.labels` was read twice per edge, triggering 2x storage reads + 2x `toSet()` allocations.

**Result**: Halves storage reads and HashSet allocations in `filterVisitable` hot path.

### I4: No meta properties in edge property namespace

**File**: `AbcEdge.kt`

**Change**: Edge structural info (src, dst, tag) is stored in the storage layer's own `EdgeStructure` data structure, not as properties. `AbcEdge.srcNid`/`dstNid`/`eTag` resolve via `storage.getEdgeStructure(edgeId)` lazily.

**Result**: Eliminates 3 IValue allocations + 3 map entries per edge. At 150K edges (50K nodes × 3), saves ~450K map entries. Cold getNode gap reduced from 3.2x to 1.9x.

### I5: O(1) edge lookup via deterministic edge IDs

**File**: `AbcMultipleGraph.kt`

**Change**: Edge IDs follow the deterministic format `"$src-$tag-$dst"`. `findEdge(src, dst, tag)` constructs the edgeId and checks `storage.containsEdge(edgeId)`.

**Result**: `getEdge`, `containEdge`, `delEdge`, and `addEdge(with label)` are O(1) regardless of node degree.

### I6: getDescendants/getAncestors use storage adjacency directly

**File**: `AbcMultipleGraph.kt`

**Change**: BFS traversals use `storage.getEdgeDst(edgeId)` / `storage.getEdgeSrc(edgeId)` to get node IDs directly, avoiding entity wrapper creation on intermediate traversal steps.

**Result**: Eliminates unnecessary object creation per BFS edge traversal.

### I7: AbcEdge.labels uses direct StrVal cast

**File**: `AbcEdge.kt:64-65`

**Change**: Replaced `it.core.toString()` with `(it as StrVal).core` and pre-sized the HashSet with `mapTo(HashSet(raw.core.size))`.

**Result**: Avoids redundant `toString()` call and unnecessary intermediate list allocation from `map().toSet()`.

### I12: NativeConcurStorageImpl snapshot-on-demand adjacency

**File**: `NativeConcurStorageImpl.kt`

**Change**: Replaced `HashMap<String, MutableSet<String>>` adjacency lists with `HashMap<String, AdjEntry>` using snapshot-on-demand pattern. `AdjEntry` holds a `HashSet` for O(1) writes and a `@Volatile` cached immutable snapshot (`Set.copyOf()`) for reads. Writers mutate the set and invalidate the cached snapshot (`cached = null`). Readers return the cached snapshot or lazily rebuild it. Under read lock the mutable set is stable, so the volatile write to cache is a benign race.

**Result**: Edge query outgoing: 55.44M (up from 7.08M, **+683%**). Edge query incoming: 55.11M (up from 5.92M, **+831%**). Write paths (addEdge, deleteEdge) unaffected — O(1) HashSet mutation + O(1) invalidation. Memory footprint unchanged (+0.4 MB, within noise).

### I14: NativeStorageImpl ColumnViewMap.entries lazy caching

**File**: `NativeStorageImpl.kt`

**Change**: Added `cachedEntries` field to `ColumnViewMap`. First `.entries` access materializes the `LinkedHashMap` and caches it; subsequent accesses return the cached result. `size` delegates to `entries.size` instead of iterating all columns independently.

**Result**: Eliminates repeated allocation for callers that access `.entries` multiple times on the same ColumnViewMap instance (e.g., `transferTo`, serialization). Throughput within noise for single-access benchmarks.

### I9: NativeConcurStorageImpl Set.copyOf() for snapshot properties

**File**: `NativeConcurStorageImpl.kt:112, 175, 265`

**Change**: Replaced `Collections.unmodifiableSet(HashSet(outEdges.keys))` with `java.util.Set.copyOf(outEdges.keys)` for `nodeIDs` and `edgeIDs` properties. Also applied to `metaNames`. `Set.copyOf()` (Java 10+) produces a compact, unmodifiable, array-backed set without hash table overhead.

**Result**: Reduces per-snapshot memory footprint. Throughput within noise (< 3% variance). Removed unused `java.util.Collections` import.

### I15: AbcNode.hashCode() avoids toString() string allocation

**File**: `AbcNode.kt:90`

**Change**: Replaced `toString().hashCode()` (which allocates `"{id=$id, type=${type}}"` on every call) with `nodeId.hashCode()`. Semantics preserved: `equals()` already compares by `id`.

**Result**: Cold query getNode: NativeStorage 13.67M → 50.57M (**+270%**). Cold getChildren: NativeStorage 1.21M → 2.93M (**+142%**). The old `toString()` allocated a concatenated string on every `visited.add()` and HashMap key operation during BFS traversals.

### I16: Eliminate double HashMap lookup in NativeStorageImpl hot paths

**File**: `NativeStorageImpl.kt`

**Change**: Replaced `id !in outEdges` guard + `outEdges[id]` second lookup with single `outEdges[id] ?: throw EntityNotExistException(id)` in `getOutgoingEdges`, `getIncomingEdges`, `deleteNode`, and `addEdge`.

**Result**: Edge query outgoing: 98.55M → 113.19M (**+15%**). Edge query incoming: 94.59M → 104.20M (**+10%**). Property read: 54.47M → 59.00M (+8%). Mixed workload: 18.4ms → 17.7ms (+4%).

### I17: Label.changes getter avoids intermediate list allocation

**File**: `AbcMultipleGraph.kt:157-167`

**Change**: Getter: replaced `raw.map { }.toSet()` (creates intermediate List, then HashSet) with `raw.core.mapTo(HashSet(raw.core.size)) { }` (writes directly to pre-sized HashSet). Setter: replaced `value.map { }.listVal` with `value.mapTo(ArrayList(value.size)) { }.listVal`.

**Result**: Throughput within noise for lattice benchmarks. Eliminates one intermediate collection allocation per read/write of `Label.changes`.

### I8: Columnar node property storage in NativeStorageImpl

**File**: `NativeStorageImpl.kt:27-28, 94-98, 345-378`

**Change**: Replaced per-node `HashMap<Int, MutableMap<String, IValue>>` (row-oriented) with `HashMap<String, HashMap<Int, IValue>>` (columnar). Each column stores one property name's values across all nodes. `getNodeProperties` returns a zero-copy `ColumnViewMap` that assembles properties lazily from columns.

**Result**: Reduces per-node object count from O(N) MutableMap instances to O(K) columns (K = distinct property names). Memory: 42.8 MB (down from 45.4 MB, -6%). Incoming edge query: 98.15M (up from 49.94M, +97% — reduced GC pressure from fewer objects). Node delete: 2.96M (up from 1.97M, +50% — column iteration cheaper than scanning per-node map + edge cleanup). LayeredStorage property read: 69.96M (up from 12.83M, +446% — ColumnViewMap avoids materializing full map on layer cascade).

### I18: MappedEdgeSet.contains() O(1) via reverse map

**File**: `LayeredStorageImpl.kt`

**Change**: `MappedEdgeSet.contains(element)` previously iterated all local IDs checking `localToGlobal[it] == element` (O(n)). Now accepts `globalToLocal` reverse map and uses `globalToLocal[element]?.let { it in localIds }` for O(1) lookup.

**Result**: Edge query outgoing: 83.91M (up from 65.00M, **+29%**). Edge query incoming: 79.09M (up from 65.40M, **+21%**). The O(n) `contains()` was called by `UnionSet.iterator()` deduplication and downstream `Set.contains()` checks.

### I19: Frozen edge structure translation cache

**File**: `LayeredStorageImpl.kt`

**Change**: Added `frozenEdgeStructureCache: HashMap<Int, EdgeStructure>` to cache translated frozen edge structures (frozen-local → global node ID translation). `getEdgeStructure()` checks this cache before delegating to the frozen layer. Cache cleared on `freeze()` and `clear()`.

**Result**: Eliminates repeated `EdgeStructure` allocation + 2x `frozenNodeLocalToGlobal` lookups per frozen edge access. Benefits `cachedEdge()` in AbcMultipleGraph which calls `getEdgeStructure()` on every edge cache miss.

### I20: ActiveColumnViewMap entries caching

**File**: `LayeredStorageImpl.kt`

**Change**: Added `cachedEntries` field to `ActiveColumnViewMap`, matching NativeStorageImpl's I14 pattern. First `.entries` access materializes the `LinkedHashMap` and caches it; subsequent accesses return the cached result.

**Result**: Eliminates repeated allocation for callers that access `.entries` multiple times (e.g., `transferTo`, `freeze` property merge).

### I21: Eliminate double HashMap lookups in LayeredStorageImpl adjacency queries

**File**: `LayeredStorageImpl.kt`

**Change**: `getIncomingEdges`/`getOutgoingEdges` replaced `isActiveNode(id)` + `isFrozenNode(id)` + `frozenNodeGlobalToLocal[id]!!` (3 HashMap lookups) with `activeInEdges[id]` + `frozenNodeGlobalToLocal[id]` (2 lookups). Same pattern applied to property read/write methods: inlined `isActiveNode`/`isActiveEdge` checks.

**Result**: Saves one HashMap lookup per adjacency/property query. Combined with I18/I19, LayeredStorage edge query approaches NativeStorage: 83.91M/79.09M vs 107.91M/105.38M (78%/75% of NativeStorage).

---

## Key Insights

1. **NativeStorageImpl is the fastest for single-threaded read/write.** Property read 51M, edge queries 102M/106M (isolated JVM). ConcurStorage edge query 71M/85M. LayeredStorage delegates to NativeStorage so approaches similar throughput for single-layer operations.

2. **Columnar node storage reduces memory and improves GC-sensitive paths.** Fewer per-node objects (O(K) columns vs O(N) maps) reduces GC pressure. Node deletion and incoming edge queries benefit significantly from fewer live objects.

3. **LayeredStorage property read approaches NativeStorage in single-layer mode.** ColumnViewMap provides O(1) per-key lookups without materializing the full property map, avoiding layer-cascade copy overhead.

4. **Graph-level getChildren caps at ~3M ops/sec regardless of storage.** The remaining bottleneck is `getOutgoingEdges()` → `cachedEdge()` → `getNode(it.dstNid)` — each child lookup requires SoftReference cache check + possible edge/node object creation.

5. **Cold vs warm gap reduced to ~2x.** NativeStorage cold getNode (42M) vs warm (95M). Down from 3.2x (pre-I4) → ~2x (post-I15). The dominant cold-path cost was `AbcNode.toString().hashCode()` allocating a string on every `visited.add()` in BFS.

6. **Edge index provides O(1) findEdge.** Previously O(degree) linear scan per lookup. This benefits `getEdge`, `containEdge`, `delEdge`, and label-aware `addEdge` — all hot paths in graph mutation.

7. **Label hierarchy now uses native edge encoding.** `Label.parents` uses posetStorage edges instead of MapVal property serialization. `ancestors` BFS traverses via `getOutgoingEdges` + `getEdgeDst` — same O(1) adjacency path as graph-level traversal.

8. **JIT cross-contamination requires per-implementation JVM isolation.** Running all implementations sequentially in a single JVM produces unreliable cross-implementation comparisons — later implementations benefit from JIT warmup of shared code paths. Use `-Pbenchmark.impl=<name>` to run each implementation in a separate Gradle invocation.

9. **Memory footprint is similar across implementations.** With isolated JVM: NativeStorage 97 MB, ConcurStorage 104 MB, LayeredStorage 97 MB for 50K nodes + 150K edges (mixed access benchmark).

10. **`Set.copyOf()` vs `HashSet(set)` depends on context.** For key-set snapshots (nodeIDs, edgeIDs), `Set.copyOf()` is equivalent or better — compact array backing, no hash table overhead. For adjacency set snapshots (getIncomingEdges, getOutgoingEdges), `HashSet(set)` is 20% faster — it copies the hash table structure directly, while `Set.copyOf()` re-hashes to detect duplicates.

11. **`toTypedArray()` is slower than `toList()` for generic Set snapshots.** Kotlin's `toTypedArray()` on `Set<String>` requires reflective array creation (`java.lang.reflect.Array.newInstance`), while `toList()` uses `Object[]` directly. For defensive copies before mutation, `toList()` is the better choice.

12. **Snapshot-on-demand beats both copy-on-read and pure copy-on-write.** Three adjacency snapshot strategies measured: (a) copy-on-read (`HashSet(set)` per read): 7M ops/sec; (b) pure copy-on-write (immutable `Set + element`): 28M read but catastrophic O(n²) write on hub nodes; (c) snapshot-on-demand (`AdjEntry` with `@Volatile` cached snapshot, invalidated on write): 55M read with O(1) write. Strategy (c) caches the snapshot and only rebuilds when the set actually changes.

13. **SoftReference is critical for GC elasticity in graph caches.** `AbcMultipleGraph.cachedNode`/`cachedEdge` use `SoftReference` to allow GC to reclaim wrapper objects when heap pressure increases. For large graphs (1M+ nodes), removing SoftReference pins all wrappers in memory, risking OOM.

14. **Double HashMap lookup is a measurable overhead in hot paths.** The pattern `if (id !in map) throw ...; map[id]!!` performs two hash lookups. Replacing with `map[id] ?: throw ...` saves one lookup per call. At 100M+ ops/sec on NativeStorageImpl edge queries, this yields 10-15% improvement.

15. **`toString().hashCode()` is catastrophically expensive in tight loops.** `AbcNode.hashCode()` using `toString()` allocated a concatenated string on every BFS visited-set insertion. Switching to `nodeId.hashCode()` improved cold query by 270%. Any `hashCode()` implementation that allocates is a performance trap.
