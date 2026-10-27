# Graph & Base Classes

> Typed directed graph API with abstract base classes for multi-edge and simple-edge graphs.

## Quick Start

```kotlin
class MyNode : AbcNode() {
    enum class MyType : AbcNode.Type { DEFAULT }
    override val type = MyType.DEFAULT
}
class MyEdge : AbcEdge() {
    enum class MyType : AbcEdge.Type { DEFAULT }
    override val type = MyType.DEFAULT
}
class MyGraph(
    override val storage: IStorage,
) : AbcMultipleGraph<MyNode, MyEdge>(), PosetTrait<MyNode, MyEdge> {
    override val graphId = "myGraph"
    override val poset: IPoset = PosetDftImpl(storage)
    override fun newNodeObj() = MyNode()
    override fun newEdgeObj() = MyEdge()
}

val graph = MyGraph(NativeStorageImpl())
val n1 = graph.addNode("a")
val n2 = graph.addNode("b")
val edge = graph.addEdge("a", "b", "calls")
edge["weight"] = IntVal(5)
```

## API

### `IGraph<N : AbcNode, E : AbcEdge>`

- **`val nodeIDs: Set<NodeID>`** -- All node IDs in the graph.
- **`addNode(withID: NodeID): N`** -- Create a node. Raises `EntityAlreadyExistException` if ID exists.
- **`getNode(whoseID: NodeID): N?`** -- Retrieve a node by ID. Returns `null` if absent.
- **`containNode(whoseID: NodeID): Boolean`** -- Check node existence.
- **`delNode(whoseID: NodeID)`** -- Delete a node and all incident edges.
- **`getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>`** -- Lazy sequence of nodes, optionally filtered.
- **`addEdge(src: NodeID, dst: NodeID, tag: String): E`** -- Create an edge. Raises `EntityNotExistException` if nodes missing, `EntityAlreadyExistException` if edge exists.
- **`getEdge(src: NodeID, dst: NodeID, tag: String): E?`** -- Retrieve an edge. Returns `null` if absent.
- **`containEdge(src: NodeID, dst: NodeID, tag: String): Boolean`** -- Check edge existence.
- **`delEdge(src: NodeID, dst: NodeID, tag: String)`** -- Delete an edge.
- **`getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>`** -- Lazy sequence of edges, optionally filtered.
- **`getIncomingEdges(of: NodeID): Sequence<E>`** -- All incoming edges to a node.
- **`getOutgoingEdges(of: NodeID): Sequence<E>`** -- All outgoing edges from a node.
- **`getChildren(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- Direct successors via outgoing edges.
- **`getParents(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- Direct predecessors via incoming edges.
- **`getDescendants(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- BFS transitive successors.
- **`getAncestors(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- BFS transitive predecessors.

### `AbcMultipleGraph<N, E>` (extends `IGraph`)

- **`abstract val storage: IStorage`** -- Graph data storage backend.
- **`abstract fun newNodeObj(): N`** -- Factory for node instances. No-arg constructor required.
- **`abstract fun newEdgeObj(): E`** -- Factory for edge instances. No-arg constructor required.
- **`protected fun rebuild()`** -- Restore graph caches from storage: registers the nodes whose owner set holds this graph's ID.

### `PosetTrait<N, E>` (extends `IGraph`, provides label-filtered operations)

Concrete graph classes mix in `PosetTrait` and provide `override val poset: IPoset`.

- **`val poset: IPoset`** -- Pluggable poset module for label hierarchy operations.
- **`addEdge(src: NodeID, dst: NodeID, tag: String, label: Label): E`** -- Create or update edge with a label. Appends label if edge exists.
- **`delEdge(src: NodeID, dst: NodeID, tag: String, label: Label)`** -- Remove a label from an edge. Deletes the edge when no labels remain.
- **`getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>`** -- Label-filtered outgoing edges.
- **`getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>`** -- Label-filtered incoming edges.
- **`getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Label-filtered successors.
- **`getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Label-filtered predecessors.
- **`getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Label-filtered BFS descendants.
- **`getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Label-filtered BFS ancestors.

### `GraphMergeLaws` (implemented by `AbcMultipleGraph`)

Pure declarations for merging the prospective changes of several writers; each writer carries a before-image and an after-image (`WriterChange(before, after)`; null is an absent key). They read no storage and hook no write.

- **`nodePropertyLaw(name: String): PropertyMergeLaw`** -- Node ID property immutable, owner set by union, other names `PropertyMergeLaw.EQUAL_OR_FAIL`.
- **`edgePropertyLaw(name: String): PropertyMergeLaw`** -- Labels by the label merge law (`model.md` Merge Laws), other names `EQUAL_OR_FAIL`.
- **`edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>>`** -- Declared unique keys besides the triple; default none. Override to declare.
- **`coalesceEdges(additions: Collection<EdgeKey>, present: Collection<EdgeKey> = emptyList()): Set<EdgeKey>`** -- Coalesces by `(src, dst, tag)` and validates declared keys.
- **`PropertyMergeLaw.merge(base: IValue?, changes: Collection<PropertyChange>): IValue?`** -- Merged value or `MergeConflictException`; the result never depends on writer order.

### `AbcSimpleGraph<N, E>` (extends `AbcMultipleGraph`)

- **`addEdge(src: NodeID, dst: NodeID, tag: String): E`** -- Raises `EntityAlreadyExistException` if any edge from `src` to `dst` exists, regardless of tag.
- **`addEdge(src: NodeID, dst: NodeID, tag: String, label: Label): E`** -- Same constraint: at most one edge per direction.
- **`edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>>`** -- Declares the `(src, dst)` pair as a unique key, so `coalesceEdges` fails for two distinct edges on one pair.

## Gotchas

- `NodeID` is a `String` typealias. Callers provide node IDs; the graph manages internal `Int` storage IDs.
- `delNode` cascades to all incident edges. No orphan edges remain.
- `AbcSimpleGraph` enforces one edge per direction per node pair. The `tag` distinguishes the edge but the constraint is on `(src, dst)`.
- `addEdge` with `Label` on `AbcMultipleGraph` is additive -- calling with the same `(src, dst, tag)` appends the label, not replaces.
- `rebuild()` must be called after re-opening a previously populated storage to restore in-memory indexes.
- `IGraph` queries returning `Sequence` return a snapshot taken at the call. Later writes do not change which entities it yields. Label-filtered `PosetTrait` queries are lazy (see `llms/label.md`).
- On a thread-safe storage, each `IGraph`, `PosetTrait`, and `IPoset` call is atomic. A group of calls is not: wrap `if (!graph.containNode(id)) graph.addNode(id)` and every other check-then-act in `graph.storage.criticalSection { }`.
- Entities in a snapshot read storage live. On an entity that another thread has deleted, property access raises `EntityNotExistException`.
- Share a graph across threads only when its storage is thread-safe (`graph.storage.criticalSectionLock != null`). Concurrency rules: `llms/storage.md`, Concurrent Use.
