# Storage Design

## Design Overview

- **Classes**: `IStorage`, `IStorage.EdgeStructure`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `LayeredStorageImpl` implements `IStorage` (composes an inline active layer + at most one frozen `IStorage` layer)
- **Abstract**: `IStorage` (implemented by all storage types)
- **Exceptions**: `AccessClosedStorageException` raised on closed storage; `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when deleting entities from frozen layer
- **Dependency roles**: Data holders: `EdgeStructure`. Orchestrator: `IStorage` implementations. Composer: `LayeredStorageImpl` (layers active + frozen storage).

The storage layer is the **backend-agnostic directed property graph engine**. It manages nodes and edges identified by auto-generated `Int` IDs, per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), edge structural metadata (source, destination, tag), and graph-level metadata. It does not know about domain types (`Label`).

`IStorage` uses auto-generated `Int` IDs. `addNode()` returns a new `Int` ID; `addEdge()` returns a new `Int` ID. This eliminates String hashing overhead on all internal lookups. The graph layer manages the `NodeID` (String) to storage `Int` mapping externally.

---

## Class / Type Specifications

### IStorage

**Responsibility:** Minimum capability contract for all storage backends. All entity IDs are auto-generated `Int` values.

**Methods:**

```kotlin
interface IStorage : Closeable {
    val nodeIDs: Set<Int>
    val edgeIDs: Set<Int>
    fun containsNode(id: Int): Boolean
    fun addNode(properties: Map<String, IValue> = emptyMap()): Int
    fun getNodeProperties(id: Int): Map<String, IValue>
    fun getNodeProperty(id: Int, name: String): IValue?
    fun setNodeProperties(id: Int, properties: Map<String, IValue?>)
    fun deleteNode(id: Int)
    data class EdgeStructure(val src: Int, val dst: Int, val tag: String)
    fun containsEdge(id: Int): Boolean
    fun addEdge(src: Int, dst: Int, tag: String, properties: Map<String, IValue> = emptyMap()): Int
    fun getEdgeStructure(id: Int): EdgeStructure
    fun getEdgeProperties(id: Int): Map<String, IValue>
    fun getEdgeProperty(id: Int, name: String): IValue?
    fun setEdgeProperties(id: Int, properties: Map<String, IValue?>)
    fun deleteEdge(id: Int)
    fun getIncomingEdges(id: Int): Set<Int>
    fun getOutgoingEdges(id: Int): Set<Int>
    val metaNames: Set<String>
    fun getMeta(name: String): IValue?
    fun setMeta(name: String, value: IValue?)
    fun clear()
    fun transferTo(target: IStorage): Map<Int, Int>
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addNode` | Creates a node with optional initial properties | `properties`: initial property map | `Int` -- auto-generated ID | `AccessClosedStorageException` if closed |
| `addEdge` | Creates an edge between two existing nodes | `src`, `dst`: node Int IDs; `tag`; `properties` | `Int` -- auto-generated ID | `EntityNotExistException` if src/dst missing; `AccessClosedStorageException` if closed |
| `getEdgeStructure` | Returns edge structural info in a single lookup | `id`: edge ID | `EdgeStructure(src, dst, tag)` | `EntityNotExistException` if missing |
| `getNodeProperty` | Single-property access; default delegates to full map | `id`, `name` | `IValue?` | `EntityNotExistException` if missing |
| `getEdgeProperty` | Single-property access; default delegates to full map | `id`, `name` | `IValue?` | `EntityNotExistException` if missing |
| `setNodeProperties` | Atomically adds, updates, and deletes properties | `id`; `properties` (null = delete) | -- | `EntityNotExistException` if missing |
| `setEdgeProperties` | Same semantics as `setNodeProperties` for edges | `id`; `properties` | -- | `EntityNotExistException` if missing |
| `deleteNode` | Removes node and cascades deletion of all incident edges | `id` | -- | `EntityNotExistException` if missing |
| `deleteEdge` | Removes an edge | `id` | -- | `EntityNotExistException` if missing |
| `getIncomingEdges` / `getOutgoingEdges` | Returns all incoming/outgoing edge IDs | `id`: node ID | `Set<Int>` | `EntityNotExistException` if missing |
| `getMeta` / `setMeta` | Reads/writes metadata as named key-value pairs | `name`; `value` | `IValue?` | -- |
| `clear` | Removes all nodes, edges, and metadata | -- | -- | `AccessClosedStorageException` if closed |
| `transferTo` | Copies all data into `target`; returns node ID mapping | `target` | `Map<Int, Int>` | -- |

---

### NativeStorageImpl

**Responsibility:** Pure in-memory `IStorage` implementation. Not thread-safe.

- After `close()`, all state is cleared and `isClosed` is set; subsequent operations throw `AccessClosedStorageException`

---

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` with `ReentrantReadWriteLock`.

- Multiple concurrent reads allowed (read lock)
- Writes are exclusive (write lock)
- `nodeIDs` and `edgeIDs` return snapshot copies for thread safety

---

### LayeredStorageImpl

**Responsibility:** Multi-layer freeze-and-stack `IStorage` for phased analysis pipelines. Active layer data stored inline using global `Int` IDs. Frozen layer is an independent `IStorage` instance with its own local IDs. ID mapping (global to frozen local) maintained internally.

**Layer management API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freeze` | Merges frozen + active into a new frozen `IStorage`, closes old frozen layer, resets active layer. Always exactly one frozen layer after freeze. | -- | -- | `AccessClosedStorageException` if closed |
| `layerCount` | Total layers (frozen + active). Always 1 (no frozen) or 2 (one frozen + active). | -- | `Int` | -- |

**Deletion constraint:** Only active-layer entities can be deleted. Deleting a frozen-layer entity throws `FrozenLayerModificationException`.

**Query resolution (properties -- overlay semantics):**
1. Active layer -- if entity has the property, return it
2. Frozen layer -- fallback

For entities in both layers, active-layer values take precedence.

**Query resolution (adjacency -- merge semantics):**
- Edge sets merged from both layers. Frozen edge IDs translated to global IDs.

**Write routing for cross-layer properties:** When property writes target a frozen-only entity, a shadow entry is created in the active layer. Same for edges.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `AccessClosedStorageException` | Operation on closed storage |
| `EntityNotExistException` | Accessing/modifying a non-existent node or edge; adding an edge with missing src/dst |
| `FrozenLayerModificationException` | Deleting an entity from the frozen layer in `LayeredStorageImpl` |

---

## Validation Rules

### NativeStorageImpl

- Operations on closed storage throw `AccessClosedStorageException`
- `addEdge` rejects edges whose src or dst node does not exist with `EntityNotExistException`
- Property access/modification on non-existent entities throws `EntityNotExistException`
- `deleteNode` cascades -- all incident edges removed before the node is deleted

### NativeConcurStorageImpl

- Read operations acquire read lock; write operations acquire write lock
- Adjacency snapshot invalidated on write; rebuilt lazily on next read

### LayeredStorageImpl

- `deleteNode` / `deleteEdge` throw `FrozenLayerModificationException` if entity is not in active layer
- `freeze` fully merges all layer data before closing old frozen layer
- Property overlay: active layer values take precedence over frozen layer values for the same key
- Adjacency merge: `getIncomingEdges` / `getOutgoingEdges` merge results from both layers
- Property writes on frozen-layer entities create shadow entries in active layer
- Layer count is always 1 or 2 due to merge-on-freeze
- Global `Int` IDs are stable across freezes
- All layer operations use standard `IStorage` interface methods on the frozen layer
