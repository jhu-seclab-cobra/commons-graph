# Graph Module Design

## Design Overview

**Classes**: `AbcBasicGraph`, `AbcSimpleGraph`, `AbcMultiGraph`

**Relationships**: `AbcBasicGraph` implements `IGraph`, `AbcSimpleGraph` extends `AbcBasicGraph`, `AbcMultiGraph` extends `AbcBasicGraph`, `AbcBasicGraph` contains `IStorage`

**Interfaces**: `IGraph` (implemented by `AbcBasicGraph`)

**Abstract**: `AbcBasicGraph` (extended by `AbcSimpleGraph`, `AbcMultiGraph`)

**Exceptions**: `EntityNotExistException` (thrown by `addEdge`), `EntityAlreadyExistException` (thrown by `addNode`, `addEdge`)

---

## Class Specifications

### IGraph Interface

**Responsibility**: Interface representing a generic graph structure where nodes and edges are modeled by types N and E. The graph provides operations on entities using strongly-typed IDs (NodeID/EdgeID) for all input parameters, and returns entity objects (N/E) for all output operations. Entity objects act as operation wrappers and do not store data themselves; all data is managed through the storage layer.

**Properties**:
- `nodeIDs: Set<NodeID>` - cached view of all node identifiers currently stored
- `edgeIDs: Set<EdgeID>` - cached view of all edge identifiers currently stored

#### Node CRUD Operations

**[addNode(withID: NodeID): N]**
- **Behavior**: Creates and adds a new node to the graph with the specified NodeID. Returns an entity object that wraps the operation.
- **Input**: `withID: NodeID` - the node identifier to use (created by the caller)
- **Output**: `N` - newly created node entity object (operation wrapper)
- **Throws**: `EntityAlreadyExistException` if a node with the specified ID already exists in this graph

**[getNode(whoseID: NodeID): N?]**
- **Behavior**: Retrieves a node entity object from the graph using its identifier. The entity object is an operation wrapper, not a data container.
- **Input**: `whoseID: NodeID` - identifier of the node to retrieve
- **Output**: `N?` - node entity object if it exists, null otherwise
- **Throws**: None

**[containNode(whoseID: NodeID): Boolean]**
- **Behavior**: Checks whether a node with the specified identifier exists in the graph.
- **Input**: `whoseID: NodeID` - node identifier to check
- **Output**: `Boolean` - true if node exists, false otherwise
- **Throws**: None

**[delNode(whoseID: NodeID)]**
- **Behavior**: Deletes a node and all associated edges from the graph using the node identifier.
- **Input**: `whoseID: NodeID` - identifier of the node to delete
- **Output**: Unit
- **Throws**: None

**[getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves all node entity objects in the graph that satisfy the given predicate.
- **Input**: `doSatisfy: (N) -> Boolean` - predicate to filter nodes
- **Output**: `Sequence<N>` - sequence of node entity objects that satisfy the predicate
- **Throws**: None

#### Edge CRUD Operations

**[addEdge(withID: EdgeID): E]**
- **Behavior**: Creates and adds a new edge to the graph with the specified EdgeID. Returns an entity object that wraps the operation. The source and destination nodes must exist in the graph.
- **Input**: `withID: EdgeID` - the edge identifier to use (created by the caller, includes source/destination node IDs and edge name)
- **Output**: `E` - newly created edge entity object (operation wrapper)
- **Throws**: `EntityNotExistException` if source or destination node does not exist, `EntityAlreadyExistException` if an edge with the specified ID already exists

**[getEdge(whoseID: EdgeID): E?]**
- **Behavior**: Retrieves an edge entity object from the graph using its identifier. The entity object is an operation wrapper, not a data container.
- **Input**: `whoseID: EdgeID` - identifier of the edge to retrieve
- **Output**: `E?` - edge entity object if it exists, null otherwise
- **Throws**: None

**[containEdge(whoseID: EdgeID): Boolean]**
- **Behavior**: Checks whether an edge with the specified identifier exists in the graph.
- **Input**: `whoseID: EdgeID` - edge identifier to check
- **Output**: `Boolean` - true if edge exists, false otherwise
- **Throws**: None

**[delEdge(whoseID: EdgeID)]**
- **Behavior**: Deletes the specified edge from the graph using the edge identifier.
- **Input**: `whoseID: EdgeID` - identifier of the edge to delete
- **Output**: Unit
- **Throws**: None

**[getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>]**
- **Behavior**: Retrieves all edge entity objects in the graph that satisfy the given predicate.
- **Input**: `doSatisfy: (E) -> Boolean` - predicate to filter edges
- **Output**: `Sequence<E>` - sequence of edge entity objects that satisfy the predicate
- **Throws**: None

#### Graph Structure Queries

**[getIncomingEdges(of: NodeID): Sequence<E>]**
- **Behavior**: Retrieves all incoming edge entity objects to the specified node.
- **Input**: `of: NodeID` - identifier of the node whose incoming edges should be returned
- **Output**: `Sequence<E>` - sequence of incoming edge entity objects
- **Throws**: None

**[getOutgoingEdges(of: NodeID): Sequence<E>]**
- **Behavior**: Retrieves all outgoing edge entity objects from the specified node.
- **Input**: `of: NodeID` - identifier of the node whose outgoing edges should be returned
- **Output**: `Sequence<E>` - sequence of outgoing edge entity objects
- **Throws**: None

**[getChildren(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves child node entity objects reachable from the specified node by edges satisfying the predicate.
- **Input**: `of: NodeID` - starting node identifier, `edgeCond: (E) -> Boolean` - predicate applied to connecting edges
- **Output**: `Sequence<N>` - sequence of child node entity objects
- **Throws**: None

**[getParents(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves parent node entity objects that connect to the specified node via edges satisfying the predicate.
- **Input**: `of: NodeID` - node identifier, `edgeCond: (E) -> Boolean` - edge predicate
- **Output**: `Sequence<N>` - sequence of parent node entity objects
- **Throws**: None

**[getDescendants(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Computes descendant node entity objects of the specified node via breadth-first traversal subject to an edge predicate.
- **Input**: `of: NodeID` - node identifier, `edgeCond: (E) -> Boolean` - edge predicate
- **Output**: `Sequence<N>` - sequence of descendant node entity objects
- **Throws**: None

**[getAncestors(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Computes ancestor node entity objects of the specified node via depth-first traversal subject to an edge predicate.
- **Input**: `of: NodeID` - node identifier, `edgeCond: (E) -> Boolean` - edge predicate
- **Output**: `Sequence<N>` - sequence of ancestor node entity objects
- **Throws**: None

**Example Usage**:
```kotlin
// Create graph with concrete node/edge types
class MyNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
    override val type = object : AbcNode.Type { override val name = "MyNode" }
}
class MyEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
    override val type = object : AbcEdge.Type { override val name = "MyEdge" }
}

val graph = object : AbcSimpleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

// Node CRUD Operations - all use NodeID for input
val nodeAID = NodeID("userA")
val nodeBID = NodeID("userB")
val nodeA = graph.addNode(nodeAID)
val nodeB = graph.addNode(nodeBID)

// Check containment using IDs
if (graph.containNode(nodeAID)) { /* ... */ }

// Get node using ID
val foundNode = graph.getNode(nodeAID)

// Edge CRUD Operations - all use EdgeID for input
val edgeID = EdgeID(nodeAID, nodeBID, "knows")
val edge = graph.addEdge(edgeID)

// Check containment using IDs
if (graph.containEdge(edgeID)) { /* ... */ }

// Get edge using ID
val foundEdge = graph.getEdge(edgeID)

// Structure Queries - all use NodeID for input, return entity objects
val children = graph.getChildren(nodeAID).toList()
val parents = graph.getParents(nodeBID).toList()
val descendants = graph.getDescendants(nodeAID).toList()
val ancestors = graph.getAncestors(nodeBID).toList()
val outgoing = graph.getOutgoingEdges(nodeAID).toList()
val incoming = graph.getIncomingEdges(nodeBID).toList()

// Query all entities
val allNodes = graph.getAllNodes().toList()
val allEdges = graph.getAllEdges().toList()

// Delete entities using IDs
graph.delEdge(edgeID)
graph.delNode(nodeAID)
```

### AbcBasicGraph Class

**Responsibility**: Abstract base class implementing IGraph interface with a caching mechanism. Maintains synchronized caches for node and edge identifiers. Entity objects (N/E) are operation wrappers that do not store data; all data is managed through the storage layer.

**Properties**:
- `storage: IStorage` - storage kernel for managing graph nodes and edges
- `cacheNIDs: MutableSet<NodeID>` - cache for node IDs in the graph storage
- `cacheEIDs: MutableSet<EdgeID>` - cache for edge IDs in the graph storage
- `entitySize: Int` - total number of entities (nodes and edges)

**[newNodeObj(nid: NodeID): N]**
- **Behavior**: Creates a new node entity object of type N using the provided node ID. This entity object is an operation wrapper, not a data container. This is an abstract method to be implemented by subclasses.
- **Input**: `nid: NodeID` - identifier for the new node
- **Output**: `N` - new node entity object instance
- **Throws**: None

**[newEdgeObj(eid: EdgeID): E]**
- **Behavior**: Creates a new edge entity object of type E using the provided edge ID. This entity object is an operation wrapper, not a data container. This is an abstract method to be implemented by subclasses.
- **Input**: `eid: EdgeID` - identifier for the new edge
- **Output**: `E` - new edge entity object instance
- **Throws**: None

**[refreshCache()]**
- **Behavior**: Clears and refreshes the cache for edge and node IDs in the graph storage. Loads all entities from storage into the cache. Graph isolation and filtering should be handled by external layers if needed.
- **Input**: None
- **Output**: Unit
- **Throws**: None

**[clearCache()]**
- **Behavior**: Clears the cache for edge and node IDs in the graph storage. Does not affect the actual storage.
- **Input**: None
- **Output**: Unit
- **Throws**: None

**ID Creation and Management**:
- Users are responsible for creating NodeID and EdgeID objects
- Node and edge IDs are used directly as provided, without any automatic prefixing or formatting
- Graph isolation and namespace management should be handled by external layers (e.g., wrapper classes, application logic, or storage layer filters)

**Entity Object Model**:
- Entity objects (N/E) are operation wrappers, not data containers
- All data is stored in the storage layer (IStorage)
- Entity objects provide a typed interface for graph operations
- Entity objects are lightweight and can be created on-demand

**Example Usage**:
```kotlin
abstract class MyGraph : AbcBasicGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

val graph = object : MyGraph() {}

// Refresh cache from storage
graph.refreshCache()

// Clear cache (does not affect storage)
graph.clearCache()
```

### AbcSimpleGraph Class

**Responsibility**: Abstract implementation of a simple directed graph where there is at most one edge between any two nodes in a given direction (ignoring edge names).

**Properties**: Inherits from `AbcBasicGraph`

**Implementation Notes**:
- Implements `addEdge(withID: EdgeID)` from IGraph interface
- Edge IDs are used directly as provided, without automatic prefixing
- Only one edge is allowed between any two nodes in a given direction (regardless of edge name)
- Edge validation ensures source and destination nodes exist before edge creation

**Example Usage**:
```kotlin
val graph = object : AbcSimpleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

// Create nodes using NodeIDs
val nodeAID = NodeID("userA")
val nodeBID = NodeID("userB")
val nodeA = graph.addNode(nodeAID)
val nodeB = graph.addNode(nodeBID)

// Create edge using EdgeID
val edgeID = EdgeID(nodeAID, nodeBID, "follows")
val edge = graph.addEdge(edgeID)

// Query edge by ID
val foundEdge = graph.getEdge(edgeID)

// Query nodes by ID
val foundNodeA = graph.getNode(nodeAID)
val foundNodeB = graph.getNode(nodeBID)

// Structure queries using NodeIDs
val outgoing = graph.getOutgoingEdges(nodeAID).toList()
val incoming = graph.getIncomingEdges(nodeBID).toList()
```

### AbcMultiGraph Class

**Responsibility**: Abstract class representing a multi-graph, where multiple edges between the same pair of nodes are allowed.

**Properties**: Inherits from `AbcBasicGraph`

**Implementation Notes**:
- Implements `addEdge(withID: EdgeID)` from IGraph interface
- Edge IDs are used directly as provided, without automatic prefixing
- Allows multiple edges with different names between the same pair of nodes
- Edge validation ensures source and destination nodes exist before edge creation

**Example Usage**:
```kotlin
val graph = object : AbcMultiGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

// Create nodes using NodeIDs
val nodeAID = NodeID("userA")
val nodeBID = NodeID("userB")
val nodeA = graph.addNode(nodeAID)
val nodeB = graph.addNode(nodeBID)

// Add multiple edges between same nodes using different EdgeIDs
val edge1ID = EdgeID(nodeAID, nodeBID, "likes")
val edge2ID = EdgeID(nodeAID, nodeBID, "follows")
val edge3ID = EdgeID(nodeAID, nodeBID, "knows")
val edge1 = graph.addEdge(edge1ID)
val edge2 = graph.addEdge(edge2ID)
val edge3 = graph.addEdge(edge3ID)

// Query specific edge by ID
val foundEdge = graph.getEdge(edge1ID)

// Query all edges between two nodes
val allEdgesBetween = graph.getIncomingEdges(nodeBID)
    .filter { it.id.srcNid == nodeAID }
    .toList()  // [edge1, edge2, edge3]

// Query nodes by ID
val foundNodeA = graph.getNode(nodeAID)
val foundNodeB = graph.getNode(nodeBID)

// Structure queries using NodeIDs
val outgoingFromA = graph.getOutgoingEdges(nodeAID).toList()  // [edge1, edge2, edge3]
val incomingToB = graph.getIncomingEdges(nodeBID).toList()  // [edge1, edge2, edge3]
```

---

## Exception Classes

**EntityNotExistException**: Raised when referencing a node that does not exist in the graph during `addEdge` operations.

**EntityAlreadyExistException**: Raised when attempting to add a node or edge that already exists in the graph with the same ID.

---

## Validation Rules

**ID Management**:
- Users are responsible for creating NodeID and EdgeID objects.
- Node and edge IDs are used directly as provided, without any automatic prefixing or formatting.
- Graph isolation and namespace management should be handled by external layers (e.g., wrapper classes, application logic, or storage layer filters).
- All input parameters use strongly-typed IDs (NodeID/EdgeID).
- All output operations return entity objects (N/E) which are operation wrappers.

**Entity Object Model**:
- Entity objects (N/E) are operation wrappers, not data containers.
- All data is stored in the storage layer (IStorage).
- Entity objects provide a typed interface for graph operations.
- Entity objects are lightweight and can be created on-demand.

**Graph Validation**:
- Nodes must exist (verified by NodeID) before edges can reference them.
- In `AbcSimpleGraph`, at most one edge is allowed between any two nodes in a given direction.
- In `AbcMultiGraph`, multiple edges between the same pair of nodes are allowed.

**Cache Validation**:
- `AbcBasicGraph` maintains caches for node and edge IDs that must be synchronized with storage.
- Cache refresh operations load all entities from storage. External layers should handle filtering if graph isolation is required.

**Design Principles**:
- **Single Responsibility**: Graph manages entity operations; users are responsible for ID creation and formatting.
- **Simplicity**: Graph uses IDs directly as provided, without automatic prefixing or formatting.
- **Type Safety**: All input operations use strongly-typed IDs (NodeID/EdgeID) for clarity and safety.
- **Separation of Concerns**: Graph isolation and namespace management are handled by external layers, not by the graph itself.
- **Operation Wrapper Pattern**: Entity objects are lightweight operation wrappers, not data containers.

---

## Design Discussion

### Function Merging Consideration

**Question**: Should `delEdge`/`delNode` and `containEdge`/`containNode` be merged into generic functions?

**Analysis**:

**Option 1: Keep Separate Functions (Current Design)**
```kotlin
fun delNode(whoseID: NodeID)
fun delEdge(whoseID: EdgeID)
fun containNode(whoseID: NodeID): Boolean
fun containEdge(whoseID: EdgeID): Boolean
```

**Pros**:
- **Type Safety**: Compile-time type checking ensures correct ID types are used
- **Clarity**: Method names explicitly indicate what entity type is being operated on
- **IDE Support**: Better autocomplete and refactoring support
- **Error Prevention**: Impossible to accidentally pass a NodeID to `delEdge` or vice versa
- **Consistency**: Matches the pattern of `addNode`/`addEdge`, `getNode`/`getEdge`

**Cons**:
- More methods in the interface
- Some code duplication in implementation

**Option 2: Generic Functions with Sealed Interface**
```kotlin
sealed interface EntityID
data class NodeID(...) : EntityID
data class EdgeID(...) : EntityID

fun del(id: EntityID)
fun contain(id: EntityID): Boolean
```

**Pros**:
- Fewer methods in the interface
- Single implementation for deletion/containment logic

**Cons**:
- **Loss of Type Safety**: Runtime type checking required, compile-time errors not caught
- **Less Clear API**: Method names don't indicate entity type
- **Runtime Errors**: Wrong ID type only discovered at runtime
- **Inconsistent Pattern**: Breaks consistency with `addNode`/`addEdge` and `getNode`/`getEdge`
- **Weaker IDE Support**: Autocomplete less helpful

**Recommendation**: **Keep separate functions (Option 1)**

The current design with separate `delNode`/`delEdge` and `containNode`/`containEdge` functions is superior because:
1. **Type Safety**: Prevents errors at compile time rather than runtime
2. **Consistency**: Matches the established pattern of separate node/edge operations
3. **Clarity**: Method names clearly communicate intent
4. **Maintainability**: Easier to understand and maintain

The slight increase in interface size is a worthwhile trade-off for the significant benefits in type safety and code clarity.
