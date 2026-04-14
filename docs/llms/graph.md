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
    override val posetStorage: IStorage,
) : AbcMultipleGraph<MyNode, MyEdge>() {
    override fun newNodeObj() = MyNode()
    override fun newEdgeObj() = MyEdge()
}

val graph = MyGraph(NativeStorageImpl(), NativeStorageImpl())
val n1 = graph.addNode("a")
val n2 = graph.addNode("b")
val edge = graph.addEdge("a", "b", "calls")
edge["weight"] = NumVal(5)
graph.close()
```

## API

### `IGraph<N : AbcNode, E : AbcEdge>`

- **`val nodeIDs: Set<NodeID>`** -- All node IDs in the graph.
- **`addNode(withID: NodeID): N`** -- Create a node. Raises `EntityAlreadyExistException` if ID exists.
- **`getNode(whoseID: NodeID): N?`** -- Retrieve a node by ID. Returns `null` if absent.
- **`containNode(whoseID: NodeID): Boolean`** -- Check node existence.
- **`delNode(whoseID: NodeID)`** -- Delete a node and all incident edges.
- **`getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>`** -- Lazy sequence of nodes, optionally filtered.
- **`addEdge(src: NodeID, dst: NodeID, tag: String): E`** -- Create an edge. Raises `EntityNotExistException` if nodes missing, `EntityAlreadyExistException` if edge exists.
- **`getEdge(src: NodeID, dst: NodeID, tag: String): E?`** -- Retrieve an edge. Returns `null` if absent.
- **`containEdge(src: NodeID, dst: NodeID, tag: String): Boolean`** -- Check edge existence.
- **`delEdge(src: NodeID, dst: NodeID, tag: String)`** -- Delete an edge.
- **`getAllEdges(doSatfy: (E) -> Boolean = { true }): Sequence<E>`** -- Lazy sequence of edges, optionally filtered.
- **`getIncomingEdges(of: NodeID): Sequence<E>`** -- All incoming edges to a node.
- **`getOutgoingEdges(of: NodeID): Sequence<E>`** -- All outgoing edges from a node.
- **`getChildren(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- Direct successors via outgoing edges.
- **`getParents(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- Direct predecessors via incoming edges.
- **`getDescendants(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- BFS transitive successors.
- **`getAncestors(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- BFS transitive predecessors.

### `AbcMultipleGraph<N, E>` (extends `IGraph`, `IPoset`, `Closeable`)

- **`abstract val storage: IStorage`** -- Graph data storage backend.
- **`abstract val posetStorage: IStorage`** -- Label hierarchy storage backend.
- **`abstract fun newNodeObj(): N`** -- Factory for node instances. No-arg constructor required.
- **`abstract fun newEdgeObj(): E`** -- Factory for edge instances. No-arg constructor required.
- **`addEdge(src: NodeID, dst: NodeID, tag: String, label: Label): E`** -- Create or update edge with a label. Appends label if edge exists.
- **`delEdge(src: NodeID, dst: NodeID, tag: String, label: Label)`** -- Remove a label from an edge. Deletes the edge when no labels remain.
- **`getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>`** -- Label-filtered outgoing edges.
- **`getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>`** -- Label-filtered incoming edges.
- **`getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Label-filtered successors.
- **`getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Label-filtered predecessors.
- **`getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Label-filtered BFS descendants.
- **`getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Label-filtered BFS ancestors.
- **`protected fun rebuild()`** -- Restore graph caches from storage after deserialization.
- **`close()`** -- Release caches. Does not close the storage instances.

### `AbcSimpleGraph<N, E>` (extends `AbcMultipleGraph`)

- **`addEdge(src: NodeID, dst: NodeID, tag: String): E`** -- Raises `EntityAlreadyExistException` if any edge from `src` to `dst` exists, regardless of tag.
- **`addEdge(src: NodeID, dst: NodeID, tag: String, label: Label): E`** -- Same constraint: at most one edge per direction.

## Gotchas

- `NodeID` is a `String` typealias. Callers provide node IDs; the graph manages internal `Int` storage IDs.
- `delNode` cascades to all incident edges. No orphan edges remain.
- `AbcSimpleGraph` enforces one edge per direction per node pair. The `tag` distinguishes the edge but the constraint is on `(src, dst)`.
- `addEdge` with `Label` on `AbcMultipleGraph` is additive -- calling with the same `(src, dst, tag)` appends the label, not replaces.
- `close()` clears graph caches but does not call `storage.close()` or `posetStorage.close()`. Close storage separately.
- `rebuild()` must be called after re-opening a previously populated storage to restore in-memory indexes.
- All `Sequence` returns are lazy. Collect to a list before modifying the graph mid-iteration.
