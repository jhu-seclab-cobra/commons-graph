# Neo4j 3.5.35 — Implementation Notes

Paired design: `storage.design.md`

---

## APIs

**`org.neo4j.graphdb.factory.GraphDatabaseFactory`** `GraphDatabaseFactory().newEmbeddedDatabaseBuilder(path).newGraphDatabase()` — 创建嵌入式 DB；返回 `GraphDatabaseService`。
**`org.neo4j.graphdb.GraphDatabaseService`** `database.beginTx()` — 开启事务，返回 `Transaction`；嵌入式模式下所有读写必须在事务内。
**`org.neo4j.graphdb.Transaction`** `tx.success()` — 标记事务为提交；`tx.failure()` — 标记为回滚；`tx.close()` / `use {}` — 结束事务。3.5 中嵌套 `beginTx()` 返回 `PlaceboTransaction`，内层 `failure()` 会将外层标记为 rollback。
**`org.neo4j.graphdb.Node`** `node.setProperty(name, value)` / `node.getProperty(name, default)` — 属性读写；`node.getRelationships(Direction, RelationshipType...)` — 获取关联边；`node.delete()` — 删除节点（有关系时抛 `ConstraintViolationException`）。
**`org.neo4j.graphdb.Relationship`** `relationship.delete()` — 删除关系；`relationship.getStartNode()` / `getEndNode()` — 获取端点。
**`org.neo4j.graphdb.Direction`** `INCOMING` / `OUTGOING` / `BOTH` — 关系方向枚举。
**`org.neo4j.graphdb.RelationshipType`** `RelationshipType.withName(name)` — 按名称创建关系类型。
**`java.lang.Runtime`** `Runtime.getRuntime().addShutdownHook(Thread { graphDB.shutdown() })` — JVM 退出兜底关闭；主动 `close()` 仍是首选。

---

## Libraries

- `org.neo4j:neo4j:3.5.35` — 嵌入式图数据库引擎（非 bolt/driver 模式）
- `edu.jhu.cobra:commons-value:0.1.0` — `IValue` 序列化体系，属性值统一序列化为 `ByteArray` 存入 Neo4j 原生属性

---

## Developer instructions

- 使用 `lazy` 延迟初始化 DB，避免构造函数阻塞
- 所有读写操作封装在 `readTx {}` / `writeTx {}` 中，`writeTx` 通过 `runCatching` 控制 `success()` / `failure()`
- `deleteNode` 前必须先删除所有关联关系，否则 Neo4j 抛 `ConstraintViolationException`
- `clear()` 中必须先删关系再删节点（顺序敏感）
- 属性值通过 `DftByteArraySerializerImpl` 序列化为 `ByteArray` 存储，保留 `IValue` 类型但失去 Neo4j 原生类型查询能力

---

## Design-specific

### 内存索引

```kotlin
private val node2ElementIdMap: ConcurrentMap<NodeID, String> = ConcurrentHashMap()
private val edge2ElementIdMap: ConcurrentMap<EdgeID, String> = ConcurrentHashMap()
```

维护 `NodeID → Neo4j internal long ID` 的内存映射，`containsNode` / `nodeIDs` 等全部命中内存。初始化时通过 `readTx` 从 DB 重建索引，保证重启后状态一致。

### 嵌套事务问题

`deleteNodes` 在外层 `writeTx` 中调用 `deleteNode`，后者内部再次 `writeTx`，导致嵌套 `beginTx()`。Neo4j 3.5 返回 `PlaceboTransaction`（N 次删除产生 N+1 个事务对象）。内层 `tx.failure()` 会将外层标记为 rollback，批量删除部分失败时整批回滚。

建议提取无事务的内部实现供批量操作复用：

```kotlin
private fun GraphDatabaseService.deleteNodeInternal(id: NodeID) {
    val node = getNodeById(node2ElementIdMap.remove(id)!!.toLong())
    node.relationships.forEach { edge ->
        edge2ElementIdMap.remove(edge.storageID)
        edge.delete()
    }
    node.delete()
}
```

### clear() 顺序

当前代码先删节点再删关系，触发 `ConstraintViolationException`。正确顺序：

```kotlin
override fun clear(): Boolean = writeTx {
    allRelationships.forEach { it.delete() }   // 先删关系
    allNodes.forEach { it.delete() }           // 再删节点
    node2ElementIdMap.clear()
    edge2ElementIdMap.clear()
    true
}
```

### 性能分析：属性序列化为 ByteArray 的代价

**来源：** Neo4j 源码分析（`ValueRepresentation` 枚举、`GraphDatabaseInternalSettings`）；deepwiki neo4j/neo4j

**问题：** 当前所有属性值通过 `DftByteArraySerializerImpl` 统一序列化为 `ByteArray` 存入 Neo4j 原生属性。这导致：

| 影响维度 | 原生类型（String/int/long） | ByteArray |
|---------|--------------------------|-----------|
| 存储效率 | Neo4j 内部有 `string_block_size` / `array_block_size` 优化 | 失去类型感知的紧凑存储 |
| 读写开销 | 直接读写，零序列化成本 | 每次读写需 serialize/deserialize |
| 索引支持 | 支持 RANGE / TEXT / POINT 索引 | 不可索引（opaque binary） |
| Cypher 查询 | 支持范围查询、字符串匹配等 | 无法做类型感知查询 |
| 批量读取 | O(n) 直接访问 | O(n) × deserialization |

**结论：** 在当前嵌入式主键索引场景下可接受（不依赖 Cypher 查询/属性索引）。但若未来需要 Cypher 范围查询或属性索引，应将基础类型（String/Number）直接存为原生类型，仅对复杂类型（ListVal/MapVal）使用 ByteArray 序列化。

### 性能分析：嵌套事务对象开销

**来源：** Neo4j 3.5 内核；deepwiki neo4j/neo4j

**问题：** N 次 `deleteNode` 产生 N+1 个事务对象（1 个外层真实事务 + N 个 `PlaceboTransaction`）。

**量化影响：**
- 每个 `PlaceboTransaction` 创建：对象分配 + `beginTx()` 内部同步检查
- 内层 `tx.failure()` 污染外层：批量删除若中途异常，已成功的删除也会回滚
- 建议：提取无事务的 `deleteNodeInternal` 方法（如上），单一 `writeTx` 包裹批量操作

**Neo4j 内核参考：** `Operations.nodeDetachDelete(nodeId, consumer)` 是内核级原子删除（删除节点及所有关系），但在 3.5 嵌入式 Java API 中不可直接调用，需通过 Cypher `DETACH DELETE` 或手动遍历 `node.relationships`。

### 性能分析：getEdgesBetween

**来源：** Neo4j `Node.getRelationships()` API

**当前复杂度：** O(out_degree) × (DB node lookup + ByteArray deserialization)

过滤所有出边，对每条边读取 `endNode.storageID`（DB 访问 + ByteArray 反序列化）。

**优化方案：**

| 方案 | 复杂度 | 改动 |
|------|--------|------|
| 按 RelationshipType 过滤 | O(out_degree_of_type) | `getRelationships(OUTGOING, withName(eType))` 缩小范围；需 EdgeID 中的 eType |
| 内存二级索引 | O(1) | 维护 `Map<Pair<NodeID,NodeID>, Set<EdgeID>>` 内存索引 |
| Cypher MATCH 查询 | 取决于 Neo4j 优化器 | `MATCH (a)-[r]->(b) WHERE id(a)=X AND id(b)=Y RETURN r`；嵌入式可用但引入 Cypher 解析开销 |

当前实现在 out_degree 不大时可接受。若高频调用且度数大，推荐内存二级索引方案。
