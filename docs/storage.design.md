# Storage Module Design

## Design Overview

**Classes**: `NativeStorageImpl`, `NativeConcurStorageImpl`, `NativeCsvIOImpl`

**Relationships**: `NativeStorageImpl` implements `IStorage`, `NativeConcurStorageImpl` implements `IStorage`, `NativeCsvIOImpl` implements `IStorageExporter` and `IStorageImporter`, `NativeCsvIOImpl` uses `IStorage`

**Interfaces**: `IStorage` (implemented by `NativeStorageImpl`, `NativeConcurStorageImpl`), `IStorageExporter` (implemented by `NativeCsvIOImpl`), `IStorageImporter` (implemented by `NativeCsvIOImpl`)

**Abstract**: None

**Exceptions**: `AccessClosedStorageException` (thrown by all storage operations when storage is closed), `EntityNotExistException` (thrown by operations on non-existent entities), `EntityAlreadyExistException` (thrown when adding existing entities)

---

## Class Specifications

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
- **Throws**: `AccessClosedStorageException` if storage is closed, `EntityAlreadyExistException` if edge already exists, `EntityNotExistException` if source or destination node does not exist

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

**[close()]**
- **Behavior**: Closes the storage and releases all resources. After closing, all operations will throw AccessClosedStorageException.
- **Input**: None
- **Output**: Unit
- **Throws**: None

**Example Usage**:
```kotlin
val storage = NativeStorageImpl()

// Add nodes
val nodeId1 = NodeID("node1")
val nodeId2 = NodeID("node2")
storage.addNode(nodeId1, mapOf("name" to "Node1".strVal))
storage.addNode(nodeId2)

// Add edges
val edgeId = EdgeID(nodeId1, nodeId2, "relation")
storage.addEdge(edgeId, mapOf("weight" to 1.0.doubleVal))

// Query storage
val allNodeIds = storage.nodeIDs
val allEdgeIds = storage.edgeIDs
val nodeProps = storage.getNodeProperties(nodeId1)
val edgeProps = storage.getEdgeProperties(edgeId)

// Update properties
storage.setNodeProperties(nodeId1, mapOf("name" to "UpdatedNode1".strVal))
storage.setEdgeProperties(edgeId, mapOf("weight" to 2.0.doubleVal, "oldWeight" to null))

// Query graph structure
val incoming = storage.getIncomingEdges(nodeId2)
val outgoing = storage.getOutgoingEdges(nodeId1)

// Metadata operations
storage.setMeta("version", "1.0".strVal)
val version = storage.getMeta("version")

// Cleanup
storage.clear()
storage.close()
```

### NativeStorageImpl Class

**Responsibility**: In-memory graph storage implementation using HashMap-based data structures. Provides efficient storage with O(1) average time complexity. Not thread-safe.

**Properties**: Implements `IStorage`

**Example Usage**:
```kotlin
val storage = NativeStorageImpl()

// Add nodes and edges
storage.addNode(NodeID("n1"), mapOf("label" to "Node1".strVal))
storage.addNode(NodeID("n2"))
storage.addEdge(EdgeID(NodeID("n1"), NodeID("n2"), "connects"))

// Query
val exists = storage.containsNode(NodeID("n1"))
val props = storage.getNodeProperties(NodeID("n1"))

// Cleanup
storage.close()
```

### NativeConcurStorageImpl Class

**Responsibility**: Thread-safe in-memory graph storage implementation using read-write locks. Provides concurrent access with multiple readers and single writer. Suitable for read-heavy workloads.

**Properties**: Implements `IStorage`

**Example Usage**:
```kotlin
val storage = NativeConcurStorageImpl()

// Thread-safe operations
val thread1 = thread {
    storage.addNode(NodeID("n1"))
    storage.addNode(NodeID("n2"))
}

val thread2 = thread {
    storage.addEdge(EdgeID(NodeID("n1"), NodeID("n2"), "relation"))
}

thread1.join()
thread2.join()

// Safe concurrent reads
val allNodes = storage.nodeIDs
val allEdges = storage.edgeIDs

storage.close()
```

### IStorageExporter Interface

**Responsibility**: Provides methods for exporting graph data from storage implementations to external files.

**[export(dstFile: Path, from: IStorage, predicate: EntityFilter = { true }): Path]**
- **Behavior**: Exports data from an IStorage object to a destination file.
- **Input**: `dstFile: Path` - path to destination file, `from: IStorage` - storage to export from, `predicate: EntityFilter` - optional filter predicate
- **Output**: `Path` - path to the exported file
- **Throws**: None

**Example Usage**:
```kotlin
val storage = NativeStorageImpl()
storage.addNode(NodeID("n1"), mapOf("name" to "Node1".strVal))
storage.addNode(NodeID("n2"))
storage.addEdge(EdgeID(NodeID("n1"), NodeID("n2"), "relation"))

// Export all entities
val exportPath = NativeCsvIOImpl.export(
    dstFile = Paths.get("/tmp/graph_export"),
    from = storage
)

// Export with filter
val filteredPath = NativeCsvIOImpl.export(
    dstFile = Paths.get("/tmp/graph_filtered"),
    from = storage,
    predicate = { id -> id is NodeID }  // Only export nodes
)
```

### IStorageImporter Interface

**Responsibility**: Provides methods for importing graph data from external files into storage implementations.

**[isValidFile(file: Path): Boolean]**
- **Behavior**: Checks if a given file is valid for import.
- **Input**: `file: Path` - path to the file to be checked
- **Output**: `Boolean` - true if file is valid
- **Throws**: None

**[import(srcFile: Path, into: IStorage, predicate: EntityFilter = { true }): IStorage]**
- **Behavior**: Imports entities from a source file into the specified storage.
- **Input**: `srcFile: Path` - path to source file, `into: IStorage` - storage to import into, `predicate: EntityFilter` - optional filter predicate
- **Output**: `IStorage` - storage with imported entities
- **Throws**: None

**Example Usage**:
```kotlin
val importPath = Paths.get("/tmp/graph_export")

// Check if file is valid
if (NativeCsvIOImpl.isValidFile(importPath)) {
    val storage = NativeStorageImpl()
    
    // Import all entities
    val importedStorage = NativeCsvIOImpl.import(
        srcFile = importPath,
        into = storage
    )
    
    // Import with filter
    val filteredStorage = NativeCsvIOImpl.import(
        srcFile = importPath,
        into = NativeStorageImpl(),
        predicate = { id -> id.name.startsWith("n") }  // Only import IDs starting with "n"
    )
}
```

### NativeCsvIOImpl Object

**Responsibility**: CSV-based storage exchange implementation. Exports and imports graph data in CSV format.

**Properties**: Implements `IStorageExporter` and `IStorageImporter`

**Example Usage**:
```kotlin
val storage = NativeStorageImpl()
storage.addNode(NodeID("n1"), mapOf("name" to "Node1".strVal, "age" to 25.intVal))
storage.addNode(NodeID("n2"), mapOf("name" to "Node2".strVal))
storage.addEdge(EdgeID(NodeID("n1"), NodeID("n2"), "relation"), mapOf("weight" to 1.5.doubleVal))

// Export to CSV directory
val exportDir = Paths.get("/tmp/graph_csv")
NativeCsvIOImpl.export(exportDir, storage)
// Creates: /tmp/graph_csv/nodes.csv and /tmp/graph_csv/edges.csv

// Import from CSV directory
if (NativeCsvIOImpl.isValidFile(exportDir)) {
    val newStorage = NativeStorageImpl()
    NativeCsvIOImpl.import(exportDir, newStorage)
    // newStorage now contains all imported nodes and edges
}
```

---

## Exception Classes

**AccessClosedStorageException**: Raised when an operation is attempted on a storage that has already been closed.

**EntityNotExistException**: Raised when trying to access, update, or delete a non-existent entity.

**EntityAlreadyExistException**: Raised when attempting to create or add an entity that already exists.

---

## Validation Rules

**Storage Validation**:
- Storage operations throw `AccessClosedStorageException` if storage is closed.
- Storage operations throw `EntityNotExistException` if the referenced entity does not exist.
- Storage operations throw `EntityAlreadyExistException` if attempting to add an entity that already exists.
- When deleting a node, all connected edges are automatically deleted.
- Edges must reference existing nodes (both source and destination).

**IO Validation**:
- File validation checks if the directory contains valid `nodes.csv` and `edges.csv` files.
- Export operations create the destination directory if it does not exist.
- Import operations require valid CSV files with proper structure.
