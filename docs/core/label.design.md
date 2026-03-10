# TraitLabelLattice Design

## Design Overview

- **Classes**: `Label`, `ILabelLattice`, `AbcBasicLabelLattice`, `DefaultLatticeImpl`, `JgraphtLatticeImpl`, `TraitLabelLattice`, `AbcLabelGraph`, `AbcLabelSimpleGraph`, `AbcLabelMultiGraph`
- **Relationships**: `DefaultLatticeImpl` extends `AbcBasicLabelLattice`; `JgraphtLatticeImpl` extends `AbcBasicLabelLattice`; `AbcBasicLabelLattice` implements `ILabelLattice`; `TraitLabelLattice` extends `IGraph` and `ILabelLattice`; `AbcLabelGraph` extends `AbcBasicGraph` and implements `TraitLabelLattice`; `AbcLabelGraph` contains `AbcBasicLabelLattice` (composition, one-way); `AbcLabelSimpleGraph` extends `AbcLabelGraph`; `AbcLabelMultiGraph` extends `AbcLabelGraph`
- **Abstract**: `ILabelLattice` (implemented by `AbcBasicLabelLattice`); `AbcBasicLabelLattice` (extended by `DefaultLatticeImpl`, `JgraphtLatticeImpl`); `TraitLabelLattice` (implemented by `AbcLabelGraph`); `AbcLabelGraph` (extended by `AbcLabelSimpleGraph`, `AbcLabelMultiGraph`)
- **Exceptions**: `IllegalArgumentException` raised by `JgraphtLatticeImpl` on parent reassignment or `loadLattice` conflict; `EntityNotExistException` raised by `TraitLabelLattice.addEdge` on missing src/dst node
- **Dependency roles**: Data holders: `Label`. Orchestrator: `AbcLabelGraph` (bridges lattice to graph via composition). Helpers: `AbcBasicLabelLattice` (lattice logic, injected by constructor).

`TraitLabelLattice` is an optional graph trait that adds **label-based edge visibility** on top of standard `IGraph` operations. It owns a partial-order (poset) structure over `Label` values, label assignment to edges, visibility-filtered graph traversal, and lattice persistence into/from `IStorage` metadata. It does **not** own graph topology, node/edge storage, or the traversal algorithms of the base graph — it only filters which edges are visible under a given label.

---

## Class / Type Specifications

### Label

**Responsibility:** Plain value object representing a label in the partial-order structure.

**State / Fields:**

```kotlin
data class Label(val core: String) {
    companion object {
        val INFIMUM  = Label("infimum")   // Greatest Lower Bound — below all labels
        val SUPREMUM = Label("supremum")  // Least Upper Bound   — above all labels
    }
}
```

A `Label`'s ordering is not intrinsic — it is defined by the lattice structure. `INFIMUM` and `SUPREMUM` are special sentinel bounds.

---

### ILabelLattice

**Responsibility:** Pure poset contract providing member extensions on `Label` and `AbcEdge` for lattice operations.

**Methods:**

```kotlin
interface ILabelLattice {
    val allLabels: Set<Label>

    var Label.parents: Map<String, Label>   // typed parent relationships
    val Label.ancestors: Sequence<Label>    // all ancestors via BFS/DFS
    var Label.changes: Set<EdgeID>          // edges modified when this label changed
    var AbcEdge.labels: Set<Label>          // labels attached to an edge

    fun Label.compareTo(other: Label): Int?

    fun storeLattice(into: IStorage)        // serialize poset + change log → storage metadata
    fun loadLattice(from: IStorage)         // restore poset + change log from storage metadata
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `Label.parents` (get/set) | Reads/writes typed parent relationships for a label | — | `Map<String, Label>` | `IllegalArgumentException` (JgraphtLatticeImpl: immutable after set) |
| `Label.ancestors` | Returns all ancestors via BFS/DFS traversal | — | `Sequence<Label>` | — |
| `Label.changes` (get/set) | Reads/writes the set of edge IDs modified under this label | — | `Set<EdgeID>` | — |
| `AbcEdge.labels` (get/set) | Reads/writes the set of labels attached to an edge | — | `Set<Label>` | — |
| `Label.compareTo(other)` | Compares two labels in the poset | `other`: Label | `Int?` — 0 if equal, positive if higher, negative if lower, null if incomparable | — |
| `storeLattice` | Serializes poset and change log to storage metadata | `into`: IStorage | — | — |
| `loadLattice` | Restores poset and change log from storage metadata | `from`: IStorage | — | `IllegalArgumentException` (JgraphtLatticeImpl: parent conflicts) |

All properties are **member extensions** — they are only accessible within a scope that provides an `ILabelLattice` receiver. This is what requires the composition pattern in `AbcLabelGraph`.

---

### AbcBasicLabelLattice

**Responsibility:** Abstract base implementing `ILabelLattice` with change tracking, comparison cache, and persistence logic.

**Persistence format:**

| Key | Content |
|-----|---------|
| `__lattice__` | `MapVal` of `labelName → MapVal<relType, parentName>` |
| `__changes__` | `MapVal` of `labelName → ListVal<serialized EdgeID>` |

---

### DefaultLatticeImpl

**Responsibility:** Mutable poset implementation using `HashMap` — suitable for dynamic or test scenarios.

- Stores parents in `HashMap<Label, MutableMap<String, Label>>`
- Parents are **mutable** — the hierarchy can be rebuilt at any time
- Ancestor traversal: BFS with visited set (cycle-safe)

---

### JgraphtLatticeImpl

**Responsibility:** Immutable poset implementation using JGraphT `SimpleDirectedGraph` — suitable for static, pre-defined lattices.

- Stores the hierarchy as `SimpleDirectedGraph<Label, NamedEdge>` from JGraphT
- Parents are **immutable once set** — reassigning throws `IllegalArgumentException`
- Ancestor traversal: DFS via `incomingEdgesOf`

Choosing between implementations is done at `AbcLabelGraph` construction time:

```kotlin
class MyGraph(name: String) : AbcLabelSimpleGraph<MyNode, MyEdge>(name) {
    // default: DefaultLatticeImpl()
}

class MyStaticGraph(name: String)
    : AbcLabelSimpleGraph<MyNode, MyEdge>(name, JgraphtLatticeImpl()) {
    // uses JGraphT-backed immutable lattice
}
```

---

### TraitLabelLattice

**Responsibility:** Graph integration trait extending `IGraph` and `ILabelLattice` with label-filtered graph operations.

**Methods:**

```kotlin
interface TraitLabelLattice<N : AbcNode, E : AbcEdge> : IGraph<N, E>, ILabelLattice {

    fun addEdge(withID: EdgeID, label: Label): E
    fun delEdge(whoseID: EdgeID, label: Label)

    fun getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>
    fun getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>
    fun getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
    fun getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
    fun getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
    fun getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(withID, label)` | Creates edge and assigns label | `withID`: EdgeID; `label`: Label | `E` | `EntityNotExistException` if src/dst missing |
| `delEdge(whoseID, label)` | Removes label from edge; if no labels remain, deletes the edge | `whoseID`: EdgeID; `label`: Label | — | — |
| `getChildren` / `getParents` / `getDescendants` / `getAncestors` (with label) | Same as `IGraph` traversal but only visits edges visible under the given label | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<N>` | — |

**Edge visibility rule:** An edge is **visitable** under label `by` if at least one of its labels `l` satisfies `by == l || by > l`. Among all visitable labels, those covered by a higher visitable label are excluded (avoids double-counting in multi-label edges).

Since `TraitLabelLattice : ILabelLattice`, all member extensions (`AbcEdge.labels`, `Label.compareTo`, etc.) are in scope within the default method bodies — no `with(lattice) { ... }` wrapper is needed.

---

### AbcLabelGraph

**Responsibility:** Abstract base bridging `TraitLabelLattice` to `AbcBasicGraph` via composition with `latticeImpl`.

Kotlin does not support delegating **member extension properties** via `by`. `AbcLabelGraph` solves this by holding a `latticeImpl` and manually forwarding each member extension:

```kotlin
abstract class AbcLabelGraph<N : AbcNode, E : AbcEdge>(
    name: String,
    protected val latticeImpl: AbcBasicLabelLattice = DefaultLatticeImpl()
) : AbcBasicGraph<N, E>(name), TraitLabelLattice<N, E> {

    override val allLabels get() = latticeImpl.allLabels

    override var Label.parents: Map<String, Label>
        get() = with(latticeImpl) { this@parents.parents }
        set(value) = with(latticeImpl) { this@parents.parents = value }

    override val Label.ancestors: Sequence<Label>
        get() = with(latticeImpl) { this@ancestors.ancestors }

    override var Label.changes: Set<EdgeID>
        get() = with(latticeImpl) { this@changes.changes }
        set(value) = with(latticeImpl) { this@changes.changes = value }

    override var AbcEdge.labels: Set<Label>
        get() = with(latticeImpl) { this@labels.labels }
        set(value) = with(latticeImpl) { this@labels.labels = value }

    override fun Label.compareTo(other: Label): Int? =
        with(latticeImpl) { compareTo(other) }

    override fun storeLattice(into: IStorage) = latticeImpl.storeLattice(into)
    override fun loadLattice(from: IStorage)  = latticeImpl.loadLattice(from)

    override fun close() {
        latticeImpl.storeLattice(storage)   // auto-persist on close
        super.close()
    }
}
```

---

### AbcLabelSimpleGraph / AbcLabelMultiGraph

**Responsibility:** Concrete label graph variants combining label trait with simple/multi edge policy.

```kotlin
abstract class AbcLabelSimpleGraph<N : AbcNode, E : AbcEdge>(name: String)
    : AbcLabelGraph<N, E>(name) {
    // addEdge enforces at-most-one-edge per (src, dst) before delegating
}
```

Concrete graph classes extend these and only implement `newNodeObj`/`newEdgeObj`.

---

### Example Usage

```kotlin
class MyNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
    override val type = object : AbcNode.Type { override val name = "item" }
}
class MyEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
    override val type = object : AbcEdge.Type { override val name = "link" }
}

class MyGraph(name: String) : AbcLabelSimpleGraph<MyNode, MyEdge>(name) {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

val graph = MyGraph("catalog")

// Define label hierarchy (ILabelLattice member extension in scope via graph)
val public    = Label("public")
val protected = Label("protected")
val private   = Label("private")

public.parents    = mapOf("sub" to protected)
protected.parents = mapOf("sub" to private)

// Add nodes and edges with labels
graph.addNode(NodeID("a"))
graph.addNode(NodeID("b"))
graph.addNode(NodeID("c"))

val eid1 = EdgeID(NodeID("a"), NodeID("b"), "link")
val eid2 = EdgeID(NodeID("b"), NodeID("c"), "link")

graph.addEdge(eid1, public)     // visible under public, protected, private
graph.addEdge(eid2, private)    // visible only under private

// Label-filtered traversal
graph.getChildren(NodeID("a"), public).toList()    // [b]   (eid1 is public-visible)
graph.getChildren(NodeID("b"), public).toList()    // []    (eid2 is private-only)
graph.getChildren(NodeID("b"), private).toList()   // [c]   (private ≥ private)

graph.getDescendants(NodeID("a"), public).toList()  // [b]
graph.getDescendants(NodeID("a"), private).toList() // [b, c]

// Remove a label without deleting the edge
graph.delEdge(eid1, public)   // eid1 now has no labels → edge deleted

// Auto-persist and close
graph.close()   // storeLattice(storage) called automatically
```

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `IllegalArgumentException` | Setting parents on `JgraphtLatticeImpl` after already set; restoring lattice from storage with `JgraphtLatticeImpl` when parents conflict |
| `EntityNotExistException` | `addEdge` with missing src/dst node (from `IGraph`) |

`delEdge` on non-existent edge is a no-op.

---

## Validation Rules

### JgraphtLatticeImpl

- Parents are immutable once set; reassignment throws `IllegalArgumentException`
- `loadLattice` rejects conflicting parent relationships with `IllegalArgumentException`

### TraitLabelLattice

- `addEdge(withID, label)` requires both src and dst nodes to exist
- `delEdge(whoseID, label)` removes only the specified label; edge is deleted only when no labels remain

### AbcLabelSimpleGraph

- At most one edge per directed `(src, dst)` pair (same constraint as `AbcSimpleGraph`)

### Label

- `Label.INFIMUM` is below all labels; `Label.SUPREMUM` is above all labels — these are structural bounds and should not be assigned to edges

### Known Issues in Current Implementation

| Location | Issue |
|----------|-------|
| `AbcBasicLabelLattice.compareTo` | `compareTo` logic is inverted in the first ancestor-check loop — `this > other` cases are never detected. The condition `if (label != other)` should be `if (label != this)`. |
| `AbcBasicLabelLattice.storeLattice` | Calls old `setMeta("key" to value)` vararg signature; must be updated to `setMeta("key", value)` per new `IStorage` contract. |
| `Label.kt` context receiver functions | All `context(ILabelLattice)` extension functions on `IGraph` use the old entity-first `getEdge(from: AbcNode, ...)` signatures; must be migrated to ID-first after `TraitLabelLattice` is in place. |
