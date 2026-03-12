# Label System Design

## Design Overview

- **Classes**: `Label`, `IPartialOrderSet`, `AbcMultipleGraph` (label integration), `AbcEdge` (label storage)
- **Relationships**: `IPartialOrderSet` defines the poset contract; `AbcMultipleGraph` implements `IPartialOrderSet` with write-through persistence to `IStorage` metadata; `AbcEdge.labels` is a public property storing label assignments
- **Abstract**: `IPartialOrderSet` (implemented by `AbcMultipleGraph`)
- **Exceptions**: `EntityNotExistException` raised by `addEdge` on missing src/dst node
- **Dependency roles**: Data holders: `Label`. Orchestrator: `AbcMultipleGraph` (bridges lattice to graph). Helpers: `IStorage` (persistence of lattice metadata).

The label system provides **label-based edge visibility** integrated into `IGraph`, `IPartialOrderSet`, and `AbcMultipleGraph`. The poset structure over `Label` values is defined by `IPartialOrderSet`, label assignment to edges is a property on `AbcEdge`, and label-filtered graph traversal methods are declared on `IGraph`. `AbcMultipleGraph` implements both interfaces with **write-through persistence** -- all mutations to `Label.parents` and `Label.changes` immediately write to `IStorage` metadata. Label-filtered methods filter edges by visibility: an edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l` or `by > l` in the poset hierarchy. Non-label overloads default to `Label.SUPREMUM`, which sees all edges.

---

## Class / Type Specifications

### Label

**Responsibility:** Plain value object representing a label in the partial-order structure.

**State / Fields:**

```kotlin
data class Label(val core: String) {
    constructor(strVal: StrVal) : this(strVal.core)
    companion object {
        val INFIMUM  = Label("infimum")   // Greatest Lower Bound — below all labels
        val SUPREMUM = Label("supremum")  // Least Upper Bound   — above all labels
    }
}
```

A `Label`'s ordering is not intrinsic -- it is defined by the lattice structure. `INFIMUM` and `SUPREMUM` are special sentinel bounds and should not be assigned to edges.

---

### IPartialOrderSet

**Responsibility:** Contract for a label partial-order set (poset) controlling edge visibility.

**Methods:**

```kotlin
interface IPartialOrderSet {
    val allLabels: Set<Label>

    var Label.parents: Map<String, Label>
    val Label.ancestors: Sequence<Label>
    var Label.changes: Set<EdgeID>

    fun Label.compareTo(other: Label): Int?
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `allLabels` | All labels registered in the poset, including `INFIMUM` and `SUPREMUM` | -- | `Set<Label>` | -- |
| `Label.parents` (get/set) | Reads/writes typed parent relationships for a label | -- | `Map<String, Label>` | -- |
| `Label.ancestors` | All ancestor labels traversing upwards through parent hierarchy via BFS | -- | `Sequence<Label>` | -- |
| `Label.changes` (get/set) | Reads/writes the set of edge IDs whose label set was modified involving this label | -- | `Set<EdgeID>` | -- |
| `Label.compareTo(other)` | Compares two labels in the poset | `other`: Label | `Int?` -- 0 if equal, positive if this > other, negative if this < other, null if incomparable | -- |

All properties except `allLabels` are **member extensions** -- they are only accessible within a scope that provides an `IPartialOrderSet` receiver.

---

### AbcEdge.labels

**Responsibility:** Stores the set of visibility labels assigned to an edge.

```kotlin
var labels: Set<Label>
```

`labels` is a public property on `AbcEdge`. Getting reads from a `ListVal` property named `"labels"` on the edge via storage. Setting overwrites it and is not automatically tracked in `Label.changes` -- callers (e.g., `AbcMultipleGraph.addEdge(withID, label)`) are responsible for updating change records.

---

### Label-filtered methods on IGraph

These methods are declared on `IGraph` alongside the non-label overloads:

```kotlin
fun addEdge(withID: EdgeID, label: Label): E
fun delEdge(whoseID: EdgeID, label: Label)

fun getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>
fun getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>
fun getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
fun getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
fun getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
fun getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(withID, label)` | Creates edge (or reuses existing) and assigns label; records edge in `label.changes` | `withID`: EdgeID; `label`: Label | `E` | `EntityNotExistException` if src/dst missing |
| `delEdge(whoseID, label)` | Removes label from edge; removes edge from `label.changes`; if no labels remain, deletes the edge; no-op if edge does not exist | `whoseID`: EdgeID; `label`: Label | -- | -- |
| `getOutgoingEdges` / `getIncomingEdges` (with label) | Same as non-label overloads but only returns edges visible under the given label | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<E>` | -- |
| `getChildren` / `getParents` (with label) | Returns adjacent nodes via label-filtered outgoing/incoming edges | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<N>` | -- |
| `getDescendants` / `getAncestors` (with label) | BFS traversal of all reachable nodes via label-filtered edges | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<N>` | -- |

**Edge visibility rule:** An edge is **visitable** under label `by` if at least one of its labels `l` satisfies `by == l || by > l`. Among all visitable labels, those covered by a higher visitable label are excluded. `Label.SUPREMUM` sees all edges.

---

### AbcMultipleGraph (label implementation)

**Responsibility:** Implements `IPartialOrderSet` with write-through persistence to `IStorage` metadata.

All mutations to `Label.parents` and `Label.changes` immediately write to `IStorage` metadata via `setMeta`. `allLabels` is derived by scanning metadata key names matching the parents prefix pattern. `Label.parents` setter also clears the comparison cache.

**Persistence format:**

Lattice metadata is stored using prefixed keys in `IStorage` metadata:

| Key pattern | Content |
|-------------|---------|
| `__lp_<labelName>__` | `MapVal` of `relType -> parentName` (parent relationships for a label) |
| `__lc_<labelName>__` | `ListVal` of serialized `EdgeID` values (change-tracked edges for a label) |

`allLabels` is derived by scanning metadata keys matching `__lp_*__`, plus `INFIMUM` and `SUPREMUM`.

**Comparison cache:** `compareTo` results are cached in a `MutableMap<Pair<Label, Label>, Int?>`. The cache is cleared whenever `Label.parents` is set.

**`close()` behavior:** Clears internal wrapper object caches only. Lattice state does not require explicit persistence on close because all mutations are write-through.

---

### Example Usage

```kotlin
class MyNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
    override val type = object : AbcNode.Type { override val name = "item" }
}
class MyEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
    override val type = object : AbcEdge.Type { override val name = "link" }
}

class MyGraph : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

val graph = MyGraph()

// Define label hierarchy (IPartialOrderSet member extensions in scope via graph receiver)
with(graph) {
    val public    = Label("public")
    val protected = Label("protected")
    val private   = Label("private")

    public.parents    = mapOf("sub" to protected)
    protected.parents = mapOf("sub" to private)

    // Add nodes and edges with labels
    addNode(NodeID("a"))
    addNode(NodeID("b"))
    addNode(NodeID("c"))

    val eid1 = EdgeID(NodeID("a"), NodeID("b"), "link")
    val eid2 = EdgeID(NodeID("b"), NodeID("c"), "link")

    addEdge(eid1, public)     // visible under public, protected, private
    addEdge(eid2, private)    // visible only under private

    // Label-filtered traversal
    getChildren(NodeID("a"), public).toList()    // [b]   (eid1 is public-visible)
    getChildren(NodeID("b"), public).toList()    // []    (eid2 is private-only)
    getChildren(NodeID("b"), private).toList()   // [c]   (private >= private)

    getDescendants(NodeID("a"), public).toList()  // [b]
    getDescendants(NodeID("a"), private).toList() // [b, c]

    // Remove a label without deleting the edge
    delEdge(eid1, public)   // eid1 now has no labels -> edge deleted
}

// Clears internal caches only (lattice state is already persisted via write-through)
graph.close()
```

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | `addEdge` with missing src/dst node (from `IGraph`) |

`delEdge(whoseID, label)` on a non-existent edge is a no-op.

---

## Validation Rules

### Label

- `Label.INFIMUM` is below all labels; `Label.SUPREMUM` is above all labels -- these are structural bounds and should not be assigned to edges

### IGraph (label-filtered operations)

- `addEdge(withID, label)` requires both src and dst nodes to exist
- `delEdge(whoseID, label)` removes only the specified label; the edge is deleted only when no labels remain
- `delEdge(whoseID, label)` on a non-existent edge is a no-op
