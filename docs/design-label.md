# Label System Design

## Design Overview

- **Classes**: `Label`, `LabelID`, `IPoset`, `TraitPoset`
- **Relationships**: `IPoset` defines the poset contract; `TraitPoset` implements `IPoset` and provides label-filtered graph operations as an optional mixin; `AbcEdge.labels` stores label assignments (see `design-entity.md`)
- **Abstract**: `IPoset` (implemented by `TraitPoset`); `TraitPoset` (mixed in by concrete graph classes)
- **Exceptions**: `EntityNotExistException` raised by `addEdge` on missing src/dst node
- **Dependency roles**: Data holders: `Label`, `PosetState`. Orchestrator: `TraitPoset` (bridges poset to graph). Helpers: `IStorage` (poset store for label DAG persistence).

The label system provides **label-based edge visibility** as an optional trait. The poset structure over `Label` values is defined by `IPoset`, label assignment to edges is a property on `AbcEdge`, and label-filtered graph traversal methods are provided by `TraitPoset`. Concrete graph classes opt in by mixing in `TraitPoset` and providing a `posetStorage: IStorage` instance. Labels stored as nodes in poset storage, parent relationships as edges (`child -> parent`, edge tag = relationship name).

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

Getting reads from a `ListVal` property named `"labels"` on the edge via storage, mapping each `StrVal` to `Label`. Setting overwrites the storage property. Callers (e.g., `TraitPoset.addEdge(src, dst, tag, label)`) are responsible for managing label assignments.

---

### TraitPoset (label integration)

**Responsibility:** Optional trait implementing `IPoset` and label-filtered graph operations. Mixed in by concrete graph classes alongside `AbcMultipleGraph` or `AbcSimpleGraph`.

**State / Fields:**

| Field | Type | Content |
|-------|------|---------|
| `posetStorage` | `IStorage` | Dedicated storage for label DAG |
| `posetState` | `PosetState` | In-memory caches (labelIdCache, intToLabel, queryCache) |

The poset store uses auto-generated `Int` IDs:

| Poset concept | IStorage mapping |
|---------------|-------------------|
| Label `L` | Node with property `"label"` = `L.core` as `StrVal` |
| `L.parents = mapOf("sub" to P)` | Edge from `L`'s node to `P`'s node, tag = `"sub"` |

**Label-filtered methods:**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, tag, label)` | Creates or reuses edge and assigns label | `src`, `dst`: NodeID; `tag`; `label` | `E` | `EntityNotExistException` if src/dst missing |
| `delEdge(src, dst, tag, label)` | Removes label; if no labels remain, deletes edge | `src`, `dst`: NodeID; `tag`; `label` | -- | -- |
| `getOutgoingEdges(of, label, cond)` | Label-filtered outgoing edges | `of`; `label`; `cond` | `Sequence<E>` | -- |
| `getIncomingEdges(of, label, cond)` | Label-filtered incoming edges | `of`; `label`; `cond` | `Sequence<E>` | -- |
| `getChildren(of, label, cond)` | Nodes via label-filtered outgoing edges | `of`; `label`; `cond` | `Sequence<N>` | -- |
| `getParents(of, label, cond)` | Nodes via label-filtered incoming edges | `of`; `label`; `cond` | `Sequence<N>` | -- |
| `getDescendants(of, label, cond)` | BFS via label-filtered edges | `of`; `label`; `cond` | `Sequence<N>` | -- |
| `getAncestors(of, label, cond)` | BFS via label-filtered edges | `of`; `label`; `cond` | `Sequence<N>` | -- |

**Edge visibility rule:** An edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l || by > l`. Among all visitable labels, those covered by a higher visitable label are excluded.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | `addEdge` with missing src/dst node (from `IGraph`) |

`delEdge(src, dst, tag, label)` on a non-existent edge is a no-op.
