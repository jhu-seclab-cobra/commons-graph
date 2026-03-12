# Storage Module Design

## Design Overview

- **Classes**: `IStorage`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`, `IStorageExporter`, `IStorageImporter`, `NativeCsvIOImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `LayeredStorageImpl` implements `IStorage` (composes at most one frozen `IStorage` layer + one mutable active layer); `NativeCsvIOImpl` implements `IStorageExporter` and `IStorageImporter`
- **Abstract**: `IStorage` (implemented by all storage types); `IStorageExporter` (implemented by `NativeCsvIOImpl`); `IStorageImporter` (implemented by `NativeCsvIOImpl`)
- **Exceptions**: `AccessClosedStorageException` raised on closed storage; `EntityAlreadyExistException` raised on duplicate add; `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when attempting to delete entities from frozen layers in `LayeredStorageImpl`
- **Dependency roles**: Data holders: `NodeID`, `EdgeID`, `IValue`. Orchestrator: `IStorage` implementations. Composer: `LayeredStorageImpl` (layers multiple `IStorage` instances). Helpers: `NativeCsvIOImpl` (inputs by argument).

The storage layer is the **backend-agnostic persistence contract** for graph data. It provides two tiers of storage capability:

1. **Flat storage** (`NativeStorageImpl`, external `MapDBStorageImpl` / `JgraphtStorageImpl` / `Neo4jStorageImpl`) — single-layer, full CRUD
2. **Layered storage** (`LayeredStorageImpl`) — at most one frozen layer (read-only) + one mutable active layer; writes target active layer only; deletion restricted to active layer; frozen layers created via injectable factory (default: `NativeStorageImpl`)

The storage layer does **not** own graph traversal logic, entity type construction, or backend-specific optimization — those belong to the graph layer and module implementations respectively.

---

## Class / Type Specifications

### IStorage

**Responsibility:** Defines the minimum capability contract all storage backends must implement.

**State / Fields:**
- `nodeIDs: Set<NodeID>` — snapshot of all node IDs
- `edgeIDs: Set<EdgeID>` — snapshot of all edge IDs

**Methods:**

```kotlin
interface IStorage : Closeable {
    val nodeIDs: Set<NodeID>
    val edgeIDs: Set<EdgeID>

    fun containsNode(id: NodeID): Boolean
    fun addNode(id: NodeID, properties: Map<String, IValue> = emptyMap())
    fun getNodeProperties(id: NodeID): Map<String, IValue>
    fun getNodeProperty(id: NodeID, name: String): IValue?
    fun setNodeProperties(id: NodeID, properties: Map<String, IValue?>)
    fun deleteNode(id: NodeID)

    fun containsEdge(id: EdgeID): Boolean
    fun addEdge(id: EdgeID, properties: Map<String, IValue> = emptyMap())
    fun getEdgeProperties(id: EdgeID): Map<String, IValue>
    fun getEdgeProperty(id: EdgeID, name: String): IValue?
    fun setEdgeProperties(id: EdgeID, properties: Map<String, IValue?>)
    fun deleteEdge(id: EdgeID)

    fun getIncomingEdges(id: NodeID): Set<EdgeID>
    fun getOutgoingEdges(id: NodeID): Set<EdgeID>

    val metaNames: Set<String>
    fun getMeta(name: String): IValue?
    fun setMeta(name: String, value: IValue?)

    fun clear(): Boolean

    fun transferTo(target: IStorage) {
        for (nodeId in nodeIDs) target.addNode(nodeId, getNodeProperties(nodeId))
        for (edgeId in edgeIDs) target.addEdge(edgeId, getEdgeProperties(edgeId))
        for (name in metaNames) target.setMeta(name, getMeta(name))
    }
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addNode` | Creates a node with optional initial properties | `id`: NodeID; `properties`: initial property map | — | `EntityAlreadyExistException` if exists |
| `addEdge` | Creates an edge with optional initial properties | `id`: EdgeID; `properties`: initial property map | — | `EntityAlreadyExistException` if exists; `EntityNotExistException` if src/dst missing |
| `getNodeProperty` | Returns a single property value without constructing the full map | `id`: NodeID; `name`: property key | `IValue?` | `EntityNotExistException` if missing |
| `getEdgeProperty` | Returns a single property value without constructing the full map | `id`: EdgeID; `name`: property key | `IValue?` | `EntityNotExistException` if missing |
| `setNodeProperties` | Atomically adds, updates, and deletes properties | `id`: NodeID; `properties`: map where null values signal deletion | — | `EntityNotExistException` if missing |
| `setEdgeProperties` | Same as `setNodeProperties` for edges | `id`: EdgeID; `properties`: map | — | `EntityNotExistException` if missing |
| `deleteNode` | Removes a node and cascades deletion of all incoming/outgoing edges | `id`: NodeID | — | `EntityNotExistException` if missing |
| `deleteEdge` | Removes an edge | `id`: EdgeID | — | `EntityNotExistException` if missing |
| `metaNames` | Returns all metadata property names | — | `Set<String>` | `AccessClosedStorageException` if closed |
| `getMeta` / `setMeta` | Reads/writes metadata as named key-value pairs in a dedicated metadata map | `name`: key; `value`: IValue or null | `IValue?` | — |
| `clear` | Removes all nodes, edges, and metadata | — | `Boolean` | `AccessClosedStorageException` if closed |
| `transferTo` | Default method. Copies all nodes, edges, and metadata into `target` | `target`: destination `IStorage` | — | `EntityAlreadyExistException` if target has conflicts |

**Key design decisions:**

- `nodeIDs`/`edgeIDs` are `Set` — snapshot semantics; callers should not assume live iteration.
- `addNode`/`addEdge` take `Map<String, IValue>` — bulk property initialization avoids a redundant `setNodeProperties` round-trip.
- `setNodeProperties`/`setEdgeProperties` accept `IValue?` — null values signal deletion; a single call can atomically add, update, and delete properties.
- `getNodeProperty`/`getEdgeProperty` — single-property access with default implementation `getNodeProperties(id)[name]`. Layered implementations override for O(1) early-return without full map merge.
- `getMeta`/`setMeta` — metadata (e.g., graph name, version, label lattice) stored in a dedicated key-value map, separate from node/edge properties.
- `deleteNode` cascades edge deletion — all incoming and outgoing edges are removed inline before the node is deleted. This ensures storage-level consistency without requiring the graph layer to remove edges first.

---

### NativeStorageImpl

**Responsibility:** Pure in-memory `IStorage` implementation using `HashMap` (single-threaded).

**State / Fields:**

```
nodeProperties:  MutableMap<NodeID, MutableMap<String, IValue>>
edgeProperties:  MutableMap<EdgeID, MutableMap<String, IValue>>
outEdges:        HashMap<NodeID, MutableSet<EdgeID>>
inEdges:         HashMap<NodeID, MutableSet<EdgeID>>
```

- All operations are O(1) average.
- No synchronization — not thread-safe.
- After `close()`, all maps are cleared and `isClosed` is set; subsequent operations throw `AccessClosedStorageException`.

---

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` implementation wrapping `NativeStorageImpl` internals with `ReentrantReadWriteLock`.

**State / Fields:** Same internal structure as `NativeStorageImpl`, plus a `ReentrantReadWriteLock`.

- Multiple concurrent reads are allowed (read lock).
- Writes are exclusive (write lock).
- Property key interning via `keyPool`: all property keys are deduplicated so entities sharing the same property name reference the same `String` object, reducing heap overhead. Safe because writes are already lock-protected.

---

### LayeredStorageImpl

**Responsibility:** Multi-layer freeze-and-stack `IStorage` for phased analysis pipelines. Composes at most one frozen layer (read-only) + one mutable active layer. Writes target the active layer only. Deletion is restricted to the active layer — frozen layers are immutable.

**State / Fields:**

```
frozenLayers:       MutableList<IStorage>   ← merge-on-freeze keeps at most 1 frozen layer
activeLayer:        IStorage                ← current mutable layer, full CRUD
frozenLayerFactory: () -> IStorage          ← factory for frozen layer targets (default: NativeStorageImpl)
```

**Layer management API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freeze` | Merges all existing frozen layers and the active layer into a single new frozen `IStorage` created by `frozenLayerFactory`, closes old layers, and creates a new empty active layer. After freeze, there is always exactly one frozen layer. | — | — | `AccessClosedStorageException` if closed |
| `compact` | Merges top N frozen layers into one to reduce query chain length. With merge-on-freeze there is at most one frozen layer, so this is effectively a no-op when `topN` is 1. | `topN`: number of layers | — | `IllegalArgumentException` if out of range |
| `layerCount` | Total layers (frozen + active). Always 1 (no frozen) or 2 (one frozen + active). | — | `Int` | — |

**Deletion constraint:** Only entities in the active layer can be deleted. Attempting to delete a frozen-layer entity throws `FrozenLayerModificationException`. No deletion tracking sets or sentinel values are needed.

**Query resolution order (properties — overlay semantics):**
1. `activeLayer` — if entity has the property, return it
2. Single frozen layer — fallback

**Query resolution (adjacency — merge semantics):**
- Edges are append-only across layers; `getIncomingEdges` / `getOutgoingEdges` merge results from both layers

**Single-property optimization:** `getNodeProperty` / `getEdgeProperty` check active layer first, then the frozen layer with early return. Avoids constructing a merged property map when only one value is needed.

**Write routing for cross-layer properties:** When `setNodeProperties` targets a node that exists only in the frozen layer, a shadow entry is created in `activeLayer` to hold the overlay properties. Same for edges.

**Freeze data flow:**

```
freeze():
  merged = frozenLayerFactory()
  for each existing frozen layer:
    merge into merged, then close
  merge activeLayer into merged
  activeLayer.close()
  frozenLayers = [merged]                     → always exactly one frozen layer
  activeLayer = NativeStorageImpl()           → fresh empty heap layer
```

**Design rationale:** Restricting deletion to the active layer eliminates deletion tracking sets and sentinel values. Merge-on-freeze ensures query resolution is always a simple 2-way lookup (active then single frozen), keeping query depth at O(1).

---

### IStorageExporter

**Responsibility:** Exports a subset of entities from `IStorage` to a file.

```kotlin
typealias EntityFilter = (IEntity.ID) -> Boolean

interface IStorageExporter {
    fun export(dstFile: Path, from: IStorage, predicate: EntityFilter = { true }): Path
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `export` | Writes filtered entities to a file | `dstFile`: destination path; `from`: source storage; `predicate`: entity filter | `Path` — the written file | IO exceptions |

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
| `import` | Reads and merges entities into target storage (existing updated, absent created) | `srcFile`: source path; `into`: target storage; `predicate`: entity filter | `IStorage` — the target storage | IO / format exceptions |

`EntityFilter` is a predicate on entity IDs — either `NodeID` or `EdgeID`. It is applied during export/import to select a subset of entities.

---

### NativeCsvIOImpl

**Responsibility:** CSV-based `IStorageExporter` and `IStorageImporter` implementation.

Exports nodes, edges, and metadata as three CSV files in a directory (`nodes.csv`, `edges.csv`, `meta.csv`). Each node/edge row encodes entity ID and property values as a serialized `MapVal` column. Each metadata entry is a name-value pair row in `meta.csv`. Import reads back and merges into the target storage (existing entities are updated; absent entities are created).

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
- `addNode`/`addEdge` must reject duplicate IDs with `EntityAlreadyExistException`
- `addEdge` must reject edges whose src or dst node does not exist with `EntityNotExistException`
- Property access/modification on non-existent entities must throw `EntityNotExistException`
- `deleteNode` cascades — all incoming/outgoing edges are removed inline before the node is deleted

### LayeredStorageImpl

- `deleteNode` / `deleteEdge` must throw `FrozenLayerModificationException` if entity is not in `activeLayer`
- `freeze` must fully transfer active layer data before closing it
- Property overlay: active layer values take precedence over frozen layer values for the same key
- Adjacency merge: `getIncomingEdges` / `getOutgoingEdges` must merge results from all layers
- `setNodeProperties` / `setEdgeProperties` on frozen-layer entities must create shadow entries in `activeLayer`
- `addNode` / `addEdge` must check all layers for duplicates before adding to active layer
- Layer count is always 1 or 2 due to merge-on-freeze; `compact` is effectively a no-op

### IStorageImporter implementations

- `isValidFile` must validate format before full load attempt
