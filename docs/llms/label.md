# Labels & Poset

> Label hierarchy (partial order set) for edge visibility control, plus node grouping trait.

## Quick Start

```kotlin
val graph = MyGraph(NativeStorageImpl())
val v1 = Label("v1")
val v2 = Label("v2")

graph.poset.setParents(v2, mapOf("base" to v1))
val edge = graph.addEdge("a", "b", "calls", v1)
val visible = graph.getOutgoingEdges("a", v2)
```

## API

### `Label` (inline value class)

- **`Label(core: LabelID)`** -- Create from string. `LabelID` is a `String` typealias.
- **`Label(strVal: StrVal)`** -- Create from `StrVal`.
- **`val core: LabelID`** -- The underlying string identifier.
- **`Label.INFIMUM: Label`** -- Greatest lower bound. Below all labels in the poset.
- **`Label.SUPREMUM: Label`** -- Least upper bound. Above all labels in the poset.

### `IPoset` (interface)

- **`val allLabels: Set<Label>`** -- All registered labels, including `INFIMUM` and `SUPREMUM`.
- **`fun getParents(label: Label): Map<String, Label>`** -- Named parent labels.
- **`fun setParents(label: Label, parents: Map<String, Label>)`** -- Replace all parents for a label.
- **`fun getAncestors(label: Label): Sequence<Label>`** -- All transitive ancestors through the parent hierarchy.
- **`fun compare(a: Label, b: Label): Int?`** -- Compare in hierarchy. Positive if `a > b`, negative if `a < b`, `0` if equal, `null` if incomparable.

### `PosetTrait<N, E>` (interface, extends `IGraph`)

Label-filtered graph operations as default methods. Concrete graph classes mix in `PosetTrait` and provide `override val poset: IPoset`.

- **`fun addEdge(src, dst, tag, label): E`** -- Create or reuse edge and assign label.
- **`fun delEdge(src, dst, tag, label)`** -- Remove label; delete edge if no labels remain.
- **`fun getOutgoingEdges(of, label, cond): Sequence<E>`** -- Label-filtered outgoing edges.
- **`fun getIncomingEdges(of, label, cond): Sequence<E>`** -- Label-filtered incoming edges.
- **`fun getChildren(of, label, cond): Sequence<N>`** -- Nodes via label-filtered outgoing edges.
- **`fun getParents(of, label, cond): Sequence<N>`** -- Nodes via label-filtered incoming edges.
- **`fun getDescendants(of, label, cond): Sequence<N>`** -- BFS via label-filtered edges.
- **`fun getAncestors(of, label, cond): Sequence<N>`** -- BFS via label-filtered edges.

### Edge Visibility Rules

- An edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l` or `by > l` in the poset hierarchy.
- `Label.SUPREMUM` disables label filtering -- all edges pass.
- `Label.INFIMUM` is below all labels -- sees all edges (used as visibility floor).
- Edges with no labels are visible only when no label filter is applied.

### `TraitGroup<N, E>` (interface, extends `IGraph`) — **(deprecated)**

Group membership stored as node properties, not encoded in NodeID.

- **`val groupPrefix: String`** -- Prefix for auto-generated node IDs (e.g., `"AST"`, `"ADG"`).
- **`fun assignGroup(node: N, group: String, suffix: String? = null)`** -- Assign an existing node to a group. Sets `__group__` and `__suffix__` properties.
- **`fun addGroupNode(group: String, suffix: String? = null): N`** -- Create a node with an opaque auto-generated ID and assign it to the group.
- **`fun addGroupNode(sameGroupNode: AbcNode, suffix: String? = null): N`** -- Create a node in the same group as an existing node.
- **`fun getGroupNode(group: String, suffix: String): N?`** -- Retrieve a node by group and suffix.
- **`fun getGroupName(node: AbcNode): String?`** -- Read `__group__` property. Returns `null` if node has no group.
- **`fun getGroupSuffix(node: AbcNode): String?`** -- Read `__suffix__` property.
- **`fun getGroupNodes(group: String): Sequence<N>`** -- All nodes in a group.
- **`fun rebuildGroupCaches()`** -- Restore in-memory caches from node properties and storage meta. Call after `rebuild()`.

## Gotchas

- Never assign `Label.INFIMUM` or `Label.SUPREMUM` to edges. They are structural bounds for the poset hierarchy.
- `setParents` replaces all parents. Merge with existing parents if appending: `poset.setParents(label, poset.getParents(label) + mapOf("new" to parentLabel))`.
- `compare` returns `null` for incomparable labels. Always handle the `null` case.
- `TraitGroup.addGroupNode` requires the group to be pre-registered in `groupedNodesCounter`. Add the group key before calling.
- Group counters only increase. Deleting grouped nodes does not decrement the counter.
- Call `rebuildGroupCaches()` after `rebuild()`. Without it, suffix index and counters are empty.
- Nodes created via `addNode(withID)` have no group. Call `assignGroup` to add them to a group.
- No character restrictions on group names (arbitrary strings allowed).
