# Storage Module Design

## Design Overview

- **Classes**: `IStorage`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `DeltaStorageImpl`, `DeltaConcurStorageImpl`, `PhasedStorageImpl`, `IStorageExporter`, `IStorageImporter`, `NativeCsvIOImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `DeltaStorageImpl` implements `IStorage` (composes two `IStorage` as frozen base + mutable overlay); `DeltaConcurStorageImpl` wraps `DeltaStorageImpl` with `ReentrantReadWriteLock`; `PhasedStorageImpl` implements `IStorage` (composes N frozen layers + one mutable active layer); `NativeCsvIOImpl` implements `IStorageExporter` and `IStorageImporter`
- **Abstract**: `IStorage` (implemented by all storage types); `IStorageExporter` (implemented by `NativeCsvIOImpl`); `IStorageImporter` (implemented by `NativeCsvIOImpl`)
- **Exceptions**: `AccessClosedStorageException` raised on closed storage; `EntityAlreadyExistException` raised on duplicate add; `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when attempting to delete entities from frozen layers in `PhasedStorageImpl`; `StorageFrozenException` raised on write operations after `PhasedStorageImpl.freeze()`
- **Dependency roles**: Data holders: `NodeID`, `EdgeID`, `IValue`. Orchestrator: `IStorage` implementations. Composers: `DeltaStorageImpl` / `PhasedStorageImpl` (layer multiple `IStorage` instances). Helpers: `NativeCsvIOImpl` (inputs by argument).

The storage layer is the **backend-agnostic persistence contract** for graph data. It provides three tiers of storage capability:

1. **Flat storage** (`NativeStorageImpl`, external `MapDBStorageImpl` / `JgraphtStorageImpl` / `Neo4jStorageImpl`) — single-layer, full CRUD
2. **Delta storage** (`DeltaStorageImpl`) — two-layer overlay with frozen base + mutable present; supports full deletion tracking across layers via `deletedNodesHolder` / `deletedEdgesHolder`
3. **Phased storage** (`PhasedStorageImpl`) — multi-layer freeze-and-stack model for static analysis pipelines; deletion restricted to active layer only; frozen layers migrated to off-heap storage (e.g., MapDB `readOnly`) to reduce GC pressure

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
    fun setNodeProperties(id: NodeID, properties: Map<String, IValue?>)
    fun deleteNode(id: NodeID)

    fun containsEdge(id: EdgeID): Boolean
    fun addEdge(id: EdgeID, properties: Map<String, IValue> = emptyMap())
    fun getEdgeProperties(id: EdgeID): Map<String, IValue>
    fun setEdgeProperties(id: EdgeID, properties: Map<String, IValue?>)
    fun deleteEdge(id: EdgeID)

    fun getIncomingEdges(id: NodeID): Set<EdgeID>
    fun getOutgoingEdges(id: NodeID): Set<EdgeID>

    fun getMeta(name: String): IValue?
    fun setMeta(name: String, value: IValue?)

    fun clear(): Boolean
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addNode` | Creates a node with optional initial properties | `id`: NodeID; `properties`: initial property map | — | `EntityAlreadyExistException` if exists |
| `addEdge` | Creates an edge with optional initial properties | `id`: EdgeID; `properties`: initial property map | — | `EntityAlreadyExistException` if exists; `EntityNotExistException` if src/dst missing |
| `setNodeProperties` | Atomically adds, updates, and deletes properties | `id`: NodeID; `properties`: map where null values signal deletion | — | `EntityNotExistException` if missing |
| `setEdgeProperties` | Same as `setNodeProperties` for edges | `id`: EdgeID; `properties`: map | — | `EntityNotExistException` if missing |
| `deleteNode` | Removes a node; does **not** cascade edge deletion by contract | `id`: NodeID | — | `EntityNotExistException` if missing |
| `deleteEdge` | Removes an edge | `id`: EdgeID | — | `EntityNotExistException` if missing |
| `getMeta` / `setMeta` | Reads/writes metadata as named properties on a special reserved node | `name`: key; `value`: IValue or null | `IValue?` | — |
| `clear` | Removes all nodes, edges, and metadata | — | `Boolean` | `AccessClosedStorageException` if closed |

**Key design decisions:**

- `nodeIDs`/`edgeIDs` are `Set` — snapshot semantics; callers should not assume live iteration.
- `addNode`/`addEdge` take `Map<String, IValue>` — bulk property initialization avoids a redundant `setNodeProperties` round-trip.
- `setNodeProperties`/`setEdgeProperties` accept `IValue?` — null values signal deletion; a single call can atomically add, update, and delete properties.
- `getMeta`/`setMeta` — metadata (e.g., graph name, version) stored as named properties on a special reserved node, avoiding a separate metadata structure.
- `deleteNode` does not cascade by contract — the graph layer is responsible for removing associated edges before calling `deleteNode`.

---

### NativeStorageImpl

**Responsibility:** Pure in-memory `IStorage` implementation using `LinkedHashMap` (single-threaded).

**State / Fields:**

```
nodeProperties:  LinkedHashMap<NodeID, MutableMap<String, IValue>>
edgeProperties:  LinkedHashMap<EdgeID, MutableMap<String, IValue>>
incomingEdges:   HashMap<NodeID, MutableSet<EdgeID>>
outgoingEdges:   HashMap<NodeID, MutableSet<EdgeID>>
```

- All operations are O(1) average.
- No synchronization — not thread-safe.
- Metadata stored as properties on a dedicated meta node.
- After `close()`, all maps are cleared and `isClosed` is set; subsequent operations throw `AccessClosedStorageException`.

---

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` implementation wrapping `NativeStorageImpl` internals with `ReentrantReadWriteLock`.

**State / Fields:** Same internal structure as `NativeStorageImpl`, plus a `ReentrantReadWriteLock`.

- Multiple concurrent reads are allowed (read lock).
- Writes are exclusive (write lock).
- `nodeIDs`/`edgeIDs` snapshot the key sets inside the read lock, returning a `List`-backed sequence to avoid holding the lock during caller iteration.

---

### DeltaStorageImpl

**Responsibility:** Two-layer `IStorage` implementation composing a frozen `baseDelta` and a mutable `presentDelta`. Reads cascade from present to base; writes go to present only. Supports full deletion across both layers via `deletedNodesHolder` / `deletedEdgesHolder` and `"_deleted_"` sentinel values for property deletion.

**State / Fields:**

```
baseDelta:     IStorage           ← frozen/read-only base layer (injected)
presentDelta:  IStorage           ← mutable overlay (default: NativeStorageImpl)
deletedNodesHolder: Set<NodeID>   ← tracks nodes deleted from baseDelta
deletedEdgesHolder: Set<EdgeID>   ← tracks edges deleted from baseDelta
nodeCounter:   Int                ← maintained for O(1) size queries
edgeCounter:   Int                ← maintained for O(1) size queries
```

**Query resolution order:**
1. Check `deletedNodesHolder` / `deletedEdgesHolder` — if deleted, entity does not exist
2. Check `presentDelta` — if present, return (properties filtered for `"_deleted_"` sentinel)
3. Fall back to `baseDelta`

**Deletion semantics:**
- Deleting a `presentDelta` entity: removed from `presentDelta` directly
- Deleting a `baseDelta` entity: added to `deletedNodesHolder` / `deletedEdgesHolder`; associated edges tracked transitively
- Property deletion: stored as `"_deleted_"` sentinel in `presentDelta` overlay, filtered out on read

**Key design decisions:**
- `baseDelta` is never modified — all mutations go to `presentDelta` or deleted holders
- `close()` only closes `presentDelta`; `baseDelta` lifecycle is managed externally (shared between instances in multi-thread scenarios)
- `clear()` only clears `presentDelta`, not `baseDelta`

---

### DeltaConcurStorageImpl

**Responsibility:** Thread-safe wrapper around `DeltaStorageImpl` semantics using `ReentrantReadWriteLock`.

**State / Fields:** Same as `DeltaStorageImpl`, plus `ReentrantReadWriteLock` and `AtomicInteger` counters.

- Multiple concurrent reads allowed (read lock)
- Writes are exclusive (write lock)
- `CopyOnWriteArraySet` for deleted holders ensures safe concurrent iteration

---

### PhasedStorageImpl

**Responsibility:** Concrete `IStorage` implementation for static analysis pipelines. Composes N frozen layers (read-only) + one mutable active layer (in-heap). No intermediate interfaces — phased-specific API exposed as concrete class methods.

**State / Fields:**

```
frozenLayers:       List<IStorage>      ← bottom-to-top order; each is a frozen IStorage
activeLayer:        NativeStorageImpl   ← current mutable layer, full CRUD
frozenLayerFactory: () -> IStorage      ← factory for frozen layer targets (default: NativeStorageImpl; inject MapDB for off-heap)
isFrozen:           Boolean             ← when true, all writes throw StorageFrozenException
```

**Phased API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freezeAndPushLayer` | Transfers active layer data to a new frozen `IStorage`, closes active layer, creates new empty active layer | — | — | `StorageFrozenException` if frozen |
| `compactLayers` | Merges top N frozen layers into one to reduce query chain length | `topN`: number of layers | — | `IllegalArgumentException` if out of range |
| `freeze` | Makes entire storage read-only; idempotent | — | — | — |
| `layerCount` | Total layers (frozen + active) | — | `Int` | — |
| `isFrozen` | Whether storage is fully frozen | — | `Boolean` | — |

**Deletion constraint:** Only entities in the active layer can be deleted. Attempting to delete a frozen-layer entity throws `FrozenLayerModificationException`. This eliminates the need for `deletedNodesHolder` / `deletedEdgesHolder` and `"_deleted_"` sentinels.

**Query resolution order (properties — overlay semantics):**
1. `activeLayer` — if entity has property, return it
2. `frozenLayers` in reverse order (most recently frozen first) — first match wins

**Query resolution (adjacency — merge semantics):**
- Edges are append-only across layers; `getIncomingEdges` / `getOutgoingEdges` merge results from all layers

**Freeze data flow:**

```
freezeAndPushLayer():
  activeLayer.transferTo(frozenLayerFactory())  → bulk write to target
  activeLayer.close()                           → release heap memory
  frozenLayers.add(frozenTarget)
  activeLayer = NativeStorageImpl()             → fresh empty heap layer
```

**Extension function for transfer:**

```kotlin
fun IStorage.transferTo(target: IStorage) {
    for (nodeId in nodeIDs) target.addNode(nodeId, getNodeProperties(nodeId))
    for (edgeId in edgeIDs) target.addEdge(edgeId, getEdgeProperties(edgeId))
}
```

**Design rationale:** `PhasedStorageImpl` is the only freeze-and-stack implementation. Introducing `IFreezableStorage` / `ILayeredStorage` interfaces would add inheritance complexity without benefit — there are no other implementations to abstract over. Upstream code that needs only `IStorage` works transparently; code that needs phased-specific methods holds a `PhasedStorageImpl` reference directly.

See `docs/impl/phased-immutable-storage.md` for performance analysis, freeze optimization, and static analysis workflow mapping.

---

### DeltaStorageImpl vs PhasedStorageImpl

| Dimension | DeltaStorageImpl | PhasedStorageImpl |
|-----------|-----------------|-------------------|
| Layers | 2 (base + present) | N (frozen stack + active) |
| Deletion scope | Full (both layers, via deleted holders) | Active layer only (frozen layers immutable) |
| Deleted tracking | `deletedNodesHolder` / `deletedEdgesHolder` + `"_deleted_"` sentinel | None needed |
| Query complexity | 3-way check (deleted → present → base) | 2-way cascade (active → frozen stack) |
| Off-heap support | Manual (pass MapDB as `baseDelta`) | Built-in (`freezeAndPushLayer` auto-migrates) |
| Use case | General-purpose; supports full graph mutation including frozen-layer deletion | Static analysis pipelines; append-heavy with phase-based freezing |

Both share `IStorage` interface and are interchangeable. Use `DeltaStorageImpl` when frozen-layer deletion is needed (incremental analysis, graph restructuring); use `PhasedStorageImpl` for the common static analysis workflow (build → freeze → build → freeze → analyze).

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
| `FrozenLayerModificationException` | Attempting to delete an entity that belongs to a frozen layer in `PhasedStorageImpl` |
| `StorageFrozenException` | Write operation on `PhasedStorageImpl` after `freeze()` |

---

## Validation Rules

### IStorage implementations (flat)

- Operations on closed storage must throw `AccessClosedStorageException`
- `addNode`/`addEdge` must reject duplicate IDs with `EntityAlreadyExistException`
- `addEdge` must reject edges whose src or dst node does not exist with `EntityNotExistException`
- Property access/modification on non-existent entities must throw `EntityNotExistException`
- `deleteNode` does not cascade — graph layer must remove edges first

### DeltaStorageImpl

- `baseDelta` is never mutated; all writes go to `presentDelta`
- `deletedNodesHolder` / `deletedEdgesHolder` must be checked before all read operations
- `"_deleted_"` sentinel must be filtered from property reads
- `close()` only closes `presentDelta`; `baseDelta` lifecycle is external

### PhasedStorageImpl

- `deleteNode` / `deleteEdge` must throw `FrozenLayerModificationException` if entity is not in `activeLayer`
- `freezeAndPushLayer` must fully transfer active layer data before closing it
- Property overlay: active layer values take precedence over frozen layer values for the same key
- Adjacency merge: `getIncomingEdges` / `getOutgoingEdges` must merge results from all layers
- Layer count should stay ≤ 3-4; use `compactLayers` to merge when exceeding threshold

### IStorageImporter implementations

- `isValidFile` must validate format before full load attempt
