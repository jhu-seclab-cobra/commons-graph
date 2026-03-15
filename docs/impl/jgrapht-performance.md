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

_No benchmark data collected yet. Run the performance test to populate._

---

## Optimization Candidates

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

1. **JGraphT provides near-native HashMap performance for adjacency queries.** `incomingEdgesOf`/`outgoingEdgesOf` return internal Set references (O(1)), not copies. This makes JGraphT-backed storage competitive with NativeStorageImpl for edge queries.

2. **Dual structure (JGraphT graph + property HashMap) requires strict synchronization.** A bug where `containsNode` checks only `nodeProperties` but not `jgtGraph` would be undetectable. All mutations must update both structures atomically.

3. **JGraphT vertex/edge type affects performance.** Using `Int` as both vertex and edge type in `DirectedPseudograph<Int, Int>` avoids object allocation overhead compared to domain objects (`NodeID`/`EdgeID`). JGraphT stores vertices/edges in `LinkedHashMap` internally.

4. **`removeVertex` automatically deletes all edges.** Unlike Neo4j which throws `ConstraintViolationException`, JGraphT's `removeVertex` handles cascade deletion internally. Exploiting this avoids redundant manual edge cleanup.

5. **In-memory only — no persistence overhead.** JGraphT-backed storage has no serialization, transaction, or disk I/O cost. The performance ceiling is JVM HashMap and JGraphT internal data structure speed, making it the fastest option for transient graphs.
