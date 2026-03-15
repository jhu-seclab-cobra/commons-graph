# Graph Abstraction Layer Concept Document

## 1. Context

**Problem Statement**
Graph applications in COBRA need to run on different storage engines, but each engine exposes different access styles and data assumptions. The core problem is to keep application logic stable while storage, persistence mode, and performance strategy can change over time. For large-scale static analysis, graph data can exceed 30GB, requiring layered storage strategies that balance memory efficiency with access performance across different analysis phases. Additionally, label-based edge visibility requires a partial-order structure over labels that can scale to hundreds of function-context paths without being bottlenecked by serialization overhead.

**System Role**
This module is the stable graph domain boundary in COBRA, translating domain-level graph operations into backend-specific storage behavior.

**Data Flow**
- **Inputs:** Domain operations using `NodeID` (user-provided string) and `(src, dst, type)` edge tuples, entity property reads and updates, traversal and neighborhood queries, label hierarchy mutations, layer transition signals (freeze/compact).
- **Outputs:** Domain-consistent node/edge views, deterministic query results, label-filtered traversal results, backend-independent error semantics.
- **Connections:** Upstream services -> Graph domain API (`IGraph` + `IPoset`) -> Graph store contract (`IStorage` with opaque `Int` IDs) -> Backend implementations / Layered storage composers.

**Scope Boundaries**
- **Owned:** Graph domain vocabulary, entity identity model, graph/store contracts, layered storage composition, node grouping trait, label partial-order framework, metadata contract, import/export contract concepts.
- **Not Owned:** Backend-specific tuning (MapDB, JGraphT, Neo4j implementations), graph algorithm libraries, database lifecycle operations, external deployment concerns.

## 2. Concepts

**Conceptual Diagram**
```
[Domain Services]
            |
            v
[IGraph: domain graph contract]  +  [IPoset: label hierarchy contract]
  (NodeID + edge tuples)                    (Label)
            |                                   |
            v                                   v
[AbcMultipleGraph: domain coordinator]
  ├── nodeIdCache: NodeID → InternalID
  ├── edge lookup by (src, dst, type) via adjacency + endpoint queries
  └── label visibility filtering
            |                                   |
            v                                   v
[IStorage: graph store]             [IStorage: poset store]
  (opaque Int node/edge IDs)           (opaque Int IDs)
            |                                   |
            +--> Flat store (single-layer)       +--> Flat store (label DAG)
            |       +--> [Native in-memory]
            |       +--> [MapDB persistent]
            |       +--> [JGraphT in-memory]
            |       +--> [Neo4j embedded]
            |
            +--> Layered store (freeze-and-stack)
                    +--> [LayeredStore: 1 frozen layer + 1 active layer]
```

**Core Concepts**

- **Three-layer architecture**
    The system separates into three layers: a domain layer (`IGraph`, `IPoset`) that speaks in user-facing types (`NodeID`, `(src, dst, type)` edge tuples, `Label`), a store layer (`IStorage`) that speaks in opaque auto-generated `Int` IDs, and an entity layer (`AbcNode`, `AbcEdge`) that bridges domain identity to storage-backed property access. The domain layer owns identity semantics, edge lookup by `(src, dst, type)`, and traversal algorithms; the store layer owns data structure management, adjacency indices, and persistence; the entity layer owns typed property access and meta-property conventions. This separation allows each layer to evolve independently.

- **Store contract as Int-keyed directed property graph**
    `IStorage` is a generic directed property graph engine where nodes and edges are both identified by opaque auto-generated `Int` IDs. Edges carry structural metadata (source, destination, type) accessible via `getEdgeSrc`/`getEdgeDst`/`getEdgeType`. The store manages per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), and metadata. It does not know about `NodeID`, `Label`, or any domain concept. The same `IStorage` implementation can back both the main program-analysis graph and the label partial-order DAG. This design follows the pattern of igraph (integer-indexed edges with parallel `from`/`to` arrays) and Neo4j (auto-generated `long` relationship IDs with stored endpoints).

- **Dual store architecture**
    `AbcMultipleGraph` holds two `IStorage` instances: `storage` for the main graph (nodes and edges), `posetStorage` for the label partial-order set (labels as nodes, parent relationships as edges). Both stores are injected by the subclass, persisted independently, and use the same `IStorage` contract. This eliminates the need for metadata-based lattice serialization and provides native adjacency indexing for label hierarchy traversal.

- **Domain identity model**
    `NodeID` is a `typealias` for `String` — the user-provided node name. Nodes are created with a mandatory `NodeID` stored as the `__id__` meta property in storage. The graph layer maintains a `nodeIdCache: HashMap<NodeID, InternalID>` that maps user-facing names to storage-internal IDs, populated eagerly on first access.

    Edges have no domain-level ID object. They are identified at the graph layer by the `(src: NodeID, dst: NodeID, type: String)` tuple. The graph layer resolves this tuple to a storage-internal edge `Int` by scanning adjacency lists and querying `getEdgeSrc`/`getEdgeDst`/`getEdgeType`. Edge structural info is also stored as meta properties (`__src__`, `__dst__`, `__tag__`) for entity-layer access.

- **Entity as operation view**
    `AbcNode` and `AbcEdge` represent typed access points to properties and identity, while persistence is delegated to the store. `AbcNode` holds both the user-facing `NodeID` (read from `__id__` meta property) and the `storageId: InternalID` (opaque storage key). `AbcEdge` holds the `InternalID` as its `id` and lazily reads `srcNid`/`dstNid`/`eType` from meta properties. Properties prefixed with `__` are internal metadata, filtered from external access.

- **Layered storage as composition**
    Layered storage composes one frozen `IStorage` instance plus one mutable active layer into a unified `IStorage` view. The domain layer sees a single store; internally, reads cascade through layers (active -> frozen), writes target only the active layer, and freeze transitions migrate active data to read-only frozen storage via merge. Deletion is restricted to the active layer. Query resolution follows a simple two-phase cascade without deletion tracking.

- **Phase-based freezing**
    Static analysis proceeds in phases (AST construction -> CFG -> DFG -> analysis). Each phase completes a set of graph mutations that become read-only in subsequent phases. Layered storage exploits this by freezing completed phases into read-only storage while keeping the active phase's data in fast heap memory. The merge-on-freeze strategy ensures at most one frozen layer exists, keeping query depth at O(1).

- **Analysis state externalization**
    High-frequency analysis state (abstract values, dataflow facts) produced during fixpoint iteration should be stored outside `IStorage` in direct-indexed arrays keyed by node sequential IDs. This avoids `IValue` boxing and `HashMap` overhead on the hottest code path, achieving ~5-10ns read/write latency with zero GC allocation per access.

- **Label partial-order as dedicated graph**
    The label hierarchy is a DAG where nodes represent labels and edges represent parent relationships. In COBRA, labels represent function-context call paths (e.g., `A->B->C->D`), and the partial order reflects subpath containment — not simple string prefix. This means the hierarchy requires genuine graph traversal for ancestor queries and comparability checks. Storing the poset in a dedicated `IStorage` instance provides native adjacency indexing, eliminating the serialization overhead of metadata-based storage when label counts reach hundreds.

- **Graph variants as semantic policy**
    `AbcSimpleGraph` and `AbcMultipleGraph` represent different edge uniqueness policies over the same identity model. The variant chooses admissible edge patterns, not storage format.

- **Traits as orthogonal domain abilities**
    Grouping (`TraitNodeGroup`) and labeling are independent capabilities layered on top of the same identity and store contracts.

## 3. Contracts & Flow

**Data Contracts**
- **With upstream modules:** Inputs must be valid `NodeID` strings, edge `(src, dst, type)` tuples, and property names; outputs are graph/entity views or explicit domain exceptions.
- **With store implementations:** Stores must preserve node/edge semantics, enforce existence constraints, and expose deterministic adjacency/property behavior. Stores operate on opaque `Int` IDs — no domain type awareness.
- **With layered store composers:** Flat store instances serve as building blocks; composers manage layer lifecycle, freeze transitions, and query routing.
- **With trait modules:** Trait behaviors must reuse `IGraph`/`IStorage` invariants and must not redefine entity identity semantics.

**Internal Processing Flow**
1. **Request normalization**: Domain service provides valid `NodeID` and edge `(src, dst, type)` tuple, invokes graph operations.
2. **Graph-level contract checks**: Graph layer enforces graph policy (existence, uniqueness, variant rules).
3. **ID resolution**: Graph layer resolves `NodeID` to `InternalID` via `nodeIdCache`. For edge lookup, scans adjacency lists using `getEdgeSrc`/`getEdgeDst`/`getEdgeType` to find matching edge.
4. **Store dispatch**: Graph delegates persistence and adjacency operations to `IStorage` using `Int` IDs.
5. **Layer routing** (layered store only): Writes go to active layer; reads cascade active -> frozen layer; deletes restricted to active layer.
6. **Entity/property materialization**: Returned store data is wrapped as typed entity views via cached wrapper objects (`SoftReference`-based caches); property operations are resolved by store. Meta properties (`__id__`, `__src__`, `__dst__`, `__tag__`) bridge domain identity to storage.
7. **Poset dispatch** (label operations): Label hierarchy queries are dispatched to the dedicated `posetStorage` instance using label names stored as `__id__` meta properties on poset nodes.
8. **Freeze transition** (layered store only): On `freeze`, all layers are merged into a single new frozen store via `mergeLayerInto` (with ID remapping), active layer is replaced with empty heap store.
9. **Result/exception propagation**: Success returns typed domain outputs; contract violations raise explicit domain exceptions.

## 4. Scenarios

- **Typical:** A service creates two nodes by `NodeID`, creates an edge by `(src, dst, type)`, sets edge properties, and queries children/parents. Later, storage backend switches from native memory to MapDB without changing service-level graph calls.

- **Boundary:** A request attempts to create an edge whose source node is missing. The graph/store contract rejects it with `EntityNotExistException` instead of creating implicit nodes.

- **Interaction (layered):** A static analysis tool builds an AST graph, freezes it, then builds CFG edges on top of the frozen AST. The AST data is merged into a frozen layer, while CFG construction writes to a fresh in-heap active layer. Property reads for AST nodes transparently cascade to the frozen layer.

- **Interaction (active-only deletion):** During CFG construction, the tool creates temporary dummy entry/exit nodes in the active layer, then deletes them before freezing. Deletion succeeds because the nodes are in the active layer. Attempting to delete a frozen AST node throws `FrozenLayerModificationException`.

- **Interaction (layer compaction):** After multiple freeze cycles, frozen layers are merged on each freeze call, keeping at most one frozen layer. This ensures query cascade depth never exceeds 2 (active + one frozen).

- **Interaction (analysis state):** During fixpoint iteration, the analysis engine stores abstract states in a direct-indexed array outside `IStorage`, keyed by node sequential ID. Structural properties (node type, source location) are read from the frozen layer. This separation keeps the hot path (~5-10ns array access) independent from the bulk graph data path.

- **Interaction (label hierarchy):** The tool defines function-context labels and their parent relationships in the poset store. When querying edges visible under a given label, the graph layer uses the poset store's adjacency index to determine which labels are dominated, then filters edges accordingly. The poset store's native adjacency indexing handles hundreds of labels without the serialization overhead of metadata-based storage.

- **Interaction (dual persistence):** The main graph store and poset store are persisted independently. CSV export writes two separate directory trees. On reimport, each store is loaded into its own `IStorage` instance, and the graph is reconstructed by injecting both stores.
