# Label System Design

## Design Overview

- **Classes**: `Label`, `LabelID`, `IPoset`, `PosetDftImpl`, `PosetTrait`
- **Relationships**: `IPoset` defines the pure label hierarchy contract; `PosetDftImpl` implements `IPoset` with private caches; `PosetTrait` extends `IGraph` and provides label-filtered graph operations as default methods via a pluggable `IPoset`; `AbcEdge.labels` stores label assignments (see `design-entity.md`)
- **Abstract**: `IPoset` (implemented by `PosetDftImpl`); `PosetTrait` (mixed in by concrete graph classes)
- **Exceptions**: `EntityNotExistException` raised by `addEdge` on missing src/dst node
- **Dependency roles**: Data holders: `Label`. Orchestrator: `PosetTrait` (bridges poset to graph). Helpers: `IStorage` (poset store for label DAG persistence), `PosetDftImpl` (default `IPoset` implementation).

The label system provides label-based edge visibility as an optional trait. Concrete graph classes opt in by mixing in `PosetTrait` and providing `override val poset: IPoset`.

See `model.md` for label ordering semantics, edge visibility rules, and invariants. See `spec.md` for the visibility filtering algorithm.

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

See `model.md` for label ordering and sentinel semantics.

---

### IPoset

**Responsibility:** Contract for a pure label partial-order set (poset). No graph awareness.

**Methods:**

```kotlin
interface IPoset {
    val allLabels: Set<Label>
    fun getParents(label: Label): Map<String, Label>
    fun setParents(label: Label, parents: Map<String, Label>)
    fun getAncestors(label: Label): Sequence<Label>
    fun compare(a: Label, b: Label): Int?
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `allLabels` | All labels registered in the poset, including `INFIMUM` and `SUPREMUM` | -- | `Set<Label>` | -- |
| `getParents(label)` | Named parent relationships for a label | `label` | `Map<String, Label>` | -- |
| `setParents(label, parents)` | Replaces all parent relationships for a label | `label`; `parents` | -- | -- |
| `getAncestors(label)` | All ancestor labels traversing upwards through parent hierarchy via BFS | `label` | `Sequence<Label>` | -- |
| `compare(a, b)` | Compares two labels in the poset | `a`; `b` | `Int?` -- 0 if equal, positive if a > b, negative if a < b, null if incomparable | -- |

---

### PosetDftImpl

**Responsibility:** Default `IPoset` implementation backed by an `IStorage` for label DAG persistence. All caching state is private.

**Constructor:** `PosetDftImpl(storage: IStorage)`

The poset store uses auto-generated `Int` IDs:

| Poset concept | IStorage mapping |
|---------------|-------------------|
| Label `L` | Node with property `"label"` = `L.core` as `StrVal` |
| `setParents(L, mapOf("sub" to P))` | Edge from `L`'s node to `P`'s node, tag = `"sub"` |

---

### AbcEdge.labels

**Responsibility:** Stores the set of visibility labels assigned to an edge.

```kotlin
var labels: Set<Label>
```

Backed by a `ListVal` storage property. Callers (e.g., `PosetTrait.addEdge(src, dst, tag, label)`) are responsible for managing label assignments.

---

### PosetTrait (label-filtered graph operations)

**Responsibility:** Optional trait providing label-filtered graph operations. Mixed in by concrete graph classes alongside `AbcMultipleGraph` or `AbcSimpleGraph`. Combines `IGraph` with a pluggable `IPoset`.

**State / Fields:**

| Field | Type | Content |
|-------|------|---------|
| `poset` | `IPoset` | Pluggable poset module for label hierarchy operations |

**Label-filtered methods (default implementations):**

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

See `model.md` for the edge visibility rule and `spec.md` for the filtering algorithm.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | `addEdge` with missing src/dst node (from `IGraph`) |

`delEdge(src, dst, tag, label)` on a non-existent edge is a no-op.
