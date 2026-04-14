# Neo4j 5.26.0 — Implementation Notes

Paired design: `storage.design.md`

---

## APIs

**`org.neo4j.dbms.api.DatabaseManagementServiceBuilder`** `DatabaseManagementServiceBuilder(path).build()` — creates `DatabaseManagementService`; manages embedded database lifecycle.
**`org.neo4j.dbms.api.DatabaseManagementService`** `managementService.database(name)` — get `GraphDatabaseService` by name; `managementService.shutdown()` — close all databases.
**`org.neo4j.graphdb.GraphDatabaseService`** `database.beginTx()` — open transaction, returns `Transaction`; all reads and writes require a transaction in Neo4j 5.x embedded mode.
**`org.neo4j.graphdb.Transaction`** `tx.commit()` — commit transaction; `tx.close()` / `use {}` — end transaction. In 5.x, uncommitted transactions are rolled back on close. `tx.findNode(label, key, value)` — indexed node lookup. `tx.findRelationship(type, key, value)` — indexed relationship lookup. `tx.createNode(label)` — create a labeled node. `tx.schema()` — access schema for index creation.
**`org.neo4j.graphdb.Node`** `node.setProperty(name, value)` / `node.getProperty(name)` / `node.getProperty(name, default)` — property read/write; `node.getRelationships(Direction)` — get incident relationships; `node.delete()` — delete node (throws `ConstraintViolationException` if relationships exist). `node.createRelationshipTo(other, type)` — create relationship.
**`org.neo4j.graphdb.Relationship`** `relationship.delete()` — delete relationship; `relationship.startNode` / `endNode` — get endpoints; `relationship.type` — get relationship type.
**`org.neo4j.graphdb.Direction`** `INCOMING` / `OUTGOING` / `BOTH` — relationship direction enum.
**`org.neo4j.graphdb.RelationshipType`** `RelationshipType.withName(name)` — create relationship type by name.
**`org.neo4j.graphdb.Label`** `Label.label(name)` — create node label by name.

---

## Libraries

- `org.neo4j:neo4j:5.26.0` — embedded graph database engine (not bolt/driver mode)
- `edu.jhu.cobra:commons-value:0.1.0` — `IValue` serialization; property values serialized to `ByteArray` via `DftByteArraySerializerImpl` into Neo4j native properties

---

## Developer instructions

- Use `lazy` for DB initialization to avoid constructor blocking.
- All read/write operations wrapped in `readTx {}` / `writeTx {}`; `writeTx` calls `tx.commit()` on success.
- `deleteNode` must first delete all incident relationships; otherwise Neo4j throws `ConstraintViolationException`.
- `clear()` must delete relationships before nodes (order matters).
- Property values serialized to `ByteArray` via `DftByteArraySerializerImpl`; preserves `IValue` type but loses Neo4j native type query capability.
- Internal `__sid__` property stores the IStorage Int ID as a `Long` on each node/relationship. Schema indexes on `__sid__` enable indexed lookups via `tx.findNode()` / `tx.findRelationship()`.

---

## Design-specific

### Zero in-memory ID mapping

Neo4j 5.x implementation uses no in-memory ID maps. Entity lookups go through Neo4j transactions and schema-indexed `__sid__` properties:

```kotlin
private fun Transaction.findNodeBySid(id: Int) =
    findNode(NODE_LABEL, SID, id.toLong())

private fun Transaction.findEdgeBySid(id: Int) =
    findRelationship(EDGE_TYPE, SID, id.toLong())
```

`containsNode` / `containsEdge` open a `readTx` to check existence. `nodeIDs` / `edgeIDs` iterate all nodes/relationships to collect SIDs. This trades per-operation latency for unlimited capacity (only disk space limits, not JVM heap).

### Neo4j 5.x transaction model

Neo4j 5.x uses explicit `tx.commit()`. Uncommitted transactions roll back on `tx.close()`. Nested `beginTx()` is no longer supported in the 5.x API; each `beginTx()` creates an independent transaction.

`readTx` and `writeTx` each open a fresh transaction. `writeTx` commits; `readTx` closes without commit (read-only).

### Property serialization as ByteArray

**Source:** Neo4j source analysis (`ValueRepresentation` enum); deepwiki neo4j/neo4j

All properties are serialized to `ByteArray` via `DftByteArraySerializerImpl` and stored as Neo4j native byte-array properties. Internal reserved properties (`__sid__`, `__tag__`) are stored as native `Long`/`String`.

| Dimension | Native types (String/int/long) | ByteArray |
|-----------|-------------------------------|-----------|
| Storage efficiency | Neo4j internal `string_block_size` / `array_block_size` optimization | Loses type-aware compact storage |
| Read/write overhead | Direct, zero serialization cost | serialize/deserialize per access |
| Index support | RANGE / TEXT / POINT indexes | Not indexable (opaque binary) |
| Cypher query | Range, string matching | No type-aware queries |

**Conclusion:** Acceptable for the current embedded primary-key-index scenario (no Cypher query / property index dependency). If future Cypher range queries or property indexes are needed, store primitive types (String/Number) as native types and reserve ByteArray for complex types (ListVal/MapVal).

### clear() order

Relationships must be deleted before nodes. Deleting a node with existing relationships throws `ConstraintViolationException`.

### Neo4JUtils extension functions

`Neo4JUtils.kt` provides extension functions on `org.neo4j.graphdb.Entity`:
- `Entity.keys` — filters out reserved properties (`__meta_id__`, `__sid__`, `__tag__`), returns user-visible property names.
- `Entity[name]` / `Entity[name] = value` — property get/set via `DftByteArraySerializerImpl`; throws `InvalidPropNameException` for reserved names.
