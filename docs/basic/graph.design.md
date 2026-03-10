# Graph Module Design

## Design Overview

- **Classes**: `IGraph`, `AbcBasicGraph`, `AbcSimpleGraph`, `AbcMultiGraph`
- **Relationships**: `AbcBasicGraph` implements `IGraph`; `AbcSimpleGraph` extends `AbcBasicGraph`; `AbcMultiGraph` extends `AbcBasicGraph`; `AbcBasicGraph` contains `IStorage` (one-way)
- **Abstract**: `IGraph` (implemented by `AbcBasicGraph`); `AbcBasicGraph` (extended by `AbcSimpleGraph`, `AbcMultiGraph`)
- **Exceptions**: `EntityAlreadyExistException` raised on duplicate node/edge add; `EntityNotExistException` raised on edge add with missing src/dst; `AccessClosedStorageException` raised on storage access after close
- **Dependency roles**: Data holders: `NodeID`, `EdgeID`. Orchestrator: `AbcBasicGraph` (coordinates entity factories and storage). Helpers: `IStorage` (inputs by constructor injection).

The graph layer translates domain-level graph operations into coordinated calls on `IStorage`. It does **not** own property storage, serialization, or backend lifecycle — those belong to `IStorage` and its implementations.

The graph layer is **storage-tier agnostic**: `AbcBasicGraph` accepts any `IStorage` via constructor injection — flat (`NativeStorageImpl`, `MapDBStorageImpl`), delta (`DeltaStorageImpl`), or phased (`PhasedStorageImpl`). Layered storage composition is transparent to the graph layer; all query routing and freeze transitions are handled within the storage tier. See `docs/basic/storage.design.md` for the full storage type hierarchy.

---

## Class / Type Specifications

### IGraph

**Responsibility:** Domain-facing contract defining node/edge CRUD and structural traversal.

**State / Fields:**
- `nodeIDs: Set<NodeID>` — all node IDs in the graph
- `edgeIDs: Set<EdgeID>` — all edge IDs in the graph

**Methods:**

```kotlin
interface IGraph<N : AbcNode, E : AbcEdge> {
    val nodeIDs: Set<NodeID>
    val edgeIDs: Set<EdgeID>

    fun addNode(withID: NodeID): N
    fun getNode(whoseID: NodeID): N?
    fun containNode(whoseID: NodeID): Boolean
    fun delNode(whoseID: NodeID)
    fun getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>

    fun addEdge(withID: EdgeID): E
    fun getEdge(whoseID: EdgeID): E?
    fun containEdge(whoseID: EdgeID): Boolean
    fun delEdge(whoseID: EdgeID)
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
| `addNode` | Creates a node and registers ID in cache and storage | `withID`: NodeID | `N` — the created node | `EntityAlreadyExistException` if exists |
| `getNode` | Retrieves a node by ID (must exist in both cache and storage) | `whoseID`: NodeID | `N?` | — |
| `delNode` | Removes all associated edges then the node from cache and storage | `whoseID`: NodeID | — | — |
| `addEdge` | Creates an edge with variant-specific pre-checks | `withID`: EdgeID | `E` — the created edge | `EntityAlreadyExistException` if exists; `EntityNotExistException` if src/dst missing |
| `getEdge` | Retrieves an edge by ID | `whoseID`: EdgeID | `E?` | — |
| `delEdge` | Removes edge from cache and storage | `whoseID`: EdgeID | — | — |
| `getAllNodes` / `getAllEdges` | Returns filtered sequence of entities | predicate function | `Sequence<N>` / `Sequence<E>` | — |
| `getChildren` / `getParents` | Returns adjacent nodes via outgoing/incoming edges | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |
| `getDescendants` | BFS traversal of all reachable nodes via outgoing edges | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |
| `getAncestors` | DFS traversal of all reachable nodes via incoming edges | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |

**Key design decisions:**

- All input parameters take `NodeID`/`EdgeID` — callers always own identity construction.
- All return types use entity objects `N`/`E` — the entity layer provides typed property access.
- `nodeIDs`/`edgeIDs` are `Set`, reflecting the ID-first model; uniqueness is guaranteed by ID semantics.
- Traversal methods accept an optional edge predicate, allowing traversal scoped to edge types or properties.
- `getAllNodes`/`getAllEdges` accept a node/edge predicate, providing in-sequence filtering without materializing a full collection.

---

### AbcBasicGraph

**Responsibility:** Canonical `IGraph` implementation scaffold coordinating entity factories with storage.

**State / Fields:**

```
AbcBasicGraph
├── abstract storage: IStorage        ← injected by subclass
├── override nodeIDs: MutableSet<NodeID>   ← in-memory cache
├── override edgeIDs: MutableSet<EdgeID>   ← in-memory cache
├── abstract newNodeObj(nid): N        ← entity factory for node
└── abstract newEdgeObj(eid): E        ← entity factory for edge
```

**Cache contract:**
`nodeIDs`/`edgeIDs` are mutable sets maintained in sync with storage. On `addNode`, the ID is added to both cache and storage. On `delNode`, all associated edges are removed from cache and storage before removing the node. `getNode` requires the ID to be present in **both** cache and storage — if they diverge (e.g., post-restart), the node is not returned.

**Entity factory pattern:**
Subclasses implement `newNodeObj`/`newEdgeObj` to bind the concrete entity type to the correct storage reference. This avoids generic type erasure problems at the graph boundary.

**Traversal implementation:**
`getDescendants` uses BFS; `getAncestors` uses DFS. Both delegate adjacency lookup to `getOutgoingEdges`/`getIncomingEdges`, which in turn call `storage.getOutgoingEdges`/`getIncomingEdges`.

---

### AbcSimpleGraph

**Responsibility:** Graph variant enforcing at most one edge per directed `(src, dst)` pair.

**State / Fields:**
- `graphName: String` — used by the trait system (e.g., `TraitNodeGroup`) as the namespace prefix for grouped node IDs

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge` | Checks that no edge exists between `(src, dst)` before delegating to storage | `withID`: EdgeID | `E` | `EntityAlreadyExistException` if any edge between src→dst exists |

---

### AbcMultiGraph

**Responsibility:** Graph variant allowing multiple parallel edges between the same `(src, dst)` pair.

**State / Fields:**
- `graphName: String` — same as `AbcSimpleGraph`

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge` | Only checks edge ID uniqueness before delegating to storage | `withID`: EdgeID | `E` | `EntityAlreadyExistException` if same EdgeID exists |

| Variant | Edge policy | Use case |
|---------|-------------|----------|
| `AbcSimpleGraph` | At most one edge per `(src, dst)` pair of any type | General relation graphs |
| `AbcMultiGraph` | Multiple edges allowed between same `(src, dst)` pair | Multi-typed or multi-instance edges |

---

### Trait Ecosystem

Traits are optional capabilities layered on top of `IGraph` via interface mixin. They do **not** redefine identity or storage semantics — they extend the graph's vocabulary.

| Trait | Mixin style | Capability |
|-------|-------------|------------|
| `TraitNodeGroup` | Interface inheritance | Structured group-scoped `NodeID` generation |
| `TraitLabelLattice` | Interface inheritance + composition | Label-based edge visibility with a partial-order lattice |

**`TraitNodeGroup`** is a pure interface — `groupedNodesCounter` and four methods are the complete contract. The graph class implements it directly alongside `AbcSimpleGraph`/`AbcMultiGraph`.

**`TraitLabelLattice`** requires state (the partial-order structure), so it uses a **composition pattern**: the abstract base class `AbcLabelGraph` holds a `latticeImpl: AbcBasicLabelLattice` instance and manually delegates all member-extension properties to it. `TraitLabelLattice` then provides default implementations for label-filtered graph operations (`addEdge`, `getChildren`, `getDescendants`, etc.) that call `this` as both `IGraph` and `ILabelLattice`.

See `docs/traits/group.design.md` and `docs/traits/label.design.md` for details.

---

### Example Usage

**Flat storage (default):**

```kotlin
class MyNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
    override val type = object : AbcNode.Type { override val name = "MyNode" }
}
class MyEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
    override val type = object : AbcEdge.Type { override val name = "MyEdge" }
}

val graph = object : AbcSimpleGraph<MyNode, MyEdge>("myGraph") {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

val a = graph.addNode(NodeID("a"))
val b = graph.addNode(NodeID("b"))
val e = graph.addEdge(EdgeID(a.id, b.id, "knows"))

graph.getChildren(a.id).toList()         // [b]
graph.getDescendants(a.id).toList()      // all reachable from a
graph.delNode(a.id)                      // removes a and all its edges
```

**Layered storage (static analysis pipeline):**

```kotlin
// Same graph types — only the storage injection changes
val phasedStorage = PhasedStorageImpl()

val graph = object : AbcMultiGraph<MyNode, MyEdge>("analysisGraph") {
    override val storage: IStorage = phasedStorage
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

// Phase 1: build AST
buildAST(sourceCode, graph)
phasedStorage.freezeAndPushLayer()  // AST → off-heap, heap freed

// Phase 2: build CFG on top of frozen AST
buildCFG(graph)                     // reads AST (frozen), writes CFG (active)
phasedStorage.freezeAndPushLayer()  // CFG → off-heap

// Phase 3: analysis — all graph data off-heap, only analysis state in heap
analyze(graph)
```

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityAlreadyExistException` | Adding a node/edge that already exists |
| `EntityNotExistException` | Adding an edge whose src/dst node is missing |
| `AccessClosedStorageException` | Accessing storage after `close()` |

Deletion of a non-existent entity is a no-op at the graph level (delegates to storage which may throw or silently succeed depending on implementation).

---

## Validation Rules

### AbcSimpleGraph

- At most one edge per directed `(src, dst)` pair of any type; `addEdge` rejects duplicates with `EntityAlreadyExistException`

### AbcMultiGraph

- Edge ID must be unique; multiple edges between same `(src, dst)` pair are allowed as long as EdgeIDs differ

### AbcBasicGraph

- Both src and dst nodes must exist before adding an edge
- Node/edge IDs must be unique across the graph
- `nodeIDs`/`edgeIDs` cache must stay in sync with storage; `getNode` requires ID present in both cache and storage
