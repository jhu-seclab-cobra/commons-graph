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

## Key Improvements

See individual entries in completed optimizations below. Summary of per-optimization deltas available in each entry's **Result** field.

### Completed Optimizations

**I1: Label.parents as posetStorage edges.** `AbcMultipleGraph.kt`. Eliminates MapVal serialization per BFS step.
**I2: Label.changes uses direct StrVal.** `AbcMultipleGraph.kt`. Eliminates type conversion per element.
**I3: filterVisitable reads edge labels once.** `AbcMultipleGraph.kt:483-503`. Halves storage reads and HashSet allocations.
**I4: No meta properties in edge namespace.** `AbcEdge.kt`. Saves ~450K map entries at 150K edges. Cold getNode gap: 3.2x -> 1.9x.
**I5: O(1) edge lookup via deterministic IDs.** `AbcMultipleGraph.kt`. `findEdge` O(1) regardless of node degree.
**I6: getDescendants/getAncestors use storage adjacency directly.** `AbcMultipleGraph.kt`. Eliminates wrapper creation per BFS step.
**I7: AbcEdge.labels uses direct StrVal cast.** `AbcEdge.kt:64-65`. Avoids `toString()` and intermediate list allocation.
**I8: Columnar node property storage.** `NativeStorageImpl.kt`. Memory -6%. Incoming edge +97%. Node delete +50%. LayeredStorage property read +446%.
**I9: Set.copyOf() for snapshot properties.** `NativeConcurStorageImpl.kt`. Compact array-backed snapshots for nodeIDs/edgeIDs.
**I12: Snapshot-on-demand adjacency.** `NativeConcurStorageImpl.kt`. Edge query outgoing +683%, incoming +831%.
**I14: ColumnViewMap.entries lazy caching.** `NativeStorageImpl.kt`. Eliminates repeated allocation for multi-access callers.
**I15: AbcNode.hashCode() avoids toString().** `AbcNode.kt:90`. Cold getNode +270%. Cold getChildren +142%.
**I16: Eliminate double HashMap lookup.** `NativeStorageImpl.kt`. Edge outgoing +15%, incoming +10%.
**I17: Label.changes getter avoids intermediate list.** `AbcMultipleGraph.kt:157-167`. One less collection allocation per read/write.
**I18: MappedEdgeSet.contains() O(1) via reverse map.** `LayeredStorageImpl.kt`. Edge outgoing +29%, incoming +21%.
**I19: Frozen edge structure translation cache.** `LayeredStorageImpl.kt`. Eliminates repeated EdgeStructure allocation + translation lookups.
**I20: ActiveColumnViewMap entries caching.** `LayeredStorageImpl.kt`. Matches I14 pattern for LayeredStorage.
**I21: Eliminate double HashMap lookups in LayeredStorageImpl.** `LayeredStorageImpl.kt`. Saves one lookup per adjacency/property query.

---

## Key Insights

1. **NativeStorageImpl is fastest for single-threaded read/write.** Property read 51M, edge queries 102M/106M (isolated JVM).

2. **Columnar node storage reduces memory and improves GC-sensitive paths.** Fewer per-node objects (O(K) columns vs O(N) maps).

3. **LayeredStorage property read approaches NativeStorage in single-layer mode.** ColumnViewMap provides O(1) per-key lookups without materializing the full property map.

4. **Graph-level getChildren caps at ~3M ops/sec.** Bottleneck: `getOutgoingEdges()` -> `cachedEdge()` -> `getNode(it.dstNid)`.

5. **Cold vs warm gap reduced to ~2x.** Down from 3.2x (pre-I4) -> ~2x (post-I15). Dominant cost was `AbcNode.toString().hashCode()`.

6. **Edge index provides O(1) findEdge.** Benefits `getEdge`, `containEdge`, `delEdge`, and label-aware `addEdge`.

7. **Label hierarchy uses native edge encoding.** `Label.parents` uses posetStorage edges instead of MapVal property serialization.

8. **JIT cross-contamination requires per-implementation JVM isolation.** Use `-Pbenchmark.impl=<name>` for reliable cross-implementation comparisons.

9. **Memory footprint is similar across implementations.** NativeStorage 97 MB, ConcurStorage 104 MB, LayeredStorage 97 MB (50K nodes + 150K edges).

10. **`Set.copyOf()` vs `HashSet(set)` depends on context.** Key-set snapshots: `Set.copyOf()` is compact. Adjacency snapshots: `HashSet(set)` is 20% faster (copies table structure directly).

11. **`toTypedArray()` is slower than `toList()` for generic Set snapshots.** Reflective array creation vs direct `Object[]`.

12. **Snapshot-on-demand beats copy-on-read and pure copy-on-write.** 55M read with O(1) write vs 7M copy-on-read or catastrophic O(n^2) write.

13. **SoftReference is critical for GC elasticity in graph caches.** Removing SoftReference risks OOM on large graphs (1M+ nodes).

14. **Double HashMap lookup is measurable in hot paths.** `map[id] ?: throw` saves one lookup. 10-15% improvement at 100M+ ops/sec.

15. **`toString().hashCode()` is catastrophically expensive in tight loops.** Cold query +270% by switching to `nodeId.hashCode()`.
