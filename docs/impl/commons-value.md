# cobra-commons-value 0.1.0 — Implementation Notes

Paired designs: all modules (global dependency)

---

## APIs

**`edu.jhu.cobra.commons.value.IValue`** 值类型顶层接口；子类型：`NullVal`、`StrVal`、`NumVal`、`ListVal`、`SetVal`、`MapVal`。
**`edu.jhu.cobra.commons.value.StrVal`** `StrVal(core: String)` / `"text".strVal` — 字符串值；扩展属性与直接构造等价。
**`edu.jhu.cobra.commons.value.NumVal`** `NumVal(core: Number)` / `42.numVal` — 数字值（Int/Long/Float/Double 通用）。
**`edu.jhu.cobra.commons.value.ListVal`** `ListVal(vararg vals)` / `listOf(v1, v2).listVal` — 有序列表值。
**`edu.jhu.cobra.commons.value.SetVal`** 无重复集合值。
**`edu.jhu.cobra.commons.value.MapVal`** 键值对映射值；`add(key, value)` 是可变操作。
**`edu.jhu.cobra.commons.value.NullVal`** 空值（与 Kotlin `null` 不同：`NullVal` 是有效 `IValue` 实例，表示"值存在但为空"；`null` 表示键不存在）。
**`DftByteArraySerializerImpl`** `serialize(value): ByteArray` / `deserialize(bytes): IValue` — 单例二进制序列化器；用于 MapDB、Neo4j 属性存储。
**`DftCharBufferSerializerImpl`** `serialize(value): CharBuffer` / `deserialize(buf): IValue` — 单例文本序列化器；用于 GML/CSV IO。
**`IEntity.ID.serialize`** `nodeID.serialize` → `StrVal(name)`；`edgeID.serialize` → `ListVal(srcName, dstName, eType)`。
**`IValue.toEntityID<T>()`** 反序列化扩展：`someValue.toEntityID<NodeID>()` / `someValue.toEntityID<EdgeID>()`。
**`IValue?.orEmpty()`** 对 `null` 或 `NullVal` 返回对应类型的空值，避免空判断。

---

## Libraries

- `edu.jhu.cobra:commons-value:0.1.0` — `IValue` 类型体系、序列化器、EntityID 序列化

---

## Developer instructions

- 优先使用扩展属性构造（`"text".strVal`、`42.numVal`），与直接构造等价，无额外分配
- `NullVal` 与 Kotlin `null` 语义不同：`NullVal` 是有效实例，`null` 表示键不存在
- `MapVal.add(key, value)` 是可变操作；在 MapDB 存储中修改后需重新 `put` 回 DB（off-heap 语义）
- `ListVal` 内部 `core` 为 `MutableList<IValue>`，迭代时注意并发修改风险
- 两个序列化器均为无状态单例 `object`，可安全复用于并发场景

---

## Design-specific

### 性能分析：跨模块序列化开销

**来源：** deepwiki jankotek/mapdb（Serializer 分析）、deepwiki neo4j/neo4j（ValueRepresentation 分析）

`DftByteArraySerializerImpl` 是所有持久化模块的序列化瓶颈。每次属性读写都经过完整的 serialize/deserialize 链路：

| 模块 | 序列化路径 | 每次属性操作开销 |
|------|-----------|----------------|
| Neo4j | `IValue → DftByteArraySerializerImpl → ByteArray → Neo4j setProperty` | serialize + DB write |
| MapDB | `IValue → DftByteArraySerializerImpl → ByteArray → Serializer.BYTE_ARRAY → DataOutput2` | serialize + MapDB serialize + off-heap write |
| JGraphT GML | `IValue → DftCharBufferSerializerImpl → CharBuffer → GML attribute` | serialize（仅 IO 时，非高频） |

**热点场景：** `getNodeProperties` / `getEdgeProperties` 需逐属性 deserialize，N 个属性的节点需要 N 次反序列化。对于属性密集型图，这是主要性能瓶颈。

**优化方向：**
- 批量序列化：将整个属性 Map 一次序列化为单个 ByteArray（减少 N 次调用为 1 次）
- 缓存层：在 entity 层缓存已反序列化的属性值，dirty 时才写回
- 原生类型直通：对 Neo4j 场景，基础类型（String/Number）直接存为原生属性，跳过序列化

### 序列化器选用

| 序列化器 | 输出类型 | 适用场景 |
|----------|----------|----------|
| `DftByteArraySerializerImpl` | `ByteArray` | MapDB、Neo4j 属性存储（二进制，紧凑）|
| `DftCharBufferSerializerImpl` | `CharBuffer` | GML/CSV 文本 IO（可读，基于 JSON-like 编码）|

### 各模块用法

```kotlin
// impl-mapdb：MapDbValSerializer 委托 DftByteArraySerializerImpl
override fun serialize(out: DataOutput2, value: T) =
    delegator.serialize(out, core.serialize(value))

// impl-neo4j：Neo4JUtils 直接调用
setProperty(name, DftByteArraySerializerImpl.serialize(value))
val deserialized = DftByteArraySerializerImpl.deserialize(bytes)

// impl-jgrapht GML IO：DftCharBufferSerializerImpl
val attribute = value.asCharBuffer.let { DftCharBufferSerializerImpl.serialize(it) }
val value = DftCharBufferSerializerImpl.deserialize(charBuffer)
```

### EntityID 序列化

在 MapDB 中 EdgeID 序列化后存于 `SetVal`（adjacency 列表），在 GML IO 中序列化后存为字符串属性，在 Neo4j 中序列化为 ByteArray 后存为 `META_ID` 属性。

### kotlinx-coroutines 1.10.2

列于 `gradle/libs.versions.toml`，但当前生产代码中未使用。所有并发控制由 `ReentrantReadWriteLock`（JDK）实现。该依赖为预留。
