# Graph Module Design

## Design Overview

- **Classes**: `IGraph`, `AbcMultipleGraph`
- **Relationships**: `AbcMultipleGraph` implements `IGraph`, `IPoset`, and `Closeable`; `AbcMultipleGraph` contains two `IStorage` instances (one for graph, one for label poset)
- **Abstract**: `IGraph` (implemented by `AbcMultipleGraph`); `AbcMultipleGraph` (extended by `AbcSimpleGraph`)
- **Exceptions**: `EntityAlreadyExistException` raised on duplicate node/edge add; `EntityNotExistException` raised on edge add with missing src/dst; `AccessClosedStorageException` raised on storage access after close
- **Dependency roles**: Data holders: `NodeID`. Orchestrator: `AbcMultipleGraph` (coordinates entity factories, graph storage, and poset storage). Helpers: `IStorage` (injected via abstract property).

The graph layer translates domain-level graph operations into coordinated calls on `IStorage`. It does **not** own property storage, serialization, or backend lifecycle.

The graph layer maintains bidirectional `NodeID`-to-`Int` mapping and a nested edge index (`src -> tag -> dst -> storageId`), delegating to `IStorage` via auto-generated `Int` IDs.

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
    fun getNode(whoseID: NodeID): N?
    fun containNode(whoseID: NodeID): Boolean
    fun delNode(whoseID: NodeID)
    fun getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>
    fun addEdge(src: NodeID, dst: NodeID, tag: String): E
    fun getEdge(src: NodeID, dst: NodeID, tag: String): E?
    fun containEdge(src: NodeID, dst: NodeID, tag: String): Boolean
    fun delEdge(src: NodeID, dst: NodeID, tag: String)
    fun getAllEdges(doSatfy: (E) -> Boolean = { true }): Sequence<E>
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
| `addNode` | Creates a node and registers ID in storage | `withID`: NodeID | `N` | `EntityAlreadyExistException` if exists |
| `getNode` | Retrieves a node by ID | `whoseID`: NodeID | `N?` | -- |
| `delNode` | Removes all associated edges then the node | `whoseID`: NodeID | -- | -- |
| `addEdge` | Creates an edge with variant-specific pre-checks | `src`, `dst`: NodeID; `tag` | `E` | `EntityNotExistException` if src/dst missing |
| `getEdge` | Retrieves an edge by `(src, dst, tag)` | `src`, `dst`: NodeID; `tag` | `E?` | -- |
| `containEdge` | Checks if edge exists | `src`, `dst`: NodeID; `tag` | `Boolean` | -- |
| `delEdge` | Removes edge from storage | `src`, `dst`: NodeID; `tag` | -- | -- |
| `getAllNodes` / `getAllEdges` | Returns filtered sequence of entities | predicate | `Sequence<N>` / `Sequence<E>` | -- |
| `getChildren` / `getParents` | Adjacent nodes via outgoing/incoming edges | `of`: NodeID; `edgeCond` | `Sequence<N>` | -- |
| `getOutgoingEdges` / `getIncomingEdges` | Edges from/to a node | `of`: NodeID | `Sequence<E>` | -- |
| `getDescendants` / `getAncestors` | BFS traversal of all reachable nodes | `of`: NodeID; `edgeCond` | `Sequence<N>` | -- |

---

### AbcMultipleGraph

**Responsibility:** Canonical `IGraph` + `IPoset` + `Closeable` implementation. Allows multiple parallel edges between the same `(src, dst)` pair. Label poset state stored in a dedicated poset `IStorage` instance.

**State / Fields:**

```
AbcMultipleGraph
+-- abstract storage: IStorage          <- main graph storage
+-- abstract posetStorage: IStorage     <- label hierarchy storage
+-- newNodeObj(): N                     <- subclass entity factory (protected abstract)
+-- newEdgeObj(): E                     <- subclass entity factory (protected abstract)
```

**Close contract:** `close()` releases internal resources. Does not close storage instances.

**Label-filtered methods (concrete class, not inherited from IGraph):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, tag, label)` | Creates or reuses edge and assigns label | `src`, `dst`: NodeID; `tag`; `label`: Label | `E` | `EntityNotExistException` if src/dst missing |
| `delEdge(src, dst, tag, label)` | Removes label from edge; if no labels remain, deletes edge | `src`, `dst`: NodeID; `tag`; `label`: Label | -- | -- |
| `getOutgoingEdges(of, label, cond)` | Label-filtered outgoing edges | `of`: NodeID; `label`; `cond` | `Sequence<E>` | -- |
| `getIncomingEdges(of, label, cond)` | Label-filtered incoming edges | `of`: NodeID; `label`; `cond` | `Sequence<E>` | -- |
| `getChildren(of, label, cond)` | Adjacent nodes via label-filtered outgoing edges | `of`: NodeID; `label`; `cond` | `Sequence<N>` | -- |
| `getParents(of, label, cond)` | Adjacent nodes via label-filtered incoming edges | `of`: NodeID; `label`; `cond` | `Sequence<N>` | -- |
| `getDescendants(of, label, cond)` | BFS via label-filtered edges | `of`: NodeID; `label`; `cond` | `Sequence<N>` | -- |
| `getAncestors(of, label, cond)` | BFS via label-filtered edges | `of`: NodeID; `label`; `cond` | `Sequence<N>` | -- |

Edge visibility rule: see `docs/core/label.design.md`.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityAlreadyExistException` | Adding a node/edge that already exists |
| `EntityNotExistException` | Adding an edge whose src/dst node is missing |
| `AccessClosedStorageException` | Accessing storage after `close()` |

Deletion of a non-existent entity is a no-op at the graph level.

---

## Validation Rules

### AbcMultipleGraph

- Both src and dst nodes must exist before adding an edge
- Edge lookup by `(src, dst, tag)` uses nested edge index
