# Storage Design

## Design Overview

- **Classes**: `IStorage`, `IStorage.EdgeStructure`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `LayeredStorageImpl` implements `IStorage` (composes an inline active layer + at most one frozen `IStorage` layer)
- **Abstract**: `IStorage` (implemented by all storage types)
- **Exceptions**: `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when deleting entities from frozen layer
- **Dependency roles**: Data holders: `EdgeStructure`. Orchestrator: `IStorage` implementations. Composer: `LayeredStorageImpl` (layers active + frozen storage).

The storage layer is the **backend-agnostic directed property graph engine**. It manages nodes and edges identified by auto-generated `Int` IDs, per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), edge structural metadata (source, destination, tag), and graph-level metadata. It does not know about domain types (`Label`).

`IStorage` uses auto-generated `Int` IDs. `addNode()` returns a new `Int` ID; `addEdge()` returns a new `Int` ID. The graph layer manages the `NodeID` (String) to storage `Int` mapping externally.

`IStorage` extends `Flushable`. In-memory implementations (`NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`) implement `flush()` as a no-op. Persistent backends (MapDB, Neo4j) perform actual flush-to-disk. Resource lifecycle (file handles, database connections) is the concrete implementation's responsibility, not the `IStorage` contract.

---

## Class / Type Specifications

### IStorage

**Responsibility:** Minimum capability contract for all storage backends. All entity IDs are auto-generated `Int` values. Extends `Flushable` for persistent backends.

**Methods:**

```kotlin
interface IStorage : Flushable {
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
| `addNode` | Creates a node with optional initial properties | `properties`: initial property map | `Int` -- auto-generated ID | -- |
| `addEdge` | Creates an edge between two existing nodes | `src`, `dst`: node Int IDs; `tag`; `properties` | `Int` -- auto-generated ID | `EntityNotExistException` if src/dst missing |
| `getEdgeStructure` | Returns edge structural info in a single lookup | `id`: edge ID | `EdgeStructure(src, dst, tag)` | `EntityNotExistException` if missing |
| `getNodeProperty` | Single-property access; default delegates to full map | `id`, `name` | `IValue?` | `EntityNotExistException` if missing |
| `getEdgeProperty` | Single-property access; default delegates to full map | `id`, `name` | `IValue?` | `EntityNotExistException` if missing |
| `setNodeProperties` | Atomically adds, updates, and deletes properties | `id`; `properties` (null = delete) | -- | `EntityNotExistException` if missing |
| `setEdgeProperties` | Same semantics as `setNodeProperties` for edges | `id`; `properties` | -- | `EntityNotExistException` if missing |
| `deleteNode` | Removes node and cascades deletion of all incident edges | `id` | -- | `EntityNotExistException` if missing |
| `deleteEdge` | Removes an edge | `id` | -- | `EntityNotExistException` if missing |
| `getIncomingEdges` / `getOutgoingEdges` | Returns all incoming/outgoing edge IDs | `id`: node ID | `Set<Int>` | `EntityNotExistException` if missing |
| `getMeta` / `setMeta` | Reads/writes metadata as named key-value pairs | `name`; `value` | `IValue?` | -- |
| `clear` | Removes all nodes, edges, and metadata | -- | -- | -- |
| `transferTo` | Copies all data into `target`; returns node ID mapping | `target` | `Map<Int, Int>` | -- |
| `flush` | Persists buffered data. No-op for in-memory implementations. | -- | -- | -- |

---

### NativeStorageImpl

**Responsibility:** Pure in-memory `IStorage` implementation. Not thread-safe. `flush()` is a no-op.

---

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` with `ReentrantReadWriteLock`. `flush()` is a no-op.

- Multiple concurrent reads allowed (read lock)
- Writes are exclusive (write lock)
- `nodeIDs` and `edgeIDs` return snapshot copies for thread safety

---

### LayeredStorageImpl

**Responsibility:** Multi-layer freeze-and-stack `IStorage` for phased analysis pipelines. Active layer data stored inline using global `Int` IDs. Frozen layer is an independent `IStorage` instance with its own local IDs. ID mapping (global to frozen local) maintained internally. `flush()` is a no-op.

**Layer management API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freeze` | Merges frozen + active into a new frozen `IStorage`, replaces old frozen layer, resets active layer. Always exactly one frozen layer after freeze. | -- | -- | -- |
| `layerCount` | Total layers (frozen + active). Always 1 (no frozen) or 2 (one frozen + active). | -- | `Int` | -- |

**Deletion constraint:** Only active-layer entities can be deleted. Deleting a frozen-layer entity throws `FrozenLayerModificationException`.

See `spec.md` for layered query resolution (property overlay, adjacency merge, cross-layer writes) and `model.md` for layered storage invariants.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | Accessing/modifying a non-existent node or edge; adding an edge with missing src/dst |
| `FrozenLayerModificationException` | Deleting an entity from the frozen layer in `LayeredStorageImpl` |

Deletion of a non-existent entity is a no-op at the graph level.
