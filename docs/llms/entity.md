# Entities

> Graph entities (nodes, edges) with storage-backed typed property access.

## Quick Start

```kotlin
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.value.StrVal

fun setColor(entity: IEntity): StrVal? {
    entity["color"] = StrVal("red")
    return entity["color"] as? StrVal
}
```

## API

### `IEntity` (sealed interface)

- **`val id: String`** -- For nodes: user-provided `NodeID`. For edges: display string `"$src-$tag-$dst"`; use the `(srcNid, dstNid, eTag)` triple for identity because delimiters can occur in components.
- **`val type: IEntity.Type`** -- Entity type information. `IEntity.Type` has `val name: String`.
- **`operator get(name: String): IValue?`** -- Retrieve property. Returns `null` if absent.
- **`operator set(name: String, value: IValue?)`** -- Set property. Pass `null` to remove.
- **`operator contains(name: String): Boolean`** -- Check property existence.
- **`fun asMap(): Map<String, IValue>`** -- Snapshot of all properties.
- **`fun update(props: Map<String, IValue?>): Unit`** -- Bulk update. `null` values remove properties.

### `AbcEntity` (sealed class, implements `IEntity`)

- **`inline fun <reified T : IValue> getTypeProp(name: String): T?`** -- Type-safe property retrieval. Returns `null` on type mismatch.
- **`protected inline fun <reified T : IValue> EntityProperty(optName: String? = null, default: T): ReadWriteProperty<IEntity, T>`** -- Delegate for non-nullable typed property with default.
- **`protected inline fun <reified T : IValue?> EntityProperty(optName: String? = null): ReadWriteProperty<IEntity, T?>`** -- Delegate for nullable typed property.
- **`protected inline fun <reified T : IEntity.Type> EntityType(optName: String? = null, default: T): ReadWriteProperty<IEntity, T>`** -- Delegate for enum-based `IEntity.Type` property.

### `AbcNode` (abstract class, extends `AbcEntity`)

- **`val nodeId: NodeID`** -- User-provided node identifier (same as `id`).
- **`val storageId: Int`** -- Internal storage ID. Do not use in graph-level code.
- **`abstract val type: AbcNode.Type`** -- Node type. Subclasses implement `AbcNode.Type` as an enum.
- **`fun doUseStorage(target: IStorage): Boolean`** -- Check if node is bound to a specific storage.

### `AbcEdge` (abstract class, extends `AbcEntity`)

- **`val srcNid: NodeID`** -- Source node ID.
- **`val dstNid: NodeID`** -- Destination node ID.
- **`val eTag: String`** -- Edge tag.
- **`val storageId: Int`** -- Internal storage ID. Do not use in graph-level code.
- **`val labels: Set<Label>`** -- Visibility labels, including any implied labels. Use `graph.setLabels(edge, edge.storedLabels + label)` to update stored labels.
- **`abstract val type: AbcEdge.Type`** -- Edge type. Subclasses implement `AbcEdge.Type` as an enum.

## Configuration

- Create bound Entities through a Graph; entity subclasses supply their type and optional property delegates.
- Set delegate defaults in the subclass; the example operation accepts an already bound `IEntity`.

## Gotchas

- Never instantiate `AbcNode` or `AbcEdge` subclasses directly. The graph layer calls `bind()` to inject storage and identity.
- `AbcNode` filters reserved properties (`__nid__`, `__owners__`) from all user-facing APIs (`get`, `set`, `contains`, `asMap`, `update`). Setting a reserved property raises `IllegalArgumentException`.
- `AbcNode.equals` compares by `NodeID`. `AbcEdge.equals` compares by `(srcNid, dstNid, eTag)` triple.
- `EntityType` delegate persists the enum name as a `StrVal` property. Renaming enum entries breaks deserialization.
- `EntityProperty` nullable delegate propagates `null` to `IEntity.set(name, null)`, which removes the property.
- Property reads and writes go directly to storage. No local caching in the entity object.
- Each `entity["key"]` read and write is atomic on a thread-safe storage. A read-modify-write is not: wrap `entity["n"] = next(entity["n"])` in `graph.storage.criticalSection { }`.
