# Graph Abstraction Layer Concept Document

## 1. Context

**Problem Statement**
Graph applications need to run on different storage engines, but each engine exposes different access styles and data assumptions. The core problem is to keep application logic stable while storage, persistence mode, and performance strategy change over time. Label-based edge visibility requires a partial-order structure over labels that scales to hundreds of context paths.

**System Role**
This module is the stable graph domain boundary, translating domain-level graph operations into backend-specific storage behavior.

**Data Flow**
- **Inputs:** Domain operations using node identifiers and edge tuples, entity property reads and updates, traversal and neighborhood queries, Label hierarchy mutations, layer transition signals.
- **Outputs:** Domain-consistent node and edge views, query membership determined by the Graph contract, label-filtered traversal results, backend-independent error semantics.
- **Connections:** Upstream services -> graph domain contract + Label hierarchy contract -> graph store contract -> backend implementations and Layered Storage composers.

**Scope Boundaries**
- **Owned:** Graph domain vocabulary, entity identity model, graph and store contracts, Layered Storage composition, label partial-order framework, metadata contract, import/export contract.
- **Not Owned:** Backend deployment, database lifecycle operations and language-specific analysis policy.

## 2. Concepts

**Conceptual Diagram**
```
[Domain Services]
        |
        v
[Graph contract + Label hierarchy contract]
  (node identifiers + edge tuples)     (labels)
        |                                 |
        v                                 v
[Domain Coordinator]
  ├── Deterministic edge identity
  └── `Label` visibility filtering
        |                                 |
        v                                 v
[Store Contract: graph]         [Storage: Label hierarchy]
        |                                 |
        +--> Storage                   +--> Storage for Label hierarchy
        |
        +--> Layered Storage
```

**Core Concepts**

- **Name:** [Graph](glossary/glossary-graph.md#graph)
  - **Role:** Keeps application graph operations independent of the storage backend.
  - **Scope:** Owns edge uniqueness, entity lifecycle and structural traversal.
  - **Relationships:** Delegates persistence to Storage and visibility selection to Label hierarchy.

- **Name:** [Node](glossary/glossary-graph.md#node)
  - **Role:** Provides application vertex identity and typed property access.
  - **Scope:** Excludes adjacency indexing and traversal.
  - **Relationships:** Created and queried through a Graph.

- **Name:** [Edge](glossary/glossary-graph.md#edge)
  - **Role:** Provides directed connectivity with a tag and typed properties.
  - **Scope:** Owns structural identity; excludes adjacency indexing.
  - **Relationships:** Connects Nodes; `Label` assignments determine visibility.

- **Name:** [Entity](glossary/glossary-graph.md#entity)
  - **Role:** Gives Nodes and Edges one property-access contract.
  - **Scope:** Excludes identity generation and lifecycle.
  - **Relationships:** Reads and writes through Storage; identity rules are in the model.

- **Name:** [Storage](glossary/glossary-graph.md#storage)
  - **Role:** Supplies the data access that a Graph delegates to a backend.
  - **Scope:** Owns identifiers, adjacency, properties, metadata and persistence.
  - **Relationships:** Backs both the main Graph and its separate Label hierarchy.

- **Name:** [`Label`](glossary/glossary-labels.md#label)
  - **Role:** Scopes edge visibility without encoding order in its string.
  - **Scope:** Owns string identity and the two structural bounds.
  - **Relationships:** Ordered by Label hierarchy and assigned to Edges.

- **Name:** [Label hierarchy](glossary/glossary-labels.md#label-hierarchy)
  - **Role:** Supports visibility queries over context relationships with multiple parents.
  - **Scope:** Owns ordering, named parent relationships and ancestor queries.
  - **Relationships:** Uses separate Storage; order follows parent relationships, not a string prefix.

- **Name:** [Layered Storage](glossary/glossary-storage.md#layered-storage)
  - **Role:** Lets later analysis phases read completed data while writing temporary changes.
  - **Scope:** Owns layer lifecycle, query resolution and merge-on-Freeze.
  - **Relationships:** Combines one Frozen layer with one Active layer; node and edge deletion rules belong to the model.

## 3. Contracts & Flow

**Data Contracts**
- **With upstream modules:** Inputs are valid string node identifiers, edge tuples, and property names. Outputs are graph and entity views or explicit domain exceptions.
- **With storage implementations:** Storage determines query membership and enforces existence constraints; iteration order is unspecified unless an API states it. Storage has no application-domain policy.
- **With Layered Storage composers:** Flat storage instances serve as building blocks. Composers manage layer lifecycle, freeze transitions, and query routing through the standard storage contract.

**Internal Processing Flow**
The Graph coordinates application identity and delegates data access to Storage; the Label hierarchy supplies ordering for visibility selection. Algorithm steps belong to [Graph operations](spec-graph.md), [Label hierarchy queries](spec-poset.md), [label carrier queries](spec-poset-label-index.md) and [merge operations](spec-graph-merge.md).

## 4. Scenarios

- **Typical:** A service creates two nodes by identifier, creates an edge by source, destination, and tag, sets edge properties, and queries children. Later, the storage backend switches from in-memory to a persistent file-backed store without changing service-level graph calls.

- **Boundary:** A request creates an edge whose source node does not exist. The graph contract rejects it with a domain exception instead of creating implicit nodes.

- **Boundary:** A query on an empty graph returns empty results. No nodes or edges exist, and traversal produces no output.

- **Interaction (layered):** A static analysis tool builds a syntax graph, freezes it, then builds control-flow edges on top of the frozen data. The syntax data merges into a Frozen layer. Control-flow construction writes to a fresh Active layer. Property reads for syntax nodes cascade to the Frozen layer.

- **Interaction (active-only deletion):** During construction, the tool creates temporary nodes in the Active layer, then deletes them before freezing. Deletion succeeds because the nodes are in the Active layer. Attempting to delete a Frozen layer Node is rejected; deleting a Frozen layer Edge hides it until revert or Freeze.

- **Interaction (label-filtered traversal):** The tool defines context labels and their parent relationships in the Label hierarchy. When querying edges visible under a given label, the graph layer uses the hierarchy to determine dominated labels, then filters edges accordingly.

## 5. Rationale and Rejected Alternatives

### Label hierarchy queries

- **DFS interval labeling**: O(1) query but correct only on trees — in a DAG a
  label reachable through two parents receives one interval, so containment
  tests against the other parent report false negatives.
- **Per-query BFS**: correct, no invalidation state, but O(V + E) on the hot
  visibility-filtering path.
- **Multi-interval labeling**: correct on DAGs, but interval-set size is
  unbounded in the worst case and the implementation complexity exceeds the
  closure table with no asymptotic gain at this scale.

### Label carrier queries

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
