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

Split by library group:

- [java-graph-typed-node-edge-patterns-libraries-1.md](java-graph-typed-node-edge-patterns-libraries-1.md) — JGraphT, Neo4j embedded, Gremlin/TinkerPop
- [java-graph-typed-node-edge-patterns-libraries-2.md](java-graph-typed-node-edge-patterns-libraries-2.md) — MapDB, JanusGraph, commons-graph

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
