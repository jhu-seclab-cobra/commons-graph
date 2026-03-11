# 性能瓶颈分析与优化方案

目标：降低查询/写入延迟，降低内存占用。

---

## 瓶颈 1：EdgeID 对象开销

**位置**：`EdgeID` 是 `data class(srcNid: NodeID, dstNid: NodeID, eType: String)`。

每条边在内存中存在于 3 个位置：
- `edgeProperties` 的 key（1 个 EdgeID）
- `outEdges[src]` 的 HashSet（1 个 EdgeID 引用 + HashSet.Node 包装）
- `inEdges[dst]` 的 HashSet（1 个 EdgeID 引用 + HashSet.Node 包装）

**内存估算（3M 条边）**：
- EdgeID 对象本体：对象头 16B + 2×NodeID 引用 8B + String 引用 8B + lazy asString 8B + lazy serialize 8B ≈ 48B
- 3M × 48B = 144MB（仅 EdgeID 对象）
- outEdges + inEdges 的 HashSet.Node 包装：3M × 2 × 32B = 192MB
- edgeProperties 的 HashMap.Node：3M × 32B = 96MB
- **EdgeID 相关总计：3M 条边约 ~430MB**

**优化方案**：

方案 A — EdgeID 驻留池（intern pool）：
- 工厂方法对相同 src+dst+type 的 EdgeID 去重，返回同一实例
- 减少邻接集合与属性 Map 中的重复对象

方案 B — 整数化边索引：
- 内部使用 `Int edgeSeqId` 索引边，维护 `EdgeID↔Int` 映射表
- 邻接表使用 `IntOpenHashSet`（无装箱，无 HashSet.Node）
- 邻接结构从 `HashSet<EdgeID>`（32B/entry）→ `IntOpenHashSet`（~4B/entry）
- 3M × 2 × (32→4) = 192MB → 24MB，节省 ~170MB

---

## 瓶颈 2：每个实体独立的 MutableMap 属性存储

**位置**：`NativeStorageImpl.kt:19` — `nodeProperties: Map<NodeID, MutableMap<String, IValue>>`

每个节点/边各自持有一个 `MutableMap<String, IValue>`：
- 1M 节点 × 空 LinkedHashMap（初始 16 槽 × 8B + 对象头 ≈ 180B）= 180MB 纯结构开销
- IValue 对象：`NumVal(core: Number)` — Number 装箱 16B + NumVal 对象头 16B = 32B/属性值
- `StrVal(core: String)` — String 40B + StrVal 16B = 56B/属性值
- 1M 节点 × 5 属性 × ~40B 均值 = 200MB 属性值
- 1M 节点 × 5 属性 × HashMap.Node 32B = 160MB HashMap 结构
- **总计：1M 节点 × 5 属性约 ~540MB**

**优化方案**：

方案 A — 属性打包存储（ByteArray）：
- 将同一实体的所有属性序列化为单个 `ByteArray`
- 1M 节点 × 1 个 ByteArray ≈ 30MB（对比 540MB）
- 代价：每次属性访问需要反序列化

方案 B — 列式属性存储：
- 按属性名分列存储：`Map<String, Map<EntityID, IValue>>`
- `"type"` → `{n0: StrVal("entry"), n1: StrVal("exit"), ...}`
- 消除每个实体独立 HashMap 的开销；适合属性名固定的场景

方案 C — 固定 Schema 下的数组存储：
- 注册属性 Schema：`["type"→0, "weight"→1, ...]`
- 每个实体存 `Array<IValue?>(schema.size)` 替代 HashMap
- 数组 4B/槽位 vs HashMap 32B/entry → 约 7 倍节省

---

## 瓶颈 3：Graph 层每次查询创建包装对象

**位置**：`AbcMultipleGraph.kt:147` — `getNode` 每次调用 `newNodeObj(nid)` 分配新对象。

```kotlin
override fun getNode(whoseID: NodeID): N? {
    if (whoseID !in nodeIDs || !storage.containsNode(whoseID)) return null
    return newNodeObj(nid = whoseID)  // 每次调用都分配新对象
}
```

`getOutgoingEdges`（209-216 行）对每条边调用 `newEdgeObj(it)`。遍历 1000 条边 = 1000 个短生命周期对象 → Young Gen GC 压力。

**优化方案**：

方案 A — Node/Edge 对象缓存池：
- `WeakHashMap<NodeID, N>` / `WeakHashMap<EdgeID, E>` 缓存已创建对象
- 重复 `getNode(同一 id)` 返回同一实例
- 遍历密集场景减少约 80% 的对象分配

方案 B — 轻量级 Flyweight 游标模式：
- Node/Edge 对象无状态（仅持有 storage 引用 + id）
- 复用单个可变游标对象（单线程遍历下不安全但极快）

---

## 瓶颈 4：DeltaStorage 跨层查询倍增

**位置**：`DeltaStorageImpl.kt:56-59` — `containsNode` 需要检查 3 个数据结构：

```kotlin
fun containsNode(id: NodeID): Boolean {
    if (deletedNodesHolder.contains(id)) return false    // 第 1 次检查
    return presentDelta.containsNode(id) ||               // 第 2 次检查
           baseDelta.containsNode(id)                     // 第 3 次检查
}
```

基准数据：双层查找 7.61M vs 单层 20.4M（**2.7 倍减速**）。

`getNodeProperties` 在双层情况下更差 — 需要从两个 HashMap 读取并通过 `putAll` 合并到新 HashMap。

**优化方案**：

方案 A — containsNode 位图缓存：
- 维护 `BitSet`（或布隆过滤器）标记节点在 base 层还是 present 层
- `containsNode`：O(1) 位检查替代两次 `HashMap.containsKey`
- 双层 containsNode 性能接近单层

方案 B — getNodeProperties 延迟合并 Map：
- 返回 `LazyMergedMap(baseMap, presentMap)` 实现 `Map` 接口
- 查找时先查 present，再查 base — 无需创建新 HashMap
- 仅在遍历所有 key 时才物化
- 单属性访问：O(base.size + present.size) → O(1)

---

## 瓶颈 5：PhasedStorage 线性扫描所有冻结层

**位置**：`PhasedStorageImpl.kt:125-129` — `containsNode` 遍历所有冻结层：

```kotlin
fun containsNode(id: NodeID): Boolean {
    if (activeLayer.containsNode(id)) return true
    return frozenLayers.asReversed().any { it.containsNode(id) }
}
```

基准数据：1 层 35.9M → 10 层 7.12M（**5 倍减速**）。

`getNodeProperties` 更差 — 遍历所有层并逐层合并。

**优化方案**：

方案 A — 全局 `NodeID→layerIndex` 映射：
- `HashMap<NodeID, Int>` 记录每个节点首次出现在哪一层
- `containsNode`：O(1) HashMap 查找
- `getNodeProperties`：直接跳到正确层，无需遍历

方案 B — 冻结时合并属性到最新冻结层：
- `freezeAndPushLayer` 时，如果节点存在于多个冻结层，将属性合并到最顶层冻结层
- 查询时只需检查 activeLayer + 最新冻结层
- 查询复杂度：O(层数) → O(1)

---

## 瓶颈 6：分层存储边查询分配中间 HashSet

**位置**：`DeltaStorageImpl.kt:174-183` — 每次边查询创建并填充新 HashSet：

```kotlin
fun getIncomingEdges(id: NodeID): Set<EdgeID> {
    val base = baseDelta.getIncomingEdges(id)
    val present = presentDelta.getIncomingEdges(id)
    val result = HashSet<EdgeID>(base.size + present.size)  // 分配！
    result.addAll(base)
    result.addAll(present)
    result.removeAll(deletedEdgesHolder)
    return result
}
```

基准数据：Native 42.8M → Delta 4.2M（**10 倍减速**），主要来自 HashSet 分配 + 合并。

**优化方案**：

方案 A — 返回视图而非拷贝：
- 自定义 `ConcatenatedSet<T>(set1, set2, exclusions)` 实现 `Set` 接口
- `iterator()` 串联两个 set 并跳过 exclusions
- 零分配；遍历时才惰性计算
- 边查询：O(degree) 分配 → O(1) 分配

方案 B — 针对 PhasedStorage（无 deleted holders）：
- 如果边仅存在于一层，直接返回该层 Set 引用（零拷贝）
- 多层合并时使用 `ConcatenatedSet`

---

## 瓶颈 7：NodeID 字符串重复

**位置**：`NodeID` 是 `data class(val name: String)`。

每个 EdgeID 持有 `srcNid: NodeID` 和 `dstNid: NodeID`。如果节点 "A" 有 100 条出边，则存在 100 个 `EdgeID.srcNid == NodeID("A")` 的独立实例，每个都单独分配。

**优化方案 — NodeID 驻留池（intern pool）**：
- 全局 `ConcurrentHashMap<String, NodeID>` 缓存
- `NodeID.of("A")` 始终返回同一实例
- 1M 节点 × 平均 3 条边/节点 → EdgeID 中的 6M 个 NodeID 引用从 6M 个对象降为 1M 个
- 节省约 5M × 40B = 200MB

---

## 瓶颈 8：AbcMultipleGraph 冗余双重存在性检查

**位置**：`AbcMultipleGraph.kt:150`：

```kotlin
fun containNode(whoseID: NodeID): Boolean =
    nodeIDs.contains(whoseID) && storage.containsNode(whoseID)
```

Graph 层独立维护 `nodeIDs: MutableSet<NodeID>` 和 `edgeIDs: MutableSet<EdgeID>`，与 storage 分离。这意味着：
- Graph 的 Set 中额外存储 1M 个 NodeID 引用（与 storage 冗余）
- 每次 `containNode` 查两次 HashMap

**优化方案**：
- 移除 Graph 层的 `nodeIDs`/`edgeIDs` Set，直接委托给 `storage.containsNode/containsEdge`
- 或仅保留 `BitSet` / 布隆过滤器做快速拒绝
- 内存：ID 跟踪开销减半；查询速度约 2 倍提升

---

## 优先级矩阵

| 优先级 | 优化项 | 影响范围 | 内存节省 | 查询提升 | 实现复杂度 |
|--------|-------|---------|---------|---------|-----------|
| **P0** | NodeID 驻留池 | 全局 | ~200MB / 3M 边 | 间接（减少 GC） | 低 |
| **P0** | 边查询返回视图而非拷贝 | Delta/Phased | — | 2-5 倍 | 中 |
| **P1** | EdgeID 整数化 + IntSet 邻接 | 全局 | ~170MB / 3M 边 | 间接 | 高 |
| **P1** | PhasedStorage node→layer 索引 | PhasedStorage | — | 3-5 倍（10 层） | 低 |
| **P1** | DeltaStorage 延迟合并属性 | DeltaStorage | — | 2-3 倍（双层） | 中 |
| **P2** | 属性打包/列式存储 | 全局 | ~400MB / 1M×5 属性 | 取决于访问模式 | 高 |
| **P2** | Graph 层 Node/Edge 缓存 | Graph 层 | 减少 GC 压力 | 遍历场景 2-3 倍 | 低 |
| **P2** | 移除 Graph 层冗余 nodeIDs Set | Graph 层 | ~40MB / 1M 节点 | contains 约 2 倍 | 低 |
