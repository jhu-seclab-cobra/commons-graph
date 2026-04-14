# Label System Design

## Design Overview

- **Classes**: `Label`, `LabelID`, `IPoset`
- **Relationships**: `IPoset` defines the poset contract; `AbcMultipleGraph` implements `IPoset` using a dedicated `IStorage` for the label DAG (see `docs/basic/graph.design.md`); `AbcEdge.labels` stores label assignments (see `docs/basic/entity.design.md`)
- **Abstract**: `IPoset` (implemented by `AbcMultipleGraph`)
- **Exceptions**: `EntityNotExistException` raised by `addEdge` on missing src/dst node
- **Dependency roles**: Data holders: `Label`. Orchestrator: `AbcMultipleGraph` (bridges poset to graph). Helpers: `IStorage` (poset store for label DAG persistence).

The label system provides **label-based edge visibility** integrated into `IGraph`, `IPoset`, and `AbcMultipleGraph`. The poset structure over `Label` values is defined by `IPoset`, label assignment to edges is a property on `AbcEdge`, and label-filtered graph traversal methods are declared on `AbcMultipleGraph` (see `docs/basic/graph.design.md`). `AbcMultipleGraph` implements `IPoset` with a **dedicated poset `IStorage`** -- labels stored as nodes, parent relationships as edges (`child -> parent`, edge tag = relationship name).

---

## Class / Type Specifications

### Label

**Responsibility:** Inline value object representing a label in the partial-order structure.

**State / Fields:**

```kotlin
typealias LabelID = String

@JvmInline
value class Label(val core: LabelID) {
    constructor(strVal: StrVal) : this(strVal.core)
    companion object {
        val INFIMUM: Label   // Greatest Lower Bound -- below all labels
        val SUPREMUM: Label  // Least Upper Bound -- above all labels
    }
}
```

A `Label`'s ordering is not intrinsic -- it is defined by the poset structure. `INFIMUM` and `SUPREMUM` are special sentinel bounds and must not be assigned to edges.

---

### IPoset

**Responsibility:** Contract for a label partial-order set (poset) controlling edge visibility.

**Methods:**

```kotlin
interface IPoset {
    val allLabels: Set<Label>
    var Label.parents: Map<String, Label>
    val Label.ancestors: Sequence<Label>
    fun Label.compareTo(other: Label): Int?
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `allLabels` | All labels registered in the poset, including `INFIMUM` and `SUPREMUM` | -- | `Set<Label>` | -- |
| `Label.parents` (get/set) | Reads/writes typed parent relationships for a label | -- | `Map<String, Label>` | -- |
| `Label.ancestors` | All ancestor labels traversing upwards through parent hierarchy via BFS | -- | `Sequence<Label>` | -- |
| `Label.compareTo(other)` | Compares two labels in the poset | `other`: Label | `Int?` -- 0 if equal, positive if this > other, negative if this < other, null if incomparable | -- |

All properties except `allLabels` are **member extensions** -- accessible only within a scope that provides an `IPoset` receiver.

---

### AbcEdge.labels

**Responsibility:** Stores the set of visibility labels assigned to an edge.

```kotlin
var labels: Set<Label>
```

Getting reads from a `ListVal` property named `"labels"` on the edge via storage, mapping each `StrVal` to `Label`. Setting overwrites the storage property. Callers (e.g., `AbcMultipleGraph.addEdge(src, dst, tag, label)`) are responsible for managing label assignments.

---

### AbcMultipleGraph (label integration)

**Responsibility:** Implements `IPoset` using a dedicated `posetStorage: IStorage` instance.

The poset store uses auto-generated `Int` IDs:

| Poset concept | IStorage mapping |
|---------------|-------------------|
| Label `L` | Node with property `"label"` = `L.core` as `StrVal` |
| `L.parents = mapOf("sub" to P)` | Edge from `L`'s node to `P`'s node, tag = `"sub"` |

`allLabels` derived from `posetStorage.nodeIDs` plus `INFIMUM` and `SUPREMUM`.

`Label.parents` getter reads outgoing edges from the label node -- each edge's destination is a parent label, the edge tag is the relationship name. Setter removes all existing parent edges and creates new ones.

`Label.ancestors` performs BFS over parent edges, yielding ancestor labels transitively.

`Label.compareTo(other)` performs bidirectional reachability queries via `ancestors` traversal.

**Close contract:** `close()` releases internal resources. Poset state persisted in the poset store.

Label-filtered graph traversal methods are documented in `docs/basic/graph.design.md`.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | `addEdge` with missing src/dst node (from `IGraph`) |

`delEdge(src, dst, tag, label)` on a non-existent edge is a no-op.

---

## Validation Rules

### Label

- `Label.INFIMUM` is below all labels; `Label.SUPREMUM` is above all labels -- structural bounds, must not be assigned to edges

### AbcMultipleGraph (label-filtered operations)

- `addEdge(src, dst, tag, label)` requires both src and dst nodes to exist
- `delEdge(src, dst, tag, label)` removes only the specified label; edge deleted only when no labels remain
- `delEdge(src, dst, tag, label)` on a non-existent edge is a no-op
- Edge visibility: an edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l || by > l`; among all visitable labels, those covered by a higher visitable label are excluded
