# Label System Design

## Design Overview

- **Classes**: `Label`, `LabelID`, `IPoset`, `PosetDftImpl`, `LabelEdgeIndex`, `PosetTrait`
- **Relationships**: `IPoset` defines the pure label hierarchy contract; `PosetDftImpl` implements `IPoset` with private caches; `PosetTrait` extends `IGraph` and provides label-filtered graph operations as default methods via a pluggable `IPoset`; `LabelEdgeIndex` is the per-graph label → edges index `PosetTrait` maintains; `AbcEdge.labels` stores label assignments (see `design-entity.md`)
- **Abstract**: `IPoset` (implemented by `PosetDftImpl`); `PosetTrait` (mixed in by concrete graph classes)
- **Exceptions**: `EntityNotExistException` raised by `addEdge` on missing src/dst node
- **Dependency roles**: Data holders: `Label`. Orchestrator: `PosetTrait` (bridges poset to graph). Helpers: `IStorage` (poset store for label DAG persistence), `PosetDftImpl` (default `IPoset` implementation), `LabelEdgeIndex` (label carrier index).

The label system provides label-based edge visibility as an optional trait. Concrete graph classes opt in by mixing in `PosetTrait` and providing `override val poset: IPoset` and `override val labelIndex: LabelEdgeIndex<E>`; `AbcMultipleGraph.storage` supplies the trait's `storage`.

See `model.md` for label ordering semantics, edge visibility rules, and invariants. See `spec-graph.md` for the visibility filtering algorithm and `spec-poset-label-index.md` for the label carrier index.

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

See `model.md` for label ordering and structural-bound semantics.

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

**Responsibility:** Default `IPoset` implementation backed by an `IStorage` for label DAG persistence. All caching state is private. Every public operation runs inside `storage.criticalSection` (`design-storage.md`), so cache reads/writes and closure rebuilds never interleave with a concurrent mutation.

**Constructor:** `PosetDftImpl(storage: IStorage)`

When `storage` is an `IRevertibleStorage`, the constructor subscribes a `RevertListener`: a revert evicts the removed label nodes from the label↔`Int` caches and drops their closure rows; the other rows stay unless the notice shows a parent edge of a surviving label removed or exposed again, which discards the closure (`spec-poset.md` Maintain closure, `spec-graph.md` Revert).

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

Backed by a `ListVal` storage property. The setter is `internal`: label assignments go through `PosetTrait` (`addEdge`/`delEdge` with a label, `relabel`, `setLabels`), which keeps `LabelEdgeIndex` complete.

---

### LabelEdgeIndex

**Responsibility:** Per-graph inverted index from a label to the edges that may carry it, keyed by edge storage ID. Owned by one graph instance; mutated only by `PosetTrait` default methods. Algorithm, invariants, and preconditions: `spec-poset-label-index.md`.

**Constructor:** `LabelEdgeIndex<E : AbcEdge>()` — public; every member is `internal`.

| Member | Behavior |
|--------|----------|
| `ensureBuilt(allEdges)` | Scans `allEdges()` once and records each edge under each label it carries; later calls return immediately |
| `record(label, edge)` | Adds `edge` to the candidates of `label` under `edge.storageId`; an entry under the same storage ID is replaced |
| `candidates(label)` | Snapshot of the candidates of `label` (a superset of its carriers) |
| `drop(label, edge)` | Removes the candidate under `edge.storageId`; only for an edge that no longer exists |

Not synchronized: every member is called inside the owning graph's `storage.criticalSection` (`PosetTrait`), so the index holds no lock of its own and never orders a lock against the storage lock.

---

### PosetTrait (label-filtered graph operations)

**Responsibility:** Optional trait providing label-filtered graph operations. Mixed in by concrete graph classes alongside `AbcMultipleGraph` or `AbcSimpleGraph`. Combines `IGraph` with a pluggable `IPoset`.

**State / Fields:**

| Field | Type | Content |
|-------|------|---------|
| `poset` | `IPoset` | Pluggable poset module for label hierarchy operations |
| `labelIndex` | `LabelEdgeIndex<E>` | Label carrier index of this graph instance |
| `storage` | `IStorage` | Storage of this graph; its critical section makes each label operation atomic |

**Label-filtered methods (default implementations):** `addEdge`/`delEdge` with a label, `getLabeledEdges`, `relabel`, and `setLabels` each run inside `storage.criticalSection`: the label read, label write, and index update are one atomic step. The label-filtered queries below (`getOutgoingEdges` through `getAncestors`) return lazy sequences over atomic graph calls: each step is atomic, and an iteration is atomic as a whole only when the caller runs it inside `storage.criticalSection`.

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, tag, label)` | Creates or reuses edge, assigns label, records it in `labelIndex` | `src`, `dst`: NodeID; `tag`; `label` | `E` | `EntityNotExistException` if src/dst missing |
| `delEdge(src, dst, tag, label)` | Removes label; if the edge carried the label and none remain, deletes edge | `src`, `dst`: NodeID; `tag`; `label` | -- | -- |
| `getLabeledEdges(label)` | Edges carrying `label` directly (no covering), in O(candidates); materialized inside the critical section | `label` | `Sequence<E>` | -- |
| `relabel(from, to, cond)` | On every edge carrying `from` with `cond`, replaces `from` by `to`; `from == to` is a no-op | `from`; `to`; `cond` | -- | -- |
| `setLabels(edge, labels)` | Replaces the edge's whole label set and records the edge under each new label; an empty set keeps the edge with no label | `edge`: E; `labels`: Set<Label> | -- | -- |
| `getOutgoingEdges(of, label, cond)` | Label-filtered outgoing edges | `of`; `label`; `cond` | `Sequence<E>` | -- |
| `getIncomingEdges(of, label, cond)` | Label-filtered incoming edges | `of`; `label`; `cond` | `Sequence<E>` | -- |
| `getChildren(of, label, cond)` | Nodes via label-filtered outgoing edges | `of`; `label`; `cond` | `Sequence<N>` | -- |
| `getParents(of, label, cond)` | Nodes via label-filtered incoming edges | `of`; `label`; `cond` | `Sequence<N>` | -- |
| `getDescendants(of, label, cond)` | BFS via label-filtered edges; never yields the start node | `of`; `label`; `cond` | `Sequence<N>` | -- |
| `getAncestors(of, label, cond)` | BFS via label-filtered edges; never yields the start node | `of`; `label`; `cond` | `Sequence<N>` | -- |

See `model.md` for the edge visibility rule and `spec-graph.md` for the filtering algorithm.

**Label merge (companion function):**

| Member | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `PosetTrait.mergeLabels(base, changes)` | Pure: base plus the writers' additions minus their removals, each against the writer's own before-image (`model.md` Merge Laws; `spec-graph-merge.md`). Reads no storage and does not touch `labelIndex` | `base`: Set<Label>; `changes`: Collection<WriterChange<Set<Label>>> | `Set<Label>` | `MergeConflictException` if a label is added by one writer and removed by another |

The `labels` property of an edge takes this law through `edgePropertyLaw("labels")` (`design-graph.md`), which merges stored label lists and returns them sorted.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | `addEdge` with missing src/dst node (from `IGraph`) |
| `MergeConflictException` | `PosetTrait.mergeLabels` with opposing changes to one label |

`delEdge(src, dst, tag, label)` on a non-existent edge is a no-op. Removing a label the edge does not carry — including from an unlabeled edge — is a no-op: only the labeled `delEdge` call that removes the edge's last carried label deletes the edge.
