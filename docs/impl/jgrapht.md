# JGraphT 1.4.0 — Implementation Notes

Paired designs: `storage.design.md`, `core/label.design.md`

---

## APIs

**`org.jgrapht.graph.DirectedPseudograph`** `DirectedPseudograph(String::class.java)` — directed pseudograph (allows self-loops and parallel edges); used by `JgraphtStorageImpl` as main graph storage. Internally uses String-typed vertices/edges with Int-to-String bidirectional mapping.
**`org.jgrapht.graph.SimpleDirectedGraph`** `SimpleDirectedGraph(DefaultEdge::class.java)` — directed simple graph (no self-loops, no parallel edges); used by `JgraphtLatticeImpl` for lattice structure.
**`org.jgrapht.Graph`** `addVertex(v)` / `removeVertex(v)` — vertex operations; `removeVertex` automatically removes all incident edges. `addEdge(src, dst, edge)` / `removeEdge(edge)` — edge operations. `incomingEdgesOf(v)` / `outgoingEdgesOf(v)` — O(1) returning internal Set reference. `getAllEdges(src, dst)` — returns all edges between two vertices; returns `null` when vertex does not exist (NPE risk). `getEdgeSource(e)` / `getEdgeTarget(e)` — get edge endpoints.
**`org.jgrapht.nio.gml.GmlExporter`** `GmlExporter()` — create exporter; `setVertexAttributeProvider` / `setEdgeAttributeProvider` — attach attributes; `setParameter(Parameter.EXPORT_VERTEX_LABELS, true)` — enable label export (must be set); `exportGraph(graph, file)` — export to file.
**`org.jgrapht.nio.gml.GmlImporter`** `GmlImporter()` — create importer; `addVertexAttributeConsumer` / `addEdgeAttributeConsumer` — attribute callbacks; target graph requires `vertexSupplier` / `edgeSupplier`.
**`org.jgrapht.util.SupplierUtil`** `createIntegerSupplier()` — auto-incrementing integer supplier for GML IO; `createStringSupplier()` — string supplier.

---

## Libraries

- `org.jgrapht:jgrapht-core:1.4.0` — graph data structures (DirectedPseudograph, SimpleDirectedGraph)
- `org.jgrapht:jgrapht-io:1.4.0` — graph IO (GmlExporter, GmlImporter)
- `edu.jhu.cobra:commons-value:0.1.0` — `IValue` serialization; GML IO uses `DftCharBufferSerializerImpl`

---

## Developer instructions

- GML export uses `Int` as temporary vertex/edge substitute, attaching real IDs and properties via attribute providers (avoids GML format limitations on complex key types).
- GML import requires `vertexSupplier` / `edgeSupplier`; `GmlImporter` cannot auto-supply objects without them.
- `EXPORT_VERTEX_LABELS` / `EXPORT_EDGE_LABELS` must be explicitly set to `true`; otherwise label fields are omitted from output.
- `incomingEdgesOf` / `outgoingEdgesOf` return internal Set references; copy before modification.

---

## Design-specific

### DirectedPseudograph internals

**Source:** JGraphT source `DirectedSpecifics`, `DirectedEdgeContainer`; deepwiki jgrapht/jgrapht

JGraphT `DirectedPseudograph` uses `DirectedSpecifics` internally: `Map<V, DirectedEdgeContainer<V, E>>`. Each `DirectedEdgeContainer` maintains separate incoming/outgoing `Set<E>`.

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| `incomingEdgesOf(v)` / `outgoingEdgesOf(v)` | O(1) | Returns internal Set reference (not a copy) |
| `getAllEdges(src, dst)` | O(out_degree(src)) | Scans src outgoing edges filtering by target |
| `removeVertex(v)` | O(degree(v)) | Automatically deletes all incident edges |
| `addEdge(src, dst, e)` | O(1) | Inserts into src outgoing set and dst incoming set |
| `containsEdge(src, dst)` | O(out_degree(src)) | Scans src outgoing edges (default strategy) |

### FastLookupGraphSpecificsStrategy

**Source:** JGraphT source `FastLookupDirectedSpecifics`, `GraphPerformanceTest`; deepwiki jgrapht/jgrapht

JGraphT provides `FastLookupGraphSpecificsStrategy`, maintaining an additional `Map<Pair<V,V>, Set<E>>` (`touchingVerticesToEdgeMap`) for O(1) vertex-pair edge lookup.

| Operation | DefaultStrategy | FastLookupStrategy |
|-----------|-----------------|--------------------|
| `getEdge(u, v)` / `containsEdge(u, v)` | O(out_degree(u)) | **O(1)** |
| `getAllEdges(u, v)` | O(out_degree(u)) | **O(1)** |
| `addEdge` | O(1) | O(1) + extra map put |
| Memory overhead | Baseline | Additional `Map<Pair, Set>` |

**Recommendation:** Enable `FastLookupGraphSpecificsStrategy` when `getEdgesBetween` is a hot path or the graph is dense. Memory cost: one extra Map entry per edge.

### Dual-structure synchronization

`JgraphtStorageImpl` maintains two structures:
- `jgtGraph`: JGraphT graph (`DirectedPseudograph<String, String>`) for topology queries (in/out edges)
- `nodeProperties` / `edgeProperties`: JVM HashMap keyed by Int IDs for property storage

The Int-to-String bidirectional mapping (`intToVertex`/`vertexToInt`, `intToEdge`/`edgeToInt`) translates between external Int IDs and JGraphT internal String vertices/edges. Both structures must stay synchronized. `containsNode` / `containsEdge` only check `nodeProperties` / `edgeProperties`, not the JGraphT graph.

### getEdgesBetween potential NPE

`getAllEdges(from, to)` returns `null` when a vertex does not exist. Calling `.toSet()` on null throws NPE. Validate node existence before calling.

### deleteNode redundant operations

**Source:** JGraphT source `DirectedSpecifics.removeEdgeFromTouchingVertices`

`removeVertex(id)` in JGraphT automatically removes all incident edges (O(degree)). Current code manually removes each edge before `removeVertex`, causing each edge to be visited twice: 2 x O(degree).

**Optimization:** Collect incident edge IDs, clean only property/mapping maps, then call `removeVertex` to handle topology. Total: O(degree), saving half the operations.
