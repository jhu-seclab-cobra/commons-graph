# Entity Module Design

## Design Overview

- **Classes**: `InternalID`, `NodeID`, `IEntity`, `AbcEntity`, `AbcNode`, `AbcEdge`
- **Relationships**: `AbcNode` extends `AbcEntity` and implements `IEntity`; `AbcEdge` extends `AbcEntity` and implements `IEntity`
- **Abstract**: `IEntity` (sealed; implemented by `AbcNode`, `AbcEdge`); `AbcEntity` (extended by `AbcNode`, `AbcEdge`)
- **Exceptions**: `InvalidPropNameException` defined for reserved property name prefix (not currently enforced at entity level); `EntityNotExistException` raised by storage layer on missing entity
- **Dependency roles**: Data holders: `NodeID` (typealias). Orchestrator: `AbcNode` / `AbcEdge` (bridge identity to storage-backed property access). Helpers: `AbcEntity` (property delegate utilities).

The entity layer defines how graph elements are **identified** and how they **expose properties**. It does **not** store data — it delegates all property reads and writes to the injected `IStorage`.

`NodeID` is a `typealias` for `String` — the user-provided node name. `InternalID` is a `typealias` for `Int` — the storage-generated opaque key. The storage layer operates on `Int` IDs only. Conversion between `NodeID` and `InternalID` happens in `AbcMultipleGraph` via `nodeIdCache`.

Edges have no domain-level ID type. They are identified at the graph layer by `(src: NodeID, dst: NodeID, type: String)` tuples, and at the storage layer by opaque `InternalID` values.

---

## Class / Type Specifications

### InternalID

**Responsibility:** Storage-generated opaque identifier for internal entities (nodes, edges).

```kotlin
typealias InternalID = Int
```

External code should not parse or interpret these IDs.

---

### NodeID

**Responsibility:** User-provided node identifier.

```kotlin
typealias NodeID = String
```

`NodeID` is stored as the `__id__` meta property in the node's storage entry. The graph layer maintains a `nodeIdCache: HashMap<NodeID, InternalID>` for bidirectional resolution.

---

### IEntity

**Responsibility:** Sealed interface defining the identity and property access contracts for all graph elements.

**State / Fields:**
- `id: String` — the entity's user-facing identifier (NodeID for nodes, InternalID.toString() for edges)
- `type: IEntity.Type` — the entity's type descriptor

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
| `get` | Returns a property value by name | `name`: property name | `IValue?` | — |
| `set` | Sets a property value (null removes it) | `name`: property name; `value`: IValue or null | — | — |
| `contains` | Checks property existence | `name`: property name | `Boolean` | — |
| `asMap` | Returns all properties as an immutable map snapshot | — | `Map<String, IValue>` | — |
| `update` | Updates multiple properties at once (null values remove) | `props`: map of name->value | — | — |

Properties prefixed with `__` are internal metadata and filtered from external access via `get`/`set`/`contains`/`asMap`/`update`.

---

### AbcEntity

**Responsibility:** Provides property delegate utilities for subclasses and a public typed accessor for property retrieval.

`AbcEntity` contains protected delegate helpers (`EntityProperty`, `EntityType`) that subclasses use to declare storage-backed properties. These delegates call `get`/`set` internally, so all reads/writes go through storage.

**Public Methods:**

`getTypeProp<T>(name)` is the unchecked typed accessor; returns `null` if the value is absent or the cast fails.

---

### AbcNode

**Responsibility:** Abstract base class bridging node identity to storage-backed property access.

**State / Fields:**

```kotlin
abstract class AbcNode(
    protected val storage: IStorage,
    internal val storageId: InternalID,
) : AbcEntity() {
    abstract override val type: AbcNode.Type
    override val id: NodeID  // read from __id__ meta property
    fun doUseStorage(target: IStorage): Boolean
}
```

- `storageId` — the opaque `Int` key used for all storage operations; invisible to external code
- `id` — the user-provided `NodeID`, read lazily from the `__id__` meta property in storage
- `doUseStorage(target)` — returns true if the entity's storage matches the given target (entity provenance verification)

Properties prefixed with `__` are blocked from external `get`/`set`/`contains` access via `require` guards.

---

### AbcEdge

**Responsibility:** Abstract base class bridging edge identity to storage-backed property access.

**State / Fields:**

```kotlin
abstract class AbcEdge(
    protected val storage: IStorage,
) : AbcEntity() {
    abstract override val id: InternalID
    abstract override val type: AbcEdge.Type
    val srcNid: NodeID   // lazy, from __src__ meta property
    val dstNid: NodeID   // lazy, from __dst__ meta property
    val eType: String    // lazy, from __tag__ meta property
    var labels: Set<Label>
}
```

- `id` — the storage-generated opaque edge ID (`InternalID`)
- `srcNid` / `dstNid` — source and destination `NodeID`s, read lazily from `__src__` and `__dst__` meta properties via `storage.getEdgeProperty`
- `eType` — the edge type string, read lazily from `__tag__` meta property
- `labels` — the set of `Label` values assigned to this edge, backed by a `ListVal` storage property named `"labels"`

Properties prefixed with `__` are blocked from external access. The `toString()` format is `{srcNid-eType-dstNid, type}`.

**Meta property convention:**
When `AbcMultipleGraph.addEdge` creates an edge, it stores `__src__`, `__dst__`, `__tag__` as properties alongside user properties. This allows `AbcEdge` to reconstruct domain-level identity from storage without depending on `getEdgeSrc`/`getEdgeDst`/`getEdgeType` at the entity level.

---

### Example Usage

```kotlin
class MyNode(storage: IStorage, storageId: InternalID) : AbcNode(storage, storageId) {
    override val type = object : AbcNode.Type { override val name = "person" }

    var fullName: StrVal by EntityProperty(default = "".strVal)
}

class MyEdge(storage: IStorage, override val id: InternalID) : AbcEdge(storage) {
    override val type = object : AbcEdge.Type { override val name = "knows" }
}

// Node identity
val storageId = storage.addNode(mapOf("__id__" to "alice".strVal))
val node = MyNode(storage, storageId)
node.fullName = "Alice Smith".strVal        // writes to storage
println(node["fullName"])                   // reads from storage
println(node.id)                            // "alice" (from __id__ meta property)

// Edge identity
val edgeId = storage.addEdge(srcSid, dstSid, "knows",
    mapOf("__src__" to "alice".strVal, "__dst__" to "bob".strVal, "__tag__" to "knows".strVal))
val edge = MyEdge(storage, edgeId)
println(edge.srcNid)   // "alice" (lazy, from __src__)
println(edge.dstNid)   // "bob"   (lazy, from __dst__)
println(edge.eType)    // "knows" (lazy, from __tag__)
```

---

## Function Specifications

No global functions; all operations are instance methods on sealed types.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `InvalidPropNameException` | Defined for reserved property name prefix (e.g., `__`), but not currently enforced at entity level |
| `EntityNotExistException` | Node/edge does not exist in storage (from storage layer) |

---

## Validation Rules

- Properties prefixed with `__` are blocked from external access via `require` guards in `AbcNode` and `AbcEdge`
- No other validation rules are currently enforced at the entity level
