# Algorithm Specification — Poset Queries

Ancestor queries in the label hierarchy resolve through a memoized ancestor
closure, correct on arbitrary DAGs. Label-carrier queries (which edges carry a
label) resolve through a per-graph inverted index, specified in
`spec-poset-label-index.md`.

## Ancestor Closure (DAG reachability)

### Problem

Given labels `a`, `b` in the label hierarchy (edges child→parent), decide
whether `a > b` (`a` is a strict ancestor of `b`). Correct on arbitrary DAGs,
including labels with multiple parents. Queries are hot (called per edge×label
during visibility filtering) — lookup must be constant-time after preparation.

### Precondition

The hierarchy is a DAG. A cycle is invalid input: preparation detects it and
raises an error naming a label on the cycle.

### Steps — Prepare closure (on first query while no table exists)

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

### Steps — Maintain closure

Applies while a table exists; with no table, a mutation does nothing and the next
query prepares one.

1. `setParents(l, parents)` with the parents `l` already holds (same names, same
   labels) changes nothing, and the table stays.
2. Otherwise, a parent label seen for the first time gets an empty row.
3. If `l` has no child label and is not its own parent, only the row of `l`
   changes: `ancestors(l)` = union over each parent `p` of `{p} ∪ ancestors(p)`.
   No other row contains `l`.
4. If `l` has a child label, the rows of its descendants change too: the table is
   discarded, and the next query prepares it.
5. If `l` is its own parent, the table is discarded; the next query raises the
   cycle error.
6. A storage revert removes the rows of the removed label nodes. The table is
   discarded when the notice removes a parent edge of a surviving label, or
   restores a label's edge that was absent before the revert. A promoted edge
   with an unchanged structure changes nothing.

### Invariants

- `ancestors(n)` is exactly the set of labels reachable from `n` via
  child→parent edges, excluding `n` itself.
- The relation is a strict partial order on a DAG: irreflexive (a node is
  never its own ancestor), transitive by construction (step 2d unions the
  parents' closures).
- The closure table, while it exists, holds one row per label and reflects the
  hierarchy at the time of the last mutation.

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
- Maintain: inserting a label with `k` parents costs O(k · V) for the row.
  Without step 3, a hierarchy grown one label at a time and queried between
  insertions would prepare the table once per insertion.
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
