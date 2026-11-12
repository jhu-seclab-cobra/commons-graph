[design-graph](design-graph.md) owns these open questions.

# Open Gaps

- **Owner marks hold one set per node row.** `NodeOwners` stores each node row's
  owners as one `SetVal` holding one `StrVal` per graph; no row shares it with
  another, `NodeOwners.mark` copies the set to add a graph, and
  `NodeOwners.holds` allocates a `StrVal` per check. Edge rows carry no owner
  set. Open question: whether storages that many graphs share need a compact
  owner representation.
