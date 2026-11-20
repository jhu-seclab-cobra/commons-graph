[design-graph](design-graph.md) owns these open questions.

# Open Gaps

- **Owner marks hold one set per node row.** `NodeOwners` stores each node row's
  owners as one `SetVal` holding one `StrVal` per graph; no row shares it with
  another, `NodeOwners.mark` copies the set to add a graph, and
  `NodeOwners.holds` allocates a `StrVal` per check. Edge rows carry no owner
  set. Open question: whether storages that many graphs share need a compact
  owner representation.
- **The standalone build pins commons-value 0.2.0.** `gradle/libs.versions.toml`
  selects the published commons-value 0.2.0, whose `StrVal` stores its argument
  unchanged, so in the standalone build the two texts `"é"` and `"\uDCC3\uDCA9"`
  stay two node IDs and two labels, and `AbcMultipleGraphTest` and `LabelTest`
  fail their canonical-text cases. A composite build that includes commons-value
  from source gets the canonical text. Closes when a commons-value release with
  canonical `StrVal` is published and pinned.
