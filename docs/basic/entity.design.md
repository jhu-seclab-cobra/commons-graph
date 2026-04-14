# Entity Module Design

## Design Overview

- **Classes**: `InternalID`, `NodeID`, `IEntity`, `AbcEntity`, `AbcNode`, `AbcEdge`
- **Relationships**: `AbcNode` extends `AbcEntity` and implements `IEntity`; `AbcEdge` extends `AbcEntity` and implements `IEntity`
- **Abstract**: `IEntity` (sealed; implemented by `AbcNode`, `AbcEdge`); `AbcEntity` (sealed; extended by `AbcNode`, `AbcEdge`)
- **Exceptions**: `EntityNotExistException` raised by storage layer on missing entity
- **Dependency roles**: Data holders: `InternalID` (typealias for `Int`), `NodeID` (typealias for `String`). Orchestrator: `AbcNode` / `AbcEdge` (bridge identity to storage-backed property access). Helpers: `AbcEntity` (property delegate utilities).

The entity layer defines how graph elements are **identified** and how they **expose properties**. It does **not** store data -- it delegates all property reads and writes to the injected `IStorage`.

`InternalID` is a `typealias` for `Int` -- the storage-generated opaque identifier for internal entities. `NodeID` is a `typealias` for `String` -- the user-provided node name. The graph layer maintains bidirectional `NodeID`-to-`Int` mapping; entities hold both a `storageId: Int` for storage operations and a user-facing ID (`nodeId` / `edgeId`).

Edges are identified at the graph layer by `(src: NodeID, dst: NodeID, tag: String)` tuples, with a derived string form `"$src-$tag-$dst"`. At the storage layer, edges use auto-generated `Int` IDs.

---

## Class / Type Specifications

### InternalID

**Responsibility:** Storage-internal opaque identifier.

```kotlin
typealias InternalID = Int
```

### NodeID

**Responsibility:** User-provided node identifier.

```kotlin
typealias NodeID = String
```

---

### IEntity

**Responsibility:** Sealed interface defining the identity and property access contracts for all graph elements.

**State / Fields:**
- `id: String` -- the entity's identifier (NodeID for nodes, derived edge ID for edges)
- `type: IEntity.Type` -- the entity's type descriptor

**Methods:**

```kotlin
sealed interface IEntity {
    interface Type { val name: String }
    val id: String
    val type: Type
    operator fun get(name: String): IValue?
    operator fun set(name: String, value: IValue?)
    operator fun contains(name: String): Boolean
    fun asMap(): Map<String, IValue>
    fun update(props: Map<String, IValue?>)
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `get` | Returns a property value by name | `name`: property name | `IValue?` | -- |
| `set` | Sets a property value (null removes it) | `name`: property name; `value`: IValue or null | -- | -- |
| `contains` | Checks property existence | `name`: property name | `Boolean` | -- |
| `asMap` | Returns all properties as an immutable map snapshot | -- | `Map<String, IValue>` | -- |
| `update` | Updates multiple properties at once (null values remove) | `props`: map of name->value | -- | -- |

---

### AbcEntity

**Responsibility:** Sealed base class providing property delegate utilities (`EntityProperty`, `EntityType`) and a typed accessor `getTypeProp<T>(name)`.

Delegates call `get`/`set` internally, so all reads/writes go through storage.

---

### AbcNode

**Responsibility:** Abstract base class bridging node identity to storage-backed property access. No-arg constructor with post-construction `bind()` injection.

**State / Fields:**

```kotlin
abstract class AbcNode : AbcEntity() {
    protected lateinit var storage: IStorage   // injected by bind()
    var storageId: Int                         // injected by bind()
    lateinit var nodeId: NodeID                // injected by bind()
    internal fun bind(storage: IStorage, storageId: Int, nodeId: NodeID)
    override val id: NodeID                    // delegates to nodeId
    abstract override val type: AbcNode.Type
    fun doUseStorage(target: IStorage): Boolean
}
```

- `storage` -- the backing `IStorage`, injected via `bind()`
- `storageId` -- the storage-internal `Int` ID, injected via `bind()`; used for all storage operations
- `nodeId` -- the user-provided `NodeID`, injected via `bind()`
- `id` -- returns `nodeId`
- `doUseStorage(target)` -- returns true if the entity's storage matches the given target
- `hashCode()` -- based on `storageId`
- `equals()` -- compares by `id`
- Property access filters the internal `PROP_NODE_ID` property from all user-facing APIs

---

### AbcEdge

**Responsibility:** Abstract base class bridging edge identity to storage-backed property access. No-arg constructor with post-construction `bind()` injection. Structural info (source, destination, tag) is injected at bind time.

**State / Fields:**

```kotlin
abstract class AbcEdge : AbcEntity() {
    protected lateinit var storage: IStorage   // injected by bind()
    var storageId: Int                         // injected by bind()
    lateinit var srcNid: NodeID                // injected by bind()
    lateinit var dstNid: NodeID                // injected by bind()
    lateinit var eTag: String                  // injected by bind()
    internal fun bind(storage: IStorage, storageId: Int, srcNid: NodeID, dstNid: NodeID, tag: String)
    override val id: String                    // "$srcNid-$eTag-$dstNid"
    abstract override val type: AbcEdge.Type
    var labels: Set<Label>
}
```

- `storage` -- the backing `IStorage`, injected via `bind()`
- `storageId` -- the storage-internal `Int` ID, injected via `bind()`
- `srcNid` / `dstNid` -- source and destination `NodeID`s, injected at bind time
- `eTag` -- the edge tag, injected at bind time
- `labels` -- the set of `Label` values, backed by a `ListVal` storage property named `"labels"`
- `hashCode()` -- based on `storageId`
- `equals()` -- compares by `storageId`
- `toString()` format: `{srcNid-eTag-dstNid, type}`

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityNotExistException` | Node/edge does not exist in storage (from storage layer) |

---

## Validation Rules

- All properties in the entity namespace are user properties; `PROP_NODE_ID` is filtered from node property APIs
- `AbcNode.set`/`update` reject writes to `PROP_NODE_ID` with `require`
