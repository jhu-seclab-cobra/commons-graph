# commons-graph

[![codecov](https://codecov.io/gh/jhu-seclab-cobra/commons-graph/branch/main/graph/badge.svg)](https://codecov.io/gh/jhu-seclab-cobra/commons-graph)
[![JitPack](https://jitpack.io/v/jhu-seclab-cobra/commons-graph.svg)](https://jitpack.io/#jhu-seclab-cobra/commons-graph)
[![License](https://img.shields.io/github/license/jhu-seclab-cobra/commons-graph)](./LICENSE)

Backend-agnostic directed graph library for Kotlin/JVM. Switch between in-memory, MapDB, JGraphT, or Neo4j without changing application code.

## Installation

Java 8+. Add JitPack and the modules you need:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.jhu-seclab-cobra.commons-graph:graph:<version>")
    // Optional backends:
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-impl-jgrapht:<version>")
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-impl-mapdb:<version>")
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-impl-neo4j:<version>")
}
```

## Quick Start

### Graph API

Define node/edge types with no-arg constructors, then subclass `AbcMultipleGraph`:

```kotlin
class MyNode : AbcNode() {
    override val type = object : Type { override val name = "MyNode" }
}
class MyEdge : AbcEdge() {
    override val type = object : Type { override val name = "MyEdge" }
}

val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override val posetStorage = NativeStorageImpl()
    override fun newNodeObj() = MyNode()
    override fun newEdgeObj() = MyEdge()
}

val a = graph.addNode("A")       // returns MyNode
val b = graph.addNode("B")
val e = graph.addEdge("A", "B", "calls")  // returns MyEdge

a["weight"] = 42.numVal           // property access on nodes/edges
graph.getChildren("A")            // Sequence<MyNode> → [B]
graph.getDescendants("A")         // transitive closure
graph.getOutgoingEdges("A")       // Sequence<MyEdge>
graph.close()
```

Use `AbcSimpleGraph` instead to enforce at most one edge per direction between any two nodes.

### Storage API

`IStorage` operates on auto-generated `Int` IDs. All backends share the same interface:

```kotlin
val storage = NativeStorageImpl()
val a = storage.addNode(mapOf("type" to "entry".strVal))
val b = storage.addNode(mapOf("weight" to 42.numVal))
val e = storage.addEdge(a, b, "call")

storage.getNodeProperties(a)       // {type=entry}
storage.getOutgoingEdges(a)        // {e}
storage.setNodeProperties(b, mapOf("weight" to 100.numVal))
storage.close()
```

Switch backend by changing the constructor:

```kotlin
val jgrapht  = JgraphtStorageImpl()
val mapdb    = MapDBStorageImpl { memoryDB() }
val neo4j    = Neo4jStorageImpl(Paths.get("/tmp/neo4j-data"))
val layered  = LayeredStorageImpl()  // freeze-and-stack for phased pipelines
```

### Label Lattice

Labels form a partial order controlling edge visibility. Assign labels to edges, then query with a label to filter:

```kotlin
val debug = Label("debug")
val release = Label("release")
release.parents = mapOf("base" to debug)   // release > debug

graph.addEdge("A", "B", "flow", debug)
graph.getOutgoingEdges("A", release)       // visible (release > debug)
graph.getOutgoingEdges("A", debug)         // visible (exact match)
```

### Layered Storage

`LayeredStorageImpl` freezes data into immutable layers for phased analysis:

```kotlin
val storage = LayeredStorageImpl()
buildAST(source, storage)
storage.freeze()          // AST → frozen; new empty active layer
buildCFG(storage)
storage.freeze()          // CFG → frozen
analyze(storage)          // reads all frozen layers transparently
```

### I/O

```kotlin
source.transferTo(target)                              // storage-to-storage
NativeCsvIOImpl.export(Paths.get("out"), storage)      // CSV
JgraphtGmlIOImpl.export(Paths.get("g.gml"), storage)   // GML
MapDbGraphIOImpl.export(Paths.get("g.mapdb"), storage)  // MapDB
```

## Storage Backends

| Backend | Persistence | Thread-Safe | Use Case |
|---------|-------------|-------------|----------|
| `NativeStorageImpl` | No | No | Default. Fastest for single-threaded workloads |
| `NativeConcurStorageImpl` | No | Yes | Multi-threaded in-memory |
| `LayeredStorageImpl` | No | No | Phase-based analysis pipelines |
| `JgraphtStorageImpl` | No | No | Access to JGraphT algorithms |
| `MapDBStorageImpl` | File | No | Graphs exceeding heap capacity |
| `Neo4jStorageImpl` | Disk | Yes | Enterprise persistence, Cypher |

All concurrent variants (`*ConcurStorageImpl`) add a read-write lock.

## Performance

Measured on Apple Silicon, Temurin 21. NativeStorageImpl at 100K nodes / 300K edges:

| Operation | Throughput |
|---|---|
| Graph population | 42 ms |
| Node add | 9.6M ops/s |
| Node lookup | 176M ops/s |
| Property read | 51M ops/s |
| Edge query (outgoing) | 111M ops/s |
| Mixed workload (5 ops/iter, 50K iter) | 11 ms |
| Memory (10K nodes, 30K edges, 5 props) | 34 MB heap |

Tested up to 1M nodes / 3M edges. Theoretical upper bound is ~2B nodes + ~2B edges (Int ID space). Memory is the practical limit: ~120 GB heap for 100M nodes / 300M edges, ~1.2 TB for 1B. All backends currently maintain in-memory ID mappings, but MapDB and Neo4j have the potential to support larger-than-memory graphs by moving these mappings to their native storage — this is a future optimization, not a current capability. For graphs beyond billion-scale, use a dedicated graph database directly. Run benchmarks:

```shell
./gradlew :graph:test --tests "*.StoragePerformanceTest" -PincludePerformanceTests --rerun
```

## Testing

```shell
./gradlew test          # all modules
./gradlew :graph:test   # core only
```

## License

[GNU General Public License v2.0](./LICENSE)
