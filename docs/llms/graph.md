# Graph & Base Classes

> Typed directed graph API with abstract base classes for multi-edge and simple-edge graphs.

## Quick Start

```kotlin
import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcMultipleGraph
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.poset.IPoset
import edu.jhu.cobra.commons.graph.poset.LabelEdgeIndex
import edu.jhu.cobra.commons.graph.poset.PosetDftImpl
import edu.jhu.cobra.commons.graph.poset.PosetTrait
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IntVal

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
    override val poset: IPoset = PosetDftImpl(NativeStorageImpl())
    override val labelIndex = LabelEdgeIndex<MyEdge>()
    override fun newNodeObj() = MyNode()
    override fun newEdgeObj() = MyEdge()
    override fun onRestoredEdges(ids: Set<Int>) = restoreLabelIndex(ids, ::edgeByStorageId)
}

fun main() {
    val graph = MyGraph(NativeStorageImpl())
    graph.addNode("a")
    graph.addNode("b")
    val edge = graph.addEdge("a", "b", "calls")
    edge["weight"] = IntVal(5)
}
```

## API

### `IGraph<N : AbcNode, E : AbcEdge>`

- **`val nodeIDs: Set<NodeID>`** -- All node IDs in the graph.
- **`addNode(withID: NodeID): N`** -- Create a node. Raises `EntityAlreadyExistException` if ID exists.
- **`getNode(whoseID: NodeID): N?`** -- Retrieve a node by ID. Returns `null` if absent.
- **`containNode(whoseID: NodeID): Boolean`** -- Check node existence.
- **`delNode(whoseID: NodeID)`** -- Delete a node and all incident edges.
- **`getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>`** -- Snapshot sequence of nodes, optionally filtered.
- **`addEdge(src: NodeID, dst: NodeID, tag: String): E`** -- Create an edge. Raises `EntityNotExistException` if nodes missing, `EntityAlreadyExistException` if edge exists.
- **`getEdge(src: NodeID, dst: NodeID, tag: String): E?`** -- Retrieve an edge. Returns `null` if absent.
- **`containEdge(src: NodeID, dst: NodeID, tag: String): Boolean`** -- Check edge existence.
- **`delEdge(src: NodeID, dst: NodeID, tag: String)`** -- Delete an edge.
- **`getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>`** -- Snapshot sequence of edges, optionally filtered.
- **`getIncomingEdges(of: NodeID): Sequence<E>`** -- All incoming edges to a node.
- **`getOutgoingEdges(of: NodeID): Sequence<E>`** -- All outgoing edges from a node.
- **`getChildren(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- Direct successors via outgoing edges.
- **`getParents(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- Direct predecessors via incoming edges.
- **`getDescendants(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- BFS transitive successors.
- **`getAncestors(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>`** -- BFS transitive predecessors.

### `AbcMultipleGraph<N, E>` (implements `IGraph` and `GraphMergeLaws`)

- **`abstract val storage: IStorage`** -- Graph data storage backend.
- **`protected abstract fun newNodeObj(): N`** -- Factory for an unbound node instance.
- **`protected abstract fun newEdgeObj(): E`** -- Factory for an unbound edge instance.
- **`protected open fun isEdgeVisible(edge: E): Boolean`** -- Declare enumerated edge visibility here; do not hide edges in whole-adjacency overrides.
- **`protected fun rebuild()`** -- Restore graph caches from storage: registers the nodes whose owner set holds this graph's ID.

### `PosetTrait<N, E>` (extends `IGraph`, provides label-filtered operations)

Concrete graph classes mix in `PosetTrait` and provide `poset` and `labelIndex`.

- **`val poset: IPoset`** -- Pluggable poset module for label hierarchy operations.
- **`addEdge(src: NodeID, dst: NodeID, tag: String, label: Label): E`** -- Create or update edge with a label. Appends label if edge exists.
- **`delEdge(src: NodeID, dst: NodeID, tag: String, label: Label)`** -- Remove a label from an edge. Deletes the edge if removing that label leaves no stored labels; an absent label is a no-op.
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
- The labeled `addEdge` overload requires `PosetTrait`; the same one-edge-per-direction constraint applies.
- **`edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>>`** -- Declares the `(src, dst)` pair as a unique key, so `coalesceEdges` fails for two distinct edges on one pair.

## Gotchas

- `NodeID` is a `String` typealias. Callers provide node IDs; the graph manages internal `Int` storage IDs.
- `delNode` cascades to all incident edges. No orphan edges remain.
- `AbcSimpleGraph` enforces one edge per direction per node pair. The `tag` distinguishes the edge but the constraint is on `(src, dst)`.
- `addEdge` with `Label` on `AbcMultipleGraph` is additive -- calling with the same `(src, dst, tag)` appends the label, not replaces.
- `rebuild()` must be called after re-opening a previously populated storage to restore in-memory indexes.
- `AbcMultipleGraph` queries returning `Sequence` return a snapshot taken at the call. Later writes do not change which entities it yields. Label-filtered `PosetTrait` queries are lazy (see `llms/label.md`).
- On a thread-safe storage, individual graph and poset operations are atomic; labeled traversal sequences are lazy and their whole iteration is not. A group of calls is not atomic: wrap `if (!graph.containNode(id)) graph.addNode(id)` and every other check-then-act in `graph.storage.criticalSection { }`.
- Entities in a snapshot read storage live. On an entity that another thread has deleted, property access raises `EntityNotExistException`.
- Share a graph across threads only when its storage is thread-safe (`graph.storage.criticalSectionLock != null`); its poset storage must also be thread-safe or externally synchronized. The Quick Start uses a single-threaded poset storage. Concurrency rules: `llms/storage.md`, Concurrent Use.
