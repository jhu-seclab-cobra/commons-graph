# Glossary — Graph

## Graph data

### Graph
- **Definition:** A directed property graph with application-supplied vertex identifiers and source–destination–tag connection identities, independent of its storage backend.
- **Example:** Vertices a and b joined by (a, b, calls) → a Graph containing two Nodes and one Edge.
- **Source:** `IGraph.kt` `IGraph`; `AbcMultipleGraph.kt` `AbcMultipleGraph`; `docs/model.md` Graph lifecycle

### Node
- **Definition:** A vertex registered in a Graph under an application-supplied identifier and carrying typed properties.
- **Example:** `graph.addNode("a")` → the Node identified by a.
- **Source:** `AbcNode.kt` `AbcNode`; `AbcMultipleGraph.kt` `addNode`

### Edge
- **Definition:** A directed connection added between two Nodes of one Graph and identified by its source identifier, destination identifier and tag.
- **Example:** `graph.addEdge("a", "b", "calls")` → the Edge (a, b, calls).
- **Source:** `AbcEdge.kt` `AbcEdge`; `AbcMultipleGraph.kt` `addEdge`

### Entity
- **Definition:** A Node or Edge whose typed properties are accessed through its bound storage.
- **Example:** A Node a and an Edge (a, b, calls) → two Entities with separate property sets.
- **Source:** `IEntity.kt` `IEntity`; `AbcEntity.kt` `AbcEntity`

### Storage
- **Definition:** A directed property graph engine that manages vertex records, connection records, adjacency and typed properties through storage-local integer identifiers.
- **Example:** A `NativeStorageImpl` containing two node rows and one edge row → the Storage behind a Graph.
- **Source:** `storage/IStorage.kt` `IStorage`; `docs/design-storage.md` IStorage
