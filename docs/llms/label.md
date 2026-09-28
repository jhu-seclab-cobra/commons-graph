# Labels & Poset

> Label hierarchy (partial order set) for edge visibility control.

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

Label-filtered graph operations as default methods. Concrete graph classes mix in `PosetTrait` and provide `override val poset: IPoset` and `override val labelIndex: LabelEdgeIndex<E> = LabelEdgeIndex()`; `AbcMultipleGraph.storage` supplies `val storage: IStorage`. Labeled `addEdge`/`delEdge`, `getLabeledEdges`, and `relabel` run inside `storage.criticalSection`, so they are atomic on a thread-safe storage.

- **`fun addEdge(src, dst, tag, label): E`** -- Create or reuse edge, assign label, record it in `labelIndex`.
- **`fun delEdge(src, dst, tag, label)`** -- Remove label; delete edge if no labels remain.
- **`fun getOutgoingEdges(of, label, cond): Sequence<E>`** -- Label-filtered outgoing edges.
- **`fun getIncomingEdges(of, label, cond): Sequence<E>`** -- Label-filtered incoming edges.
- **`fun getChildren(of, label, cond): Sequence<N>`** -- Nodes via label-filtered outgoing edges.
- **`fun getParents(of, label, cond): Sequence<N>`** -- Nodes via label-filtered incoming edges.
- **`fun getDescendants(of, label, cond): Sequence<N>`** -- BFS via label-filtered edges.
- **`fun getAncestors(of, label, cond): Sequence<N>`** -- BFS via label-filtered edges.
- **`fun getLabeledEdges(label): Sequence<E>`** -- Edges that directly carry `label` (no poset covering). Cost O(edges ever labeled `label`), not O(|E|).
- **`fun relabel(from, to, cond = { true })`** -- On every edge carrying `from` that satisfies `cond`, replace `from` with `to`. No-op when `from == to`.

### Edge Visibility Rules

- An edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l` or `by > l` in the poset hierarchy.
- `Label.SUPREMUM` disables label filtering -- all edges pass.
- `Label.INFIMUM` is below all labels -- as a query label it matches no labeled edge.
- Edges with no labels are visible only when no label filter is applied.

## Gotchas

- Never assign `Label.INFIMUM` or `Label.SUPREMUM` to edges. They are structural bounds for the poset hierarchy.
- `setParents` replaces all parents. Merge with existing parents if appending: `poset.setParents(label, poset.getParents(label) + mapOf("new" to parentLabel))`.
- `compare` returns `null` for incomparable labels. Always handle the `null` case.
- Edge labels change only through `addEdge`/`delEdge`/`relabel` (the `labels` setter is internal). Label writes through generic property `set`, direct storage writes, or another graph instance over the same storage are invisible to `labelIndex`.
- Never reuse a graph instance after `storage.clear()`: storage IDs restart and the index is not reset.
- Label-filtered `getOutgoingEdges`, `getIncomingEdges`, `getChildren`, `getParents`, `getDescendants`, and `getAncestors` are lazy. Each step is atomic; the whole iteration is not. Collect a list before modifying the graph mid-iteration. While other threads write, collect it inside the critical section: `graph.storage.criticalSection { graph.getDescendants(id, label).toList() }`.
