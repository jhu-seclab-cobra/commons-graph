# JGraphT Module Performance

Paired design: `storage.design.md`, `jgrapht.md`

---

## Architecture

JGraphT module provides two `IStorage` implementations using JGraphT `DirectedPseudograph`:
- **`JgraphtStorageImpl`** — non-concurrent, direct JGraphT graph + HashMap properties
- **`JgraphtConcurStorageImpl`** — thread-safe, `ReentrantReadWriteLock` over same structures

Internal structure:
- `jgtGraph: DirectedPseudograph<Int, Int>` — JGraphT graph for topology (adjacency, edge endpoints)
- `nodeProperties: HashMap<Int, MutableMap<String, IValue>>` — node property storage
- `edgeProperties: HashMap<Int, MutableMap<String, IValue>>` — edge property storage
- `edgeSrcMap / edgeDstMap / edgeTypeMap: HashMap<Int, *>` — edge structural info cache

JGraphT provides O(1) `incomingEdgesOf`/`outgoingEdgesOf` via internal `DirectedEdgeContainer`. Edge endpoint and property queries are backed by JVM HashMap.

---

## Benchmark Infrastructure

```bash
./gradlew :modules:impl-jgrapht:test --tests "*.JgraphtPerformanceTest" -PincludePerformanceTests --rerun
```

Scale tiers:
- 10K nodes / 30K edges
- 100K nodes / 300K edges
- 1M nodes / 3M edges

Benchmark categories:
- Graph population (median ms)
- Node lookup (500K ops on 100K nodes)
- Property read/write (200K ops on 50K nodes)
- Edge query — outgoing/incoming (100K queries on 10K nodes / 50K edges)

---

## Current Baseline

Benchmarked on macOS (Apple Silicon), Eclipse Temurin 21.0.6+7-LTS, G1GC with tuned flags.

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

## Candidates

### J1: FastLookupGraphSpecificsStrategy

**Problem**: `DirectedPseudograph` default strategy resolves `getEdge(u, v)` / `containsEdge(u, v)` / `getAllEdges(u, v)` in O(out_degree(u)) by scanning outgoing edges.

**Proposed change**: Use `FastLookupGraphSpecificsStrategy` which maintains an additional `Map<Pair<V,V>, Set<E>>` for O(1) vertex-pair edge lookup.

```kotlin
val strategy = FastLookupGraphSpecificsStrategy<Int, Int>()
val jgtGraph = DirectedPseudograph<Int, Int>(null, null, false, false, strategy)
```

**Expected impact**: `getEdgesBetween`-equivalent operations become O(1) instead of O(out_degree). Memory cost: one additional Map entry per edge.

**Risk**: Low. Only affects vertex-pair queries. Standard single-vertex adjacency queries (`incomingEdgesOf`/`outgoingEdgesOf`) are unaffected.

**Applicability**: Only beneficial if `getEdgesBetween` or `containsEdge(src, dst)` is a hot path. Current IStorage interface does not expose `getEdgesBetween`, so this is future-proofing.

### J2: Eliminate Redundant deleteNode Edge Traversal

**Problem**: `deleteNode` manually iterates all incoming/outgoing edges to remove from `edgeProperties` map, then calls `jgtGraph.removeVertex(id)` which internally iterates all edges again to remove from topology. Total cost: 2 x O(degree).

**Proposed change**: Collect edge IDs, clean only `edgeProperties` map, then let `removeVertex` handle topology cleanup.

```kotlin
override fun deleteNode(id: Int) {
    if (!containsNode(id)) throw EntityNotExistException(id)
    val allEdges = jgtGraph.incomingEdgesOf(id) + jgtGraph.outgoingEdgesOf(id)
    allEdges.forEach { edgeProperties.remove(it); edgeSrcMap.remove(it); edgeDstMap.remove(it); edgeTypeMap.remove(it) }
    jgtGraph.removeVertex(id)  // handles topology cleanup
    nodeProperties.remove(id)
}
```

**Expected impact**: Saves one full edge traversal per deleteNode. For high-degree nodes, this halves delete cost.

**Risk**: Low. `removeVertex` already handles topology cleanup internally.

### J3: Direct Property Map Return (Avoid Defensive Copy)

**Problem**: If `getNodeProperties`/`getEdgeProperties` returns a defensive copy (`toMap()` or `toMutableMap()`), every property read allocates a new Map.

**Proposed change**: Return an unmodifiable view (`Collections.unmodifiableMap()`) of the internal map, eliminating copy overhead.

**Expected impact**: Eliminates O(properties) allocation on every property read. For entities with many properties, this is significant.

**Risk**: Medium. Callers must not retain references across mutations. This matches the pattern used by `LazyMergedMap` in `LayeredStorageImpl`.

### J4: Eclipse Collections for Edge Structural Maps

**Problem**: `edgeSrcMap: HashMap<Int, Int>`, `edgeDstMap: HashMap<Int, Int>` autobox all keys and values. For a graph with 300K edges, this creates ~1.2M Integer wrapper objects.

**Proposed change**: Use eclipse-collections `IntIntHashMap` for edge structural maps.

**Expected impact**: Eliminates autoboxing. Reduces memory ~3x for edge maps. Faster lookup due to primitive operations.

**Risk**: Low. Adds eclipse-collections dependency. Internal-only API change.

---

## Evaluated & Rejected

_None yet._

---

## Key Insights

1. **JGraphT property read is the fastest of all implementations.** 81.13M ops/sec — faster than NativeStorageImpl (55.80M). JGraphT returns the internal `MutableMap` reference directly, while NativeStorage performs a HashMap lookup + optional copy.

2. **JGraphT population is 4x slower than NativeStorage.** 251.7 ms vs 61.4 ms at 100K/300K. JGraphT's `DirectedPseudograph.addEdge()` maintains internal `DirectedEdgeContainer` per vertex (incoming/outgoing sets) with higher allocation overhead.

3. **ConcurStorage property read shows 8.5x slowdown.** 9.57M vs 81.13M. The `ReentrantReadWriteLock` overhead is disproportionately large for JGraphT because the underlying operation (direct HashMap reference return) is extremely fast (~12ns), making the lock acquisition cost (~20-50ns) a significant fraction.

4. **Edge query performance is symmetric.** Outgoing 8.97M ≈ Incoming 8.79M. JGraphT's `DirectedEdgeContainer` provides O(1) access for both directions, unlike NativeStorageImpl where incoming queries (49.94M) trail outgoing (116.33M) due to separate adjacency set lookups.

5. **JGraphT provides near-native HashMap performance for adjacency queries.** `incomingEdgesOf`/`outgoingEdgesOf` return internal Set references (O(1)), not copies. This makes JGraphT-backed storage competitive with NativeStorageImpl for edge queries.

6. **`removeVertex` automatically deletes all edges.** Unlike Neo4j which throws `ConstraintViolationException`, JGraphT's `removeVertex` handles cascade deletion internally. Exploiting this avoids redundant manual edge cleanup.

7. **In-memory only — no persistence overhead.** JGraphT-backed storage has no serialization, transaction, or disk I/O cost. The performance ceiling is JVM HashMap and JGraphT internal data structure speed, making it the fastest option for transient graphs.
