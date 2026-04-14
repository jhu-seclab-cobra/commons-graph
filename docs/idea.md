# Graph Abstraction Layer Concept Document

## 1. Context

**Problem Statement**
Graph applications need to run on different storage engines, but each engine exposes different access styles and data assumptions. The core problem is to keep application logic stable while storage, persistence mode, and performance strategy change over time. Label-based edge visibility requires a partial-order structure over labels that scales to hundreds of context paths.

**System Role**
This module is the stable graph domain boundary, translating domain-level graph operations into backend-specific storage behavior.

**Data Flow**
- **Inputs:** Domain operations using node identifiers and edge tuples, entity property reads and updates, traversal and neighborhood queries, label hierarchy mutations, layer transition signals.
- **Outputs:** Domain-consistent node and edge views, deterministic query results, label-filtered traversal results, backend-independent error semantics.
- **Connections:** Upstream services -> graph domain contract + label hierarchy contract -> graph store contract -> backend implementations and layered storage composers.

**Scope Boundaries**
- **Owned:** Graph domain vocabulary, entity identity model, graph and store contracts, layered storage composition, node grouping, label partial-order framework, metadata contract, import/export contract.
- **Not Owned:** Backend-specific tuning, graph algorithm libraries, database lifecycle operations, external deployment concerns.

## 2. Concepts

**Conceptual Diagram**
```
[Domain Services]
        |
        v
[Graph Contract + Label Hierarchy Contract]
  (node identifiers + edge tuples)     (labels)
        |                                 |
        v                                 v
[Domain Coordinator]
  ├── Deterministic edge identity
  ├── Node/edge wrapper caching
  └── Label visibility filtering
        |                                 |
        v                                 v
[Store Contract: graph]         [Store Contract: label hierarchy]
  (auto-generated integer IDs)      (auto-generated integer IDs)
        |                                 |
        +--> Flat store (single-layer)    +--> Flat store (label DAG)
        |       ├── [In-memory]
        |       ├── [Persistent file-backed]
        |       ├── [Algorithm-integrated]
        |       └── [Embedded database]
        |
        +--> Layered store (freeze-and-stack)
                └── 1 frozen layer + 1 active layer
```

**Core Concepts**

- **Graph**
  - **Definition:** The top-level domain contract for directed property graph operations. Accepts string node identifiers and edge tuples, enforces graph-level policy, and delegates storage.
  - **Scope:** Owns edge uniqueness semantics, node/edge lifecycle, and traversal queries. Does not own storage internals or backend selection.
  - **Relationships:** Delegates to storage for persistence. Delegates to the label hierarchy for edge visibility filtering. Produces entities as query results.

- **Node**
  - **Definition:** A vertex in the graph, identified by a user-provided string identifier. Carries typed properties backed by storage.
  - **Scope:** Owns property access for its entity. Does not own adjacency data or traversal logic.
  - **Relationships:** Created and queried through the graph. Properties stored in the underlying storage.

- **Edge**
  - **Definition:** A directed connection between two nodes, identified by source, destination, and tag. Carries typed properties and optional label association.
  - **Scope:** Owns its structural metadata (source, destination, tag) and property access. Does not own adjacency indexing.
  - **Relationships:** Created through the graph. Structural metadata resolved from storage on demand. Visibility governed by the label hierarchy.

- **Entity**
  - **Definition:** A lightweight property-access wrapper backed by storage. Both nodes and edges are entities. Constructed empty and bound to storage and an identifier by the graph layer.
  - **Scope:** Owns typed property access. Does not own identity generation or lifecycle.
  - **Relationships:** Bound to a storage instance. All properties in the entity namespace are user properties.

- **Storage**
  - **Definition:** A generic directed property graph engine that manages nodes, edges, adjacency indices, and per-entity properties using auto-generated integer identifiers.
  - **Scope:** Owns data structure management, adjacency indices, persistence, and identifier generation. Does not know about domain-level node identifiers, labels, or graph policy.
  - **Relationships:** Consumed by the graph layer, which maintains a mapping between string node identifiers and storage integer identifiers. The same storage contract backs both the main graph and the label hierarchy.

- **Label**
  - **Definition:** A domain marker that controls edge visibility. Wraps a string value. Includes sentinel values for top and bottom of the partial order.
  - **Scope:** Owns its string identity and sentinel semantics. Does not own hierarchy relationships (those belong to the label hierarchy).
  - **Relationships:** Organized into a partial order by the label hierarchy. Used by the graph layer to filter edge queries.

- **Label Hierarchy (Poset)**
  - **Definition:** A directed acyclic graph where nodes represent labels and edges represent parent relationships. Provides ancestor queries, comparability checks, and partial-order operations over labels.
  - **Scope:** Owns label ordering, ancestor traversal, and comparability. Does not own label identity or edge association.
  - **Relationships:** Backed by a dedicated storage instance. Queried by the graph layer for edge visibility filtering. Labels represent context paths, and the partial order reflects containment rather than simple string prefix.

- **Node Group**
  - **Definition:** An orthogonal domain ability that groups nodes and provides automatic identifier generation within groups.
  - **Scope:** Owns grouping membership and group-scoped identifier generation. Does not redefine entity identity.
  - **Relationships:** Layered on top of the graph and storage contracts.

- **Layered Storage**
  - **Definition:** A composition of one frozen storage instance plus one mutable active layer into a unified storage view. Supports phase-based analysis where completed phases become read-only.
  - **Scope:** Owns layer lifecycle, freeze transitions, query routing, and merge operations. Deletion restricted to the active layer. Query depth bounded to two layers (active + one frozen).
  - **Relationships:** Composed from flat storage instances. Exposes the same storage contract. On freeze, active data merges into the frozen layer and a fresh active layer is created.

## 3. Contracts & Flow

**Data Contracts**
- **With upstream modules:** Inputs are valid string node identifiers, edge tuples, and property names. Outputs are graph and entity views or explicit domain exceptions.
- **With storage implementations:** Storage preserves node and edge semantics, enforces existence constraints, and exposes deterministic adjacency and property behavior. Storage operates on auto-generated integer identifiers with no domain type awareness.
- **With layered storage composers:** Flat storage instances serve as building blocks. Composers manage layer lifecycle, freeze transitions, and query routing through the standard storage contract.
- **With node group modules:** Grouping reuses graph and storage invariants without redefining entity identity semantics.

**Internal Processing Flow**
1. **Request normalization** — Domain service provides a valid node identifier and edge tuple, invokes a graph operation.
2. **Graph-level contract checks** — Graph layer enforces existence, uniqueness, and variant rules.
3. **Identity translation** — Graph layer maps string node identifiers to storage integer identifiers, generating new mappings for additions.
4. **Store dispatch** — Graph layer passes integer identifiers to storage for node, edge, adjacency, and property operations.
5. **Layer routing** (layered storage only) — Writes go to the active layer. Reads cascade from active to frozen. Deletes are restricted to the active layer.
6. **Entity materialization** — Storage data wraps into typed entity views via cached wrapper objects. Property operations route through the entity to storage.
7. **Label hierarchy dispatch** (label operations) — Label hierarchy queries route to the dedicated storage instance using label strings mapped to storage identifiers.
8. **Freeze transition** (layered storage only) — On freeze, all layers merge into a single frozen store. The active layer is replaced with an empty store.
9. **Result propagation** — Success returns typed domain outputs. Contract violations raise explicit domain exceptions.

## 4. Scenarios

- **Typical:** A service creates two nodes by identifier, creates an edge by source, destination, and tag, sets edge properties, and queries children. Later, the storage backend switches from in-memory to a persistent file-backed store without changing service-level graph calls.

- **Boundary:** A request creates an edge whose source node does not exist. The graph contract rejects it with a domain exception instead of creating implicit nodes.

- **Boundary:** A query on an empty graph returns empty results. No nodes or edges exist, and traversal produces no output.

- **Interaction (layered):** A static analysis tool builds a syntax graph, freezes it, then builds control-flow edges on top of the frozen data. The syntax data merges into a frozen layer. Control-flow construction writes to a fresh active layer. Property reads for syntax nodes cascade to the frozen layer.

- **Interaction (active-only deletion):** During construction, the tool creates temporary nodes in the active layer, then deletes them before freezing. Deletion succeeds because the nodes are in the active layer. Attempting to delete a frozen node raises a domain exception.

- **Interaction (label-filtered traversal):** The tool defines context labels and their parent relationships in the label hierarchy. When querying edges visible under a given label, the graph layer uses the hierarchy to determine dominated labels, then filters edges accordingly.
