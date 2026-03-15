# Graph Module Design

## Design Overview

- **Classes**: `IGraph`, `IPoset`, `AbcMultipleGraph`, `AbcSimpleGraph`
- **Relationships**: `AbcMultipleGraph` implements `IGraph` and `IPoset`; `AbcSimpleGraph` extends `AbcMultipleGraph`; `AbcMultipleGraph` contains two `IStorage` instances: one for the main graph, one for the label poset
- **Abstract**: `IGraph` (implemented by `AbcMultipleGraph`); `AbcMultipleGraph` (extended by `AbcSimpleGraph`)
- **Exceptions**: `EntityAlreadyExistException` raised on duplicate node/edge add; `EntityNotExistException` raised on edge add with missing src/dst; `AccessClosedStorageException` raised on storage access after close
- **Dependency roles**: Data holders: `NodeID`, `InternalID`. Orchestrator: `AbcMultipleGraph` (coordinates entity factories, graph storage, and poset storage). Helpers: `IStorage` (inputs by constructor injection).

The graph layer translates domain-level graph operations into coordinated calls on `IStorage`. It does **not** own property storage, serialization, or backend lifecycle — those belong to `IStorage` and its implementations.

The graph layer is **storage-tier agnostic**: `AbcMultipleGraph` accepts any `IStorage` via constructor injection — flat (`NativeStorageImpl`, `MapDBStorageImpl`) or layered (`LayeredStorageImpl`). Layered storage composition is transparent to the graph layer; all query routing and freeze transitions are handled within the storage tier. See `docs/core/storage.design.md` for the full storage type hierarchy.

The graph layer **bridges domain identity to storage IDs**: `NodeID` -> `InternalID` via `nodeIdCache`, edge `(src, dst, type)` -> `InternalID` via adjacency scan + endpoint queries. The storage layer operates on opaque `Int` IDs only.

---

## Class / Type Specifications

### IGraph

**Responsibility:** Domain-facing contract defining node/edge CRUD and structural traversal. Edges are identified by their `(src: NodeID, dst: NodeID, type: String)` triple. Label-aware operations are provided by `AbcMultipleGraph` (not on this interface).

**State / Fields:**
- `nodeIDs: Set<NodeID>` — all node IDs in the graph

**Methods:**

```kotlin
interface IGraph<N : AbcNode, E : AbcEdge> {
    val nodeIDs: Set<NodeID>

    // Node CRUD
    fun addNode(withID: NodeID): N
    fun getNode(whoseID: NodeID): N?
    fun containNode(whoseID: NodeID): Boolean
    fun delNode(whoseID: NodeID)
    fun getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>

    // Edge CRUD (by tuple)
    fun addEdge(src: NodeID, dst: NodeID, type: String): E
    fun getEdge(src: NodeID, dst: NodeID, type: String): E?
    fun containEdge(src: NodeID, dst: NodeID, type: String): Boolean
    fun delEdge(src: NodeID, dst: NodeID, type: String)
    fun getAllEdges(doSatfy: (E) -> Boolean = { true }): Sequence<E>

    // Structure queries
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
| `addNode` | Creates a node and registers ID in storage | `withID`: NodeID | `N` — the created node | `EntityAlreadyExistException` if exists |
| `getNode` | Retrieves a node by ID | `whoseID`: NodeID | `N?` | — |
| `delNode` | Removes all associated edges then the node from storage | `whoseID`: NodeID | — | — |
| `addEdge` | Creates an edge with variant-specific pre-checks | `src`, `dst`: NodeID; `type`: edge type | `E` — the created edge | `EntityNotExistException` if src/dst missing |
| `getEdge` | Retrieves an edge by its `(src, dst, type)` tuple | `src`, `dst`: NodeID; `type`: edge type | `E?` | — |
| `containEdge` | Checks if edge exists by `(src, dst, type)` | `src`, `dst`: NodeID; `type`: edge type | `Boolean` | — |
| `delEdge` | Removes edge from storage | `src`, `dst`: NodeID; `type`: edge type | — | — |
| `getAllNodes` / `getAllEdges` | Returns filtered sequence of entities | predicate function | `Sequence<N>` / `Sequence<E>` | — |
| `getChildren` / `getParents` | Returns adjacent nodes via outgoing/incoming edges | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |
| `getOutgoingEdges` / `getIncomingEdges` | Returns edges from/to a node | `of`: NodeID | `Sequence<E>` | — |
| `getDescendants` / `getAncestors` | BFS traversal of all reachable nodes | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |

**Key design decisions:**

- Edge CRUD uses `(src, dst, type)` tuple — no separate `EdgeID` object. This keeps the domain API simple and avoids an additional identity type.
- `nodeIDs` is `Set<NodeID>` — derived from storage. No `edgeIDs` on the interface; edges are accessed via adjacency or `getAllEdges`.
- All return types use entity objects `N`/`E` — the entity layer provides typed property access.
- Traversal methods accept an optional edge predicate, allowing traversal scoped to edge types or properties.
- Label-filtered methods are on `AbcMultipleGraph` (concrete class), not on `IGraph`.

---

### AbcMultipleGraph

**Responsibility:** Canonical `IGraph` and `IPoset` implementation scaffold coordinating entity factories with dual storage. Implements `Closeable`. Allows multiple parallel edges between the same `(src, dst)` pair. Label poset state is stored in a dedicated poset `IStorage` instance.

**State / Fields:**

```
AbcMultipleGraph
├── abstract storage: IStorage          <- main graph storage, injected by subclass
├── abstract posetStorage: IStorage     <- label hierarchy storage, injected by subclass
├── nodeIdCache: HashMap<NodeID, InternalID>  <- user ID → storage ID
├── labelIdCache: HashMap<String, InternalID> <- label name → poset storage ID
├── nodeCache: HashMap<InternalID, SoftReference<N>>
├── edgeCache: HashMap<InternalID, SoftReference<E>>
├── newNodeObj(storageId): N            <- subclass entity factory (protected abstract)
└── newEdgeObj(storageId): E            <- subclass entity factory (protected abstract)
```

**ID resolution:**
- `nodeIdCache` maps `NodeID` -> `InternalID`, populated eagerly on first access by scanning all storage nodes and reading their `__id__` meta property.
- Edge lookup by `(src, dst, type)`: resolves both `NodeID`s to `InternalID`s, then scans outgoing edges from the source node using `storage.getEdgeSrc`/`getEdgeDst`/`getEdgeType` to find a match.

**Edge creation:**
`addEdge(src, dst, type)` resolves `NodeID`s, calls `storage.addEdge(srcSid, dstSid, type, metaProps)` where `metaProps` includes `__src__`, `__dst__`, `__tag__` for entity-layer access.

**Entity caching:**
`SoftReference`-based caches for nodes and edges. Allows GC to reclaim unused wrapper objects under memory pressure.

**Close behavior:**
`close()` clears all internal caches (`nodeIdCache`, `labelIdCache`, `nodeCache`, `edgeCache`). Does not close the storage instances — storage lifecycle is managed by the caller.

**Label-filtered methods (concrete class, not inherited from IGraph):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, type, label)` | Creates edge (or reuses existing) and assigns label | `src`, `dst`: NodeID; `type`: String; `label`: Label | `E` | `EntityNotExistException` if src/dst missing |
| `delEdge(src, dst, type, label)` | Removes label from edge; if no labels remain, deletes the edge | `src`, `dst`: NodeID; `type`: String; `label`: Label | — | — |
| `getOutgoingEdges(of, label, cond)` | Label-filtered outgoing edges | `of`: NodeID; `label`: Label; `cond`: edge predicate | `Sequence<E>` | — |
| `getIncomingEdges(of, label, cond)` | Label-filtered incoming edges | `of`: NodeID; `label`: Label; `cond`: edge predicate | `Sequence<E>` | — |
| `getChildren(of, label, cond)` | Adjacent nodes via label-filtered outgoing edges | `of`: NodeID; `label`: Label; `cond`: edge predicate | `Sequence<N>` | — |
| `getParents(of, label, cond)` | Adjacent nodes via label-filtered incoming edges | `of`: NodeID; `label`: Label; `cond`: edge predicate | `Sequence<N>` | — |
| `getDescendants(of, label, cond)` | BFS via label-filtered edges | `of`: NodeID; `label`: Label; `cond`: edge predicate | `Sequence<N>` | — |
| `getAncestors(of, label, cond)` | BFS via label-filtered edges | `of`: NodeID; `label`: Label; `cond`: edge predicate | `Sequence<N>` | — |

**Edge visibility rule:** An edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l || by > l`. Among all visitable labels, those covered by a higher visitable label are excluded. Non-label overloads see all edges.

---

### AbcSimpleGraph

**Responsibility:** Graph variant enforcing at most one edge per directed `(src, dst)` pair. Extends `AbcMultipleGraph`.

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, type)` | Checks that no edge exists between `(src, dst)` before delegating to storage | `src`, `dst`: NodeID; `type`: String | `E` | `EntityAlreadyExistException` if any edge between src->dst exists |
| `addEdge(src, dst, type, label)` | Checks direction uniqueness, then delegates to super | same + `label`: Label | `E` | `EntityAlreadyExistException` if direction conflict |

| Variant | Edge policy | Use case |
|---------|-------------|----------|
| `AbcSimpleGraph` | At most one edge per `(src, dst)` pair of any type | General relation graphs |
| `AbcMultipleGraph` | Multiple edges allowed between same `(src, dst)` pair | Multi-typed or multi-instance edges |

---

### Trait Ecosystem

Traits are optional capabilities layered on top of `IGraph` via interface mixin. They do **not** redefine identity or storage semantics — they extend the graph's vocabulary.

| Trait | Mixin style | Capability |
|-------|-------------|------------|
| `TraitNodeGroup` | Interface inheritance | Structured group-scoped `NodeID` generation |

**`TraitNodeGroup`** is a pure interface — `groupedNodesCounter` and four methods are the complete contract. The graph class implements it directly alongside `AbcSimpleGraph`/`AbcMultipleGraph`.

Label poset functionality is defined by `IPoset` and implemented in `AbcMultipleGraph` using a dedicated poset `IStorage` instance. See `docs/core/label.design.md` for details.

See `docs/core/group.design.md` for `TraitNodeGroup` details.

---

### Example Usage

**Flat storage (default):**

```kotlin
class MyNode(storage: IStorage, storageId: InternalID) : AbcNode(storage, storageId) {
    override val type = object : AbcNode.Type { override val name = "MyNode" }
}
class MyEdge(storage: IStorage, override val id: InternalID) : AbcEdge(storage) {
    override val type = object : AbcEdge.Type { override val name = "MyEdge" }
}

val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override val posetStorage = NativeStorageImpl()
    override fun newNodeObj(storageId: InternalID) = MyNode(storage, storageId)
    override fun newEdgeObj(storageId: InternalID) = MyEdge(storage, storageId)
}

val a = graph.addNode("a")
val b = graph.addNode("b")
val e = graph.addEdge("a", "b", "knows")

graph.getChildren("a").toList()         // [b]
graph.getDescendants("a").toList()      // all reachable from a
graph.delNode("a")                      // removes a and all its edges
```

**Layered storage (static analysis pipeline):**

```kotlin
val layeredStorage = LayeredStorageImpl()

val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage: IStorage = layeredStorage
    override val posetStorage = NativeStorageImpl()
    override fun newNodeObj(storageId: InternalID) = MyNode(storage, storageId)
    override fun newEdgeObj(storageId: InternalID) = MyEdge(storage, storageId)
}

// Phase 1: build AST
buildAST(sourceCode, graph)
layeredStorage.freeze()  // AST -> frozen, heap freed

// Phase 2: build CFG on top of frozen AST
buildCFG(graph)                     // reads AST (frozen), writes CFG (active)
layeredStorage.freeze()             // CFG -> frozen

// Phase 3: analysis — all graph data frozen, only analysis state in heap
analyze(graph)
```

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

### AbcSimpleGraph

- At most one edge per directed `(src, dst)` pair of any type; `addEdge` rejects duplicates with `EntityAlreadyExistException`

### AbcMultipleGraph

- Both src and dst nodes must exist before adding an edge
- `nodeIDs` derives from storage nodes; `nodeIdCache` provides O(1) resolution
- Edge lookup by `(src, dst, type)` scans adjacency list + endpoint queries
