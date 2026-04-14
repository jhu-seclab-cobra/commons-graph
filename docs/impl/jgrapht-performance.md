# JGraphT Module Performance

Paired design: `storage.design.md`, `jgrapht.md`

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), Eclipse Temurin 21.0.6+7-LTS, G1GC with tuned flags.

```bash
./gradlew :modules:impl-jgrapht:test --tests "*.JgraphtPerformanceTest" -PincludePerformanceTests --rerun
```

Scale tiers: 10K/30K, 100K/300K, 1M/3M nodes/edges.

### Architecture

JGraphT module provides two `IStorage` implementations:
- **`JgraphtStorageImpl`** — non-concurrent, JGraphT graph + HashMap properties
- **`JgraphtConcurStorageImpl`** — thread-safe, `ReentrantReadWriteLock` over same structures

Internal structure:
- `jgtGraph: DirectedPseudograph<String, String>` — JGraphT graph for topology, using String vertices/edges with Int-to-String bidirectional mapping
- `nodeProperties: HashMap<Int, MutableMap<String, IValue>>` — node property storage keyed by Int ID
- `edgeProperties: HashMap<Int, MutableMap<String, IValue>>` — edge property storage keyed by Int ID
- `edgeTagMap: HashMap<Int, String>` — edge tag cache keyed by Int ID

JGraphT provides O(1) `incomingEdgesOf`/`outgoingEdgesOf` via internal `DirectedEdgeContainer`. Edge endpoint and property queries are backed by JVM HashMap.

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

### P7-1: FastLookupGraphSpecificsStrategy

**Problem**: `DirectedPseudograph` default strategy resolves `getEdge(u, v)` / `containsEdge(u, v)` / `getAllEdges(u, v)` in O(out_degree(u)) by scanning outgoing edges.

**Proposed change**: Use `FastLookupGraphSpecificsStrategy` with additional `Map<Pair<V,V>, Set<E>>` for O(1) vertex-pair edge lookup.

**Expected impact**: `getEdgesBetween`-equivalent operations become O(1). Memory cost: one additional Map entry per edge.

**Risk**: Low. Only beneficial if `getEdgesBetween` or `containsEdge(src, dst)` is a hot path. Current IStorage interface does not expose `getEdgesBetween`, so this is future-proofing.

### P7-2: Eliminate redundant deleteNode edge traversal

**Problem**: `deleteNode` manually iterates all incident edges to remove from property/mapping maps, then calls `jgtGraph.removeVertex(id)` which internally iterates all edges again. Total cost: 2 x O(degree).

**Proposed change**: Collect edge IDs, clean only property/mapping maps, then let `removeVertex` handle topology cleanup.

**Expected impact**: Halves delete cost for high-degree nodes.

**Risk**: Low. `removeVertex` already handles topology cleanup internally.

### P7-3: Direct property map return (avoid defensive copy)

**Problem**: If `getNodeProperties`/`getEdgeProperties` returns a defensive copy, every property read allocates a new Map.

**Proposed change**: Return an unmodifiable view (`Collections.unmodifiableMap()`) of the internal map.

**Expected impact**: Eliminates O(properties) allocation per property read.

**Risk**: Medium. Callers must not retain references across mutations.

### P7-4: Eclipse Collections for edge structural maps

**Problem**: `edgeTagMap: HashMap<Int, String>` and bidirectional mapping maps (`intToVertex`, `vertexToInt`, `intToEdge`, `edgeToInt`) use boxed keys. For 300K edges, this creates significant Integer wrapper objects.

**Proposed change**: Use eclipse-collections primitive maps for Int-keyed structural maps.

**Expected impact**: Eliminates autoboxing. Reduces memory ~3x for edge maps.

**Risk**: Low. Adds eclipse-collections dependency. Internal-only change.

---

## Remaining Known Bottlenecks

- Population 4x slower than NativeStorage due to JGraphT `DirectedPseudograph.addEdge()` internal `DirectedEdgeContainer` allocation overhead.
- ConcurStorage property read 8.5x slowdown (9.57M vs 81.13M) — lock acquisition cost dominates when underlying operation is ~12ns direct HashMap reference return.

---

## Key Insights

1. **JGraphT property read is fastest of all implementations.** 81.13M ops/sec. Returns internal `MutableMap` reference directly.

2. **JGraphT population is 4x slower than NativeStorage.** 251.7 ms vs 88.2 ms at 100K/300K. JGraphT `DirectedEdgeContainer` per-vertex allocation overhead.

3. **ConcurStorage property read shows 8.5x slowdown.** Lock acquisition (~20-50ns) is disproportionate when the underlying operation (~12ns) is extremely fast.

4. **Edge query is symmetric.** Outgoing 8.97M ≈ Incoming 8.79M. `DirectedEdgeContainer` provides O(1) both directions.

5. **`removeVertex` automatically deletes all edges.** Exploiting this avoids redundant manual edge cleanup in `deleteNode`.

6. **In-memory only — no persistence overhead.** No serialization, transaction, or disk I/O cost. Performance ceiling is JVM HashMap + JGraphT data structure speed.
