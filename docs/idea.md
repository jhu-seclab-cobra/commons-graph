# Graph Abstraction Layer Concept Document

## 1. Context

**Problem Statement**
Graph applications in COBRA need to run on different storage engines, but each engine exposes different access styles and data assumptions. The core problem is to keep application logic stable while storage, persistence mode, and performance strategy can change over time. For large-scale static analysis, graph data can exceed 30GB, requiring layered storage strategies that balance memory efficiency with access performance across different analysis phases. Additionally, label-based edge visibility requires a partial-order structure over labels that can scale to hundreds of function-context paths without being bottlenecked by serialization overhead.

**System Role**
This module is the stable graph domain boundary in COBRA, translating domain-level graph operations into backend-specific storage behavior.

**Data Flow**
- **Inputs:** Domain operations using `NodeID` / `EdgeID`, entity property reads and updates, traversal and neighborhood queries, label hierarchy mutations, layer transition signals (freeze/compact).
- **Outputs:** Domain-consistent node/edge views, deterministic query results, label-filtered traversal results, backend-independent error semantics.
- **Connections:** Upstream services -> Graph domain API (`IGraph` + `IPoset`) -> Graph store contract (`IStorage`) -> Backend implementations / Layered storage composers.

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
            |                                   |
            v                                   v
[IStorage: graph store]             [IStorage: poset store]
  (String + ArcKey)                      (String + ArcKey)
            |                                   |
            +--> Flat store (single-layer)       +--> Flat store (label DAG)
            |       +--> [Native in-memory]
            |       +--> [MapDB persistent]
            |       +--> [JGraphT in-memory]
            |       +--> [Neo4j embedded]
            |
            +--> Layered store (freeze-and-stack)
                    +--> [LayeredStore: N frozen layers + 1 active layer]
```

**Core Concepts**

- **Three-layer architecture**
    The system separates into three layers: a domain layer (`IGraph`, `IPoset`) that speaks in domain types (`NodeID`, `EdgeID`, `Label`), a store layer (`IStorage`) that speaks in primitive types (`String`, `ArcKey`), and an entity layer (`AbcNode`, `AbcEdge`) that bridges domain identity to storage-backed property access. The domain layer owns identity semantics and traversal algorithms; the store layer owns data structure management and persistence; the entity layer owns typed property access. This separation allows each layer to evolve independently.

- **Store contract as generic directed property graph**
    `IStorage` is a generic directed property graph engine that manages vertices (identified by `String`), directed arcs (identified by `ArcKey` encoding `src`, `dst`, `type`), per-vertex and per-arc properties, adjacency indices, and metadata. It does not know about `NodeID`, `EdgeID`, `Label`, or any domain concept. The same `IStorage` implementation can back both the main program-analysis graph and the label partial-order DAG.

- **Dual store architecture**
    `AbcMultipleGraph` holds two `IStorage` instances: one for the main graph (nodes and edges), one for the label partial-order set (labels as vertices, parent relationships as arcs). Both stores are injected by the subclass, persisted independently, and use the same `IStorage` contract. This eliminates the need for metadata-based lattice serialization and provides native adjacency indexing for label hierarchy traversal.

- **Domain identity as boundary constructs**
    `NodeID` and `EdgeID` are domain-layer constructs that exist only at the `IGraph` boundary. `NodeID` is a zero-cost `value class` wrapping `String`. `EdgeID` is a `data class` constructed from `ArcKey` at the boundary. Neither type has an interning pool; deduplication is unnecessary because the store layer uses primitive `String`/`ArcKey` keys directly, and the domain layer caches wrapper objects (`nodeCache`, `edgeCache`) rather than identity objects.

- **Entity as operation view**
    `AbcNode` and `AbcEdge` represent typed access points to properties and identity, while persistence is delegated to the store. This separates domain behavior from physical storage layout.

- **Layered storage as composition**
    Layered storage composes N frozen `IStorage` instances plus one mutable active layer into a unified `IStorage` view. The domain layer sees a single store; internally, reads cascade through layers (active -> frozen in reverse order), writes target only the active layer, and freeze transitions migrate active data to read-only frozen storage. Deletion is restricted to the active layer. Query resolution follows a simple two-phase cascade without deletion tracking.

- **Phase-based freezing**
    Static analysis proceeds in phases (AST construction -> CFG -> DFG -> analysis). Each phase completes a set of graph mutations that become read-only in subsequent phases. Layered storage exploits this by freezing completed phases into read-only storage while keeping the active phase's data in fast heap memory. The `compact` operation merges accumulated frozen layers to keep query chain length bounded.

- **Analysis state externalization**
    High-frequency analysis state (abstract values, dataflow facts) produced during fixpoint iteration should be stored outside `IStorage` in direct-indexed arrays keyed by node sequential IDs. This avoids `IValue` boxing and `HashMap` overhead on the hottest code path, achieving ~5-10ns read/write latency with zero GC allocation per access.

- **Label partial-order as dedicated graph**
    The label hierarchy is a DAG where vertices represent labels and arcs represent parent relationships. In COBRA, labels represent function-context call paths (e.g., `A->B->C->D`), and the partial order reflects subpath containment — not simple string prefix. This means the hierarchy requires genuine graph traversal for ancestor queries and comparability checks. Storing the poset in a dedicated `IStorage` instance provides native adjacency indexing, eliminating the serialization overhead of metadata-based storage when label counts reach hundreds.

- **Graph variants as semantic policy**
    `AbcSimpleGraph` and `AbcMultiGraph` represent different edge uniqueness policies over the same identity model. The variant chooses admissible edge patterns, not storage format.

- **Traits as orthogonal domain abilities**
    Grouping (`TraitNodeGroup`) and labeling are independent capabilities layered on top of the same identity and store contracts.

## 3. Contracts & Flow

**Data Contracts**
- **With upstream modules:** Inputs must be valid IDs and property names; outputs are graph/entity views or explicit domain exceptions.
- **With store implementations:** Stores must preserve vertex/arc semantics, enforce existence constraints, and expose deterministic adjacency/property behavior. Stores operate on `String` vertex IDs and `ArcKey` arc IDs — no domain type awareness.
- **With layered store composers:** Flat store instances serve as building blocks; composers manage layer lifecycle, freeze transitions, and query routing.
- **With trait modules:** Trait behaviors must reuse `IGraph`/`IStorage` invariants and must not redefine entity identity semantics.

**Internal Processing Flow**
1. **Request normalization**: Domain service builds valid `NodeID`/`EdgeID` and invokes graph operations.
2. **Graph-level contract checks**: Graph layer enforces graph policy (existence, uniqueness, variant rules).
3. **ID conversion**: Graph layer converts domain IDs to store primitives (`NodeID.name` -> `String`, `EdgeID` -> `ArcKey`).
4. **Store dispatch**: Graph delegates persistence and adjacency operations to `IStorage`.
5. **Layer routing** (layered store only): Writes go to active layer; reads cascade active -> frozen layers (reverse order); deletes restricted to active layer.
6. **Entity/property materialization**: Returned store data is wrapped as typed entity views via cached wrapper objects; property operations are resolved by store.
7. **Poset dispatch** (label operations): Label hierarchy queries are dispatched to the dedicated poset `IStorage` instance using label names as vertex IDs.
8. **Freeze transition** (layered store only): On `freeze`, active layer data is transferred to a frozen store instance via `transferTo`, active layer is replaced with empty heap store.
9. **Result/exception propagation**: Success returns typed domain outputs; contract violations raise explicit domain exceptions.

## 4. Scenarios

- **Typical:** A service creates two nodes by ID, creates an edge by `EdgeID`, sets edge properties, and queries children/parents. Later, storage backend switches from native memory to MapDB without changing service-level graph calls.

- **Boundary:** A request attempts to create an edge whose source node is missing. The graph/store contract rejects it with `EntityNotExistException` instead of creating implicit nodes.

- **Interaction (layered):** A static analysis tool builds an AST graph, freezes it, then builds CFG edges on top of the frozen AST. The AST data is transferred to a frozen layer, while CFG construction writes to a fresh in-heap active layer. Property reads for AST nodes transparently cascade to the frozen layer.

- **Interaction (active-only deletion):** During CFG construction, the tool creates temporary dummy entry/exit nodes in the active layer, then deletes them before freezing. Deletion succeeds because the nodes are in the active layer. Attempting to delete a frozen AST node throws `FrozenLayerModificationException`.

- **Interaction (layer compaction):** After multiple freeze cycles, 5 frozen layers have accumulated. The tool calls `compact(3)` to merge the top 3 layers into one, reducing query cascade depth from 6 to 4 without changing visible behavior.

- **Interaction (analysis state):** During fixpoint iteration, the analysis engine stores abstract states in a direct-indexed array outside `IStorage`, keyed by node sequential ID. Structural properties (node type, source location) are read from the frozen layer. This separation keeps the hot path (~5-10ns array access) independent from the bulk graph data path.

- **Interaction (label hierarchy):** The tool defines function-context labels (`A->B`, `A->B->C`, `B->C`) and their parent relationships in the poset store. When querying edges visible under label `A->B->C`, the graph layer uses the poset store's adjacency index to determine which labels are dominated, then filters edges accordingly. The poset store's native adjacency indexing handles hundreds of labels without the serialization overhead of metadata-based storage.

- **Interaction (dual persistence):** The main graph store and poset store are persisted independently. CSV export writes two separate directory trees. On reimport, each store is loaded into its own `IStorage` instance, and the graph is reconstructed by injecting both stores.
