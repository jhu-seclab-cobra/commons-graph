# commons-graph

> Common Graph — backend-agnostic graph abstraction for COBRA.

Directed property graph library for Kotlin/JVM with pluggable storage backends.

[![codecov](https://codecov.io/gh/jhu-seclab-cobra/commons-graph/branch/main/graph/badge.svg)](https://codecov.io/gh/jhu-seclab-cobra/commons-graph)
[![JitPack](https://jitpack.io/v/jhu-seclab-cobra/commons-graph.svg)](https://jitpack.io/#jhu-seclab-cobra/commons-graph)
[![License](https://img.shields.io/github/license/jhu-seclab-cobra/commons-graph)](./LICENSE)

## Install

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

```kotlin
val graph = object : AbcMultipleGraph<MyNode, MyEdge>() {
    override val storage = NativeStorageImpl()
    override val posetStorage = NativeStorageImpl()
    override fun newNodeObj() = MyNode()
    override fun newEdgeObj() = MyEdge()
}

val a = graph.addNode("A")                    // returns MyNode (NodeID is String)
val b = graph.addNode("B")
val e = graph.addEdge("A", "B", "calls")      // returns MyEdge
a["weight"] = 42.numVal                        // property access
graph.getChildren("A")                         // Sequence<MyNode>
graph.close()
```

## API

**Core Interfaces**

| Interface | Description |
|-----------|-------------|
| `IGraph<N: AbcNode, E: AbcEdge>` | Domain graph contract. String `NodeID` identifiers. `addNode(withID)`, `addEdge(src, dst, tag)`, traversal queries. |
| `IStorage` | Storage engine contract. Auto-generated `Int` IDs. `addNode(): Int`, `addEdge(src, dst, tag): Int`, adjacency, properties. |
| `IPoset` | Label partial-order contract. Parents, ancestors, `compareTo`. |

**Abstract Classes**

| Class | Description |
|-------|-------------|
| `AbcMultipleGraph` | Implements `IGraph` + `IPoset` + `Closeable`. Label-aware edges. Dual storage (graph + poset). |
| `AbcSimpleGraph` | Extends `AbcMultipleGraph`. Enforces single edge per source-destination direction. |
| `AbcNode` | Lightweight node wrapper. Bound to storage via `bind()`. |
| `AbcEdge` | Lightweight edge wrapper. Resolves source, destination, tag lazily. |

**Storage Implementations**

| Implementation | Persistence | Thread-Safe | Use Case |
|----------------|-------------|-------------|----------|
| `NativeStorageImpl` | No | No | Default in-memory |
| `NativeConcurStorageImpl` | No | Yes | Multi-threaded in-memory |
| `LayeredStorageImpl` | No | No | Phase-based freeze-and-stack |
| `JgraphtStorageImpl` | No | No | JGraphT algorithm access |
| `MapDBStorageImpl` | File | No | Graphs exceeding heap |
| `Neo4jStorageImpl` | Disk | Yes | Enterprise persistence |

**Supporting Types**

| Type | Description |
|------|-------------|
| `Label` | Value class wrapping `String`. `INFIMUM`/`SUPREMUM` sentinels. |
| `TraitNodeGroup` | Node grouping with auto-ID generation. |
| `NodeID` | Typealias for `String`. User-facing node identifier. |

**Exceptions**

| Exception | Trigger |
|-----------|---------|
| `EntityNotExistException` | Operation on missing node or edge |
| `EntityAlreadyExistException` | Duplicate node or edge creation |
| `InvalidPropNameException` | Reserved or invalid property name |
| `AccessClosedStorageException` | Operation on closed storage |
| `FrozenLayerModificationException` | Write to frozen layer |

## Documentation

- [Concepts and terminology](docs/idea.md) — domain model, identity scheme, layered storage, label hierarchy.

## Citation

If you use this repository in your research, please cite our paper:

```bibtex
@inproceedings{xu2026cobra,
  title     = {CoBrA: Context-, Branch-sensitive Static Analysis for Detecting Taint-style Vulnerabilities in PHP Web Applications},
  author    = {Xu, Yichao and Kang, Mingqing and Thimmaiah, Neil and Gjomemo, Rigel and Venkatakrishnan, V. N. and Cao, Yinzhi},
  booktitle = {Proceedings of the 48th IEEE/ACM International Conference on Software Engineering (ICSE)},
  year      = {2026},
  address   = {Rio de Janeiro, Brazil}
}
```

## License

[GNU General Public License v2.0](./LICENSE)
