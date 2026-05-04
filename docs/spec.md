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
2. Initialize a queue with the start node's storage ID.
3. While queue is not empty:
   a. Dequeue a node storage ID.
   b. Retrieve outgoing (or incoming) edge IDs from storage.
   c. For each edge, check the edge condition predicate. Skip if false.
   d. Resolve the opposite endpoint. Skip if not registered in this graph.
   e. If the endpoint storage ID is not in visited, add to visited and enqueue. Yield the node.
4. Terminate when queue is empty.

### Invariants

- Visited set contains all dequeued storage IDs. No node enqueued twice.
- Only edges with both endpoints in this graph are traversed (edge isolation).

### Termination

Each node enqueued at most once (visited set guard). Terminates in at most `|V|` iterations.

### Complexity

- Time: O(V + E) where V = reachable nodes, E = edges traversed. Space: O(V).

---

## Label Visibility Filtering

### Problem

Given edges and a query label `by`, return edges visible under `by`. An edge is visible if at least one of its labels `l` satisfies `by == l` or `by > l`. Among visible labels, keep only maximal ones. Correct when every returned edge has at least one label satisfying the visibility rule.

### Steps

1. For each edge in the input sequence:
   a. Retrieve the edge's label set.
   b. Collect all labels `l` where `by == l` or `by > l` in the poset.
   c. If no such labels exist, skip this edge.
   d. Among collected labels, remove any dominated by another collected label.
   e. If at least one label remains, include this edge in output.
2. `SUPREMUM` as `by`: all labeled edges pass.
3. Edges with no labels: excluded when any label filter is applied.

### Invariants

- An edge is included iff it has at least one label visible under `by`. Maximal-label filtering removes only dominated labels.

### Termination

Input edge sequence is finite. Per edge, label set is finite. Ancestor lookup terminates (DAG). Each edge processed once.

### Complexity

- Time: O(E * L * A) where E = edges, L = labels per edge, A = ancestor lookup cost (cached). Space: O(L) per edge.

---

## Layered Query Resolution

### Problem

Given a layered storage with one active layer and at most one frozen layer, resolve property reads, adjacency queries, and containment checks. Correct when active layer values take precedence over frozen layer values.

### Steps — Property read

1. Check active layer. If present, return it.
2. Check frozen layer (translating global ID to frozen-local ID). If present, return it.
3. If neither layer has the entity, raise an error.

### Steps — Adjacency query

1. Retrieve edge IDs from active layer for the node.
2. Retrieve edge IDs from frozen layer (translating to global IDs).
3. Return the union of both sets.

### Steps — Property write (cross-layer)

1. If entity exists in active layer, update directly.
2. If entity exists only in frozen layer, create a shadow entry in active layer.

### Steps — Freeze

1. Transfer frozen-layer data into a new storage instance.
2. Transfer active-layer data into the same instance (active overwrites frozen for same entity).
3. Replace old frozen layer with merged storage. Clear active layer.

### Invariants

- Active layer values take precedence over frozen layer values for the same key.
- Frozen layer entities cannot be deleted. Only active-layer entities can be deleted.
- Layer count is always 1 or 2. Global IDs are stable across freezes.

### Termination

Property read and adjacency query check at most two layers. Property write updates one layer. Freeze transfers all data — bounded by total entity count.

### Complexity

- Property read: O(1) amortized. Adjacency: O(active + frozen edges). Freeze: O(N + E).

---

## Ownership Persistence (Flush / Rebuild)

### Problem

Multiple graph instances may share one storage. Each graph must persist which nodes it owns so a fresh instance can restore only its own nodes. Correct when rebuild restores exactly the flushed nodes.

### Steps — Flush

1. For each node registered in this graph:
   a. Read the owners property from storage. Default to empty set if absent.
   b. If this graph's ID is not in the set, add it and write back.

### Steps — Rebuild

1. Clear all in-memory node registries.
2. For each node in storage:
   a. Read node ID property. Skip if absent.
   b. Read owners property. If present and this graph's ID not in set, skip.
   c. If owners absent (pre-flush state), include the node (fallback: load all).
   d. Register the node in this graph's registry.

### Invariants

- Flush is idempotent. Rebuild after flush restores exactly the flushed nodes; without prior flush, restores all.

### Termination

Flush iterates once over nodes in this graph. Rebuild iterates once over nodes in storage.

### Complexity

- Flush: O(N) where N = nodes in this graph. Rebuild: O(S) where S = nodes in storage.

