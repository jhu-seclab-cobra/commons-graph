# Storage Module Design -- LayeredStorageImpl and NativeConcurStorageImpl

## Design Overview

- **Classes**: `LayeredStorageImpl`, `NativeConcurStorageImpl`
- **Relationships**: `LayeredStorageImpl` implements `IStorage` (composes an inline active layer + at most one frozen `IStorage` layer); `NativeConcurStorageImpl` implements `IStorage`
- **Abstract**: `IStorage` (defined in `storage.design.md`)
- **Exceptions**: `FrozenLayerModificationException` raised when deleting entities from frozen layer; `AccessClosedStorageException` raised on closed storage; `EntityNotExistException` raised on missing entity
- **Dependency roles**: Composer: `LayeredStorageImpl` (layers active + frozen storage). Helpers: `NativeConcurStorageImpl` (thread-safe flat storage).

---

## Class / Type Specifications

### LayeredStorageImpl

**Responsibility:** Multi-layer freeze-and-stack `IStorage` for phased analysis pipelines. Active layer data stored inline using global `Int` IDs. Frozen layer is an independent `IStorage` instance with its own local IDs. ID mapping (global to frozen local) maintained internally.

**State / Fields:**

```
frozenLayer:              IStorage?                    <- at most one frozen layer (or null)
frozenLayerFactory:       () -> IStorage               <- factory for frozen layer (default: NativeStorageImpl)
frozenNodeGlobalToLocal:  HashMap<Int, Int>            <- global ID -> frozen local ID
frozenNodeLocalToGlobal:  HashMap<Int, Int>            <- reverse mapping
frozenEdgeGlobalToLocal:  HashMap<Int, Int>
frozenEdgeLocalToGlobal:  HashMap<Int, Int>
activeNodeColumns:        HashMap<String, HashMap<Int, IValue>>
activeEdgeColumns:        HashMap<String, HashMap<Int, IValue>>
activeEdgeEndpoints:      HashMap<Int, EdgeStructure>
activeOutEdges:           HashMap<Int, MutableSet<Int>>
activeInEdges:            HashMap<Int, MutableSet<Int>>
activeMetaProperties:     HashMap<String, IValue>
nodeCounter, edgeCounter: Int                          <- global auto-increment
```

**Layer management API (concrete class methods, not inherited from IStorage):**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `freeze` | Merges frozen + active into a new frozen `IStorage` via `frozenLayerFactory`, closes old frozen layer, resets active layer. Always exactly one frozen layer after freeze. | -- | -- | `AccessClosedStorageException` if closed |
| `layerCount` | Total layers (frozen + active). Always 1 (no frozen) or 2 (one frozen + active). | -- | `Int` | -- |

**Deletion constraint:** Only active-layer entities can be deleted. Deleting a frozen-layer entity throws `FrozenLayerModificationException`.

**Query resolution (properties -- overlay semantics):**
1. Active layer -- if entity has the property, return it
2. Frozen layer -- fallback

For entities in both layers, properties merged via `LazyMergedMap` (active values take precedence).

**Query resolution (adjacency -- merge semantics):**
- Edge sets merged from both layers via `UnionSet` (allocation-free view). Frozen edge IDs translated to global IDs via `MappedEdgeSet`.

**Single-property optimization:** `getNodeProperty` / `getEdgeProperty` check active layer first, then frozen layer with early return.

**Write routing for cross-layer properties:** When `setNodeProperties` targets a frozen-only node, a shadow entry is created in the active layer (`ensureNodeInActiveLayer`) copying frozen properties. Same for edges via `setEdgeProperties`.

**Freeze data flow:**

```
freeze():
  merged = frozenLayerFactory()
  transfer frozen nodes to merged (preserving structure)
  merge active nodes into merged (overlay properties)
  transfer frozen edges to merged (resolving overlay props)
  merge active-only edges into merged
  transfer metadata (frozen then active overlay)
  close old frozen layer
  swap frozen layer to merged with new ID mappings
  clear active layer
```

---

### NativeConcurStorageImpl

**Responsibility:** Thread-safe in-memory `IStorage` with `ReentrantReadWriteLock`. Same columnar property layout as `NativeStorageImpl`.

- Multiple concurrent reads allowed (read lock)
- Writes are exclusive (write lock)
- Property key interning via `keyPool`: all property keys deduplicated so entities sharing the same property name reference the same `String` object
- Adjacency uses snapshot-on-demand: `AdjEntry` holds a `HashSet` for O(1) writes and a `@Volatile` cached immutable snapshot for reads
- `nodeIDs` and `edgeIDs` return `Set.copyOf()` snapshots for thread safety

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `FrozenLayerModificationException` | Deleting an entity from the frozen layer in `LayeredStorageImpl` |
| `AccessClosedStorageException` | Operation on closed storage |
| `EntityNotExistException` | Accessing/modifying a non-existent entity |

---

## Validation Rules

### LayeredStorageImpl

- `deleteNode` / `deleteEdge` throw `FrozenLayerModificationException` if entity is not in active layer
- `freeze` fully merges all layer data before closing old frozen layer
- Property overlay: active layer values take precedence over frozen layer values for the same key
- Adjacency merge: `getIncomingEdges` / `getOutgoingEdges` merge results from both layers
- `setNodeProperties` / `setEdgeProperties` on frozen-layer entities create shadow entries in active layer
- Layer count is always 1 or 2 due to merge-on-freeze
- Global `Int` IDs are stable across freezes; local-to-global mappings are rebuilt on each freeze
- All layer operations use standard `IStorage` interface methods on the frozen layer

### NativeConcurStorageImpl

- Read operations acquire read lock; write operations acquire write lock
- Adjacency snapshot invalidated on write; rebuilt lazily on next read
