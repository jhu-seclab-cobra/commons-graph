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
  ├── Node/edge wrapper caching
  ├── edge lookup by (src, dst, type) via adjacency queries
  └── label visibility filtering
            |                                   |
            v                                   v
[IStorage: graph store]             [IStorage: poset store]
  (String node/edge IDs)                (String IDs)
  (InternalID as impl detail)          (InternalID as impl detail)
            |                                   |
            +--> Flat store (single-layer)       +--> Flat store (label DAG)
            |       ├── [Native in-memory]
            |       ├── [MapDB persistent]
            |       ├── [JGraphT in-memory]
            |       └── [Neo4j embedded]
            |
            +--> Layered store (freeze-and-stack)
                    └── [LayeredStore: 1 frozen layer + 1 active layer]
```

**Core Concepts**

- **Three-layer architecture**
    The system separates into three layers: a domain layer (`IGraph`, `IPoset`) that speaks in user-facing types (`NodeID`, `(src, dst, type)` edge tuples, `Label`), a store layer (`IStorage`) that speaks in semantic `String` IDs (`NodeID` for nodes, user-specified `String` for edges), and an entity layer (`AbcNode`, `AbcEdge`) that wraps storage-backed property access. The domain layer owns identity semantics, edge lookup by `(src, dst, type)`, and traversal algorithms; the store layer owns ID management, data structure management, adjacency indices, and persistence; the entity layer owns typed property access and meta-property conventions. **InternalID is now a storage implementation detail, not exposed to domain or entity layers.** This separation allows each layer to evolve independently and eliminates ambiguous ID layers.

- **Store contract as String-keyed directed property graph**
    `IStorage` is a generic directed property graph engine where nodes and edges are identified by semantic `String` IDs. Callers pass `nodeId: String` to `addNode()` and `edgeId: String` to `addEdge(src, dst, edgeId, type)`. Internally, storage implementations may use integer IDs (`InternalID`) for compactness and indexing — this is a pure implementation detail. Edges carry structural metadata (source, destination, type) accessible via `getEdgeSrc`/`getEdgeDst`/`getEdgeType`, which return `String` (NodeID for src/dst). The store manages per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), and metadata. It does not know about `Label` or domain concepts. The same `IStorage` implementation can back both the main program-analysis graph and the label partial-order DAG. This design eliminates ID translation overhead at the domain/entity layer while preserving storage flexibility.

- **Dual store architecture**
    `AbcMultipleGraph` holds two `IStorage` instances: `storage` for the main graph (nodes and edges), `posetStorage` for the label partial-order set (labels as nodes, parent relationships as edges). Both stores are injected by the subclass, persisted independently, and use the same `IStorage` contract. This eliminates the need for metadata-based lattice serialization and provides native adjacency indexing for label hierarchy traversal.

- **Domain identity model (NodeID and EdgeID)**
    `NodeID` is a `typealias` for `String` — the user-provided node name. Nodes are created with `IStorage.addNode(nodeId: String, properties)`, where the caller supplies the semantic ID. The graph layer does not maintain separate caches; all node IDs are String and passed directly to storage.

    Edges have a caller-provided semantic `String` ID (generated as `"$srcNodeId-$type-$dstNodeId"` by the graph layer). The graph layer resolves edge queries by `(src, dst, type)` tuples through storage adjacency queries and endpoint inspection. Edge structural info (source, destination, type) is retrieved via `storage.getEdgeSrc(edgeId)`, `storage.getEdgeDst(edgeId)`, and `storage.getEdgeType(edgeId)`, all returning String or domain types directly.

- **Entity as lightweight operation view**
    `AbcNode` and `AbcEdge` are lightweight property access wrappers backed by storage. `AbcNode` is constructed with `(storage, nodeId: String)` — no InternalID needed. `AbcEdge` is constructed with `(storage, edgeId: String)` — and lazily reads `srcNid`/`dstNid`/`eType` directly from storage without intermediate translation. Properties prefixed with `__` are internal metadata, filtered from external access. **No resolver functions or additional ID mappings are required.**

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
3. **ID generation for edges**: For edge operations, graph layer generates semantic edge ID as `"$srcNodeId-$type-$dstNodeId"`.
4. **Store dispatch**: Graph delegates to `IStorage` using String IDs directly (no intermediate translation). For edge queries, scans adjacency lists using `storage.getOutgoingEdges(src)` and filters by `(dst, type)` tuple.
5. **Layer routing** (layered store only): Writes go to active layer; reads cascade active -> frozen layer; deletes restricted to active layer.
6. **Entity/property materialization**: Returned store data is wrapped as typed entity views via cached wrapper objects (`SoftReference`-based caches); property operations are resolved by store directly using String IDs.
7. **Poset dispatch** (label operations): Label hierarchy queries are dispatched to the dedicated `posetStorage` instance using label names as String node IDs.
8. **Storage endpoint resolution**: When AbcEdge accesses `srcNid`/`dstNid`, it calls `storage.getEdgeSrc(edgeId)`/`storage.getEdgeDst(edgeId)`, which return `String` (NodeID) directly — no resolver functions or reverse lookups needed.
9. **Freeze transition** (layered store only): On `freeze`, all layers are merged into a single new frozen store via `mergeLayerInto`, with String ID preservation (no remapping needed). Active layer is replaced with empty heap store.
10. **Result/exception propagation**: Success returns typed domain outputs; contract violations raise explicit domain exceptions.

## 4. Scenarios

- **Typical:** A service creates two nodes by `NodeID`, creates an edge by `(src, dst, type)`, sets edge properties, and queries children/parents. Later, storage backend switches from native memory to MapDB without changing service-level graph calls.

- **Boundary:** A request attempts to create an edge whose source node is missing. The graph/store contract rejects it with `EntityNotExistException` instead of creating implicit nodes.

- **Interaction (layered):** A static analysis tool builds an AST graph, freezes it, then builds CFG edges on top of the frozen AST. The AST data is merged into a frozen layer, while CFG construction writes to a fresh in-heap active layer. Property reads for AST nodes transparently cascade to the frozen layer.

- **Interaction (active-only deletion):** During CFG construction, the tool creates temporary dummy entry/exit nodes in the active layer, then deletes them before freezing. Deletion succeeds because the nodes are in the active layer. Attempting to delete a frozen AST node throws `FrozenLayerModificationException`.

- **Interaction (layer compaction):** After multiple freeze cycles, frozen layers are merged on each freeze call, keeping at most one frozen layer. This ensures query cascade depth never exceeds 2 (active + one frozen).

- **Interaction (analysis state):** During fixpoint iteration, the analysis engine stores abstract states in a direct-indexed array outside `IStorage`, keyed by node sequential ID. Structural properties (node type, source location) are read from the frozen layer. This separation keeps the hot path (~5-10ns array access) independent from the bulk graph data path.

- **Interaction (label hierarchy):** The tool defines function-context labels and their parent relationships in the poset store. When querying edges visible under a given label, the graph layer uses the poset store's adjacency index to determine which labels are dominated, then filters edges accordingly. The poset store's native adjacency indexing handles hundreds of labels without the serialization overhead of metadata-based storage.

- **Interaction (dual persistence):** The main graph store and poset store are persisted independently. CSV export writes two separate directory trees. On reimport, each store is loaded into its own `IStorage` instance, and the graph is reconstructed by injecting both stores.
