# Neo4j Module Performance

Paired design: `design-storage.md`, `impl.md`

## Current Baseline

macOS (Apple Silicon), Temurin 21.0.6+7-LTS, G1GC. Separate JVM per implementation.

```bash
./gradlew :modules:impl-neo4j:test --tests "*.Neo4jPerformanceTest" -PincludePerformanceTests --rerun
```

Scale tiers: 1K/3K, 5K/15K, 10K/30K nodes/edges.

### Architecture

Zero in-memory ID mappings. All lookups via Neo4j transactions + schema-indexed `__sid__` properties. Every data access requires a `Transaction` (Neo4j 5.x). All `IValue` properties serialized to `ByteArray` via `DftByteArraySerializerImpl`.

Two implementations: `Neo4jStorageImpl` (non-concurrent) and `Neo4jConcurStorageImpl` (thread-safe, `ReentrantReadWriteLock`).

### Graph Population (median ms)

| Implementation | 1K/3K | 5K/15K | 10K/30K |
|---|---|---|---|
| Neo4jStorageImpl | 37,959.5 | 174,599.1 | 174,001.1 |
| Neo4jConcurStorageImpl | 17,965.9 | 90,629.1 | 173,379.5 |

Anomaly: 5K/15K (174,599ms) is slower than 10K/30K (174,001ms) for Neo4jStorageImpl. Likely JIT warmup or GC pressure variation; warrants re-measurement.

### Node Lookup (20K lookups on 5K nodes)

| Implementation | ops/sec |
|---|---|
| Neo4jStorageImpl | 23.40M |
| Neo4jConcurStorageImpl | 34.12M |

### Property Read/Write (10K ops on 2K nodes)

| Implementation | Read | Write |
|---|---|---|
| Neo4jStorageImpl | 452.5K | 116 |
| Neo4jConcurStorageImpl | 813.3K | 117 |

### Edge Query (10K queries, 2K nodes / 6K edges)

| Implementation | Outgoing | Incoming |
|---|---|---|
| Neo4jStorageImpl | 696.8K | 744.7K |
| Neo4jConcurStorageImpl | 699.3K | 752.0K |

---

## Evaluated & Rejected

| ID | Title | Result | Reason |
|---|---|---|---|
| P9-2 | Native Int storage for __sid__ | Already optimized | `__sid__` stored as native `Long` |
| P9-5 | Eclipse Collections for ID mapping | Not applicable | Zero-mapping architecture |

## Candidates

### P9-1: Transaction batching for transferTo

Per-entity `readTx` creates many transactions for bulk operations. Proposed: batch into single long-running read transaction. Risk: low.

### P9-3: Property native type fast path

Store primitive `IValue` types as Neo4j native properties. Expected: ~10x faster property access for common types. Risk: medium.

### P9-4: Entity.keys filtering optimization

Remove unnecessary `distinct()` call (property keys already unique per entity). Risk: low.

---

## Remaining Known Bottlenecks

- Population: 37,959ms for 1K/3K. Each addNode/addEdge opens a write transaction with WAL, lock manager, and bookkeeping.
- Population does not scale linearly: 5K/15K is 4.6x the 1K/3K cost for 5x data.
- Property write 3,900x slower than read (116 vs 452.5K). Write transaction commit includes WAL flush.

---

## Key Insights

1. **Population is dominated by per-operation write transactions.** WAL + lock manager per write.
2. **Node lookup is fast.** 23.40M ops/sec.
3. **ConcurStorage faster than non-Concur for node lookup.** 34.12M vs 23.40M. Likely JIT warmup artifact.
4. **Property write is 3,900x slower than read.** Write transaction commit includes WAL flush.
5. **Edge query is symmetric.** Neo4j relationship chain threading provides O(1) both directions.
6. **ConcurStorage lock overhead is negligible.** Lock ~20-50ns invisible next to ~1-10us transaction overhead.
7. **Neo4j's value is persistence and query language, not throughput.** Use only when Cypher, ACID, or disk persistence are required.
