# Storage Backends

> Pluggable graph storage with auto-generated Int IDs, property management, and adjacency queries.

## Quick Start

```kotlin
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.strVal

val storage = NativeStorageImpl()
val nodeA = storage.addNode(mapOf("name" to "foo".strVal))
val nodeB = storage.addNode()
val edgeId = storage.addEdge(nodeA, nodeB, "calls")
storage.setEdgeProperties(edgeId, mapOf("weight" to IntVal(5)))
```

## API

### `IStorage` (extends `Flushable`)

**Node Operations**

- **`val nodeIDs: Set<Int>`** -- All node IDs.
- **`containsNode(id: Int): Boolean`** -- Check node existence.
- **`addNode(properties: Map<String, IValue> = emptyMap()): Int`** -- Create node, return auto-generated ID.
- **`getNodeProperties(id: Int): Map<String, IValue>`** -- All properties. Raises `EntityNotExistException` if absent.
- **`getNodeProperty(id: Int, name: String): IValue?`** -- Single property. Returns `null` if property absent.
- **`setNodeProperties(id: Int, properties: Map<String, IValue?>): Unit`** -- Update properties. `null` values delete the property.
- **`deleteNode(id: Int): Unit`** -- Delete node and all incident edges.

**Edge Operations**

- **`val edgeIDs: Set<Int>`** -- All edge IDs.
- **`containsEdge(id: Int): Boolean`** -- Check edge existence.
- **`addEdge(src: Int, dst: Int, tag: String, properties: Map<String, IValue> = emptyMap()): Int`** -- Create edge, return auto-generated ID. Raises `EntityNotExistException` if endpoints missing.
- **`getEdgeStructure(id: Int): EdgeStructure`** -- Returns `EdgeStructure(src: Int, dst: Int, tag: String)`.
- **`getEdgeProperties(id: Int): Map<String, IValue>`** -- All properties.
- **`getEdgeProperty(id: Int, name: String): IValue?`** -- Single property.
- **`setEdgeProperties(id: Int, properties: Map<String, IValue?>): Unit`** -- Update properties. `null` values delete.
- **`deleteEdge(id: Int): Unit`** -- Delete an edge.

**Adjacency**

- **`getIncomingEdges(id: Int): Set<Int>`** -- Incoming edge IDs to a node.
- **`getOutgoingEdges(id: Int): Set<Int>`** -- Outgoing edge IDs from a node.

**Metadata**

- **`val metaNames: Set<String>`** -- All metadata property names.
- **`getMeta(name: String): IValue?`** -- Retrieve metadata value.
- **`setMeta(name: String, value: IValue?): Unit`** -- Set or delete (`null`) metadata.

**Lifecycle**

- **`clear(): Unit`** -- Remove all nodes, edges, and metadata.
- **`transferTo(target: IStorage): Map<Int, Int>`** -- Copy all data to target. `target` must be a different storage instance; raises `IllegalArgumentException` if same. Returns node ID mapping (source to target).
- Persistent backends (`MapDBStorageImpl`, `Neo4jStorageImpl` and their concurrent variants) implement `AutoCloseable`. Call `close()` to release resources. `IStorage` itself is not closeable.

**Concurrency**

- **`val criticalSectionLock: Lock?`** -- The lock `criticalSection` holds. Non-null on a thread-safe implementation, `null` on a single-threaded one.
- **`IStorage.criticalSection { ... }: T`** -- Runs the block atomically against every other operation on the same thread-safe storage and returns its result. Runs unguarded on a single-threaded one. Reentrant: a nested `criticalSection` on the same storage and thread proceeds.

### Implementations

- **`NativeStorageImpl(projection: AdjacencyProjection? = null)`** -- In-memory, single-threaded; omit the projection for ordinary storage.
- **`NativeConcurStorageImpl`** -- In-memory, thread-safe. No constructor parameters.
- **`LayeredStorageImpl(frozenLayerFactory: () -> IStorage = { NativeStorageImpl() })`** -- Freeze-and-stack for phased pipelines. Thread-safe. The Frozen layer is read-only, so the default single-threaded factory stays safe.
- **`LayeredStorageImpl(projection: AdjacencyProjection?, frozenLayerFactory: () -> IStorage = { NativeStorageImpl() })`** -- Construct with one fixed projection; the factory-only constructor remains available to Kotlin and Java callers.
- **`AdjacencyProjection(edgeTag: String, kindProperty: String, keyProperty: String)`** -- Select exact adjacency by an edge tag and two fixed destination fields; configure it before creating rows.
- **`ExactAdjacencyStorage.getExactOutgoingEdges(source: Int, kind: IValue, key: IValue): Set<Int>`** -- Return matching physical edge IDs; requires a configured projection.
- **`ExactAdjacencyReads.getExactOutgoingEdges(source: Int, kinds: Set<IValue>, key: IValue): Set<Int>`** -- Selects the requested kinds in one operation.
- **`ExactAdjacencyStorage.attachExact(source: Int, target: Int, properties: Map<String, IValue> = emptyMap()): Int`** -- Reuse or create the projected relation; raises `IllegalArgumentException` for a conflicting target key.

### `LayeredStorageImpl`

- **`val layerCount: Int`** -- Number of layers (1 = active only, 2 = active + frozen).
- **`freeze(): Unit`** -- Merges the visible image into one Frozen layer and resets the Active layer.
- **`clearActiveLayer(): Unit`** -- Reverts temporary changes; frozen entities and their prior images reappear.
- **`bindPropertyFamilies(families: EdgePropertyFamilies): Unit`** -- Binds the immutable property family; raises `IllegalStateException` for an unequal rebind.
- **`getEdgeProperties(id: Int, layer: StorageLayer): Map<String, IValue>?`** -- Returns the selected complete image or null; `FROZEN` ignores visible deletion.
- **`getEdgeProperty(id: Int, name: String, layer: StorageLayer): IValue?`** -- Returns the selected named property; `ACTIVE` reads only explicit edits on a promoted Edge.
- **`hasActiveEdgeProperty(id: Int, name: String): Boolean`** -- True for an explicit edit, null removals included.

### External Backend Modules

- **`JgraphtStorageImpl`** -- JGraphT backend (module: `commons-graph-impl-jgrapht`).
- **`JgraphtConcurStorageImpl`** -- Concurrent JGraphT backend.
- **`MapDBStorageImpl`** -- MapDB persistent backend (module: `commons-graph-impl-mapdb`).
- **`MapDBConcurStorageImpl`** -- Concurrent MapDB backend.
- **`Neo4jStorageImpl`** -- Neo4j backend (module: `commons-graph-impl-neo4j`).
- **`Neo4jConcurStorageImpl`** -- Concurrent Neo4j backend.

## Configuration

- `NativeStorageImpl` accepts `projection: AdjacencyProjection? = null`; null selects ordinary Storage.
- `LayeredStorageImpl` accepts `frozenLayerFactory: () -> IStorage = { NativeStorageImpl() }`, with an overload accepting `projection: AdjacencyProjection?`; its Frozen layer is read-only.
- Configure projected destination fields before creating projected Edges and keep them unchanged while such an Edge exists.
- Bind an optional `EdgePropertyFamilies` declaration once, before or after import; unequal rebinding fails.

## Gotchas

- Share one storage instance across threads only when `storage.criticalSectionLock != null`. `criticalSection` does not guard a single-threaded storage; two threads on one corrupt it.
- Each single `IStorage` call on a thread-safe storage is atomic. A sequence of calls is not. Wrap every check-then-add, read-modify-write, or read that decides a write in one `storage.criticalSection { }`.
- Graph operations and Label hierarchy operations are atomic on their thread-safe Storage. Labeled traversal sequences are lazy: each step is atomic, but the whole iteration is not. Wrap a group of operations or an iteration that must be atomic together (see [Graph API](graph.md)).
- A copy returned by a thread-safe storage is fixed at the call. Later writes, `freeze()`, and `clearActiveLayer()` do not change it.
- Never call a `suspend` function inside `criticalSection`. The coroutine can resume on another thread, which does not own the lock.
- Never wait inside `criticalSection` for another thread that uses the same storage (`Future.get()`, `CountDownLatch.await()`, `Thread.join()`, `runBlocking`, `awaitAll`). Both threads block forever.
- Take the locks of two storages in one fixed order on every thread. Nesting `a.criticalSection { b.criticalSection { } }` on one thread and the reverse on another deadlocks. So do `a.transferTo(b)` and `b.transferTo(a)` running at the same time.
- Revert listeners on `LayeredStorageImpl` run while the lock is held. A listener never blocks, may read the storage, and never writes it.
- A `RevertListener` gets one `RevertNotice(removed, restored, edgeStructuresBefore)` per revert (`clearActiveLayer()` and `clear()`), empty ones included. `removed` and `restored` are `StorageEntityIds(nodes, edges)`. A restored edge missing from `edgeStructuresBefore` has reappeared.
- Stop every thread that uses a persistent concurrent backend before calling `close()`.

- All graph-layer exceptions (`EntityNotExistException`, `EntityAlreadyExistException`, `InvalidPropNameException`, `FrozenLayerModificationException`) extend `GraphException`. Catch `GraphException` to handle any graph failure uniformly.
- `IStorage` IDs are auto-generated `Int` values. Never hard-code or predict them.
- `transferTo` remaps IDs. The returned `Map<Int, Int>` maps source IDs to new target IDs. `target` must be a different storage instance.
- `deleteNode` cascades to all incident edges in the same storage.
- `setNodeProperties`/`setEdgeProperties` with `null` values delete those properties, not set them to null.
- `LayeredStorageImpl.freeze()` merges active + frozen into a new Frozen layer. Active layer properties overlay Frozen layer properties for the same entity.
- `LayeredStorageImpl` raises `FrozenLayerModificationException` when deleting a frozen node. Deleting a frozen edge masks it until a revert or freeze.
