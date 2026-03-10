# Phased Immutable Storage — Design Analysis

Paired designs: `core/storage.design.md`, `idea.md`

**Module position:** `DeltaStorageImpl` and `PhasedStorageImpl` are **core storage types** defined in `storage.design.md`, alongside `NativeStorageImpl` and `NativeConcurStorageImpl`. They compose multiple `IStorage` instances into layered views — not traits, not backend implementations. Both reside in the `graph` core module.

---

## 1. 核心思路

**Append-only 分层冻结架构：** 图的构建按阶段进行，每个阶段完成后将当前层"冻结"为不可变存储（off-heap），后续阶段在冻结层之上叠加新的可变层。禁止删除节点和边，通过分层覆盖实现属性更新语义。

```
时间轴 →

阶段 1        阶段 2         阶段 3          阶段 4
构建 AST      构建 CFG/PDG   构建 DFG        抽象解释

┌─────────┐
│ Layer 0  │  ← freeze →  ┌──────────┐
│ AST 节点 │  (off-heap)  │ Layer 0  │ (只读, off-heap)
│ AST 边   │              │ frozen   │
│ AST 属性 │              ├──────────┤
│ (可写)   │              │ Layer 1  │  ← freeze →  ┌──────────┐
└─────────┘              │ CFG 节点 │  (off-heap)  │ Layer 0+1│ (只读)
                          │ CFG 边   │              │ frozen   │
                          │ (可写)   │              ├──────────┤
                          └──────────┘              │ Layer 2  │ ← freeze → ...
                                                    │ DFG 节点 │
                                                    │ (可写)   │
                                                    └──────────┘
```

**与现有 DeltaStorageImpl 的关系：** `DeltaStorageImpl` 已实现 `baseDelta`（只读）+ `presentDelta`（可写）的两层模式。本方案是其自然扩展——多层冻结 + append-only 约束。

---

## 2. 删除约束：仅活跃层可删除

**核心约束：** 冻结层完全不可变（不可删除、不可修改）。删除操作仅允许作用于当前活跃层（presentDelta）中的实体。这比完全 append-only 更实用，同时保留了分层简化的所有优势。

### 2.1 与当前 DeltaStorageImpl 的对比

| 维度 | 当前 DeltaStorageImpl | **仅活跃层可删除** |
|------|----------------------|-------------------|
| 冻结层删除 | 通过 `deletedNodesHolder` / `deletedEdgesHolder` 追踪 | **禁止**（抛异常） |
| 活跃层删除 | 支持 | **支持** |
| `deletedNodesHolder` | 需要（随删除量增长） | **不需要** |
| `deletedEdgesHolder` | 需要 | **不需要** |
| `"_deleted_"` 哨兵值 | 需要（属性删除标记） | **不需要** |
| `containsNode` 查询 | 检查 deleted → present → base（三方） | present → base（**两层**） |
| `nodeIDs` 遍历 | 合并后 filter deleted + distinct | 合并后 **distinct**（无 filter） |
| 冻结层邻接查询 | 需排除 deletedEdgesHolder 中的边 | **直接返回**（边不会被删除） |
| 正确性复杂度 | 三方一致性（base + present + deleted） | **两层覆盖**（present 优先于 base） |

### 2.2 简化后的核心操作

**属性读取 — 两层覆盖，无哨兵：**

```kotlin
// 当前 DeltaStorageImpl — 三方检查 + 哨兵值
override fun getNodeProperty(id: NodeID, byName: String): IValue? {
    if (!containsNode(id)) throw EntityNotExistException(id)          // 检查 deletedHolder
    if (!presentDelta.containsNode(id)) return baseDelta.getNodeProperty(id, byName)
    presentDelta.getNodeProperty(id, byName)?.also {
        return it.takeIf { it.core != "_deleted_" }                   // 哨兵值判断
    }
    return if (baseDelta.containsNode(id)) baseDelta.getNodeProperty(id, byName) else null
}

// 新方案 — 两层覆盖
override fun getNodeProperty(id: NodeID, byName: String): IValue? {
    presentDelta.getNodeProperty(id, byName)?.let { return it }       // 活跃层优先
    return baseDelta.getNodeProperty(id, byName)                      // 回退到冻结层
}
```

**删除操作 — 仅活跃层：**

```kotlin
override fun deleteNode(id: NodeID) {
    if (!presentDelta.containsNode(id))
        throw FrozenLayerModificationException(id)  // 冻结层节点不可删除
    presentDelta.deleteNode(id)                      // 活跃层正常删除
}

override fun deleteEdge(id: EdgeID) {
    if (!presentDelta.containsEdge(id))
        throw FrozenLayerModificationException(id)
    presentDelta.deleteEdge(id)
}
```

**属性写入 — 覆盖语义：**

```kotlin
override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) {
    if (!containsNode(id)) throw EntityNotExistException(id)
    // 写入活跃层（如果节点在冻结层，创建 overlay 到活跃层）
    if (!presentDelta.containsNode(id)) presentDelta.addNode(id, *newProperties)
    else presentDelta.setNodeProperties(id, newProperties = newProperties)
    // null value 仅删除活跃层 overlay 属性，冻结层原值会重新"透出"
}
```

### 2.3 适用场景

| 场景 | 需要删除冻结层？ | 活跃层删除够用？ |
|------|----------------|----------------|
| AST 构建 → 冻结 | — | — |
| CFG 构建中临时 dummy 节点 | 不需要 | 够用（临时节点在活跃层） |
| 不动点分析中属性更新 | 不需要 | 够用（overlay 写到活跃层） |
| 分析完成后清理中间结果 | 不需要 | 够用（中间结果在活跃层） |
| **增量分析（删除旧 AST 子树）** | **需要** | 不够用 → 使用完整 `DeltaStorageImpl` |
| **图重构（内联优化等）** | **需要** | 不够用 → 使用完整 `DeltaStorageImpl` |

对于极少数需要删除冻结层数据的场景，保留现有 `DeltaStorageImpl`（带 deleted holders）作为备选。两者共享 `IStorage` 接口，可按需选择。

---

## 3. MapDB 作为冻结层：适配性重新评估

### 3.1 为什么只读场景下 MapDB 变得合理

MapDB 在读写场景下的两大痛点：

| 痛点 | 读写场景代价 | 冻结只读场景 |
|------|------------|------------|
| **COW 写放大** | `setNodeProperties` → 全量序列化 → 分配新 off-heap 空间 → 释放旧空间 | **完全消除**（无写入） |
| **邻接 SetVal 修改** | `addEdge` → 反序列化整个 SetVal → 添加 → 重新序列化写回 | **完全消除**（邻接结构冻结） |

**剩余代价：读取反序列化。** 每次 `getNodeProperty` 仍需 off-heap → ByteArray → IValue 反序列化链路（~3-6μs）。

### 3.2 冻结时优化：预计算 + 缓存

冻结是一个**明确的时间点**，可以在此执行一次性优化：

**优化 1：邻接列表拆分**

当前 MapDB 的 `graphStructure` 将入边和出边存在同一个 SetVal 中，每次方向查询需全量反序列化再过滤。冻结时可预计算拆分：

```kotlin
// 冻结时：拆分为独立的入边/出边 hashMap
fun freeze(mutableStorage: NativeStorageImpl): MapDBStorageImpl {
    val frozen = MapDBStorageImpl(config = { fileDB(path).fileMmapEnableIfSupported().readOnly() })
    // 预计算拆分的邻接列表
    for (nodeId in mutableStorage.nodeIDs) {
        frozen.incomingEdges[nodeId] = mutableStorage.getIncomingEdges(nodeId)  // 已过滤方向
        frozen.outgoingEdges[nodeId] = mutableStorage.getOutgoingEdges(nodeId)
    }
    // ... 复制节点/边属性
    return frozen
}
```

冻结后方向查询：O(1) hashMap get + O(degree_方向) 反序列化（非 O(degree_total)）。

**优化 2：SoftReference 热数据缓存**

```kotlin
class CachedFrozenStorage(private val mapdb: MapDBStorageImpl) : IStorage {
    // SoftReference: GC 压力大时自动释放，下次 miss 再从 MapDB 反序列化
    private val propCache = ConcurrentHashMap<NodeID, SoftReference<Map<String, IValue>>>()

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        propCache[id]?.get()?.let { return it }
        val props = mapdb.getNodeProperties(id)
        propCache[id] = SoftReference(props)
        return props
    }
}
```

**效果：** 热数据（频繁访问的节点属性）命中缓存 ~50ns；冷数据（偶尔访问）miss 时 ~3-6μs。GC 压力大时 SoftReference 自动释放冷数据，堆内存自适应调节。

**优化 3：属性合并序列化**

当前 MapDB 的 `EntityPropertyMap` 使用组合键 `"$entityId:$propName"` 逐属性存储。冻结时可将同一实体的所有属性合并为单个 ByteArray：

```
当前：N 个属性 → N 次 hashMap.get + N 次反序列化
优化：N 个属性 → 1 次 hashMap.get + 1 次反序列化（批量）
```

### 3.3 MapDB 只读模式配置

```kotlin
DBMaker.fileDB(frozenFile)
    .fileMmapEnableIfSupported()  // mmap: OS 页缓存管理，热数据自动留在内存
    .readOnly()                    // 只读模式：无 WAL，无锁，无 COW
    .concurrencyDisable()          // 无并发写，移除内部锁
    .closeOnJvmShutdown()
    .make()
```

`readOnly()` 模式下 MapDB 跳过所有写相关的内部结构（WAL、锁、空间管理），读取路径更短。

### 3.4 冻结层内存收益估算

以 2M 节点 + 20M 边 + 平均 5 属性的图为例：

| 存储位置 | NativeStorage（全堆内） | 冻结到 MapDB |
|---------|----------------------|-------------|
| 节点/边对象 | ~2.1GB（NodeID + EdgeID） | ~0（off-heap） |
| 属性值（IValue） | ~3.5GB（110M 个 IValue 对象） | ~0（off-heap） |
| HashMap 内部结构 | ~4.5GB（HashMap.Node + 数组） | ~0（off-heap） |
| 邻接结构 | ~1.3GB（Set<EdgeID>） | ~0（off-heap） |
| **堆内占用** | **~11.4GB** | **<100MB**（SoftRef 缓存） |
| Off-heap/磁盘 | 0 | ~8-10GB（序列化后更紧凑） |

**GC 影响：** 堆内从 ~11.4GB / ~200M 个对象 → <100MB / ~数千个对象。Full GC 从秒级降到毫秒级。

---

## 4. 替代方案：自定义只读格式 vs MapDB

MapDB 是一个通用读写引擎，用于只读场景有些"杀鸡用牛刀"。更高效的只读格式：

### 4.1 Memory-Mapped Flat File

冻结时将数据序列化为紧凑的二进制格式，运行时通过 `MappedByteBuffer` 直接访问：

```
文件布局：
┌──────────────┬──────────────┬──────────────┬──────────────┐
│ Header       │ Node Index   │ Edge Index   │ Property Data│
│ (节点数,边数) │ (offset 数组) │ (offset 数组) │ (紧凑序列化)  │
└──────────────┴──────────────┴──────────────┘──────────────┘

读取路径：
  nodeId → hash → index[hash] → offset → mmap.get(offset) → deserialize
```

| 维度 | MapDB 只读 | Flat File mmap |
|------|-----------|----------------|
| 读延迟 | ~3-6μs（MapDB 内部路径 + 反序列化） | ~1-3μs（直接 offset 访问 + 反序列化） |
| 启动速度 | 需打开 DB + 校验 | 仅 `FileChannel.map()`（~1ms） |
| 文件大小 | MapDB 内部结构开销 ~20-30% | 最小化（仅数据 + 索引） |
| 依赖 | `org.mapdb:mapdb:3.0.5` | **零依赖**（JDK `MappedByteBuffer`） |
| 实现复杂度 | 低（复用现有 MapDB 代码） | 中（需自行实现序列化和索引） |

### 4.2 建议

**短期用 MapDB**：复用现有 `MapDBStorageImpl` 代码，加 `readOnly()` + SoftReference 缓存，改动量最小。

**中期可替换为 Flat File**：当 MapDB 只读延迟成为瓶颈时，自定义 mmap 格式可进一步降低延迟并消除 MapDB 依赖。接口不变（`IStorage`），只是冻结层的实现替换。

---

## 5. PhasedStorageImpl 设计

### 5.1 设计原则：无中间接口

`PhasedStorageImpl` 直接实现 `IStorage`，不引入 `IFreezableStorage` / `ILayeredStorage` 中间接口。理由：

- 只有一个实现（`PhasedStorageImpl`），创建抽象接口是过度设计
- 上游只需要 `IStorage` 的代码透明兼容
- 需要分层 API 的代码直接持有 `PhasedStorageImpl` 引用
- 减少继承层次复杂度

分层特有方法（`freeze()`、`freezeAndPushLayer()`、`compactLayers()`、`layerCount`、`isFrozen`）作为具体类方法暴露。

### 5.2 删除约束

活跃层拥有完整的 CRUD 能力，冻结层仅支持读取。删除操作在运行时检查目标实体归属：

```kotlin
class PhasedStorageImpl(
    private val frozenLayerFactory: () -> IStorage = { NativeStorageImpl() }
) : IStorage {

    fun deleteNode(id: NodeID) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (!activeLayer.containsNode(id))
            throw FrozenLayerModificationException(id)
        activeLayer.deleteNode(id)
    }

    fun deleteEdge(id: EdgeID) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        if (!activeLayer.containsEdge(id))
            throw FrozenLayerModificationException(id)
        activeLayer.deleteEdge(id)
    }
}
```

**属性 null 值语义：** `setNodeProperties(id, "key" to null)` 仅移除活跃层的 overlay 属性。如果冻结层有同名属性，该属性会重新"透出"。这提供了简洁的"撤销本层修改"语义，无需 `"_deleted_"` 哨兵值。

### 5.3 实现层次

```
graph (核心模块)
└── IStorage
    ├── NativeStorageImpl           （堆内读写，作为 activeLayer 基础）
    ├── NativeConcurStorageImpl     （线程安全堆内读写）
    ├── DeltaStorageImpl            （两层 overlay + 跨层删除追踪）
    ├── DeltaConcurStorageImpl      （线程安全 Delta）
    ├── PhasedStorageImpl           （多层冻结 + 活跃层可删除）
    │   ├── frozenLayers: List<IStorage>  （NativeStorageImpl 或 MapDB，只读）
    │   ├── activeLayer: NativeStorageImpl （堆内，完整 CRUD）
    │   ├── freeze() / isFrozen           （整体只读开关）
    │   ├── freezeAndPushLayer()          （冻结活跃层 → 推入冻结栈 → 新活跃层）
    │   └── compactLayers(topN)           （合并顶部冻结层）
    └── IStorage.transferTo()       （扩展函数，storage-to-storage 转写）

modules (外部后端实现)
├── impl-mapdb   → MapDBStorageImpl        （off-heap，可作为 frozenLayer）
├── impl-jgrapht → JgraphtStorageImpl      （JGraphT 后端）
└── impl-neo4j   → Neo4jStorageImpl        （Neo4j 嵌入式后端）
```

**关键定位：** `PhasedStorageImpl` 和 `DeltaStorageImpl` 都是核心存储组合器（composer），不是 trait，也不是后端实现。它们只依赖 `IStorage` 接口，`PhasedStorageImpl` 可以组合任意后端实现作为冻结层（通过 `frozenLayerFactory` 注入）。`DeltaStorageImpl` 适用于需要跨层删除的场景。

---

## 6. 静态分析阶段映射

### 典型工作流

```kotlin
val storage = PhasedStorageImpl()

// 阶段 1：构建 AST
astBuilder.buildAST(sourceCode, storage)
storage.freezeAndPushLayer()  // AST 数据 → MapDB off-heap

// 阶段 2：构建 CFG
cfgBuilder.buildCFG(storage)  // 读取冻结的 AST，写入新 CFG 节点/边
storage.freezeAndPushLayer()  // CFG 数据 → MapDB off-heap

// 阶段 3：构建 PDG/DFG
dfgBuilder.buildDFG(storage)  // 读取冻结的 AST+CFG，写入新 DFG 边
storage.freezeAndPushLayer()  // DFG 数据 → MapDB off-heap

// 阶段 4：抽象解释
// 此时所有拓扑结构都在冻结层（off-heap）
// 分析状态使用外置 Array<AbstractState>（见 storage-performance.md 第 7 节）
// 如果仍需通过 IStorage 写属性，只写入最顶层活跃层
abstractInterpreter.analyze(storage)

// 分析完成后：可选压缩
storage.compactLayers(storage.layerCount)  // 合并所有层为一个持久化文件
```

### 各阶段内存分布

| 阶段 | 冻结层 (off-heap) | 活跃层 (堆内) | 总堆内存 |
|------|------------------|-------------|---------|
| 构建 AST | 0 | ~8GB（AST 全量） | ~8GB |
| 构建 CFG | ~5GB（AST 冻结，序列化压缩） | ~3GB（CFG 增量） | ~3GB |
| 构建 DFG | ~7GB（AST+CFG 冻结） | ~1GB（DFG 增量） | ~1GB |
| 抽象解释 | ~8GB（全部冻结） | ~128MB（分析状态数组） | **~128MB** |

**对比当前全堆内方案：** 抽象解释阶段从 ~30GB 堆内存降到 ~128MB，GC 暂停从秒级降到不可感知。

---

## 7. 风险与权衡

### 7.1 冻结迁移的一次性开销

将 NativeStorage 数据批量序列化到 MapDB 需要时间：

| 数据规模 | 预估冻结时间 | 说明 |
|---------|------------|------|
| 2M 节点 + 5 属性 | ~5-10s | 10M 次序列化 + MapDB 写入 |
| 20M 边 + 3 属性 | ~15-30s | 60M 次序列化 + 邻接结构写入 |
| **总计** | **~20-40s** | 单次开销，后续阶段无此代价 |

**缓解：** 冻结发生在阶段切换边界，不在热路径上。相比分析阶段数分钟的运行时间，20-40s 冻结开销可接受。可通过并行序列化进一步优化。

### 7.2 跨层查询的性能

属性读取需要逐层查找（presentDelta → baseDelta），层数越多查找链越长：

| 层数 | 最坏情况查找次数 | 影响 |
|------|----------------|------|
| 2（当前 DeltaStorage） | 2 次 containsNode + 2 次 getProperty | 可忽略 |
| 3-4（典型分析流程） | 3-4 次 | 冻结层有 SoftRef 缓存，影响小 |
| >5 | 可观 | 应触发 `compactLayers` 合并 |

**缓解：** `compactLayers` 将多个冻结层合并为一个，维持查找链长度 ≤ 3。

### 7.3 删除约束的适用性

| 场景 | 删除范围 | 活跃层删除够用？ |
|------|---------|----------------|
| AST 构建 | 不需要删除 | 完美 |
| CFG 构建（含临时 dummy 节点） | 活跃层内临时节点 | 完美 |
| 不动点分析（属性更新） | 不需要删除（overlay 写入） | 完美 |
| 分析后清理中间结果 | 活跃层内中间节点/边 | 完美 |
| 图重构（内联优化） | **需要删除冻结层节点** | 不够用 → `DeltaStorageImpl` |
| 增量分析（文件变更） | **需要删除冻结层 AST 子树** | 不够用 → `DeltaStorageImpl` |

**缓解：** `PhasedStorageImpl` 和 `DeltaStorageImpl` 共享 `IStorage` 接口。大多数分析场景使用 `PhasedStorageImpl`（简化、高效），需要删除冻结数据的场景退回 `DeltaStorageImpl`（完整但略复杂）。

---

## 8. Freeze 迁移机制

### 8.1 问题：现有 IO 不适用

现有两条 IO 路径都不适用于 freeze：

| 路径 | 格式 | 问题 |
|------|------|------|
| `MapDbGraphIOImpl.export` | `indexTreeList`（有序列表） | 文件交换格式；读回需重建邻接结构，不能直接作为 `IStorage` 使用 |
| 手动逐条调用 `MapDBStorageImpl.addNode/addEdge` | `hashMap` + `graphStructure` | 可以工作，但 addEdge 每次触发 SetVal COW（冻结时写入是一次性的，可以优化） |

### 8.2 方案：IStorage.transferTo 扩展函数

在 `IStorage` 层面提供通用的 storage-to-storage 转写，不依赖具体实现：

```kotlin
fun IStorage.transferTo(target: IStorage) {
    for (nodeId in nodeIDs) target.addNode(nodeId, getNodeProperties(nodeId))
    for (edgeId in edgeIDs) target.addEdge(edgeId, getEdgeProperties(edgeId))
}
```

**freeze 操作使用 transferTo：**

```kotlin
class PhasedStorageImpl(
    private val frozenLayerFactory: () -> IStorage = { NativeStorageImpl() }
) : IStorage {
    private val frozenLayers = mutableListOf<IStorage>()
    private var activeLayer: IStorage = NativeStorageImpl()

    fun freezeAndPushLayer() {
        val target = frozenLayerFactory()
        activeLayer.transferTo(target)
        activeLayer.close()
        frozenLayers.add(target)
        activeLayer = NativeStorageImpl()
    }
}
```

### 8.3 优化：批量写入减少 MapDB COW

`transferTo` 使用标准 `addEdge` 逐条写入，每次 addEdge 都会触发邻接 SetVal 的 COW 重写。对于大规模图（20M 边），这是冻结阶段的主要瓶颈。

**优化方向 1：预计算邻接，直接写入 hashMap：**

```kotlin
/**
 * Optimized transfer that pre-computes adjacency to avoid per-edge SetVal COW.
 * Specific to MapDBStorageImpl as target.
 */
fun IStorage.freezeInto(target: MapDBStorageImpl) {
    // 1. 批量写入节点属性
    for (nodeId in nodeIDs) {
        target.addNode(nodeId, *getNodeProperties(nodeId).toTypedArray())
    }
    // 2. 批量写入边属性（不通过 addEdge 路径，避免逐条 SetVal 更新）
    for (edgeId in edgeIDs) {
        target.bulkAddEdge(edgeId, getEdgeProperties(edgeId))
    }
    // 3. 一次性写入预计算的邻接结构
    for (nodeId in nodeIDs) {
        target.bulkSetAdjacency(
            nodeId,
            incoming = getIncomingEdges(nodeId),
            outgoing = getOutgoingEdges(nodeId)
        )
    }
}
```

**优化方向 2：MapDB 冻结模式下拆分邻接结构：**

当前 `MapDBStorageImpl.graphStructure` 将入边和出边混存于同一个 SetVal。冻结时可拆分为两个独立的 hashMap，方向查询不再需要过滤：

```kotlin
// 冻结专用的 MapDB 布局
private val incomingStructure = dbManager.hashMap("incoming", ...).createOrOpen()
private val outgoingStructure = dbManager.hashMap("outgoing", ...).createOrOpen()

// 方向查询：直接返回，无过滤
override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
    return incomingStructure[id.name]?.map { EdgeID(it as ListVal) }?.toSet() ?: emptySet()
}
```

### 8.4 Freeze 的完整数据流

```
freezeAndPushLayer() 执行流程：

  activeLayer (NativeStorageImpl, 堆内)
       │
       │  transferTo / freezeInto
       ▼
  MapDBStorageImpl (off-heap file-backed)
       │
       │  activeLayer.close() → 堆内存释放
       │  frozenLayers.add(mapdb)
       ▼
  frozenLayers: [layer0(MapDB), layer1(MapDB), ...]
       │
       │  创建新空 activeLayer
       ▼
  activeLayer = NativeStorageImpl() (空，堆内)

内存变化：
  冻结前：堆内 ~8GB（AST 数据）
  冻结中：堆内 ~8GB + off-heap 写入（短暂峰值）
  冻结后：堆内 ~0（旧 activeLayer 已 close）+ off-heap ~5GB（MapDB mmap）
```

### 8.5 查询路由：多冻结层 + 活跃层

```kotlin
override fun containsNode(id: NodeID): Boolean {
    if (activeLayer.containsNode(id)) return true
    return frozenLayers.asReversed().any { it.containsNode(id) }  // 新层优先
}

override fun getNodeProperty(id: NodeID, byName: String): IValue? {
    // 活跃层优先（覆盖语义）
    activeLayer.getNodeProperty(id, byName)?.let { return it }
    // 逆序查冻结层（最近冻结的优先）
    for (layer in frozenLayers.asReversed()) {
        layer.getNodeProperty(id, byName)?.let { return it }
    }
    return null
}

override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
    // 邻接是 append-only 的：合并所有层的结果
    val result = mutableSetOf<EdgeID>()
    for (layer in frozenLayers) {
        if (layer.containsNode(id)) result += layer.getIncomingEdges(id)
    }
    if (activeLayer.containsNode(id)) result += activeLayer.getIncomingEdges(id)
    return result
}

override fun deleteNode(id: NodeID) {
    if (!activeLayer.containsNode(id))
        throw FrozenLayerModificationException(id)
    activeLayer.deleteNode(id)
}
```

---

## 9. 实现路径

### Phase 1：最小可行（基于现有代码）

1. 实现 `IStorage.transferTo(target)` 扩展函数（~30 行）
2. 基于现有 `DeltaStorageImpl` 做验证：`baseDelta` 传入通过 transferTo 写入的 `MapDBStorageImpl`
3. 验证：构建 AST → transferTo MapDB → 新 DeltaStorage(baseDelta=mapdb) → 构建 CFG → 检查内存下降
4. 加入 SoftReference 属性缓存层

**改动量：** ~200 行新代码 + 现有代码零修改

### Phase 2：PhasedStorageImpl

5. 实现 `PhasedStorageImpl`（直接实现 `IStorage`，多层冻结 + 活跃层可删除）
6. 实现 `freeze()` / `freezeAndPushLayer()` / `compactLayers()` 具体类方法
7. 冻结时预计算邻接列表拆分（IN/OUT 分离）

### Phase 3：性能优化

8. 实现 `freezeInto` 批量写入（避免逐条 SetVal COW）
9. MapDB 属性合并序列化（每实体 1 个 entry）
10. 可选：自定义 mmap flat file 替代 MapDB 冻结格式
11. 可选：冻结层并行序列化

---

## Libraries

- `org.mapdb:mapdb:3.0.5` — 冻结层 off-heap 只读存储（现有依赖，readOnly 模式）
- `edu.jhu.cobra:commons-value:0.1.0` — IValue 序列化（现有依赖）
- 无新增依赖（Phase 1）
