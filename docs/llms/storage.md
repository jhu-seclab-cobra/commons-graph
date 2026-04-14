# Storage Backends

> Pluggable graph storage with auto-generated Int IDs, property management, and adjacency queries.

## Quick Start

```kotlin
val storage = NativeStorageImpl()
val nodeA = storage.addNode(mapOf("name" to "foo".strVal))
val nodeB = storage.addNode()
val edgeId = storage.addEdge(nodeA, nodeB, "calls")
storage.setEdgeProperties(edgeId, mapOf("weight" to NumVal(5)))
storage.close()
```

## API

### `IStorage` (extends `Closeable`)

#### Node Operations

- **`val nodeIDs: Set<Int>`** -- All node IDs. Raises `AccessClosedStorageException` if closed.
- **`containsNode(id: Int): Boolean`** -- Check node existence.
- **`addNode(properties: Map<String, IValue> = emptyMap()): Int`** -- Create node, return auto-generated ID.
- **`getNodeProperties(id: Int): Map<String, IValue>`** -- All properties. Raises `EntityNotExistException` if absent.
- **`getNodeProperty(id: Int, name: String): IValue?`** -- Single property. Returns `null` if property absent.
- **`setNodeProperties(id: Int, properties: Map<String, IValue?>)`** -- Update properties. `null` values delete the property.
- **`deleteNode(id: Int)`** -- Delete node and all incident edges.

#### Edge Operations

- **`val edgeIDs: Set<Int>`** -- All edge IDs.
- **`containsEdge(id: Int): Boolean`** -- Check edge existence.
- **`addEdge(src: Int, dst: Int, tag: String, properties: Map<String, IValue> = emptyMap()): Int`** -- Create edge, return auto-generated ID. Raises `EntityNotExistException` if endpoints missing.
- **`getEdgeStructure(id: Int): EdgeStructure`** -- Returns `EdgeStructure(src: Int, dst: Int, tag: String)`.
- **`getEdgeProperties(id: Int): Map<String, IValue>`** -- All properties.
- **`getEdgeProperty(id: Int, name: String): IValue?`** -- Single property.
- **`setEdgeProperties(id: Int, properties: Map<String, IValue?>)`** -- Update properties. `null` values delete.
- **`deleteEdge(id: Int)`** -- Delete an edge.

#### Adjacency

- **`getIncomingEdges(id: Int): Set<Int>`** -- Incoming edge IDs to a node.
- **`getOutgoingEdges(id: Int): Set<Int>`** -- Outgoing edge IDs from a node.

#### Metadata

- **`val metaNames: Set<String>`** -- All metadata property names.
- **`getMeta(name: String): IValue?`** -- Retrieve metadata value.
- **`setMeta(name: String, value: IValue?)`** -- Set or delete (`null`) metadata.

#### Lifecycle

- **`clear()`** -- Remove all nodes, edges, and metadata.
- **`transferTo(target: IStorage): Map<Int, Int>`** -- Copy all data to target. Returns node ID mapping (source to target).
- **`close()`** -- Release resources. All operations raise `AccessClosedStorageException` after close.

### Implementations

- **`NativeStorageImpl`** -- In-memory, single-threaded. No constructor parameters.
- **`NativeConcurStorageImpl`** -- In-memory, thread-safe with read-write locks. No constructor parameters.
- **`LayeredStorageImpl(frozenLayerFactory: () -> IStorage = { NativeStorageImpl() })`** -- Freeze-and-stack for phased pipelines.

### `LayeredStorageImpl` Extra API

- **`val layerCount: Int`** -- Number of layers (1 = active only, 2 = active + frozen).
- **`freeze()`** -- Merge active layer into frozen layer. Resets active layer.

### External Backend Modules

- **`JgraphtStorageImpl`** -- JGraphT backend (module: `commons-graph-impl-jgrapht`).
- **`JgraphtConcurStorageImpl`** -- Concurrent JGraphT backend.
- **`MapDBStorageImpl`** -- MapDB persistent backend (module: `commons-graph-impl-mapdb`).
- **`MapDBConcurStorageImpl`** -- Concurrent MapDB backend.
- **`Neo4jStorageImpl`** -- Neo4j backend (module: `commons-graph-impl-neo4j`).
- **`Neo4jConcurStorageImpl`** -- Concurrent Neo4j backend.

## Gotchas

- `IStorage` IDs are auto-generated `Int` values. Never hard-code or predict them.
- `transferTo` remaps IDs. The returned `Map<Int, Int>` maps source IDs to new target IDs.
- `deleteNode` cascades to all incident edges in the same storage.
- `setNodeProperties`/`setEdgeProperties` with `null` values delete those properties, not set them to null.
- `LayeredStorageImpl.freeze()` merges active + frozen into a new frozen layer. Active layer properties overlay frozen layer properties for the same entity.
- `LayeredStorageImpl` raises `FrozenLayerModificationException` when deleting frozen-layer entities. Only active-layer entities can be deleted.
- `NativeConcurStorageImpl` returns snapshot copies from adjacency queries. Mutations after retrieval do not affect the snapshot.
