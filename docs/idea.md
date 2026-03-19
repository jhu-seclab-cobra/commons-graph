# Graph Abstraction Layer Concept Document

## 1. Context

**Problem Statement**
Graph applications in COBRA need to run on different storage engines, but each engine exposes different access styles and data assumptions. The core problem is to keep application logic stable while storage, persistence mode, and performance strategy can change over time. For large-scale static analysis, graph data can exceed 30GB, requiring layered storage strategies that balance memory efficiency with access performance across different analysis phases. Additionally, label-based edge visibility requires a partial-order structure over labels that can scale to hundreds of function-context paths without being bottlenecked by serialization overhead.

**System Role**
This module is the stable graph domain boundary in COBRA, translating domain-level graph operations into backend-specific storage behavior.

**Data Flow**
- **Inputs:** Domain operations using `NodeID` (user-provided string) and `(src, dst, tag)` edge tuples, entity property reads and updates, traversal and neighborhood queries, label hierarchy mutations, layer transition signals (freeze/compact).
- **Outputs:** Domain-consistent node/edge views, deterministic query results, label-filtered traversal results, backend-independent error semantics.
- **Connections:** Upstream services -> Graph domain API (`IGraph` + `IPoset`) -> Graph store contract (`IStorage` with caller-provided `String` IDs) -> Backend implementations / Layered storage composers.

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
  ├── Deterministic edge IDs: "$src-$tag-$dst"
  ├── Node/edge wrapper caching
  └── Label visibility filtering
            |                                   |
            v                                   v
[IStorage: graph store]             [IStorage: poset store]
  (caller-provided String IDs)          (caller-provided String IDs)
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
    The system separates into three layers: a domain layer (`IGraph`, `IPoset`) that speaks in user-facing types (`NodeID`, `(src, dst, tag)` edge tuples, `Label`), a store layer (`IStorage`) that speaks in caller-provided `String` IDs, and an entity layer (`AbcNode`, `AbcEdge`) that wraps storage-backed property access. The domain layer owns edge uniqueness semantics and graph-level policy. The store layer owns data structure management, adjacency indices, and persistence. The entity layer owns typed property access via the `bind()` pattern.

- **Store contract as caller-ID directed property graph**
    `IStorage` is a generic directed property graph engine where nodes and edges are identified by caller-provided `String` IDs. Callers pass IDs to `addNode(nodeId)` and `addEdge(src, dst, edgeId, tag)` — the store does not generate IDs. Edges carry structural metadata (source, destination, tag) accessible via `getEdgeStructure(id)` (returning `EdgeStructure(src, dst, tag)`) or the convenience methods `getEdgeSrc`/`getEdgeDst`/`getEdgeTag`. The store manages per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), and metadata. It does not know about `NodeID`, `Label`, or domain concepts. The same `IStorage` implementation can back both the main program-analysis graph and the label partial-order DAG. Caller-controlled IDs allow layered storage to perform freeze merges and shadow entry creation through the standard `IStorage` interface, without requiring internal methods that break encapsulation.

- **Dual store architecture**
    `AbcMultipleGraph` holds two `IStorage` instances: `storage` for the main graph (nodes and edges), `posetStorage` for the label partial-order set (labels as nodes, parent relationships as edges). Both stores are injected by the subclass, persisted independently, and use the same `IStorage` contract. This eliminates the need for metadata-based lattice serialization and provides native adjacency indexing for label hierarchy traversal.

- **Domain identity model (NodeID and EdgeID)**
    `NodeID` is a `typealias` for `String` — the user-provided node name. When a node is created via `graph.addNode(nodeId)`, the graph layer calls `storage.addNode(nodeId)` directly — the `NodeID` is the storage key. No bidirectional cache or ID translation is needed at the graph boundary.

    Edges are created via `graph.addEdge(src, dst, tag)`, which generates a deterministic `edgeId` string (`"$src-$tag-$dst"`) and calls `storage.addEdge(src, dst, edgeId, tag)`. Edge lookup by `(src, dst, tag)` constructs the deterministic edgeId and checks `storage.containsEdge(edgeId)` — no index or adjacency scan is needed.

- **Entity as lightweight operation view**
    `AbcNode` and `AbcEdge` are lightweight property access wrappers backed by storage. Both use a no-arg constructor followed by `bind(storage, id)` injection by the graph layer. `AbcNode` uses `nodeId` directly for all storage operations. `AbcEdge` resolves `srcNid`/`dstNid`/`eTag` lazily via `storage.getEdgeStructure(edgeId)`, which returns the structural info in a single lookup. All properties in the entity namespace are user properties — no internal metadata is stored in the property namespace.

- **Layered storage as composition**
    Layered storage composes one frozen `IStorage` instance plus one mutable active layer into a unified `IStorage` view. The domain layer sees a single store; internally, reads cascade through layers (active -> frozen), writes target only the active layer, and freeze transitions migrate active data to read-only frozen storage via merge. Deletion is restricted to the active layer. Query resolution follows a simple two-phase cascade without deletion tracking. Because `IStorage` uses caller-controlled `String` IDs, layered storage can perform all operations (freeze merges, shadow entry creation) through the standard interface — no internal methods or counter manipulation is needed.

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
- **With upstream modules:** Inputs must be valid `NodeID` strings, edge `(src, dst, tag)` tuples, and property names; outputs are graph/entity views or explicit domain exceptions.
- **With store implementations:** Stores must preserve node/edge semantics, enforce existence constraints, and expose deterministic adjacency/property behavior. Stores operate on caller-provided `String` IDs — no domain type awareness. Storage implementations may internally use integer indices for compactness, but this is an implementation detail not exposed to callers.
- **With layered store composers:** Flat store instances serve as building blocks; composers manage layer lifecycle, freeze transitions, and query routing. Composers depend only on `IStorage` interface — caller-controlled IDs enable all layer operations without internal method access.
- **With trait modules:** Trait behaviors must reuse `IGraph`/`IStorage` invariants and must not redefine entity identity semantics.

**Internal Processing Flow**
1. **Request normalization**: Domain service provides valid `NodeID` and edge `(src, dst, tag)` tuple, invokes graph operations.
2. **Graph-level contract checks**: Graph layer enforces graph policy (existence, uniqueness, variant rules).
3. **Direct store dispatch**: Graph layer passes `NodeID` strings and generated `edgeId` strings directly to `IStorage`. No ID translation is needed — `String` IDs flow through unchanged.
4. **Store-internal optimization**: Storage implementations may internally map `String` IDs to integer indices for compactness and cache-friendly access. This mapping is a pure implementation detail, invisible to callers.
5. **Layer routing** (layered store only): Writes go to active layer; reads cascade active -> frozen layer; deletes restricted to active layer.
6. **Entity/property materialization**: Returned store data is wrapped as typed entity views via cached wrapper objects (`SoftReference`-based caches); property operations use `String` IDs for store access.
7. **Poset dispatch** (label operations): Label hierarchy queries are dispatched to the dedicated `posetStorage` instance using label strings as IDs.
8. **Storage endpoint resolution**: When `AbcEdge` accesses `srcNid`/`dstNid`/`eTag`, it calls `storage.getEdgeStructure(edgeId)` which returns `EdgeStructure(src, dst, tag)` in a single lazy lookup.
9. **Freeze transition** (layered store only): On `freeze`, all layers are merged into a single new frozen store. Active layer is replaced with a fresh empty store. String IDs are preserved across merges — no ID remapping is needed.
10. **Result/exception propagation**: Success returns typed domain outputs; contract violations raise explicit domain exceptions.

## 4. Scenarios

- **Typical:** A service creates two nodes by `NodeID`, creates an edge by `(src, dst, tag)`, sets edge properties, and queries children/parents. The graph layer passes `NodeID` strings directly to storage. Later, storage backend switches from native memory to MapDB without changing service-level graph calls.

- **Boundary:** A request attempts to create an edge whose source node is missing. The graph/store contract rejects it with `EntityNotExistException` instead of creating implicit nodes.

- **Interaction (layered):** A static analysis tool builds an AST graph, freezes it, then builds CFG edges on top of the frozen AST. The AST data is merged into a frozen layer, while CFG construction writes to a fresh active layer. Property reads for AST nodes transparently cascade to the frozen layer.

- **Interaction (active-only deletion):** During CFG construction, the tool creates temporary dummy entry/exit nodes in the active layer, then deletes them before freezing. Deletion succeeds because the nodes are in the active layer. Attempting to delete a frozen AST node throws `FrozenLayerModificationException`.

- **Interaction (layer compaction):** After multiple freeze cycles, frozen layers are merged on each freeze call, keeping at most one frozen layer. This ensures query cascade depth never exceeds 2 (active + one frozen).

- **Interaction (analysis state):** During fixpoint iteration, the analysis engine stores abstract states in a direct-indexed array outside `IStorage`, keyed by node sequential ID. Structural properties (node type, source location) are read from the frozen layer. This separation keeps the hot path (~5-10ns array access) independent from the bulk graph data path.

- **Interaction (label hierarchy):** The tool defines function-context labels and their parent relationships in the poset store. When querying edges visible under a given label, the graph layer uses the poset store's adjacency index to determine which labels are dominated, then filters edges accordingly. The poset store's native adjacency indexing handles hundreds of labels without the serialization overhead of metadata-based storage.

- **Interaction (dual persistence):** The main graph store and poset store are persisted independently. CSV export writes two separate directory trees. On reimport, each store is loaded into its own `IStorage` instance, and the graph is reconstructed by injecting both stores.
