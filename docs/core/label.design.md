# Label System Design

## Design Overview

- **Classes**: `Label`, `LabelID`, `IPoset`, `AbcMultipleGraph` (label integration), `AbcEdge` (label storage)
- **Relationships**: `IPoset` defines the poset contract; `AbcMultipleGraph` implements `IPoset` using a dedicated `IStorage` for the label DAG; `AbcEdge.labels` is a public property storing label assignments
- **Abstract**: `IPoset` (implemented by `AbcMultipleGraph`)
- **Exceptions**: `EntityNotExistException` raised by `addEdge` on missing src/dst node
- **Dependency roles**: Data holders: `Label`. Orchestrator: `AbcMultipleGraph` (bridges poset to graph). Helpers: `IStorage` (poset store for label DAG persistence).

The label system provides **label-based edge visibility** integrated into `IGraph`, `IPoset`, and `AbcMultipleGraph`. The poset structure over `Label` values is defined by `IPoset`, label assignment to edges is a property on `AbcEdge`, and label-filtered graph traversal methods are declared on `AbcMultipleGraph` (concrete class). `AbcMultipleGraph` implements both interfaces with a **dedicated poset `IStorage`** — labels are stored as nodes (using `Label.core` as the node ID) and parent relationships are stored as edges (`child → parent`, edge tag = relationship name), providing native adjacency indexing for hierarchy traversal. Label-filtered methods filter edges by visibility: an edge is visitable under label `by` if at least one of its labels `l` satisfies `by == l` or `by > l` in the poset hierarchy. Among all visitable labels, those covered by a higher visitable label are excluded. Non-label overloads see all edges.

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
        val INFIMUM: Label   // Greatest Lower Bound — below all labels
        val SUPREMUM: Label  // Least Upper Bound — above all labels
    }
}
```

A `Label`'s ordering is not intrinsic — it is defined by the poset structure. `INFIMUM` and `SUPREMUM` are special sentinel bounds and should not be assigned to edges.

---

### IPoset

**Responsibility:** Contract for a label partial-order set (poset) controlling edge visibility.

**Methods:**

```kotlin
interface IPoset {
    val allLabels: Set<Label>

    var Label.parents: Map<String, Label>
    val Label.ancestors: Sequence<Label>
    var Label.changes: Set<String>

    fun Label.compareTo(other: Label): Int?
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `allLabels` | All labels registered in the poset, including `INFIMUM` and `SUPREMUM` | — | `Set<Label>` | — |
| `Label.parents` (get/set) | Reads/writes typed parent relationships for a label | — | `Map<String, Label>` | — |
| `Label.ancestors` | All ancestor labels traversing upwards through parent hierarchy via BFS | — | `Sequence<Label>` | — |
| `Label.changes` (get/set) | Reads/writes the set of edge ID strings whose label set was modified involving this label | — | `Set<String>` | — |
| `Label.compareTo(other)` | Compares two labels in the poset | `other`: Label | `Int?` — 0 if equal, positive if this > other, negative if this < other, null if incomparable | — |

All properties except `allLabels` are **member extensions** — they are only accessible within a scope that provides an `IPoset` receiver.

---

### AbcEdge.labels

**Responsibility:** Stores the set of visibility labels assigned to an edge.

```kotlin
var labels: Set<Label>
```

`labels` is a public property on `AbcEdge`. Getting reads from a `ListVal` property named `"labels"` on the edge via the graph store. Setting overwrites it and is not automatically tracked in `Label.changes` — callers (e.g., `AbcMultipleGraph.addEdge(src, dst, tag, label)`) are responsible for updating change records.

---

### Label-filtered methods on AbcMultipleGraph

These methods are declared on `AbcMultipleGraph` as concrete methods (not inherited from `IGraph`):

```kotlin
fun addEdge(src: NodeID, dst: NodeID, tag: String, label: Label): E
fun delEdge(src: NodeID, dst: NodeID, tag: String, label: Label)

fun getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>
fun getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>
fun getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
fun getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
fun getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
fun getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, tag, label)` | Creates edge (or reuses existing) and assigns label; records edge in `label.changes` | `src`, `dst`: NodeID; `tag`: String; `label`: Label | `E` | `EntityNotExistException` if src/dst missing |
| `delEdge(src, dst, tag, label)` | Removes label from edge; removes edge from `label.changes`; if no labels remain, deletes the edge; no-op if edge does not exist | `src`, `dst`: NodeID; `tag`: String; `label`: Label | — | — |
| `getOutgoingEdges` / `getIncomingEdges` (with label) | Same as non-label overloads but only returns edges visible under the given label | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<E>` | — |
| `getChildren` / `getParents` (with label) | Returns adjacent nodes via label-filtered outgoing/incoming edges | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<N>` | — |
| `getDescendants` / `getAncestors` (with label) | BFS traversal of all reachable nodes via label-filtered edges | `of`: NodeID; `label`: Label; `cond`: optional edge predicate | `Sequence<N>` | — |

**Edge visibility rule:** An edge is **visitable** under label `by` if at least one of its labels `l` satisfies `by == l || by > l`. Among all visitable labels, those covered by a higher visitable label are excluded. `Label.SUPREMUM` sees all edges.

---

### AbcMultipleGraph (label implementation)

**Responsibility:** Implements `IPoset` using a dedicated `posetStorage: IStorage` instance.

The poset store uses `Label.core` as the node ID directly (no meta properties needed):

| Poset concept | IStorage mapping |
|---------------|-------------------|
| Label `L` | Node with ID = `L.core` |
| `L.parents = mapOf("sub" to P)` | Edge from `L.core` to `P.core`, tag = `"sub"` |
| `L.changes` | Node property `"changes"` as `ListVal` of `StrVal` |

`allLabels` is derived from `posetStorage.nodeIDs` (reading node IDs directly as label names) plus `INFIMUM` and `SUPREMUM`.

`labelIdCache: HashMap<String, NodeID>` maps label names to poset storage node IDs, populated eagerly on first access. Since `Label.core` is used directly as the node ID, the cache maps label name → node ID (same value).

`Label.parents` getter reads outgoing edges from the label node — each edge's destination is a parent label, the edge tag is the relationship name. Setter removes all existing parent edges and creates new ones.

`Label.ancestors` performs BFS over parent edges using `posetStorage.getOutgoingEdges` + `posetStorage.getEdgeDst`, yielding ancestor labels transitively.

`Label.compareTo(other)` performs bidirectional reachability queries via `ancestors` traversal. Results are cached in a runtime `MutableMap<Pair<Label, Label>, Int?>`. The cache is cleared whenever `Label.parents` is set.

`Label.changes` getter/setter reads/writes a `ListVal` node property containing `StrVal` edge ID strings.

`close()` clears internal caches only. Poset state is persisted in the poset store and requires no explicit persist step.

---

### Example Usage

```kotlin
class MyNode : AbcNode() {
    override val type = object : AbcNode.Type { override val name = "item" }
}
class MyEdge : AbcEdge() {
    override val type = object : AbcEdge.Type { override val name = "link" }
}

class MyGraph : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override val posetStorage = NativeStorageImpl()
    override fun newNodeObj() = MyNode()
    override fun newEdgeObj() = MyEdge()
}

val graph = MyGraph()

// Define label hierarchy (IPoset member extensions in scope via graph receiver)
with(graph) {
    val public    = Label("public")
    val protected = Label("protected")
    val private   = Label("private")

    public.parents    = mapOf("sub" to protected)
    protected.parents = mapOf("sub" to private)

    // Add nodes and edges with labels
    addNode("a")
    addNode("b")
    addNode("c")

    addEdge("a", "b", "link", public)     // visible under public, protected, private
    addEdge("b", "c", "link", private)    // visible only under private

    // Label-filtered traversal
    getChildren("a", public).toList()    // [b]   (edge is public-visible)
    getChildren("b", public).toList()    // []    (edge is private-only)
    getChildren("b", private).toList()   // [c]   (private >= private)

    getDescendants("a", public).toList()  // [b]
    getDescendants("a", private).toList() // [b, c]

    // Remove a label without deleting the edge
    delEdge("a", "b", "link", public)   // edge now has no labels -> edge deleted
}

// Clears internal caches only (poset state is in the poset store)
graph.close()
```

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | `addEdge` with missing src/dst node (from `IGraph`) |

`delEdge(src, dst, tag, label)` on a non-existent edge is a no-op.

---

## Validation Rules

### Label

- `Label.INFIMUM` is below all labels; `Label.SUPREMUM` is above all labels — these are structural bounds and should not be assigned to edges

### AbcMultipleGraph (label-filtered operations)

- `addEdge(src, dst, tag, label)` requires both src and dst nodes to exist
- `delEdge(src, dst, tag, label)` removes only the specified label; the edge is deleted only when no labels remain
- `delEdge(src, dst, tag, label)` on a non-existent edge is a no-op
