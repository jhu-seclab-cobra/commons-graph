# Glossary — Graph Algorithms

## Search and Components

### Depth-first search
- **Definition:** A graph traversal that follows each newly reached node's outgoing edges before returning to the node that discovered it.
- **Example:** `a → b → c` → discovery order `a, b, c`.
- **Source:** `algorithm/StronglyConnected.kt` `TarjanSearch`; `algorithm/DominatorTree.kt` `LengauerTarjan`
- **Literature:** [@tarjan_depthfirst_1972, sec. 2] — borrowed

### Strongly connected component (SCC)
- **Definition:** A maximal set of nodes of a directed graph in which each node is reachable from every other node.
- **Example:** `a → b → c → b` → components `{b, c}` and `{a}`.
- **Source:** `algorithm/StronglyConnected.kt` `tarjanScc`
- **Literature:** [@tarjan_depthfirst_1972, sec. 4] — borrowed

### Reverse topological order
- **Definition:** An order of strongly connected components in which each component precedes every distinct component that reaches it.
- **Example:** `a → b → c` → component order `{c}, {b}, {a}`.
- **Source:** `algorithm/StronglyConnected.kt` `tarjanScc`
- **Literature:** [@tarjan_depthfirst_1972, sec. 4] — same

## Dominators

### Dominator
- **Definition:** A node that lies on every path from a graph's root to a reachable node.
- **Example:** `r → a → b` → `a` is a dominator of `b`.
- **Source:** `docs/design-algorithm.md` Function Specifications; `algorithm/DominatorTree.kt` `immediateDominators`
- **Literature:** [@lengauer_fast_1979, sec. 1] — borrowed

### Immediate dominator
- **Is a:** Dominator
- **Definition:** The dominator of a node other than the node itself that is dominated by every other dominator distinct from that node.
- **Example:** `r → a → b` → the immediate dominator of `b` is `a`.
- **Source:** `algorithm/DominatorTree.kt` `immediateDominators`
- **Literature:** [@lengauer_fast_1979, sec. 1] — borrowed

### Dominator tree
- **Definition:** The rooted tree whose nodes are the reachable nodes of a directed graph and whose parent of each non-root node is its immediate dominator.
- **Example:** `r → a → b` → tree parents `a ↦ r, b ↦ a`.
- **Source:** `algorithm/DominatorTree.kt` `immediateDominators`
- **Literature:** [@lengauer_fast_1979, sec. 1] — borrowed

### Semidominator
- **Definition:** The earliest node in depth-first discovery order that reaches a given node by a nonempty path whose interior nodes are all discovered later than the given node.
- **Example:** `r → a → b` → the semidominator of `b` is `a`.
- **Source:** `algorithm/DominatorTree.kt` `LengauerTarjan`
- **Literature:** [@lengauer_fast_1979, sec. 2] — borrowed
