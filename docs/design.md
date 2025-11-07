## commons-graph — 设计文档

本文件按照项目的设计文档标准编写，目标是定义 commons-graph 的核心抽象、公开 API（Kotlin 风格签名）、行为契约、异常与验证规则，并给出简要示例。

注意：仓库中存在多个扩展（jgrapht、neo4j、mapdb 等），本设计聚焦于核心图抽象与算法/查询 API，扩展模块应基于此接口实现适配器。

## 约定与假设
- 语言风格：Kotlin 风格的接口与签名，但实现可为 Java/Kotlin。\
- 泛型：Graph 使用泛型参数 `<V, E>` 分别代表顶点类型与边类型（边类型通常封装源/目标/权重/标签信息）。\
- 可变性：区分只读图接口 `Graph` 与可变图接口 `MutableGraph`。\
- 并发：核心接口不含线程安全承诺；需要时由实现者通过包装或并发实现提供。\
- 兼容性假设：不修改现有代码（除非用户额外授权）；本文档可用于指导实现或重构。

## API 概览
- Graph<V, E> — 只读图抽象（查询视图）\
- MutableGraph<V, E> — 可修改图（顶点/边的添加、删除）\
- Edge<V> / WeightedEdge / LabeledEdge — 边的通用表示\
- GraphBuilder<V, E> — 图构建器/工厂参数\
- GraphFactory — 工厂方法创建图实例\
- Traversal / Search / ShortestPath — 遍历与最短路径算法的统一接口\
- Path<V, E> — 表示路径（顶点序列、边序列、代价）\
- SubgraphView — 基于谓词的子图视图\
- GraphQuery — 简洁查询工具（基于谓词）\

---

## Class & Interface 文档

### Graph<V, E> 接口
- Responsibility: 提供图的只读查询 API
- Properties: 无固定字段（实现决定）
- [vertices(): Set<V>]
	- Behavior: 返回图中所有顶点的不可变集合
	- Input: 无
	- Output: `Set<V>` — 顶点集合
	- Raises: 无
- [edges(): Set<E>]
	- Behavior: 返回图中所有边的不可变集合
	- Input: 无
	- Output: `Set<E>` — 边集合
	- Raises: 无
- [outgoingEdges(v: V): Set<E>]
	- Behavior: 返回从顶点 v 发出的所有边
	- Input: v: V — 顶点
	- Output: `Set<E>` — 边集合（空集合表示无出边）
	- Raises: `VertexNotFoundException` 当 v 不存在于图中
- [incomingEdges(v: V): Set<E>]
	- Behavior: 返回指向顶点 v 的所有边
	- Input: v: V
	- Output: `Set<E>`
	- Raises: `VertexNotFoundException`
- [neighbors(v: V): Set<V>]
	- Behavior: 返回与 v 相邻（通过出边或入边）的顶点集合
	- Input: v: V
	- Output: `Set<V>`
	- Raises: `VertexNotFoundException`
- [hasVertex(v: V): Boolean]
	- Behavior: 判断顶点 v 是否存在
	- Input: v: V
	- Output: `Boolean`
- [hasEdge(e: E): Boolean]
	- Behavior: 判断边实例 e 是否存在于图中
	- Input: e: E
	- Output: `Boolean`
- [getEdge(u: V, v: V): E?]
	- Behavior: 如果存在从 u 到 v 的单条（或首条）边则返回，否则返回 null。对于并行边语义由实现决定（可返回任一或抛出 `AmbiguousEdgeException`）
	- Input: u: V, v: V
	- Output: `E?`
	- Raises: `VertexNotFoundException` 若 u 或 v 不存在；`AmbiguousEdgeException` 若并行边不可区分
- [degree(v: V): Int]
	- Behavior: 返回顶点的度（有向图可解释为出度+入度或由实现另有 API outDegree/inDegree）
	- Input: v: V
	- Output: `Int`
	- Raises: `VertexNotFoundException`

Example Usage:
```
val g: Graph<String, Edge<String>> = GraphFactory.createDirected<String, Edge<String>>()
val vs = g.vertices()
val e = g.getEdge("A", "B")
```

### MutableGraph<V, E> 接口 : Graph<V, E>
- Responsibility: 在 Graph 的基础上提供变更（增删顶点/边）操作
- [addVertex(v: V): Boolean]
	- Behavior: 如果顶点不存在则添加并返回 true；否则返回 false
	- Input: v: V
	- Output: `Boolean` — true 表示添加成功
	- Raises: `IllegalArgumentException` 当 v 为 null（如果类型可空则另议）
- [removeVertex(v: V): Boolean]
	- Behavior: 移除顶点及其相关边，返回是否实际移除
	- Input: v: V
	- Output: `Boolean`
	- Raises: `VertexNotFoundException` 若不存在（可由实现选择返回 false 替代抛出）
- [addEdge(e: E): Boolean]
	- Behavior: 将边加入图；若端点不存在，行为受 `createMissingVertices` 配置影响（参见 `GraphBuilder`）
	- Input: e: E
	- Output: `Boolean` — true 表示新边已添加
	- Raises: `InvalidEdgeException`，`DuplicateEdgeException`（若禁止并行边）
- [removeEdge(e: E): Boolean]
	- Behavior: 移除边
	- Input: e: E
	- Output: `Boolean`
	- Raises: `EdgeNotFoundException` 当指定边不存在（实现可选择返回 false）
- [clear()]
	- Behavior: 移除所有顶点与边
	- Input: 无
	- Output: Unit
	- Raises: 无

示例：
```
val mg: MutableGraph<Int, DefaultEdge<Int>> = GraphFactory.createMutable(directed = true)
mg.addVertex(1)
mg.addVertex(2)
mg.addEdge(DefaultEdge(1, 2, weight = 3.0))
```

### Edge<V> (接口或数据类)
- Responsibility: 表示一条图中的边，包含端点信息与可选元数据
- Properties:
	- `source: V` — 源顶点
	- `target: V` — 目标顶点
	- `directed: Boolean` — 是否有向（实现时可作为类型层级）
	- `meta: Map<String, Any>` — 可选的元数据
- [weight: Double?]（可选，通过 `WeightedEdge` 子类型暴露）

示例数据类签名（Kotlin 样式）：
```
data class DefaultEdge<V>(
	val source: V,
	val target: V,
	val directed: Boolean = true,
	val weight: Double? = null,
	val label: String? = null,
	val meta: Map<String, Any> = emptyMap()
)
```

### WeightedEdge<V> : Edge<V>
- Responsibility: 明确支持权重的边表示
- Properties: 同 Edge，`weight: Double` 必须为有限数值
- Raises: `InvalidEdgeException` 当权重为 NaN/Infinity

### GraphBuilder<V, E>
- Responsibility: 提供图实例化配置（建造者模式）
- Properties/配置字段（示例）:
	- `directed: Boolean = true` — 默认有向
	- `allowParallelEdges: Boolean = false`
	- `allowSelfLoops: Boolean = false`
	- `createMissingVertices: Boolean = false` — 当添加边而端点不存在时是否自动创建顶点
	- `edgeFactory: (u: V, v: V) -> E` — 默认边构造器
	- `initialCapacityVertices: Int = 16`
	- `initialCapacityEdges: Int = 32`

方法签名示例：
```
interface GraphBuilder<V, E> {
	fun directed(d: Boolean): GraphBuilder<V, E>
	fun allowParallelEdges(allow: Boolean): GraphBuilder<V, E>
	fun allowSelfLoops(allow: Boolean): GraphBuilder<V, E>
	fun createMissingVertices(enable: Boolean): GraphBuilder<V, E>
	fun edgeFactory(factory: (V, V) -> E): GraphBuilder<V, E>
	fun buildMutable(): MutableGraph<V, E>
	fun buildReadonly(): Graph<V, E>
}
```

示例：
```
val builder = GraphBuilder<String, DefaultEdge<String>>()
	.directed(true)
	.allowParallelEdges(false)
	.createMissingVertices(true)
val graph = builder.buildMutable()
```

### GraphFactory
- Responsibility: 提供常用图实例的工厂方法（便于测试与快速构建）
- 方法签名示例：
```
object GraphFactory {
	fun <V, E> createDirected(edgeFactory: (V, V) -> E): MutableGraph<V, E>
	fun <V, E> createUndirected(edgeFactory: (V, V) -> E): MutableGraph<V, E>
	fun <V, E> createReadOnlyDirected(edgeFactory: (V, V) -> E): Graph<V, E>
}
```

---

### Traversal 与算法 API

#### Traversal<V>
- Responsibility: 提供图遍历（BFS、DFS）抽象与回调钩子
- 方法示例：
```
interface Traversal<V> {
	fun bfs(start: V, visitor: (v: V) -> Unit)
	fun dfs(start: V, visitor: (v: V) -> Unit)
}
```

#### ShortestPath / ShortestPathAlgorithm<V, E>
- Responsibility: 抽象最短路径计算，支持可插拔算法（Dijkstra、Bellman-Ford、A*）
- 签名示例：
```
interface ShortestPathAlgorithm<V, E> {
	fun shortestPath(graph: Graph<V, E>, source: V, target: V): Path<V, E>?
	fun shortestPathsFrom(graph: Graph<V, E>, source: V): Map<V, Path<V, E>>
}
```

实现提示：Dijkstra 需边权为非负；Bellman-Ford 处理负权并检测负环并抛出 `NegativeCycleException`。

#### Path<V, E>
- Responsibility: 表示一条路径
- Properties:
	- `vertices: List<V>`
	- `edges: List<E>`
	- `totalWeight: Double` — 权重总和（若不可用则为 Double.POSITIVE_INFINITY 或 0，视上下文）

---

### SubgraphView<V, E>
- Responsibility: 提供一个基于谓词的图视图，不复制底层数据结构（视图应反映原图的变更）
- 签名示例：
```
interface SubgraphView<V, E> : Graph<V, E> {
	val base: Graph<V, E>
	val vertexPredicate: (V) -> Boolean
	val edgePredicate: (E) -> Boolean
}
```

行为：对基图的更改应当反映在视图中（除非实现做了副本）。

---

### GraphQuery
- Responsibility: 常用查询工具集合（以谓词为基础），便于链式/函数式使用
- 示例方法：
```
interface GraphQuery<V, E> {
	fun findVertices(graph: Graph<V, E>, predicate: (V) -> Boolean): Set<V>
	fun findEdges(graph: Graph<V, E>, predicate: (E) -> Boolean): Set<E>
	fun neighborsOf(graph: Graph<V, E>, v: V, depth: Int = 1): Set<V>
	fun inducedSubgraph(graph: Graph<V, E>, vertices: Set<V>): Graph<V, E>
}
```

---

## Exception 类
- VertexNotFoundException(message: String)
	- When: 在查询/修改中引用了不存在的顶点且实现选择抛出而非返回布尔值
- EdgeNotFoundException(message: String)
	- When: 移除或查询的边不存在
- DuplicateVertexException(message: String)
	- When: 尝试添加重复顶点而实现选择以异常方式拒绝
- DuplicateEdgeException(message: String)
	- When: 添加并行边且实现禁止并行边
- InvalidEdgeException(message: String)
	- When: 边的端点无效或权重非法（NaN/Infinity）
- AmbiguousEdgeException(message: String)
	- When: 在并行边存在时请求单一边但无法决断
- NegativeCycleException(message: String)
	- When: 最短路径算法检测到负权回路

示例（Kotlin 风格）：
```
class VertexNotFoundException(message: String) : RuntimeException(message)
class EdgeNotFoundException(message: String) : RuntimeException(message)
class DuplicateEdgeException(message: String) : RuntimeException(message)
class InvalidEdgeException(message: String) : RuntimeException(message)
```

## 验证规则（Validation Rules）
- 顶点验证：顶点引用不得为 null（若泛型允许则另议），`hasVertex` 判断应为常量时间或接近常量时间由实现保证。\
- 边验证：边的 `source` 与 `target` 必须存在于图中，除非 `createMissingVertices=true`。\
- 权重验证：边权重必须为有限数值（非 NaN、非 Infinity）。\
- Self-loop/Parallel edges：当 `allowSelfLoops=false` 或 `allowParallelEdges=false` 时，相应的添加操作应被拒绝（返回 false 或抛出异常，取决于实现策略）。\
- 并发修改：迭代器对于并发修改的行为由实现决定（文档中应明确抛出 ConcurrentModificationException 或提供弱一致视图）。

## 设计变更策略（摘录/补充）
- 遵循仓库的 Design Change Policy：不在未经明确授权的情况下修改既有设计；文档以接口为中心，扩展实现应保持向后兼容。

## 常见边界与异常情况（Edge Cases）
- 空图（0 顶点）行为：`vertices()` 返回空集合；遍历起点不存在时抛出 `VertexNotFoundException`。\
- 大图：实现应提供可定制的初始容量以减少 rehash/内存分配。\
- 不可比较顶点：路径/优先队列算法需用户提供启发/权重提取器或可比器。\
- 权重为负：Dijkstra 不适用，文档与实现需在运行时检测并抛出 `IllegalArgumentException` 或使用 Bellman-Ford。

## 示例用法（综合）
```
// 构建一个有向带权图
val builder = GraphBuilder<String, DefaultEdge<String>>()
	.directed(true)
	.allowParallelEdges(false)
	.createMissingVertices(true)

val g = builder.buildMutable()
g.addEdge(DefaultEdge("A", "B", weight = 2.0))
g.addEdge(DefaultEdge("B", "C", weight = 1.0))

val dijkstra: ShortestPathAlgorithm<String, DefaultEdge<String>> = Dijkstra()
val path = dijkstra.shortestPath(g, "A", "C")
println(path?.vertices)
```

## 扩展与实现提示
- 为了适配 `extension-neo4j`、`extension-jgrapht` 等模块，建议核心接口保持最小且稳定：Graph/MutableGraph/Edge/Path/ShortestPathAlgorithm/Traversal 即可覆盖大部分需求。\
- 实现应提供桥接适配器（adapter）负责将第三方实现的 API 映射到本项目核心接口。\
- 性能注意：对于高性能场景，提供可选择的原地实现（例如基于开源高性能容器或内存映射存储）并在工厂方法中暴露。

## 完成验证
- 本文档遵循仓库 `docs/*.md` 的格式要求，包含类文档模板条目、异常与验证规则。如需把这些接口映射到现有代码，请授权我在 `graph/src/main/kotlin` 下对接口进行草案实现或把现有类符号导入到文档中。

---

已依据仓库 `requirements.instructions.md` 中的规范编写。如需更精细的 API（例如强类型边子类、事件监听器、并发语义或序列化策略），请指出优先级与目标实现。 
