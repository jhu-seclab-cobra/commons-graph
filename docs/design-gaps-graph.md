[design-graph](design-graph.md) owns these open questions.

# Open Gaps

- **Edge lookup by triple reads every outgoing edge of the source.**
  `AbcMultipleGraph.findEdge` scans `getOutgoingEdges(src)`, and a labeled
  `PosetTrait.addEdge` of a new edge runs that scan twice: through `getEdge` and
  through the duplicate check of `AbcMultipleGraph.addEdge`. Adding D edges from
  one source therefore costs Θ(D²) edge-structure reads. Scanning the smaller of
  the source's outgoing and the destination's incoming edges needs a degree
  read, which `LayeredStorageImpl` answers by copying the adjacency set in
  O(degree); the exact adjacency projection
  ([design-exact-adjacency.md](design-exact-adjacency.md)) covers only its one
  configured edge tag. Open question: which index answers a triple lookup for
  every tag at a cost independent of the source's out-degree.
- **Owner marks hold one set per node row.** `NodeOwners` stores each node row's
  owners as one `SetVal` holding one `StrVal` per graph; no row shares it with
  another, `NodeOwners.mark` copies the set to add a graph, and
  `NodeOwners.holds` allocates a `StrVal` per check. Edge rows carry no owner
  set. Open question: whether storages that many graphs share need a compact
  owner representation.
