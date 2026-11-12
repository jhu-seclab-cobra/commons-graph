# Layered Storage algorithms

State rules: [model](model.md#property-families-in-layered-storage). Types: [Storage design](design-storage.md). Terms: [Layered Storage](glossary/glossary-storage.md#layered-storage).

## Layered Query Resolution

### Problem

Given a Layered Storage with one Active layer and at most one Frozen layer, resolve property reads, adjacency queries, and containment checks. Correct when active node rows and active-only edge rows are authoritative, while a promoted frozen edge's explicit property edits override its frozen row one name at a time.

### Steps — Property read

1. Reject an absent or tombstoned entity with the missing-entity error before selecting a property image.
2. If an active node or active-only edge holds the entity, read its active property. For a promoted frozen edge, use its explicit edit for that name, including a deletion marker; otherwise, if its family anchor has an explicit active edit, return absent; otherwise read the frozen property through its translated ID.
3. For a frozen-only entity, read from the Frozen layer (translating global ID to frozen-local ID).


### Steps — Adjacency query

1. Retrieve edge IDs from Active layer for the node.
2. Retrieve edge IDs from Frozen layer (translating to global IDs), minus tombstoned edges.
3. Return the union of both sets.

### Steps — Edge delete

1. Active-only edge: remove it.
2. Frozen edge (promoted or not): remove the active copy when present, then record a tombstone. Containment, ID enumeration, and property/structure reads treat a tombstoned edge as absent.

### Steps — Property write (cross-layer)

1. If entity exists in Active layer, update directly.
2. If an edge exists only in the Frozen layer, promote its structure and record only the named edits in active storage; a null edit masks the inherited property. A frozen node still promotes its complete property row.

### Steps — Freeze

1. Transfer frozen-layer data into a new storage instance.
2. Transfer active-layer data into the same instance; an active node row replaces its frozen row, while a promoted edge composes its explicit edits over the frozen row, including deletions and the property-family rule of the model. Tombstoned edges are not transferred.
3. Replace old Frozen layer with merged storage. Clear Active layer.

### Steps — Revert (`clearActiveLayer`)

1. Removed nodes and edges = active-layer ones absent from the Frozen layer. Restored nodes = active-layer nodes present in the Frozen layer (promoted). Restored edges = active-layer edges present in the Frozen layer (promoted) and tombstoned edges. Edge structures before = the structures of the removed edges and the promoted edges; a tombstoned edge was invisible, so it has none.
2. Clear the Active layer, tombstones included.
3. Notify each revert listener once with the `RevertNotice`. A graph repairs in a fixed order. First it reconciles its node registry: it evicts the removed nodes and edge wrappers; for each restored node it reads the owner set and the node ID property of the row, registers the node when the set holds the graph's ID and it is missing, and evicts it when the set no longer holds the ID. Then it passes the restored edges, with the edges incident to every node it newly registered, to its edge-derived state; a labeled graph re-records the final labels of those edges in its label carrier index (`spec-poset-label-index.md`). A poset evicts removed label nodes and drops closure rows that the notice shows stale (`spec-poset.md` Maintain closure). A claim made since the freeze is undone with the active copy that carried its mark, and the next claim marks the row again.

### Invariants

- An active node or active-only edge reads its active property set. A promoted edge reads its explicit edits over frozen properties; a deleted name stays absent.
- Promotion copies frozen node properties. Promoted frozen edges retain only their explicit property edits; whole-row reads compose the same image a complete active copy would hold.
- Frozen layer nodes cannot be deleted. A deleted frozen edge is masked by a tombstone, never removed from the Frozen layer.
- After a revert, storage reads equal the reads right after the last freeze, and every registered graph and poset cache holds only IDs that exist; a graph registers a node only while the owner set of its row holds the graph's ID, and its label carrier index holds a candidate for every label of every edge it owns.
- Layer count is always 1 or 2. Global IDs are stable across freezes.

### Termination

Property read and adjacency query check at most two layers. Property write updates one layer. Freeze transfers all data — bounded by total entity count.

### Complexity

- A named read of an in-memory row with P properties costs O(P), since the row scans its own names; sparse promoted-edge edits add expected O(1) map access and at most one family-anchor lookup, whose string work depends on the property and declaration lengths.
- Adjacency resolution costs O(A + F) time and result space for A Active layer and F Frozen layer incident edge IDs.
- Freeze visits every visible entity and copied property; its cost includes value serialization or copying and the destination backend’s insert costs, which need not be constant.
- Revert visits active nodes, active edges and tombstones, then invokes each listener; graph listeners read owner sets and labels of restored rows, and hierarchy listeners may invalidate their closure. The cost includes these reads, incident-edge enumeration and listener-specific work.

### Example

For edge tag `call`, configure family `(call, p_, labels)`, add an edge with `labels = [L]` and `p_x = 1`, then Freeze. Writing only `labels = [M]` into the Active layer makes the visible `p_x` absent; writing `p_x = 2` makes it 2. Revert restores `[L]` and 1, while Freeze after the edits retains `[M]` and 2. A deleted Frozen layer Edge is absent in the visible view, remains readable through the explicit Frozen layer snapshot, and reappears after revert.
