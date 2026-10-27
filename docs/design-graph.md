# Graph Design

## Design Overview

- **Classes**: `IGraph`, `AbcMultipleGraph`, `AbcSimpleGraph`, `GraphMergeLaws`, `PropertyMergeLaw`, `WriterChange`, `EdgeKey`
- **Relationships**: `AbcMultipleGraph` implements `IGraph`, `GraphMergeLaws` and `Flushable`; `AbcSimpleGraph` extends `AbcMultipleGraph`
- **Abstract**: `IGraph` (implemented by `AbcMultipleGraph`); `AbcMultipleGraph` (extended by `AbcSimpleGraph`)
- **Exceptions**: `EntityAlreadyExistException` raised on duplicate node/edge add; `EntityNotExistException` raised on edge add with missing src/dst; `MergeConflictException` raised by a merge law; all extend `GraphException`
- **Dependency roles**: Data holders: `NodeID`. Orchestrator: `AbcMultipleGraph` (coordinates entity factories and graph storage). Helpers: `IStorage` (injected via abstract property); `GraphEntityCache` (internal — NodeID↔Int index and soft-referenced entity wrappers).

The graph layer translates domain-level graph operations into coordinated calls on `IStorage`. It does **not** own property storage, serialization, or backend lifecycle.

`AbcMultipleGraph` maintains bidirectional `NodeID`-to-`Int` mapping, delegating to `IStorage` via auto-generated `Int` IDs. See `spec-graph.md` for edge lookup and traversal algorithms.

---

## Class / Type Specifications

### IGraph

**Responsibility:** Domain-facing contract defining node/edge CRUD and structural traversal. Edges are identified by their `(src: NodeID, dst: NodeID, tag: String)` triple.

**State / Fields:**
- `nodeIDs: Set<NodeID>` -- all node IDs in the graph

**Methods:**

```kotlin
interface IGraph<N : AbcNode, E : AbcEdge> {
    val nodeIDs: Set<NodeID>
    fun addNode(withID: NodeID): N
    fun claimNode(from: AbcNode): N
    fun getNode(whoseID: NodeID): N?
    fun containNode(whoseID: NodeID): Boolean
    fun delNode(whoseID: NodeID)
    fun getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>
    fun addEdge(src: NodeID, dst: NodeID, tag: String): E
    fun getEdge(src: NodeID, dst: NodeID, tag: String): E?
    fun containEdge(src: NodeID, dst: NodeID, tag: String): Boolean
    fun delEdge(src: NodeID, dst: NodeID, tag: String)
    fun getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>
    fun getIncomingEdges(of: NodeID): Sequence<E>
    fun getOutgoingEdges(of: NodeID): Sequence<E>
    fun getChildren(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
    fun getParents(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
    fun getDescendants(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
    fun getAncestors(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addNode` | Creates a new storage row marked as owned by this graph and registers the node in this graph | `withID`: NodeID | `N` | `EntityAlreadyExistException` if exists |
| `claimNode` | Registers an existing storage row in this graph, marking it as owned by this graph, without creating a new row. Returns the existing node if already claimed. | `from`: AbcNode | `N` | `EntityNotExistException` if the row is absent from storage |
| `getNode` | Retrieves a node by ID | `whoseID`: NodeID | `N?` | -- |
| `delNode` | Removes all associated edges then the node | `whoseID`: NodeID | -- | -- |
| `addEdge` | Creates an edge with variant-specific pre-checks | `src`, `dst`: NodeID; `tag` | `E` | `EntityNotExistException` if src/dst missing |
| `getEdge` | Retrieves an edge by `(src, dst, tag)` | `src`, `dst`: NodeID; `tag` | `E?` | -- |
| `containEdge` | Checks if edge exists | `src`, `dst`: NodeID; `tag` | `Boolean` | -- |
| `delEdge` | Removes edge from storage | `src`, `dst`: NodeID; `tag` | -- | -- |
| `getAllNodes` / `getAllEdges` | Returns filtered sequence of entities | predicate | `Sequence<N>` / `Sequence<E>` | -- |
| `getChildren` / `getParents` | Adjacent nodes via outgoing/incoming edges | `of`: NodeID; `edgeCond` | `Sequence<N>` | -- |
| `getOutgoingEdges` / `getIncomingEdges` | Edges from/to a node | `of`: NodeID | `Sequence<E>` | -- |
| `getDescendants` / `getAncestors` | BFS traversal of all reachable nodes (typed edge queries: [spec-graph.md](spec-graph.md#bfs-traversal-descendants--ancestors)) | `of`: NodeID; `edgeCond` | `Sequence<N>` | -- |

---

### AbcMultipleGraph

**Responsibility:** Canonical `IGraph` + `Flushable` implementation. Maintains bidirectional `NodeID`-to-`Int` mapping, delegating to `IStorage` via auto-generated `Int` IDs. Allows multiple parallel edges between the same `(src, dst)` pair. Label-filtered operations provided by `PosetTrait` when mixed in by concrete graph classes.

**State / Fields:**

```
AbcMultipleGraph
+-- abstract storage: IStorage              <- main graph storage
+-- abstract graphId: String                <- graph identifier for ownership tracking
+-- newNodeObj(): N                         <- subclass entity factory (protected abstract)
+-- newEdgeObj(): E                         <- subclass entity factory (protected abstract)
+-- cache: GraphEntityCache<N, E>           <- NodeID↔Int index + entity wrappers (private, created on first use)
```

**Shared Storage:**

Multiple graph instances may share a single `IStorage`. See `model.md` for shared-storage semantics and ownership invariants.

**Node Operations:**

| Method | Behavior |
|--------|----------|
| `addNode(withID)` | Creates a new storage row via `storage.addNode()` with this graph's ownership mark. Registers in this graph. |
| `claimNode(from)` | Registers an existing storage row (via `from.storageId`) in this graph and adds this graph to its owner set. No new storage row created. Returns existing node if already claimed. |

**Concurrency:**

Every public operation runs inside `storage.criticalSection` (`design-storage.md`), so a compound operation is atomic against other callers sharing the storage. Sequence-returning queries materialize their result inside the critical section.

**Revert Coherence:**

The cache is created on the first graph operation, when `storage` is initialized. When `storage` is an `IRevertibleStorage`, the graph subscribes a `RevertListener` at creation. The listener first reconciles the cache: it evicts the removed node IDs from the `NodeID`↔`Int` index and the removed edge IDs from the wrapper cache, and for each restored node ID it applies the test `rebuild()` applies to a row (the owner set holds the graph's ID and the row has a node ID property), registering or evicting the node accordingly. It then calls `onRestoredEdges(ids)` once with the restored edges and the edges incident to the newly registered nodes (`spec-graph.md` Revert). The default does nothing; a graph that derives state from edge properties overrides it, and `edgeByStorageId` resolves an ID to the graph's edge when both endpoints are registered. A graph with `PosetTrait` overrides it as `restoreLabelIndex(ids, ::edgeByStorageId)`.

**Edge Isolation:**

All edge query methods filter by this graph's node registry membership. See `model.md` for the edge isolation invariant.

**Ownership Persistence:**

`addNode` writes the `__owners__` property, the set holding this graph's ID, in the same storage call that creates the row. `claimNode` adds this graph's ID to the row's owner set when it is absent. `rebuild()` restores the nodes whose owner set holds this graph's ID; a row with no owner set belongs to no graph. See `spec-graph.md` for the mark/rebuild algorithm.

---

### AbcSimpleGraph

**Responsibility:** Graph variant enforcing at most one edge per directed `(src, dst)` pair. Extends `AbcMultipleGraph`. All node CRUD, label, and traversal operations are inherited.

**Overridden Methods:**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, tag)` | Rejects if any edge from `src` to `dst` already exists, then delegates to super; check and insert run in one critical section | `src`, `dst`: NodeID; `tag`: String | `E` | `EntityAlreadyExistException` if any edge from src to dst exists |
| `edgeUniqueKeys(edge)` | Declares the `(src, dst)` pair as a unique key, so `coalesceEdges` rejects two distinct edges on one pair (`model.md` Merge Laws) | `edge`: EdgeKey | `Map<String, List<String>>` | -- |

---

### GraphMergeLaws

**Responsibility:** The pure merge laws a graph declares for the prospective changes of several writers; `AbcMultipleGraph` implements it. The laws and their rules: `model.md` Merge Laws; the steps: `spec-graph-merge.md`. Every member reads no storage, registers nothing and hooks no graph or storage write; it runs only when a caller invokes it.

| Member | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `nodePropertyLaw(name)` | Node ID property: `PropertyMergeLaw.IMMUTABLE`; owner set: union; any other name: `PropertyMergeLaw.EQUAL_OR_FAIL` | `name` | `PropertyMergeLaw` | -- |
| `edgePropertyLaw(name)` | The labels property: the label law of `PosetTrait.mergeLabels` over stored lists; any other name: `EQUAL_OR_FAIL` | `name` | `PropertyMergeLaw` | -- |
| `edgeUniqueKeys(edge)` | Declared unique keys of `edge` besides its triple, name to key components; default none. A variant overrides it | `edge`: EdgeKey | `Map<String, List<String>>` | -- |
| `coalesceEdges(additions, present)` | Coalesces additions by triple, then checks every declared key of the coalesced edges against each other and against `present` | `additions`, `present`: Collection<EdgeKey> | `Set<EdgeKey>` | `MergeConflictException` |

A variant declares its own property law by overriding `nodePropertyLaw` or `edgePropertyLaw` and delegating other names to `super`.

### PropertyMergeLaw and WriterChange

- `WriterChange<T>(before, after)` is one writer's change; `PropertyChange` is `WriterChange<IValue?>`, where null is an absent key.
- `PropertyMergeLaw.merge(base, changes): IValue?` is the pure law of one property. The companion holds `EQUAL_OR_FAIL`, the default, and `IMMUTABLE`. A declared law must not depend on the order of `changes`.
- `MergeConflictException(detail)` extends `GraphException`; a conflict fails the whole set, so the caller applies none of it.

### EdgeKey

`EdgeKey(src, dst, tag)` is the graph-level identity of an edge; `AbcEdge.key` returns it for a bound edge. `NodeOwners` holds the owner law (`NodeOwners.LAW`) beside the owner marks it reads and writes.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityAlreadyExistException` | Adding a node/edge that already exists; adding a second edge between the same `(src, dst)` pair in `AbcSimpleGraph` |
| `EntityNotExistException` | Adding an edge whose src/dst node is missing; claiming a node whose storage row is absent |
| `MergeConflictException` | A merge law meets opposing changes, or `coalesceEdges` meets two edges sharing a declared unique key |

Deletion of a non-existent entity is a no-op at the graph level.
