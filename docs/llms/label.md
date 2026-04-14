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

- **`val groupPrefix: String`** -- Prefix for grouped node IDs. Typically the graph name.
- **`val groupedNodesCounter: MutableMap<String, Int>`** -- Monotonic counter per group name.
- **`addGroupNode(group: String, suffix: String? = null): N`** -- Add node with ID `prefix@group#suffix`. Auto-generates suffix from counter when `null`. Raises `IllegalArgumentException` if group not in `groupedNodesCounter`.
- **`addGroupNode(sameGroupNode: AbcNode, suffix: String? = null): N`** -- Add node to same group as existing node.
- **`getGroupNode(group: String, suffix: String): N?`** -- Retrieve grouped node.
- **`getGroupName(node: AbcNode): String?`** -- Extract group name from node ID. Returns `null` if format invalid.

### Node ID Format

- Grouped node IDs follow: `{prefix}@{group}#{suffix}`.
- `@` and `#` are reserved delimiters. Group names and suffixes must not contain them.

## Gotchas

- Never assign `Label.INFIMUM` or `Label.SUPREMUM` to edges. They are structural bounds for the poset hierarchy.
- `Label.parents` setter replaces all parents. Merge with existing parents if appending: `label.parents = label.parents + mapOf("new" to parentLabel)`.
- `Label.compareTo` returns `null` for incomparable labels. Always handle the `null` case.
- `IPoset` operations require the `AbcMultipleGraph` receiver scope (`with(graph) { ... }`) because `parents`, `ancestors`, and `compareTo` are extension members on `Label`.
- `TraitNodeGroup.addGroupNode` requires the group to be pre-registered in `groupedNodesCounter`. Add the group key before calling.
- Group counters only increase. Deleting grouped nodes does not decrement the counter.
