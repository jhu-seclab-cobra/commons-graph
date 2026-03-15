# MapDB Module Performance

Paired design: `storage.design.md`, `mapdb.md`

---

## Architecture

MapDB module provides two `IStorage` implementations using MapDB 3.0.5:
- **`MapDBStorageImpl`** — non-concurrent, `concurrencyDisable()` for zero lock overhead
- **`MapDBConcurStorageImpl`** — thread-safe, external `ReentrantReadWriteLock` + MapDB internal concurrency

Configurable backends via `DBMaker` lambda:
- `memoryDB()` — pure heap memory
- `memoryDirectDB()` — off-heap direct byte buffers
- `tempFileDB()` — file-backed with default I/O
- `tempFileDB().fileMmapEnableIfSupported()` — file-backed with memory-mapped I/O

Internal structure:
- `EntityPropertyMap` — flattened property storage using `hashMap<String, IValue>` with composite keys (`"$entityId:$propName"`)
- `identities: HTreeMap<String, SetVal>` — entity ID to property key set mapping
- Edge structural info stored in separate HashMaps (`edgeSrcMap`, `edgeDstMap`, `edgeTypeMap`)

Key overhead: every MapDB access involves serialization/deserialization through `MapDbValSerializer` -> `DftByteArraySerializerImpl`.

---

## Benchmark Infrastructure

```bash
./gradlew :modules:impl-mapdb:test --tests "*.MapDBPerformanceTest" -PincludePerformanceTests --rerun
```

Tests all 8 configurations (2 impls x 4 backends). Scale tiers (smaller than JGraphT due to serialization overhead):
- 5K nodes / 15K edges (population)
- 5K nodes (node lookup, 50K ops)
- 5K nodes (property read/write, 20K ops)
- 2K nodes / 10K edges (edge query, 10K ops)

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), Eclipse Temurin 21.0.6+7-LTS, G1GC with tuned flags.

### Graph Population (median ms, 5K nodes / 15K edges)

| Config | ms |
|---|---|
| MapDB[memoryDB] | 150.9 |
| MapDB[memoryDirectDB] | 167.6 |
| MapDB[tempFileDB] | 4134.9 |
| MapDB[tempFile+mmap] | 1858.1 |
| MapDBConcur[memoryDB] | 154.2 |
| MapDBConcur[memDirect] | 175.4 |
| MapDBConcur[tempFile] | 4053.8 |
| MapDBConcur[tmpFile+mm] | 1463.9 |

### Node Lookup (50K lookups on 5K nodes)

| Config | ops/sec |
|---|---|
| MapDB[memoryDB] | 1.14M |
| MapDB[memoryDirectDB] | 1.70M |
| MapDB[tempFileDB] | 163.4K |
| MapDB[tempFile+mmap] | 1.83M |
| MapDBConcur[memoryDB] | 1.75M |
| MapDBConcur[memDirect] | 1.81M |
| MapDBConcur[tempFile] | 161.6K |
| MapDBConcur[tmpFile+mm] | 1.84M |

### Property Read/Write (20K ops on 5K nodes)

| Config | Read | Write |
|---|---|---|
| MapDB[memoryDB] | 1.61M | 94.7K |
| MapDB[memoryDirectDB] | 1.67M | 87.6K |
| MapDB[tempFileDB] | 148.6K | 2.8K |
| MapDB[tempFile+mmap] | 1.66M | 24.4K |
| MapDBConcur[memoryDB] | 439.4K | 86.9K |
| MapDBConcur[memDirect] | 396.1K | 82.8K |
| MapDBConcur[tempFile] | 35.9K | 3.0K |
| MapDBConcur[tmpFile+mm] | 399.7K | 10.6K |

### Edge Query (10K queries, 2K nodes / 10K edges)

| Config | Outgoing | Incoming |
|---|---|---|
| MapDB[memoryDB] | 1.43M | 1.49M |
| MapDB[memoryDirectDB] | 1.49M | 1.48M |
| MapDB[tempFileDB] | 144.7K | 146.1K |
| MapDB[tempFile+mmap] | 1.52M | 1.51M |
| MapDBConcur[memoryDB] | 1.23M | 1.30M |
| MapDBConcur[memDirect] | 1.32M | 1.29M |
| MapDBConcur[tempFile] | 139.3K | 142.3K |
| MapDBConcur[tmpFile+mm] | 1.33M | 1.32M |

---

## Optimization Candidates

### M1: Split Adjacency by Direction

**Problem**: Each node's adjacency is stored as a single `SetVal` containing all incoming and outgoing edges. Querying one direction requires deserializing the entire set and filtering.

**Current cost**: O(degree_total) deserialize + O(degree_total) filter, even when only incoming or outgoing is needed.

**Proposed change**: Maintain two separate `HTreeMap`s for incoming and outgoing edges.

```kotlin
private val incomingStructure = dbManager.hashMap("incoming", ...).createOrOpen()
private val outgoingStructure = dbManager.hashMap("outgoing", ...).createOrOpen()
```

**Expected impact**: Halves serialization cost for directional edge queries. Read and write both reduce from O(degree_total) to O(degree_direction).

**Risk**: Low. Doubles the number of MapDB collections for adjacency. Increases write cost slightly (two puts instead of one read-modify-write).

### M2: Composite Key Edge Storage

**Problem**: The adjacency `SetVal` approach suffers from copy-on-write — adding one edge requires deserializing the entire set, appending, serializing the entire set, and writing back. For high-degree nodes, this is O(degree) per edge insertion.

**Proposed change**: Store each edge as an independent MapDB entry with composite key `"nodeId:IN:edgeId"` / `"nodeId:OUT:edgeId"`. Query by prefix scan.

**Expected impact**: O(1) per edge insertion/deletion (no set serialization). Edge query becomes O(degree_direction) key scan, same as current.

**Risk**: Medium. Significantly increases key count in MapDB. Prefix scan requires `BTreeMap` instead of `HTreeMap` (different performance characteristics). Increases MapDB internal metadata overhead.

### M3: Native Type Property Fast Path

**Problem**: All properties go through `MapDbValSerializer` -> `DftByteArraySerializerImpl` -> `Serializer.BYTE_ARRAY`. For `NumVal` (wrapping `Long`) and `StrVal` (wrapping `String`), this adds unnecessary serialization overhead.

**Proposed change**: Detect simple `IValue` types and store directly with MapDB's built-in `Serializer.LONG` / `Serializer.STRING`. Only complex types use `ByteArray` serialization.

**Expected impact**: Eliminates serialize/deserialize overhead for the most common property types.

**Risk**: High. Requires separate MapDB collections per type, or a tagged union storage scheme. Complicates `EntityPropertyMap` significantly.

### M4: concurrencyDisable() Verification

**Problem**: `MapDBStorageImpl` uses `concurrencyDisable()` to remove MapDB internal locks. The performance benefit has not been quantified.

**Proposed change**: Add a benchmark comparing `memoryDB()` with and without `concurrencyDisable()` to measure the actual lock overhead.

**Expected impact**: Quantifies the benefit of removing internal locks in single-threaded scenarios. MapDB's `Store2` uses `ReentrantLock` on every get/put, so the savings should be measurable on high-frequency operations.

**Risk**: None. Benchmark-only change.

### M5: Batch Property Operations

**Problem**: `getNodeProperties` reads the identity set (one MapDB access), then reads each property individually (N MapDB accesses). For an entity with 5 properties, this is 6 serialization round-trips.

**Proposed change**: Store all properties for an entity as a single serialized blob (e.g., `Map<String, IValue>` serialized as one `ByteArray`). Single read/write per entity.

**Expected impact**: Reduces MapDB access count from O(properties) to O(1) per entity. Eliminates composite key construction overhead.

**Risk**: Medium. Loses per-property granularity. Any property update requires full entity re-serialization and write. Trade-off between read and write paths.

---

## Evaluated & Rejected

| ID | Optimization | Result | Reason |
|---|---|---|---|
| — | Off-heap storage for graph module | ~3-6us per access | Serialization overhead negates GC benefit. See main `performance.md` evaluated section. |

---

## Key Insights

1. **Serialization is the dominant cost.** MapDB[memoryDB] property read (1.61M) is 35x slower than NativeStorageImpl (55.80M). Every access involves `IValue` -> `ByteArray` -> MapDB internal storage -> `ByteArray` -> `IValue`. MapDB's value proposition is persistence and off-heap storage, not speed.

2. **Write is disproportionately slow.** MapDB[memoryDB] property write (94.7K) is 17x slower than read (1.61M). Write requires serialization + MapDB record update + potential page split. For file-backed, write drops to 2.8K ops/sec (tempFileDB) due to fsync overhead.

3. **tempFileDB without mmap is 10x slower than memory backends.** Node lookup: 163.4K (tempFileDB) vs 1.83M (tempFile+mmap). File I/O through Java NIO channels adds kernel/user space copy overhead that mmap eliminates.

4. **mmap nearly matches in-memory performance.** tempFile+mmap node lookup (1.83M) matches memoryDB (1.14M) and memoryDirectDB (1.70M). Memory-mapped files leverage OS page cache, making hot data access equivalent to RAM access.

5. **ConcurStorage read penalty is 3.7x for memoryDB.** Property read: 439.4K (Concur) vs 1.61M (non-Concur). The external `ReentrantReadWriteLock` plus MapDB internal concurrency create double-lock overhead. Write is only 1.1x slower (86.9K vs 94.7K), suggesting write is bottlenecked by serialization, not locking.

6. **Edge query is symmetric across all backends.** Outgoing ≈ Incoming for all configs (e.g., memoryDB: 1.43M vs 1.49M). Unlike NativeStorageImpl where outgoing (116.33M) is 2.3x faster than incoming (49.94M), MapDB's serialization cost dominates over data structure asymmetry.

7. **Population at 5K/15K takes 150ms — 60x slower than NativeStorage at same scale.** NativeStorageImpl populates 10K/30K in 2.6ms (~1.3ms for 5K/15K). The serialization pipeline makes MapDB impractical for high-frequency graph construction.

8. **`concurrencyDisable()` shows minimal benefit in population.** MapDB[memoryDB] (150.9ms) vs MapDBConcur[memoryDB] (154.2ms) — only 2% difference. The bottleneck is serialization, not lock acquisition. `concurrencyDisable()` may matter more for micro-operations (property read shows 1.61M vs 439.4K, but Concur also adds external RWLock).
