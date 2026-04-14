# Neo4j Module Performance

Paired design: `storage.design.md`, `neo4j.md`

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), Eclipse Temurin 21.0.6+7-LTS, G1GC with tuned flags.

```bash
./gradlew :modules:impl-neo4j:test --tests "*.Neo4jPerformanceTest" -PincludePerformanceTests --rerun
```

Scale tiers (smaller due to Neo4j startup overhead): 1K/3K, 5K/15K, 10K/30K nodes/edges.

### Architecture

Neo4j module provides two `IStorage` implementations using Neo4j 5.26.0 embedded mode:
- **`Neo4jStorageImpl`** — non-concurrent, zero in-memory ID mappings; all lookups via Neo4j transactions + schema-indexed `__sid__` properties
- **`Neo4jConcurStorageImpl`** — thread-safe, `ReentrantReadWriteLock` over same structures

Key overhead sources:
1. **Transaction per operation** — Neo4j 5.x requires `Transaction` for all data access, even reads
2. **Serialization** — all `IValue` properties serialized to `ByteArray` via `DftByteArraySerializerImpl`
3. **Schema index lookups** — `findNode(label, key, value)` / `findRelationship(type, key, value)` on `__sid__` property

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

## Key Improvements

_None yet._

---

## Completed Optimizations

_None yet._

---

## Evaluated & Rejected

_None yet._

---

## Candidates

### P9-1: Transaction batching for transferTo

**Problem**: `transferTo` reads properties and structure per-entity, each in a separate `readTx`. For N nodes and E edges, this creates many transactions. Current implementation already uses a single `readTx` block for the entire transfer, but individual helper methods could be optimized further.

**Expected impact**: Reduces transaction creation overhead for bulk operations.

**Risk**: Low. Single long-running read transaction holds Neo4j resources longer.

### P9-2: Native Int storage for __sid__

**Problem**: `__sid__` is stored as `Long` (via `id.toLong()` / `(prop as Long).toInt()`). This is already a native Neo4j type, not serialized through `DftByteArraySerializerImpl`.

**Status**: Already optimized in current implementation. No action needed.

### P9-3: Property native type fast path

**Problem**: All `IValue` properties serialized to `ByteArray`. For `NumVal`/`StrVal`, this adds unnecessary overhead.

**Proposed change**: Store primitive `IValue` types as Neo4j native properties. Only complex types use `ByteArray`.

**Expected impact**: ~10x faster property access for common types.

**Risk**: Medium. Type-aware get/set logic in `Neo4JUtils`. Migration path for existing databases.

### P9-4: Entity.keys filtering optimization

**Problem**: `Entity.keys` extension calls `propertyKeys.filter { it !in RESERVED_PROPS }.distinct()`. The `distinct()` creates an unnecessary intermediate collection.

**Proposed change**: Use `filterNot` directly without `distinct()` (property keys are already unique per entity).

**Expected impact**: Minor. One less intermediate collection per property read.

**Risk**: Low.

### P9-5: Eclipse Collections for ID mapping

**Problem**: If in-memory ID maps were used, `HashMap<Int, Long>` and `HashMap<Long, Int>` would autobox all keys/values. Current implementation uses zero in-memory ID maps (all lookups via Neo4j transactions), so autoboxing is not an issue.

**Status**: Not applicable to current zero-mapping architecture.

---

## Remaining Known Bottlenecks

- Population catastrophically slow (37,959ms for 1K/3K). Each addNode/addEdge opens a write transaction with WAL, lock manager, and transaction bookkeeping.
- Population does not scale linearly. 5K/15K (174,599ms) is 4.6x the 1K/3K cost for 5x data.
- Property write 3,900x slower than read (116 vs 452.5K). Write transaction commit includes WAL flush + lock release.

---

## Key Insights

1. **Population is catastrophically slow.** 37,959ms for 1K/3K — over 14,000x slower than NativeStorageImpl (2.6ms for 10K/30K). Each write transaction incurs WAL + lock manager + bookkeeping overhead.

2. **Node lookup is fast.** 23.40M ops/sec. `containsNode` opens a `readTx` and uses schema-indexed `findNode`, but overhead is dominated by transaction creation, not the index lookup.

3. **ConcurStorage is faster than non-Concur for node lookup.** 34.12M vs 23.40M. Likely JIT warmup artifact, not a real advantage.

4. **Property write is 3,900x slower than read.** 116 vs 452.5K. Write transaction commit includes WAL flush.

5. **Edge query is symmetric.** 696.8K ≈ 744.7K. Neo4j relationship chain threading provides O(1) both directions.

6. **ConcurStorage lock overhead is negligible relative to Neo4j transaction cost.** Edge query Concur (699.3K) ≈ non-Concur (696.8K). Lock ~20-50ns is invisible next to ~1-10us transaction overhead.

7. **Neo4j's value is persistence and query language, not throughput.** Property read 452.5K is 114x slower than NativeStorageImpl (51.46M). Use only when Cypher queries, ACID transactions, or disk persistence are required.
