# Entity Module Design

## Design Overview

**Classes**: `AbcBasicEntity`, `AbcNode`, `AbcEdge`, `NodeID`, `EdgeID`

**Relationships**: `AbcBasicEntity` extends `IEntity`, `AbcNode` extends `AbcBasicEntity`, `AbcEdge` extends `AbcBasicEntity`, `NodeID` implements `IEntity.ID`, `EdgeID` implements `IEntity.ID`, `AbcNode` uses `IStorage`, `AbcEdge` uses `IStorage`

**Interfaces**: `IEntity` (implemented by `AbcBasicEntity`), `IEntity.ID` (implemented by `NodeID`, `EdgeID`), `IEntity.Type` (implemented by `AbcNode.Type`, `AbcEdge.Type`)

**Abstract**: `AbcBasicEntity` (extended by `AbcNode`, `AbcEdge`)

**Exceptions**: `InvalidPropNameException` (thrown when invalid property names are used)

---

## Class Specifications

### IEntity Interface

**Responsibility**: Base interface for all graph entities, providing unique identification and typed property storage.

**Properties**:
- `id: ID` - unique identifier for the entity
- `type: Type` - type information for the entity

**[setProp(name: String, value: IValue?)]**
- **Behavior**: Sets a property value by name. Passing null removes the property.
- **Input**: `name: String` - property name, `value: IValue?` - property value or null
- **Output**: Unit
- **Throws**: None

**[setProps(props: Map<String, IValue?>)]**
- **Behavior**: Sets multiple properties at once.
- **Input**: `props: Map<String, IValue?>` - map of property names to values
- **Output**: Unit
- **Throws**: None

**[getProp(name: String): IValue?]**
- **Behavior**: Returns a property value by name.
- **Input**: `name: String` - property name
- **Output**: `IValue?` - property value or null
- **Throws**: None

**[getAllProps(): Map<String, IValue>]**
- **Behavior**: Returns all properties of this entity.
- **Input**: None
- **Output**: `Map<String, IValue>` - map of property names to values
- **Throws**: None

**[containProp(name: String): Boolean]**
- **Behavior**: Returns true if the property exists in the cache.
- **Input**: `name: String` - property name
- **Output**: `Boolean` - true if property exists
- **Throws**: None

**[set(byName: String, newVal: IPrimitiveVal?)]**
- **Behavior**: Sets a primitive property value by name using operator syntax (cache only).
- **Input**: `byName: String` - property name, `newVal: IPrimitiveVal?` - primitive value or null
- **Output**: Unit
- **Throws**: None

**[get(byName: String): IPrimitiveVal?]**
- **Behavior**: Returns a primitive property value by name using operator syntax (cache only).
- **Input**: `byName: String` - property name
- **Output**: `IPrimitiveVal?` - primitive value or null
- **Throws**: None

**[contains(byName: String): Boolean]**
- **Behavior**: Returns true if the property exists in the cache using operator syntax.
- **Input**: `byName: String` - property name
- **Output**: `Boolean` - true if property exists
- **Throws**: None

**Example Usage**:
```kotlin
// Set and get properties
entity.setProp("name", "value".strVal)
val value = entity.getProp("name")

// Set multiple properties
entity.setProps(mapOf("prop1" to "value1".strVal, "prop2" to "value2".strVal))

// Check property existence
if (entity.containProp("name")) {
    val allProps = entity.getAllProps()
}

// Use operator syntax for primitive values
entity["age"] = 25.intVal
val age = entity["age"]
if ("age" in entity) { /* ... */ }
```

### IEntity.ID Interface

**Responsibility**: Uniquely identifies an entity within the graph.

**Properties**:
- `serialize: IValue` - serialized value representing this identifier
- `asString: String` - string representation of this identifier

### IEntity.Type Interface

**Responsibility**: Categorizes the type of entity (e.g., node, edge).

**Properties**:
- `name: String` - type name of the entity

### AbcBasicEntity Class

**Responsibility**: Base entity for graph nodes and edges with property management. Provides property delegate utilities for typed property access.

**Properties**: Inherits from `IEntity`

**[getTypeProp(name: String): T?]**
- **Behavior**: Returns the property value with the specified name, cast to type T.
- **Input**: `name: String` - property name
- **Output**: `T?` - property value as T, or null if absent or type does not match
- **Throws**: None

**[EntityProperty(optName: String?, default: T): ReadWriteProperty<IEntity, T>]**
- **Behavior**: Creates a delegate for a non-nullable typed property.
- **Input**: `optName: String?` - optional custom property name (uses property name if null), `default: T` - default value if property is absent
- **Output**: `ReadWriteProperty<IEntity, T>` - delegate for property access and modification
- **Throws**: None

**[EntityProperty(optName: String?): ReadWriteProperty<IEntity, T?>]**
- **Behavior**: Creates a delegate for a nullable typed property.
- **Input**: `optName: String?` - optional custom property name (uses property name if null)
- **Output**: `ReadWriteProperty<IEntity, T?>` - delegate for property access and modification
- **Throws**: None

**[EntityType(optName: String?, default: T): ReadWriteProperty<IEntity, T>]**
- **Behavior**: Creates a delegate for an entity type property using an enum type. Property names are automatically prefixed with the lowercase class name if optName is not provided.
- **Input**: `optName: String?` - optional custom property name (auto-generated if null), `default: T` - default enum value if property is absent
- **Output**: `ReadWriteProperty<IEntity, T>` - delegate for type property access and modification
- **Throws**: None

**Example Usage**:
```kotlin
// Get typed property
val name: StrVal? = entity.getTypeProp<StrVal>("name")

// Use property delegate for non-nullable property
class MyEntity : AbcBasicEntity() {
    var name: StrVal by EntityProperty(default = "default".strVal)
}

// Use property delegate for nullable property
class MyEntity : AbcBasicEntity() {
    var description: StrVal? by EntityProperty()
}

// Use entity type delegate with enum
enum class Nodname { PERSON, COMPANY }
class MyNode : AbcBasicEntity() {
    var type: Nodname by EntityType(default = Nodname.PERSON)
}
```

### AbcNode Class

**Responsibility**: Abstract base class for graph nodes with storage-backed property management. Provides property access, identity management, and storage integration for nodes.

**Properties**:
- `storage: IStorage` - storage system for node properties
- `id: NodeID` - unique node identifier
- `type: Type` - node type information

**[doUseStorage(target: IStorage): Boolean]**
- **Behavior**: Returns true if the target storage matches this node's storage.
- **Input**: `target: IStorage` - storage to compare
- **Output**: `Boolean` - true if storage matches
- **Throws**: None

**Example Usage**:
```kotlin
class MyNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
    override val type = object : AbcNode.Type {
        override val name = "MyNode"
    }
}

val storage = NativeStorageImpl()
val node = MyNode(storage, NodeID("node1"))

// Set and get properties
node.setProp("name", "Node1".strVal)
val name = node.getProp("name")

// Check storage compatibility
if (node.doUseStorage(storage)) {
    // Node uses this storage
}
```

### AbcEdge Class

**Responsibility**: Abstract base class for graph edges with storage-backed property management. Provides property access, identity management, and storage integration for edges.

**Properties**:
- `storage: IStorage` - storage system for edge properties
- `id: EdgeID` - unique edge identifier
- `type: Type` - type information

**Example Usage**:
```kotlin
class MyEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
    override val type = object : AbcEdge.Type {
        override val name = "MyEdge"
    }
}

val storage = NativeStorageImpl()
val edge = MyEdge(storage, EdgeID(NodeID("src"), NodeID("dst"), "relation"))

// Access edge properties
val srcId = edge.id.srcNid
val dstId = edge.id.dstNid
val name = edge.id.name

// Set and get edge properties
edge.setProp("weight", 1.5.doubleVal)
val weight = edge.getProp("weight")
```

### NodeID Data Class

**Responsibility**: Unique identifier for a node in the graph.

**Properties**:
- `name: String` - node identifier string (constructor parameter)
- `asString: String` - string representation of this identifier "name"
- `serialize: StrVal` - serialized node identifier as StrVal

**[NodeID(name: String)]**
- **Behavior**: Creates a NodeID from a string.
- **Input**: `name: String` - node identifier string
- **Output**: `NodeID` - new node identifier instance
- **Throws**: None

**[NodeID(strVal: StrVal)]**
- **Behavior**: Creates a NodeID from a StrVal.
- **Input**: `strVal: StrVal` - string value representing the node identifier
- **Output**: `NodeID` - new node identifier instance
- **Throws**: None

**Example Usage**:
```kotlin
// Create NodeID from string
val nodeId1 = NodeID("node1")

// Create NodeID from StrVal
val nodeId2 = NodeID("node2".strVal)

// Access properties
val idString = nodeId1.asString  // "node1"
val name = nodeId1.name  // "node1"
val serialized = nodeId1.serialize  // StrVal("node1")
val toString = nodeId1.toString()  // "node1"
```

### EdgeID Data Class

**Responsibility**: Unique identifier for an edge in the graph.

**Properties**:
- `srcNid: NodeID` - source node identifier (constructor parameter)
- `dstNid: NodeID` - destination node identifier (constructor parameter)
- `name: String` - name of the edge (constructor parameter)
- `asString: String` - string representation in format "srcID>dstID:name"
- `serialize: ListVal` - serialized edge identifier as ListVal containing source ID, destination ID, and name

**[EdgeID(srcNid: NodeID, dstNid: NodeID, name: String)]**
- **Behavior**: Creates an EdgeID from source node, destination node, and name.
- **Input**: `srcNid: NodeID` - source node identifier, `dstNid: NodeID` - destination node identifier, `name: String` - name name
- **Output**: `EdgeID` - new edge identifier instance
- **Throws**: None

**[EdgeID(value: ListVal)]**
- **Behavior**: Creates an EdgeID from a ListVal.
- **Input**: `value: ListVal` - list containing source ID, destination ID, and name
- **Output**: `EdgeID` - new edge identifier instance
- **Throws**: None

**Example Usage**:
```kotlin
// Create EdgeID from components
val srcId = NodeID("node1")
val dstId = NodeID("node2")
val edgeId1 = EdgeID(srcId, dstId, "relation")

// Create EdgeID from ListVal
val edgeId2 = EdgeID(ListVal(srcId.serialize, dstId.serialize, "relation".strVal))

// Access properties
val idString = edgeId1.asString  // "node1-relation-node2"
val source = edgeId1.srcNid  // NodeID("node1")
val destination = edgeId1.dstNid  // NodeID("node2")
val name = edgeId1.name  // "relation"
val serialized = edgeId1.serialize  // ListVal([src, dst, type])
val toString = edgeId1.toString()  // "node1-relation-node2"
```

---

## Exception Classes

**InvalidPropNameException**: Raised when an invalid property name is used for an entity. The property name is invalid if it starts with "meta_".

---

## Validation Rules

**Entity Validation**:
- Entity IDs must not be null.
- Node and edge IDs must be unique within their respective collections.

**Node Validation**:
- Node properties are stored in the storage system, not in the node object itself.

**Edge Validation**:
- Edge IDs are composed of source node ID, destination node ID, and name.

**Property Validation**:
- Property names starting with "meta_" are invalid and throw `InvalidPropNameException`.
- Properties with null values are removed from entities.
