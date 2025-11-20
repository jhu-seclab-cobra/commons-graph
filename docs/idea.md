# Graph Abstraction Layer Concept Document

## 1. Context

**Problem Statement**
Different graph libraries and storage systems (JGraphT, Neo4j, MapDB, in-memory structures) provide distinct APIs and data models, making it difficult to write portable graph code across different backends. COBRA platform requires a unified graph abstraction layer that allows applications to work with any graph storage implementation through a consistent interface, without being coupled to specific backend technologies.

**System Role**
This library serves as the core graph abstraction layer in the COBRA platform, providing a unified intermediate wrapper for different graph structure implementations. It enables applications to interact with various graph storage backends (in-memory, MapDB, JGraphT, Neo4j) through a single, consistent API, while allowing seamless switching between implementations without changing business logic.

**Data Flow**
- **Inputs:** Graph operations (add/remove nodes/edges, property management, traversal queries), storage backend implementations
- **Outputs:** Unified graph API results, consistent entity models, storage-agnostic graph operations
- **Connections:** Application Code → Graph Abstraction Layer → Storage Backend (IStorage implementations)

**Scope Boundaries**
- **Owned:** Unified graph API design, entity abstraction (nodes, edges, IDs), property management system, graph operation interfaces, storage abstraction layer, CSV import/export utilities
- **Not Owned:** Specific storage backend implementations (MapDB, JGraphT, Neo4j are provided as extensions), graph algorithm implementations, external graph database management

## 2. Concepts

**Conceptual Diagram**
```
Application Code
    ↓ (graph operations)
IGraph Interface
    ↓ (delegates to)
IStorage Interface
    ↓ (implemented by)
Storage Backends
    ├─ NativeStorageImpl (in-memory)
    ├─ MapDBStorageImpl (persistent)
    ├─ JGraphTStorageImpl (algorithm-focused)
    └─ Neo4jStorageImpl (distributed)
```

**Core Concepts**

- **Unified Graph Abstraction**: The central concept that defines a consistent API for all graph operations regardless of the underlying storage implementation. This abstraction provides interfaces (`IGraph`, `IStorage`) that encapsulate graph structure manipulation, entity management, and property operations. Scope includes node/edge addition/removal, graph traversal, property access, and query operations, ensuring that application code remains independent of storage backend details.

- **Entity Model**: The concept defining standardized representations of graph entities (nodes and edges) with unique identifiers and typed property storage. This model provides `IEntity`, `AbcNode`, `AbcEdge`, `NodeID`, and `EdgeID` classes that work consistently across all storage backends. Scope includes entity identification, property management, type information, and serialization, enabling seamless entity transfer between different storage systems.

- **Storage Backend Abstraction**: The concept that decouples graph operations from specific storage implementations through the `IStorage` interface. This abstraction allows applications to switch between in-memory, persistent, or distributed storage without code changes. Scope includes node/edge storage operations, property persistence, graph structure queries, and metadata management, with each backend implementing the interface according to its capabilities.

- **Property Management System**: The concept providing type-safe, persistent property storage for graph entities. This system enables entities to store arbitrary typed properties while maintaining consistency across different storage backends. Scope includes property get/set operations, typed property delegates, property serialization, and validation rules, ensuring that properties work uniformly regardless of storage implementation.

- **Graph Type Variants**: The concept supporting different graph semantics (simple graphs vs. multi-graphs) through abstract base classes. This concept provides `AbcSimpleGraph` for graphs with at most one edge between nodes, and `AbcMultiGraph` for graphs allowing multiple edges between the same pair of nodes. Scope includes edge type management, graph name prefixing, and type-specific validation rules, allowing applications to choose appropriate graph semantics for their use cases.

- **Import/Export Utilities**: The concept enabling data exchange between storage backends and external formats. This concept provides CSV-based import/export functionality that works with any `IStorage` implementation, allowing data migration and interoperability. Scope includes entity filtering, format conversion, and file validation, ensuring that graph data can be transferred between systems and formats.

## 3. Contracts & Flow

**Data Contracts**
- **With Application Code:** Provides unified graph API (`IGraph`) with consistent node/edge operations, property management, and traversal methods, independent of storage backend
- **With Storage Backends:** Defines `IStorage` interface contract requiring implementations to support node/edge storage, property persistence, graph structure queries, and metadata operations
- **With Entity Models:** Establishes `IEntity` contract requiring unique identifiers, type information, and property storage capabilities for all graph entities
- **With Import/Export Systems:** Provides `IStorageExporter` and `IStorageImporter` interfaces for CSV-based data exchange, supporting entity filtering and format validation

**Internal Processing Flow**
1. **Graph Initialization** - Application creates graph instance by providing concrete node/edge types and selecting storage backend implementation
2. **Entity Creation** - Graph operations create entities (nodes/edges) through unified API, which delegates to storage backend for persistence
3. **Property Management** - Entity properties are accessed through unified interface, with storage backend handling actual persistence according to implementation
4. **Graph Operations** - Traversal, query, and manipulation operations are performed through unified API, with storage backend providing graph structure information
5. **Storage Abstraction** - All storage operations go through `IStorage` interface, allowing backend implementations to optimize according to their capabilities
6. **Cache Management** - Graph layer maintains caches for node/edge identifiers, synchronized with storage backend for performance
7. **Data Exchange** - Import/export utilities read from or write to storage backends through `IStorage` interface, enabling format conversion and data migration

## 4. Scenarios

- **Typical:** Application creates a graph using `AbcSimpleGraph` with `MapDBStorageImpl` backend, adds nodes and edges through unified API, sets properties on entities, performs graph traversal operations, and later switches to `Neo4jStorageImpl` by changing only the storage initialization code while keeping all graph operation code unchanged.

- **Boundary:** Application attempts to add an edge between non-existent nodes, the system throws `EntityNotExistException` through unified API regardless of storage backend. Another boundary case occurs when storage backend is closed, all operations throw `AccessClosedStorageException` consistently across all backends.

- **Interaction:** Application code uses `IGraph` interface to add nodes and edges, graph layer delegates to `IStorage` implementation for actual persistence, storage backend (e.g., `MapDBStorageImpl`) handles disk-based storage according to MapDB capabilities, entity properties are managed through unified `IEntity` interface with storage backend handling serialization, import/export utilities use `IStorage` interface to read/write data enabling format conversion, and graph operations maintain caches synchronized with storage backend for efficient query performance.

