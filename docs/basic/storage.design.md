# Storage Module Design

## Design Overview

- **Classes**: `IStorage`, `IStorage.EdgeStructure`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`, `IStorageExporter`, `IStorageImporter`, `NativeCsvIOImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `LayeredStorageImpl` implements `IStorage` (composes at most one frozen `IStorage` layer + one mutable active layer); `NativeCsvIOImpl` implements `IStorageExporter` and `IStorageImporter`
- **Abstract**: `IStorage` (implemented by all storage types); `IStorageExporter` (implemented by `NativeCsvIOImpl`); `IStorageImporter` (implemented by `NativeCsvIOImpl`)
- **Exceptions**: `AccessClosedStorageException` raised on closed storage; `EntityAlreadyExistException` raised on duplicate add; `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when attempting to delete entities from frozen layers in `LayeredStorageImpl`
- **Dependency roles**: Data holders: `IValue`, `EdgeStructure`. Orchestrator: `IStorage` implementations. Composer: `LayeredStorageImpl` (layers multiple `IStorage` instances). Helpers: `NativeCsvIOImpl` (inputs by argument).

The storage layer is the **backend-agnostic directed property graph engine**. It manages nodes and edges identified by caller-provided `String` IDs, per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), edge structural metadata (source, destination, tag), and graph-level metadata. It does not know about domain types (`Label`) — those belong to the graph and entity layers.

`IStorage` operates on caller-provided `String` IDs directly. Callers pass node IDs and edge IDs to `addNode()` and `addEdge()`. Caller-controlled IDs are a deliberate design choice: layered storage needs to create entries with specific IDs (during freeze merges and shadow entry creation). With caller-controlled IDs, all layer operations work through the standard `IStorage` interface without internal methods.

It provides two tiers of storage capability:

1. **Flat storage** (`NativeStorageImpl`, `NativeConcurStorageImpl`, external `MapDBStorageImpl` / `JgraphtStorageImpl` / `Neo4jStorageImpl`) — single-layer, full CRUD
2. **Layered storage** (`LayeredStorageImpl`) — at most one frozen layer (read-only) + one mutable active layer; writes target active layer only; deletion restricted to active layer; frozen layers created via injectable factory (default: `NativeStorageImpl`)

The same `IStorage` implementation can back both the main program-analysis graph and the label partial-order DAG.

---

## Class / Type Specifications

### IStorage

**Responsibility:** Defines the minimum capability contract all storage backends must implement. All entity IDs are caller-provided `String` values.

**Methods:**

```kotlin
interface IStorage : Closeable {
    val nodeIDs: Set<String>
    val edgeIDs: Set<String>

    fun containsNode(id: String): Boolean
    fun addNode(nodeId: String, properties: Map<String, IValue> = emptyMap()): String
    fun getNodeProperties(id: String): Map<String, IValue>
    fun getNodeProperty(id: String, name: String): IValue?
    fun setNodeProperties(id: String, properties: Map<String, IValue?>)
    fun deleteNode(id: String)

    data class EdgeStructure(val src: String, val dst: String, val tag: String)

    fun containsEdge(id: String): Boolean
    fun addEdge(src: String, dst: String, edgeId: String, tag: String, properties: Map<String, IValue> = emptyMap()): String
    fun getEdgeStructure(id: String): EdgeStructure
    fun getEdgeSrc(id: String): String
    fun getEdgeDst(id: String): String
    fun getEdgeTag(id: String): String
    fun getEdgeProperties(id: String): Map<String, IValue>
    fun getEdgeProperty(id: String, name: String): IValue?
    fun setEdgeProperties(id: String, properties: Map<String, IValue?>)
    fun deleteEdge(id: String)

    fun getIncomingEdges(id: String): Set<String>
    fun getOutgoingEdges(id: String): Set<String>

    val metaNames: Set<String>
    fun getMeta(name: String): IValue?
    fun setMeta(name: String, value: IValue?)

    fun clear()
    fun transferTo(target: IStorage)
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addNode` | Creates a node with optional initial properties | `nodeId`: caller-provided String ID; `properties`: initial property map | `String` — the same nodeId | `EntityAlreadyExistException` if nodeId already exists; `AccessClosedStorageException` if closed |
| `addEdge` | Creates an edge between two existing nodes | `src`: source node ID; `dst`: destination node ID; `edgeId`: caller-provided edge ID; `tag`: edge tag name; `properties`: initial property map | `String` — the same edgeId | `EntityNotExistException` if src/dst missing; `EntityAlreadyExistException` if edgeId exists; `AccessClosedStorageException` if closed |
| `getEdgeStructure` | Returns edge structural info in a single lookup | `id`: edge ID | `EdgeStructure(src, dst, tag)` | `EntityNotExistException` if missing |
| `getEdgeSrc` | Returns the source node ID of an edge | `id`: edge ID | `String` — source node ID | `EntityNotExistException` if missing |
| `getEdgeDst` | Returns the destination node ID of an edge | `id`: edge ID | `String` — destination node ID | `EntityNotExistException` if missing |
| `getEdgeTag` | Returns the tag of an edge | `id`: edge ID | `String` | `EntityNotExistException` if missing |
| `getNodeProperty` | Returns a single property value without constructing the full map | `id`: node ID; `name`: property key | `IValue?` | `EntityNotExistException` if missing |
| `getEdgeProperty` | Returns a single property value without constructing the full map | `id`: edge ID; `name`: property key | `IValue?` | `EntityNotExistException` if missing |
| `setNodeProperties` | Atomically adds, updates, and deletes properties | `id`: node ID; `properties`: map where null values signal deletion | — | `EntityNotExistException` if missing |
| `setEdgeProperties` | Same as `setNodeProperties` for edges | `id`: edge ID; `properties`: map | — | `EntityNotExistException` if missing |
| `deleteNode` | Removes a node and cascades deletion of all incoming/outgoing edges | `id`: node ID | — | `EntityNotExistException` if missing |
| `deleteEdge` | Removes an edge | `id`: edge ID | — | `EntityNotExistException` if missing |
| `getIncomingEdges` | Returns all incoming edge IDs to a node | `id`: node ID | `Set<String>` — edge IDs | `EntityNotExistException` if missing |
| `getOutgoingEdges` | Returns all outgoing edge IDs from a node | `id`: node ID | `Set<String>` — edge IDs | `EntityNotExistException` if missing |
| `metaNames` | Returns all metadata property names | — | `Set<String>` | `AccessClosedStorageException` if closed |
| `getMeta` / `setMeta` | Reads/writes metadata as named key-value pairs | `name`: key; `value`: IValue or null | `IValue?` | — |
| `clear` | Removes all nodes, edges, and metadata | — | — | `AccessClosedStorageException` if closed |
| `transferTo` | Copies all nodes, edges, and metadata into `target` preserving String IDs | `target`: destination `IStorage` | — | — |

**Key design decisions:**

- All IDs are caller-provided `String` values — callers own ID generation and uniqueness.
- `addNode` requires a `nodeId: String` parameter — typically the domain `NodeID`.
- `addEdge` requires an `edgeId: String` parameter — typically generated by the graph layer as `"$src-$tag-$dst"`.
- `addEdge` stores edge structure (src, dst, tag) internally, queryable via `getEdgeStructure` (single lookup) or `getEdgeSrc`/`getEdgeDst`/`getEdgeTag` (delegating to `getEdgeStructure` by default).
- `addNode`/`addEdge` take `Map<String, IValue>` — bulk property initialization avoids a redundant `setProperties` round-trip.
- `setNodeProperties`/`setEdgeProperties` accept `IValue?` — null values signal deletion; a single call can atomically add, update, and delete properties.
- `getNodeProperty`/`getEdgeProperty` — single-property access with default implementation delegating to the full map. Implementations override for O(1) direct access.
- `deleteNode` cascades edge deletion — all incoming and outgoing edges are removed inline before the node is deleted.
- `transferTo` preserves String IDs: copies as-is to the target via `addNode`/`addEdge`. No ID remapping is needed.
- Caller-controlled IDs enable `LayeredStorageImpl` to perform freeze merges and shadow entry creation through the standard interface.

---

### NativeStorageImpl

**Responsibility:** Pure in-memory `IStorage` implementation using `HashMap` (single-threaded). Uses columnar property layout for reduced per-entity object count.

**State / Fields:**

```
nodeColumns:       HashMap<String, HashMap<String, IValue>>  <- one column per property name
edgeColumns:       HashMap<String, HashMap<String, IValue>>  <- one column per property name
edgeEndpoints:     HashMap<String, EdgeStructure>            <- edge ID → (src, dst, tag)
outEdges:          HashMap<String, MutableSet<String>>       <- node ID → outgoing edge IDs
inEdges:           HashMap<String, MutableSet<String>>       <- node ID → incoming edge IDs
metaProperties:    HashMap<String, IValue>
```

- Node existence is determined by `outEdges.containsKey(id)` — no separate node set.
- Columnar property storage: one `HashMap<String, IValue>` per property name (column), rather than one map per entity. Reduces per-entity object count from O(N) maps to O(K) columns (K = distinct property names).
- `getNodeProperties` returns a zero-copy `ColumnViewMap` that assembles properties lazily from columns. `ColumnViewMap` caches its `entries` on first access for repeated reads.
- All operations are O(1) average.
- No synchronization — not thread-safe.
- After `close()`, all maps are cleared and `isClosed` is set; subsequent operations throw `AccessClosedStorageException`.

---

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` implementation with `ReentrantReadWriteLock`. Same columnar property layout as `NativeStorageImpl`.

**State / Fields:** Same columnar structure as `NativeStorageImpl`, plus a `ReentrantReadWriteLock` and snapshot-on-demand adjacency via `AdjEntry`.

- Multiple concurrent reads are allowed (read lock).
- Writes are exclusive (write lock).
- Property key interning via `keyPool`: all property keys are deduplicated so entities sharing the same property name reference the same `String` object, reducing heap overhead.
- Adjacency uses snapshot-on-demand: `AdjEntry` holds a `HashSet` for O(1) writes and a `@Volatile` cached immutable snapshot for reads. Writers mutate the set and invalidate the snapshot (`cached = null`). Readers return the cached snapshot or lazily rebuild it via `Set.copyOf()`.
- `nodeIDs` and `edgeIDs` return `Set.copyOf()` snapshots for thread safety.

---

### LayeredStorageImpl

**Responsibility:** Multi-layer freeze-and-stack `IStorage` for phased analysis pipelines. Composes at most one frozen layer (read-only) + one mutable active layer. Writes target the active layer only. Deletion is restricted to the active layer — frozen layers are immutable. Depends only on the `IStorage` interface.

**State / Fields:**

```
frozenLayers:       MutableList<IStorage>   <- merge-on-freeze keeps at most 1 frozen layer
activeLayer:        NativeStorageImpl       <- current mutable layer, full CRUD
frozenLayerFactory: () -> IStorage          <- factory for frozen layer targets (default: NativeStorageImpl)
```

**Layer management API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freeze` | Merges all existing frozen layers and the active layer into a single new frozen `IStorage` created by `frozenLayerFactory`, closes old layers, and creates a new empty active layer. After freeze, there is always exactly one frozen layer. | — | — | `AccessClosedStorageException` if closed |
| `compact` | Merges top N frozen layers into one. With merge-on-freeze there is at most one frozen layer, so this is effectively a no-op when `topN` is 1. | `topN`: number of layers | — | `IllegalArgumentException` if out of range |
| `layerCount` | Total layers (frozen + active). Always 1 (no frozen) or 2 (one frozen + active). | — | `Int` | — |

**Deletion constraint:** Only entities in the active layer can be deleted. Attempting to delete a frozen-layer entity throws `FrozenLayerModificationException`.

**Query resolution order (properties — overlay semantics):**
1. `activeLayer` — if entity has the property, return it
2. Single frozen layer — fallback

For entities existing in both layers, properties are merged via `LazyMergedMap` (overlay pattern: active values take precedence).

**Query resolution (adjacency — merge semantics):**
- Edge sets are append-only across layers; `getIncomingEdges` / `getOutgoingEdges` merge results from both layers via `UnionSet` (allocation-free view)

**Single-property optimization:** `getNodeProperty` / `getEdgeProperty` check active layer first, then the frozen layer with early return. Avoids constructing a merged property map when only one value is needed.

**Write routing for cross-layer properties:** When `setNodeProperties` targets a node that exists only in the frozen layer, a shadow entry is created in `activeLayer` via `addNode(nodeId, frozenProperties)` to hold the overlay properties. Same for edges via `addEdge(src, dst, edgeId, tag)` (which copies edge structure from frozen layer using `getEdgeStructure`). All shadow creation uses standard `IStorage` interface methods.

**Freeze data flow:**

```
freeze():
  merged = frozenLayerFactory()
  for each existing frozen layer:
    mergeLayerInto(layer, merged), then close
  mergeLayerInto(activeLayer, merged)
  activeLayer.close()
  frozenLayers = [merged]              -> always exactly one frozen layer
  activeLayer = NativeStorageImpl()    -> fresh empty layer
```

`mergeLayerInto` copies all nodes and edges from a source layer into the merged target using standard `addNode`/`addEdge` calls with preserved String IDs. If a node/edge already exists in the target (from a previous layer), properties are updated via `setNodeProperties`/`setEdgeProperties`. Edge reconstruction uses `source.getEdgeStructure(edgeId)` to read endpoints from the source layer.

**Design rationale:** Restricting deletion to the active layer eliminates deletion tracking sets and sentinel values. Merge-on-freeze ensures query resolution is always a simple 2-way lookup (active then single frozen), keeping query depth at O(1). Caller-controlled String IDs mean freeze and shadow operations use only the `IStorage` interface.

---

### IStorageExporter

**Responsibility:** Exports a subset of entities from `IStorage` to a file.

```kotlin
typealias EntityFilter = (String) -> Boolean

interface IStorageExporter {
    fun export(dstFile: Path, from: IStorage, predicate: EntityFilter = { true }): Path
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `export` | Writes filtered entities to a file | `dstFile`: destination path; `from`: source storage; `predicate`: entity filter on String IDs | `Path` — the written file | IO exceptions |

---

### IStorageImporter

**Responsibility:** Imports entities from a file into `IStorage`.

```kotlin
interface IStorageImporter {
    fun isValidFile(file: Path): Boolean
    fun import(srcFile: Path, into: IStorage, predicate: EntityFilter = { true }): IStorage
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `isValidFile` | Validates file contains the expected format | `file`: file path | `Boolean` | — |
| `import` | Reads and merges entities into target storage (existing updated, absent created) | `srcFile`: source path; `into`: target storage; `predicate`: entity filter on String IDs | `IStorage` — the target storage | IO / format exceptions |

`EntityFilter` is a predicate on entity IDs (`String`). It is applied during export/import to select a subset of entities.

---

### NativeCsvIOImpl

**Responsibility:** CSV-based `IStorageExporter` and `IStorageImporter` implementation.

Exports nodes, edges, and metadata as CSV files. Each node row encodes the node ID and property values. Each edge row encodes edge ID, source, destination, tag, and property values. Metadata entries are name-value pair rows. Import reads back and merges into the target storage (existing entities are updated; absent entities are created).

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `AccessClosedStorageException` | Operation on closed storage |
| `EntityAlreadyExistException` | Adding an already-existing node or edge |
| `EntityNotExistException` | Accessing/modifying a non-existent node or edge; adding an edge with missing src or dst node |
| `FrozenLayerModificationException` | Attempting to delete an entity that belongs to a frozen layer in `LayeredStorageImpl` |

---

## Validation Rules

### IStorage implementations (flat)

- Operations on closed storage must throw `AccessClosedStorageException`
- `addNode(nodeId, ...)` must reject duplicate nodeIds with `EntityAlreadyExistException`
- `addEdge(src, dst, edgeId, ...)` must reject edges whose src or dst node does not exist with `EntityNotExistException`
- `addEdge(...)` must reject duplicate edgeIds with `EntityAlreadyExistException`
- Property access/modification on non-existent entities must throw `EntityNotExistException`
- `deleteNode` cascades — all incoming/outgoing edges are removed inline before the node is deleted
- `getEdgeSrc()` / `getEdgeDst()` must return the original `String` node IDs passed to `addEdge()`

### LayeredStorageImpl

- `deleteNode` / `deleteEdge` must throw `FrozenLayerModificationException` if entity is not in `activeLayer`
- `freeze` must fully merge all layer data before closing old layers
- Property overlay: active layer values take precedence over frozen layer values for the same key
- Adjacency merge: `getIncomingEdges` / `getOutgoingEdges` must merge results from all layers
- `setNodeProperties` / `setEdgeProperties` on frozen-layer entities must create shadow entries in `activeLayer`
- Layer count is always 1 or 2 due to merge-on-freeze; `compact` is effectively a no-op
- String IDs are preserved across layer merges
- All layer operations (freeze, shadow creation) use only `IStorage` interface methods

### IStorageImporter implementations

- `isValidFile` must validate format before full load attempt
- Imports must preserve String entity IDs from the source file
