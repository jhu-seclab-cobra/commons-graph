# MapDB Module Performance

Paired design: `storage.design.md`, `mapdb.md`

## Current Baseline

macOS (Apple Silicon), Temurin 21.0.6+7-LTS, G1GC. Separate JVM per implementation.

```bash
./gradlew :modules:impl-mapdb:test --tests "*.MapDBPerformanceTest" -PincludePerformanceTests --rerun
```

Tests all 8 configurations (2 impls x 4 backends). Scale: 5K/15K nodes/edges.

### Architecture

`EntityPropertyMap`: MapDB-backed `HTreeMap<Int, SetVal>` (identities) + `HTreeMap<String, IValue>` (flattened composite keys `"$entityId:$propName"`). Adjacency in JVM `HashMap<Int, MutableSet<Int>>`. Every property access serializes through `MapDbValSerializer` -> `DftByteArraySerializerImpl`.

Two implementations: `MapDBStorageImpl` (non-concurrent, `concurrencyDisable()`) and `MapDBConcurStorageImpl` (thread-safe, external `ReentrantReadWriteLock` + MapDB internal concurrency). Backends: `memoryDB()`, `memoryDirectDB()`, `tempFileDB()`, `tempFileDB().fileMmapEnableIfSupported()`.

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

## Completed Optimizations

_None yet._

## Evaluated & Rejected

| ID | Title | Result | Reason |
|---|---|---|---|
| P8-1 | Split adjacency by direction | Not applicable | Adjacency already uses in-memory `HashMap<Int, MutableSet<Int>>`; MapDB serialization only applies to `EntityPropertyMap` |
| -- | Off-heap storage for graph module | ~3-6us per access | Serialization overhead negates GC benefit. See `performance.md` cross-implementation comparison |

## Candidates

### P8-2: Composite key edge storage

MapDB copy-on-write requires full re-serialization for identity SetVal updates. Proposed: store each edge as independent `BTreeMap` entry with composite key, O(1) insertion. Risk: medium (increases key count, requires BTreeMap).

### P8-3: Native type property fast path

Detect `NumVal`/`StrVal` and store with MapDB built-in `Serializer.LONG` / `Serializer.STRING`. Risk: high (requires separate collections per type or tagged union).

### P8-4: concurrencyDisable() quantification

Benchmark `memoryDB()` with and without `concurrencyDisable()`. Risk: none (benchmark-only).

### P8-5: Batch property operations

Store all properties as single serialized blob per entity. Reduces MapDB access from O(properties) to O(1). Risk: medium (loses per-property granularity).

---

## Remaining Known Bottlenecks

- Serialization dominates all operations.
- Write disproportionately slow (17x slower than read for memoryDB; 53x for tempFileDB).
- tempFileDB without mmap is 10x slower than memory backends.

---

## Key Insights

1. **Serialization is the dominant cost.** Property read 1.61M for memoryDB.
2. **Write is disproportionately slow.** 94.7K write vs 1.61M read for memoryDB (serialize + record update + page split).
3. **tempFileDB without mmap is 10x slower.** 163.4K vs 1.83M for node lookup.
4. **mmap nearly matches in-memory performance.** tempFile+mmap 1.83M vs memoryDB 1.14M for node lookup.
5. **ConcurStorage read penalty is 3.7x for memoryDB.** Double-lock overhead (external RWLock + MapDB internal).
6. **Edge query is symmetric.** Serialization cost dominates over data structure asymmetry.
7. **`concurrencyDisable()` shows minimal population benefit.** 150.9ms vs 154.2ms. Serialization masks lock overhead.
