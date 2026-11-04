# Glossary — Labels

Labels: the markers that scope edge visibility, their two bounds, and the partial order over them.

---

## Labels

### `Label`
- **Definition:** A marker identified by its string that an edge carries to scope the queries under which the edge is visible; its order against other labels is given by the label hierarchy of its graph, not by the string.
- **Example:** `graph.addEdge("a", "b", "r", Label("ctx"))` → the edge `(a, b, r)` carries the label `ctx` and is visible to a query under `ctx` or under any label above `ctx`.
- **Source:** `poset/Label.kt` `Label`; `poset/PosetTrait.kt` `PosetTrait`
- **Reference:** none

### `Label.INFIMUM`
- **Is a:** `Label`
- **Definition:** The greatest lower bound of every label hierarchy, below every other label: an edge that carries it is visible under every label, and a query under it sees only the edges that carry it.
- **Example:** `graph.addEdge("a", "b", "seed", Label.INFIMUM)` → the edge `(a, b, seed)` is visible to a query under `ctx` and under every other label.
- **Source:** `poset/Label.kt` `Label.INFIMUM`; `poset/PosetDftImpl.kt` `PosetDftImpl.compare`; `docs/model.md` Label, Edge Visibility Rule
- **Reference:** none

### `Label.SUPREMUM`
- **Is a:** `Label`
- **Definition:** The least upper bound of every label hierarchy, above every other label: a query under it applies no label filter and sees every edge, labeled or not.
- **Example:** `graph.getOutgoingEdges("a", Label.SUPREMUM)` → every outgoing edge of `a`.
- **Source:** `poset/Label.kt` `Label.SUPREMUM`; `poset/PosetTrait.kt` `PosetTrait.getOutgoingEdges`
- **Reference:** none

### Label hierarchy
- **Definition:** The partial order over the labels of a graph, kept as a directed acyclic graph whose edges are named parent links and queried for ancestors and comparability to decide edge visibility.
- **Example:** `poset.setParents(ctx, mapOf("call" to root))` → `root` is above `ctx`, so a query under `root` sees the edges that carry `ctx`.
- **Source:** `poset/IPoset.kt` `IPoset`; `poset/PosetDftImpl.kt` `PosetDftImpl`; `docs/spec-poset.md`
- **Reference:** none
