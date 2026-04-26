# JGraphT Module Performance

Paired design: `design-storage.md`, `impl.md`

## Current Baseline

macOS (Apple Silicon), Temurin 21.0.6+7-LTS, G1GC. Separate JVM per implementation.

```bash
./gradlew :modules:impl-jgrapht:test --tests "*.JgraphtPerformanceTest" -PincludePerformanceTests --rerun
```

Scale tiers: 10K/30K, 100K/300K, 1M/3M nodes/edges.

### Architecture

JGraphT `DirectedPseudograph<String, String>` for topology with Int-to-String bidirectional mapping. Property storage in JVM `HashMap<Int, MutableMap<String, IValue>>`. O(1) `incomingEdgesOf`/`outgoingEdgesOf` via internal `DirectedEdgeContainer`.

Two implementations: `JgraphtStorageImpl` (non-concurrent) and `JgraphtConcurStorageImpl` (thread-safe, `ReentrantReadWriteLock`).

### Graph Population (median ms)

| Implementation | 10K/30K | 100K/300K | 1M/3M |
|---|---|---|---|
| JgraphtStorageImpl | 18.5 | 251.7 | 2939.6 |
| JgraphtConcurStorageImpl | 14.2 | 232.5 | 2924.0 |

### Node Lookup (500K lookups on 100K nodes)

| Implementation | ops/sec |
|---|---|
| JgraphtStorageImpl | 40.46M |
| JgraphtConcurStorageImpl | 24.20M |

### Property Read/Write (200K ops on 50K nodes)

| Implementation | Read | Write |
|---|---|---|
| JgraphtStorageImpl | 81.13M | 33.38M |
| JgraphtConcurStorageImpl | 9.57M | 11.00M |

### Edge Query (100K queries, 10K nodes / 50K edges)

| Implementation | Outgoing | Incoming |
|---|---|---|
| JgraphtStorageImpl | 8.97M | 8.79M |
| JgraphtConcurStorageImpl | 8.18M | 7.38M |

---

## Evaluated & Rejected

| ID | Title | Result | Reason |
|---|---|---|---|
| P7-1 | FastLookupGraphSpecificsStrategy | YAGNI | IStorage interface does not expose `getEdgesBetween`; no hot path benefits |

## Candidates

### P7-2: Eliminate redundant deleteNode edge traversal

Manual edge iteration + `removeVertex` internal iteration = 2 x O(degree). Proposed: collect edge IDs, clean property/mapping maps only, let `removeVertex` handle topology. Risk: low.

### P7-3: Direct property map return (avoid defensive copy)

Return `Collections.unmodifiableMap()` view instead of defensive copy. Risk: medium (callers must not retain references across mutations).

### P7-4: Eclipse Collections for edge structural maps

`edgeTagMap`, bidirectional mapping maps use boxed Int keys. Primitive maps eliminate autoboxing and reduce memory ~3x. Risk: low.

---

## Remaining Known Bottlenecks

- Population overhead from JGraphT `DirectedEdgeContainer` allocation per vertex.
- ConcurStorage property read 8.5x slowdown (9.57M vs 81.13M): lock acquisition dominates.

---

## Key Insights

1. **Property read returns internal MutableMap reference directly.** 81.13M ops/sec, highest of all implementations.
2. **Edge query is symmetric.** `DirectedEdgeContainer` O(1) both directions.
3. **`removeVertex` automatically deletes all edges.** Exploiting this avoids redundant manual cleanup.
4. **In-memory only -- no persistence overhead.**
