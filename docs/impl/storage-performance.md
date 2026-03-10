# Storage 性能分析与大规模图方案调研

Paired design: `storage.design.md`

---

## 1. 问题背景

在大型项目分析中，图数据消耗内存超过 **30GB**。当前两种 IStorage 实现各有瓶颈：

| 实现 | 问题 | 根因 |
|------|------|------|
| NativeStorage（HashMap） | GC 暂停时间长，Full GC 可达秒级 | 30GB 堆内对象数量巨大，GC 扫描/标记/压缩代价高 |
| MapDB | 性能不如 NativeStorage，无明显提升 | 每次属性访问需 serialize/deserialize，抵消了 off-heap 的 GC 优势 |

---

## 2. NativeStorage 在 30GB 下的 GC 根因分析

### 堆内对象数量估算

假设图有 N 个节点、M 条边、平均 P 个属性/实体：

| 对象类型 | 数量 | 每个大小（约） | 总计 |
|---------|------|-------------|------|
| NodeID | N | ~40B（对象头 + String 引用 + String 内部） | 40N |
| EdgeID | M | ~96B（3 个字段 + 2 个 NodeID 引用 + String） | 96M |
| 属性 Map entry | (N+M) × P | ~64B（HashMap.Node + key String + IValue） | 64(N+M)P |
| IValue 包装 | (N+M) × P | ~32B（StrVal/NumVal 对象头 + 内部值） | 32(N+M)P |
| adjacency Set entry | 2M | ~32B（HashSet.Node + EdgeID 引用） | 64M |

**示例：** 2M 节点 + 20M 边 + 平均 5 属性：
- 对象数：~2M + 20M + 110M + 110M + 40M = **~282M 个对象**
- 内存：~40×2M + 96×20M + 64×110M + 32×110M + 64×20M = **~13.8GB**（仅数据，不含 HashMap 内部结构开销）

### GC 代价分析

| GC 类型 | 30GB 堆场景 | 影响 |
|---------|------------|------|
| Minor GC (Young Gen) | 频率取决于分配速率；图构建阶段高频触发 | 每次 ~10-50ms |
| Mixed GC (G1) | 需扫描 Old Gen 引用；2.8 亿对象的标记开销大 | 每次 ~100-500ms |
| Full GC | 堆压力大时触发；需扫描 + 压缩整个 30GB 堆 | **~2-10 秒暂停** |
| 长期影响 | Old Gen 碎片化 → 频繁 Mixed/Full GC | 吞吐量持续下降 |

**核心矛盾：** HashMap + IValue 包装产生大量小对象（每个属性 ~2-3 个对象），GC 需要逐个扫描。对象数量（非内存大小）是 GC 代价的主要驱动因素。

---

## 3. MapDB 为何未能解决问题

### 序列化代价 vs GC 收益

```
MapDB 的承诺：数据 off-heap → 减少堆对象 → 减少 GC
MapDB 的现实：每次访问 → 创建临时 ByteArray + IValue → 堆对象反而增加
```

| 场景 | NativeStorage 堆对象 | MapDB 堆对象 |
|------|---------------------|-------------|
| 图构建完成后（稳态） | N+M 个实体 + (N+M)P 个 IValue（长生命周期，Old Gen） | ~0（off-heap），但... |
| 每次 getProp | 0（返回引用） | 1× ByteArray + 1× IValue（短生命周期，Young Gen） |
| BFS 遍历 1000 节点 × 5 属性 | 0 额外分配 | **5000 个临时对象**（Young Gen 压力） |

**结论：** MapDB 将 GC 压力从 Old Gen（长生命周期大对象）转移到了 Young Gen（高频短生命周期小对象）。对于**遍历密集型**工作负载，MapDB 的 Young Gen GC 压力反而更高。

### 序列化延迟放大

图遍历的访问模式是**高频、随机、小数据量**：

```
BFS/DFS 遍历路径：
  getOutgoingEdges(nodeId)  → MapDB: deserialize SetVal(全量)
  → 对每条边: getEdge(edgeId)  → MapDB: deserialize 边属性
    → 对目标节点: getNode(targetId)  → MapDB: deserialize 节点属性

NativeStorage 总计：3次 HashMap.get（~150ns）
MapDB 总计：3次 off-heap read + 3次 deserialize（~3000-6000ns）
放大倍数：20-40x
```

---

## 4. 候选方案调研

### 方案 A：RocksDB（JNI）

**来源：** deepwiki facebook/rocksdb；RocksJava API

| 维度 | 评估 |
|------|------|
| 存储模型 | LSM-tree，数据在 native 内存 + 磁盘；off-heap block cache |
| GC 影响 | Java 侧仅持有 long handle（~16B/entry），极低 GC 压力 |
| 读延迟 | 点查询 ~1-5μs（memtable hit）；~10-50μs（block cache hit） |
| 写延迟 | ~1-5μs（写入 memtable） |
| 序列化 | key/value 为 byte[]，需自行 serialize，但只需一层（无 MapDB 双层） |
| 邻接查询 | **prefix seek 原生支持**：key=`"nodeId:IN:edgeId"`，用 Iterator + seek 实现方向查询 |
| 30GB 数据 | 完美适配：数据在磁盘 + block cache，堆内存可控在 1-2GB |
| 缺点 | JNI 调用开销（~100ns/call）；需要管理 native 资源 |

**适配度评估：**
- prefix seek 可以替代 MapDB 的 SetVal 方案，直接 O(log n) 遍历方向边
- block cache 控制内存使用，避免 30GB 全部上堆
- 写入是 append-only（LSM），比 MapDB COW 更高效
- **主要挑战：** byte[] 序列化仍需要，但比 MapDB 少一层；JNI 调用开销

### 方案 B：Chronicle Map（Off-heap）

**来源：** deepwiki OpenHFT/Chronicle-Map

| 维度 | 评估 |
|------|------|
| 存储模型 | 分段 off-heap HashMap，memory-mapped 可持久化 |
| GC 影响 | 数据完全 off-heap；`getUsing()` 复用对象实现零分配读取 |
| 读延迟 | ~200-500ns（off-heap 直接访问） |
| 写延迟 | ~200-500ns |
| 序列化 | 支持 flyweight values（零拷贝直接读写 off-heap 内存）；`Byteable` 接口 |
| 邻接查询 | **不支持 prefix/range 查询**，仅点查询；邻接列表需存为 value 集合 |
| 30GB 数据 | off-heap 不占堆；但变长 value（邻接列表）需预估大小，超出需 realloc |
| 缺点 | 不支持范围查询；变长 value 管理复杂；需预先配置 entry 数量和大小 |

**适配度评估：**
- 属性存储（key=entityId+propName, value=bytes）非常适合：O(1) 点查询 + 零 GC
- 邻接查询仍需将整个邻接列表作为 value 存储（与 MapDB 同一问题）
- `getUsing()` + flyweight 可以实现**真正的零分配读取**，比 MapDB 的 deserialize 好
- **主要挑战：** 邻接列表变长 value 管理；不支持范围查询

### 方案 C：混合架构（推荐）

结合各方案优势，按数据类型选择最优存储：

```
┌─────────────────────────────────────────────────────┐
│                   IStorage 接口                       │
├─────────────────────────────────────────────────────┤
│  拓扑结构（邻接）    →  RocksDB prefix seek            │
│                        key: "nodeId:IN:edgeId"       │
│                        key: "nodeId:OUT:edgeId"      │
│                        O(log n) 方向查询              │
├─────────────────────────────────────────────────────┤
│  属性存储            →  Chronicle Map 或 堆内优化      │
│                        key: entityId:propName        │
│                        value: serialized IValue      │
│                        O(1) 零 GC 点查询             │
├─────────────────────────────────────────────────────┤
│  ID 索引（nodeIDs/   →  Eclipse Collections           │
│   edgeIDs）             IntObjectHashMap / ObjectSet  │
│                        原始类型特化，减少包装对象      │
└─────────────────────────────────────────────────────┘
```

### 方案 D：堆内优化（最小改动）

不引入新依赖，优化 NativeStorage 减少对象数量：

| 优化 | 效果 | 改动量 |
|------|------|--------|
| 属性合并存储：`Map<EntityID, ByteArray>` | 每实体 1 个 ByteArray 替代 P 个 IValue 对象 | 中 |
| 使用 Eclipse Collections 的 `ObjectObjectHashMap` | 减少 HashMap.Node 包装开销（~30% 内存节省） | 小 |
| NodeID 内部化（intern pool） | 消除重复 NodeID 对象 | 小 |
| 延迟反序列化：属性仅在访问时 deserialize，缓存结果 | 减少不必要的 IValue 创建 | 中 |
| G1GC 调优：`-XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m` | 控制 GC 暂停时间 | 零（JVM 参数） |

---

## 5. 方案对比总结

| 方案 | 30GB 堆内存 | GC 暂停 | 读延迟 | 邻接查询 | 改动量 | 复杂度 |
|------|-----------|---------|--------|---------|--------|--------|
| 当前 NativeStorage | 30GB | 2-10s Full GC | ~50ns | O(1) | — | — |
| 当前 MapDB | <1GB 堆 | Minor GC 频繁 | ~3-6μs | O(degree) | — | — |
| **RocksDB** | <2GB 堆 | 极低 | ~1-5μs | **O(log n) prefix** | 大 | 中 |
| **Chronicle Map** | <1GB 堆 | 极低 | ~200-500ns | O(degree) 同 MapDB | 大 | 高 |
| **混合架构** | <2GB 堆 | 极低 | ~200ns-5μs | **O(log n)** | 大 | 高 |
| **堆内优化** | ~15-20GB | 控制在 200ms 内 | ~50-100ns | O(1) | 中 | 低 |

---

## 6. 建议路径

### 短期（最小改动，立即见效）

1. **G1GC 调优**：`-XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m -XX:InitiatingHeapOccupancyPercent=45`
2. **NodeID intern pool**：消除重复 NodeID 对象（图遍历中同一 NodeID 被大量引用）
3. **属性合并存储**：每实体一个 `ByteArray` 存所有属性，减少对象数量 5-10x

### 中期（引入新依赖）

4. **Eclipse Collections 替换 HashMap/HashSet**：`ObjectObjectHashMap` 减少 ~30% 内存
5. **RocksDB 作为邻接存储后端**：prefix seek 解决方向查询；block cache 控制内存

### 长期（架构级）

6. **混合存储架构**：RocksDB（拓扑） + Chronicle Map（属性） + Eclipse Collections（索引）
7. **列式属性存储**（借鉴 flatgraph）：按属性类型打包为原始类型数组，极致内存效率

---

## Libraries（候选）

- `org.rocksdb:rocksdbjni:9.x` — 嵌入式 LSM-tree KV 存储，prefix seek，off-heap block cache
- `net.openhft:chronicle-map:3.x` — off-heap 分段 HashMap，零 GC，flyweight values
- `org.eclipse.collections:eclipse-collections:11.x` — 原始类型特化集合，减少对象包装
- `com.github.luben:zstd-jni:1.x` — Zstd 压缩（如需自定义持久化格式）

---

## 7. 抽象解释工作负载特征分析

### 工作负载模式

静态分析中的抽象解释（abstract interpretation）具有独特的图访问模式：

```
阶段 1：图构建（一次性）
  解析源码 → 构建 CFG/调用图/PDG → 拓扑结构固定

阶段 2：不动点迭代（高频，核心瓶颈）
  repeat:
    for each node in worklist:
      read node properties（当前抽象状态）
      read incoming edges → read predecessor properties
      compute transfer function → new abstract state
      if state changed:
        write node property（更新抽象状态）
        add successors to worklist
  until fixpoint
```

| 维度 | 阶段 1（构建） | 阶段 2（不动点） |
|------|---------------|-----------------|
| 拓扑操作 | 大量 addNode/addEdge | **几乎不修改**（只读遍历） |
| 属性读取 | 少量初始化 | **极高频**（每次 transfer function） |
| 属性写入 | 少量初始化 | **高频**（状态变化时） |
| 访问模式 | 顺序批量 | **随机访问**（worklist 驱动） |
| 热点数据 | 无 | 循环体内节点反复访问 |

**核心需求：** O(1) 随机属性读写 + 极低 GC 开销 + 拓扑只读遍历高效

### flatgraph 在抽象解释中的适配性

**来源：** deepwiki joernio/flatgraph（DiffGraphBuilder、DiffGraphApplier、PropertyDirectAccess）

flatgraph 与 Joern（同为静态分析工具）共享场景。但其属性更新机制存在根本限制：

| 操作 | flatgraph 机制 | 代价 | 适配性 |
|------|---------------|------|--------|
| 拓扑构建 | DiffGraphBuilder → DiffGraphApplier 批量应用 | O(N+M) 一次性 | **完美** |
| 拓扑只读遍历 | `neighbors(direction)` 数组切片 | O(1) 起始 + O(degree) 遍历 | **完美** |
| 属性读取 | `node.property(kind)` → 数组索引 | O(1) | **完美** |
| **属性写入** | DiffGraphBuilder → DiffGraphApplier | **O(N) 数组重分配** | **不适配** |

**flatgraph 属性更新的内部流程：**

```
DiffGraph.setNodeProperty(node, key, value)
  → DiffGraphApplier.applyDiff()
    → 收集所有同类型属性变更
    → 分配新数组（旧数组大小 + 变更数量）
    → 复制旧数据 + 插入新数据
    → 替换 GNode 中的属性数组引用
```

**不动点迭代中的问题：** 每轮迭代可能更新数千个节点的属性，每次更新触发数组重分配。对于 2M 节点的图，单轮迭代可能产生数百次数组拷贝（每次 O(N)），总计 O(N × updates_per_round × rounds)。

**Joern 的做法：** Joern 的数据流分析实际上将抽象状态存储为节点属性，但其不动点迭代频率远低于经典抽象解释（Joern 主要做 taint analysis，迭代轮次少）。对于经典格理论抽象解释（widening/narrowing 多轮迭代），flatgraph 的 DiffGraph 机制代价过高。

### 推荐架构：分层存储

结合抽象解释的工作负载特征，推荐将存储按**访问模式**分层：

```
┌─────────────────────────────────────────────────────────┐
│                    IStorage 接口                         │
├─────────────────────────────────────────────────────────┤
│  Layer 1: 拓扑结构（只读，构建后不变）                     │
│  ┌─────────────────────────────────────────────────┐    │
│  │  方案 A: flatgraph 风格数组存储                    │    │
│  │    nodes: Array<NodeID>  （按 seqId 索引）        │    │
│  │    edges: Array<EdgeID>  （按 seqId 索引）        │    │
│  │    adjacency: IntArray   （CSR 格式压缩邻接表）    │    │
│  │    O(1) 节点/边查找，O(degree) 邻接遍历           │    │
│  │    内存：~24 bytes/node + ~16 bytes/edge          │    │
│  │                                                   │    │
│  │  方案 B: JGraphT DirectedPseudograph（现有）       │    │
│  │    已验证可靠，改动量为零                           │    │
│  └─────────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────────┤
│  Layer 2: 持久属性（构建时写入，分析时只读）               │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Eclipse Collections ObjectObjectHashMap          │    │
│  │    key: EntityID, value: Map<String, IValue>      │    │
│  │    减少 ~30% 内存（无 HashMap.Node 包装）          │    │
│  │    或：属性合并为 ByteArray（每实体 1 个对象）      │    │
│  └─────────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────────┤
│  Layer 3: 分析状态（不动点迭代，极高频读写）              │
│  ┌─────────────────────────────────────────────────┐    │
│  │  方案: 类型化直接数组                              │    │
│  │    states: Array<AbstractState>                   │    │
│  │    索引: node.seqId → states[seqId]              │    │
│  │    O(1) 读写，零序列化，零 GC（原始类型数组时）    │    │
│  │    不经过 IValue 包装                             │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### Layer 3 详细设计：分析状态直接数组

**核心思路：** 不动点迭代中的抽象状态不应经过 IValue/IStorage 路径。使用节点序号（seqId）直接索引的原始数组：

```kotlin
// 分析状态存储 — 独立于 IStorage
class AnalysisStateStore<S : AbstractState>(nodeCount: Int) {
    private val states: Array<S?> = arrayOfNulls(nodeCount)

    fun get(nodeSeqId: Int): S? = states[nodeSeqId]       // O(1)，零分配
    fun set(nodeSeqId: Int, state: S) {                    // O(1)，零分配
        states[nodeSeqId] = state
    }
}
```

**性能对比（单次属性读写）：**

| 方案 | 读延迟 | 写延迟 | GC 对象/次 | 适配性 |
|------|--------|--------|-----------|--------|
| NativeStorage HashMap | ~50ns | ~50ns | 0（已在堆上） | 良好，但 30GB GC 问题 |
| MapDB | ~3-6μs | ~3-6μs | 2-3（ByteArray + IValue） | 差 |
| RocksDB | ~1-5μs | ~1-5μs | 1-2（byte[] + 反序列化） | 差（不动点迭代延迟不可接受） |
| Chronicle Map | ~200-500ns | ~200-500ns | 0-1（flyweight） | 中等 |
| **直接数组索引** | **~5-10ns** | **~5-10ns** | **0** | **最优** |

**关键优势：**
- 分析状态数组与图结构属性分离，不动点迭代不触碰 30GB 图数据
- 数组大小 = 节点数 × 状态大小，对于 2M 节点 × 64B 状态 ≈ 128MB（可控）
- 零序列化：分析状态是 JVM 对象引用，无需 IValue 包装
- GC 友好：单个大数组 vs 数百万个 HashMap.Node

### 修订后的方案对比

| 方案 | 拓扑构建 | 不动点属性读写 | 30GB 图数据 GC | 改动量 |
|------|---------|---------------|---------------|--------|
| 当前 NativeStorage | O(1) | ~50ns | **2-10s Full GC** | — |
| flatgraph 全量迁移 | 批量 O(N+M) | **O(N) 数组重分配** | 极低 | 极大 |
| RocksDB 全量 | O(log n) | ~1-5μs（过慢） | 极低 | 大 |
| **分层架构** | O(1) 或批量 | **~5-10ns** | 可控 | 中 |
| **分阶段冻结** | O(1) 构建 + 冻结迁移 | ~50ns（缓存命中）/ ~3μs（miss） | **极低**（冻结层 off-heap） | 小-中 |

> **分阶段冻结架构**详见 `phased-immutable-storage.md`。核心思路：append-only + 阶段完成后冻结到 MapDB off-heap，利用现有 DeltaStorageImpl 分层模式，冻结层加 SoftReference 缓存。与分析状态外置（Layer 3）互补——冻结解决 30GB 图数据 GC 问题，外置数组解决不动点迭代性能问题。

### 修订后的建议路径

#### 短期（立即见效，零/小架构改动）

1. **分析状态外置**：将不动点迭代的抽象状态从 IStorage 属性中移出，使用独立的 `Array<S>` 按节点 seqId 索引。这是**最高优先级**优化——直接消除不动点迭代中的所有序列化和 HashMap 开销。
2. **分阶段冻结**（`phased-immutable-storage.md`）：利用现有 `DeltaStorageImpl`，`baseDelta` 传入 `MapDBStorageImpl(readOnly)`，每个分析阶段完成后冻结当前层到 off-heap。改动量 ~200 行，堆内存从 ~30GB 降到活跃层大小。
3. **G1GC 调优**：`-XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m`
4. **NodeID intern pool**：消除重复 NodeID 对象

#### 中期（减少图结构内存）

5. **属性合并存储**：每实体一个 ByteArray 存所有持久属性（非分析状态），减少对象数量
6. **Eclipse Collections**：替换 HashMap/HashSet，减少 ~30% 内存
7. **节点 seqId 映射**：为 NodeID 分配连续整数 ID，支持数组索引（Layer 3 前提）
8. **冻结层优化**：邻接列表预计算拆分（IN/OUT 分离），SoftReference 热数据缓存

#### 长期（架构级优化）

9. **自定义 mmap flat file**：替代 MapDB 作为冻结格式，零依赖 + 更低读延迟
10. **CSR 邻接表**：flatgraph 风格的压缩稀疏行格式，替代 JGraphT（如拓扑遍历成为瓶颈）
11. **列式属性存储**：按属性类型打包为原始类型数组
