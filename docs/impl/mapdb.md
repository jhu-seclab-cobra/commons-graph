# MapDB 3.0.5 — Implementation Notes

Paired design: `storage.design.md`

---

## APIs

**`org.mapdb.DBMaker`** `DBMaker.memoryDB()` — 内存 DB；`DBMaker.fileDB(file)` — 文件 DB。链式配置：`concurrencyDisable()` 禁用内部锁（单线程场景）；`fileMmapEnableIfSupported()` 启用 mmap IO（文件型性能显著提升）；`closeOnJvmShutdown()` JVM 退出自动关闭；`make()` 创建 `DB` 实例。
**`org.mapdb.DB`** `db.hashMap(name, keySerializer, valueSerializer)` — 创建 HashMap builder；`db.indexTreeList(name)` — 创建有序列表 builder。builder 用 `.createOrOpen()` 打开或创建集合（`.create()` 在集合已存在时抛 `DBException.WrongConfiguration`）。`.counterEnable()` — 使 `size` 查询 O(1)。`db.close()` — 关闭并持久化（MapDB 3.x 非事务模式不需要 `commit()`）。
**`org.mapdb.Serializer`** `Serializer.STRING` — 字符串序列化器；`Serializer.BYTE_ARRAY` — 字节数组序列化器。自定义序列化器实现 `serialize(DataOutput2, T)` / `deserialize(DataInput2, Int): T`。`isTrusted()` 返回 `true` 跳过额外校验。
**`org.mapdb.DataOutput2`** / **`org.mapdb.DataInput2`** — MapDB 自定义二进制流接口，供 `Serializer` 实现使用。

---

## Libraries

- `org.mapdb:mapdb:3.0.5` — 嵌入式 Java 数据库引擎（off-heap 存储，支持 HashMap / TreeMap / IndexTreeList）
- `edu.jhu.cobra:commons-value:0.1.0` — `IValue` 序列化体系，通过 `MapDbValSerializer` 委托 `DftByteArraySerializerImpl`

---

## Developer instructions

- 文件型 DB 集合必须用 `.createOrOpen()`，不要用 `.create()`（重启后重新打开会失败）
- 仅在明确需要全新状态时使用 `.create()`（如单次写 IO 临时文件）
- `MapVal.add(key, value)` 是可变操作；对 off-heap 存储修改后需重新 `put` 回 DB，否则修改不持久化
- MapDB 3.x 非事务模式下 `db.close()` 即持久化，不需要 `commit()`
- `closeOnJvmShutdown()` 作为兜底，主动调用 `close()` 是首选

---

## Design-specific

### 集合类型选择

| 集合 | 用法 | 说明 |
|------|------|------|
| `hashMap` | 节点/边属性、图结构 | O(1) 读写，主存储 |
| `indexTreeList` | IO 导入导出序列 | 有序列表，适合全量遍历 |

#### EntityPropertyMap 内部结构

- `identities`：`hashMap<EntityID, SetVal>().counterEnable().createOrOpen()` — 存储每个实体的属性键集合
- `propertiesMap`：`hashMap<String, IValue>().createOrOpen()` — 扁平化存储，键格式为 `"$entityId:$propName"`

`counterEnable()` 使 `size` 查询 O(1)（维护内部计数器），不加则每次 `size` 需 O(n) 扫描。

### 性能分析：adjacency 单 SetVal 的序列化开销

**来源：** MapDB 源码 `StoreDirect`、`Store2.serialize`；deepwiki jankotek/mapdb

**问题：** `graphStructure` 将每个节点的入边和出边全部存在一个 `SetVal` 中。每次方向查询的代价：

```
读取 → 反序列化整个 SetVal → 遍历过滤方向 → 丢弃不需要的一半
```

**量化分析：**

| 步骤 | 当前代价 | 说明 |
|------|----------|------|
| 读取 | O(1) hashMap get | 从 off-heap 读入 ByteArray |
| 反序列化 | O(degree_total) | `DftByteArraySerializerImpl.deserialize` 整个 SetVal |
| 过滤 | O(degree_total) | 逐边解析 EdgeID 再比较 srcNid/dstNid |
| **总计** | **O(degree_total)** | 即使只需入边或出边，也要处理全部 |

**MapDB copy-on-write 问题：** MapDB `StoreDirect` 使用 copy-on-write —— 修改 SetVal 后必须整体重新序列化并写回。即使只添加一条边，也需要：序列化整个新 SetVal → 分配新 off-heap 空间 → 释放旧空间。度数越大，写入开销越大。

**MapDB 不支持 partial update：** `Store` 接口的 `update` 方法始终操作整条记录。`updateRaw(ByteBuffer)` 内部也转为全量 `byte[]` 写入。

**方案对比：**

| 方案 | 读复杂度 | 写复杂度 | 实现改动 |
|------|----------|----------|----------|
| 当前：单 SetVal | O(degree_total) | O(degree_total) 全量重写 | — |
| **方案 A：拆分方向** | O(degree_in 或 degree_out) | O(degree_方向) 半量重写 | 两个独立 hashMap |
| **方案 B：组合键** | O(1) 单边查询 | O(1) 单条写入 | key=`"nodeId:IN:edgeId"` / `"nodeId:OUT:edgeId"`，value=空或边属性 |
| **方案 C：BTreeMap 前缀键** | O(log n) 范围查询 | O(log n) | key=`"nodeId:IN:edgeId"`，利用 `STRING_DELTA` 压缩共同前缀 |

**建议：** 方案 A 改动最小，读写开销均减半：

```kotlin
private val incomingStructure = dbManager.hashMap("incoming", ...).createOrOpen()
private val outgoingStructure = dbManager.hashMap("outgoing", ...).createOrOpen()
```

若需进一步优化（避免度数大时的全量序列化），方案 B 使用组合键将每条边独立存储，完全消除集合序列化开销，但增加 key 数量。

### 性能分析：concurrencyDisable() 效果

**来源：** MapDB 源码 `Store2`（`ReentrantLock`、`ReentrantReadWriteLock`）；deepwiki jankotek/mapdb

`concurrencyDisable()` 移除 MapDB 内部的 `structuralLock`、`newRecidLock` 和分段 `locks` 数组的锁获取/释放开销。在单线程场景下：
- 消除每次 get/put 的锁竞争检查
- 对于高频属性读写（如 `getNodeProperties` 逐属性访问），累积节省可观

`MapDBStorageImpl`（单线程）已正确启用。`MapDBConcurStorageImpl`（多线程）使用外部 `ReentrantReadWriteLock` 协调，MapDB 内部并发仍开启。

### 性能分析：fileMmapEnableIfSupported() 效果

**来源：** MapDB 源码 `MappedFileVol`、`FileChannel.map`；deepwiki jankotek/mapdb

| 模式 | 读写方式 | 优势 |
|------|----------|------|
| RandomAccessFile（默认） | 每次读写触发系统调用 | 兼容性好 |
| **Memory-mapped（mmap）** | 直接内存访问，OS 管理页缓存 | 大文件读写显著提升；避免 user/kernel 切换 |

文件型 DB 应始终启用 `fileMmapEnableIfSupported()`（当前已正确使用）。

### graphStructure 使用 `.create()` 而非 `.createOrOpen()`

当前代码对文件型 DB 在重启后重新打开时会抛 `DBException.WrongConfiguration`。应改为 `.createOrOpen()`。

### IO 中的资源泄漏风险

`MapDbGraphIOImpl.isValidFile` 异常路径没有关闭 DB。应用 `use` 模式：

```kotlin
return try {
    DBMaker.fileDB(file.toFile()).fileMmapEnableIfSupported().make().use { db ->
        db.indexTreeList("nodes").open()
        db.indexTreeList("edges").open()
    }
    true
} catch (e: Exception) {
    false
}
```

### 序列化

`MapDbValSerializer<T>` 桥接 `DftByteArraySerializerImpl` 与 MapDB `Serializer` 接口，委托 `Serializer.BYTE_ARRAY` 处理底层 bytes。`isTrusted()` 返回 `true` 与 `Serializer.BYTE_ARRAY` 一致。

内置 Serializer 对比：

| Serializer | 适用场景 |
|------------|----------|
| `Serializer.STRING` | 键为纯字符串时（如 nodeId.name）用于 hashMap key |
| `Serializer.BYTE_ARRAY` | 委托给它处理底层 bytes，内置实现比自定义更优 |
| `MapDbValSerializer<T>` | `IValue` 子类型，桥接 `DftByteArraySerializerImpl` |
| `MapDbIDSerializer<T>` | `IEntity.ID`，复用同一序列化路径 |
