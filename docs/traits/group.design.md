# TraitNodeGroup Design

## Design Overview

**Classes**: `TraitNodeGroup`

**Relationships**: `TraitNodeGroup` extends `IGraph`, `TraitNodeGroup` uses `AbcNode`, `TraitNodeGroup` uses `AbcEdge`, `TraitNodeGroup` uses `NodeID`

**Interfaces**: `TraitNodeGroup` (extends `IGraph`)

**Exceptions**: `IllegalArgumentException` (thrown by `TraitNodeGroup` for invalid group names or suffixes, or when group does not exist), `EntityAlreadyExistException` (thrown by `TraitNodeGroup` when attempting to create duplicate nodes)

---

## Class Specifications

**[TraitNodeGroup] Interface**

**Responsibility**: Extends graph functionality to support node grouping with structured naming conventions.

**Properties**:
- `groupedNodesCounter: MutableMap<String, Int>` - Maps group names to the next available suffix value. Counter values only increase and never decrease. The presence of a group name in this map indicates that the group has been created. For concurrent access, implementations must provide a thread-safe map such as `ConcurrentHashMap<String, Int>`.

**[addGroupNode(group: String, suffix: String? = null): N]**
- **Behavior**: Adds a node to a group. Requires the group to be registered in `groupedNodesCounter` first. Auto-generated suffixes use a monotonically increasing counter. Counter never decreases. May result in gaps in numeric sequence. Node ID format: `graphName@group#suffix`.
- **Input**: `group: String` - group name (must exist in `groupedNodesCounter`), `suffix: String?` - optional suffix
- **Output**: `N` - newly added grouped node
- **Throws**: `IllegalArgumentException` if group name or suffix is invalid, or if group does not exist in `groupedNodesCounter`, `EntityAlreadyExistException` if node ID already exists

**[addGroupNode(sameGroupNode: AbcNode, suffix: String? = null): N]**
- **Behavior**: Adds a node to the same group as an existing node. Extracts group name from existing node identifier using `getGroupName`. Requires the extracted group to be registered in `groupedNodesCounter`.
- **Input**: `sameGroupNode: AbcNode` - node from which group identifier is extracted, `suffix: String?` - optional suffix
- **Output**: `N` - newly added node
- **Throws**: `IllegalArgumentException` if node ID format is invalid, extracted group name or suffix is invalid, or if group does not exist in `groupedNodesCounter`, `EntityAlreadyExistException` if node ID already exists

**[getGroupNode(group: String, suffix: String): N?]**
- **Behavior**: Retrieves a node by group name and suffix.
- **Input**: `group: String` - group name, `suffix: String` - suffix
- **Output**: `N?` - grouped node if exists, `null` otherwise
- **Throws**: `IllegalArgumentException` if group name or suffix is invalid

**[getGroupName(node: AbcNode): String?]**
- **Behavior**: Extracts group name from node identifier. Node ID format: `graphName@groupName#suffix`. Returns `null` if the node ID does not match the required format.
- **Input**: `node: AbcNode` - node
- **Output**: `String?` - group name if format is valid, `null` otherwise
- **Throws**: None

**Example Usage**:
```kotlin
// Using type alias to simplify type parameters
typealias MyGroupGraph = TraitNodeGroup<MyNode, MyEdge>

class MyGraph : IGraph<MyNode, MyEdge>, MyGroupGraph {
    override val groupedNodesCounter: MutableMap<String, Int> = mutableMapOf()
    // ... other implementation
}

val graph = MyGraph()
// Register group before adding nodes
graph.groupedNodesCounter["users"] = 0
val node1 = graph.addGroupNode("users")
val node2 = graph.addGroupNode("users", "custom")
val node3 = graph.addGroupNode(node1)
val groupName = graph.getGroupName(node1)
val retrieved = graph.getGroupNode("users", "custom")
```

**Note**: Type parameters `N` and `E` cannot use `out` or `in` variance modifiers because:
- `TraitNodeGroup` inherits from `IGraph<N, E>`, and `IGraph` uses `N` as parameter type in methods like `delNode(node: N)`
- `E` is required by the parent interface `IGraph<N, E>`
- Type aliases can be used to simplify usage when type parameters are known

---

## Exception Classes

**[IllegalArgumentException]**: Raised when a group name or suffix contains reserved characters (`@` or `#`), is empty, is otherwise invalid, or when attempting to add a node to a group that does not exist in `groupedNodesCounter`.

**[EntityAlreadyExistException]**: Raised when attempting to create a node with an ID that already exists in the graph.

---

## Validation Rules

**Group Name Validation**:
- Group name must not be empty
- Group name must not contain `@` character
- Group name must not contain `#` character
- Group must exist in `groupedNodesCounter` before adding nodes

**Suffix Validation**:
- Suffix must not be empty (if provided)
- Suffix must not contain `#` character (if provided)

**Node ID Format**:
- Grouped nodes: `graphName@group#suffix`
- `graphName` is obtained from the graph's `graphName` property (with `@` characters removed)
- `group` is the group name
- `suffix` is either provided explicitly or auto-generated as a monotonically increasing integer

**Counter Behavior**:
- Counter values only increase and never decrease
- Counter must be initialized in `groupedNodesCounter` before adding nodes to a group
- Counter increments by 1 for each auto-generated suffix
- Counter persists after nodes are deleted
- Counter is updated using `compute` operation for thread-safety

**Thread Safety**:
- For concurrent access, `groupedNodesCounter` must be a thread-safe map implementation such as `ConcurrentHashMap<String, Int>`
- Operations on `groupedNodesCounter` (`compute`) must be thread-safe
- Example implementation:
```kotlin
class MyGraph : TraitNodeGroup<MyNode, MyEdge> {
    override val groupedNodesCounter: MutableMap<String, Int> = ConcurrentHashMap()
    // ... other implementation
}
```
