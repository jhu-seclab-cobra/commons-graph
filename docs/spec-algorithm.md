# Graph algorithms

Terms: [graph algorithms](glossary/glossary-algorithm.md). Input and output types: [algorithm design](design-algorithm.md). These functions operate on finite directed adjacency maps; they require no Graph or Storage instance.

## Strongly connected components

### Problem

Partition every adjacency-map key and every successor into a Strongly connected component (SCC), with components returned in Reverse topological order; each vertex occurs exactly once. A successor with no map entry has no outgoing edge. The algorithm follows [@tarjan_depthfirst_1972].

### Steps

1. Keep discovery indices, lowlinks, an unresolved-vertex stack, a membership set and explicit Depth-first search frames; begin with no discovered vertices.
2. For each undiscovered map key, discover it: assign the next index and the same lowlink, push it on the unresolved stack and push a frame for its outgoing-edge iterator.
3. For the frame at the top, consume its next successor. Discover an undiscovered successor with step 2; for a successor still on the unresolved stack, lower the current lowlink to the minimum of itself and that successor’s discovery index.
4. When the frame has no successor left, remove it. If its lowlink equals its discovery index, pop the unresolved stack through that vertex and emit the popped vertices as one component.
5. If a parent frame remains, lower the parent’s lowlink by the completed child’s lowlink; continue until no frame remains, then resume step 2 with the next map key.

### Invariants

- Each discovered vertex receives one discovery index and belongs to at most one emitted component.
- The unresolved stack contains discovered vertices not yet assigned to a component.
- A frame resumes at its next unconsumed successor after its child frame completes.
- A completed component has no outgoing path to a distinct component that remains un-emitted.

### Termination

Each finite adjacency iterator advances once per outgoing edge; each vertex is discovered once and each frame is removed once. Explicit frames bound traversal state independently of the JVM call stack.

### Complexity

Expected O(V + E) time and O(V) auxiliary space for V distinct vertices and E successor occurrences, with constant-time key hashing/equality. The result holds V vertices across its component sets. An empty map returns no component; an isolated key and a self-loop each form a singleton component. For `a → b → c → b`, the result is `{b,c}`, then `{a}`.

## Immediate dominators

### Problem

For a root and a finite adjacency map, map every reachable non-root vertex to its Immediate dominator, yielding the parent relation of its Dominator tree. Omit the root and unreachable vertices; a missing adjacency entry is empty. The algorithm follows [@lengauer_fast_1979] with simple ancestor linking and path compression.

### Steps

1. Compute Depth-first search discovery order from the root with an explicit stack; record the discovery index and tree parent of each reachable vertex.
2. Collect reachable predecessors. For each vertex initialize its semidominator to itself, its best label to itself, its ancestor and immediate dominator to absent, and its bucket to empty.
3. Process non-root vertices in reverse discovery order. For each predecessor, evaluate the predecessor as in step 6; lower the vertex’s semidominator to the earliest semidominator obtained.
4. Add the vertex to the bucket of its semidominator, link its ancestor to its tree parent, then resolve that parent’s bucket: evaluate each bucket member, select that result when its semidominator is earlier than the member’s, otherwise select the parent, and empty the bucket.
5. Process non-root vertices in discovery order. If a selected immediate dominator differs from the semidominator, replace it with its selected dominator’s immediate dominator; return the resulting parent map.
6. To evaluate a vertex with no linked ancestor, return it. Otherwise collect its ancestor path while a grandparent exists, then process that path from the highest collected ancestor downward: retain the label with the earliest semidominator and replace each ancestor by its grandparent; return the vertex’s retained label.

### Invariants

- Discovery indices, predecessor lists and tree parents describe only reachable vertices.
- A linked ancestor points to a lower discovery index, so its chain cannot cycle.
- Each resolved bucket is empty before the reverse-order walk continues.
- The final correction processes an earlier selected dominator before a later vertex that uses it.

### Termination

The discovery walk consumes finite successor lists, both order passes visit each reachable vertex once, and every evaluation follows a strictly decreasing ancestor chain. Path processing uses explicit stacks rather than recursive calls.

### Complexity

A conservative bound for this implementation is O((V + E) * V) time and O(V + E) auxiliary space, including predecessor lists and pending discovery edges, for V reachable vertices and E successor occurrences. Each of O(V + E) evaluations follows at most V ancestors; this bound does not claim the tighter bound of a different linking strategy. Hash-map operations assume constant expected cost. A root with no successors returns an empty map; in `r → a → b`, the result is `a ↦ r, b ↦ a`, and in `r → a,b; a,b → c`, c maps to r.
