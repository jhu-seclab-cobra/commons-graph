# commons-graph Performance

Paired design: `storage.design.md`

## Current Baseline

macOS (Apple Silicon), Temurin 21.0.6+7-LTS, G1GC. Median of 3-5 iterations after JIT warmup. Separate JVM per implementation.

```bash
./gradlew :graph:test --tests "*.StoragePerformanceTest" -PincludePerformanceTests -Pbenchmark.impl=NativeStorageImpl --rerun
```

JVM flags: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m -XX:InitiatingHeapOccupancyPercent=45`

Int-keyed storage (identity-function hashCode). NativeStorageImpl uses columnar layout -- one `HashMap<Int, IValue>` per property name (O(K) columns, K << N).

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

## Cross-Implementation Comparison

| Metric (best config) | Native | JGraphT | MapDB (memDB) | Neo4j |
|---|---|---|---|---|
| Population 10K/30K (ms) | 9.3 | 18.5 | 150.9 (5K/15K) | 37,959 (1K/3K) |
| Node lookup (ops/s) | 128.09M | 40.46M | 1.14M | 23.40M |
| Property read (ops/s) | 51.46M | 81.13M | 1.61M | 452.5K |
| Edge query out (ops/s) | 101.66M | 8.97M | 1.43M | 696.8K |

---

## Key Improvements

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

## Evaluated & Rejected

| ID | Title | Result | Reason |
|---|---|---|---|
| P6-3 | Pre-sized HashMap in collectNodeProperties/collectEdgeProperties | -20% read regression | Over-allocates when entities use subset of columns; larger table hurts cache locality |
| P6-4 | Pure copy-on-write adjacency (immutable Set + rebuild) | Edge query +300%, but edge add -86% at 1M | Kotlin `Set + element` rebuilds full LinkedHashSet; hub nodes cause O(n^2) write |
| P6-5 | Replace SoftReference cache with direct HashMap | Not measured | SoftReference provides GC elasticity for large graphs (1M+ nodes); risks OOM |
| P6-6 | Set.copyOf() in getIncomingEdges/getOutgoingEdges | -21% out, -19% in | `Set.copyOf()` re-hashes; `HashSet(set)` copies table structure directly |
| P6-7 | toTypedArray() in deleteNode edge snapshot | -18% regression | Reflective array creation; `toList()` uses `Object[]` directly |
| P9-2 | Native Int storage for __sid__ | Already optimized | `__sid__` stored as native `Long`, not serialized through `DftByteArraySerializerImpl` |
| P9-5 | Eclipse Collections for ID mapping | Not applicable | Current zero-mapping architecture uses no in-memory ID maps |

---

## Candidates

### P6-2: NativeStorage cold getNode slower than warm

Cold 29.73M vs warm 57.60M (1.9x). Gap reduced from 3.2x by I4. Proposed: eliminate SoftReference or use LRU cache. Risk: medium (GC behavior change).

---

## Remaining Known Bottlenecks

- getChildren caps at ~3M ops/sec: `getOutgoingEdges()` -> `cachedEdge()` -> `getNode(it.dstNid)`.
- NativeConcurStorageImpl read-lock: 34.30M vs 51.46M (1.5x). Not actionable without JMH isolation.
- Neo4j population: 37,959ms for 1K/3K. WAL + lock manager overhead per write transaction.
- MapDB serialization: 1.61M property read vs 51.46M NativeStorage.

---

## Key Insights

1. **NativeStorageImpl is fastest for single-threaded read/write.** Property read 51M, edge queries 102M/106M.
2. **Columnar node storage reduces memory and improves GC-sensitive paths.** O(K) columns vs O(N) maps.
3. **LayeredStorage property read approaches NativeStorage in single-layer mode.** ColumnViewMap O(1) per-key without materializing full map.
4. **Cold vs warm gap reduced to ~2x.** Down from 3.2x (pre-I4) via `AbcNode.hashCode()` fix (I15).
5. **Edge index provides O(1) findEdge.** Benefits `getEdge`, `containEdge`, `delEdge`, and label-aware `addEdge`.
6. **Label hierarchy uses native edge encoding.** Poset edges instead of MapVal property serialization.
7. **JIT cross-contamination requires per-implementation JVM isolation.** Use `-Pbenchmark.impl=<name>`.
8. **Memory footprint is similar across implementations.** 97-104 MB at 50K nodes + 150K edges.
9. **Snapshot-on-demand beats copy-on-read and copy-on-write.** 55M read with O(1) write vs 7M or O(n^2).
10. **SoftReference is critical for GC elasticity.** Removing risks OOM on large graphs (1M+ nodes).
11. **Double HashMap lookup is measurable in hot paths.** 10-15% improvement at 100M+ ops/sec.
12. **`toString().hashCode()` is catastrophically expensive in tight loops.** Cold query +270% by switching to `nodeId.hashCode()`.
