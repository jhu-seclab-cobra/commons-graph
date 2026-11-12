# Graph & Base Classes

> Typed directed graph API with abstract base classes for multi-edge and simple-edge graphs.

## Quick Start

```kotlin
import edu.jhu.cobra.commons.graph.IGraph

fun connect(graph: IGraph<*, *>): String {
    graph.addNode("a")
    graph.addNode("b")
    return graph.addEdge("a", "b", "calls").id
}
```

## API

### `IGraph<N : AbcNode, E : AbcEdge>`

- **`val nodeIDs: Set<NodeID>`** -- All node IDs in the graph.
- **`addNode(withID: NodeID): N`** -- Create a node. Raises `EntityAlreadyExistException` if ID exists.
- **`claimNode(from: NodeIdentity): N`** — Registers an existing storage row without allocating a row; raises `EntityNotExistException` for a missing row.
- **`readNode(from: NodeIdentity): N`** — Reads an existing row without claiming it; raises `IllegalArgumentException` for absent or mismatched identity.
- **`resolveNodeId(storageId: Int): NodeID?`** — Returns a registered application identifier or null.
- **`getNode(whoseID: NodeID): N?`** -- Retrieve a node by ID. Returns `null` if absent.
- **`containNode(whoseID: NodeID): Boolean`** -- Check node existence.
- **`delNode(whoseID: NodeID): Unit`** -- Delete a node and all incident edges.
- **`getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>`** -- Snapshot sequence of nodes, optionally filtered.
- **`addEdge(src: NodeID, dst: NodeID, tag: String): E`** -- Create an edge. Raises `EntityNotExistException` if nodes missing, `EntityAlreadyExistException` if edge exists.
- **`getEdge(src: NodeID, dst: NodeID, tag: String): E?`** -- Retrieve an edge. Returns `null` if absent.
- **`containEdge(src: NodeID, dst: NodeID, tag: String): Boolean`** -- Check edge existence.
- **`delEdge(src: NodeID, dst: NodeID, tag: String): Unit`** -- Delete an edge.
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
- **`protected fun rebuild()`** -- Restore graph caches from storage: registers the nodes whose owner set holds this graph's ID.

### `GraphMergeLaws` (implemented by `AbcMultipleGraph`)

- Merge declarations accept prospective writer changes as `WriterChange(before, after)`; null denotes an absent property, and invoking a declaration writes no Storage.

- **`nodePropertyLaw(name: String): PropertyMergeLaw`** -- Node ID property immutable, owner set by union, other names `PropertyMergeLaw.EQUAL_OR_FAIL`.
- **`edgePropertyLaw(name: String): PropertyMergeLaw`** -- Labels by the label merge law (`model.md` Merge Laws), other names `EQUAL_OR_FAIL`.
- **`edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>>`** -- Declared unique keys besides the triple; default none. Override to declare.
- **`coalesceEdges(additions: Collection<EdgeKey>, present: Collection<EdgeKey> = emptyList()): Set<EdgeKey>`** -- Coalesces by `(src, dst, tag)` and validates declared keys.
- **`PropertyMergeLaw.merge(base: IValue?, changes: Collection<PropertyChange>): IValue?`** -- Merged value or `MergeConflictException`; the result never depends on writer order.

### `AbcSimpleGraph<N, E>` (extends `AbcMultipleGraph`)

- **`addEdge(src: NodeID, dst: NodeID, tag: String): E`** -- Raises `EntityAlreadyExistException` if any edge from `src` to `dst` exists, regardless of tag.
- The labeled `addEdge` overload requires `PosetTrait`; the same one-edge-per-direction constraint applies.
- **`edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>>`** -- Declares the `(src, dst)` pair as a unique key, so `coalesceEdges` fails for two distinct edges on one pair.

## Configuration

- Supply a unique `graphId: String`, an `IStorage` and the two entity factories in an `AbcMultipleGraph` subclass; construction is shown in [Graph design](../design-graph.md#caller-examples).
- Supply `poset: IPoset` and `labelIndex: LabelEdgeIndex<E>` when implementing `PosetTrait`; construct them as shown in [Label design](../design-label.md).

## Gotchas

- `NodeID` is a `String` typealias. Callers provide node IDs; the graph manages internal `Int` storage IDs.
- `delNode` cascades to all incident edges. No orphan edges remain.
- `AbcSimpleGraph` enforces one edge per direction per node pair. The `tag` distinguishes the edge but the constraint is on `(src, dst)`.
- `addEdge` with `Label` on `AbcMultipleGraph` is additive -- calling with the same `(src, dst, tag)` appends the label, not replaces.
- `rebuild()` must be called after re-opening a previously populated storage to restore in-memory indexes.
- `AbcMultipleGraph` queries returning `Sequence` return a snapshot taken at the call. Later writes do not change which entities it yields. Label-filtered `PosetTrait` queries are lazy (see [Label API](label.md)).
- On a thread-safe storage, individual Graph and Label hierarchy operations are atomic; labeled traversal sequences are lazy and their whole iteration is not. A group of calls is not atomic: wrap `if (!graph.containNode(id)) graph.addNode(id)` and every other check-then-act in `graph.storage.criticalSection { }`.
- Entities in a snapshot read storage live. On an entity that another thread has deleted, property access raises `EntityNotExistException`.
- Share a graph across threads only when its storage is thread-safe (`graph.storage.criticalSectionLock != null`); its Label hierarchy Storage must be thread-safe or externally synchronized. Concurrent-use constraints: [Storage Gotchas](storage.md#gotchas).
