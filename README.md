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

Benchmarks use warmup iterations (JIT stabilization) + median of multiple measured iterations. Scale is measured in node/edge counts. Results below were measured on Apple M4 Pro / JDK 8 / 8 GB test heap. Absolute numbers vary by hardware; relative comparisons between backends are stable.

Run benchmarks:
```shell
./gradlew :graph:test --tests "*.StoragePerformanceTest"
./gradlew :graph:test --tests "*.GraphPerformanceTest"
./gradlew :modules:impl-jgrapht:test --tests "*.JgraphtPerformanceTest"
./gradlew :modules:impl-mapdb:test --tests "*.MapDBPerformanceTest"
./gradlew :modules:impl-neo4j:test --tests "*.Neo4jPerformanceTest"
```

### Core storage — Graph population (median ms)

| Implementation | 10K/30K | 100K/300K | 1M/3M |
|----------------|--------:|----------:|------:|
| NativeStorageImpl | 5.8 | 99.4 | 2,345 |
| NativeConcurStorageImpl | 6.0 | 97.7 | 2,299 |
| DeltaStorageImpl | 7.3 | 111.1 | 2,541 |
| DeltaConcurStorageImpl | 7.7 | 128.6 | 2,544 |
| PhasedStorageImpl | 6.9 | 107.4 | 2,588 |

### Core storage — Operation throughput (median ops/sec)

| Operation | Native | NativeConcur | Delta | DeltaConcur | Phased |
|-----------|-------:|-------------:|------:|------------:|-------:|
| Node add (1M) | 6.25M | 6.28M | 5.93M | 5.73M | 6.06M |
| Edge add (1M) | 2.84M | 2.84M | 2.08M | 2.12M | 2.24M |
| Node lookup (500K on 100K) | 58.1M | 50.8M | 55.0M | 46.6M | 53.5M |
| Property read (200K on 50K) | 14.3M | 20.3M | 15.4M | 13.0M | 12.7M |
| Property write (200K on 50K) | 18.0M | 17.1M | 11.1M | 10.1M | 14.8M |
| Edge query — outgoing | 42.8M | 17.2M | 4.20M | 3.57M | 4.15M |
| Edge query — incoming | 43.8M | 17.3M | 4.30M | 4.28M | 4.20M |
| Node delete (2K from 10K) | 1.89M | 1.90M | 2.10M | 2.01M | 2.19M |

### Delta storage — Base vs overlay lookup (200K lookups on 50K nodes)

| Scenario | DeltaStorage | DeltaConcur |
|----------|-------------:|------------:|
| Base-only | 20.4M | 17.0M |
| Overlay-only | 21.8M | 19.2M |
| Both-layers | 7.61M | 7.12M |

### Phased storage — Multi-layer query (100K queries, 10K nodes/layer)

| Layers | containsNode | getNodeProperties |
|-------:|-------------:|------------------:|
| 1 | 35.9M | 15.5M |
| 3 | 16.5M | 8.72M |
| 5 | 13.8M | 6.09M |
| 10 | 7.12M | 3.72M |

### JGraphT backend

| Operation | JgraphtStorage | JgraphtConcur |
|-----------|---------------:|--------------:|
| Population 1M/3M (ms) | 6,525 | 6,181 |
| Node lookup (500K on 100K) | 35.3M | 52.8M |
| Property read (200K on 50K) | 27.7M | 13.8M |
| Property write (200K on 50K) | 21.8M | 9.64M |
| Edge query — outgoing | 5.79M | 4.40M |
| Edge query — incoming | 5.17M | 4.41M |

### MapDB backend — Config comparison (5K nodes / 15K edges)

| Config | Population (ms) | Node lookup | Prop read | Prop write | Edge query (out) |
|--------|----------------:|------------:|----------:|-----------:|-----------------:|
| MapDB[memoryDB] | 200 | 2.07M | 1.49M | 136K | 374K |
| MapDB[memoryDirectDB] | 200 | 1.58M | 1.51M | 137K | 378K |
| MapDB[tempFileDB] | 11,471 | 47.6K | 45.4K | 1.7K | 22.5K |
| MapDB[tempFile+mmap] | 216 | 1.55M | 1.45M | 126K | 372K |
| MapDBConcur[memoryDB] | 207 | 1.50M | 369K | 130K | 372K |
| MapDBConcur[memDirect] | 206 | 1.50M | 374K | 131K | 362K |
| MapDBConcur[tempFile] | 11,487 | 47.5K | 11.4K | 1.7K | 22.6K |
| MapDBConcur[tmpFile+mm] | 222 | 1.52M | 376K | 123K | 367K |

**Key MapDB findings**: `tempFileDB()` without mmap is 50-100x slower than memory/mmap configs. Always use `fileMmapEnableIfSupported()` for file-backed storage. Memory and mmap configs perform comparably.

### Neo4j backend

| Operation | Neo4jStorageImpl |
|-----------|----------------:|
| Population 10K/30K (ms) | 1,385 |
| Node lookup (20K on 5K) | 19.1M |
| Property read (10K on 2K) | 418K |
| Property write (10K on 2K) | 36.0K |
| Edge query — outgoing | 310K |
| Edge query — incoming | 315K |

### Graph-level queries (100K queries on 10K nodes / 50K edges)

| Storage | getNode | getOutEdges | getChildren | getDescendants |
|---------|--------:|------------:|------------:|---------------:|
| NativeStorage | 21.9M | 3.41M | 2.35M | 303K |
| NativeConcurStorage | 19.6M | 3.49M | 2.65M | 133K |
| DeltaStorage | 23.1M | 2.92M | 2.21M | 127K |
| PhasedStorage | 24.3M | 2.76M | 2.11M | 573K |

### Lattice operations (5K nodes / 15K edges, 5-label hierarchy)

| Storage | Label assignment (ms) | Filtered query (ops/sec) | storeLattice (ms) |
|---------|---------:|------------------------:|---------:|
| NativeStorage | 5.1 | 950K | 0.9 |
| NativeConcurStorage | 3.8 | 1.24M | 0.9 |
| DeltaStorage | 3.7 | 1.01M | 0.5 |
| PhasedStorage | 3.9 | 882K | 0.5 |

### Cross-backend comparison — Node lookup (ops/sec)

| Backend | Scale | ops/sec |
|---------|-------|--------:|
| NativeStorageImpl | 500K lookups on 100K nodes | 58,140,000 |
| NativeConcurStorageImpl | 500K lookups on 100K nodes | 50,780,000 |
| DeltaStorageImpl | 500K lookups on 100K nodes | 55,030,000 |
| PhasedStorageImpl | 500K lookups on 100K nodes | 53,530,000 |
| JgraphtStorageImpl | 500K lookups on 100K nodes | 35,340,000 |
| JgraphtConcurStorageImpl | 500K lookups on 100K nodes | 52,830,000 |
| MapDBStorageImpl [memoryDB] | 50K lookups on 5K nodes | 2,070,000 |
| MapDBStorageImpl [tempFile+mmap] | 50K lookups on 5K nodes | 1,550,000 |
| MapDBStorageImpl [tempFileDB] | 50K lookups on 5K nodes | 47,600 |
| Neo4jStorageImpl | 20K lookups on 5K nodes | 19,100,000 |

### Cross-backend comparison — Property read (ops/sec)

| Backend | Scale | ops/sec |
|---------|-------|--------:|
| NativeStorageImpl | 200K ops on 50K nodes | 14,280,000 |
| NativeConcurStorageImpl | 200K ops on 50K nodes | 20,280,000 |
| DeltaStorageImpl | 200K ops on 50K nodes | 15,410,000 |
| PhasedStorageImpl | 200K ops on 50K nodes | 12,670,000 |
| JgraphtStorageImpl | 200K ops on 50K nodes | 27,650,000 |
| JgraphtConcurStorageImpl | 200K ops on 50K nodes | 13,830,000 |
| MapDBStorageImpl [memoryDB] | 20K ops on 5K nodes | 1,490,000 |
| MapDBStorageImpl [tempFile+mmap] | 20K ops on 5K nodes | 1,450,000 |
| MapDBStorageImpl [tempFileDB] | 20K ops on 5K nodes | 45,400 |
| Neo4jStorageImpl | 10K ops on 2K nodes | 418,400 |

### Cross-backend comparison — Edge query outgoing (ops/sec)

| Backend | Scale | ops/sec |
|---------|-------|--------:|
| NativeStorageImpl | 100K queries on 10K nodes | 42,750,000 |
| NativeConcurStorageImpl | 100K queries on 10K nodes | 17,220,000 |
| DeltaStorageImpl | 100K queries on 10K nodes | 4,200,000 |
| PhasedStorageImpl | 100K queries on 10K nodes | 4,150,000 |
| JgraphtStorageImpl | 100K queries on 10K nodes | 5,790,000 |
| JgraphtConcurStorageImpl | 100K queries on 10K nodes | 4,400,000 |
| MapDBStorageImpl [memoryDB] | 10K queries on 2K nodes | 374,400 |
| MapDBStorageImpl [tempFile+mmap] | 10K queries on 2K nodes | 371,600 |
| MapDBStorageImpl [tempFileDB] | 10K queries on 2K nodes | 22,500 |
| Neo4jStorageImpl | 10K queries on 2K nodes | 310,000 |

### Cross-backend comparison — Graph population (median ms)

| Backend | 10K nodes / 30K edges | 100K nodes / 300K edges | 1M nodes / 3M edges |
|---------|----------------------:|------------------------:|--------------------:|
| NativeStorageImpl | 5.8 | 99.4 | 2,345 |
| NativeConcurStorageImpl | 6.0 | 97.7 | 2,299 |
| DeltaStorageImpl | 7.3 | 111.1 | 2,541 |
| DeltaConcurStorageImpl | 7.7 | 128.6 | 2,544 |
| PhasedStorageImpl | 6.9 | 107.4 | 2,588 |
| JgraphtStorageImpl | 17.0 | 201.9 | 6,525 |
| JgraphtConcurStorageImpl | 12.0 | 199.9 | 6,181 |
| MapDBStorageImpl [memoryDB] | — | — | — |
| Neo4jStorageImpl | — | — | 1,385 (10K/30K only) |

MapDB population: 5K nodes / 15K edges — memoryDB: 200 ms, tempFileDB: 11,471 ms, tempFile+mmap: 216 ms.
Neo4j population: 1K/3K: 195 ms, 5K/15K: 703 ms, 10K/30K: 1,385 ms.

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
