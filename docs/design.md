# commons-graph Design

## Design Overview

**Classes**: `AbcBasicEntity`, `AbcNode`, `AbcEdge`, `AbcBasicGraph`, `AbcSimpleGraph`, `AbcMultiGraph`, `NodeID`, `EdgeID`, `NativeStorageImpl`, `NativeConcurStorageImpl`, `NativeCsvExchangeImpl`

**Relationships**: `AbcBasicEntity` extends `IEntity`, `AbcNode` extends `AbcBasicEntity`, `AbcEdge` extends `AbcBasicEntity`, `AbcBasicGraph` implements `IGraph`, `AbcSimpleGraph` extends `AbcBasicGraph`, `AbcMultiGraph` extends `AbcBasicGraph`, `AbcNode` uses `IStorage`, `AbcEdge` uses `IStorage`, `AbcBasicGraph` contains `IStorage`, `NodeID` implements `IEntity.ID`, `EdgeID` implements `IEntity.ID`, `NativeStorageImpl` implements `IStorage`, `NativeConcurStorageImpl` implements `IStorage`, `NativeCsvExchangeImpl` implements `IStorageExchange`

**Interfaces**: `IEntity` (implemented by `AbcBasicEntity`), `IGraph` (implemented by `AbcBasicGraph`), `IStorage` (implemented by `NativeStorageImpl`, `NativeConcurStorageImpl`), `IStorageExchange` (implemented by `NativeCsvExchangeImpl`), `IStorageImporter`, `IStorageExporter`

**Abstract**: `AbcBasicEntity` (extended by `AbcNode`, `AbcEdge`), `AbcBasicGraph` (extended by `AbcSimpleGraph`, `AbcMultiGraph`)

**Exceptions**: `EntityNotExistException`, `EntityAlreadyExistException`, `InvalidPropNameException`, `AccessClosedStorageException`

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

### IEntity.ID Interface

**Responsibility**: Uniquely identifies an entity within the graph.

**Properties**:
- `serialize: IValue` - serialized value representing this identifier
- `name: String` - human-readable name or label for this identifier

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

### AbcEdge Class

**Responsibility**: Abstract base class for graph edges with storage-backed property management. Provides property access, identity management, and storage integration for edges.

**Properties**:
- `storage: IStorage` - storage system for edge properties
- `id: EdgeID` - unique edge identifier
- `type: Type` - edge type information
- `srcNid: NodeID` - source node identifier
- `dstNid: NodeID` - destination node identifier
- `eType: String` - edge type name

### NodeID Data Class

**Responsibility**: Unique identifier for a node in the graph.

**Properties**:
- `name: String` - node identifier string
- `serialize: StrVal` - serialized node identifier as StrVal

### EdgeID Data Class

**Responsibility**: Unique identifier for an edge in the graph.

**Properties**:
- `srcNid: NodeID` - source node identifier
- `dstNid: NodeID` - destination node identifier
- `eType: String` - edge type name
- `name: String` - formatted edge identifier string
- `serialize: ListVal` - serialized edge identifier as ListVal

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

### AbcSimpleGraph Class

**Responsibility**: Abstract implementation of a simple directed graph where there is at most one edge between any two nodes in a given direction (ignoring edge types).

**Properties**: Inherits from `AbcBasicGraph`

**[addEdge(from: AbcNode, to: AbcNode): E]**
- **Behavior**: Adds a directed edge between two nodes without specifying an edge type. This is a shorthand for calling addEdge with an empty type string.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node
- **Output**: `E` - newly created edge
- **Throws**: `EntityAlreadyExistException` if an edge already exists between the specified nodes

**[getEdge(from: AbcNode, to: AbcNode): E?]**
- **Behavior**: Retrieves a directed edge between two nodes without considering the edge type. Returns null if no edge exists or multiple edges exist.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node
- **Output**: `E?` - edge if exactly one exists, null otherwise
- **Throws**: None

### AbcMultiGraph Class

**Responsibility**: Abstract class representing a multi-graph, where multiple edges between the same pair of nodes are allowed.

**Properties**: Inherits from `AbcBasicGraph`

**[addEdge(from: AbcNode, to: AbcNode): E]**
- **Behavior**: Adds a directed edge between two nodes with a randomly generated UUID as the edge type.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node
- **Output**: `E` - newly created edge
- **Throws**: `EntityAlreadyExistException` if an edge with the same ID already exists

**[getEdges(from: AbcNode, to: AbcNode): Sequence<E>]**
- **Behavior**: Retrieves all edges between two nodes, regardless of their types.
- **Input**: `from: AbcNode` - source node, `to: AbcNode` - destination node
- **Output**: `Sequence<E>` - sequence of edges between the specified nodes
- **Throws**: None

### IStorage Interface

**Responsibility**: Core interface for managing storage of nodes and edges. Provides essential operations for graph data management with clear separation of concerns.

**Properties**:
- `nodeIDs: Set<NodeID>` - all node IDs currently in storage
- `edgeIDs: Set<EdgeID>` - all edge IDs currently in storage

**[containsNode(id: NodeID): Boolean]**
- **Behavior**: Checks if a node exists in storage.
- **Input**: `id: NodeID` - ID of the node
- **Output**: `Boolean` - true if node exists
- **Throws**: `AccessClosedStorageException` if storage is closed

**[addNode(id: NodeID, properties: Map<String, IValue> = emptyMap())]**
- **Behavior**: Adds a node with the given ID and properties.
- **Input**: `id: NodeID` - ID of the node to add, `properties: Map<String, IValue>` - map of property names to values
- **Output**: Unit
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityAlreadyExistException` if node already exists

**[getNodeProperties(id: NodeID): Map<String, IValue>]**
- **Behavior**: Returns all properties associated with a specific node.
- **Input**: `id: NodeID` - ID of the node
- **Output**: `Map<String, IValue>` - map containing all node properties
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityNotExistException` if node does not exist

**[setNodeProperties(id: NodeID, properties: Map<String, IValue?>)]**
- **Behavior**: Updates properties of a specific node. Properties with null values will be deleted.
- **Input**: `id: NodeID` - ID of the node to update, `properties: Map<String, IValue?>` - map of property names to values
- **Output**: Unit
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityNotExistException` if node does not exist

**[deleteNode(id: NodeID)]**
- **Behavior**: Deletes a node from storage.
- **Input**: `id: NodeID` - ID of the node to delete
- **Output**: Unit
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityNotExistException` if node does not exist

**[containsEdge(id: EdgeID): Boolean]**
- **Behavior**: Checks if an edge exists in storage.
- **Input**: `id: EdgeID` - ID of the edge
- **Output**: `Boolean` - true if edge exists
- **Throws**: `AccessClosedStorageException` if storage is closed

**[addEdge(id: EdgeID, properties: Map<String, IValue> = emptyMap())]**
- **Behavior**: Adds an edge with the given ID and properties.
- **Input**: `id: EdgeID` - ID of the edge to add, `properties: Map<String, IValue>` - map of property names to values
- **Output**: Unit
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityAlreadyExistException` if edge already exists

**[getEdgeProperties(id: EdgeID): Map<String, IValue>]**
- **Behavior**: Returns all properties associated with a specific edge.
- **Input**: `id: EdgeID` - ID of the edge
- **Output**: `Map<String, IValue>` - map containing all edge properties
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityNotExistException` if edge does not exist

**[setEdgeProperties(id: EdgeID, properties: Map<String, IValue?>)]**
- **Behavior**: Updates properties of a specific edge. Properties with null values will be deleted.
- **Input**: `id: EdgeID` - ID of the edge to update, `properties: Map<String, IValue?>` - map of property names to values
- **Output**: Unit
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityNotExistException` if edge does not exist

**[deleteEdge(id: EdgeID)]**
- **Behavior**: Deletes an edge from storage.
- **Input**: `id: EdgeID` - ID of the edge to delete
- **Output**: Unit
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityNotExistException` if edge does not exist

**[getIncomingEdges(id: NodeID): Set<EdgeID>]**
- **Behavior**: Returns all incoming edges to a specific node.
- **Input**: `id: NodeID` - ID of the node
- **Output**: `Set<EdgeID>` - set of edge IDs representing the incoming edges
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityNotExistException` if node does not exist

**[getOutgoingEdges(id: NodeID): Set<EdgeID>]**
- **Behavior**: Returns all outgoing edges from a specific node.
- **Input**: `id: NodeID` - ID of the node
- **Output**: `Set<EdgeID>` - set of edge IDs representing the outgoing edges
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityNotExistException` if node does not exist

**[getMeta(name: String): IValue?]**
- **Behavior**: Returns a metadata value by name.
- **Input**: `name: String` - name of the metadata property
- **Output**: `IValue?` - metadata value, or null if not found
- **Throws**: `AccessClosedStorageException` if storage is closed

**[setMeta(name: String, value: IValue?)]**
- **Behavior**: Sets a metadata value by name. Passing null as the value will delete the metadata property.
- **Input**: `name: String` - name of the metadata property, `value: IValue?` - metadata value, or null to delete
- **Output**: Unit
- **Throws**: `AccessClosedStorageException` if storage is closed

**[clear(): Boolean]**
- **Behavior**: Clears all nodes and edges from storage.
- **Input**: None
- **Output**: `Boolean` - true if operation was successful
- **Throws**: `AccessClosedStorageException` if storage is closed

### NativeStorageImpl Class

**Responsibility**: In-memory graph storage implementation using HashMap-based data structures. Provides efficient storage with O(1) average time complexity. Not thread-safe.

**Properties**: Implements `IStorage`

### NativeConcurStorageImpl Class

**Responsibility**: Thread-safe in-memory graph storage implementation using concurrent data structures.

**Properties**: Implements `IStorage`

### IStorageExchange Interface

**Responsibility**: Provides methods for importing and exporting graph data between different storage implementations.

**[isValidFile(file: Path): Boolean]**
- **Behavior**: Checks if a given file is valid.
- **Input**: `file: Path` - path to the file to be checked
- **Output**: `Boolean` - true if file is valid
- **Throws**: None

**[export(dstFile: Path, from: IStorage, predicate: EntityFilter = { true }): Path]**
- **Behavior**: Exports data from an IStorage object to a destination file.
- **Input**: `dstFile: Path` - path to destination file, `from: IStorage` - storage to export from, `predicate: EntityFilter` - optional filter predicate
- **Output**: `Path` - path to the exported file
- **Throws**: None

**[import(srcFile: Path, into: IStorage, predicate: EntityFilter = { true }): IStorage]**
- **Behavior**: Imports entities from a source file into the specified storage.
- **Input**: `srcFile: Path` - path to source file, `into: IStorage` - storage to import into, `predicate: EntityFilter` - optional filter predicate
- **Output**: `IStorage` - storage with imported entities
- **Throws**: None

### NativeCsvExchangeImpl Object

**Responsibility**: CSV-based storage exchange implementation.

**Properties**: Implements `IStorageExchange`

---

## Exception Classes

**EntityNotExistException**: Raised when an entity with the given ID does not exist. Used when trying to access, update, or delete a non-existent entity.

**EntityAlreadyExistException**: Raised when an entity with the given ID already exists. Used when attempting to create or add an entity that already exists.

**InvalidPropNameException**: Raised when an invalid property name is used for an entity. The property name is invalid if it starts with "meta_".

**AccessClosedStorageException**: Raised when an operation is attempted on a storage that has already been closed. Used within graph storage systems to indicate that the storage context is no longer active or accessible.

---

## Validation Rules

**Entity Validation**:
- Entity IDs must not be null.
- Node and edge IDs must be unique within their respective collections.

**Node Validation**:
- Nodes must exist in storage before edges can reference them.
- Node properties are stored in the storage system, not in the node object itself.

**Edge Validation**:
- Edges must reference existing nodes (both source and destination).
- Edge IDs are composed of source node ID, destination node ID, and edge type.
- In `AbcSimpleGraph`, at most one edge is allowed between any two nodes in a given direction.
- In `AbcMultiGraph`, multiple edges between the same pair of nodes are allowed.

**Storage Validation**:
- Storage operations throw `AccessClosedStorageException` if storage is closed.
- Storage operations throw `EntityNotExistException` if the referenced entity does not exist.
- Storage operations throw `EntityAlreadyExistException` if attempting to add an entity that already exists.

**Property Validation**:
- Property names starting with "meta_" are invalid and throw `InvalidPropNameException`.
- Properties with null values are removed from entities.

**Cache Validation**:
- `AbcBasicGraph` maintains caches for node and edge IDs that must be synchronized with storage.
- Cache refresh operations only load edges whose type starts with the graph name.
