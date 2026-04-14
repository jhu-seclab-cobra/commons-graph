# JGraphT 1.4.0 — Implementation Notes

Paired designs: `storage.design.md`, `core/label.design.md`

---

## APIs

**`org.jgrapht.graph.DirectedPseudograph`** `DirectedPseudograph(String::class.java)` — directed pseudograph (allows self-loops and parallel edges); used by `JgraphtStorageImpl` as main graph storage. Internally uses String-typed vertices/edges with Int-to-String bidirectional mapping.
**`org.jgrapht.graph.SimpleDirectedGraph`** `SimpleDirectedGraph(DefaultEdge::class.java)` — directed simple graph (no self-loops, no parallel edges); used for lattice structure.
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
- `getAllEdges(from, to)` returns `null` when a vertex does not exist; validate node existence before calling to avoid NPE.
- `removeVertex(id)` automatically removes all incident edges; avoid manual edge cleanup before calling it.

---

## Design-specific

### Neo4j Label vs commons-graph Label

Neo4j `org.neo4j.graphdb.Label` is a node classification interface (used for schema indexing). JGraphT has no equivalent concept. The commons-graph `Label` value class (`edu.jhu.cobra.commons.graph.label`) is an analysis-domain label stored as a `StrVal` property, unrelated to Neo4j's `Label`.
