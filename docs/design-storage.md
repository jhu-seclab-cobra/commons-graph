# Storage Design

## Design Overview

- **Classes**: `IStorage`, `IStorage.EdgeStructure`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `LayeredStorageImpl` implements `IStorage` (composes an internal `ActiveLayer` + at most one `FrozenLayer` wrapping a frozen `IStorage`)
- **Abstract**: `IStorage` (implemented by all storage types); per-backend bases `AbcJgraphtStorage`, `AbcMapDBStorage`, `AbcNeo4jStorage` (each extended by a plain and a concurrent subclass)
- **Exceptions**: `GraphException` (abstract base for all graph-layer failures); `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when deleting entities from frozen layer
- **Dependency roles**: Data holders: `EdgeStructure`. Orchestrator: `IStorage` implementations. Composer: `LayeredStorageImpl` (composes internal `ActiveLayer` + at most one `FrozenLayer`). Helpers: `ColumnarProperties` (internal — pure operations on columnar property maps).

The storage layer is the **backend-agnostic directed property graph engine**. It manages nodes and edges identified by auto-generated `Int` IDs, per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), edge structural metadata (source, destination, tag), and graph-level metadata. It does not know about domain types (`Label`).

`IStorage` uses auto-generated `Int` IDs. `addNode()` returns a new `Int` ID; `addEdge()` returns a new `Int` ID. The graph layer manages the `NodeID` (String) to storage `Int` mapping externally.

`IStorage` extends `Flushable`. In-memory implementations (`NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`) implement `flush()` as a no-op. Persistent backends (MapDB, Neo4j) perform actual flush-to-disk. Resource lifecycle (file handles, database connections) is the concrete implementation's responsibility, not the `IStorage` contract.

Persistent backends persist graph-level metadata with the same lifetime as entity data: metadata written via `setMeta` is recoverable after close and reopen of the same storage path. MapDB stores metadata in a dedicated map inside the database file. Neo4j stores metadata on a single dedicated meta node (label `_M`) identified by the reserved marker property `__meta_id__`; the meta node carries meta entries as its properties, never appears in `nodeIDs` (entity nodes use label `_N`), and is deleted by `clear()`.

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

**Internal composition:** `ActiveLayer` (mutable columnar node/edge properties, endpoints, adjacency, meta — global IDs); `FrozenLayer` (immutable snapshot wrapping a frozen `IStorage`, owns global↔local ID maps, built by its companion merge builder during `freeze`); lazy view types (`ActiveColumnViewMap`, `LazyMergedMap`, `MappedEdgeSet`, `UnionSet`) implement cross-layer property overlay and adjacency union without copying.

See `spec.md` for layered query resolution (property overlay, adjacency merge, cross-layer writes) and `model.md` for layered storage invariants.

---

### Backend base classes

Each backend module folds its plain and concurrent implementations into one abstract base holding the full engine; subclasses supply only the guard strategy:

| Base | Module | Subclasses |
|------|--------|-----------|
| `AbcJgraphtStorage` | `commons-graph-impl-jgrapht` | `JgraphtStorageImpl`, `JgraphtConcurStorageImpl` |
| `AbcMapDBStorage` | `commons-graph-impl-mapdb` | `MapDBStorageImpl`, `MapDBConcurStorageImpl` |
| `AbcNeo4jStorage` | `commons-graph-impl-neo4j` | `Neo4jStorageImpl`, `Neo4jConcurStorageImpl` |

Each base declares `protected abstract fun <R> readGuarded(action: () -> R): R` and `writeGuarded`. Plain subclasses pass through; concurrent subclasses guard with a `ReentrantReadWriteLock`. The native pair (`NativeStorageImpl`, `NativeConcurStorageImpl`) is intentionally not folded: the two differ in read-path strategy (live views vs snapshot copies) per `performance-optimizations.md`.

---

### CSV export/import (`storage.nio`)

`NativeCsvIOImpl` (public object) exposes `isValidFile`/`export`/`import`; the CSV format constants and escaping live in internal `NativeCsvFormat`, streaming write in internal `NativeCsvWriter`, streaming read in internal `NativeCsvReader` (with `NodeRecord`/`EdgeRecord` data holders).

---

## Exception / Error Types

All graph-layer exceptions extend the abstract base `GraphException` (extends `Exception`, carries optional `cause`). Callers catch `GraphException` for uniform handling or a concrete subtype for one condition. Third-party backend exceptions are mapped to these domain types at the module boundary.

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | Accessing/modifying a non-existent node or edge; adding an edge with missing src/dst |
| `EntityAlreadyExistException` | Adding a node/edge whose ID already exists |
| `InvalidPropNameException` | Using a reserved property name on an entity |
| `FrozenLayerModificationException` | Deleting an entity from the frozen layer in `LayeredStorageImpl` |

Deletion of a non-existent entity is a no-op at the graph level.
