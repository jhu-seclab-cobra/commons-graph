# Domain Model

## Entities

### Node

A vertex in the graph identified by a user-provided string (`NodeID`). Carries typed properties.

- Existence: a node exists when registered in a graph. Deleting a node removes it and all incident edges.
- Identity: two nodes are equal when they have the same `NodeID`.

### Edge

A directed connection between two nodes identified by the triple `(src: NodeID, dst: NodeID, tag: String)`. Carries typed properties and optional label assignments.

- Existence: an edge exists when both its source and destination nodes exist in the same graph.
- Identity at graph level: the `(src, dst, tag)` triple.
- Identity at storage level: auto-generated opaque integer.

### Label

A marker controlling edge visibility. Wraps a string identifier.

- `INFIMUM`: greatest lower bound — below all labels in the poset. Structural bound only; never assigned to edges.
- `SUPREMUM`: least upper bound — above all labels in the poset. Structural bound only; never assigned to edges.
- A label's ordering is not intrinsic — it is defined by the poset structure.

---

## Relations

| Relation | Direction | Cardinality | Meaning |
|----------|-----------|-------------|---------|
| Node → Edge (outgoing) | Node to Edge | 1:N | Node is the source of the edge |
| Edge → Node (incoming) | Edge to Node | N:1 | Edge points to its destination node |
| Edge → Label | Edge to Label | N:M | Edge is visible under assigned labels |
| Label → Label (parent) | Child to Parent | N:M (named) | Parent relationship in the label hierarchy; each parent link has a name |

---

## State Model

### Graph lifecycle

| State | Trigger | Guard | Target |
|-------|---------|-------|--------|
| Empty | `addNode` | ID not taken | Has nodes |
| Has nodes | `addEdge` | src and dst exist | Has edges |
| Has edges | `delNode` | node exists | Edges cascade-deleted; may return to Empty |
| Any | `delEdge` | -- | Edge removed (no-op if absent) |
| Any | `delNode` | -- | Node and incident edges removed (no-op if absent) |

### Layered storage lifecycle

| State | Trigger | Guard | Target |
|-------|---------|-------|--------|
| Active only (1 layer) | `freeze` | -- | Active + frozen (2 layers) |
| Active + frozen (2 layers) | `freeze` | -- | Active + frozen (2 layers); old frozen merged with active into new frozen |
| Any | write operation | -- | Targets active layer only |
| Any | read operation | -- | Cascades: active first, then frozen |
| Active + frozen | delete | Entity in active layer | Entity deleted |
| Active + frozen | delete | Entity in frozen layer only | Rejected |

---

## Invariants

- Every edge has exactly one source node and one destination node. Both must exist in the graph.
- Deleting a node removes all incident edges. No orphan edges.
- In a simple graph variant, at most one edge exists per directed `(src, dst)` pair, regardless of tag.
- In a multiple-edge graph variant, multiple edges between the same `(src, dst)` pair are allowed if tags differ.
- `NodeID` is unique within a graph. Adding a duplicate is rejected.
- Edge `(src, dst, tag)` is unique within a graph. Adding a duplicate is rejected.
- `INFIMUM` and `SUPREMUM` are never assigned to edges. They are structural bounds only.
- Label hierarchy is a DAG. No cycles in parent relationships.
- Layer count is always 1 (active only) or 2 (active + one frozen). Merge-on-freeze maintains this bound.
- Storage integer IDs are stable across freeze operations.

---

## Cross-Structure Contracts

### Graph ↔ Storage

The graph layer maps node identifiers to storage integer IDs. Storage operates on integer IDs only and has no knowledge of domain types, labels, or graph policy. Multiple graph instances may share a single storage; each graph maintains its own node registry.

**Shared-storage invariants:**

- A node in storage can be registered by multiple graphs. Each graph holds its own typed view of the same storage row, sharing all properties.
- Edge queries return only edges whose both endpoints are registered in the querying graph. Edges belonging to other graphs sharing the same storage are excluded (edge isolation).

### Graph ↔ Label Hierarchy

The label hierarchy is backed by a dedicated storage instance separate from the main graph storage. Labels are registered as nodes in the poset storage; parent relationships as edges. The graph layer queries the hierarchy to filter edge visibility.

### Edge Visibility Rule

An edge is visible under label `by` if at least one of its labels `l` satisfies `by == l` or `by > l` in the poset hierarchy. `SUPREMUM` sees all labeled edges. `INFIMUM` is below all labels. Edges with no labels are visible only when no label filter is applied.
