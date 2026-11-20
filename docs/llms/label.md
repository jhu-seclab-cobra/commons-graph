# Label hierarchy

> Label hierarchy and label-selected edge queries.

## Quick Start

```kotlin
import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.poset.PosetTrait

fun directEdges(graph: PosetTrait<*, *>, label: Label): List<AbcEdge> =
    graph.getLabeledEdges(label).toList()
```

## API

### `Label` (inline value class)

- **`Label(core: LabelID)`** -- Create from string; the label keeps the canonical text of `core`, so two texts that stand for the same bytes are one label. `LabelID` is a `String` typealias.
- **`Label(strVal: StrVal)`** -- Create from `StrVal`.
- **`val core: LabelID`** -- The underlying string identifier.
- **`Label.INFIMUM: Label`** -- Greatest lower bound. Below all labels in the Label hierarchy.
- **`Label.SUPREMUM: Label`** -- Least upper bound. Above all labels in the Label hierarchy.

### `IPoset` (interface)

- **`val allLabels: Set<Label>`** -- All registered labels, including `Label.INFIMUM` and `Label.SUPREMUM`.
- **`fun getParents(label: Label): Map<String, Label>`** -- Named parent labels.
- **`fun setParents(label: Label, parents: Map<String, Label>): Unit`** -- Replace all parents for a label.
- **`fun getAncestors(label: Label): Sequence<Label>`** -- All transitive ancestors through the parent hierarchy.
- **`fun compare(a: Label, b: Label): Int?`** -- Compare in hierarchy. Positive if `a > b`, negative if `a < b`, `0` if equal, `null` if incomparable.

### `PosetTrait<N, E>` (interface, extends `IGraph`)

- Labeled writes, direct-carrier queries and relabel/copy operations are atomic on thread-safe Storage; synchronize a full lazy iteration explicitly.

- **`fun addEdge(src: NodeID, dst: NodeID, tag: String, label: Label): E`** -- Creates or reuses an Edge and assigns the `Label`.
- **`fun delEdge(src: NodeID, dst: NodeID, tag: String, label: Label): Unit`** -- Remove label; delete edge if removing that label leaves no stored labels. An absent label is a no-op.
- **`fun getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>`** -- Label-filtered outgoing edges.
- **`fun getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>`** -- Label-filtered incoming edges.
- **`fun getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Nodes via label-filtered outgoing edges.
- **`fun getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- Nodes via label-filtered incoming edges.
- **`fun getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- BFS via label-filtered edges.
- **`fun getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>`** -- BFS via label-filtered edges.
- **`fun getLabeledEdges(label: Label): Sequence<E>`** -- Edges that directly carry `label` (no Label hierarchy covering). Assignments must use the owning Graph’s label operations.
- **`fun relabel(from: Label, to: Label, cond: (E) -> Boolean = { true }): Unit`** -- On every edge carrying `from` that satisfies `cond`, replace `from` with `to`. No-op when `from == to`.
- **`fun copyLabel(from: Label, to: Label, since: Long = 0L, cond: (E) -> Boolean = { true }): Long`** -- Add `to` beside `from` on every edge carrying `from` that satisfies `cond` and was recorded under `from` after record `since`; no label is removed. Returns the record count: pass it as `since` to the next copy of `from` so that copy reads only the edges recorded under `from` in between. No-op when `from == to`.
- **`PosetTrait.mergeLabels(base: Set<Label>, changes: Collection<WriterChange<Set<Label>>>): Set<Label>`** -- Pure merge under the label merge law (`model.md` Merge Laws); `MergeConflictException` under that law's conflict rule.

- **`fun setLabels(edge: E, labels: Set<Label>): Unit`** — Replaces stored labels; an empty set keeps the Edge without a label.
- **`fun visitLabeledEdgesSince(label: Label, since: Long = 0L, visit: (E) -> Label?): Long`** — Visits current carriers recorded after the cursor and records each callback’s contributed label; returns the resulting cursor.

### Edge Visibility Rules

- An edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l` or `by > l` in the Label hierarchy.
- `Label.SUPREMUM` disables label filtering -- all edges pass.
- `Label.INFIMUM` is below all labels -- an edge carrying it is visible under every label; as a query label it matches only the edges carrying it.
- Edges with no labels are visible only when no label filter is applied.

## Configuration

- Construct `Label(core: String)` with the required identifier.
- Construct `PosetDftImpl(storage: IStorage)` with separate Storage for the Label hierarchy.
- Supply `poset` and `labelIndex` on a Graph implementing `PosetTrait`; the operation above accepts that configured Graph.

## Gotchas

- Never assign `Label.SUPREMUM` to edges. It is a structural bound for the Label hierarchy.
- `setParents` replaces all parents. Merge with existing parents if appending: `poset.setParents(label, poset.getParents(label) + mapOf("new" to parentLabel))`.
- `compare` returns `null` for incomparable labels. Always handle the `null` case.
- Edge labels change only through `addEdge`/`delEdge`/`relabel`/`copyLabel`/`setLabels` (the `storedLabels` setter is internal; `labels` is read-only). Label writes through generic property `set`, direct Storage writes or another Graph instance are not observed by the owning Graph’s label operations.
- Never reuse a graph instance after `storage.clear()`: storage IDs restart and the index is not reset.
- Label-filtered `getOutgoingEdges`, `getIncomingEdges`, `getChildren`, `getParents`, `getDescendants`, and `getAncestors` are lazy. The underlying storage calls are individually atomic on thread-safe storage; label filtering and the whole iteration are not one atomic operation. Collect a list before modifying the graph mid-iteration. While other threads write, collect it inside the critical section: `graph.storage.criticalSection { graph.getDescendants(id, label).toList() }`. Keep the Label hierarchy unchanged during traversal, or synchronize its writers with the same enclosing critical section.
