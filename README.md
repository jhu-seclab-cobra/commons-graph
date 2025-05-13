# COBRA.COMMONS.GRAPH

[![codecov](https://codecov.io/gh/jhu-seclab-cobra/commons-graph/branch/main/graph/badge.svg)](https://codecov.io/gh/jhu-seclab-cobra/commons-graph)
[![Kotlin JVM](https://img.shields.io/badge/Kotlin%20JVM-1.8%2B-blue?logo=kotlin)](https://kotlinlang.org/)
[![Release](https://img.shields.io/github/v/release/jhu-seclab-cobra/commons-graph?include_prereleases)](https://github.com/jhu-seclab-cobra/commons-graph/releases)
[![last commit](https://img.shields.io/github/last-commit/jhu-seclab-cobra/commons-graph)](https://github.com/jhu-seclab-cobra/commons-graph/commits/main)
[![JitPack](https://jitpack.io/v/jhu-seclab-cobra/commons-graph.svg)](https://jitpack.io/#jhu-seclab-cobra/commons-graph)
[![License](https://img.shields.io/github/license/jhu-seclab-cobra/commons-graph)](./LICENSE)

A modular, high-performance Kotlin library for graph data structures and storage, designed for the COBRA platform.
Supports in-memory, MapDB, JGraphT, and Neo4j backends, with robust concurrency, serialization, and property management.

---

## Features

- Unified, extensible graph API
- Multiple storage backends: In-memory, MapDB, JGraphT, Neo4j
- Type-safe, persistent entity property management
- Thread-safe and concurrent graph operations
- Custom serialization for MapDB
- CSV, MapDB, and GML import/export utilities
- Comprehensive test suite for correctness and concurrency

---

## Module Overview

- **graph**  
  The core module that defines the unified graph abstraction layer. It provides the main graph interfaces, entity
  models (nodes, edges, groups), and all high-level graph operations (such as add/remove/query/traverse, property
  management, etc.).  
  All actual data storage is delegated to implementations of the `IStorage` interface, allowing users to interact with
  any backend using a consistent API.  
  Includes an in-memory storage implementation (`NativeStorageImpl`) for lightweight or testing scenarios.

- **modules/extension-mapdb**  
  Provides a high-performance persistent storage backend based on [MapDB](https://github.com/jankotek/mapdb).  
  Implements the `IStorage` interface for disk-based, scalable graph storage, efficient property serialization, and
  optional concurrency support.  
  Includes MapDB-specific property maps, custom serializers, and import/export utilities for MapDB files.

- **modules/extension-jgrapht**  
  Offers a storage backend based on [JGraphT](https://jgrapht.org/), suitable for in-memory graphs that require advanced
  algorithms and flexible graph structures.  
  Implements the `IStorage` interface and supports GML format import/export for interoperability with other graph tools.

- **modules/extension-neo4j**  
  Integrates [Neo4j](https://neo4j.com/) as a scalable, transactional graph database backend.  
  Implements the `IStorage` interface, making it suitable for distributed, persistent, and enterprise-grade graph
  applications.  
  Can be connected to a running Neo4j server for full database capabilities.

**Design Notes:**

- All high-level graph operations (such as adding/removing nodes/edges, property management, traversal, etc.) are
  performed through the unified API provided by the `graph` module.
- The actual storage details are completely handled by the selected `IStorage` implementation, making it easy to switch
  or extend storage backends without changing business logic.
- To add a new storage backend, simply implement the `IStorage` interface and plug it into the graph layer—no changes to
  the upper-level code are required.
- Users can choose the most suitable storage implementation (in-memory, MapDB, JGraphT, Neo4j, or custom) and always use
  the same graph API for all operations.

---

## Requirements

- Java 8 or higher

---

## Installation

### 1. Add JitPack repository

In your `build.gradle.kts`:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
```

### 2. Add the dependency

**Core graph module:**

```kotlin
dependencies {
    implementation("com.github.jhu-seclab-cobra.commons-graph:graph:<version>")
}
```

**MapDB extension:**

```kotlin
dependencies {
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-extension-mapdb:<version>")
}
```

**JGraphT extension:**

```kotlin
dependencies {
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-extension-jgrapht:<version>")
}
```

**Neo4j extension:**

```kotlin
dependencies {
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-extension-neo4j:<version>")
}
```

Replace `<version>` with the latest [release version](https://jitpack.io/#jhu-seclab-cobra/commons-graph).

---

## Usage

### 1. In-memory Graph Example

```kotlin
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.entity.AbcNode
import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

// You need to provide concrete node/edge implementations for your use case
class MyNode(id: NodeID, storage: NativeStorageImpl) : AbcNode(storage) {
    override val id = id
    override val type = object : Type { override val name = "MyNode" }
}
class MyEdge(id: EdgeID, storage: NativeStorageImpl) : AbcEdge(id, storage) {
    override val type = object : Type { override val name = "MyEdge" }
}

val graph = object : AbcSimpleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(nid, storage)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(eid, storage)
}
val nodeA = NodeID("A")
val nodeB = NodeID("B")
graph.addNode(nodeA)
graph.addNode(nodeB)
graph.addEdge(graph.getNode(nodeA)!!, graph.getNode(nodeB)!!, "edge1")
```

### 2. MapDB Persistent Storage

```kotlin
import edu.jhu.cobra.commons.graph.storage.MapDBStorageImpl
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.entity.EdgeID

val storage = MapDBStorageImpl { fileDB("mygraph.db") }
storage.addNode(NodeID("n1"))
storage.addNode(NodeID("n2"))
storage.addEdge(EdgeID(NodeID("n1"), NodeID("n2"), "e"))
storage.close()
```

### 3. CSV Import/Export

```kotlin
import edu.jhu.cobra.commons.graph.exchange.NativeCsvExchangeImpl

val csvExchange = NativeCsvExchangeImpl()
csvExchange.exportGraph(graph, "output.csv")
val importedGraph = csvExchange.importGraph("output.csv")
```

### 4. JGraphT/Neo4j Storage

```kotlin
// JGraphT
import edu.jhu.cobra.commons.graph.storage.JgraphtStorageImpl
val jgraphtStorage = JgraphtStorageImpl()
// Neo4j
import edu.jhu.cobra.commons.graph.storage.Neo4jStorageImpl
val neo4jStorage = Neo4jStorageImpl("bolt://localhost:7687", "user", "password")
```

---

## Testing

Run all tests with:

```shell
./gradlew test
```

---

## License

[GNU General Public License v2.0](./LICENSE)

---

## Contributing

Contributions are welcome! Please open issues or submit pull requests for bug fixes, new features, or improvements.

---

## Acknowledgements

Part of the COBRA platform.  
For more information, see [COBRA Project](https://github.com/jhu-seclab-cobra).
