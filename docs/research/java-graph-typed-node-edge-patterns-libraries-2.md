# Research: Typed Node and Edge Creation — Library Analysis (2 of 2)

Part of [java-graph-typed-node-edge-patterns.md](java-graph-typed-node-edge-patterns.md). Covers MapDB, JanusGraph, and commons-graph.

### 4. MapDB 3.0.5 — No Object Wrapping

**Problem**: How do we manage graph structure in an off-heap embedded database?

**Solution**: MapDB is a **lower-level data structure library**, not a graph library. It provides primitives, not typed entities:
- Graph data stored as **collections of raw data** (maps, lists, sets) not wrapped in typed objects
- User is responsible for building graph semantics on top of MapDB primitives
- No factory methods; users directly manipulate maps and sets

**Code Pattern**:
```kotlin
// Create DB and collections
val db = DBMaker.memoryDB()
    .concurrencyDisable()
    .closeOnJvmShutdown()
    .make()

// Define your own structure (MapDB does not provide graph abstraction)
val nodes = db.hashMap<String, Map<String, IValue>>()
    .createOrOpen()  // key: nodeID, value: properties

val nodeStructure = db.hashMap<String, SetVal>()
    .createOrOpen()  // key: nodeID, value: set of edge IDs

val edgeProperties = db.hashMap<String, Map<String, IValue>>()
    .createOrOpen()  // key: edgeID, value: properties

// Add nodes manually
nodes["alice"] = mapOf("name" to "Alice Smith".strVal)
nodes["bob"] = mapOf("name" to "Bob Jones".strVal)

// Manage edges as SetVal in nodeStructure
nodeStructure["alice"] = setOf("edge1", "edge2").toSetVal()

// Query requires manual navigation
val aliceEdges = nodeStructure["alice"]  // Deserialized SetVal
```

**Key Design Traits**:
- **No abstraction** — MapDB is a persistence layer, not a domain model layer
- **No factory pattern** — users construct their own data structures
- **No typing** — collections are generic `Map<K, V>` and `Set<V>`
- **Serialization-focused** — all data serialized to bytes for off-heap storage

**Performance Notes**:
- Off-heap storage reduces GC pressure
- Memory-mapped file (mmap) I/O when `fileMmapEnableIfSupported()` is used
- Each collection read requires deserialization (e.g., `SetVal` deserialization is O(degree))
- Copy-on-write semantics: any modification triggers full re-serialization and write

**Limitations**:
- Not a graph library — requires user to build graph semantics
- No graph operations (traversal, shortest path, etc.)
- No query optimization
- Serialization/deserialization overhead for every access

---

### 5. JanusGraph (via TinkerPop) — Same Patterns as TinkerPop

**Analysis**: JanusGraph is built on TinkerPop, so it inherits TinkerPop's patterns:
- Vertices and edges created via Gremlin traversal DSL (`addV()`, `addE()`)
- Types expressed as labels, not Java classes
- No user subclassing or abstract factories
- Backend storage managed by JanusGraph (Cassandra, HBase, BerkeleyDB, etc.)

JanusGraph adds distributed graph optimizations (partitioning, replication) but does not change the vertex/edge creation abstraction.

---

### 6. Commons-Graph (This Project) — Abstract Factory Methods

**Problem**: How do we create typed node and edge objects while remaining storage-backend agnostic?

**Solution**: Commons-graph uses **protected abstract factory methods** that subclasses override to instantiate typed entities:

**Architecture Tiers**:

1. **Storage Tier** (`IStorage`) — opaque `Int` IDs, no domain types
   ```kotlin
   interface IStorage {
       fun addNode(properties: Map<String, IValue> = emptyMap()): Int
       fun addEdge(src: Int, dst: Int, type: String, ...): Int
       // ... property access ...
   }
   ```

2. **Graph Tier** (`AbcMultipleGraph<N : AbcNode, E : AbcEdge>`) — maps domain types to storage IDs
   ```kotlin
   abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> : IGraph<N, E> {
       abstract val storage: IStorage
       protected abstract fun newNodeObj(internalId: InternalID): N
       protected abstract fun newEdgeObj(
           internalId: InternalID,
           nodeIdResolver: (InternalID) -> NodeID
       ): E
   }
   ```

3. **Entity Tier** (`AbcNode`, `AbcEdge`) — typed wrappers bridging storage access
   ```kotlin
   abstract class AbcNode(
       protected val storage: IStorage,
       internal val internalId: InternalID
   ) : AbcEntity() {
       abstract override val type: Type
       override val id: NodeID
           get() = (storage.getNodeProperty(internalId, "__id__") as StrVal).core
   }

   abstract class AbcEdge(
       protected val storage: IStorage,
       internal val internalId: InternalID,
       private val nodeIdResolver: (InternalID) -> NodeID
   ) : AbcEntity() {
       abstract override val type: Type
       val srcNid: NodeID by lazy { nodeIdResolver(storage.getEdgeSrc(internalId)) }
       val dstNid: NodeID by lazy { nodeIdResolver(storage.getEdgeDst(internalId)) }
       val eType: String by lazy { storage.getEdgeType(internalId) }
   }
   ```

**Concrete Implementation Pattern**:
```kotlin
// User extends AbcNode for each node type
class PersonNode(storage: IStorage, internalId: InternalID) : AbcNode(storage, internalId) {
    override val type = object : AbcNode.Type { override val name = "person" }
    var fullName: StrVal by EntityProperty(default = "".strVal)
    var age: NumVal by EntityProperty(default = 0.numVal)
}

// User extends AbcEdge for each edge type
class KnowsEdge(storage: IStorage, internalId: InternalID) : AbcEdge(storage, internalId, ::resolveNodeId) {
    override val type = object : AbcEdge.Type { override val name = "knows" }
}

// User extends AbcMultipleGraph and provides factory methods
class MyGraph(override val storage: IStorage, override val posetStorage: IStorage)
    : AbcMultipleGraph<PersonNode, KnowsEdge>() {

    protected override fun newNodeObj(internalId: InternalID): PersonNode
        = PersonNode(storage, internalId)

    protected override fun newEdgeObj(
        internalId: InternalID,
        nodeIdResolver: (InternalID) -> NodeID
    ): KnowsEdge
        = KnowsEdge(storage, internalId)
}

// Usage
val storage = NativeStorageImpl()
val posetStorage = NativeStorageImpl()
val graph = MyGraph(storage, posetStorage)

// Add node
val node = graph.addNode("alice")  // Returns PersonNode
node.fullName = "Alice Smith".strVal
node.age = 30.numVal

// Query node
val retrieved = graph.getNode("alice")
println(retrieved?.fullName)  // Reads from storage
```

**Key Design Traits**:

- **Abstract factory methods** — subclass must override `newNodeObj` and `newEdgeObj` to instantiate typed entities
- **Storage-agnostic** — `IStorage` is injected; works with any backend (Native, MapDB, Neo4j, JGraphT, etc.)
- **Lazily resolved IDs** — `AbcEdge` uses lazy properties for src/dst/type resolution; only computed when accessed
- **SoftReference caching** — node and edge wrapper objects cached with `SoftReference`, allowing GC reclamation under memory pressure
- **Type information encoded in abstract classes** — each domain type (PersonNode, KnowsEdge) is a concrete subclass with its own `type` property

**Performance Characteristics**:
- Wrapper object creation is O(1)
- Property access delegates to storage (O(1) on in-memory backend, O(disk) on persistent)
- Edge endpoint resolution is lazy — only computed when accessed (reduces overhead for read-only workflows)
- Caching via `SoftReference` avoids repeated wrapper allocation

**Advantages over JGraphT**:
- No requirement for users to implement `equals()` and `hashCode()` on vertex/edge types
- Storage backend can be plugged in (not limited to in-memory data structures)
- Type information available at compile time via typed subclass
- Supports both flat and layered storage (freeze/thaw for phased analysis)

**Advantages over Neo4j/TinkerPop**:
- Typed entities with compile-time type safety (vs. label-based string typing)
- No database transaction boilerplate required
- Supports multiple backends without requiring Cypher or Gremlin DSL
- Can use property delegates for typed field access (vs. dictionary-based property lookup)

