# JGraphT 1.4.0 — Implementation Notes

Paired designs: `storage.design.md`, `traits/label.design.md`

---

## APIs

**`org.jgrapht.graph.DirectedPseudograph`** `DirectedPseudograph(EdgeID::class.java)` — 有向伪图（允许自环和平行边）；用于 `JgraphtStorageImpl` 主存储。
**`org.jgrapht.graph.SimpleDirectedGraph`** `SimpleDirectedGraph(DefaultEdge::class.java)` — 有向简单图（无自环、无平行边）；用于 `JgraphtLatticeImpl` 格结构。
**`org.jgrapht.Graph`** `addVertex(v)` / `removeVertex(v)` — 顶点操作；`removeVertex` 自动移除所有关联边。`addEdge(src, dst, edge)` / `removeEdge(edge)` — 边操作。`incomingEdgesOf(v)` / `outgoingEdgesOf(v)` — O(1) 返回内部 Set 引用。`getAllEdges(src, dst)` — 返回两顶点间所有边；顶点不存在时返回 `null`（注意 NPE）。`getEdgeSource(e)` / `getEdgeTarget(e)` — 获取边端点。
**`org.jgrapht.nio.gml.GmlExporter`** `GmlExporter()` — 创建导出器；`setVertexAttributeProvider` / `setEdgeAttributeProvider` — 附加属性；`setParameter(Parameter.EXPORT_VERTEX_LABELS, true)` — 启用 label 导出（必须设置）；`exportGraph(graph, file)` — 导出到文件。
**`org.jgrapht.nio.gml.GmlImporter`** `GmlImporter()` — 创建导入器；`addVertexAttributeConsumer` / `addEdgeAttributeConsumer` — 消费属性回调；目标图需设置 `vertexSupplier` / `edgeSupplier`。
**`org.jgrapht.util.SupplierUtil`** `createIntegerSupplier()` — 为 GML IO 提供自增整数 supplier；`createStringSupplier()` — 字符串 supplier。

---

## Libraries

- `org.jgrapht:jgrapht-core:1.4.0` — 图数据结构（DirectedPseudograph, SimpleDirectedGraph）
- `org.jgrapht:jgrapht-io:1.4.0` — 图 IO（GmlExporter, GmlImporter）
- `edu.jhu.cobra:commons-value:0.1.0` — `IValue` 序列化体系，GML IO 使用 `DftCharBufferSerializerImpl`

---

## Developer instructions

- GML 导出使用 `Int` 作为临时顶点/边替代 `NodeID`/`EdgeID`，再通过 attribute provider 附加真实 ID 和属性（避免 GML 格式对复杂键类型的限制）
- GML 导入必须设置 `vertexSupplier` / `edgeSupplier`，否则 `GmlImporter` 无法自动 supply 对象
- `EXPORT_VERTEX_LABELS` / `EXPORT_EDGE_LABELS` 必须显式设置为 `true`，否则 label 字段不写入文件
- `incomingEdgesOf` / `outgoingEdgesOf` 返回内部 Set 引用，修改前需复制

---

## Design-specific

### 性能分析：DirectedPseudograph 内部结构

**来源：** JGraphT 源码 `DirectedSpecifics`、`DirectedEdgeContainer`；deepwiki jgrapht/jgrapht

JGraphT `DirectedPseudograph` 使用 `DirectedSpecifics` 实现，内部结构为 `Map<V, DirectedEdgeContainer<V, E>>`。每个 `DirectedEdgeContainer` 维护独立的 incoming/outgoing `Set<E>`。

| 操作 | 复杂度 | 说明 |
|------|--------|------|
| `incomingEdgesOf(v)` / `outgoingEdgesOf(v)` | O(1) | 直接返回内部 Set 引用（非复制） |
| `getAllEdges(src, dst)` | O(out_degree(src)) | 遍历 src 的出边过滤 target；与手动遍历 `outgoingEdgesOf` 过滤等价 |
| `removeVertex(v)` | O(degree(v)) | 自动删除所有关联边；内部逐边调用 `removeEdgeFromTouchingVertices` |
| `addEdge(src, dst, e)` | O(1) | 插入到 src 的 outgoing set 和 dst 的 incoming set |
| `containsEdge(src, dst)` | O(out_degree(src)) | 遍历 src 的出边查找（默认策略） |

### 性能分析：FastLookupGraphSpecificsStrategy

**来源：** JGraphT 源码 `FastLookupDirectedSpecifics`、`GraphPerformanceTest`；deepwiki jgrapht/jgrapht

JGraphT 提供 `FastLookupGraphSpecificsStrategy`，内部额外维护 `Map<Pair<V,V>, Set<E>>` (`touchingVerticesToEdgeMap`)，实现顶点对间 O(1) 边查找。

| 操作 | DefaultStrategy | FastLookupStrategy |
|------|-----------------|--------------------|
| `getEdge(u, v)` / `containsEdge(u, v)` | O(out_degree(u)) | **O(1)** |
| `getAllEdges(u, v)` | O(out_degree(u)) | **O(1)** |
| `addEdge` | O(1) | O(1) + 额外 map put |
| 内存开销 | 基础 | 额外 `Map<Pair, Set>` |

**启用方式（JGraphT 1.4.0+）：**

```kotlin
val strategy = FastLookupGraphSpecificsStrategy<NodeID, EdgeID>()
val jgtGraph = DirectedPseudograph<NodeID, EdgeID>(
    null, // vertexSupplier
    null, // edgeSupplier
    false, // weighted
    false, // allowMultipleEdges — 实际由 Pseudograph 语义控制
    strategy
)
```

**建议：** 当 `getEdgesBetween` 为高频操作或图稠密时，应启用 `FastLookupGraphSpecificsStrategy`。内存代价为每条边额外一个 Map entry，对于中等规模图可接受。

### 双结构同步

`JgraphtStorageImpl` 维护两套结构：
- `jgtGraph`：JGraphT 内部图，负责拓扑查询（入/出边）
- `nodeProperties` / `edgeProperties`：JVM HashMap，负责属性存储

两者必须始终同步。`containsNode` / `containsEdge` 只查 `nodeProperties`，不校验 JGraphT 是否也存在，两者不一致时无法检测。

### getEdgesBetween 潜在 NPE

`getAllEdges(from, to)` 在顶点不存在时返回 `null`，直接 `.toSet()` 抛 NPE。应在调用前校验节点存在：

```kotlin
override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> {
    if (isClosed) throw AccessClosedStorageException()
    if (from !in nodeProperties) throw EntityNotExistException(from)
    if (to !in nodeProperties) throw EntityNotExistException(to)
    return jgtGraph.getAllEdges(from, to)?.toSet() ?: emptySet()
}
```

### 性能分析：deleteNode 冗余操作

**来源：** JGraphT 源码 `DirectedSpecifics.removeEdgeFromTouchingVertices`

`removeVertex(id)` 在 JGraphT 内部自动移除所有关联边（O(degree)）。当前代码在 `removeVertex` 前手动逐边调用 `deleteEdge`，导致每条关联边被访问两次。

**当前代价：** 2 × O(degree) — 手动删边 O(degree) + removeVertex 内部空操作 O(degree)

**优化做法：** 先取出关联边 ID 仅清理 `edgeProperties`（O(degree) HashMap remove），再 `removeVertex` 让 JGraphT 处理拓扑（O(degree)）。总计 O(degree)，节省一半操作：

```kotlin
override fun deleteNode(id: NodeID) {
    if (!containsNode(id)) throw EntityNotExistException(id)
    val incEdges = jgtGraph.incomingEdgesOf(id).toList()
    val outEdges = jgtGraph.outgoingEdgesOf(id).toList()
    (incEdges + outEdges).forEach { edgeProperties.remove(it) }
    jgtGraph.removeVertex(id)   // JGraphT 内部一并删边
    nodeProperties.remove(id)
}
```
