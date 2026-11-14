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

- **`val tagGroup: (String) -> String`** -- Constructor-fixed pure mapping of a complete immutable tag to its Tag group; default identity.
- **`getIncomingEdges(id: Int, group: String): Set<Int>`** -- Copies selected IDs in full directional encounter order; raises `EntityNotExistException` for a missing node.
- **`getOutgoingEdges(id: Int, group: String): Set<Int>`** -- Copies selected IDs in full directional encounter order; raises `EntityNotExistException` for a missing node.
- **`getIncomingEdges(id: Int, groups: Set<String>): Set<Int>`** -- Copies IDs selected by any requested Tag group in full directional encounter order.
- **`getOutgoingEdges(id: Int, groups: Set<String>): Set<Int>`** -- Copies IDs selected by any requested Tag group in full directional encounter order.
- **`getEdges(src: Int, dst: Int, tag: String? = null): Set<Int>`** -- Copies pair IDs, optionally restricted to the complete tag; returns empty for missing endpoints or matches.
- **`findEdge(src: Int, dst: Int): Int?`** -- First pair ID, or null for missing endpoints or matches.
- **`findEdge(src: Int, dst: Int, tag: String): Int?`** -- First complete-triple ID, or null for missing endpoints or matches.

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

- **`NativeStorageImpl(projection: AdjacencyProjection? = null, tagGroup: (String) -> String = { it })`** -- In-memory, single-threaded; omit the projection for ordinary storage.
- **`NativeConcurStorageImpl(tagGroup: (String) -> String = { it })`** -- In-memory, thread-safe.
- **`LayeredStorageImpl(frozenLayerFactory: () -> IStorage = { NativeStorageImpl() })`** -- Freeze-and-stack for phased pipelines. Thread-safe. The Frozen layer is read-only, so the default single-threaded factory stays safe.
- **`LayeredStorageImpl(projection: AdjacencyProjection?, frozenLayerFactory: () -> IStorage = { NativeStorageImpl() })`** -- Construct with one fixed projection; the factory-only constructor remains available to Kotlin and Java callers.
- **`LayeredStorageImpl(projection: AdjacencyProjection?, tagGroup: (String) -> String, frozenLayerFactory: () -> IStorage = { NativeStorageImpl(tagGroup = tagGroup) })`** -- Construct with fixed Tag group selection and the same default frozen grouping.
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

- `NativeStorageImpl` accepts `projection: AdjacencyProjection? = null`; null selects ordinary Storage. `NativeStorageImpl` and `NativeConcurStorageImpl` accept `tagGroup: (String) -> String = { it }`.
- `LayeredStorageImpl` accepts `frozenLayerFactory: () -> IStorage = { NativeStorageImpl() }`, with an overload accepting `projection: AdjacencyProjection?`; its Frozen layer is read-only.
- Select the `LayeredStorageImpl` overload with `projection` and `tagGroup` for custom grouping; keep the function pure and fixed for the Storage lifetime.
- Configure projected destination fields before creating projected Edges and keep them unchanged while such an Edge exists.
- Bind an optional `EdgePropertyFamilies` declaration once, before or after import; unequal rebinding fails.

## Gotchas

- The typed reader/writer, `ArrayStorage`, schema keys and scoped cell readers in [Storage design](../design-storage.md#typed-storage-capabilities) are not available APIs; missing implementation is listed in [Storage Open Gaps](../design-gaps-storage.md#typed-storage-implementation).
- Compatibility outer-map copies do not freeze nested mutable `IValue` content; preserve ownership until the captured-snapshot boundary is implemented.

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
- A Tag group never changes complete-tag identity: grouping `write@1` as `write` does not make an exact lookup of `write` match it.
- Native directional reads preserve insertion order; layered reads place frozen rows before active-only rows, and property promotion retains frozen order.
- `deleteNode` cascades to all incident edges in the same storage.
- `setNodeProperties`/`setEdgeProperties` with `null` values delete those properties, not set them to null.
- `LayeredStorageImpl.freeze()` merges active + frozen into a new Frozen layer. Active layer properties overlay Frozen layer properties for the same entity.
- `LayeredStorageImpl` raises `FrozenLayerModificationException` when deleting a frozen node. Deleting a frozen edge masks it until a revert or freeze.

## Capability adapters

- **`StorageAccess(storage: LayeredStorage)`** — Takes ownership; exposes `reader`, `writer`, `compatibility` and idempotent `close()`.
- **`GraphReader.read(action: (StorageReadScope) -> R): R`** — Supplies a read-only scope; saved scopes and iterators expire at exit.
- **`GraphWriter.write(action: (StorageWriteScope) -> R): R`** — Supplies a write-only scope with compound exclusion, without callback rollback.
- **`GraphWriter.freeze()` / `clearActiveLayer()` / `clear()`** — Applies the existing layered lifecycle.
- **`StorageReadScope.getNodeIDs()` / `getEdgeIDs()`** — Returns fixed selected ID snapshots.
- **`StorageReadScope.getNodeProperties(id)` / `getEdgeProperties(id)`** — Returns detached fixed property maps.
- **`StorageReadScope.getIncomingEdges(id, group?)` / `getOutgoingEdges(id, group?)`** — Returns ordered iterators valid inside the callback.
- **`StorageWriteScope.addNode(properties)` / `addEdge(src, dst, tag, properties)`** — Captures values and returns storage IDs.
- Keep no direct access to the storage supplied to `StorageAccess`; close its owner after releasing every graph.
- Exit the read scope before writing, freezing, reverting, clearing, closing or acquiring a compatibility critical section; each attempt throws `IllegalStateException`.
- Use borrowed scopes on their entering thread only; copied property content and selected ID snapshots may escape.
- Reacquire integer entity IDs after `clear`; integers do not carry a stored-entity lifetime.

## Registered fields and cells

Construct `LayeredStorageImpl(StorageSchema(keys))` before `StorageAccess`. A `PropertyKey<T>` binds one compatibility name, runtime type and `PropertyCodec<T>`; the codec validates/captures immutable payloads and converts independent compatibility values. Registration is fixed at construction.

- `read.getNodeProperty(id,key)` and `read.getEdgeProperty(id,key,layer)` return optional immutable payloads without a codec cycle. Required `getNodeInt`, `getNodeLong`, `getEdgeInt` and `getEdgeLong` reject absence rather than returning zero.
- `write.setNodeProperty(id,key,value)` and `write.setEdgeProperty(id,key,value)` capture before publication.
- `IndexedPropertyKey<T>` registers one anchor, cell prefix and opaque pair-address codec. `read.edgeCells(id,key,layer).lookup(first,second)` selects one payload without enumerating the directory; `members()` returns fixed membership. `write.setEdgeCell` captures the changed payload and publishes membership/cell together.
- `PropertyValue.capture(key,value)` admits a typed payload. `membership(key,values)` admits fixed family membership; `legacy(value)` captures an extension value. A retained value supports typed `get(key)`, `members(key)` and independent compatibility `snapshot()`.
- `getNodeContents`/`getEdgeContents` return fixed maps of admitted values; named `getNodeContent`/`getEdgeContent` select one property. `setNodeContents`/`setEdgeContents` validate the complete update before mutation and retain already admitted payloads without recapture. `captureNodeContents`/`captureEdgeContents` and `validateNodeContents`/`validateEdgeContents` prepare caller-owned buffered writes without publishing them.

An escaped payload or property-content map retains no mutable row, directory or borrowing scope. A borrowed cell reader expires at callback exit. Family selection and explicit deletion retain the layered contracts; full compatibility snapshots pay for their requested values. Schema meaning and reference interpretation belong to the caller.
