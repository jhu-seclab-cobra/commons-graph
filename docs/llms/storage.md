# Storage Backends

> Pluggable graph storage with auto-generated Int IDs, property management, and adjacency queries.

## Quick Start

```kotlin
val storage = NativeStorageImpl()
val nodeA = storage.addNode(mapOf("name" to "foo".strVal))
val nodeB = storage.addNode()
val edgeId = storage.addEdge(nodeA, nodeB, "calls")
storage.setEdgeProperties(edgeId, mapOf("weight" to IntVal(5)))
```

## API

### `IStorage` (extends `Flushable`)

#### Node Operations

- **`val nodeIDs: Set<Int>`** -- All node IDs.
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
- **`transferTo(target: IStorage): Map<Int, Int>`** -- Copy all data to target. `target` must be a different storage instance; raises `IllegalArgumentException` if same. Returns node ID mapping (source to target).
- Persistent backends (`MapDBStorageImpl`, `Neo4jStorageImpl` and their concurrent variants) implement `AutoCloseable`. Call `close()` to release resources. `IStorage` itself is not closeable.

#### Concurrency

- **`val criticalSectionLock: Lock?`** -- The lock `criticalSection` holds. Non-null on a thread-safe implementation, `null` on a single-threaded one.
- **`IStorage.criticalSection { ... }: T`** -- Runs the block atomically against every other operation on the same thread-safe storage and returns its result. Runs unguarded on a single-threaded one. Reentrant: a nested `criticalSection` on the same storage and thread proceeds.

### Implementations

- **`NativeStorageImpl`** -- In-memory, single-threaded. No constructor parameters.
- **`NativeConcurStorageImpl`** -- In-memory, thread-safe. No constructor parameters.
- **`LayeredStorageImpl(frozenLayerFactory: () -> IStorage = { NativeStorageImpl() })`** -- Freeze-and-stack for phased pipelines. Thread-safe. The frozen layer is read-only, so the default single-threaded factory stays safe.

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

## Concurrent Use

```kotlin
val storage = NativeConcurStorageImpl()
val counter = storage.addNode(mapOf("n" to 0.intVal))
storage.criticalSection {
    val n = (storage.getNodeProperty(counter, "n") as IntVal).core
    storage.setNodeProperties(counter, mapOf("n" to (n + 1).intVal))
}
```

| Implementation | Share across threads | Returned maps and sets |
|----------------|----------------------|------------------------|
| `NativeStorageImpl`, `JgraphtStorageImpl`, `MapDBStorageImpl`, `Neo4jStorageImpl` | Never | -- |
| `NativeConcurStorageImpl`, `JgraphtConcurStorageImpl`, `MapDBConcurStorageImpl`, `Neo4jConcurStorageImpl`, `LayeredStorageImpl` | Yes | Copies |

- Share one storage instance across threads only when `storage.criticalSectionLock != null`. `criticalSection` does not guard a single-threaded storage; two threads on one corrupt it.
- Each single `IStorage` call on a thread-safe storage is atomic. A sequence of calls is not. Wrap every check-then-add, read-modify-write, or read that decides a write in one `storage.criticalSection { }`.
- Graph, poset, and labeled-edge operations already run inside `criticalSection`. Wrap only a group of them that must be atomic together (see `llms/graph.md`).
- A copy returned by a thread-safe storage is fixed at the call. Later writes, `freeze()`, and `clearActiveLayer()` do not change it.
- Never call a `suspend` function inside `criticalSection`. The coroutine can resume on another thread, which does not own the lock.
- Never wait inside `criticalSection` for another thread that uses the same storage (`Future.get()`, `CountDownLatch.await()`, `Thread.join()`, `runBlocking`, `awaitAll`). Both threads block forever.
- Take the locks of two storages in one fixed order on every thread. Nesting `a.criticalSection { b.criticalSection { } }` on one thread and the reverse on another deadlocks. So do `a.transferTo(b)` and `b.transferTo(a)` running at the same time.
- Revert listeners on `LayeredStorageImpl` run while the lock is held. A listener never blocks, may read the storage, and never writes it.
- Stop every thread that uses a persistent concurrent backend before calling `close()`.

## Gotchas

- All graph-layer exceptions (`EntityNotExistException`, `EntityAlreadyExistException`, `InvalidPropNameException`, `FrozenLayerModificationException`) extend `GraphException`. Catch `GraphException` to handle any graph failure uniformly.
- `IStorage` IDs are auto-generated `Int` values. Never hard-code or predict them.
- `transferTo` remaps IDs. The returned `Map<Int, Int>` maps source IDs to new target IDs. `target` must be a different storage instance.
- `deleteNode` cascades to all incident edges in the same storage.
- `setNodeProperties`/`setEdgeProperties` with `null` values delete those properties, not set them to null.
- `LayeredStorageImpl.freeze()` merges active + frozen into a new frozen layer. Active layer properties overlay frozen layer properties for the same entity.
- `LayeredStorageImpl` raises `FrozenLayerModificationException` when deleting frozen-layer entities. Only active-layer entities can be deleted.
