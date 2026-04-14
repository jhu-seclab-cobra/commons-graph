# Storage Module Design -- LayeredStorageImpl

## Design Overview

- **Classes**: `LayeredStorageImpl`
- **Relationships**: `LayeredStorageImpl` implements `IStorage` (composes an inline active layer + at most one frozen `IStorage` layer)
- **Abstract**: `IStorage` (defined in `storage.design.md`)
- **Exceptions**: `FrozenLayerModificationException` raised when deleting entities from frozen layer; `AccessClosedStorageException` raised on closed storage; `EntityNotExistException` raised on missing entity
- **Dependency roles**: Composer: `LayeredStorageImpl` (layers active + frozen storage).

---

## Class / Type Specifications

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
- Property writes on frozen-layer entities create shadow entries in active layer
- Layer count is always 1 or 2 due to merge-on-freeze
- Global `Int` IDs are stable across freezes
- All layer operations use standard `IStorage` interface methods on the frozen layer
