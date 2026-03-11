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
  - AbcMultipleGraph (parallel edges)
  - AbcSimpleGraph (unique edges per direction)
  - Label lattice (edge visibility poset)
        |
        v
[IStorage: backend-agnostic storage contract]
        |
        +-- Flat storage (single-layer, full CRUD)
        |     +-- NativeStorageImpl         (heap HashMap)
        |     +-- NativeConcurStorageImpl   (heap + RW lock)
        |     +-- JgraphtStorageImpl        (JGraphT pseudograph)
        |     +-- MapDBStorageImpl          (off-heap / file-backed)
        |     +-- Neo4jStorageImpl          (embedded Neo4j)
        |
        +-- Layered storage (multi-layer composition)
              +-- DeltaStorageImpl    (2-layer: frozen base + mutable overlay)
              +-- PhasedStorageImpl   (N-layer: freeze-and-stack pipeline)
```

`IGraph` owns traversal, edge uniqueness policy, and the label lattice. `IStorage` owns persistence, adjacency, and property maps. Layered storages compose multiple `IStorage` instances — reads cascade through layers, writes target only the active layer.

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `graph` | `commons-graph:graph` | Core: `IGraph`, `IStorage`, entities, labels, Native/Delta/Phased storage, CSV I/O |
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

All backends share the same `IStorage` interface — `NodeID`/`EdgeID`-based CRUD, property maps, adjacency queries, and metadata:

```kotlin
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal

val storage = NativeStorageImpl()
storage.addNode(NodeID("A"), mapOf("type" to "entry".strVal))
storage.addNode(NodeID("B"), mapOf("weight" to 42.numVal))
storage.addEdge(EdgeID(NodeID("A"), NodeID("B"), "call"))

storage.getNodeProperties(NodeID("A"))       // {type=entry}
storage.getOutgoingEdges(NodeID("A"))        // {A-call-B}
storage.setNodeProperties(NodeID("B"), mapOf("weight" to 100.numVal))
storage.close()
```

Switch backend by changing the constructor — no other code changes:

```kotlin
// JGraphT backend
val jgrapht = JgraphtStorageImpl()

// MapDB in-memory
val mapdb = MapDBStorageImpl { memoryDB() }

// MapDB file-backed with mmap
val mapdbFile = MapDBStorageImpl { fileDB("graph.db").fileMmapEnableIfSupported() }

// Neo4j embedded
val neo4j = Neo4jStorageImpl(Paths.get("/tmp/neo4j-data"))
```

### Graph-level operations

Define concrete node/edge types, then build a graph backed by any `IStorage`:

```kotlin
import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

class MyNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
    override val type = object : Type { override val name = "MyNode" }
}
class MyEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
    override val type = object : Type { override val name = "MyEdge" }
}

val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override fun newNodeObj(nid: NodeID) = MyNode(storage, nid)
    override fun newEdgeObj(eid: EdgeID) = MyEdge(storage, eid)
}

val a = graph.addNode(NodeID("A"))
val b = graph.addNode(NodeID("B"))
graph.addEdge(EdgeID(NodeID("A"), NodeID("B"), "dep"))

graph.getChildren(NodeID("A")).forEach { println(it.id) }  // B
graph.getDescendants(NodeID("A")).forEach { println(it.id) }
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

    val edge = graph.addEdge(EdgeID(a.id, b.id, "flow"), debug)
    // edge.labels == {debug}

    graph.getOutgoingEdges(a.id, release)  // sees edge (release > debug)
    graph.getOutgoingEdges(a.id, debug)    // sees edge (debug == debug)
}
```

### Layered storage

**DeltaStorage** — two-layer overlay for scenarios requiring frozen-layer deletion (incremental analysis, graph restructuring):

```kotlin
val base = NativeStorageImpl()
// populate base...
val delta = DeltaStorageImpl(base)  // base is frozen; delta writes go to overlay
delta.addNode(NodeID("new"))
delta.deleteNode(NodeID("old"))     // tracked via deleted-entity holders
```

**PhasedStorage** — N-layer freeze-and-stack for static analysis pipelines (AST → CFG → DFG → analysis). Each phase freezes to off-heap, reducing heap pressure:

```kotlin
val storage = PhasedStorageImpl()

// Phase 1: build AST
buildAST(source, storage)
storage.freezeAndPushLayer()   // AST data → frozen layer; new empty active layer

// Phase 2: build CFG
buildCFG(storage)              // reads frozen AST, writes CFG to active layer
storage.freezeAndPushLayer()

// Phase 3: analysis
analyze(storage)               // reads all frozen layers transparently
```

Frozen layers are immutable — deletion throws `FrozenLayerModificationException`. Active layer supports full CRUD. Use `compactLayers()` to merge frozen layers when the stack exceeds 3-4 layers.

Thread-safe variants: `NativeConcurStorageImpl`, `JgraphtConcurStorageImpl`, `MapDBConcurStorageImpl`, `DeltaConcurStorageImpl`.

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
| `JgraphtStorageImpl` | All in heap | No | No | When JGraphT algorithms are needed |
| `MapDBStorageImpl` | Off-heap | Yes (file) | No | Large graphs exceeding heap, persistence |
| `Neo4jStorageImpl` | Managed by Neo4j | Yes (disk) | Yes | Enterprise features, Cypher queries |
| `DeltaStorageImpl` | Overlay in heap | Via base | No | Incremental analysis, full deletion support |
| `PhasedStorageImpl` | Active layer in heap | Via frozen factory | No | Static analysis pipelines, phase-based freezing |

**MapDB configuration variants:**

| Config | Memory Model | Use Case |
|--------|-------------|----------|
| `{ memoryDB() }` | Heap (serialized) | Testing, small datasets |
| `{ memoryDirectDB() }` | Off-heap direct buffers | Medium graphs, avoid GC |
| `{ tempFileDB() }` | Temp file, no mmap | Default, portable |
| `{ tempFileDB().fileMmapEnableIfSupported() }` | File + mmap | Large graphs, OS page cache |
| `{ fileDB("path").fileMmapEnableIfSupported().readOnly() }` | Read-only mmap | Frozen layers |

## Performance Characteristics

Benchmarks use warmup iterations + median of multiple measured iterations. Scale is measured in node/edge counts.

Run benchmarks:
```shell
./gradlew :graph:test --tests "*.StoragePerformanceTest"
./gradlew :graph:test --tests "*.GraphPerformanceTest"
./gradlew :modules:impl-jgrapht:test --tests "*.JgraphtPerformanceTest"
./gradlew :modules:impl-mapdb:test --tests "*.MapDBPerformanceTest"
./gradlew :modules:impl-neo4j:test --tests "*.Neo4jPerformanceTest"
```

### Throughput guidelines (relative, not absolute)

| Operation | Native | NativeConcur | Delta | Phased | JGraphT | MapDB (memory) | Neo4j |
|-----------|--------|-------------|-------|--------|---------|----------------|-------|
| Node lookup | Fastest | ~20-40% overhead | Fast (2-layer check) | Scales with layers | Comparable | Slower (serialization) | Slowest (transaction) |
| Property read | Fastest | RW lock overhead | Fast (single-layer) / Slower (merge) | Scales with layers | Comparable | Serialization cost | Transaction cost |
| Edge query | Fastest (split in/out index) | RW lock overhead | Merge cost for both layers | Merge across all layers | JGraphT pseudograph lookup | SetVal deserialization | Transaction + cache |
| Population (1M/3M) | Fast | Moderate | ~2x Native | ~1.5x Native | Comparable to Native | Varies by config | 10-100x slower |

### Scaling behavior

- **NativeStorageImpl**: O(1) all operations. Heap-bound — graphs >8GB cause GC pressure.
- **DeltaStorageImpl**: Property merge cost when data spans both layers. Single-layer fast-paths avoid merge. `DeltaConcurStorageImpl` adds RW lock overhead.
- **PhasedStorageImpl**: Query cost grows linearly with layer count. Keep layers ≤ 3-4; use `compactLayers()` to merge. Freezing a layer with 2M nodes + 20M edges takes ~20-40s.
- **MapDB**: `memoryDB()` vs `tempFileDB()` — memory configs faster for reads, file configs handle larger-than-heap data. Mmap configs benefit from OS page cache for repeated access.
- **Neo4j**: Transaction overhead dominates. Scale tests use smaller datasets (1K-10K nodes). Suitable when Neo4j ecosystem features (Cypher, clustering) are needed.

### Memory and disk usage estimates

For a graph with 2M nodes + 20M edges + 5 properties/entity:

| Storage | Heap | Off-heap/Disk | Notes |
|---------|------|---------------|-------|
| NativeStorage | ~11-14 GB | 0 | HashMap overhead + IValue objects |
| PhasedStorage (frozen to MapDB) | Active layer only (~0-3 GB) | ~8-10 GB per frozen layer | Frozen data serialized, more compact |
| MapDB (memoryDB) | ~1-2 GB (serialized on-heap) | 0 | MapDB internal structures |
| MapDB (fileDB + mmap) | <100 MB (OS page cache managed) | ~8-10 GB | Mmap lets OS manage memory |
| Neo4j | Managed | Disk: ~5-15 GB | Depends on Neo4j configuration |

## Capability Boundaries

- **Not a graph algorithm library.** Provides storage and traversal primitives. Use JGraphT algorithms via `JgraphtStorageImpl` or implement algorithms on top of `IGraph`.
- **No distributed mode.** All backends are single-JVM. Neo4j can connect to a cluster, but the `IStorage` contract is local.
- **No schema enforcement.** Properties are `Map<String, IValue>` — no compile-time type checking on property keys or values.
- **No automatic edge cascade on node delete.** `IStorage.deleteNode` does not remove connected edges. The graph layer (`AbcMultipleGraph`) handles cascade; direct storage usage must manage this manually.
- **PhasedStorage frozen layers are immutable.** Cannot delete entities from frozen layers — use `DeltaStorageImpl` if frozen-layer deletion is needed.
- **MapDB serialization cost.** Every property access incurs serialize/deserialize overhead. For traversal-heavy workloads on large graphs, consider NativeStorage with `PhasedStorageImpl` for freezing completed phases.

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
