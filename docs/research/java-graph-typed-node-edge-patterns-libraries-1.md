# Research: Typed Node and Edge Creation — Library Analysis (1 of 2)

Part of [java-graph-typed-node-edge-patterns.md](java-graph-typed-node-edge-patterns.md). Covers JGraphT, Neo4j embedded, and Gremlin/TinkerPop.

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

