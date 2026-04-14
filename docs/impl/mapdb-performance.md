# MapDB Module Performance

Paired design: `storage.design.md`, `mapdb.md`

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), Eclipse Temurin 21.0.6+7-LTS, G1GC with tuned flags.

```bash
./gradlew :modules:impl-mapdb:test --tests "*.MapDBPerformanceTest" -PincludePerformanceTests --rerun
```

Tests all 8 configurations (2 impls x 4 backends). Scale tiers (smaller due to serialization overhead): 5K/15K nodes/edges.

### Architecture

MapDB module provides two `IStorage` implementations using MapDB 3.0.5:
- **`MapDBStorageImpl`** — non-concurrent, `concurrencyDisable()` for zero lock overhead
- **`MapDBConcurStorageImpl`** — thread-safe, external `ReentrantReadWriteLock` + MapDB internal concurrency

Configurable backends: `memoryDB()`, `memoryDirectDB()`, `tempFileDB()`, `tempFileDB().fileMmapEnableIfSupported()`.

Internal structure:
- `EntityPropertyMap` — MapDB-backed property storage using `HTreeMap<Int, SetVal>` (identities) + `HTreeMap<String, IValue>` (flattened composite keys `"$entityId:$propName"`)
- `outEdges` / `inEdges`: `HashMap<Int, MutableSet<Int>>` — in-memory adjacency lists
- `edgeSrcMap` / `edgeDstMap` / `edgeTagMap`: `HashMap<Int, *>` — in-memory edge structural info

Key overhead: every property access involves serialization through `MapDbValSerializer` -> `DftByteArraySerializerImpl`.

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

## Key Improvements

_None yet._

---

## Completed Optimizations

_None yet._

---

## Evaluated & Rejected

| ID | Title | Result | Reason |
|---|---|---|---|
| — | Off-heap storage for graph module | ~3-6us per access | Serialization overhead negates GC benefit. See main `performance.md`. |

---

## Candidates

### P8-1: Split adjacency by direction

**Problem**: If adjacency were stored in a single MapDB SetVal per node, querying one direction requires deserializing the entire set and filtering. Current implementation already uses in-memory `HashMap<Int, MutableSet<Int>>` for adjacency, avoiding this issue for topology queries. However, the pattern applies to `EntityPropertyMap` identity sets.

**Expected impact**: Halves serialization cost for directional queries if adjacency moves to MapDB.

**Risk**: Low. Doubles MapDB collection count for adjacency.

### P8-2: Composite key edge storage

**Problem**: MapDB copy-on-write means adding one property key to an entity's identity SetVal requires full re-serialization. For entities with many properties, this is O(properties) per key insertion.

**Proposed change**: Store each edge as independent MapDB entry with composite key. Query by prefix scan using `BTreeMap`.

**Expected impact**: O(1) per edge insertion/deletion.

**Risk**: Medium. Significantly increases key count. Requires `BTreeMap` instead of `HTreeMap`.

### P8-3: Native type property fast path

**Problem**: All properties go through `MapDbValSerializer` -> `DftByteArraySerializerImpl`. For `NumVal`/`StrVal`, this adds unnecessary serialization overhead.

**Proposed change**: Detect simple types and store with MapDB built-in `Serializer.LONG` / `Serializer.STRING`.

**Expected impact**: Eliminates serialize/deserialize for common property types.

**Risk**: High. Requires separate MapDB collections per type or tagged union storage.

### P8-4: concurrencyDisable() quantification

**Problem**: `concurrencyDisable()` benefit not quantified.

**Proposed change**: Benchmark `memoryDB()` with and without `concurrencyDisable()`.

**Risk**: None. Benchmark-only.

### P8-5: Batch property operations

**Problem**: `getNodeProperties` reads identity set (1 MapDB access), then each property individually (N accesses). 6 round-trips for 5 properties.

**Proposed change**: Store all properties as single serialized blob per entity.

**Expected impact**: Reduces MapDB access from O(properties) to O(1) per entity.

**Risk**: Medium. Loses per-property granularity; any update requires full re-serialization.

---

## Remaining Known Bottlenecks

- Serialization dominates all MapDB operations (35x slower than NativeStorage for property read).
- Write disproportionately slow (17x slower than read for memoryDB; 53x for tempFileDB).
- tempFileDB without mmap is 10x slower than memory backends.

---

## Key Insights

1. **Serialization is the dominant cost.** MapDB[memoryDB] property read (1.61M) is 32x slower than NativeStorageImpl (51.46M).

2. **Write is disproportionately slow.** 94.7K write vs 1.61M read for memoryDB. Write requires serialization + record update + potential page split.

3. **tempFileDB without mmap is 10x slower.** 163.4K (tempFileDB) vs 1.83M (tempFile+mmap) for node lookup.

4. **mmap nearly matches in-memory performance.** tempFile+mmap node lookup (1.83M) matches memoryDB (1.14M).

5. **ConcurStorage read penalty is 3.7x for memoryDB.** Double-lock overhead (external RWLock + MapDB internal).

6. **Edge query is symmetric.** Serialization cost dominates over data structure asymmetry.

7. **Population at 5K/15K takes 150ms — orders of magnitude slower than NativeStorage.** Serialization pipeline makes MapDB impractical for high-frequency graph construction.

8. **`concurrencyDisable()` shows minimal population benefit.** 150.9ms vs 154.2ms. Serialization bottleneck masks lock overhead.
