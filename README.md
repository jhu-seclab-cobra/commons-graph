# COBRA.COMMONS.GRAPH

[![codecov](https://codecov.io/gh/jhu-seclab-cobra/commons-graph/branch/main/graph/badge.svg)](https://codecov.io/gh/jhu-seclab-cobra/commons-graph)
[![Kotlin JVM](https://img.shields.io/badge/Kotlin%20JVM-1.8%2B-blue?logo=kotlin)](https://kotlinlang.org/)
[![Release](https://img.shields.io/github/v/release/jhu-seclab-cobra/commons-graph?include_prereleases)](https://github.com/jhu-seclab-cobra/commons-graph/releases)
[![JitPack](https://jitpack.io/v/jhu-seclab-cobra/commons-graph.svg)](https://jitpack.io/#jhu-seclab-cobra/commons-graph)
[![License](https://img.shields.io/github/license/jhu-seclab-cobra/commons-graph)](./LICENSE)

Backend-agnostic graph library for Kotlin/JVM. Decouple graph domain logic from storage: switch between in-memory, MapDB, JGraphT, or Neo4j backends without changing application code.

## Architecture

```
[Application Code]
        |
        v
[IGraph: identity-first graph contract]
  - AbcMultipleGraph (parallel edges + label lattice)
  - AbcSimpleGraph (unique edges per direction)
        |
        v
[IStorage: backend-agnostic storage contract (Int IDs)]
        |
        +-- NativeStorageImpl         (heap HashMap, fastest)
        +-- NativeConcurStorageImpl   (heap + RW lock)
        +-- LayeredStorageImpl        (freeze-and-stack, multi-layer)
        +-- JgraphtStorageImpl        (JGraphT DirectedPseudograph)
        +-- JgraphtConcurStorageImpl  (JGraphT + RW lock)
        +-- MapDBStorageImpl          (off-heap / file-backed)
        +-- MapDBConcurStorageImpl    (MapDB + RW lock)
        +-- Neo4jStorageImpl          (embedded Neo4j)
        +-- Neo4jConcurStorageImpl    (Neo4j + RW lock)
```

`IGraph` owns traversal, edge uniqueness, and label lattice. `IStorage` owns persistence, adjacency, and property maps. `LayeredStorageImpl` composes a frozen layer (read-only) + mutable active layer — reads cascade through layers, writes target only the active layer.

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `graph` | `commons-graph:graph` | Core: `IGraph`, `IStorage`, entities, labels, Native/Layered storage, CSV I/O |
| `modules/impl-jgrapht` | `commons-graph:modules-impl-jgrapht` | JGraphT `DirectedPseudograph` backend + GML I/O |
| `modules/impl-mapdb` | `commons-graph:modules-impl-mapdb` | MapDB off-heap/file backend + MapDB I/O |
| `modules/impl-neo4j` | `commons-graph:modules-impl-neo4j` | Neo4j embedded database backend |

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

## Usage

### Storage-level operations

`IStorage` uses `Int`-based opaque IDs returned by `addNode`/`addEdge`. All backends share the same interface:

```kotlin
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal

val storage = NativeStorageImpl()
val a = storage.addNode(mapOf("type" to "entry".strVal))
val b = storage.addNode(mapOf("weight" to 42.numVal))
val e = storage.addEdge(a, b, "call")

storage.getNodeProperties(a)       // {type=entry}
storage.getOutgoingEdges(a)        // {e}
storage.setNodeProperties(b, mapOf("weight" to 100.numVal))
storage.close()
```

Switch backend by changing the constructor — no other code changes:

```kotlin
val jgrapht = JgraphtStorageImpl()
val mapdb = MapDBStorageImpl { memoryDB() }
val mapdbFile = MapDBStorageImpl { fileDB("graph.db").fileMmapEnableIfSupported() }
val neo4j = Neo4jStorageImpl(Paths.get("/tmp/neo4j-data"))
```

### Graph-level operations

`IGraph` uses `NodeID` (String) identifiers. Define concrete node/edge types, then build a graph backed by any `IStorage`:

```kotlin
import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

class MyNode(storage: IStorage, internalId: InternalID) : AbcNode(storage, internalId) {
    override val type = object : Type { override val name = "MyNode" }
}
class MyEdge(
    storage: IStorage, internalId: InternalID, nodeIdResolver: (InternalID) -> NodeID,
) : AbcEdge(storage, internalId, nodeIdResolver) {
    override val type = object : Type { override val name = "MyEdge" }
}

val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override val posetStorage = NativeStorageImpl()
    override fun newNodeObj(internalId: InternalID) = MyNode(storage, internalId)
    override fun newEdgeObj(internalId: InternalID, nodeIdResolver: (InternalID) -> NodeID) =
        MyEdge(storage, internalId, nodeIdResolver)
}

val a = graph.addNode("A")
val b = graph.addNode("B")
graph.addEdge("A", "B", "dep")

graph.getChildren("A").forEach { println(it.id) }  // B
graph.getDescendants("A").forEach { println(it.id) }
graph.close()
```

Use `AbcSimpleGraph` to enforce at most one edge per direction between any two nodes.

### Label lattice (edge visibility)

Labels form a partially ordered set controlling edge visibility. Assign labels to edges, then query with a label to see only edges at or below that label in the hierarchy:

```kotlin
with(graph) {
    val debug = Label("debug")
    val release = Label("release")
    release.parents = mapOf("base" to debug)  // release > debug

    val edge = graph.addEdge("A", "B", "flow", debug)
    // edge.labels == {debug}

    graph.getOutgoingEdges("A", release)  // sees edge (release > debug)
    graph.getOutgoingEdges("A", debug)    // sees edge (debug == debug)
}
```

### Layered storage

`LayeredStorageImpl` provides freeze-and-stack for phased analysis pipelines. Each `freeze()` merges all data into a single frozen layer and creates a fresh active layer:

```kotlin
val storage = LayeredStorageImpl()

// Phase 1: build AST
buildAST(source, storage)
storage.freeze()   // all data → frozen layer; new empty active layer

// Phase 2: build CFG
buildCFG(storage)   // reads frozen AST, writes CFG to active layer
storage.freeze()

// Phase 3: analysis
analyze(storage)    // reads all frozen layers transparently
```

Frozen layers are immutable — deletion throws `FrozenLayerModificationException`. Active layer supports full CRUD.

### Data transfer and I/O

```kotlin
// Storage-to-storage transfer
source.transferTo(target)

// CSV export/import
val csv = NativeCsvIOImpl()
csv.export(Paths.get("output"), storage)
csv.import(Paths.get("output"), targetStorage)

// GML (JGraphT module)
val gml = JgraphtGmlIOImpl()
gml.export(Paths.get("graph.gml"), storage)

// MapDB native I/O
val mapdbIO = MapDbGraphIOImpl()
mapdbIO.export(Paths.get("graph.mapdb"), storage)
```

## Choosing a Storage Backend

| Backend | Heap Usage | Persistence | Thread-Safe | Best For |
|---------|-----------|-------------|-------------|----------|
| `NativeStorageImpl` | All in heap | No | No | Fast prototyping, small-medium graphs |
| `NativeConcurStorageImpl` | All in heap | No | Yes (RW lock) | Multi-threaded in-memory workloads |
| `LayeredStorageImpl` | Active layer in heap | Via frozen factory | No | Static analysis pipelines, phase-based freezing |
| `JgraphtStorageImpl` | All in heap | No | No | When JGraphT algorithms are needed |
| `MapDBStorageImpl` | Off-heap | Yes (file) | No | Large graphs exceeding heap, persistence |
| `Neo4jStorageImpl` | Managed by Neo4j | Yes (disk) | Yes | Enterprise features, Cypher queries |

## Performance

Benchmarked on macOS (Apple Silicon), Eclipse Temurin 21, G1GC. Median of multiple measured iterations after JIT warmup. Absolute numbers vary by hardware; relative comparisons between backends are stable.

### Cross-Backend Comparison

Non-concurrent implementations, best config per backend:

| Metric | NativeStorage | JGraphT | MapDB | Neo4j |
|---|---:|---:|---:|---:|
| **Population** (10K/30K) | **2.6 ms** | 18.5 ms | ~300 ms | ~38,000 ms |
| **Node Lookup** (ops/sec) | **143.54M** | 40.46M | 1.83M | 23.40M \* |
| **Property Read** (ops/sec) | **~46M** | **~46M** | 1.61M | 452.5K |
| **Property Write** (ops/sec) | **~40M** | ~20M | 94.7K | 116 |
| **Edge Query Out** (ops/sec) | **116.33M** | 8.97M | 1.52M | 696.8K |
| **Edge Query In** (ops/sec) | **49.94M** | 8.79M | 1.51M | 744.7K |

> \* Neo4j node lookup checks an in-memory HashMap, not Neo4j transactions.
>
> Property Read/Write numbers are median of 5 independent JVM runs (cross-run validated). Single-run numbers show up to 2.8x JIT variance.
>
> MapDB uses `memoryDB` config. `tempFile+mmap` is comparable; `tempFileDB` without mmap is 10x slower.

### Memory and Disk Usage

Measured with 10K nodes / 30K edges (3 edges/node), 5 properties per entity. Heap delta = GC'd heap after populate - GC'd heap before.

| Backend | Heap (MB) | Off-heap (MB) | Disk (MB) | Total (MB) |
|---|---:|---:|---:|---:|
| **NativeStorage** | **45.4** | — | — | **45.4** |
| **LayeredStorage** | 47.3 | — | — | 47.3 |
| **JGraphT** | 52.8 | — | — | 52.8 |
| **MapDB (memoryDB)** | 53.6 | — | — | 53.6 |
| **MapDB (memoryDirectDB)** | 13.7 | 34.0 | — | 47.7 |
| **MapDB (fileDB)** | 13.7 | — | 34.0 | 47.7 |
| **MapDB (fileDB+mmap)** | 13.8 | — | 34.0 | 47.8 |
| **Neo4j** | ~54 \* | — | 41.4 | ~95 |

> \* Neo4j heap delta is unreliable in single-run measurement (GC timing, JIT warmup, embedded DB internals). Reported as average of two runs.
>
> NativeStorage scales ~4.5 MB per 1K nodes (with 3K edges, 5 props/entity).
>
> MapDB total footprint (~48 MB) is comparable to NativeStorage (~45 MB) — the data must exist somewhere. `memoryDirectDB` and `fileDB` shift it off-heap/to-disk, reducing GC pressure at the cost of 30-80x slower throughput.

### Run Benchmarks

```shell
./gradlew :graph:test --tests "*.StoragePerformanceTest" -PincludePerformanceTests --rerun
./gradlew :graph:test --tests "*.GraphPerformanceTest" -PincludePerformanceTests --rerun
./gradlew :modules:impl-jgrapht:test --tests "*.JgraphtPerformanceTest" -PincludePerformanceTests --rerun
./gradlew :modules:impl-mapdb:test --tests "*.MapDBPerformanceTest" -PincludePerformanceTests --rerun
./gradlew :modules:impl-neo4j:test --tests "*.Neo4jPerformanceTest" -PincludePerformanceTests --rerun
```

Detailed per-backend results: [`docs/impl/performance.md`](docs/impl/performance.md), [`jgrapht-performance.md`](docs/impl/jgrapht-performance.md), [`mapdb-performance.md`](docs/impl/mapdb-performance.md), [`neo4j-performance.md`](docs/impl/neo4j-performance.md).

## Capability Boundaries

- **Not a graph algorithm library.** Provides storage and traversal primitives. Use JGraphT algorithms via `JgraphtStorageImpl` or implement algorithms on top of `IGraph`.
- **No distributed mode.** All backends are single-JVM. Neo4j can connect to a cluster, but the `IStorage` contract is local.
- **No schema enforcement.** Properties are `Map<String, IValue>` — no compile-time type checking on property keys or values.
- **No automatic edge cascade on node delete.** `IStorage.deleteNode` does not remove connected edges. The graph layer (`AbcMultipleGraph`) handles cascade; direct storage usage must manage this manually.
- **LayeredStorage frozen layers are immutable.** Cannot delete entities from frozen layers.
- **MapDB serialization cost.** Every property access incurs serialize/deserialize overhead. For traversal-heavy workloads, prefer NativeStorage.

## Testing

```shell
./gradlew test                    # all modules
./gradlew :graph:test             # core module only
./gradlew :modules:impl-mapdb:test  # specific module
```

## License

[GNU General Public License v2.0](./LICENSE)

## Acknowledgements

Part of the [COBRA platform](https://github.com/jhu-seclab-cobra) at Johns Hopkins University.
