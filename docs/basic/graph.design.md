# Graph Module Design

## Design Overview

- **Classes**: `IGraph`, `IPoset`, `AbcMultipleGraph`, `AbcSimpleGraph`
- **Relationships**: `AbcMultipleGraph` implements `IGraph` and `IPoset`; `AbcSimpleGraph` extends `AbcMultipleGraph`; `AbcMultipleGraph` contains two `IStorage` instances (one-way): one for the main graph, one for the label poset
- **Abstract**: `IGraph` (implemented by `AbcMultipleGraph`); `AbcMultipleGraph` (extended by `AbcSimpleGraph`)
- **Exceptions**: `EntityAlreadyExistException` raised on duplicate node/edge add; `EntityNotExistException` raised on edge add with missing src/dst; `AccessClosedStorageException` raised on storage access after close
- **Dependency roles**: Data holders: `NodeID`, `EdgeID`. Orchestrator: `AbcMultipleGraph` (coordinates entity factories, graph storage, and poset storage). Helpers: `IStorage` (inputs by constructor injection).

The graph layer translates domain-level graph operations into coordinated calls on `IStorage`. It does **not** own property storage, serialization, or backend lifecycle — those belong to `IStorage` and its implementations.

The graph layer is **storage-tier agnostic**: `AbcMultipleGraph` accepts any `IStorage` via constructor injection — flat (`NativeStorageImpl`, `MapDBStorageImpl`) or layered (`LayeredStorageImpl`). Layered storage composition is transparent to the graph layer; all query routing and freeze transitions are handled within the storage tier. See `docs/basic/storage.design.md` for the full storage type hierarchy.

The graph layer **converts domain IDs to storage primitives** at the boundary: `NodeID.name` -> `String` vertex ID, `EdgeID` -> `ArcKey`. The storage layer operates on `String` and `ArcKey` only.

---

## Class / Type Specifications

### IGraph

**Responsibility:** Domain-facing contract defining node/edge CRUD and structural traversal. Label-filtered overloads accept `Label` parameters; the label poset hierarchy itself is defined by `IPoset`.

**State / Fields:**
- `nodeIDs: Set<NodeID>` — all node IDs in the graph
- `edgeIDs: Set<EdgeID>` — all edge IDs in the graph

**Methods:**

```kotlin
interface IGraph<N : AbcNode, E : AbcEdge> {
    val nodeIDs: Set<NodeID>
    val edgeIDs: Set<EdgeID>

    // Node CRUD
    fun addNode(withID: NodeID): N
    fun getNode(whoseID: NodeID): N?
    fun containNode(whoseID: NodeID): Boolean
    fun delNode(whoseID: NodeID)
    fun getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>

    // Edge CRUD
    fun addEdge(withID: EdgeID): E
    fun addEdge(withID: EdgeID, label: Label): E
    fun getEdge(whoseID: EdgeID): E?
    fun containEdge(whoseID: EdgeID): Boolean
    fun delEdge(whoseID: EdgeID)
    fun delEdge(whoseID: EdgeID, label: Label)

    // Structure queries
    fun getAllEdges(doSatfy: (E) -> Boolean = { true }): Sequence<E>
    fun getIncomingEdges(of: NodeID): Sequence<E>
    fun getOutgoingEdges(of: NodeID): Sequence<E>
    fun getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>
    fun getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>
    fun getChildren(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
    fun getParents(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
    fun getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
    fun getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
    fun getDescendants(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
    fun getAncestors(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
    fun getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
    fun getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
}
```

**Edge visibility rule:** An edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l || by > l`. Non-label overloads default to `Label.SUPREMUM`, which sees all edges.

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addNode` | Creates a node and registers ID in storage | `withID`: NodeID | `N` — the created node | `EntityAlreadyExistException` if exists |
| `getNode` | Retrieves a node by ID | `whoseID`: NodeID | `N?` | — |
| `delNode` | Removes all associated edges then the node from storage | `whoseID`: NodeID | — | — |
| `addEdge` | Creates an edge with variant-specific pre-checks | `withID`: EdgeID | `E` — the created edge | `EntityAlreadyExistException` if exists; `EntityNotExistException` if src/dst missing |
| `addEdge` (label) | Creates edge and assigns label; if edge already exists, adds label to existing edge | `withID`: EdgeID; `label`: Label | `E` — the created or existing edge | `EntityNotExistException` if src/dst missing |
| `getEdge` | Retrieves an edge by ID | `whoseID`: EdgeID | `E?` | — |
| `delEdge` | Removes edge from storage | `whoseID`: EdgeID | — | — |
| `delEdge` (label) | Removes label from edge; if no labels remain, deletes the edge | `whoseID`: EdgeID; `label`: Label | — | — |
| `getAllNodes` / `getAllEdges` | Returns filtered sequence of entities | predicate function | `Sequence<N>` / `Sequence<E>` | — |
| `getChildren` / `getParents` | Returns adjacent nodes via outgoing/incoming edges | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |
| `getChildren` / `getParents` (label) | Label-filtered versions returning adjacent nodes whose connecting edge is visible under the given label | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<N>` | — |
| `getOutgoingEdges` / `getIncomingEdges` | Returns edges from/to a node | `of`: NodeID | `Sequence<E>` | — |
| `getOutgoingEdges` / `getIncomingEdges` (label) | Label-filtered versions returning edges visible under the given label | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<E>` | — |
| `getDescendants` | BFS traversal of all reachable nodes via outgoing edges | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |
| `getAncestors` | BFS traversal of all reachable nodes via incoming edges | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |
| `getDescendants` / `getAncestors` (label) | Label-filtered BFS traversal of reachable nodes | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<N>` | — |

**Key design decisions:**

- All input parameters take `NodeID`/`EdgeID` — callers always own identity construction.
- All return types use entity objects `N`/`E` — the entity layer provides typed property access.
- `nodeIDs`/`edgeIDs` are `Set`, reflecting the ID-first model; uniqueness is guaranteed by ID semantics.
- Traversal methods accept an optional edge predicate, allowing traversal scoped to edge types or properties.
- `getAllNodes`/`getAllEdges` accept a node/edge predicate, providing in-sequence filtering without materializing a full collection.
- Label poset hierarchy is defined by `IPoset` (see `docs/basic/label.design.md`); `IGraph` only consumes labels via label-filtered query overloads.

---

### AbcMultipleGraph

**Responsibility:** Canonical `IGraph` and `IPoset` implementation scaffold coordinating entity factories with dual storage. Implements `Closeable`. Allows multiple parallel edges between the same `(src, dst)` pair. Label poset state is stored in a dedicated poset `IStorage` instance.

**State / Fields:**

```
AbcMultipleGraph
├── abstract graphStore: IStorage      <- main graph storage, injected by subclass
├── abstract posetStore: IStorage      <- label hierarchy storage, injected by subclass
├── override nodeIDs: Set<NodeID>      <- derives from graphStore.vertices
├── override edgeIDs: Set<EdgeID>      <- derives from graphStore.arcs
├── newNodeObj(nid): N                 <- subclass entity factory (protected abstract)
└── newEdgeObj(eid): E                 <- subclass entity factory (protected abstract)
```

**Storage delegation:**
`nodeIDs` derives from `graphStore.vertices` by wrapping each `String` as `NodeID`. `edgeIDs` derives from `graphStore.arcs` by wrapping each `ArcKey` as `EdgeID`. There is no separate in-memory ID cache.

**ID conversion at boundary:**
All graph operations convert domain IDs to storage primitives: `NodeID.name` -> `String`, `EdgeID` -> `ArcKey(srcNid.name, dstNid.name, eType)`. Reverse conversion uses `NodeID(string)` and `EdgeID.of(arcKey)`.

**Traversal implementation:**
Both `getDescendants` and `getAncestors` use BFS (`ArrayDeque` + `removeFirst`). They delegate adjacency lookup to `getOutgoingEdges`/`getIncomingEdges`, which in turn call `graphStore.getOutgoingArcs`/`getIncomingArcs`.

**Close behavior:**
`close()` clears internal wrapper-object caches (`nodeCache`, `edgeCache`). Poset state is in the poset store and requires no explicit persist step.

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge` | Only checks edge ID uniqueness before delegating to storage | `withID`: EdgeID | `E` | `EntityAlreadyExistException` if same EdgeID exists |

---

### AbcSimpleGraph

**Responsibility:** Graph variant enforcing at most one edge per directed `(src, dst)` pair. Extends `AbcMultipleGraph`.

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge` | Checks that no edge exists between `(src, dst)` before delegating to storage | `withID`: EdgeID | `E` | `EntityAlreadyExistException` if any edge between src->dst exists |

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

Label poset functionality is defined by `IPoset` and implemented in `AbcMultipleGraph` using a dedicated poset `IStorage` instance. See `docs/basic/label.design.md` for details.

See `docs/basic/group.design.md` for `TraitNodeGroup` details.

---

### Example Usage

**Flat storage (default):**

```kotlin
class MyNode(store: IStorage, override val id: NodeID) : AbcNode(store) {
    override val type = object : AbcNode.Type { override val name = "MyNode" }
}
class MyEdge(store: IStorage, override val id: EdgeID) : AbcEdge(store) {
    override val type = object : AbcEdge.Type { override val name = "MyEdge" }
}

val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val graphStore = NativeStorageImpl()
    override val posetStore = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(graphStore, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(graphStore, eid)
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
val layeredStorage = LayeredStorageImpl()

val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val graphStore: IStorage = layeredStorage
    override val posetStore = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(graphStore, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(graphStore, eid)
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

Deletion of a non-existent entity is a no-op at the graph level (delegates to storage which may throw or silently succeed depending on implementation).

---

## Validation Rules

### AbcSimpleGraph

- At most one edge per directed `(src, dst)` pair of any type; `addEdge` rejects duplicates with `EntityAlreadyExistException`

### AbcMultipleGraph

- Edge ID must be unique; multiple edges between same `(src, dst)` pair are allowed as long as EdgeIDs differ
- Both src and dst nodes must exist before adding an edge
- Node/edge IDs must be unique across the graph
- `nodeIDs`/`edgeIDs` derive from storage vertices/arcs; no separate cache synchronization needed
