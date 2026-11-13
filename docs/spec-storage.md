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

## Typed Selection and Freeze

### Problem

Given registered typed fields, stable logical IDs, one Active layer and at most one Frozen layer, select only requested kinds and build replacement immutable storage within a caller budget. Preserve public identity, ordered compatibility reads, family selection, deletion, reusable Freeze and revert notifications. [Open Gaps](design-gaps-storage.md#typed-storage-implementation) identifies missing implementation.

### Steps

1. Validate required capabilities and schema before construction. Enter one read guard for a read scope and activate its borrowed readers; reject mutation within that scope.
2. Resolve stable node ID to slot and select incoming/outgoing kind membership without loading unrelated rows. Return immutable selected payloads or scoped ranges; snapshot selected IDs before an interleaved write and capture legacy snapshot payloads explicitly.
3. Select layer and family presence before a cell. On first active family write start empty labels/inputs; atomically capture and publish the active family. Never supply frozen inputs for active labels.
4. On scope exit invalidate borrowed readers and release the guard on every exit path. Immutable acquired payloads remain valid.
5. For Freeze take the exclusive guard. Count visible entities, memberships and properties; include current storage, replacement arrays, indexes and workspace. Check capacity/conversion and budget before each allocation.
6. Build replacement stable-ID/slot maps, central fields, both CSR directions and nonempty kind directories. Apply active edits, property/family/metadata deletion and frozen-edge tombstones; preserve required encounter ranks and complete identity.
7. Validate endpoints, counts, directories, codecs and indexes before publication. On preflight/validation failure close new resources and retain old storage and active state. Actual out-of-memory recovery is not guaranteed.
8. Publish the sole replacement Frozen layer, reset touched active state and release obsolete resources; emit lifecycle completion after consistency and resource closure. Revert retains the algorithm above and repairs derived caches through existing notices.

### Invariants

- Compaction never changes surviving logical IDs; clear/close invalidate old handles.
- One edge record owns endpoints/kind/tag; indexes own derived selection. Raw duplicate triples remain distinct physical rows.
- Frozen arrays and admitted typed payloads are immutable; readers never write a translation cache.
- Named, whole-row and cell reads select labels and complete inputs together. Frozen-node deletion stays forbidden; frozen-edge deletion stays supported.
- Replacement is published only after validation; compatibility snapshots preserve captured content and promised encounter order.

### Termination

Reads process finite selected neighborhoods. Freeze performs finite count/fill/validation passes and finite directory sorts. Revert visits touched state and finite listener work.

### Complexity

For `r` selected edges, `b` nonempty kinds and `p` extension properties, candidate mutable selection is expected O(1+r), frozen selection O(log b+r), and untyped enumeration O(b+degree). Required ordering may add rank merging/sorting; measure its cost. Ordered vector removal costs O(selected degree) plus property/index work. Sorted extension lookup costs O(log p), insertion/removal O(p); small linear rows remain O(p).

Freeze costs O(V+E+P) counting/filling plus directory sorting, payload conversion, ordered-index construction and resource work; V/E/P count visible nodes/edges/properties. Peak space includes old+new+workspace, payloads and auxiliary indexes. Revert costs touched state plus notification/cache repair. Hash bounds are expected/amortized; collision and resize worst cases remain linear.

### Edge Cases

Empty graphs and zero-property entities require no per-entity empty container. Missing endpoints, wrong schema and overflow fail before mutation; capacity never wraps. Import retains explicit identity remapping. Repeated Freeze, sparse/dense edits, self/parallel edges, tombstones and empty active families obey the same rules.
