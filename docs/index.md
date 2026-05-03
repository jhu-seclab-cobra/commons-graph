# commons-graph — Backend-agnostic directed property graph library

| File | Content |
|------|---------|
| [idea.md](idea.md) | Concept document — problem, scope, terminology, data flow, scenarios |
| [model.md](model.md) | Domain model — entity identity, relations, state, invariants |
| [design-entity.md](design-entity.md) | Entity module — InternalID, NodeID, IEntity, AbcEntity, AbcNode, AbcEdge |
| [design-graph.md](design-graph.md) | Graph module — IGraph, AbcMultipleGraph, AbcSimpleGraph |
| [design-storage.md](design-storage.md) | Storage module — IStorage and implementations (Native, Concurrent, Layered) |
| [design-group.md](design-group.md) | **DEPRECATED** Node grouping — TraitGroup (to be removed) |
| [design-label.md](design-label.md) | Label system — Label, IPoset, PosetDftImpl, PosetTrait |
| [spec.md](spec.md) | Algorithms — edge lookup, BFS, visibility filtering, layered queries |
| [impl.md](impl.md) | Library APIs — commons-value, JGraphT, MapDB, Neo4j |
| [todo.md](todo.md) | Tasks — TraitGroup removal migration |
| [performance.md](performance.md) | Core module benchmarks — NativeStorage, LayeredStorage, graph-level |
| [performance-optimizations.md](performance-optimizations.md) | Optimization log — completed, rejected, candidates, insights |
| [performance-jgrapht.md](performance-jgrapht.md) | JGraphT module benchmarks |
| [performance-mapdb.md](performance-mapdb.md) | MapDB module benchmarks |
| [performance-neo4j.md](performance-neo4j.md) | Neo4j module benchmarks |
| [bugs.md](bugs.md) | Known bugs and technical debt |
| [java-graph-typed-node-edge-patterns.md](research/java-graph-typed-node-edge-patterns.md) | Typed node/edge creation patterns in Java graph libraries |
| [llms.txt](llms.txt) | LLM-consumable entry point |
| [llms/](llms/) | LLM-consumable module docs |
