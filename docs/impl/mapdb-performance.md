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

_No benchmark data collected yet. Run the performance test to populate._

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

1. **Serialization is the dominant cost.** Every MapDB access involves `IValue` -> `ByteArray` -> MapDB internal storage -> `ByteArray` -> `IValue`. This 4-step pipeline makes MapDB inherently slower than in-memory HashMap for per-operation throughput. MapDB's value proposition is persistence and off-heap storage, not speed.

2. **Copy-on-write amplifies degree-dependent operations.** Adding/removing an edge from a node's adjacency set requires full deserialization + modification + re-serialization of the set. For a node with degree 100, adding one edge processes 100 existing edges. This is the primary bottleneck for graph mutation.

3. **`concurrencyDisable()` is critical for single-threaded performance.** MapDB's internal `Store2` acquires a `ReentrantLock` on every record access. In single-threaded scenarios, this is pure overhead with zero benefit.

4. **Backend choice impacts I/O-bound operations.** `memoryDB()` is fastest for pure computation. `memoryDirectDB()` avoids GC for stored data. `tempFileDB().fileMmapEnableIfSupported()` is recommended for file persistence (avoids user/kernel space copies via mmap).

5. **MapDB shifts GC pressure, doesn't eliminate it.** While stored data is off-heap, every read creates temporary `ByteArray` + deserialized `IValue` objects in Young Gen. For traversal-intensive workloads, Young Gen pressure from MapDB can exceed Old Gen pressure from in-memory HashMap storage.

6. **`EntityPropertyMap` flattened key design trades memory for lookup speed.** Composite keys (`"$entityId:$propName"`) in a single `HTreeMap` avoid per-entity nested map allocation. But string concatenation for key construction adds overhead per access.
