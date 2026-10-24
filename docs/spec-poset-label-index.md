# Algorithm Specification — Label Carrier Index (label → edges)

The per-graph inverted index from a label to the edges that carry it. Label
hierarchy queries: `spec-poset.md`.

## Problem

Given a label `L`, return the edges that carry `L` directly (`L ∈ labels(e)`,
no covering), and move `L` to another label on those edges. Callers relabel
once per control-flow merge; a full edge scan per call costs O(|E|) and makes
a whole analysis quadratic. Lookup must cost O(carriers(L)), not O(|E|).

## State

- `candidates(L)`: a set of edges per label, keyed by each edge's storage ID
  (`model.md`, Edge identity at storage level). Superset of the current
  carriers of `L`; entries are only added, except edges that no longer exist.
- `built`: whether the index has taken its initial scan.

Every step below — build, record with its label assignment, query, and each
relabel — executes atomically with respect to every other operation on the
graph's storage.

## Steps — Build (before the first label operation on the graph)

1. For each edge `e` of the graph, for each `l ∈ labels(e)`: add `e` to
   `candidates(l)`.
2. Set `built`.

## Steps — Record (every label assignment through the trait)

1. Build when not `built`.
2. Assign the label on the edge, then add the edge to `candidates(label)`
   under its storage ID.

A label removal leaves `candidates` unchanged.

## Steps — Query `carriers(L)`

1. Build when not `built`.
2. For each `e ∈ candidates(L)`: when `e`'s storage ID no longer exists in
   storage, drop it from `candidates(L)`; otherwise return `e` when both
   endpoints belong to the graph and `L ∈ labels(e)`. A candidate whose
   endpoints the graph does not own stays, so a later claim makes it eligible.

## Steps — Restore (storage revert)

1. When not `built`, do nothing: the first build reads the final storage.
2. For each restored edge `e` that exists and has both endpoints in the
   graph, for each `l ∈ labels(e)`: add `e` to `candidates(l)`.

## Steps — Relabel `from → to` on edges satisfying `cond`

1. `from == to` is a no-op.
2. For each `e ∈ carriers(from)` with `cond(e)`: `labels(e) := labels(e) − {from} ∪ {to}`;
   add `e` to `candidates(to)`.

## Edge cases

- An edge deleted and re-added under the same `(src, dst, tag)` receives a new
  storage ID; the old entry drops at the next query and the new one is a
  separate candidate.
- Two concurrent label assignments on one edge serialize; neither is lost.

## Invariants

- Completeness: every edge currently carrying `L` is in `candidates(L)`,
  provided every label assignment after the build goes through the trait.
- A revert can expose an edge again after a query dropped it, or after a build
  that ran while it was hidden. The notice names every restored edge and
  Restore re-records its labels, so completeness holds across a revert.
- Dropping a nonexistent edge is safe: storage IDs are never reused within one
  storage lifetime.
- `carriers(L)` equals `{e ∈ E | L ∈ labels(e)}`: the query verifies every
  candidate against storage.

## Termination

Build visits each edge and each of its labels once. Query and relabel iterate
a snapshot of `candidates(L)` taken at their start, so records made during a
relabel (`to` may equal a later `from` in the caller) never extend the
iteration.

## Preconditions

- Label assignments go through the owning graph's trait operations. Writes
  through the generic property API, through storage directly, or through
  another graph instance over the same storage are not recorded.
- `IStorage.clear()` resets storage IDs; a graph whose storage was cleared is
  not reused.

## Complexity

- Build: O(|E| + Σ|labels(e)|), once per graph instance.
- Record: O(1) expected. Restore: O(Σ|labels(e)|) over the restored edges.
- Query and relabel: O(|candidates(L)|). Candidates exceed carriers only by
  edges that lost `L` (a relabel or a labeled delete); a label relabeled `k`
  times keeps up to `k` generations of such edges.

## Rejected alternatives

- **Full scan per relabel**: O(|E|) per merge; quadratic over an analysis.
- **Exact index (remove on relabel)**: needs a removal record per relabel; the
  superset keeps relabel and restore at O(1) per assignment.
- **Traversal-local write log**: misses carriers written before the current
  traversal under the same deterministic label.
- **Candidates keyed by `(src, dst, tag)`**: the graph-level identity survives
  a delete and re-add while the storage ID does not; the stale entry shadows
  the re-added edge, and the query drops both.
