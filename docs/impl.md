# Implementation Notes

## APIs

### commons-value 0.1.0

**[IValue]** Top-level value type interface; subtypes: `NullVal`, `StrVal`, `IntVal`, `FloatVal`, `ListVal`, `SetVal`, `MapVal`.
**[StrVal]** `StrVal(core: String)` / `"text".strVal` -- string value.
**[IntVal]** `IntVal(core: Long)` / `42.intVal` -- integer value (Int/Long stored as Long).
**[FloatVal]** `FloatVal(core: Double)` / `3.14.floatVal` -- floating-point value (Float/Double stored as Double).
**[ListVal]** `ListVal(vararg vals)` / `listOf(v1, v2).listVal` -- ordered list value.
**[SetVal]** Duplicate-free set value.
**[MapVal]** Key-value mapping value; `add(key, value)` is mutable.
**[NullVal]** Null value -- distinct from Kotlin `null` (`NullVal` = "present but empty"; `null` = key absent).
**[DftByteArraySerializerImpl]** `serialize(value): ByteArray` / `deserialize(bytes): IValue` -- binary serializer for MapDB/Neo4j.
**[DftCharBufferSerializerImpl]** `serialize(value): CharBuffer` / `deserialize(buf): IValue` -- text serializer for GML/CSV IO.
**[ListVal?.orEmpty()]** / **[SetVal?.orEmpty()]** / **[MapVal?.orEmpty()]** -- returns empty collection value for `null`.

### JGraphT 1.4.0

**[DirectedPseudograph]** `DirectedPseudograph(String::class.java)` -- allows self-loops and parallel edges; used by `JgraphtStorageImpl`.
**[SimpleDirectedGraph]** `SimpleDirectedGraph(DefaultEdge::class.java)` -- no self-loops, no parallel edges; used for lattice structure.
**[Graph]** `addVertex(v)` / `removeVertex(v)` / `addEdge(src, dst, edge)` / `incomingEdgesOf(v)` / `outgoingEdgesOf(v)` -- O(1) adjacency.
**[GmlExporter]** / **[GmlImporter]** -- GML IO with attribute providers/consumers.
**[SupplierUtil]** `createIntegerSupplier()` / `createStringSupplier()` -- auto-incrementing suppliers for GML IO.

### MapDB 3.0.5

**[DBMaker]** `memoryDB()` / `fileDB(file)` -- config: `concurrencyDisable()`, `fileMmapEnableIfSupported()`, `closeOnJvmShutdown()`, `make()`.
**[DB]** `hashMap(name, keySerializer, valueSerializer)` / `indexTreeList(name)` -- `.createOrOpen()` / `.create()`, `.counterEnable()` makes `size` O(1).
**[Serializer]** `STRING` / `BYTE_ARRAY` -- custom: `serialize(DataOutput2, T)` / `deserialize(DataInput2, Int): T`.

### Neo4j 5.26.0

**[DatabaseManagementServiceBuilder]** `DatabaseManagementServiceBuilder(path).build()` -- creates `DatabaseManagementService`.
**[GraphDatabaseService]** `database.beginTx()` -- all reads and writes require a transaction in Neo4j 5.x.
**[Transaction]** `tx.commit()` / `tx.close()` / `tx.findNode(label, key, value)` / `tx.createNode(label)`.
**[Node]** `setProperty` / `getProperty` / `getRelationships(Direction)` / `delete()` / `createRelationshipTo(other, type)`.
**[Relationship]** `delete()` / `startNode` / `endNode` / `type`.

## Libraries

- `edu.jhu.cobra:commons-value:0.1.0` -- `IValue` type system, serializers
- `org.jgrapht:jgrapht-core:1.4.0` -- graph data structures
- `org.jgrapht:jgrapht-io:1.4.0` -- GML import/export
- `org.mapdb:mapdb:3.0.5` -- embedded off-heap storage
- `org.neo4j:neo4j:5.26.0` -- embedded graph database

## Developer Instructions

- `MapVal.add(key, value)` is mutable; in MapDB off-heap storage, re-`put` after modification.
- `incomingEdgesOf` / `outgoingEdgesOf` return internal Set references; copy before modification.
- `getAllEdges(from, to)` returns `null` when a vertex does not exist; validate before calling.
- `removeVertex(id)` automatically removes all incident edges.
- File-based DB collections: `.createOrOpen()`, not `.create()`.
- MapDB 3.x non-transactional mode: `db.close()` persists data; no `commit()` needed.
- File-based DB: always enable `fileMmapEnableIfSupported()`.
- Neo4j 5.x: all operations wrapped in `readTx {}` / `writeTx {}`; `writeTx` calls `tx.commit()` on success.
- Neo4j `deleteNode` must first delete all incident relationships; otherwise `ConstraintViolationException`.
- Neo4j `clear()` must delete relationships before nodes.
- Neo4j property values serialized to `ByteArray` via `DftByteArraySerializerImpl`.
- Neo4j internal `__sid__` property stores IStorage Int ID as `Long`; schema indexes on `__sid__` enable indexed lookups.
- `EXPORT_VERTEX_LABELS` / `EXPORT_EDGE_LABELS` must be explicitly set to `true` for GML export.
- `closeOnJvmShutdown()` is a safety net; call `close()` explicitly as primary path.
- Use `lazy` for Neo4j DB initialization to avoid constructor blocking.

## Design-Specific

- Neo4j `org.neo4j.graphdb.Label` is a node classification interface (schema indexing), unrelated to commons-graph `Label` value class.
- `MapDbValSerializer<T>` bridges `DftByteArraySerializerImpl` and MapDB `Serializer`.
- `Neo4JUtils` extension: `Entity.keys` filters reserved properties (`__sid__`, `__tag__`); `Entity[name]` throws `InvalidPropNameException` for reserved names.
