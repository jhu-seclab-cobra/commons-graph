# Graph Design

## Design Overview

- **Classes**: `IGraph`, `AbcMultipleGraph`, `AbcSimpleGraph`
- **Relationships**: `AbcMultipleGraph` implements `IGraph` and `Flushable`; `AbcSimpleGraph` extends `AbcMultipleGraph`
- **Abstract**: `IGraph` (implemented by `AbcMultipleGraph`); `AbcMultipleGraph` (extended by `AbcSimpleGraph`)
- **Exceptions**: `EntityAlreadyExistException` raised on duplicate node/edge add; `EntityNotExistException` raised on edge add with missing src/dst; `AccessClosedStorageException` raised on storage access after close
- **Dependency roles**: Data holders: `NodeID`. Orchestrator: `AbcMultipleGraph` (coordinates entity factories and graph storage). Helpers: `IStorage` (injected via abstract property).

The graph layer translates domain-level graph operations into coordinated calls on `IStorage`. It does **not** own property storage, serialization, or backend lifecycle.

`AbcMultipleGraph` maintains bidirectional `NodeID`-to-`Int` mapping, delegating to `IStorage` via auto-generated `Int` IDs. Edge lookups by `(src, dst, tag)` scan the source node's adjacency list in storage -- O(out-degree) per query.

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
| `addNode` | Creates a new storage row and registers the node in this graph | `withID`: NodeID | `N` | `EntityAlreadyExistException` if exists |
| `claimNode` | Registers an existing storage row in this graph without creating a new row. Returns the existing node if already claimed. | `from`: AbcNode | `N` | -- |
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

**Responsibility:** Canonical `IGraph` + `Flushable` implementation. Maintains bidirectional `NodeID`-to-`Int` mapping, delegating to `IStorage` via auto-generated `Int` IDs. Allows multiple parallel edges between the same `(src, dst)` pair. Label-filtered operations provided by `TraitPoset` when mixed in by concrete graph classes.

**State / Fields:**

```
AbcMultipleGraph
+-- abstract storage: IStorage              <- main graph storage
+-- abstract graphId: String                <- graph identifier for ownership tracking
+-- newNodeObj(): N                         <- subclass entity factory (protected abstract)
+-- newEdgeObj(): E                         <- subclass entity factory (protected abstract)
+-- nodeEntries: Map<NodeID, NodeEntry>     <- NodeID → storageId mapping (private)
+-- nodeByStorageId: Map<Int, NodeEntry>    <- storageId → NodeEntry mapping (private)
```

**Shared Storage:**

Multiple graph instances may share a single `IStorage`. Each graph maintains its own `nodeEntries` and `nodeByStorageId`. A node in storage can be registered by multiple graphs via `claimNode` — each graph holds its own typed view (`N`) of the same storage row, sharing all properties.

**Node Operations:**

| Method | Behavior |
|--------|----------|
| `addNode(withID)` | Creates a new storage row via `storage.addNode()`. Registers in this graph. |
| `claimNode(from)` | Registers an existing storage row (via `from.storageId`) in this graph. No new storage row created. Returns existing node if already claimed. |

**Edge Isolation:**

All edge query methods (`getAllEdges`, `getOutgoingEdges`, `getIncomingEdges`, `getAncestors`, `getDescendants`) filter by `nodeByStorageId` membership — only edges whose both endpoints are registered in this graph are returned. Edges belonging to other graphs sharing the same storage are excluded.

**Ownership Persistence (`__owners__`):**

Node ownership is persisted lazily via a `__owners__` SetVal property on each storage row.

- `flush()` writes `graphId` into `__owners__` for every node in `nodeByStorageId`. Zero runtime overhead — ownership only persisted on explicit flush.
- `rebuild()` restores nodes where `graphId` is in `__owners__`. When `__owners__` is absent (first run), falls back to restoring all nodes.

**Flush contract:** `flush()` persists node ownership to storage. Does not release caches or close storage. Graph remains usable after flush.

---

### AbcSimpleGraph

**Responsibility:** Graph variant enforcing at most one edge per directed `(src, dst)` pair. Extends `AbcMultipleGraph`. All node CRUD, label, and traversal operations are inherited.

**Overridden Methods:**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, tag)` | Rejects if any edge from `src` to `dst` already exists, then delegates to super | `src`, `dst`: NodeID; `tag`: String | `E` | `EntityAlreadyExistException` if any edge from src to dst exists |
| `addEdge(src, dst, tag, label)` | Rejects if an edge exists between `(src, dst)` with a different tag; otherwise delegates to super | `src`, `dst`: NodeID; `tag`: String; `label`: Label | `E` | `EntityAlreadyExistException` if direction conflict |

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityAlreadyExistException` | Adding a node/edge that already exists; adding a second edge between the same `(src, dst)` pair in `AbcSimpleGraph` |
| `EntityNotExistException` | Adding an edge whose src/dst node is missing |
| `AccessClosedStorageException` | Accessing storage after `close()` |

Deletion of a non-existent entity is a no-op at the graph level.

---

## Validation Rules

### AbcMultipleGraph

- Both src and dst nodes must exist before adding an edge
- Edge lookups by `(src, dst, tag)` scan the source node's adjacency list in storage -- O(out-degree) per query

### AbcSimpleGraph

- At most one edge per directed `(src, dst)` pair of any type; `addEdge` rejects duplicates with `EntityAlreadyExistException`
