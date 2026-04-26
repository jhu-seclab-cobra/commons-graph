# commons-graph Performance

Paired design: `design-storage.md`

## Current Baseline

macOS (Apple Silicon), Temurin 21.0.6+7-LTS, G1GC. Median of 3-5 iterations after JIT warmup. Separate JVM per implementation. JVM flags: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m -XX:InitiatingHeapOccupancyPercent=45`. Int-keyed storage (identity-function hashCode). NativeStorageImpl uses columnar layout -- one `HashMap<Int, IValue>` per property name (O(K) columns, K << N).

```bash
./gradlew :graph:test --tests "*.StoragePerformanceTest" -PincludePerformanceTests -Pbenchmark.impl=NativeStorageImpl --rerun
```

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

## Remaining Known Bottlenecks

- getChildren caps at ~3M ops/sec: `getOutgoingEdges()` -> `cachedEdge()` -> `getNode(it.dstNid)`.
- NativeConcurStorageImpl read-lock: 34.30M vs 51.46M (1.5x). Not actionable without JMH isolation.
- Neo4j population: 37,959ms for 1K/3K. WAL + lock manager overhead per write transaction.
- MapDB serialization: 1.61M property read vs 51.46M NativeStorage.

Optimization history, candidates, and insights: see [performance-optimizations.md](performance-optimizations.md).
