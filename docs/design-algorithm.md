# Graph Algorithm Design

## Design Overview

- **Classes:** Private `TarjanSearch` and `LengauerTarjan`; neither is part of the public interface.
- **Relationships:** Each public function creates one search whose state belongs to that call.
- **Abstract:** None.
- **Exceptions:** No algorithm-specific exception type.
- **Dependency roles:** Kotlin collections describe the input; `NodeID` identifies nodes for `immediateDominators`.

The public functions live in `edu.jhu.cobra.commons.graph.algorithm` in the core `commons-graph` module.
They accept directed graphs as maps of nodes to their outgoing neighbours, without requiring graph objects,
labels or a storage backend. Their names and input types differ because `tarjanScc` accepts any key type,
while `immediateDominators` uses the library's `NodeID`.

## Class / Type Specifications

### `TarjanSearch<K>` (private)

- **Responsibility:** Own the state of one `tarjanScc` call.
- **State:** Input map, node indices, lowlinks, membership set, explicit stacks and component list.
- **Methods:** `run` returns the result; private methods open, walk and close nodes and collect a component.
- **Lifetime:** All search state is released with the call except the returned components.

### `LengauerTarjan` (private)

- **Responsibility:** Own the state of one `immediateDominators` call.
- **State:** Reachable nodes and their depth-first order, predecessors, semidominators, ancestors,
  labels, immediate dominators and buckets. The private `DepthFirstOrder` holds nodes, indices and parents.
- **Methods:** `run` returns the result; private methods collect predecessors, resolve buckets,
  evaluate and compress paths, and compute depth-first order.
- **Values:** Private `NONE = -1` marks a missing array index; it is fixed by the array representation.
- **Lifetime:** Search state belongs to the call and is not retained by the returned map.

## Function Specifications

### `tarjanScc(graph: Map<K, Collection<K>>): List<Set<K>>`

- **Visibility:** Public.
- **Responsibility:** Return the strongly connected components using [@tarjan_depthfirst_1972].
- **Behavior:** Include every map key and every node in its neighbour collections exactly once.
  A neighbour without a map entry has no outgoing neighbours.
- **Input:** A finite directed graph represented by `graph`; keys have stable equality and hash codes.
  The caller does not mutate the map or neighbour collections during the call.
- **Output:** Components in reverse topological order: a component precedes every distinct component
  that reaches it. No order is promised within a component or between components with no path between them.
- **Errors:** None for the documented input.
- **Verification:** `StronglyConnectedTest` covers a chain, cycle, self loop, component ordering,
  a neighbour without an entry, integer keys and a 100000-node chain.

### `immediateDominators(root: NodeID, successors: Map<NodeID, Collection<NodeID>>): Map<NodeID, NodeID>`

- **Visibility:** Public.
- **Responsibility:** Return the immediate dominators using [@lengauer_fast_1979].
- **Behavior:** Consider only nodes reachable from `root`; a node without an entry has no outgoing neighbours.
- **Input:** A root and a finite directed graph represented by `successors`.
  The caller does not mutate the map or neighbour collections during the call.
- **Output:** Each reachable node other than `root` maps to its parent in the dominator tree.
  The root and unreachable nodes have no entry. Map iteration order is unspecified.
- **Errors:** None for the documented input.
- **Verification:** `DominatorTreeTest` covers a diamond, chain, loop, unreachable node, mutually reachable
  nodes with separate entries from the root, the root alone and 200 seeded graphs checked by definition.

## Exception / Error Types

Neither function introduces an exception type or rejects missing adjacency entries.

## Caller Examples

```kotlin
import edu.jhu.cobra.commons.graph.algorithm.tarjanScc

val graph = mapOf("a" to listOf("b"), "b" to listOf("c"), "c" to listOf("b"))
val components = tarjanScc(graph)
check(components == listOf(setOf("b", "c"), setOf("a")))
```

```kotlin
import edu.jhu.cobra.commons.graph.algorithm.immediateDominators

val successors = mapOf("r" to listOf("a", "b"), "a" to listOf("c"), "b" to listOf("c"))
val parents = immediateDominators("r", successors)
check(parents["c"] == "r")
```
