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

**Responsibility**: Interface representing a generic graph structure where nodes and edges are modeled by types N and E. The graph owns all entity ID creation/formatting, while callers provide raw strings for creation and then operate on the strongly-typed node/edge objects returned (IDs are only required for explicit lookups).

**Properties**:
- `graphName: String` - name of the graph
- `nodeIDs: Set<NodeID>` - cached view of all node identifiers currently stored
- `edgeIDs: Set<EdgeID>` - cached view of all edge identifiers currently stored

**[addNode(name: String): N]**
- **Behavior**: Creates and adds a new node to the graph with the specified name. The graph constructs the underlying `NodeID`, including any graph-level prefixing or formatting.
- **Input**: `name: String` - human-readable name for the new node (without graph prefix)
- **Output**: `N` - newly created node whose `id` is managed by the graph
- **Throws**: `EntityAlreadyExistException` if a node with the specified name already exists in this graph

**[wrapNode(node: AbcNode): N]**
- **Behavior**: Wraps a generic `AbcNode` instance (for example retrieved from storage) into the concrete node type used by this graph.
- **Input**: `node: AbcNode` - the generic node to wrap
- **Output**: `N` - node instance bound to this graph
- **Throws**: `EntityNotExistException` if the node does not belong to the graph

**[getNode(id: NodeID): N?]**
- **Behavior**: Retrieves a node from the graph using its identifier. The ID should be obtained from a previously created node.
- **Input**: `id: NodeID` - identifier of the node to retrieve (from node.id)
- **Output**: `N?` - node if it exists, null otherwise
- **Throws**: None

**[containNode(node: AbcNode): Boolean]**
- **Behavior**: Checks whether the provided node instance is currently contained in the graph.
- **Input**: `node: AbcNode` - node instance to check
- **Output**: `Boolean` - true if contained
- **Throws**: None

**[delNode(node: N)]**
- **Behavior**: Deletes a node and all associated edges from the graph.
- **Input**: `node: N` - node instance (created by this graph) to delete
- **Output**: Unit
- **Throws**: None

**[addEdge(from: AbcNode, to: AbcNode, name: String): E]**
- **Behavior**: Creates and adds a directed edge between two graph nodes. The graph constructs the underlying `EdgeID`, including graph-level name prefixing and isolation rules.
- **Input**: `from: AbcNode` - source node instance, `to: AbcNode` - destination node instance, `name: String` - edge name (without graph prefix)
- **Output**: `E` - newly created edge with graph-managed ID
- **Throws**: `EntityNotExistException` if either node is not part of the graph, `EntityAlreadyExistException` if an equivalent edge already exists

**[getEdge(id: EdgeID): E?]**
- **Behavior**: Retrieves an edge from the graph using its identifier. The ID should be obtained from a previously created edge.
- **Input**: `id: EdgeID` - identifier of the edge to retrieve (from edge.id)
- **Output**: `E?` - edge if it exists, null otherwise
- **Throws**: None

**[containEdge(edge: AbcEdge): Boolean]**
- **Behavior**: Checks whether the provided edge instance is contained in the graph.
- **Input**: `edge: AbcEdge` - edge instance to check
- **Output**: `Boolean` - true if contained
- **Throws**: None

**[delEdge(edge: E)]**
- **Behavior**: Deletes the specified edge from the graph.
- **Input**: `edge: E` - edge instance (created by this graph) to delete
- **Output**: Unit
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

**[getIncomingEdges(of: AbcNode): Sequence<E>]**
- **Behavior**: Retrieves all incoming edges to the specified node instance.
- **Input**: `of: AbcNode` - node whose incoming edges should be returned
- **Output**: `Sequence<E>` - incoming edges
- **Throws**: None

**[getOutgoingEdges(of: AbcNode): Sequence<E>]**
- **Behavior**: Retrieves all outgoing edges from the specified node instance.
- **Input**: `of: AbcNode`
- **Output**: `Sequence<E>` - outgoing edges
- **Throws**: None

**[getChildren(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves child nodes reachable from the specified node by edges satisfying the predicate.
- **Input**: `of: AbcNode` - starting node, `edgeCond` - predicate applied to connecting edges
- **Output**: `Sequence<N>` - child nodes
- **Throws**: None

**[getParents(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Retrieves parent nodes that connect to the specified node via edges satisfying the predicate.
- **Input**: `of: AbcNode`, `edgeCond` - edge predicate
- **Output**: `Sequence<N>` - parent nodes
- **Throws**: None

**[getDescendants(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Computes descendants of the specified node via breadth-first traversal subject to an edge predicate.
- **Input**: `of: AbcNode`, `edgeCond` - edge predicate
- **Output**: `Sequence<N>` - descendant nodes
- **Throws**: None

**[getAncestors(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>]**
- **Behavior**: Computes ancestors of the specified node via depth-first traversal subject to an edge predicate.
- **Input**: `of: AbcNode`, `edgeCond` - edge predicate
- **Output**: `Sequence<N>` - ancestor nodes
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

// Create nodes - provide names, graph creates NodeIDs internally
val nodeA = graph.addNode("userA")
val nodeB = graph.addNode("userB")

// Create edge - supply node instances
val edge = graph.addEdge(nodeA, nodeB, "knows")

// Query by ID - use IDs from created entities
val foundNode = graph.getNode(nodeA.id)
val foundEdge = graph.getEdge(edge.id)

// Relationship queries operate on node instances
val children = graph.getChildren(nodeA).toList()
val parents = graph.getParents(nodeB).toList()
val descendants = graph.getDescendants(nodeA).toList()
val ancestors = graph.getAncestors(nodeB).toList()
val outgoing = graph.getOutgoingEdges(nodeA).toList()
val incoming = graph.getIncomingEdges(nodeB).toList()

// Containment checks use node/edge instances
if (graph.containNode(nodeA)) { /* ... */ }
if (graph.containEdge(edge)) { /* ... */ }

// Query all entities
val allNodes = graph.getAllNodes().toList()
val allEdges = graph.getAllEdges().toList()

// Delete entities - use graph-created instances
graph.delEdge(edge)
graph.delNode(nodeA)
```

### AbcBasicGraph Class

**Responsibility**: Abstract base class implementing IGraph interface with a caching mechanism. Maintains synchronized caches for node and edge identifiers. Manages all entity ID creation and formatting, ensuring graph isolation through automatic name prefixing.

**Properties**:
- `storage: IStorage` - storage kernel for managing graph nodes and edges
- `cacheNIDs: MutableSet<NodeID>` - cache for node IDs in the graph storage
- `cacheEIDs: MutableSet<EdgeID>` - cache for edge IDs in the graph storage
- `entitySize: Int` - total number of entities (nodes and edges)
- `graphName: String` - name of the graph (derived from class name)

**[newNodeObj(nid: NodeID): N]**
- **Behavior**: Creates a new node object of type N using the provided node ID. This is an abstract method to be implemented by subclasses.
- **Input**: `nid: NodeID` - identifier for the new node
- **Output**: `N` - new instance of type N
- **Throws**: None

**[newEdgeObj(eid: EdgeID): E]**
- **Behavior**: Creates a new edge object of type E using the provided edge ID. This is an abstract method to be implemented by subclasses.
- **Input**: `eid: EdgeID` - identifier for the new edge
- **Output**: `E` - new instance of type E
- **Throws**: None

**[refreshCache()]**
- **Behavior**: Clears and refreshes the cache for edge and node IDs in the graph storage. Only loads entities whose IDs belong to this graph (matched by graph name prefix).
- **Input**: None
- **Output**: Unit
- **Throws**: None

**[clearCache()]**
- **Behavior**: Clears the cache for edge and node IDs in the graph storage. Does not affect the actual storage.
- **Input**: None
- **Output**: Unit
- **Throws**: None

**ID Creation and Management**:
- Graph is responsible for all NodeID and EdgeID creation
- Node names are automatically prefixed with `graphName:` for isolation
- Edge names are automatically prefixed with `graphName:` for isolation
- Users provide raw strings; graph returns entities with properly formatted IDs

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
- Implements `addEdge(from: AbcNode, to: AbcNode, name: String)` from IGraph interface
- Edge names are automatically prefixed with graph name for isolation (format: "GraphName:name")
- Only one edge is allowed between any two nodes in a given direction (regardless of edge name)
- Provides convenient edge query by node pair since at most one edge exists

**Additional Methods**:

**[addEdge(from: AbcNode, to: AbcNode): E]**
- **Behavior**: Adds a directed edge between two nodes without specifying an edge name. An empty name string is used. Only one edge is allowed between any two nodes in a given direction.
- **Input**: `from: AbcNode` - source node instance, `to: AbcNode` - destination node instance
- **Output**: `E` - newly created edge
- **Throws**: `EntityAlreadyExistException` if an edge already exists between the specified nodes

**[getEdge(from: AbcNode, to: AbcNode): E?]**
- **Behavior**: Retrieves the edge between two nodes. Since only one edge is allowed between any two nodes in a given direction, this uniquely identifies the edge.
- **Input**: `from: AbcNode` - source node instance, `to: AbcNode` - destination node instance
- **Output**: `E?` - the edge if it exists, null otherwise
- **Throws**: None

**Example Usage**:
```kotlin
val graph = object : AbcSimpleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

// Create nodes - provide names, graph creates NodeIDs
val nodeA = graph.addNode("userA")
val nodeB = graph.addNode("userB")

// Add edge with name using node instances
val edge = graph.addEdge(nodeA, nodeB, "follows")

// Query edge by ID obtained from created edge
val foundByID = graph.getEdge(edge.id)

// Query edge by node pair (SimpleGraph allows at most one edge)
val foundByNodes = graph.getEdge(nodeA, nodeB)

// Query nodes by ID
val foundNodeA = graph.getNode(nodeA.id)
val foundNodeB = graph.getNode(nodeB.id)

// Query relationships
val outgoing = graph.getOutgoingEdges(nodeA).toList()
val incoming = graph.getIncomingEdges(nodeB).toList()
```

### AbcMultiGraph Class

**Responsibility**: Abstract class representing a multi-graph, where multiple edges between the same pair of nodes are allowed.

**Properties**: Inherits from `AbcBasicGraph`

**Implementation Notes**:
- Implements `addEdge(from: AbcNode, to: AbcNode, name: String)` from IGraph interface
- Edge names are automatically prefixed with graph name for isolation (format: "GraphName:name")
- Allows multiple edges with different names between the same pair of nodes
- Provides convenient bulk edge query by node pair

**Additional Methods**:

**[addEdge(from: AbcNode, to: AbcNode): E]**
- **Behavior**: Adds a directed edge between two nodes with a randomly generated UUID as the edge name.
- **Input**: `from: AbcNode` - source node instance, `to: AbcNode` - destination node instance
- **Output**: `E` - newly created edge with UUID-based name
- **Throws**: `EntityAlreadyExistException` if an edge with the same ID already exists

**[getEdges(from: AbcNode, to: AbcNode): Sequence<E>]**
- **Behavior**: Retrieves all edges between two nodes, regardless of their names. Since multiple edges are allowed, this returns a sequence of all matching edges.
- **Input**: `from: AbcNode` - source node instance, `to: AbcNode` - destination node instance
- **Output**: `Sequence<E>` - sequence of all edges from source to destination (may be empty)
- **Throws**: None

**Example Usage**:
```kotlin
val graph = object : AbcMultiGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

// Create nodes - provide names, graph creates NodeIDs
val nodeA = graph.addNode("userA")
val nodeB = graph.addNode("userB")

// Add multiple edges between same nodes using node instances
val edge1 = graph.addEdge(nodeA, nodeB, "likes")
val edge2 = graph.addEdge(nodeA, nodeB, "follows")
val edge3 = graph.addEdge(nodeA, nodeB)  // UUID-based name

// Query specific edge by ID obtained from created edge
val foundEdge = graph.getEdge(edge1.id)

// Query all edges between two nodes (MultiGraph allows multiple edges)
val allEdgesBetween = graph.getEdges(nodeA, nodeB).toList()  // [edge1, edge2, edge3]

// Query nodes by ID
val foundNodeA = graph.getNode(nodeA.id)
val foundNodeB = graph.getNode(nodeB.id)

// Query edges using relationship methods
val outgoingFromA = graph.getOutgoingEdges(nodeA).toList()  // [edge1, edge2, edge3]
val incomingToB = graph.getIncomingEdges(nodeB).toList()  // [edge1, edge2, edge3]
```

---

## Exception Classes

**EntityNotExistException**: Raised when referencing a node that does not exist in the graph during `addEdge` operations.

**EntityAlreadyExistException**: Raised when attempting to add a node or edge that already exists in the graph with the same name/specification.

---

## Validation Rules

**ID Management**:
- All NodeID and EdgeID creation is managed exclusively by the Graph.
- Node names are automatically prefixed with graph name for isolation (format: "GraphName:name").
- Edge names are automatically prefixed with graph name for isolation (format: "GraphName:name").
- Users provide raw strings for creation; the graph returns fully constructed node/edge instances.
- Use IDs only for direct `getNode(id)` / `getEdge(id)` lookups; relationship and traversal APIs operate on node/edge instances produced by the graph.

**Graph Validation**:
- Nodes must exist (verified by NodeID) before edges can reference them.
- In `AbcSimpleGraph`, at most one edge is allowed between any two nodes in a given direction.
- In `AbcMultiGraph`, multiple edges between the same pair of nodes are allowed.

**Cache Validation**:
- `AbcBasicGraph` maintains caches for node and edge IDs that must be synchronized with storage.
- Cache refresh operations only load entities whose IDs belong to this graph (matched by graph name prefix).

**Design Principles**:
- **Single Responsibility**: Graph is the sole creator and manager of all entity IDs.
- **Information Hiding**: ID formatting (including graphName prefix) is completely internal.
- **Type Safety**: Creation uses primitive types (String); queries use strong types (NodeID/EdgeID).
- **Consistency**: All ID creation follows the same pattern with proper isolation guarantees.
