# Labels & Poset

> Label hierarchy (partial order set) for edge visibility control, plus node grouping trait.

## Quick Start

```kotlin
val graph = MyGraph(NativeStorageImpl(), NativeStorageImpl())
val v1 = Label("v1")
val v2 = Label("v2")

with(graph) {
    v2.parents = mapOf("base" to v1)
    val edge = addEdge("a", "b", "calls", v1)
    val visible = getOutgoingEdges("a", v2)
}
```

## API

### `Label` (inline value class)

- **`Label(core: LabelID)`** -- Create from string. `LabelID` is a `String` typealias.
- **`Label(strVal: StrVal)`** -- Create from `StrVal`.
- **`val core: LabelID`** -- The underlying string identifier.
- **`Label.INFIMUM: Label`** -- Greatest lower bound. Below all labels in the poset.
- **`Label.SUPREMUM: Label`** -- Least upper bound. Above all labels in the poset.

### `IPoset` (interface, implemented by `AbcMultipleGraph`)

- **`val allLabels: Set<Label>`** -- All registered labels, including `INFIMUM` and `SUPREMUM`.
- **`var Label.parents: Map<String, Label>`** -- Named parent labels. Setting replaces all parents. Reading returns current parents.
- **`val Label.ancestors: Sequence<Label>`** -- All transitive ancestors through the parent hierarchy.
- **`fun Label.compareTo(other: Label): Int?`** -- Compare in hierarchy. Positive if `this > other`, negative if `this < other`, `0` if equal, `null` if incomparable.

### Edge Visibility Rules

- An edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l` or `by > l` in the poset hierarchy.
- `Label.SUPREMUM` disables label filtering -- all edges pass.
- `Label.INFIMUM` is below all labels -- sees all edges (used as visibility floor).
- Edges with no labels are visible only when no label filter is applied.

### `TraitNodeGroup<N, E>` (interface, extends `IGraph`)

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
- `Label.parents` setter replaces all parents. Merge with existing parents if appending: `label.parents = label.parents + mapOf("new" to parentLabel)`.
- `Label.compareTo` returns `null` for incomparable labels. Always handle the `null` case.
- `IPoset` operations require a `TraitPoset` receiver scope (`with(graph) { ... }`) because `parents`, `ancestors`, and `compareTo` are extension members on `Label`.
- `TraitNodeGroup.addGroupNode` requires the group to be pre-registered in `groupedNodesCounter`. Add the group key before calling.
- Group counters only increase. Deleting grouped nodes does not decrement the counter.
- Call `rebuildGroupCaches()` after `rebuild()`. Without it, suffix index and counters are empty.
- Nodes created via `addNode(withID)` have no group. Call `assignGroup` to add them to a group.
- No character restrictions on group names (arbitrary strings allowed).
