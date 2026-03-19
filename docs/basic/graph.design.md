# Graph Module Design

## Design Overview

- **Classes**: `IGraph`, `IPoset`, `AbcMultipleGraph`, `AbcSimpleGraph`
- **Relationships**: `AbcMultipleGraph` implements `IGraph` and `IPoset`; `AbcSimpleGraph` extends `AbcMultipleGraph`; `AbcMultipleGraph` contains two `IStorage` instances: one for the main graph, one for the label poset
- **Abstract**: `IGraph` (implemented by `AbcMultipleGraph`); `AbcMultipleGraph` (extended by `AbcSimpleGraph`)
- **Exceptions**: `EntityAlreadyExistException` raised on duplicate node/edge add; `EntityNotExistException` raised on edge add with missing src/dst; `AccessClosedStorageException` raised on storage access after close
- **Dependency roles**: Data holders: `NodeID`. Orchestrator: `AbcMultipleGraph` (coordinates entity factories, graph storage, and poset storage). Helpers: `IStorage` (inputs by abstract property injection).

The graph layer translates domain-level graph operations into coordinated calls on `IStorage`. It does **not** own property storage, serialization, or backend lifecycle — those belong to `IStorage` and its implementations.

The graph layer is **storage-tier agnostic**: `AbcMultipleGraph` accepts any `IStorage` via abstract property injection — flat (`NativeStorageImpl`, `MapDBStorageImpl`) or layered (`LayeredStorageImpl`). Layered storage composition is transparent to the graph layer.

The graph layer passes `NodeID` strings directly to `IStorage` — no ID translation or caching is needed. Edge IDs are deterministically generated as `"$src-$tag-$dst"` and passed to `storage.addEdge()`. Edge lookup by `(src, dst, tag)` constructs the deterministic edgeId and checks `storage.containsEdge(edgeId)`.

---

## Class / Type Specifications

### IGraph

**Responsibility:** Domain-facing contract defining node/edge CRUD and structural traversal. Edges are identified by their `(src: NodeID, dst: NodeID, tag: String)` triple. Label-aware operations are provided by `AbcMultipleGraph` (not on this interface).

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
    fun addEdge(src: NodeID, dst: NodeID, tag: String): E
    fun getEdge(src: NodeID, dst: NodeID, tag: String): E?
    fun containEdge(src: NodeID, dst: NodeID, tag: String): Boolean
    fun delEdge(src: NodeID, dst: NodeID, tag: String)
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
| `addEdge` | Creates an edge with variant-specific pre-checks | `src`, `dst`: NodeID; `tag`: edge tag | `E` — the created edge | `EntityNotExistException` if src/dst missing |
| `getEdge` | Retrieves an edge by its `(src, dst, tag)` tuple | `src`, `dst`: NodeID; `tag`: edge tag | `E?` | — |
| `containEdge` | Checks if edge exists by `(src, dst, tag)` | `src`, `dst`: NodeID; `tag`: edge tag | `Boolean` | — |
| `delEdge` | Removes edge from storage | `src`, `dst`: NodeID; `tag`: edge tag | — | — |
| `getAllNodes` / `getAllEdges` | Returns filtered sequence of entities | predicate function | `Sequence<N>` / `Sequence<E>` | — |
| `getChildren` / `getParents` | Returns adjacent nodes via outgoing/incoming edges | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |
| `getOutgoingEdges` / `getIncomingEdges` | Returns edges from/to a node | `of`: NodeID | `Sequence<E>` | — |
| `getDescendants` / `getAncestors` | BFS traversal of all reachable nodes | `of`: NodeID; `edgeCond`: optional edge predicate | `Sequence<N>` | — |

**Key design decisions:**

- Edge CRUD uses `(src, dst, tag)` tuple — no separate `EdgeID` object.
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
├── labelIdCache: HashMap<String, NodeID>       <- label name → poset storage node ID
├── nodeCache: HashMap<NodeID, SoftReference<N>>
├── edgeCache: HashMap<String, SoftReference<E>>
├── newNodeObj(): N                     <- subclass entity factory (protected abstract)
└── newEdgeObj(): E                     <- subclass entity factory (protected abstract)
```

**ID resolution:**
- `NodeID` strings are passed directly to `storage` — no caching or translation needed.
- Edge lookup by `(src, dst, tag)`: constructs deterministic `edgeId = "$src-$tag-$dst"` and checks `storage.containsEdge(edgeId)`.

**Edge creation:**
`addEdge(src, dst, tag)` generates `edgeId = "$src-$tag-$dst"` and calls `storage.addEdge(src, dst, edgeId, tag, emptyMap())`.

**Entity caching:**
`SoftReference`-based caches for nodes and edges. Allows GC to reclaim unused wrapper objects under memory pressure. Entity objects are created via `newNodeObj()` / `newEdgeObj()` (no-arg factories) then initialized via `bind(storage, id)`.

**Close behavior:**
`close()` clears all internal caches (`labelIdCache`, `nodeCache`, `edgeCache`). Does not close the storage instances — storage lifecycle is managed by the caller.

**Label-filtered methods (concrete class, not inherited from IGraph):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, tag, label)` | Creates edge (or reuses existing) and assigns label | `src`, `dst`: NodeID; `tag`: String; `label`: Label | `E` | `EntityNotExistException` if src/dst missing |
| `delEdge(src, dst, tag, label)` | Removes label from edge; if no labels remain, deletes the edge | `src`, `dst`: NodeID; `tag`: String; `label`: Label | — | — |
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
| `addEdge(src, dst, tag)` | Checks that no edge exists between `(src, dst)` before delegating to super | `src`, `dst`: NodeID; `tag`: String | `E` | `EntityAlreadyExistException` if any edge between src->dst exists |
| `addEdge(src, dst, tag, label)` | Checks direction uniqueness, then delegates to super | same + `label`: Label | `E` | `EntityAlreadyExistException` if direction conflict |

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
class MyNode : AbcNode() {
    override val type = object : AbcNode.Type { override val name = "MyNode" }
}
class MyEdge : AbcEdge() {
    override val type = object : AbcEdge.Type { override val name = "MyEdge" }
}

val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override val posetStorage = NativeStorageImpl()
    override fun newNodeObj() = MyNode()
    override fun newEdgeObj() = MyEdge()
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
    override fun newNodeObj() = MyNode()
    override fun newEdgeObj() = MyEdge()
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
- `nodeIDs` derives from `storage.nodeIDs`
- Edge lookup by `(src, dst, tag)` uses deterministic `edgeId = "$src-$tag-$dst"` + `storage.containsEdge(edgeId)`
