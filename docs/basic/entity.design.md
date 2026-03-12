# Entity Module Design

## Design Overview

- **Classes**: `NodeID`, `EdgeID`, `IEntity`, `IEntity.ID`, `IEntity.Type`, `AbcEntity`, `AbcNode`, `AbcEdge`
- **Relationships**: `NodeID` implements `IEntity.ID`; `EdgeID` implements `IEntity.ID`; `AbcNode` extends `AbcEntity` and implements `IEntity`; `AbcEdge` extends `AbcEntity` and implements `IEntity`
- **Abstract**: `IEntity` (sealed; implemented by `AbcNode`, `AbcEdge`); `AbcEntity` (extended by `AbcNode`, `AbcEdge`)
- **Exceptions**: `InvalidPropNameException` defined for reserved property name prefix (not currently enforced at entity level); `EntityNotExistException` raised by storage layer on missing entity
- **Dependency roles**: Data holders: `NodeID`, `EdgeID`. Orchestrator: `AbcNode` / `AbcEdge` (bridge identity to storage-backed property access). Helpers: `AbcEntity` (property delegate utilities).

The entity layer defines how graph elements are **identified** and how they **expose properties**. It does **not** store data — it delegates all property reads and writes to the injected `IStorage`.

---

## Class / Type Specifications

### NodeID

**Responsibility:** Canonical identity value object for nodes, constructed from a plain string name.

**State / Fields:**

```kotlin
data class NodeID(val name: String) : IEntity.ID {
    val serialize: StrVal        // → StrVal(name)
    val asString: String         // → name
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `serialize` | Serializes to `StrVal` | — | `StrVal(name)` | — |
| `asString` | Returns the raw name | — | `String` | — |

---

### EdgeID

**Responsibility:** Canonical identity value object for edges, encoding the full directed edge identity (source, destination, type).

**State / Fields:**

```kotlin
data class EdgeID(val srcNid: NodeID, val dstNid: NodeID, val eType: String) : IEntity.ID {
    val serialize: ListVal       // → ListVal(srcNid.serialize, dstNid.serialize, eType.strVal)
    val asString: String         // → "$srcNid-$eType-$dstNid"
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `serialize` | Serializes to `ListVal` | — | `ListVal(srcNid.serialize, dstNid.serialize, eType.strVal)` | — |
| `asString` | Returns formatted string | — | `"srcName-eType-dstName"` | — |

**Design rationale:** Encoding `(src, dst, type)` in the ID enables O(1) edge lookup by identity without requiring an adjacency scan, and keeps edge semantics explicit at every call site.

---

### IEntity

**Responsibility:** Sealed interface defining the identity and property access contracts for all graph elements.

**State / Fields:**
- `id: IEntity.ID` — the entity's identity
- `type: IEntity.Type` — the entity's type descriptor

**Methods:**

```kotlin
sealed interface IEntity {
    val id: ID
    val type: Type

    fun setProp(name: String, value: IValue?)
    fun setProps(props: Map<String, IValue?>)
    fun getProp(name: String): IValue?
    fun getAllProps(): Map<String, IValue>
    fun containProp(name: String): Boolean

    // Operator sugar (delegates to set/getProp)
    operator fun set(byName: String, newVal: IPrimitiveVal?)
    operator fun get(byName: String): IPrimitiveVal?
    operator fun contains(byName: String): Boolean
}
```

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `setProp` | Sets a single property (null removes it) | `name`: property name; `value`: IValue or null | — | — |
| `setProps` | Sets multiple properties atomically | `props`: map of name→value | — | — |
| `getProp` | Reads a single property | `name`: property name | `IValue?` | — |
| `getAllProps` | Returns all properties | — | `Map<String, IValue>` | — |
| `containProp` | Checks property existence | `name`: property name | `Boolean` | — |
| `set` / `get` / `contains` | Operator sugar delegating to setProp/getProp/containProp | same as above | same as above | same as above |

---

### AbcEntity

**Responsibility:** Provides property delegate utilities for subclasses and a public typed accessor for property retrieval.

`AbcEntity` contains protected delegate helpers (`EntityProperty`, `EntityType`) that subclasses use to declare storage-backed properties. These delegates call `getProp`/`setProp` internally, so all reads/writes go through storage.

**Public Methods:**

`getTypeProp<T>(name)` is the unchecked typed accessor; returns `null` if the value is absent or the cast fails.

---

### AbcNode

**Responsibility:** Abstract base class bridging node identity to storage-backed property access.

**State / Fields:**

```kotlin
abstract class AbcNode : AbcEntity() {
    abstract override val id: NodeID
    abstract override val type: AbcNode.Type
    fun doUseStorage(target: IStorage): Boolean
}
```

- `doUseStorage(target: IStorage): Boolean` — returns true if the entity's storage matches the given target (used for entity provenance verification)

Subclasses only need to provide `id`, `type`, and the storage reference. All property behavior is inherited.

---

### AbcEdge

**Responsibility:** Abstract base class bridging edge identity to storage-backed property access.

**State / Fields:**

```kotlin
abstract class AbcEdge : AbcEntity() {
    abstract override val id: EdgeID
    abstract override val type: AbcEdge.Type
    val srcNid: NodeID get() = id.srcNid
    val dstNid: NodeID get() = id.dstNid
    val eType: String get() = id.eType
    var labels: Set<Label>
}
```

- `srcNid` / `dstNid` — convenience accessors delegating to `id`
- `eType` — convenience accessor for the edge type string, delegating to `id.eType`
- `labels` — the set of `Label` values assigned to this edge, backed by storage property `"labels"`. Used by the label visibility system (see `docs/basic/label.design.md`).

---

### Example Usage

```kotlin
class MyNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
    override val type = object : AbcNode.Type { override val name = "person" }

    var fullName: StrVal by EntityProperty(default = "".strVal)
}

class MyEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
    override val type = object : AbcEdge.Type { override val name = "knows" }
}

// Node identity
val id = NodeID("alice")
val node = MyNode(storage, id)
node.fullName = "Alice Smith".strVal        // writes to storage
println(node.getProp("fullName"))           // reads from storage

// Edge identity carries direction and type
val eid = EdgeID(NodeID("alice"), NodeID("bob"), "knows")
println(eid.srcNid)   // NodeID("alice")
println(eid.dstNid)   // NodeID("bob")
println(eid.eType)    // "knows"
```

---

## Function Specifications

No global functions; all operations are instance methods on sealed types.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `InvalidPropNameException` | Defined for reserved property name prefix (e.g., `meta_`), but not currently enforced at entity level |
| `EntityNotExistException` | Node/edge does not exist in storage (from storage layer) |

---

## Validation Rules

No validation rules are currently enforced at the entity level. Reserved property name prefix checking (`InvalidPropNameException`) is defined but not enforced in entity-level write operations.
