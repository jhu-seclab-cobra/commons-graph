# Domain Model

## Entities

### Node

A [Node](glossary/glossary-graph.md#node) is registered under an application-supplied identifier and carries typed properties.

- Existence: a node exists when registered in a graph. Deleting a node removes it and all incident edges.
- Identity: two nodes are equal when they have the same application-supplied identifier.

### Edge

An [Edge](glossary/glossary-graph.md#edge) carries typed properties and optional `Label` assignments.

- Existence: an Edge exists only after it has been added, while both endpoints remain registered in the Graph and the Edge has not been deleted or hidden by Layered Storage.
- Identity at graph level: the `(src, dst, tag)` triple.
- Storage identity mapping: [Entity design](design-entity.md).

### `Label`

[`Label`](glossary/glossary-labels.md#label) assignments select the visibility of an Edge.

- `Label.INFIMUM`: greatest lower bound — below all labels in the Label hierarchy. An edge may carry it: such an edge is visible under every label, and a query under `Label.INFIMUM` sees only the edges that carry it.
- `Label.SUPREMUM`: least upper bound — above all labels in the Label hierarchy. Structural bound only; never assigned to edges.
- A label's ordering is not intrinsic — it is defined by the Label hierarchy.

---

## Relations

| Relation | Direction | Cardinality | Meaning |
|----------|-----------|-------------|---------|
| Node → Edge (outgoing) | Node to Edge | 1:N | Node is the source of the edge |
| Edge → Node (incoming) | Edge to Node | N:1 | Edge points to its destination node |
| Edge → Label | Edge to Label | N:M | Edge is visible under assigned labels |
| Label → Label (parent) | Child to Parent | N:M (named) | Parent relationship in the Label hierarchy; each parent link has a name |

---

## State Model

### Graph lifecycle

| State | Trigger | Guard | Target |
|-------|---------|-------|--------|
| Empty | add node | identifier not taken | Has nodes |
| Has nodes | add edge | source and destination exist | Has edges |
| Has edges | delete node | node exists | Edges cascade-deleted; may return to Empty |
| Any | delete edge | -- | Edge removed (no-op if absent) |
| Any | delete node | -- | Node and incident edges removed (no-op if absent) |

### Layered Storage lifecycle

| State | Trigger | Guard | Target |
|-------|---------|-------|--------|
| Active only (1 layer) | freeze | -- | Active + frozen (2 layers) |
| Active + frozen (2 layers) | freeze | -- | Active + frozen (2 layers); old frozen merged with active into new frozen |
| Any | write operation | -- | Targets Active layer only |
| Any | read operation | -- | Selects the entity image and property family under Cross-Structure Contracts |
| Active + frozen | delete | Entity in Active layer only | Entity deleted |
| Active + frozen | delete edge | Edge in Frozen layer (promoted or not) | Edge masked as deleted until the next revert; removed at the next freeze |
| Active + frozen | delete node | Node in Frozen layer (promoted or not) | Rejected |
| Active + frozen | revert (clear Active layer) | -- | Active + frozen; every read equals the read right after the last freeze |

---

## Invariants

- Every edge has exactly one source node and one destination node. Both must exist in the graph.
- Deleting a node removes all incident edges. No orphan edges.
- In a simple graph variant, at most one edge exists per directed `(src, dst)` pair, regardless of tag.
- In a multiple-edge graph variant, multiple edges between the same `(src, dst)` pair are allowed if tags differ.
- The application-supplied Node identifier is unique within a graph. Adding a duplicate is rejected.
- Edge `(src, dst, tag)` is unique within a graph. Adding a duplicate is rejected.
- `Label.SUPREMUM` is never assigned to edges. It is a structural bound only.
- Label hierarchy is a DAG. No cycles in parent relationships.
- Layer count is always 1 (active only) or 2 (active + one frozen). Merge-on-freeze maintains this bound.
- Storage integer IDs are stable across Freeze operations and are not reused between clear calls; a Graph or Label hierarchy instance whose Storage was cleared is not reused.
- After a revert, every Graph and Label hierarchy over the storage holds exactly the entities the storage holds: no registered node, cached edge, or label refers to a removed ID, and a graph's label carrier index lists every edge it owns under each label the edge carries.

---

## Merge Laws

Terms: [Property merge](glossary/glossary-merge.md#property-merge) and [Owner union](glossary/glossary-merge.md#owner-union).

A caller may merge the prospective changes of several writers on top of the current value, the base. A writer's change is its before-image (the value it read) and its after-image (the value it wants); an absent key and a present value differ, and a change compares only with its own writer's before-image. The laws below read no storage and run only when a caller asks; they change no write path of a graph or a storage.

- A merge result is a function of the collection of changes, never of the order of the writers. No law selects a winner by arrival order.
- Labels of an edge: each writer adds the labels its after-image holds beyond its before-image and removes the labels its before-image held beyond its after-image. The result is the base plus all additions minus all removals. A label one writer adds and another removes fails the whole set; a label a writer leaves unchanged keeps its base state.
- Owners of a node: the result is the base owner set plus every owner a writer added to its own before-image. The set only grows, so the result is the same in either order and a stale owner a writer merely carried is not re-added.
- Property default law: the writers that change the property must write equal values, and the base must equal that value or every such writer's before-image. Equal writes are idempotent; a differing write, a delete against a set, or a write against a base another writer moved fails the whole set.
- Immutable property: a set value stays; a write of another value or a delete fails, and an unset property takes one value under the default law. The node ID property is immutable.
- A graph variant may declare another pure law for a property; it replaces the default law for that property only.
- Edge identity: additions with one `(src, dst, tag)` triple coalesce into one edge. A graph may declare further unique keys, each a name and key components; two distinct edges, present or added, sharing the components of one declaration fail the whole set. A multiple-edge graph declares only the triple; a simple graph also declares the `(src, dst)` pair, the key of its one-edge-per-pair invariant.

---

## Cross-Structure Contracts

### Graph ↔ Storage

The graph layer maps node identifiers to storage integer IDs. Storage operates on integer IDs only and has no knowledge of domain types, labels, or graph policy. Multiple graph instances may share a single storage; each graph maintains its own node registry.

**Shared-storage invariants:**

- A node in storage can be registered by multiple graphs. Each graph holds its own typed view of the same storage row, sharing all properties.
- A row carries an owner set naming every graph that added or claimed it. A graph registers a row only when the set holds its ID; a row with no owner set belongs to no graph. A revert drops an owner mark that a claim wrote on a promoted frozen row, and the claiming graph drops the registry entry with it, so the registry and a rebuild agree after every revert.
- Edge queries return only edges whose both endpoints are registered in the querying graph. Edges belonging to other graphs sharing the same storage are excluded (edge isolation).

### Graph ↔ Label hierarchy

The Label hierarchy is backed by a dedicated storage instance separate from the main graph storage. Labels are registered as nodes in the Label hierarchy Storage; parent relationships as edges. The graph layer queries the hierarchy to filter edge visibility.

### Edge Visibility Rule

An edge is visible under label `by` if at least one of its labels `l` satisfies `by == l` or `by > l` in the Label hierarchy (pure covering). Covered labels never shadow one another: every edge carrying at least one covered label is visible. `Label.SUPREMUM` bypasses filtering and sees every edge, labeled or not. `Label.INFIMUM` is below all labels, so an edge carrying it is visible under every label and a query under `Label.INFIMUM` sees only the edges carrying it. Edges with no labels are otherwise visible only when no label filter is applied.

### Property families in Layered Storage

A configured property family groups an anchor property and the properties with its member prefix on one edge tag. On a promoted Frozen layer Edge, an explicit Active layer edit of the anchor makes each family member read its explicit Active layer edit or absence; an unedited anchor leaves the Frozen layer fallback available. Later anchor writes preserve other explicit Active layer member edits. Properties outside that family use their explicit Active layer edits before the Frozen layer value. Freeze preserves this same composed image, and revert discards all Active layer edits and their family ownership.
