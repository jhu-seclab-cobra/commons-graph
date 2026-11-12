# commons-graph

> commons-graph names the graph abstractions shared by consuming projects.

A directed property graph library for Kotlin/JVM with pluggable storage and edge labels.

## Install

Use JDK 21 and add JitPack and the core module to a Gradle Kotlin build:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.jhu-seclab-cobra.commons-graph:jhu-seclab-cobra-commons-graph:main-SNAPSHOT")
}
```

Replace `main-SNAPSHOT` with a tagged release version once one is published. Optional storage modules use the same group and version, with artifact names `jhu-seclab-cobra-commons-graph-impl-jgrapht`, `jhu-seclab-cobra-commons-graph-impl-mapdb`, and `jhu-seclab-cobra-commons-graph-impl-neo4j`.

## Usage

```kotlin
import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcMultipleGraph
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

class Node : AbcNode() {
    enum class Kind : AbcNode.Type { DEFAULT }
    override val type = Kind.DEFAULT
}
class Edge : AbcEdge() {
    enum class Kind : AbcEdge.Type { DEFAULT }
    override val type = Kind.DEFAULT
}
class Graph : AbcMultipleGraph<Node, Edge>() {
    override val graphId = "example"
    override val storage = NativeStorageImpl()
    override fun newNodeObj() = Node()
    override fun newEdgeObj() = Edge()
}

fun main() {
    val graph = Graph()
    graph.addNode("a")
    graph.addNode("b")
    graph.addEdge("a", "b", "calls")
    println(graph.getChildren("a").map { it.id }.toList())
}
```

## API

Exported signatures, parameters and return types: [Graph API](docs/llms/graph.md), [Entity API](docs/llms/entity.md), [Storage API](docs/llms/storage.md) and [Label API](docs/llms/label.md).

## Citation

It requests that every project using the library cite the project's associated paper.

```bibtex
@inproceedings{xu2026cobra,
  title     = {CoBrA: Context-, Branch-sensitive Static Analysis for Detecting Taint-style Vulnerabilities in PHP Web Applications},
  author    = {Xu, Yichao and Kang, Mingqing and Thimmaiah, Neil and Gjomemo, Rigel and Venkatakrishnan, V. N. and Cao, Yinzhi},
  booktitle = {Proceedings of the 48th IEEE/ACM International Conference on Software Engineering (ICSE)},
  year      = {2026},
  address   = {Rio de Janeiro, Brazil}
}
```

## Documentation

- [Concepts](docs/concept.md) — scope and graph concepts.
- [Graph design](docs/design-graph.md) — graph interfaces and behavior.
- [Documentation index](docs/index.md) — all library documentation.

## For Agents

Agent-consumable documentation index at [docs/llms.txt](docs/llms.txt) (llmstxt.org format).

## License

GPL-2.0; see [LICENSE](LICENSE).
