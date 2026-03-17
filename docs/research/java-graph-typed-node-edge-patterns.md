# Research: Typed Node and Edge Creation in Major Java Graph Libraries

**Date**: 2026-03-16
**Scope**: Analysis of how JGraphT, Neo4j, Gremlin/TinkerPop, and MapDB handle typed node and edge object creation.

---

## Executive Summary

This research examines five major Java graph libraries and one commons-graph project to understand how they solve the problem of creating typed node and edge objects. The analysis reveals **three primary patterns**:

1. **Generic Type Parameters** (JGraphT) — library-agnostic, user provides vertex/edge types
2. **Abstract Factory Methods** (commons-graph, TinkerPop) — subclass implements protected abstract factory methods
3. **Reflection + Builder Pattern** (Neo4j embedded, MapDB) — lazy instantiation with constructor/property injection

---

## Library-by-Library Analysis

### 1. JGraphT 1.4.0 — Generic Type Parameters

**Problem**: How do we allow users to define custom vertex and edge types without the library prescribing specific classes?

**Solution**: JGraphT does not provide its own vertex or edge classes. Instead:
- Users specify vertex and edge types as **generic parameters** when instantiating a graph
- The graph holds `Map<V, DirectedEdgeContainer<V, E>>` where `V` and `E` are user-supplied
- No abstract factory methods; vertices and edges are **user-owned objects**

**Code Pattern**:
```java
// User defines their own vertex type
public class URLVertex {
    private String url;

    public URLVertex(String url) { this.url = url; }
    public String getUrl() { return url; }
    // Note: must implement equals() and hashCode()
}

// User defines their own edge type (optional; can use DefaultEdge)
public class URLEdge {
    public URLEdge() { }
}

// Graph is generic over vertex and edge types
Graph<URLVertex, URLEdge> graph = new DirectedPseudograph<>(URLEdge.class);

// Add vertices directly
URLVertex google = new URLVertex("http://www.google.com");
URLVertex wiki = new URLVertex("http://www.wikipedia.org");
graph.addVertex(google);
graph.addVertex(wiki);

// Add edges with vertex references
graph.addEdge(google, wiki, new URLEdge());
```

**Key Design Traits**:
- **No reflection** — all objects constructed explicitly by user code
- **No factory pattern** — users are responsible for vertex/edge construction
- **Type safety via generics** — `Graph<V, E>` ensures type correctness at compile time
- **Constraint**: Vertices and edges must properly implement `equals()` and `hashCode()` per Java contracts

**Performance Notes**:
- `DirectedPseudograph` uses `FastLookupGraphSpecificsStrategy` for O(1) edge lookup via `Map<Pair<V,V>, Set<E>>` supplementary index
- Incoming/outgoing edge lookup is O(1) direct Set reference return (not copied)
- `removeVertex(v)` is O(degree(v)) — cascades to remove all associated edges

**Limitations**:
- User bears responsibility for object pooling and lifecycle management
- No built-in serialization framework
- No integration with database persistence

---

### 2. Neo4j 3.5.35 (Embedded Java) — No Abstract Factory

**Problem**: How do we represent graph elements (nodes, relationships) when the backend manages storage?

**Solution**: Neo4j embedded does **not** use abstract factories or typed wrappers. Instead:
- All nodes and relationships are **opaque `Node` and `Relationship` interfaces** provided by Neo4j
- The user does **not** subclass; instead, they use properties to define types
- Neo4j generates unique internal `long` IDs for all elements
- Users interact via **Cypher queries or imperative API** (no object-oriented wrapping)

**Code Pattern**:
```java
// Create DB and transaction
GraphDatabaseService graphDB = new GraphDatabaseFactory()
    .newEmbeddedDatabaseBuilder(dbPath)
    .newGraphDatabase();

try (Transaction tx = graphDB.beginTx()) {
    // Create nodes directly via API (not via factory)
    Node person1 = graphDB.createNode(DynamicLabel.label("Person"));
    Node person2 = graphDB.createNode(DynamicLabel.label("Person"));

    // Set properties directly
    person1.setProperty("name", "Alice");
    person2.setProperty("name", "Bob");

    // Create relationship
    Relationship rel = person1.createRelationshipTo(person2,
        RelationshipType.withName("KNOWS"));
    rel.setProperty("since", 2020);

    // Query: relationships are opaque Neo4j objects
    for (Relationship r : person1.getRelationships(Direction.OUTGOING)) {
        System.out.println(r.getType());
    }

    tx.success();
}
```

**Key Design Traits**:
- **No user subclassing** — Neo4j provides sealed `Node` and `Relationship` interfaces
- **No factory methods** — nodes/relationships created directly via `graphDB.createNode()` or node-owned `createRelationshipTo()`
- **Label-based typing** — types are expressed as Neo4j labels and relationship types (strings), not Java classes
- **Properties-driven** — all custom attributes stored as dynamic properties, not typed fields

**Performance Notes**:
- All read/write operations must occur within a `Transaction` (mandatory)
- Nested transactions in 3.5 return `PlaceboTransaction` — inner `failure()` taints outer transaction
- Node and relationship objects are **lightweight wrappers** around internal `long` IDs; can be recreated on demand
- Property access triggers DB reads; no caching at the wrapper level

**Limitations**:
- No typed subclassing — all domain logic must be external to node/relationship objects
- Type information stored as string properties — no compile-time type safety
- Not suitable for OOP graph models where nodes/edges encapsulate behavior

---

### 3. Gremlin/TinkerPop (3.8.0 / 4.0) — Factory Methods in Graph Traversal

**Problem**: How do we support multiple backend databases (Neo4j, JanusGraph, ArangoDB, etc.) with a common API for vertex/edge creation?

**Solution**: TinkerPop uses a **graph-level `addV()` and `addE()` methods** that return **step objects** in the traversal DSL:
- Vertices and edges are identified by **opaque `id` values** (implementation-dependent)
- No user subclassing of vertex/edge types
- Types are expressed via **labels (vertex labels, edge labels)**, not Java classes
- Creation is **lazy** — `addV()` returns a traversal step, executed only when `.next()` or `.iterate()` is called

**Code Pattern**:
```groovy
// Gremlin Console or via GremlinClient
GraphTraversalSource g = graph.traversal()

// Create vertices via traversal API
def v1 = g.addV("person")
    .property("name", "marko")
    .property("age", 29)
    .next()

def v2 = g.addV("person")
    .property("name", "vadas")
    .property("age", 27)
    .next()

// Create edge via from/to traversal steps
g.addE("knows")
    .from(v1)
    .to(v2)
    .property("weight", 0.5)
    .next()

// Query vertices by label
def results = g.V().hasLabel("person").toList()

// Note: Vertex and Edge objects are opaque to user code
// They are handled as generic traversal step results
```

**Key Design Traits**:
- **No abstract factories** — graph provides DSL methods (`addV`, `addE`)
- **No user subclassing** — vertices and edges are opaque step results
- **Label-based typing** — types expressed as vertex labels and edge labels (strings)
- **Lazy evaluation** — traversal chains are not executed until terminal operations (`.next()`, `.iterate()`, etc.)
- **Database-agnostic** — same code works against Neo4j, TinkerGraph, JanusGraph, etc.

**Performance Notes**:
- Traversal compilation and optimization depend on backend
- Most databases support index-backed traversals (e.g., `hasLabel("person")` can use label index)
- Property lookup can be O(1) or O(n) depending on backend storage

**Limitations**:
- Opaque vertex/edge objects — no compile-time type information
- DSL-based — not suitable for imperative Java code patterns
- Type information only available at runtime via labels

---

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

---

## Comparison Matrix

| Aspect | JGraphT | Neo4j Embedded | TinkerPop/Gremlin | MapDB | Commons-Graph |
|--------|---------|---|---|---|---|
| **Abstraction** | Generic types | Opaque interfaces | Traversal DSL | Raw collections | Abstract factories |
| **Type Safety** | Compile-time (generics) | Runtime (labels) | Runtime (labels) | None | Compile-time (subclass) |
| **Factory Pattern** | None (user constructs) | None (createNode API) | DSL methods | None | Abstract methods |
| **Reflection Use** | None | Minimal (property access) | Minimal (backend-specific) | None | None |
| **Persistence** | User responsibility | Built-in (embedded DB) | Backend-specific | Supports off-heap | Pluggable via IStorage |
| **Object Subclassing** | Yes (user) | No | No | N/A | Yes (required) |
| **Graph Operations** | Rich (algorithms, traversal) | Cypher-based | Traversal-based | None | Query methods (traversal) |
| **Transaction Model** | None | Mandatory | Backend-specific | Optional (DB level) | Delegated to storage |
| **ID Semantics** | User objects (unrestricted) | Internal `long` IDs | Backend-specific opaque | None | Storage `Int` + user `String` |
| **Concurrent Access** | Optional via subclass | Built-in (locking) | Backend-specific | Optional (external lock) | Delegated to storage |

---

## Key Findings

### 1. Three Distinct Patterns Emerge

**Pattern A: Generic Types** (JGraphT)
- Library treats vertices and edges as **generic type parameters**
- User responsible for construction, equals/hashCode, lifecycle
- Simple, flexible, but places burden on user

**Pattern B: Abstract Factories** (Commons-Graph)
- Library defines abstract factory methods in base graph class
- Subclass instantiates typed entities via factory
- Good balance of compile-time type safety and abstraction

**Pattern C: DSL/Opaque Objects** (Neo4j, TinkerPop)
- Library provides API (imperative or DSL) for object creation
- Objects remain opaque; type expressed as labels/properties
- No OOP polymorphism; type information is metadata

### 2. Reflection Usage

- **None in JGraphT, MapDB, Commons-Graph** — all object construction is explicit
- **Minimal in Neo4j** — only for property access (could use reflection but doesn't require it)
- **Backend-dependent in TinkerPop** — actual reflection depends on which backend is plugged in

**Conclusion**: Major libraries **avoid reflection** for node/edge creation. They prefer explicit construction or DSL-based APIs.

### 3. Abstract Methods vs. Factory Interfaces

**Observation**: Most libraries that use factories do so via:
- **Abstract methods** (Commons-Graph: `newNodeObj`, `newEdgeObj`)
- **Constructor/initializer** (Neo4j: `graphDB.createNode()`)
- **DSL methods** (TinkerPop: `g.addV()`, `g.addE()`)

None use separate factory **interface types** (e.g., `NodeFactory<N>` passed as argument). **Recommendation**: Abstract methods are sufficient.

### 4. Lazy Initialization and Caching

- **JGraphT**: No caching; user objects live in user's vertex/edge collections
- **Commons-Graph**: `SoftReference` caching of wrapper objects; lazy resolution of edge endpoints
- **Neo4j**: Lightweight wrappers around internal IDs; can recreate on demand
- **TinkerPop**: Backend-specific caching strategies

**Pattern**: When library wraps storage IDs in typed objects, caching is important for performance.

### 5. ID Semantics

| Library | User-Facing ID | Storage ID |
|---------|---|---|
| JGraphT | User-supplied objects (any type with equals/hashCode) | Implicit (internal adjacency lists) |
| Neo4j | User-supplied properties + Neo4j-generated `long` | Internal DB ID (`long`) |
| TinkerPop | User-supplied properties + backend-specific opaque ID | Backend-specific |
| Commons-Graph | User `String` (NodeID) | Storage `Int` + graph-layer cache |

**Key Insight**: Commons-Graph's dual-ID scheme (user `String` + storage `Int`) provides good separation: storage is backend-agnostic (uses opaque `Int`), while domain is user-friendly (uses `String`).

### 6. Complex Initialization Logic

**How libraries handle complex initialization**:

1. **JGraphT**: No special handling; user constructs objects before adding to graph
2. **Neo4j**: Properties set after creation via `node.setProperty()`; no complex initialization
3. **TinkerPop**: Properties set via `.property()` chained calls in traversal
4. **Commons-Graph**: Properties set via storage during `addNode()` or via entity property delegates

**Pattern**: Complex initialization is deferred to **after entity creation** or handled via **builder-style chaining**.

---

## Recommendations for Commons-Graph

**Strengths of Current Design**:
1. ✓ Abstract factory methods are sufficient (no need for separate factory interfaces)
2. ✓ `SoftReference` caching balances memory and performance well
3. ✓ Lazy resolution of edge endpoints is a good optimization
4. ✓ Compile-time type safety via typed subclasses
5. ✓ Storage-agnostic design via `IStorage` injection

**Potential Improvements**:
1. Consider providing a **default factory implementation** for users who don't need typed subclasses (e.g., `DefaultNode`, `DefaultEdge`)
2. Add **builder pattern support** for complex node/edge initialization (e.g., `graph.addNode("alice").withProperty("age", 30).create()`)
3. Document **factory method contract** clearly — parameters, when called, error handling
4. Add **type registry pattern** — optional registration of node/edge types for reflective instantiation (if needed for serialization)

---

## Conclusion

The three main patterns for handling typed node and edge creation in Java graph libraries are:

1. **Generic Types** — suitable for small, known set of vertex/edge types (JGraphT)
2. **Abstract Factories** — good balance of type safety and flexibility (Commons-Graph, internal to many libraries)
3. **DSL/Opaque Objects** — for database-backed systems and polyglot querying (Neo4j, TinkerPop)

Commons-Graph's **abstract factory method pattern is well-chosen**. It provides compile-time type safety, supports multiple backends, and avoids reflection. The combination of factory methods + SoftReference caching + lazy property resolution is a sound design that scales well.

**No major libraries use reflection for node/edge creation**; they prefer explicit construction or DSL-based APIs. This validates Commons-Graph's choice of explicit factory methods.
