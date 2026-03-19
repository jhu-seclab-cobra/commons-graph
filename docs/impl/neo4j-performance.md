# Neo4j Module Performance

Paired design: `storage.design.md`, `neo4j.md`

---

## Architecture

Neo4j module provides two `IStorage` implementations using Neo4j 5.x embedded mode:
- **`Neo4jStorageImpl`** — non-concurrent, String ID to Neo4j element ID mapping
- **`Neo4jConcurStorageImpl`** — thread-safe, `ReentrantReadWriteLock` over same structures

Both maintain bidirectional ID mapping between `IStorage` String IDs and Neo4j element IDs. Edge structural info (src, dst, tag) is queried through Neo4j native `Relationship` methods within transactions.

Key overhead sources:
1. **Transaction per operation** — Neo4j 5.x requires `Transaction` for all data access, even reads
2. **Serialization** — all `IValue` properties serialized to `ByteArray` via `DftByteArraySerializerImpl`
3. **ID mapping** — String-to-Neo4j-element-ID mapping lookup on every operation

---

## Benchmark Infrastructure

```bash
./gradlew :modules:impl-neo4j:test --tests "*.Neo4jPerformanceTest" -PincludePerformanceTests --rerun
```

Scale tiers (smaller than in-memory implementations due to Neo4j startup overhead):
- 1K nodes / 3K edges
- 5K nodes / 15K edges
- 10K nodes / 30K edges

Benchmark categories:
- Graph population (median ms)
- Node lookup (ops/sec)
- Property read/write (ops/sec)
- Edge query — outgoing/incoming (ops/sec)

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), Eclipse Temurin 21.0.6+7-LTS, G1GC with tuned flags.

### Graph Population (median ms)

| Implementation | 1K/3K | 5K/15K | 10K/30K |
|---|---|---|---|
| Neo4jStorageImpl | 37,959.5 | 174,599.1 | 174,001.1 |
| Neo4jConcurStorageImpl | 17,965.9 | 90,629.1 | 173,379.5 |

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

## Optimization Candidates

### N1: Transaction Batching for transferTo

**Problem**: `transferTo` calls `getNodeProperties`, `getEdgeStructure`, `getEdgeProperties` individually — each opens a separate `readTx`. For a graph with N nodes and E edges, this creates 2N + 2E transactions.

**Proposed change**: Collect all data within a single `readTx`, then write to target outside the transaction.

```kotlin
override fun transferTo(target: IStorage) {
    ensureOpen()
    val nodeData: Map<Int, Map<String, IValue>>
    val edgeData: List<EdgeTransferInfo>
    readTx {
        nodeData = intToNeo4jNode.keys.associateWith { id ->
            val node = getNodeById(intToNeo4jNode[id]!!)
            node.keys.associateWith { node[it]!! }
        }
        edgeData = intToNeo4jEdge.keys.map { id ->
            val rel = getRelationshipById(intToNeo4jEdge[id]!!)
            EdgeTransferInfo(
                neo4jNodeToInt[rel.startNode.id]!!,
                neo4jNodeToInt[rel.endNode.id]!!,
                rel.type.name(),
                rel.keys.associateWith { rel[it]!! }
            )
        }
    }
    // write to target outside transaction
}
```

**Expected impact**: Reduces transaction count from O(N+E) to O(1). Neo4j transaction creation has non-trivial overhead (internal bookkeeping, snapshot isolation setup).

**Risk**: Single long-running read transaction holds Neo4j resources longer. Acceptable for transferTo which is inherently a bulk operation.

### N2: StorageID as Native Int Property

**Problem**: `storageID` is stored as a `String` property (`__meta_id__`) on Neo4j entities, requiring `toString()`/`toInt()` conversion on every access during init block and property filtering.

**Proposed change**: Store `storageID` as a native Neo4j `Int` property. Neo4j natively supports `Int`/`Long` properties without serialization overhead.

**Expected impact**: Eliminates string allocation and parsing during init. Marginal per-operation savings (storageID is only read during init and property filtering).

**Risk**: Low. Requires migration path for existing databases (read old String format, write new Int format).

### N3: Property Native Type Fast Path

**Problem**: All `IValue` properties are serialized to `ByteArray` via `DftByteArraySerializerImpl`. For simple types (`NumVal`, `StrVal`), this adds unnecessary serialization/deserialization overhead.

**Proposed change**: Store primitive `IValue` types (`NumVal` -> `Long`, `StrVal` -> `String`, `BoolVal` -> `Boolean`) as Neo4j native properties. Only complex types (`ListVal`, `MapVal`) use `ByteArray` serialization.

**Expected impact**: Eliminates serialize/deserialize for common property types. Neo4j native property access is ~10x faster than ByteArray round-trip.

**Risk**: Medium. Requires type-aware get/set logic in `Neo4jUtils`. Must handle type migration for existing databases. Increases code complexity.

### N4: Entity.keys Filtering Optimization

**Problem**: `getNodeProperties`/`getEdgeProperties` use `node.keys` which includes the internal `__meta_id__` property. The current filtering via `keys` extension property calls `distinct()` which creates unnecessary intermediate collections.

**Proposed change**: Filter `__meta_id__` with a direct `filterNot` instead of going through the `keys` extension.

**Expected impact**: Minor. Reduces one intermediate collection allocation per property read.

**Risk**: Low.

### N5: Eclipse Collections for ID Mapping

**Problem**: `HashMap<Int, Long>` and `HashMap<Long, Int>` autobox all keys and values. For a graph with 100K nodes + 300K edges, this creates ~1.6M wrapper objects.

**Proposed change**: Use eclipse-collections `IntLongHashMap` / `LongIntHashMap` for primitive-specialized open-addressing hash maps.

**Expected impact**: Eliminates autoboxing overhead. Reduces memory footprint ~3x for ID mapping structures. Faster lookup due to cache-friendly linear probing.

**Risk**: Low. Adds eclipse-collections dependency. API change is internal only.

---

## Evaluated & Rejected

_None yet._

---

## Key Insights

1. **Population is catastrophically slow.** Neo4jStorageImpl takes 37,959ms for 1K/3K — over 14,000x slower than NativeStorageImpl (2.6ms for 10K/30K). Each `addNode`/`addEdge` opens a write transaction with Neo4j's WAL (write-ahead log), lock manager, and transaction bookkeeping. This makes Neo4j unsuitable for bulk graph construction.

2. **Population does not scale linearly.** 5K/15K (174,599ms) is 4.6x the 1K/3K cost (37,959ms) for a 5x data increase. Neo4j transaction overhead likely compounds as the B-tree index grows and write-ahead log entries accumulate.

3. **Node lookup is surprisingly fast.** 23.40M ops/sec (Neo4jStorageImpl) — only 6x slower than NativeStorageImpl (143.54M). `containsNode` checks an in-memory `HashMap<Int, Long>` without opening a Neo4j transaction, so this metric reflects HashMap performance, not Neo4j access.

4. **ConcurStorage is faster than non-Concur for node lookup.** 34.12M vs 23.40M (1.5x). This is unexpected and likely an artifact of JIT warmup order rather than a real advantage.

5. **Property write is 3,900x slower than read.** Write: 116 ops/sec vs Read: 452.5K ops/sec. Each property write opens a write transaction, serializes `IValue` to `ByteArray`, sets the Neo4j property, and commits. The commit includes WAL flush + lock release. Property read is fast because it uses a read transaction with snapshot isolation.

6. **Edge query is symmetric.** Outgoing (696.8K) ≈ Incoming (744.7K). Neo4j's relationship chain threading provides O(1) access for both directions. The ~7% incoming advantage is within noise.

7. **ConcurStorage lock overhead is negligible relative to Neo4j transaction cost.** Edge query: Concur (699.3K) ≈ non-Concur (696.8K). The `ReentrantReadWriteLock` acquisition (~20-50ns) is invisible when each operation already incurs ~1-10us of Neo4j transaction overhead.

8. **Neo4j's value is persistence and query language, not throughput.** Property read (452.5K) is 123x slower than NativeStorageImpl (55.80M). Edge query (696.8K) is 167x slower than NativeStorageImpl (116.33M). Neo4j-backed storage should only be used when Cypher queries, ACID transactions, or disk persistence are required.
