# Algorithm Specification — Label Carrier Index (label → edges)

The per-graph inverted index from a label to the edges that carry it. Label
hierarchy queries: `spec-poset.md`.

## Problem

Given a label `L`, return the edges that carry `L` directly (`L ∈ labels(e)`,
no covering), and move `L` to another label on those edges or add another
label beside it. Callers copy a label once per control-flow merge, and a
merge can repeat for the same label; a full edge scan per call costs O(|E|)
and makes a whole analysis quadratic. Lookup must cost O(carriers(L)), not
O(|E|), and a repeated copy must cost O(edges recorded under `L` since the
previous copy), not O(carriers(L)).

## State

- `candidates(L)`: a set of edges per label, keyed by each edge's storage ID
  (`model.md`, Edge identity at storage level). Superset of the current
  carriers of `L`; entries are only added, except edges that no longer exist.
  Each entry holds the number of its latest record, and the entries of one
  label are ordered by that number.
- `count`: the number of records taken so far.
- `built`: whether the index has taken its initial scan.

Every step below — build, record with its label assignment, query, and each
relabel or copy — executes atomically with respect to every other operation
on the graph's storage.

## Steps — Build (before the first label operation on the graph)

1. For each edge `e` of the graph, for each `l ∈ labels(e)`: record `e`
   under `l` (Record step 2).
2. Set `built`.

## Steps — Record (every label assignment through the trait)

1. Build when not `built`.
2. Assign the label on the edge, then increment `count` and place the edge
   last in `candidates(label)` under its storage ID with number `count`,
   replacing an entry under the same storage ID.

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
   graph, for each `l ∈ labels(e)`: record `e` under `l` (Record step 2).

## Steps — Relabel `from → to` on edges satisfying `cond`

1. `from == to` is a no-op.
2. For each `e ∈ carriers(from)` with `cond(e)`: `labels(e) := labels(e) − {from} ∪ {to}`;
   record `e` under `to`.

## Steps — Copy `from → to` after cursor `s` on edges satisfying `cond`

1. Build when not `built`. `from == to` changes no edge; return `count`.
2. For each entry of `candidates(from)` from the last back to the first,
   stop at the first entry numbered `s` or less; check each edge `e` visited
   as Query step 2 does with `L = from`.
3. For each `e` the check returns with `to ∉ labels(e)` and `cond(e)`:
   `labels(e) := labels(e) ∪ {to}`; record `e` under `to`.
4. Return `count`. A caller passes it as `s` to its next copy of `from`.

No label is removed and no edge is created. An edge that a copy visited is
visited again only after a new record under `from`, by a label assignment
through the trait or by a revert that restores it (Restore). A later loss of
`to`, a `cond` that later holds, or an endpoint the graph claims later does
not bring it back by itself; a caller that needs those cases copies from
`s = 0`.

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
- Every edge that still exists and was recorded under `L` after record `s`
  is an entry of `candidates(L)` numbered above `s`, and those entries are
  the last ones: a record moves its entry last with the highest number.

## Termination

Build visits each edge and each of its labels once. Query, relabel and copy
iterate a snapshot of `candidates(L)` taken at their start, so records made
during a relabel or a copy (`to` may equal a later `from` in the caller) never
extend the iteration.

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
- Copy after cursor `s`: O(1 + entries of `candidates(from)` numbered above
  `s`). A series of copies of one label that each pass the previous result as
  `s` visits each record of `from` once in total; the first copy, with `s = 0`,
  visits every candidate. Each entry adds one record number to memory.

## Rejected alternatives

- **Full scan per relabel**: O(|E|) per merge; quadratic over an analysis.
- **Full candidate scan per copy**: O(|candidates(from)|) per merge; a label
  copied once per arrival of each nested branch rescans its earlier carriers
  each time.
- **Cursor kept by the index per `(from, to)` pair**: the index would hold
  caller state that the caller's `cond` and its own run boundaries decide.
- **Append-only record log per label**: every repeated record of one edge
  stays in the log, so a first copy visits each edge once per record instead
  of once.
- **Exact index (remove on relabel)**: needs a removal record per relabel; the
  superset keeps relabel and restore at O(1) per assignment.
- **Traversal-local write log**: misses carriers written before the current
  traversal under the same deterministic label.
- **Candidates keyed by `(src, dst, tag)`**: the graph-level identity survives
  a delete and re-add while the storage ID does not; the stale entry shadows
  the re-added edge, and the query drops both.
