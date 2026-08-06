# Algorithm Specification — Poset Ancestor Query

Ancestor queries in the label hierarchy resolve through a memoized ancestor
closure, correct on arbitrary DAGs.

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

1. Any parent mutation (`setParents`) discards the closure table. The next
   query rebuilds it.

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
node.

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
