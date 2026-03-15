# Neo4j Module Performance

Paired design: `storage.design.md`, `neo4j.md`

---

## Architecture

Neo4j module provides two `IStorage` implementations using Neo4j 5.x embedded mode:
- **`Neo4jStorageImpl`** — non-concurrent, plain `HashMap` + `Int` counters
- **`Neo4jConcurStorageImpl`** — thread-safe, `ReentrantReadWriteLock` over same structures

Both maintain bidirectional ID mapping (`HashMap<Int, Long>` / `HashMap<Long, Int>`) between `IStorage` Int IDs and Neo4j internal Long IDs. Edge structural info (src, dst, type) is queried through Neo4j native `Relationship` methods within transactions.

Key overhead sources:
1. **Transaction per operation** — Neo4j 5.x requires `Transaction` for all data access, even reads
2. **Serialization** — all `IValue` properties serialized to `ByteArray` via `DftByteArraySerializerImpl`
3. **ID mapping** — bidirectional HashMap lookup on every operation

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

_No benchmark data collected yet. Run the performance test to populate._

---

## Optimization Candidates

### N1: Transaction Batching for transferTo

**Problem**: `transferTo` calls `getNodeProperties`, `getEdgeSrc`, `getEdgeDst`, `getEdgeType`, `getEdgeProperties` individually — each opens a separate `readTx`. For a graph with N nodes and E edges, this creates 2N + 4E transactions.

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

1. **Neo4j transaction overhead dominates small operations.** Every `containsNode`, `getEdgeSrc`, etc. opens and closes a Neo4j transaction. For in-memory graph operations, this overhead is 100-1000x compared to direct HashMap access. This is the fundamental performance ceiling for Neo4j-backed storage.

2. **ID mapping is necessary despite overhead.** Neo4j uses internal `Long` IDs that are not stable across restarts (in some configurations). The bidirectional `Int <-> Long` mapping ensures IStorage contract stability but adds one HashMap lookup per operation.

3. **Neo4j embedded startup is heavyweight.** Database initialization takes 1-3 seconds, making Neo4j unsuitable for short-lived or frequently-created storage instances. The `lazy` initialization pattern defers this cost until first access.

4. **Concurrent variant adds lock overhead but minimal contention.** `ReentrantReadWriteLock` allows concurrent reads while serializing writes. For read-heavy workloads, throughput should approach non-concurrent variant. The lock acquisition cost (~20-50ns) is negligible compared to Neo4j transaction overhead (~1-10us).

5. **Metadata is in-memory only.** `metaProperties` HashMap is not persisted to Neo4j. This is intentional — meta is lightweight key-value storage for graph-level configuration, not entity data.
