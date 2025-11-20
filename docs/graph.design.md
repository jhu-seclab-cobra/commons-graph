# Graph Module Design

## Design Overview

**Classes**: `AbcBasicGraph`, `AbcSimpleGraph`, `AbcMultiGraph`

**Relationships**: `AbcBasicGraph` implements `IGraph`, `AbcSimpleGraph` extends `AbcBasicGraph`, `AbcMultiGraph` extends `AbcBasicGraph`, `AbcBasicGraph` contains `IStorage`

**Interfaces**: `IGraph` (implemented by `AbcBasicGraph`)

**Abstract**: `AbcBasicGraph` (extended by `AbcSimpleGraph`, `AbcMultiGraph`)

**Exceptions**: `EntityNotExistException` (thrown by `addEdge`, `wrapNode`), `EntityAlreadyExistException` (thrown by `addNode`, `addEdge`)

---

## Class Specifications

### IGraph Interface

**Responsibility**: Interface representing a generic graph structure where nodes and edges are modeled by types N and E. Provides operations to manipulate and query the graph.

**Properties**:
- `graphName: String` - name of the graph
- `entitySize: Int` - total number of entities (nodes and edges) in the graph

**[containNode(node: AbcNode): Boolean]**
- **Behavior**: Checks if the specified node is contained in the graph.
- **Input**: `node: AbcNode` - node to check
- **Output**: `Boolean` - true if node is contained
- **Throws**: None

**[containEdge(edge: AbcEdge): Boolean]**
- **Behavior**: Checks if the graph contains the specified edge.
- **Input**: `edge: AbcEdge` - edge to check
- **Output**: `Boolean` - true if edge is present
- **Throws**: None

**[addNode(whoseID: NodeID): N]**
- **Behavior**: Adds a new node to the graph with the specified identifier.
- **Input**: `whoseID: NodeID` - identifier of the node to be added
- **Output**: `N` - newly added node
- **Throws**: `EntityAlreadyExistException` if a node with the specified identifier already exists

**[addEdge(from: AbcNode, to: AbcNode, type: String): E]**
- **Behavior**: Adds a directed edge between two nodes in the graph.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node, `type: String` - edge type
- **Output**: `E` - newly added edge
- **Throws**: `EntityNotExistException` if source or destination node does not exist, `EntityAlreadyExistException` if an edge of the specified type between the nodes already exists

**[wrapNode(node: AbcNode): N]**
- **Behavior**: Wraps a generic AbcNode into its specific graph-context type N.
- **Input**: `node: AbcNode` - generic node to be wrapped
- **Output**: `N` - node converted to specific type
- **Throws**: `EntityNotExistException` if the node does not exist in the graph

**[getNode(whoseID: NodeID): N?]**
- **Behavior**: Retrieves a node from the graph based on its identifier.
- **Input**: `whoseID: NodeID` - identifier of the node to retrieve
- **Output**: `N?` - node if it exists, null otherwise
- **Throws**: None

**[getEdge(whoseID: EdgeID): E?]**
- **Behavior**: Retrieves an edge from the graph based on its identifier.
- **Input**: `whoseID: EdgeID` - identifier of the edge to retrieve
- **Output**: `E?` - edge if it exists, null otherwise
- **Throws**: None

**[getEdge(from: AbcNode, to: AbcNode, type: String): E?]**
- **Behavior**: Retrieves an edge between two nodes with the specified type.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node, `type: String` - edge type
- **Output**: `E?` - edge if it exists, null otherwise
- **Throws**: None

**[getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves all nodes in the graph that satisfy the given predicate.
- **Input**: `doSatisfy: (N) -> Boolean` - predicate to filter nodes
- **Output**: `Sequence<N>` - sequence of nodes that satisfy the predicate
- **Throws**: None

**[getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>]**
- **Behavior**: Retrieves all edges in the graph that satisfy the given predicate.
- **Input**: `doSatisfy: (E) -> Boolean` - predicate to filter edges
- **Output**: `Sequence<E>` - sequence of edges that satisfy the predicate
- **Throws**: None

**[delNode(node: N)]**
- **Behavior**: Deletes a node and all its associated edges from the graph.
- **Input**: `node: N` - node to be deleted
- **Output**: Unit
- **Throws**: None

**[delEdge(edge: E)]**
- **Behavior**: Deletes an edge from the graph.
- **Input**: `edge: E` - edge to be deleted
- **Output**: Unit
- **Throws**: None

**[getIncomingEdges(of: AbcNode): Sequence<E>]**
- **Behavior**: Retrieves all incoming edges to the specified node.
- **Input**: `of: AbcNode` - node whose incoming edges are to be retrieved
- **Output**: `Sequence<E>` - sequence of incoming edges
- **Throws**: None

**[getOutgoingEdges(of: AbcNode): Sequence<E>]**
- **Behavior**: Retrieves all outgoing edges from the specified node.
- **Input**: `of: AbcNode` - node whose outgoing edges are to be retrieved
- **Output**: `Sequence<E>` - sequence of outgoing edges
- **Throws**: None

**[getChildren(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves all child nodes of the specified node that are connected by edges satisfying the given predicate.
- **Input**: `of: AbcNode` - node whose children are to be retrieved, `edgeCond: (E) -> Boolean` - predicate to filter connecting edges
- **Output**: `Sequence<N>` - sequence of child nodes
- **Throws**: None

**[getParents(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves all parent nodes of the specified node that are connected by edges satisfying the given predicate.
- **Input**: `of: AbcNode` - node whose parents are to be retrieved, `edgeCond: (E) -> Boolean` - predicate to filter connecting edges
- **Output**: `Sequence<N>` - sequence of parent nodes
- **Throws**: None

**[getDescendants(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves all descendant nodes of the specified node that are connected by edges satisfying the given predicate. Uses breadth-first search.
- **Input**: `of: AbcNode` - node whose descendants are to be retrieved, `edgeCond: (E) -> Boolean` - predicate to filter connecting edges
- **Output**: `Sequence<N>` - sequence of descendant nodes
- **Throws**: None

**[getAncestors(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves all ancestor nodes of the specified node that are connected by edges satisfying the given predicate. Uses depth-first search.
- **Input**: `of: AbcNode` - node whose ancestors are to be retrieved, `edgeCond: (E) -> Boolean` - predicate to filter connecting edges
- **Output**: `Sequence<N>` - sequence of ancestor nodes
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

// Add nodes and edges
val nodeA = graph.addNode(NodeID("A"))
val nodeB = graph.addNode(NodeID("B"))
val edge = graph.addEdge(nodeA, nodeB, "connects")

// Query graph
val allNodes = graph.getAllNodes().toList()
val allEdges = graph.getAllEdges().toList()
val children = graph.getChildren(nodeA).toList()
val parents = graph.getParents(nodeB).toList()
val descendants = graph.getDescendants(nodeA).toList()
val ancestors = graph.getAncestors(nodeB).toList()

// Check containment
if (graph.containNode(nodeA)) { /* ... */ }
if (graph.containEdge(edge)) { /* ... */ }

// Delete entities
graph.delEdge(edge)
graph.delNode(nodeA)
```

### AbcBasicGraph Class

**Responsibility**: Abstract base class implementing IGraph interface with a caching mechanism. Maintains synchronized caches for node and edge identifiers.

**Properties**:
- `storage: IStorage` - storage kernel for managing graph nodes and edges
- `cacheNIDs: MutableSet<NodeID>` - cache for node IDs in the graph storage
- `cacheEIDs: MutableSet<EdgeID>` - cache for edge IDs in the graph storage
- `entitySize: Int` - total number of entities (nodes and edges)
- `graphName: String` - name of the graph

**[newNodeObj(nid: NodeID): N]**
- **Behavior**: Creates a new node object of type N using the provided node ID.
- **Input**: `nid: NodeID` - identifier for the new node
- **Output**: `N` - new instance of type N
- **Throws**: None

**[newEdgeObj(eid: EdgeID): E]**
- **Behavior**: Creates a new edge object of type E using the provided edge ID.
- **Input**: `eid: EdgeID` - identifier for the new edge
- **Output**: `E` - new instance of type E
- **Throws**: None

**[refreshCache()]**
- **Behavior**: Clears and refreshes the cache for edge and node IDs in the graph storage. Only loads edges whose type starts with the graph name.
- **Input**: None
- **Output**: Unit
- **Throws**: None

**[clearCache()]**
- **Behavior**: Clears the cache for edge and node IDs in the graph storage. Does not affect the actual storage.
- **Input**: None
- **Output**: Unit
- **Throws**: None

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

**Responsibility**: Abstract implementation of a simple directed graph where there is at most one edge between any two nodes in a given direction (ignoring edge types).

**Properties**: Inherits from `AbcBasicGraph`

**[addEdge(from: AbcNode, to: AbcNode, type: String): E]**
- **Behavior**: Adds a directed edge between two nodes with the specified type. The edge type is automatically prefixed with the graph name. Only one edge is allowed between any two nodes in a given direction.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node, `type: String` - edge type
- **Output**: `E` - newly created edge
- **Throws**: `EntityAlreadyExistException` if an edge already exists between the specified nodes

**[addEdge(from: AbcNode, to: AbcNode): E]**
- **Behavior**: Adds a directed edge between two nodes without specifying an edge type. This is a shorthand for calling addEdge with an empty type string.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node
- **Output**: `E` - newly created edge
- **Throws**: `EntityAlreadyExistException` if an edge already exists between the specified nodes

**[getEdge(from: AbcNode, to: AbcNode, type: String): E?]**
- **Behavior**: Retrieves an edge between two nodes with the specified type. The edge type must include the graph name prefix.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node, `type: String` - edge type
- **Output**: `E?` - edge if it exists, null otherwise
- **Throws**: None

**[getEdge(from: AbcNode, to: AbcNode): E?]**
- **Behavior**: Retrieves a directed edge between two nodes without considering the edge type. Returns null if no edge exists or multiple edges exist.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node
- **Output**: `E?` - edge if exactly one exists, null otherwise
- **Throws**: None

**Example Usage**:
```kotlin
val graph = object : AbcSimpleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

val nodeA = graph.addNode(NodeID("A"))
val nodeB = graph.addNode(NodeID("B"))

// Add edge with type
val edge1 = graph.addEdge(nodeA, nodeB, "relation")

// Add edge without type (empty type)
val edge2 = graph.addEdge(nodeA, nodeB)

// Get edge with type
val foundEdge = graph.getEdge(nodeA, nodeB, "relation")

// Get edge without type (returns single edge if exists)
val singleEdge = graph.getEdge(nodeA, nodeB)
```

### AbcMultiGraph Class

**Responsibility**: Abstract class representing a multi-graph, where multiple edges between the same pair of nodes are allowed.

**Properties**: Inherits from `AbcBasicGraph`

**[addEdge(from: AbcNode, to: AbcNode, type: String): E]**
- **Behavior**: Adds a directed edge between two nodes with the specified type. The edge type is automatically prefixed with the graph name. Non-existent nodes are automatically wrapped.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node, `type: String` - edge type
- **Output**: `E` - newly created edge
- **Throws**: `EntityAlreadyExistException` if an edge with the same ID already exists

**[addEdge(from: AbcNode, to: AbcNode): E]**
- **Behavior**: Adds a directed edge between two nodes with a randomly generated UUID as the edge type. The UUID is automatically prefixed with the graph name.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node
- **Output**: `E` - newly created edge
- **Throws**: `EntityAlreadyExistException` if an edge with the same ID already exists

**[getEdge(from: AbcNode, to: AbcNode, type: String): E?]**
- **Behavior**: Retrieves an edge between two nodes with the specified type. The edge type must include the graph name prefix.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node, `type: String` - edge type
- **Output**: `E?` - edge if it exists, null otherwise
- **Throws**: None

**[getEdges(from: AbcNode, to: AbcNode): Sequence<E>]**
- **Behavior**: Retrieves all edges between two nodes, regardless of their types.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node
- **Output**: `Sequence<E>` - sequence of edges between the specified nodes
- **Throws**: None

**Example Usage**:
```kotlin
val graph = object : AbcMultiGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

val nodeA = graph.addNode(NodeID("A"))
val nodeB = graph.addNode(NodeID("B"))

// Add multiple edges between same nodes
val edge1 = graph.addEdge(nodeA, nodeB, "relation1")
val edge2 = graph.addEdge(nodeA, nodeB, "relation2")
val edge3 = graph.addEdge(nodeA, nodeB)  // UUID-based type

// Get all edges between two nodes
val allEdges = graph.getEdges(nodeA, nodeB).toList()  // [edge1, edge2, edge3]

// Get specific edge by type
val specificEdge = graph.getEdge(nodeA, nodeB, "relation1")
```

---

## Exception Classes

**EntityNotExistException**: Raised when a node does not exist in the graph during `addEdge` or `wrapNode` operations.

**EntityAlreadyExistException**: Raised when attempting to add a node or edge that already exists in the graph.

---

## Validation Rules

**Graph Validation**:
- Nodes must exist in storage before edges can reference them.
- In `AbcSimpleGraph`, at most one edge is allowed between any two nodes in a given direction.
- In `AbcMultiGraph`, multiple edges between the same pair of nodes are allowed.

**Cache Validation**:
- `AbcBasicGraph` maintains caches for node and edge IDs that must be synchronized with storage.
- Cache refresh operations only load edges whose type starts with the graph name.

**Graph Type Validation**:
- In `AbcSimpleGraph`, edge types are automatically prefixed with the graph name (format: "GraphName:type").
- In `AbcMultiGraph`, edge types are automatically prefixed with the graph name (format: "GraphName:type").
- When adding edges without specifying a type in `AbcMultiGraph`, a UUID is automatically generated and prefixed with the graph name.
