# commons-graph — Backend-agnostic directed property graph library

| File | Content |
|------|---------|
| [concept.md](concept.md) | Concept document — problem, scope, terminology, data flow, scenarios |
| [model.md](model.md) | Domain model — entity identity, relations, state, invariants |
| [design-entity.md](design-entity.md) | Entity module — InternalID, NodeID, IEntity, AbcEntity, AbcNode, AbcEdge |
| [design-graph.md](design-graph.md) | Graph module — IGraph, AbcMultipleGraph, AbcSimpleGraph |
| [design-storage.md](design-storage.md) | Storage module — IStorage and implementations (Native, Concurrent, Layered) |
| [design-label.md](design-label.md) | Label system — Label, IPoset, PosetDftImpl, PosetTrait |
| [spec-graph.md](spec-graph.md) | Algorithms — edge lookup, BFS, visibility filtering, layered queries |
| [spec-poset.md](spec-poset.md) | Poset ancestor query — DAG-correct ancestor closure |
| [impl.md](impl.md) | Library APIs — commons-value, JGraphT, MapDB, Neo4j |
| [performance-core.md](performance-core.md) | Core module benchmarks — NativeStorage, LayeredStorage, graph-level |
| [performance-optimizations.md](performance-optimizations.md) | Optimization log — completed, rejected, candidates, insights |
| [performance-jgrapht.md](performance-jgrapht.md) | JGraphT module benchmarks |
| [performance-mapdb.md](performance-mapdb.md) | MapDB module benchmarks |
| [performance-neo4j.md](performance-neo4j.md) | Neo4j module benchmarks |
| [research/java-graph-typed-node-edge-patterns.md](research/java-graph-typed-node-edge-patterns.md) | Typed node/edge creation patterns in Java graph libraries — summary, comparison, findings |
| [research/java-graph-typed-node-edge-patterns-libraries-1.md](research/java-graph-typed-node-edge-patterns-libraries-1.md) | Library analysis — JGraphT, Neo4j embedded, Gremlin/TinkerPop |
| [research/java-graph-typed-node-edge-patterns-libraries-2.md](research/java-graph-typed-node-edge-patterns-libraries-2.md) | Library analysis — MapDB, JanusGraph, commons-graph |
| [llms.txt](llms.txt) | LLM-consumable entry point — links the llms/ module docs |
