# Algorithm Specification — Poset Queries

Ancestor queries in the label hierarchy resolve through a memoized ancestor
closure, correct on arbitrary DAGs. Label-carrier queries (which edges carry a
label) resolve through a per-graph inverted index.

## Ancestor Closure (DAG reachability)

### Problem

Given labels `a`, `b` in the label hierarchy (edges child→parent), decide
whether `a > b` (`a` is a strict ancestor of `b`). Correct on arbitrary DAGs,
including labels with multiple parents. Queries are hot (called per edge×label
during visibility filtering) — lookup must be constant-time after preparation.

### Precondition

The hierarchy is a DAG. A cycle is invalid input: preparation detects it and
raises an error naming a label on the cycle.

### Steps — Prepare closure (on first query after any mutation)

1. Initialize an empty ancestor-set table keyed by label node.
2. For each label node `n`, compute `ancestors(n)` by memoized traversal:
   a. If `ancestors(n)` is memoized, return it.
   b. If `n` is currently being computed (on the traversal path), raise an
      error: cycle through `n`.
   c. Mark `n` as being computed.
   d. `ancestors(n)` = union over each parent `p` of `n` of `{p} ∪ ancestors(p)`.
   e. Memoize `ancestors(n)`, unmark `n`.

### Steps — Query

1. `isAncestor(a, b)` = `a ∈ ancestors(b)`.
2. `compare(a, b)`: equality and `SUPREMUM`/`INFIMUM` bounds resolve first
   (unchanged); otherwise `isAncestor` in both directions; incomparable → null.

### Steps — Invalidate

1. Any parent mutation (`setParents`) or storage revert discards the closure
   table. The next query rebuilds it.

### Invariants

- `ancestors(n)` is exactly the set of labels reachable from `n` via
  child→parent edges, excluding `n` itself.
- The relation is a strict partial order on a DAG: irreflexive (a node is
  never its own ancestor), transitive by construction (step 2d unions the
  parents' closures).
- The closure table reflects the hierarchy at the time of the last mutation.

### Termination

The hierarchy is finite and acyclic. Memoization computes each label's set
once; the being-computed mark bounds every traversal path by the longest
chain. Cycle input terminates with an error at the first revisited on-path
node. The traversal keeps its pending nodes on an explicit worklist, so
hierarchy depth never consumes call-stack frames.

### Complexity

- Prepare: O(V · (V + E)) worst case with set unions; V = labels, E = parent
  edges. Label hierarchies are small (tens of labels) — build cost is
  negligible against per-query traversal.
- Query: O(1) expected (set membership). Space: O(V²) worst case.

### Rejected alternatives

- **DFS interval labeling**: O(1) query but correct only on trees — in a DAG a
  label reachable through two parents receives one interval, so containment
  tests against the other parent report false negatives.
- **Per-query BFS**: correct, no invalidation state, but O(V + E) on the hot
  visibility-filtering path.
- **Multi-interval labeling**: correct on DAGs, but interval-set size is
  unbounded in the worst case and the implementation complexity exceeds the
  closure table with no asymptotic gain at this scale.

## Label Carrier Index (label → edges)

### Problem

Given a label `L`, return the edges that carry `L` directly (`L ∈ labels(e)`,
no covering), and move `L` to another label on those edges. Callers relabel
once per control-flow merge; a full edge scan per call costs O(|E|) and makes
a whole analysis quadratic. Lookup must cost O(carriers(L)), not O(|E|).

### State

- `candidates(L)`: a set of edges per label, keyed by each edge's storage ID
  (`model.md`, Edge identity at storage level). Superset of the current
  carriers of `L`; entries are only added, except edges that no longer exist.
- `built`: whether the index has taken its initial scan.

Every step below — build, record with its label assignment, query, and each
relabel — executes atomically with respect to every other operation on the
graph's storage.

### Steps — Build (before the first label operation on the graph)

1. For each edge `e` of the graph, for each `l ∈ labels(e)`: add `e` to
   `candidates(l)`.
2. Set `built`.

### Steps — Record (every label assignment through the trait)

1. Build when not `built`.
2. Assign the label on the edge, then add the edge to `candidates(label)`
   under its storage ID.

A label removal leaves `candidates` unchanged.

### Steps — Query `carriers(L)`

1. Build when not `built`.
2. For each `e ∈ candidates(L)`: when `e`'s storage ID no longer exists in
   storage, drop it from `candidates(L)`; otherwise keep `e` when `L ∈ labels(e)`.

### Steps — Relabel `from → to` on edges satisfying `cond`

1. `from == to` is a no-op.
2. For each `e ∈ carriers(from)` with `cond(e)`: `labels(e) := labels(e) − {from} ∪ {to}`;
   add `e` to `candidates(to)`.

### Edge cases

- An edge deleted and re-added under the same `(src, dst, tag)` receives a new
  storage ID; the old entry drops at the next query and the new one is a
  separate candidate.
- Two concurrent label assignments on one edge serialize; neither is lost.

### Invariants

- Completeness: every edge currently carrying `L` is in `candidates(L)`,
  provided every label assignment after the build goes through the trait.
- A storage that reverts writes (a cleared layered-storage active layer)
  restores only labels an edge held before; the build or a record added the
  edge to those labels' candidates, and they are never removed while the edge
  exists. Completeness therefore holds across a revert with no invalidation.
- Dropping a nonexistent edge is safe: storage IDs are never reused within one
  storage lifetime, so a dropped edge never exists again under the same ID.
- `carriers(L)` equals `{e ∈ E | L ∈ labels(e)}`: the query verifies every
  candidate against storage.

### Termination

Build visits each edge and each of its labels once. Query and relabel iterate
a snapshot of `candidates(L)` taken at their start, so records made during a
relabel (`to` may equal a later `from` in the caller) never extend the
iteration.

### Preconditions

- Label assignments go through the owning graph's trait operations. Writes
  through the generic property API, through storage directly, or through
  another graph instance over the same storage are not recorded.
- `IStorage.clear()` resets storage IDs; a graph whose storage was cleared is
  not reused.

### Complexity

- Build: O(|E| + Σ|labels(e)|), once per graph instance.
- Record: O(1) expected.
- Query and relabel: O(|candidates(L)|). Candidates exceed carriers only by
  edges that lost `L` (a relabel or a labeled delete); a label relabeled `k`
  times keeps up to `k` generations of such edges.

### Rejected alternatives

- **Full scan per relabel**: O(|E|) per merge; quadratic over an analysis.
- **Exact index (remove on relabel)**: loses completeness when a layered
  storage reverts the active layer: a revert notification names removed IDs
  only, not the frozen label sets it restores, so the index would rescan every
  restored edge.
- **Traversal-local write log**: misses carriers written before the current
  traversal under the same deterministic label.
- **Candidates keyed by `(src, dst, tag)`**: the graph-level identity survives
  a delete and re-add while the storage ID does not; the stale entry shadows
  the re-added edge, and the query drops both.
