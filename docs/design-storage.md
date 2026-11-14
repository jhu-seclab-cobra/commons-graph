# Storage Design

## Design Overview

- **Classes**: `IStorage`, `IStorage.EdgeStructure`, `IRevertibleStorage`, `RevertListener`, `RevertNotice`, `StorageEntityIds`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`
- **Relationships**: `NativeStorageImpl` implements `IStorage`; `NativeConcurStorageImpl` implements `IStorage`; `IRevertibleStorage` extends `IStorage`; `LayeredStorageImpl` implements `IRevertibleStorage` (composes an internal `ActiveLayer` + at most one `FrozenLayer` wrapping a frozen `IStorage`) and notifies registered `RevertListener`s
- **Abstract**: `IStorage` (implemented by all storage types); `IRevertibleStorage` (implemented by `LayeredStorageImpl`); `RevertListener` (implemented by graph and poset caches); per-backend bases `AbcJgraphtStorage`, `AbcMapDBStorage`, `AbcNeo4jStorage` (each extended by a plain and a concurrent subclass)
- **Exceptions**: `GraphException` (abstract base for all graph-layer failures); `EntityNotExistException` raised on missing entity access; `FrozenLayerModificationException` raised when deleting a frozen-layer node
- **Dependency roles**: Data holders: `EdgeStructure`. Orchestrator: `IStorage` implementations. Composer: `LayeredStorageImpl` (composes internal `ActiveLayer` + at most one `FrozenLayer`). Helpers: `RowProperties` (internal — the property store of one entity kind: each entity with properties holds one exactly sized row of its own names and values in first-set order, and an entity without properties holds no row; every read and write of an entity looks up that entity's row once and never visits another row, so a row read and a row removal cost O(properties of the row) and a named read scans the row's names; the store keeps one instance per property name in a name pool; the owning storage guards every call, the name pool included).

The storage layer is the **backend-agnostic directed property graph engine**. It manages nodes and edges identified by auto-generated `Int` IDs, per-node and per-edge properties, adjacency indices (incoming/outgoing edge sets per node), edge structural metadata (source, destination, tag), and graph-level metadata. It does not know about domain types (`Label`).

`IStorage` uses auto-generated `Int` IDs. `addNode()` returns a new `Int` ID; `addEdge()` returns a new `Int` ID. The graph layer manages the `NodeID` (String) to storage `Int` mapping externally.

`IStorage` extends `Flushable`. In-memory implementations (`NativeStorageImpl`, `NativeConcurStorageImpl`, `LayeredStorageImpl`) implement `flush()` as a no-op. Persistent backends (MapDB, Neo4j) perform actual flush-to-disk. Resource lifecycle (file handles, database connections) is the concrete implementation's responsibility, not the `IStorage` contract.

Persistent backends persist graph-level metadata with the same lifetime as entity data: wherever entity data survives (reopen of the same storage path, export/import round-trip), metadata written via `setMeta` survives with it. Backend-specific meta persistence mechanics: `impl.md`.

Completion records follow [Storage operation logging](design-storage-logging.md).

## Class / Type Specifications

### IStorage

**Responsibility:** Minimum capability contract for all storage backends. All entity IDs are auto-generated `Int` values. Extends `Flushable` for persistent backends. `IStorage` promises no iteration order for the maps `getNodeProperties` and `getEdgeProperties` return. `NativeStorageImpl` and `NativeConcurStorageImpl` iterate an entity's properties in the order their names were first set; a name deleted and set again moves to the end. `LayeredStorageImpl` promises no order for any of its property reads.

**Methods:**

Implemented compatibility signatures: [Storage API](llms/storage.md#istorage-extends-flushable).

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

Structural selection, constructor settings and index lifecycle: [Storage structural access](design-storage-access.md).

### Typed storage capabilities
The capability adapters and their lifetimes are specified in [Storage capability adapters](design-storage-capabilities.md). [Registered storage fields](design-storage-fields.md) defines typed row and cell access; physical layout below remains separate implementation work.
These are the chosen interfaces and ownership boundaries; missing implementation is listed in [Open Gaps](design-gaps-storage.md#typed-storage-implementation). All types below belong to the core module; visibility is stated per type. Arrays, directories and builders are internal.

| Type / visibility | Responsibility, state and operations |
|---|---|
| `GraphReader` / public | `read(action)` supplies one guarded read scope for structure, typed fields and selected cells. Borrowed cursors/readers expire at scope exit; immutable selected payloads may escape. Mutation inside a scope fails rather than upgrading the read lock. |
| `GraphWriter` / public | Creates/deletes entities and captures property writes under the owning guard; validates endpoints, schema, capacity and derived indexes before exposing a write. |
| `EdgeKind`, `PropertyKey<T>` / public | Registered schema identities with stable serialized names, primitive or immutable payload type, cardinality and capture/codec rules. Registration precedes construction; incompatible duplicate names fail. Process-local ordinals are not serialized identities. |
| `ArrayStorage` / public | Mutable engine containing stable-ID/slot maps, reverse ID arrays, endpoint/kind/tag fields, entity-local properties and directional adjacency. It composes a guard strategy; concurrent use retains compound atomicity. |
| Frozen storage / public read-only capability, internal representation | Read-only capability over frozen arrays and immutable payloads, produced by an internal builder. No unusable mutation methods or lazy mutable translation cache. |
| Compatibility adapter / internal | Retains `IStorage`, graph factory entry points, names, tags and codecs. It converts typed fields to `IValue` at explicit boundaries without a second authoritative row store. |

Public Graph identity remains `NodeID` and opaque `(source, destination, tag)` Edge identity. Raw Storage may retain duplicate physical triples; the graph owner enforces its identity contract. Display IDs are not parseable. Kind is independent of the complete tag. Legacy type properties project the central field; import rejects disagreement.

Surviving logical IDs remain stable through compaction and every Freeze. `clear` starts a new lifetime and invalidates old handles; close invalidates access. Check capacity before increment/allocation so IDs cannot wrap. Internal slot reuse never reuses a live logical identity. Listener registration has a closeable token or an explicit storage-owned graph lifetime.

Required primitive fields use direct access; optional fields carry explicit presence, so zero and `NullVal` are not absence. Extension properties stay entity-local. Sorted key/value rows and packed frozen rows require row-size and mixed-operation measurements; keep small linear rows where they win. No global property-name scan or parallel row-name index is introduced.

Mutable composite `IValue` is captured privately on admission and copied at legacy read/export boundaries. Structural records use registered immutable payloads; read-only Kotlin collection interfaces alone do not establish immutability. Codecs retain schema versions, type tags and reference identity; malformed shape fails before visibility. The library owns generic schema mechanics, not caller-specific reference meanings.

| Function sketch | Input / output / errors |
|---|---|
| `GraphReader.read(action)` | Calls the action with a stable view and returns its result; rejects expired borrowed access and mutation in scope. Interleaved readers/writers snapshot selected IDs before writing. |
| `GraphWriter.addEdge(src,dst,kind,tag,fields)` | Validates/captures fields and returns a stable Storage ID, never a compacted slot; endpoint, schema and capacity errors fail before mutation. Graph insertion separately enforces triple identity. |
| `property(entity, key: PropertyKey<T>)` | Returns immutable `T` or explicit absence; wrong schema/type and absent required fields fail distinctly. Generic `T` may box on the JVM. |
| `intProperty(entity,key): Int` / `longProperty(entity,key): Long` | Required primitive-return field access; rejects absent required fields and wrong schema/type without a nullable/generic return. |
| `snapshotProperties(entity)` | Returns a fixed compatibility map with captured payload content; work includes requested properties and copied mutable payload. |

The library owns physical layers, generic notifications, schema layout and read guards. Callers own evaluation buffers, chronology, plain/supplied projections, acquired observations, reference validation, retirement and atomic publication groups. No library buffer API or duplicate authority is introduced.

Native, concurrent and layered capabilities remain available. JGraphT interoperability and MapDB/Neo4j persistence remain optional with their resource/transaction contracts. Unsupported layered capabilities fail at construction; flat-store family binding remains vacuous. Backend removal needs external-client inventory and separate acceptance. Migration uses one caller-owned implementation selector, the present implementation as default, no independent field/adjacency/value switches and no silent runtime fallback. Default change requires separate acceptance.

### IRevertibleStorage

**Responsibility:** An `IStorage` that removes entities, or exposes dropped entities and property images again, without a `deleteNode`/`deleteEdge` call (a revert). Consumers holding state derived from storage IDs subscribe to learn which IDs a revert removed or restored.

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addRevertListener` | Registers a listener, notified after every revert with a `RevertNotice`. The storage holds the listener for its own lifetime. | `listener: RevertListener` | -- | -- |

### RevertListener

**Responsibility:** `fun interface` receiving one revert notification, `onRevert(notice: RevertNotice)`. It is invoked once for every successful revert, an empty one included, inside the storage's `criticalSection`, after the storage state and every ID translation are final. A listener may read the storage and never writes it.

### RevertNotice and StorageEntityIds

**Responsibility:** Data of one revert, in the ID namespace of the notifying storage. Every set and map is a snapshot. `StorageEntityIds(nodes, edges)` holds node IDs and edge IDs.

| Field | Content |
|-------|---------|
| `removed` | The IDs visible before the revert and absent after it. |
| `restored` | The IDs visible after the revert whose presence or stored image the revert exposed again by dropping reverted writes, a promoted row included even when the dropped write left the same value. Disjoint from `removed` for each kind. |
| `edgeStructuresBefore` | The source, destination and tag, read before the revert, of every affected edge visible before it: the removed edges and the restored edges visible before. A restored edge missing from the map has reappeared; one present in it survived with its stored image restored. |

### NativeStorageImpl

**Responsibility:** Pure in-memory `IStorage` implementation. Not thread-safe. `flush()` is a no-op. Optional fixed projection: [exact adjacency access](design-exact-adjacency.md), also supported by `LayeredStorageImpl`. **Name pool:** each `RowProperties` store keeps every property name set since its last `clear` and gives every row the same `String` instance for one name; a name stays in the pool after the last row holding it drops it. The pool therefore holds at most the distinct property names written into the store since its last `clear`: for `NativeStorageImpl` and `NativeConcurStorageImpl` since their last `clear()`, for the Active layer of `LayeredStorageImpl` since the last `freeze`, `clearActiveLayer` or `clear`, and a Frozen layer is a new storage built at each `freeze`. A program bounds it by the distinct property names its analysis writes.

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` with `ReentrantReadWriteLock`. `flush()` is a no-op.

- Multiple concurrent reads allowed (read lock); a property read returns a copy taken under the read lock, in first-set order
- Writes are exclusive (write lock); only writes add names to the name pool, so every read and write of the property rows and the name pool holds the lock
- `nodeIDs` and `edgeIDs` return snapshot copies for thread safety
- `criticalSectionLock` is the write lock

### LayeredStorageImpl

**Responsibility:** Multi-layer freeze-and-stack `IRevertibleStorage` for phased analysis pipelines. Active layer data stored inline using global `Int` IDs. Frozen layer is an independent `IStorage` instance with its own local IDs. ID mapping (global to frozen local) maintained internally. `flush()` is a no-op.

**Layer management API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freeze` | Merges frozen + active into a new frozen `IStorage`, replaces old Frozen layer, resets Active layer. Always exactly one Frozen layer after freeze. | -- | -- | -- |
| `clearActiveLayer` | Reverts to the Frozen layer: drops every active-layer node, edge, property change, and tombstone. Notifies revert listeners once (`spec-storage.md` Revert): the active-only entities as removed; the promoted frozen nodes, the promoted frozen edges and the tombstoned edges as restored. | -- | -- | -- |
| `layerCount` | Total layers (frozen + active). Always 1 (no frozen) or 2 (one frozen + active). | -- | `Int` | -- |

`clear()` is a revert: it notifies revert listeners with every node and edge it removes, with their edge structures, and restores nothing.

**Edge deletion:** Deleting an active-only edge removes it. Deleting a frozen-layer edge — including one promoted into the Active layer — drops the active copy and records a tombstone in the Active layer. A tombstoned edge reads as absent through the ordinary `IStorage` reads (`containsEdge`, `edgeIDs`, adjacency, structure, properties) and is excluded from `freeze` merges; only the `FROZEN` snapshot below still shows its frozen row; `clearActiveLayer` drops the tombstone and the frozen edge reads again.

**Node deletion constraint:** Only nodes that exist solely in the Active layer can be deleted. Deleting a frozen-layer node — including one promoted into the Active layer — throws `FrozenLayerModificationException`.

**Metadata deletion:** `setMeta(name, null)` deletes the property across both layers: the Active layer records a tombstone that masks the frozen value on reads and excludes the name from `freeze` merges. A later non-null `setMeta` clears the tombstone.

**Layer snapshots:** Family selection follows [Property families](design-storage-families.md). One private nullable declaration slot supplies a stable accessor to `LayerStack` and `ActiveLayer`; each point or fold captures it once under the storage lock.

- `getEdgeProperties(id, layer: StorageLayer): Map<String, IValue>?` copies one complete selected image under one read lock, so labels and payload reflect one stored image.
- `StorageLayer.VISIBLE` selects the ordinary view, null for an absent or tombstoned Edge. `ACTIVE` selects the composed image of an added or promoted Edge, null when it has no Active layer writes. `FROZEN` selects its frozen row and ignores tombstones.
- `getEdgeProperty(id, name, layer)` reads one named property without collecting a row. `VISIBLE` uses the model’s family selection; `ACTIVE` returns only explicit edits on a promoted Edge; `FROZEN` ignores tombstones.
- An ID unknown to every layer returns null for every snapshot operation, rather than throwing.

**Internal composition:** `LayerStack` (internal; holds the two layers and resolves every unlocked read over them: ordinary `IStorage` reads use active edits before frozen values and tombstones hide frozen edges); `ActiveLayer` (mutable property rows for active-only and promoted nodes and active-only edges, sparse named edits and deletion markers for promoted frozen edges, endpoints, adjacency, meta and tombstones — global IDs); `FrozenLayer` (immutable snapshot wrapping a frozen `IStorage`, owns global↔local ID maps, built by its companion merge builder during `freeze`); lazy view types (`RowViewMap` over one entity's row, shared with `NativeStorageImpl`, `MappedEdgeSet`, `UnionSet`) implement active-only property reads, frozen-edge ID translation, and cross-layer adjacency union inside the lock; a public collection read copies the view's contents before releasing it.

See [Layered Storage algorithms](spec-storage.md) for layered query resolution (active-authoritative property reads, adjacency merge, promotion writes) and `model.md` for Layered Storage invariants.

**Thread safety:** every operation holds one `ReentrantReadWriteLock` — reads shared, writes (including `freeze`, `clearActiveLayer`, and `clear`) exclusive. `criticalSectionLock` is the write lock; revert listeners run while it is held. Like `NativeConcurStorageImpl`, every collection it returns (property maps, adjacency sets, `nodeIDs`, `edgeIDs`, `metaNames`) is a copy taken under the read lock: later writes, `freeze`, and `clearActiveLayer` do not change it, and a caller reads it outside the lock.

### Backend base classes

Each backend module folds its plain and concurrent implementations into one abstract base holding the full engine; subclasses supply only the guard strategy:

| Base | Module | Subclasses |
|------|--------|-----------|
| `AbcJgraphtStorage` | `commons-graph-impl-jgrapht` | `JgraphtStorageImpl`, `JgraphtConcurStorageImpl` |
| `AbcMapDBStorage` | `commons-graph-impl-mapdb` | `MapDBStorageImpl`, `MapDBConcurStorageImpl` |
| `AbcNeo4jStorage` | `commons-graph-impl-neo4j` | `Neo4jStorageImpl`, `Neo4jConcurStorageImpl` |

Each base declares `protected abstract fun <R> readGuarded(action: () -> R): R` and `writeGuarded`. Plain subclasses pass through and leave `criticalSectionLock` `null`; concurrent subclasses guard with a `ReentrantReadWriteLock` and expose its write lock as `criticalSectionLock`. `NativeStorageImpl` and `NativeConcurStorageImpl` remain separate classes: they differ in read-path strategy (live views vs snapshot copies) as specified by their collection-return contracts.

### Storage export/import

`NativeCsvIOImpl` (public object) exposes `isValidFile`/`export`/`import`; the CSV format constants and escaping live in internal `NativeCsvFormat`, streaming write in internal `NativeCsvWriter`, streaming read in internal `NativeCsvReader` (with `NodeRecord`/`EdgeRecord` data holders). `import` owns the reader lifetime and completion record. Its private `importNodes` applies the node predicate, creates accepted nodes, and returns the source-to-destination ID map and filtered source IDs before any edge is imported. Edge import uses both results to exclude filtered endpoints and reject unknown endpoints; metadata is imported last.

`JgraphtGmlIOImpl.export` writes serialized structural fields `nid`, `esrc`, `edst`, and `etype` under those GML attribute names. Internal `encodePropertyName` and `decodePropertyName` in `GmlPropertyNames.kt` own property-name conversion in the JGraphT module. Every user property name is encoded as `property` followed by four lowercase hexadecimal digits for each UTF-16 code unit, including empty names and names reserved by GML. This injective encoding uses only letters and digits and separates user properties from structural fields. Import removes the structural fields before decoding user property names; a name without the prefix is retained, while a prefixed name must consist of complete hexadecimal groups. Property values use the existing Value serializer. Thus `weight`, `nid`, `id`, `label`, and names beginning with `property` retain their names and values after export/import.

## Exception / Error Types

All graph-layer exceptions extend the abstract base `GraphException` (extends `Exception`, carries optional `cause`). Callers catch `GraphException` for uniform handling or a concrete subtype for one condition. Third-party backend exceptions are mapped to these domain types at the module boundary.

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | Accessing/modifying a non-existent node or edge through an `IStorage` accessor (the `StorageLayer` snapshot returns null instead); adding an edge with missing src/dst |
| `EntityAlreadyExistException` | Graph layer: adding a node/edge whose `NodeID` or `(src, dst, tag)` triple already exists; never raised by `IStorage`, whose IDs are auto-generated |
| `InvalidPropNameException` | Property name collides with a backend reserved name (Neo4j `__sid__`, `__tag__`, `__meta_id__`) or a MapDB exporter structural attribute (`_nid`/`_esrc`/`_edst`/`_etag`) |
| `FrozenLayerModificationException` | Deleting a frozen-layer node in `LayeredStorageImpl` |

Deletion of a non-existent entity is a no-op at the graph level.

## Caller Examples

```kotlin
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl

val storage = LayeredStorageImpl()
val node = storage.addNode()
storage.freeze()
storage.addNode()
storage.clearActiveLayer()
check(storage.nodeIDs == setOf(node))
```

For the planned array/typed-adjacency path, register keys/kinds before constructing `ArrayStorage(schema)`; use its writer to add endpoints and an edge, then consume `incoming(node, kind)` and `property(entity,key)` within `GraphReader.read`. The selected immutable payload remains valid after return; a borrowed reader does not. `snapshotProperties` produces a fixed captured image before a later writer update.

## Open Gaps

[Storage mechanism questions](design-gaps-storage.md) identify costs and unsupported capabilities.
