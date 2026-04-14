# Entities

> Graph entities (nodes, edges) with storage-backed typed property access.

## Quick Start

```kotlin
val node = graph.addNode("myNode")
node["color"] = "red".strVal
val color = node["color"] as? StrVal
val hasColor = "color" in node
val allProps = node.asMap()

val edge = graph.addEdge("a", "b", "calls")
edge["weight"] = NumVal(10)
edge.update(mapOf("weight" to NumVal(20), "stale" to null))
```

## API

### `IEntity` (sealed interface)

- **`val id: String`** -- Unique identifier. For nodes: user-provided `NodeID`. For edges: `"$src-$tag-$dst"`.
- **`val type: IEntity.Type`** -- Entity type information. `IEntity.Type` has `val name: String`.
- **`operator get(name: String): IValue?`** -- Retrieve property. Returns `null` if absent.
- **`operator set(name: String, value: IValue?)`** -- Set property. Pass `null` to remove.
- **`operator contains(name: String): Boolean`** -- Check property existence.
- **`fun asMap(): Map<String, IValue>`** -- Snapshot of all properties.
- **`fun update(props: Map<String, IValue?>)`** -- Bulk update. `null` values remove properties.

### `AbcEntity` (sealed class, implements `IEntity`)

- **`inline fun <reified T : IValue> getTypeProp(name: String): T?`** -- Type-safe property retrieval. Returns `null` on type mismatch.
- **`protected fun EntityProperty(optName: String? = null, default: T): ReadWriteProperty`** -- Delegate for non-nullable typed property with default.
- **`protected fun EntityProperty(optName: String? = null): ReadWriteProperty`** -- Delegate for nullable typed property.
- **`protected fun EntityType(optName: String? = null, default: T): ReadWriteProperty`** -- Delegate for enum-based `IEntity.Type` property.

### `AbcNode` (extends `AbcEntity`)

- **`val nodeId: NodeID`** -- User-provided node identifier (same as `id`).
- **`val storageId: Int`** -- Internal storage ID. Do not use in graph-level code.
- **`abstract val type: AbcNode.Type`** -- Node type. Subclasses implement `AbcNode.Type` as an enum.
- **`fun doUseStorage(target: IStorage): Boolean`** -- Check if node is bound to a specific storage.

### `AbcEdge` (extends `AbcEntity`)

- **`val srcNid: NodeID`** -- Source node ID.
- **`val dstNid: NodeID`** -- Destination node ID.
- **`val eTag: String`** -- Edge tag.
- **`val storageId: Int`** -- Internal storage ID. Do not use in graph-level code.
- **`var labels: Set<Label>`** -- Visibility labels assigned to this edge.
- **`abstract val type: AbcEdge.Type`** -- Edge type. Subclasses implement `AbcEdge.Type` as an enum.

### Property Delegate Usage

```kotlin
class MyNode : AbcNode() {
    enum class MyType : AbcNode.Type { FUNC, CLASS }
    override var type: MyType by EntityType(default = MyType.FUNC)
    var name: StrVal by EntityProperty(default = StrVal())
    var optData: NumVal? by EntityProperty()
}
```

## Gotchas

- Never instantiate `AbcNode` or `AbcEdge` subclasses directly. The graph layer calls `bind()` to inject storage and identity.
- `AbcNode` filters the internal `__nid__` property from all user-facing APIs (`get`, `set`, `contains`, `asMap`, `update`). Setting `__nid__` raises `IllegalArgumentException`.
- `AbcNode.equals` compares by `NodeID`. `AbcEdge.equals` compares by `storageId`.
- `EntityType` delegate persists the enum name as a `StrVal` property. Renaming enum entries breaks deserialization.
- `EntityProperty` nullable delegate silently ignores `null` writes -- it does not remove the property.
- Property reads and writes go directly to storage. No local caching in the entity object.
