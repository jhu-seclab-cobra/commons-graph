# Storage Module Design

## Design Overview

- **Classes**: `IStorage`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `CompactFrozenStorageImpl`, `LayeredStorageImpl`, `IStorageExporter`, `IStorageImporter`, `NativeCsvIOImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `CompactFrozenStorageImpl` implements `IStorage` (read-only); `LayeredStorageImpl` implements `IStorage` (composes N frozen `IStorage` layers + one mutable active layer); `NativeCsvIOImpl` implements `IStorageExporter` and `IStorageImporter`
- **Abstract**: `IStorage` (implemented by all storage types); `IStorageExporter` (implemented by `NativeCsvIOImpl`); `IStorageImporter` (implemented by `NativeCsvIOImpl`)
- **Exceptions**: `AccessClosedStorageException` raised on closed storage; `EntityAlreadyExistException` raised on duplicate add; `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when attempting to delete entities from frozen layers in `LayeredStorageImpl`
- **Dependency roles**: Data holders: `NodeID`, `EdgeID`, `IValue`. Orchestrator: `IStorage` implementations. Composer: `LayeredStorageImpl` (layers multiple `IStorage` instances). Helpers: `NativeCsvIOImpl` (inputs by argument).

The storage layer is the **backend-agnostic persistence contract** for graph data. It provides two tiers of storage capability:

1. **Flat storage** (`NativeStorageImpl`, external `MapDBStorageImpl` / `JgraphtStorageImpl` / `Neo4jStorageImpl`) — single-layer, full CRUD
2. **Compact frozen storage** (`CompactFrozenStorageImpl`) — read-only `IStorage` that encodes each entity's properties into a single `ByteArray`, reducing heap objects from ~2P+1 per entity (P = property count) to 1. Designed as the frozen layer backend for `LayeredStorageImpl`, replacing `NativeStorageImpl` or MapDB in that role. Supports optional `SoftReference` caching for hot-data acceleration
3. **Layered storage** (`LayeredStorageImpl`) — N frozen layers + 1 mutable active layer; writes target active layer only; deletion restricted to active layer; frozen layers created via injectable factory (default: `NativeStorageImpl`; inject `CompactFrozenStorageImpl` for reduced GC pressure)

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
| `deleteNode` | Removes a node; does **not** cascade edge deletion by contract | `id`: NodeID | — | `EntityNotExistException` if missing |
| `deleteEdge` | Removes an edge | `id`: EdgeID | — | `EntityNotExistException` if missing |
| `metaNames` | Returns all metadata property names | — | `Set<String>` | `AccessClosedStorageException` if closed |
| `getMeta` / `setMeta` | Reads/writes metadata as named properties on a special reserved node | `name`: key; `value`: IValue or null | `IValue?` | — |
| `clear` | Removes all nodes, edges, and metadata | — | `Boolean` | `AccessClosedStorageException` if closed |
| `transferTo` | Default method. Copies all nodes, edges, and metadata into `target` | `target`: destination `IStorage` | — | `EntityAlreadyExistException` if target has conflicts |

**Key design decisions:**

- `nodeIDs`/`edgeIDs` are `Set` — snapshot semantics; callers should not assume live iteration.
- `addNode`/`addEdge` take `Map<String, IValue>` — bulk property initialization avoids a redundant `setNodeProperties` round-trip.
- `setNodeProperties`/`setEdgeProperties` accept `IValue?` — null values signal deletion; a single call can atomically add, update, and delete properties.
- `getNodeProperty`/`getEdgeProperty` — single-property access with default implementation `getNodeProperties(id)[name]`. Layered implementations override for O(1) early-return without full map merge.
- `getMeta`/`setMeta` — metadata (e.g., graph name, version) stored as named properties on a special reserved node, avoiding a separate metadata structure.
- `deleteNode` does not cascade by contract — the graph layer is responsible for removing associated edges before calling `deleteNode`.

---

### NativeStorageImpl

**Responsibility:** Pure in-memory `IStorage` implementation using `HashMap` (single-threaded).

**State / Fields:**

```
nodeProperties:  MutableMap<NodeID, MutableMap<String, IValue>>
edgeProperties:  MutableMap<EdgeID, MutableMap<String, IValue>>
outEdges:        HashMap<NodeID, MutableSet<EdgeID>>
inEdges:         HashMap<NodeID, MutableSet<EdgeID>>
keyPool:         HashMap<String, String>   ← deduplicates property key strings across entities
```

- All operations are O(1) average.
- No synchronization — not thread-safe.
- Property key interning: all property keys are deduplicated via `keyPool` so entities sharing the same property name (e.g., "weight") reference the same `String` object, reducing heap overhead.
- After `close()`, all maps are cleared and `isClosed` is set; subsequent operations throw `AccessClosedStorageException`.

---

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` implementation wrapping `NativeStorageImpl` internals with `ReentrantReadWriteLock`.

**State / Fields:** Same internal structure as `NativeStorageImpl`, plus a `ReentrantReadWriteLock`.

- Multiple concurrent reads are allowed (read lock).
- Writes are exclusive (write lock).
- Property key interning via `keyPool` (same as `NativeStorageImpl`; safe because writes are already lock-protected).

---

### CompactFrozenStorageImpl

**Responsibility:** Read-only `IStorage` that stores each entity's properties as a single compact `ByteArray`, minimizing heap object count for frozen layers.

**State / Fields:**

```
nodeData:       HashMap<NodeID, ByteArray>                         ← compact-encoded properties per node
edgeData:       HashMap<EdgeID, ByteArray>                         ← compact-encoded properties per edge
outEdges:       HashMap<NodeID, Set<EdgeID>>                       ← precomputed outgoing adjacency (immutable)
inEdges:        HashMap<NodeID, Set<EdgeID>>                       ← precomputed incoming adjacency (immutable)
metaData:       HashMap<String, ByteArray>                         ← compact-encoded metadata values
nodeCache:      HashMap<NodeID, SoftReference<Map<String, IValue>>> ← optional hot-data cache (SoftReference, GC-reclaimable)
edgeCache:      HashMap<EdgeID, SoftReference<Map<String, IValue>>> ← optional hot-data cache
```

**Binary encoding format (per entity ByteArray):**

```
┌──────────┬──────────┬────────┬──────────┬──────────┬────────┐
│ propCount│ key (UTF-8, length-prefixed) │ type tag │ value  │ ... repeat
│ 2 bytes  │ 2 bytes (len) + bytes        │ 1 byte   │ varies │
└──────────┴──────────┴────────┴──────────┴──────────┴────────┘

Type tags:
  0x01 = NumVal(Byte)    → 1 byte
  0x02 = NumVal(Short)   → 2 bytes
  0x03 = NumVal(Int)     → 4 bytes
  0x04 = NumVal(Long)    → 8 bytes
  0x05 = NumVal(Float)   → 4 bytes
  0x06 = NumVal(Double)  → 8 bytes
  0x07 = BoolVal         → 1 byte
  0x08 = StrVal          → 2 bytes (len) + UTF-8 bytes
  0x09 = SetVal          → 4 bytes (count) + recursive encoded elements
```

**Read operations:**

| Method | Behavior | Complexity |
|--------|----------|------------|
| `getNodeProperty(id, name)` | Check `nodeCache` SoftReference → on miss, scan `nodeData[id]` ByteArray to target key, decode single value | O(P) scan, ~100-200ns |
| `getNodeProperties(id)` | Check `nodeCache` → on miss, decode all properties from ByteArray, populate cache | O(P) decode, ~200-500ns |
| `getIncomingEdges(id)` | Return precomputed `inEdges[id]` | O(1) lookup |
| `getOutgoingEdges(id)` | Return precomputed `outEdges[id]` | O(1) lookup |

**Write operations:** All mutation methods (`addNode`, `addEdge`, `setNodeProperties`, `setEdgeProperties`, `deleteNode`, `deleteEdge`) throw `UnsupportedOperationException`. This storage is read-only by design.

**Ingestion (construction-time only):**

Data is populated via `transferTo` from a source `IStorage` during `LayeredStorageImpl.freeze()`. The `addNode`/`addEdge` implementations encode incoming `Map<String, IValue>` properties into `ByteArray` format during construction. After construction completes, the storage transitions to read-only mode and rejects further mutations.

**Key design decisions:**

- **One ByteArray per entity:** Reduces heap objects from ~2P+1 (P HashMap.Node + P IValue + 1 HashMap) to 1 ByteArray per entity. For 2M entities × 5 properties: from ~22M objects to ~2M objects.
- **On-demand single-property decode:** `getNodeProperty(id, name)` scans the ByteArray and decodes only the matched property, avoiding full deserialization. This is ~100-200ns vs MapDB's ~3-6μs full-entry deserialization.
- **SoftReference cache:** Decoded `Map<String, IValue>` is cached via `SoftReference`. Hot data returns cached result (~50ns); GC reclaims cache entries under memory pressure without OOM risk.
- **Read-only contract:** Frozen layers are immutable. Rejecting writes at the storage level (rather than relying on `LayeredStorageImpl` routing) provides defense-in-depth.

**Performance characteristics:**

| Scenario | NativeStorageImpl | MapDB | CompactFrozenStorageImpl |
|----------|-------------------|-------|--------------------------|
| Heap objects per entity | ~2P+1 | 0 | 1 |
| Single property read | ~50ns | ~3-6μs | ~100-200ns (miss) / ~50ns (cache hit) |
| Full property read | ~50ns | ~3-6μs | ~200-500ns (miss) / ~50ns (cache hit) |
| GC impact (2M entities, P=5) | ~22M objects | ~0 | ~2M objects |

---

### LayeredStorageImpl

**Responsibility:** Multi-layer freeze-and-stack `IStorage` for phased analysis pipelines. Composes N frozen layers (read-only) + one mutable active layer. Writes target the active layer only. Deletion is restricted to the active layer — frozen layers are immutable.

**State / Fields:**

```
frozenLayers:       MutableList<IStorage>   ← bottom-to-top order; each is a frozen IStorage
activeLayer:        IStorage                ← current mutable layer, full CRUD
frozenLayerFactory: () -> IStorage          ← factory for frozen layer targets (default: NativeStorageImpl; inject CompactFrozenStorageImpl for reduced GC)
```

**Layer management API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freeze` | Transfers active layer data to a new frozen `IStorage` created by `frozenLayerFactory`, closes old active layer, creates new empty active layer | — | — | `AccessClosedStorageException` if closed |
| `compact` | Merges top N frozen layers into one to reduce query chain length | `topN`: number of layers | — | `IllegalArgumentException` if out of range |
| `layerCount` | Total layers (frozen + active) | — | `Int` | — |

**Deletion constraint:** Only entities in the active layer can be deleted. Attempting to delete a frozen-layer entity throws `FrozenLayerModificationException`. No deletion tracking sets or sentinel values are needed.

**Query resolution order (properties — overlay semantics):**
1. `activeLayer` — if entity has the property, return it
2. `frozenLayers` in reverse order (most recently frozen first) — first match wins

**Query resolution (adjacency — merge semantics):**
- Edges are append-only across layers; `getIncomingEdges` / `getOutgoingEdges` merge results from all layers

**Single-property optimization:** `getNodeProperty` / `getEdgeProperty` check active layer first, then iterate frozen layers in reverse with early return. Avoids constructing a merged property map when only one value is needed.

**Write routing for cross-layer properties:** When `setNodeProperties` targets a node that exists only in frozen layers, a shadow entry is created in `activeLayer` to hold the overlay properties. Same for edges.

**Freeze data flow:**

```
freeze():
  activeLayer.transferTo(frozenLayerFactory())  → bulk write to target
  activeLayer.close()                           → release heap memory
  frozenLayers.add(frozenTarget)
  activeLayer = NativeStorageImpl()             → fresh empty heap layer
```

**Design rationale:** Restricting deletion to the active layer eliminates deletion tracking sets and sentinel values. Query resolution is a simple 2-way cascade (active → frozen stack). The N-layer model supports arbitrary freeze depth — a single frozen layer suffices for simple pipelines, while multiple layers accommodate multi-phase analysis.

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

Exports nodes and edges as two CSV files in a directory (`nodes.csv`, `edges.csv`). Each row encodes entity ID and property values as a serialized `MapVal` column. Import reads back and merges into the target storage (existing entities are updated; absent entities are created).

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
- `deleteNode` does not cascade — graph layer must remove edges first

### CompactFrozenStorageImpl

- All mutation methods must throw `UnsupportedOperationException`
- `getNodeProperty`/`getEdgeProperty` must return `null` for absent property keys (not throw)
- ByteArray encoding must be lossless: decode(encode(properties)) must equal original properties
- SoftReference cache must not change observable behavior — cache miss and cache hit must return identical results
- `close()` must clear all data maps and caches; subsequent operations must throw `AccessClosedStorageException`

### LayeredStorageImpl

- `deleteNode` / `deleteEdge` must throw `FrozenLayerModificationException` if entity is not in `activeLayer`
- `freeze` must fully transfer active layer data before closing it
- Property overlay: active layer values take precedence over frozen layer values for the same key
- Adjacency merge: `getIncomingEdges` / `getOutgoingEdges` must merge results from all layers
- `setNodeProperties` / `setEdgeProperties` on frozen-layer entities must create shadow entries in `activeLayer`
- `addNode` / `addEdge` must check all layers for duplicates before adding to active layer
- Layer count should stay bounded; use `compact` to merge when exceeding threshold

### IStorageImporter implementations

- `isValidFile` must validate format before full load attempt
