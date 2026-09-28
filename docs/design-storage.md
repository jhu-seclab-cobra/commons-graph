# Storage Design

## Design Overview

- **Classes**: `IStorage`, `IStorage.EdgeStructure`, `IRevertibleStorage`, `RevertListener`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `IRevertibleStorage` extends `IStorage`; `LayeredStorageImpl` implements `IRevertibleStorage` (composes an internal `ActiveLayer` + at most one `FrozenLayer` wrapping a frozen `IStorage`) and notifies registered `RevertListener`s
- **Abstract**: `IStorage` (implemented by all storage types); `IRevertibleStorage` (implemented by `LayeredStorageImpl`); `RevertListener` (implemented by graph and poset caches); per-backend bases `AbcJgraphtStorage`, `AbcMapDBStorage`, `AbcNeo4jStorage` (each extended by a plain and a concurrent subclass)
- **Exceptions**: `GraphException` (abstract base for all graph-layer failures); `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when deleting a frozen-layer node
- **Dependency roles**: Data holders: `EdgeStructure`. Orchestrator: `IStorage` implementations. Composer: `LayeredStorageImpl` (composes internal `ActiveLayer` + at most one `FrozenLayer`). Helpers: `ColumnarProperties` (internal — pure operations on columnar property maps).

The storage layer is the **backend-agnostic directed property graph engine**. It manages nodes and edges identified by auto-generated `Int` IDs, per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), edge structural metadata (source, destination, tag), and graph-level metadata. It does not know about domain types (`Label`).

`IStorage` uses auto-generated `Int` IDs. `addNode()` returns a new `Int` ID; `addEdge()` returns a new `Int` ID. The graph layer manages the `NodeID` (String) to storage `Int` mapping externally.

`IStorage` extends `Flushable`. In-memory implementations (`NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`) implement `flush()` as a no-op. Persistent backends (MapDB, Neo4j) perform actual flush-to-disk. Resource lifecycle (file handles, database connections) is the concrete implementation's responsibility, not the `IStorage` contract.

Persistent backends persist graph-level metadata with the same lifetime as entity data: wherever entity data survives (reopen of the same storage path, export/import round-trip), metadata written via `setMeta` survives with it. Backend-specific meta persistence mechanics: `impl.md`.

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
    val criticalSectionLock: Lock? get() = null
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
| `transferTo` | Copies all data into `target`; returns node ID mapping. `target` must be a different storage instance. | `target` | `Map<Int, Int>` | `IllegalArgumentException` if `target` is the receiver |
| `flush` | Persists buffered data. No-op for in-memory implementations. | -- | -- | -- |
| `criticalSectionLock` | The lock `criticalSection` holds: the exclusive half of the implementation's own read-write lock. `null` for an implementation that is not thread-safe. | -- | `Lock?` | -- |

---

### IRevertibleStorage

**Responsibility:** An `IStorage` that removes entities without a `deleteNode`/`deleteEdge` call (a revert). Consumers holding caches keyed by storage IDs subscribe to learn which IDs a revert removed.

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addRevertListener` | Registers a listener, notified after every revert with the IDs the revert removed. The storage holds the listener for its own lifetime. | `listener: RevertListener` | -- | -- |

### RevertListener

**Responsibility:** `fun interface` receiving one revert notification: `onRevert(removedNodes: Set<Int>, removedEdges: Set<Int>)`. The sets hold exactly the node and edge IDs that existed before the revert and do not exist after it. Invoked inside the storage's `criticalSection`, after the storage state is final; a listener does not call back into the storage.

---

### NativeStorageImpl

**Responsibility:** Pure in-memory `IStorage` implementation. Not thread-safe. `flush()` is a no-op.

---

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` with `ReentrantReadWriteLock`. `flush()` is a no-op.

- Multiple concurrent reads allowed (read lock)
- Writes are exclusive (write lock)
- `nodeIDs` and `edgeIDs` return snapshot copies for thread safety
- `criticalSectionLock` is the write lock

---

### LayeredStorageImpl

**Responsibility:** Multi-layer freeze-and-stack `IRevertibleStorage` for phased analysis pipelines. Active layer data stored inline using global `Int` IDs. Frozen layer is an independent `IStorage` instance with its own local IDs. ID mapping (global to frozen local) maintained internally. `flush()` is a no-op.

**Layer management API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freeze` | Merges frozen + active into a new frozen `IStorage`, replaces old frozen layer, resets active layer. Always exactly one frozen layer after freeze. | -- | -- | -- |
| `clearActiveLayer` | Reverts to the frozen layer: drops every active-layer node, edge, property change, and tombstone. Notifies revert listeners with the active-only node and edge IDs. | -- | -- | -- |
| `layerCount` | Total layers (frozen + active). Always 1 (no frozen) or 2 (one frozen + active). | -- | `Int` | -- |

`clear()` is also a revert: it notifies revert listeners with every node and edge ID it removes.

**Edge deletion:** Deleting an active-only edge removes it. Deleting a frozen-layer edge — including one promoted into the active layer — drops the active copy and records a tombstone in the active layer. A tombstoned edge reads as absent everywhere (`containsEdge`, `edgeIDs`, adjacency, structure, properties) and is excluded from `freeze` merges; `clearActiveLayer` drops the tombstone and the frozen edge reads again.

**Node deletion constraint:** Only nodes that exist solely in the active layer can be deleted. Deleting a frozen-layer node — including one promoted into the active layer — throws `FrozenLayerModificationException`.

**Metadata deletion:** `setMeta(name, null)` deletes the property across both layers: the active layer records a tombstone that masks the frozen value on reads and excludes the name from `freeze` merges. A later non-null `setMeta` clears the tombstone.

**Internal composition:** `ActiveLayer` (mutable columnar node/edge properties, endpoints, adjacency, meta, edge and meta tombstones — global IDs); `FrozenLayer` (immutable snapshot wrapping a frozen `IStorage`, owns global↔local ID maps, built by its companion merge builder during `freeze`); lazy view types (`ColumnViewMap` shared with the native backends, `MappedEdgeSet`, `UnionSet`) implement active-layer property reads, frozen-edge ID translation, and cross-layer adjacency union without copying.

See `spec-graph.md` for layered query resolution (active-authoritative property reads, adjacency merge, promotion writes) and `model.md` for layered storage invariants.

**Thread safety:** every operation holds one `ReentrantReadWriteLock` — reads shared, writes (including `freeze`, `clearActiveLayer`, and `clear`) exclusive. `criticalSectionLock` is the write lock; revert listeners run while it is held. Unlike `NativeConcurStorageImpl`, the collections it returns (property maps, adjacency sets, `nodeIDs`, `edgeIDs`, `metaNames`) are the lazy views above, not copies: the lock covers building a view, not reading it. A view reflects later writes, and `freeze` and `clearActiveLayer` empty the active-layer data it reads. A caller that reads a returned view while another thread writes reads it inside `criticalSection`.

---

### Storage critical section

**Responsibility:** `IStorage.criticalSection(action)` runs `action` holding the storage's `criticalSectionLock`, or runs it unguarded when that is `null`. A thread-safe `IStorage` makes each single call atomic; a graph-layer step that makes several calls (allocate an ID then persist it, check-then-create, read-modify-write, a multi-edge traversal) runs inside `criticalSection` so the sequence is atomic as a unit. Every graph and poset sharing one storage serializes through that storage's lock; a nested call on the same thread does not block, and every storage call inside the section re-enters the same lock.

Compound atomicity is a property of the chosen implementation, like per-call atomicity: a graph over a storage that is not thread-safe is single-threaded. Each storage holds exactly one lock; no lock state lives outside the storage.

A caller holding a storage's read lock never enters its critical section (a read lock does not upgrade to the write lock). Storage implementations never invoke caller code while holding their read lock.

**Concurrent use per implementation:**

| Implementation | Thread-safe | Returned collections (property maps, adjacency sets, ID and meta-name sets) |
|----------------|-------------|-------------------------------------------|
| `NativeStorageImpl`, `JgraphtStorageImpl`, `MapDBStorageImpl`, `Neo4jStorageImpl` | No (`criticalSectionLock` is `null`) | -- |
| `NativeConcurStorageImpl`, `JgraphtConcurStorageImpl`, `MapDBConcurStorageImpl`, `Neo4jConcurStorageImpl` | Yes | Copies, readable outside the lock |
| `LayeredStorageImpl` | Yes | Live views, readable only inside `criticalSection` while another thread writes |

A section that holds two storages' locks at once (a `criticalSection` nested inside another storage's, or `transferTo`, which holds the source's read lock while writing the target) deadlocks against a thread taking the same two locks in the opposite order. Callers take the locks of any two storages in one fixed order.

---

### Backend base classes

Each backend module folds its plain and concurrent implementations into one abstract base holding the full engine; subclasses supply only the guard strategy:

| Base | Module | Subclasses |
|------|--------|-----------|
| `AbcJgraphtStorage` | `commons-graph-impl-jgrapht` | `JgraphtStorageImpl`, `JgraphtConcurStorageImpl` |
| `AbcMapDBStorage` | `commons-graph-impl-mapdb` | `MapDBStorageImpl`, `MapDBConcurStorageImpl` |
| `AbcNeo4jStorage` | `commons-graph-impl-neo4j` | `Neo4jStorageImpl`, `Neo4jConcurStorageImpl` |

Each base declares `protected abstract fun <R> readGuarded(action: () -> R): R` and `writeGuarded`. Plain subclasses pass through and leave `criticalSectionLock` `null`; concurrent subclasses guard with a `ReentrantReadWriteLock` and expose its write lock as `criticalSectionLock`. `NativeStorageImpl` and `NativeConcurStorageImpl` remain separate classes: they differ in read-path strategy (live views vs snapshot copies) per `performance-optimizations.md`.

---

### CSV export/import (`storage.nio`)

`NativeCsvIOImpl` (public object) exposes `isValidFile`/`export`/`import`; the CSV format constants and escaping live in internal `NativeCsvFormat`, streaming write in internal `NativeCsvWriter`, streaming read in internal `NativeCsvReader` (with `NodeRecord`/`EdgeRecord` data holders).

---

## Exception / Error Types

All graph-layer exceptions extend the abstract base `GraphException` (extends `Exception`, carries optional `cause`). Callers catch `GraphException` for uniform handling or a concrete subtype for one condition. Third-party backend exceptions are mapped to these domain types at the module boundary.

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | Accessing/modifying a non-existent node or edge; adding an edge with missing src/dst |
| `EntityAlreadyExistException` | Graph layer: adding a node/edge whose `NodeID` or `(src, dst, tag)` triple already exists; never raised by `IStorage`, whose IDs are auto-generated |
| `InvalidPropNameException` | Property name collides with a backend reserved name (Neo4j `__sid__`, `__tag__`, `__meta_id__`) or an exporter structural attribute (GML `nid`/`esrc`/`edst`/`etype`, MapDB `_nid`/`_esrc`/`_edst`/`_etag`) |
| `FrozenLayerModificationException` | Deleting a frozen-layer node in `LayeredStorageImpl` |

Deletion of a non-existent entity is a no-op at the graph level.
