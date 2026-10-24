# Algorithm Specifications

## Edge Lookup by Triple

### Problem

Given `(src, dst, tag)`, find the storage edge ID or determine it is absent. Correct when the returned ID matches the unique edge with the given triple.

### Steps

1. Look up `src` in node registry to get `srcStorageId`. If absent, return absent.
2. Look up `dst` to get `dstStorageId`. If absent, return absent.
3. For each outgoing edge of `srcStorageId`, retrieve structure `(eSrc, eDst, eTag)`.
4. If `eDst == dstStorageId` and `eTag == tag`, return this edge ID.
5. If no match, return absent.

### Invariants

- At most one edge matches a given `(src, dst, tag)` triple (uniqueness from model.md).

### Termination

Outgoing edge set is finite. Exits on first match or after exhausting the set.

### Complexity

- Time: O(out-degree of src). Space: O(1).

---

## BFS Traversal (Descendants / Ancestors)

### Problem

Given a start node, a direction (outgoing for descendants, incoming for ancestors), and an optional edge condition predicate, find all transitively reachable nodes. Output: sequence of reachable nodes excluding start. Correct when every reachable node appears exactly once.

### Steps

1. Initialize a visited set with the start node's storage ID.
2. Initialize a queue with the start node ID.
3. While queue is not empty:
   a. Dequeue a node ID.
   b. Read its edges through `getOutgoingEdges` (or `getIncomingEdges`), the overridable queries of this graph, so a graph that keeps only its own edge types on a shared storage walks only those.
   c. For each edge, check the edge condition predicate. Skip if false.
   d. Resolve the opposite endpoint. Skip if not registered in this graph.
   e. If the endpoint storage ID is not in visited, add to visited and enqueue. Yield the node.
4. Terminate when queue is empty.

### Invariants

- Visited set contains every enqueued node's storage ID. No node enqueued twice.
- Only edges with both endpoints in this graph are traversed (edge isolation).

### Termination

Each node enqueued at most once (visited set guard). Terminates in at most `|V|` iterations.

### Complexity

- Time: O(V + E) where V = reachable nodes, E = edges traversed. Space: O(V).

---

## Label Visibility Filtering

### Problem

Given edges and a query label `by`, return edges visible under `by`. An edge is visible if at least one of its labels `l` satisfies `by == l` or `by > l` (pure covering). Correct when exactly the edges carrying at least one covered label are returned; covered labels never shadow one another.

### Steps

1. `SUPREMUM` as `by`: every edge passes unfiltered, labeled or not.
2. For each edge in the input sequence:
   a. Retrieve the edge's label set.
   b. Include the edge iff at least one label `l` satisfies `by == l` or `by > l` in the poset.
3. Edges with no labels: excluded by every label filter except `SUPREMUM`.

### Invariants

- An edge is included iff it carries at least one label covered by `by`. No maximality selection among covered labels.

### Termination

Input edge sequence is finite. Per edge, label set is finite. Ancestor lookup terminates (DAG). Each edge processed once.

### Complexity

- Time: O(E * L * A) where E = edges, L = labels per edge, A = ancestor lookup cost (cached). Space: O(1) beyond the streamed sequence.

---

## Layered Query Resolution

### Problem

Given a layered storage with one active layer and at most one frozen layer, resolve property reads, adjacency queries, and containment checks. Correct when the active layer is authoritative for every entity present in it, and frozen values are visible only for frozen-only entities.

### Steps — Property read

1. If the entity is present in the active layer, read from the active layer only.
2. Otherwise, read from the frozen layer (translating global ID to frozen-local ID).
3. If neither layer has the entity, raise an error.

### Steps — Adjacency query

1. Retrieve edge IDs from active layer for the node.
2. Retrieve edge IDs from frozen layer (translating to global IDs), minus tombstoned edges.
3. Return the union of both sets.

### Steps — Edge delete

1. Active-only edge: remove it.
2. Frozen edge (promoted or not): remove the active copy when present, then record a tombstone. Containment, ID enumeration, and property/structure reads treat a tombstoned edge as absent.

### Steps — Property write (cross-layer)

1. If entity exists in active layer, update directly.
2. If entity exists only in frozen layer, promote it: copy all frozen properties into the active layer, then update.

### Steps — Freeze

1. Transfer frozen-layer data into a new storage instance.
2. Transfer active-layer data into the same instance; for an entity present in both layers, the active property set replaces the frozen one wholesale. Tombstoned edges are not transferred.
3. Replace old frozen layer with merged storage. Clear active layer.

### Steps — Revert (`clearActiveLayer`)

1. Removed nodes and edges = active-layer ones absent from the frozen layer. Restored nodes = active-layer nodes present in the frozen layer (promoted). Restored edges = active-layer edges present in the frozen layer (promoted) and tombstoned edges. Edge structures before = the structures of the removed edges and the promoted edges; a tombstoned edge was invisible, so it has none.
2. Clear the active layer, tombstones included.
3. Notify each revert listener once with the `RevertNotice`. A graph repairs in a fixed order. First it reconciles its node registry: it evicts the removed nodes and edge wrappers; for each restored node it reads the owner set and the node ID property of the row, registers the node when the set holds the graph's ID and it is missing, and evicts it when the set no longer holds the ID. Then it passes the restored edges, with the edges incident to every node it newly registered, to its edge-derived state; a labeled graph re-records the final labels of those edges in its label carrier index (`spec-poset.md` Label Carrier Index). A poset evicts removed label nodes and drops closure rows that the notice shows stale (`spec-poset.md` Maintain closure). A claim made since the freeze is undone with the active copy that carried its mark, and the next claim marks the row again.

### Invariants

- An entity present in the active layer reads exactly its active property set; a property deleted from that set stays deleted.
- Promotion copies every frozen property into the active layer, so the active copy is complete at promotion time.
- Frozen layer nodes cannot be deleted. A deleted frozen edge is masked by a tombstone, never removed from the frozen layer.
- After a revert, storage reads equal the reads right after the last freeze, and every registered graph and poset cache holds only IDs that exist; a graph registers a node only while the owner set of its row holds the graph's ID, and its label carrier index holds a candidate for every label of every edge it owns.
- Layer count is always 1 or 2. Global IDs are stable across freezes.

### Termination

Property read and adjacency query check at most two layers. Property write updates one layer. Freeze transfers all data — bounded by total entity count.

### Complexity

- Property read: O(1) amortized. Adjacency: O(active + frozen edges). Freeze: O(N + E). Revert: O(active nodes + active edges + tombstones) per listener, plus one owner-set read per restored node and one label read per restored edge a labeled graph re-records.

---

## Ownership Persistence (Mark / Rebuild)

### Problem

Multiple graph instances may share one storage. A graph records in storage that it owns a node at the moment it takes the node, so a fresh instance restores only its own nodes, whatever the other graphs have written. Correct when rebuild restores exactly the nodes this graph added or claimed.

### Steps — Mark

Add a node:

1. Write the node ID property and the owners property, the set holding this graph's ID, in the one storage call that creates the row.
2. Register the node in this graph's registry.

Claim a node:

1. If the node ID is registered, return the registered node.
2. Read the owners property of the row. Default to the empty set if absent; a row absent from storage raises `EntityNotExistException` and registers nothing.
3. If this graph's ID is not in the set, add it and write back.
4. Register the node in this graph's registry.

### Steps — Rebuild

1. Clear all in-memory node registries.
2. For each node in storage:
   a. Read the node ID property. Skip if absent.
   b. Read the owners property. Skip unless it holds this graph's ID.
   c. Register the node in this graph's registry.

### Invariants

- Once add or claim returns, the owners property of the row holds this graph's ID, until a revert undoes the claim together with the registry entry. The set only grows; no graph removes its ID.
- A claim of a node whose owners property already holds this graph's ID writes nothing.
- Rebuild restores exactly the nodes whose owners property holds this graph's ID. A row with no owners property belongs to no graph.

### Termination

Mark takes a constant number of storage calls. Rebuild iterates once over nodes in storage.

### Complexity

- Add: O(1) beyond the row creation, one extra property in the same call. Claim: O(1), one read and at most one write. Rebuild: O(S) where S = nodes in storage.
