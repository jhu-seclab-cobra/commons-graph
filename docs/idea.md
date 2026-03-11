# Graph Abstraction Layer Concept Document

## 1. Context

**Problem Statement**
Graph applications in COBRA need to run on different storage engines, but each engine exposes different access styles and data assumptions. The core problem is to keep application logic stable while storage, persistence mode, and performance strategy can change over time. For large-scale static analysis, graph data can exceed 30GB, requiring layered storage strategies that balance memory efficiency with access performance across different analysis phases.

**System Role**
This module is the stable graph domain boundary in COBRA, translating domain-level graph operations into backend-specific storage behavior.

**Data Flow**
- **Inputs:** Domain operations using `NodeID` / `EdgeID`, entity property reads and updates, traversal and neighborhood queries, layer transition signals (freeze/compact).
- **Outputs:** Domain-consistent node/edge views, deterministic query results, backend-independent error semantics.
- **Connections:** Upstream services → Graph domain API (`IGraph`) → Storage contract (`IStorage`) → Backend implementations / Layered storage composers.

**Scope Boundaries**
- **Owned:** Graph domain vocabulary, entity identity model, graph/storage contracts, layered storage composition, node grouping trait, label lattice framework, metadata contract, import/export contract concepts.
- **Not Owned:** Backend-specific tuning (MapDB, JGraphT, Neo4j implementations), graph algorithm libraries, database lifecycle operations, external deployment concerns.

## 2. Concepts

**Conceptual Diagram**
```
[Domain Services]
            |
            v
[IGraph: ID-first graph contract]
            |
            v
[IStorage: backend-agnostic storage contract]
            |
            +--> Flat storage (single-layer, full CRUD)
            |       +--> [Native in-memory]
            |       +--> [MapDB persistent]
            |       +--> [JGraphT in-memory]
            |       +--> [Neo4j embedded]
            |
            +--> Layered storage (freeze-and-stack)
                    +--> [LayeredStorage: N frozen layers + 1 active layer]
                            |
                            +--> frozen layers (read-only, injectable factory)
                            +--> active layer (in-heap, mutable, sole write target)
```

**Core Concepts**

- **Identity-first graph access**
    `NodeID` and `EdgeID` are the canonical identity carriers for all graph operations. This keeps call semantics explicit, avoids hidden name-prefix rules, and makes contracts portable across backends.

- **Entity as operation view**
    `AbcNode` and `AbcEdge` represent typed access points to properties and identity, while persistence is delegated to storage. This separates domain behavior from physical storage layout.

- **Storage contract as capability floor**
    `IStorage` defines the minimum backend behavior required by the domain: existence checks, add/delete, property map updates, adjacency queries, metadata, and lifecycle management. All storage types implement this same contract.

- **Layered storage as composition**
    Layered storage composes N frozen `IStorage` instances plus one mutable active layer into a unified `IStorage` view. The domain layer sees a single `IStorage`; internally, reads cascade through layers (active → frozen in reverse order), writes target only the active layer, and freeze transitions migrate active data to read-only frozen storage. Deletion is restricted to the active layer — frozen layers are immutable. Query resolution follows a simple two-phase cascade without deletion tracking.

- **Phase-based freezing**
    Static analysis proceeds in phases (AST construction → CFG → DFG → analysis). Each phase completes a set of graph mutations that become read-only in subsequent phases. Layered storage exploits this by freezing completed phases into read-only storage while keeping the active phase's data in fast heap memory. The `compact` operation merges accumulated frozen layers to keep query chain length bounded. In typical static analysis workloads, frozen layers contain structural data (AST, CFG) that is relatively small, while the active layer holds analysis state that dominates memory usage.

- **Analysis state externalization**
    High-frequency analysis state (abstract values, dataflow facts) produced during fixpoint iteration should be stored outside `IStorage` in direct-indexed arrays keyed by node sequential IDs. This avoids `IValue` boxing and `HashMap` overhead on the hottest code path, achieving ~5-10ns read/write latency with zero GC allocation per access.

- **Graph variants as semantic policy**
    `AbcSimpleGraph` and `AbcMultiGraph` represent different edge uniqueness policies over the same identity model. The variant chooses admissible edge patterns, not storage format.

- **Traits as orthogonal domain abilities**
    Grouping (`TraitNodeGroup`) and labeling/lattice are independent capabilities layered on top of the same identity and storage contracts.

## 3. Contracts & Flow

**Data Contracts**
- **With upstream modules:** Inputs must be valid IDs and property names; outputs are graph/entity views or explicit domain exceptions.
- **With storage implementations:** Storage must preserve ID semantics, enforce existence constraints, and expose deterministic adjacency/property behavior.
- **With layered storage composers:** Flat storage instances serve as building blocks; composers manage layer lifecycle, freeze transitions, and query routing.
- **With trait modules:** Trait behaviors must reuse `IGraph`/`IStorage` invariants and must not redefine entity identity semantics.

**Internal Processing Flow**
1. **Request normalization**: Domain service builds valid `NodeID`/`EdgeID` and invokes graph operations.
2. **Graph-level contract checks**: Graph layer enforces graph policy (existence, uniqueness, variant rules).
3. **Storage dispatch**: Graph delegates persistence and adjacency operations to `IStorage`.
4. **Layer routing** (layered storage only): Writes go to active layer; reads cascade active → frozen layers (reverse order); deletes restricted to active layer.
5. **Entity/property materialization**: Returned IDs are exposed as typed entity views; property operations are resolved by storage.
6. **Freeze transition** (layered storage only): On `freeze`, active layer data is transferred to a frozen storage instance via `transferTo`, active layer is replaced with empty heap storage.
7. **Result/exception propagation**: Success returns typed domain outputs; contract violations raise explicit domain exceptions.

## 4. Scenarios

- **Typical:** A service creates two nodes by ID, creates an edge by `EdgeID`, sets edge properties, and queries children/parents. Later, storage backend switches from native memory to MapDB without changing service-level graph calls.

- **Boundary:** A request attempts to create an edge whose source node is missing. The graph/storage contract rejects it with `EntityNotExistException` instead of creating implicit nodes.

- **Interaction (layered):** A static analysis tool builds an AST graph, freezes it, then builds CFG edges on top of the frozen AST. The AST data is transferred to a frozen layer, while CFG construction writes to a fresh in-heap active layer. Property reads for AST nodes transparently cascade to the frozen layer.

- **Interaction (active-only deletion):** During CFG construction, the tool creates temporary dummy entry/exit nodes in the active layer, then deletes them before freezing. Deletion succeeds because the nodes are in the active layer. Attempting to delete a frozen AST node throws `FrozenLayerModificationException`.

- **Interaction (layer compaction):** After multiple freeze cycles, 5 frozen layers have accumulated. The tool calls `compact(3)` to merge the top 3 layers into one, reducing query cascade depth from 6 to 4 without changing visible behavior.

- **Interaction (analysis state):** During fixpoint iteration, the analysis engine stores abstract states in a direct-indexed array outside `IStorage`, keyed by node sequential ID. Structural properties (node type, source location) are read from the frozen layer. This separation keeps the hot path (~5-10ns array access) independent from the bulk graph data path.
