# Implementation Notes

## commons-value 0.1.0

Paired designs: all modules (global dependency)

### APIs

**`IValue`** Top-level value type interface; subtypes: `NullVal`, `StrVal`, `NumVal`, `ListVal`, `SetVal`, `MapVal`.
**`StrVal`** `StrVal(core: String)` / `"text".strVal` -- string value.
**`NumVal`** `NumVal(core: Number)` / `42.numVal` -- numeric value (Int/Long/Float/Double interchangeable).
**`ListVal`** `ListVal(vararg vals)` / `listOf(v1, v2).listVal` -- ordered list value.
**`SetVal`** Duplicate-free set value.
**`MapVal`** Key-value mapping value; `add(key, value)` is a mutable operation.
**`NullVal`** Null value (distinct from Kotlin `null`: `NullVal` is a valid `IValue` instance meaning "value present but empty"; `null` means key absent).
**`DftByteArraySerializerImpl`** `serialize(value): ByteArray` / `deserialize(bytes): IValue` -- singleton binary serializer; used by MapDB and Neo4j.
**`DftCharBufferSerializerImpl`** `serialize(value): CharBuffer` / `deserialize(buf): IValue` -- singleton text serializer; used by GML/CSV IO.
**`ListVal?.orEmpty()`** / **`SetVal?.orEmpty()`** / **`MapVal?.orEmpty()`** Returns empty collection value for `null`.

### Developer Instructions

- Prefer extension property construction (`"text".strVal`, `42.numVal`); equivalent to direct construction.
- `NullVal` vs Kotlin `null`: `NullVal` is a valid instance; `null` means key absent.
- `MapVal.add(key, value)` is mutable; in MapDB off-heap storage, re-`put` after modification.
- `ListVal` internal `core` is `MutableList<IValue>`; concurrent modification risk during iteration.
- Both serializers are stateless singleton `object`s, safe for concurrent reuse.

| Serializer | Output type | Use case |
|------------|------------|----------|
| `DftByteArraySerializerImpl` | `ByteArray` | MapDB, Neo4j property storage (binary, compact) |
| `DftCharBufferSerializerImpl` | `CharBuffer` | GML/CSV text IO (readable, JSON-like encoding) |

---

## JGraphT 1.4.0

Paired designs: `design-storage.md`, `design-label.md`

### APIs

**`DirectedPseudograph`** `DirectedPseudograph(String::class.java)` -- directed pseudograph (allows self-loops and parallel edges); used by `JgraphtStorageImpl`.
**`SimpleDirectedGraph`** `SimpleDirectedGraph(DefaultEdge::class.java)` -- directed simple graph (no self-loops, no parallel edges); used for lattice structure.
**`Graph`** `addVertex(v)` / `removeVertex(v)` / `addEdge(src, dst, edge)` / `removeEdge(edge)` / `incomingEdgesOf(v)` / `outgoingEdgesOf(v)` -- O(1) adjacency. `getAllEdges(src, dst)` returns `null` when vertex does not exist.
**`GmlExporter`** / **`GmlImporter`** -- GML IO with attribute providers/consumers.
**`SupplierUtil`** `createIntegerSupplier()` / `createStringSupplier()` -- auto-incrementing suppliers for GML IO.

### Developer Instructions

- `incomingEdgesOf` / `outgoingEdgesOf` return internal Set references; copy before modification.
- `getAllEdges(from, to)` returns `null` when a vertex does not exist; validate before calling.
- `removeVertex(id)` automatically removes all incident edges.
- GML export uses `Int` as temporary vertex/edge substitute via attribute providers.
- `EXPORT_VERTEX_LABELS` / `EXPORT_EDGE_LABELS` must be explicitly set to `true`.

### Neo4j Label vs commons-graph Label

Neo4j `org.neo4j.graphdb.Label` is a node classification interface (schema indexing). The commons-graph `Label` value class is an analysis-domain label stored as a `StrVal` property, unrelated to Neo4j's `Label`.

---

## MapDB 3.0.5

Paired design: `design-storage.md`

### APIs

**`DBMaker`** `memoryDB()` / `fileDB(file)`. Config: `concurrencyDisable()`, `fileMmapEnableIfSupported()`, `closeOnJvmShutdown()`, `make()`.
**`DB`** `hashMap(name, keySerializer, valueSerializer)` / `indexTreeList(name)`. Builder: `.createOrOpen()` / `.create()`. `.counterEnable()` makes `size` O(1). `close()` persists.
**`Serializer`** `STRING` / `BYTE_ARRAY`. Custom: `serialize(DataOutput2, T)` / `deserialize(DataInput2, Int): T`. `isTrusted()` returns `true`.

### Developer Instructions

- File-based DB collections must use `.createOrOpen()`, not `.create()`.
- MapDB 3.x non-transactional mode: `db.close()` persists data; no `commit()` needed.
- `closeOnJvmShutdown()` is a safety net; call `close()` explicitly as primary path.
- File-based DB should always enable `fileMmapEnableIfSupported()`.

| Collection | Usage | Notes |
|-----------|-------|-------|
| `hashMap` | node/edge properties, graph structure | O(1) read/write, primary storage |
| `indexTreeList` | IO import/export sequences | ordered list, suited for full-scan iteration |

`MapDbValSerializer<T>` bridges `DftByteArraySerializerImpl` and MapDB `Serializer`. `MapDbIDSerializer` serializes `Int` IDs through `NumVal`.

---

## Neo4j 5.26.0

Paired design: `design-storage.md`

### APIs

**`DatabaseManagementServiceBuilder`** `DatabaseManagementServiceBuilder(path).build()` -- creates `DatabaseManagementService`.
**`GraphDatabaseService`** `database.beginTx()` -- open transaction; all reads and writes require a transaction in Neo4j 5.x.
**`Transaction`** `tx.commit()` / `tx.close()` / `tx.findNode(label, key, value)` / `tx.findRelationship(type, key, value)` / `tx.createNode(label)` / `tx.schema()`.
**`Node`** `setProperty` / `getProperty` / `getRelationships(Direction)` / `delete()` / `createRelationshipTo(other, type)`.
**`Relationship`** `delete()` / `startNode` / `endNode` / `type`.

### Developer Instructions

- Use `lazy` for DB initialization to avoid constructor blocking.
- All operations wrapped in `readTx {}` / `writeTx {}`; `writeTx` calls `tx.commit()` on success.
- `deleteNode` must first delete all incident relationships; otherwise Neo4j throws `ConstraintViolationException`.
- `clear()` must delete relationships before nodes.
- Property values serialized to `ByteArray` via `DftByteArraySerializerImpl`.
- Internal `__sid__` property stores the IStorage Int ID as a `Long`. Schema indexes on `__sid__` enable indexed lookups.

### Neo4j 5.x Transaction Model

Neo4j 5.x uses explicit `tx.commit()`. Uncommitted transactions roll back on `tx.close()`. Each `beginTx()` creates an independent transaction.

### Neo4JUtils Extension Functions

- `Entity.keys` -- filters out reserved properties (`__sid__`, `__tag__`), returns user-visible property names.
- `Entity[name]` / `Entity[name] = value` -- property get/set via `DftByteArraySerializerImpl`; throws `InvalidPropNameException` for reserved names.

---

## Libraries

| Library | Version | Use |
|---------|---------|-----|
| `edu.jhu.cobra:commons-value` | 0.1.0 | `IValue` type system, serializers |
| `org.jgrapht:jgrapht-core` | 1.4.0 | Graph data structures |
| `org.jgrapht:jgrapht-io` | 1.4.0 | GML import/export |
| `org.mapdb:mapdb` | 3.0.5 | Embedded off-heap storage |
| `org.neo4j:neo4j` | 5.26.0 | Embedded graph database |
