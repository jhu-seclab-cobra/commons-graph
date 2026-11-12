# Algorithm Specifications

## Edge Lookup by Triple

### Problem

Given `(src, dst, tag)`, find the storage edge ID or determine it is absent. Correct when the returned ID matches the unique edge with the given triple.

### Steps

1. Look up `src` in node registry to get `srcStorageId`. If absent, return absent.
2. Look up `dst` to get `dstStorageId`. If absent, return absent.
3. For a simple-graph insertion, read the indexed pair's first row. Reject it if the graph's per-edge visibility declaration accepts it; if hidden, inspect only the remaining indexed pair rows and reject on a visible row. Keep this check and insertion in the existing critical section.
4. Ask storage for the exact complete triple and return its first stored edge ID, or absent. Insertion creates a row only when that triple is absent; the visibility declaration does not alter structural triple identity.

### Invariants

- Graph edge identity remains the complete `(src, dst, tag)` triple.
- Storage creation and deletion update the triple access in the same guarded operation.

### Termination

One finite hash lookup in core storage; backend defaults scan the finite outgoing adjacency.

### Complexity

Exact triple lookup and its duplicate check cost expected O(1) in core storage. The simple-graph pair check also costs expected O(1) when absent or its first row is visible; with a hidden first row it visits only P matching pair rows, O(1 + P). Across n distinct successful same-source targets and H such foreign pair rows, total work is O(n + H), hence expected O(n) for bounded pair multiplicity; unrelated source degree is never scanned.
Backend defaults retain O(out-degree) lookup. Hashing includes the bytes of the complete tag.
Pair/triple snapshots cost expected O(1 + matching rows); first-row lookup reads one indexed ID.
Group adjacency returns only IDs whose immutable tag maps to the requested group, in the order of the full directional adjacency. It costs expected O(1 + matches) and O(matches) result space;
no unrelated group is enumerated. For G requested groups, merging selected heads costs O(G + G * matches),
which is O(1 + matches) for a fixed set. Retained structural entries are O(V + E), independent of queries.
Layered reads merge only the matching frozen and active incidences and hide frozen tombstones.
All mutable operations update membership under their existing lock; properties never change it.
Directional buckets retain primitive singleton IDs inline and grow arrays geometrically from the second ID.
Pair rows keep complete tags separate; deleting the first duplicate exposes the next visible row.
New IDs append in constant amortized time. Removal scans/shifts matching bucket IDs; reinserting a reverted ID can shift matching pair IDs. A cascade sums that work over its incident edges.

---

## BFS Traversal (Descendants / Ancestors)

### Problem

Given a start node, a direction (outgoing for descendants, incoming for ancestors), and an optional edge condition predicate, find all transitively reachable nodes. Output: sequence of reachable nodes excluding start. Correct when every reachable node appears exactly once.

### Steps

1. Initialize a visited set with the start node's storage ID.
2. Initialize a queue with the start node ID.
3. While queue is not empty:
   a. Dequeue a node ID.
   b. Read the graph's visible outgoing (or incoming) adjacency, which applies its per-edge visibility declaration after endpoint registration; the walk never includes a hidden shared-storage edge.
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

- Add writes one extra owner property in the row-creation call. Claim reads one owner property and writes it at most once, with O(G) owner-set copying for G existing owners plus the selected backend’s property-access cost. Rebuild visits S storage nodes and reads each row’s identifier and owner property; its cost includes those property reads.
