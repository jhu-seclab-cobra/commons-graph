# Node Grouping Design

> **DEPRECATED.** TraitGroup mixes infrastructure (ID generation) with domain concerns (suffix-based lookup). Auto-ID generation will move into AbcMultipleGraph/AbcSimpleGraph. Group/suffix indexing will move to domain modules (AST, ADG, PDG, CCG). See todo.md for migration plan.

## Design Overview

- **Classes**: `TraitGroup`
- **Relationships**: `TraitGroup` extends `IGraph<N, E>` (interface mixin)
- **Abstract**: `TraitGroup` (implemented by concrete graph classes alongside `AbcSimpleGraph` / `AbcMultipleGraph`)
- **Exceptions**: `IllegalArgumentException` raised on invalid group/suffix
- **Dependency roles**: Orchestrator: `TraitGroup` (manages group metadata, delegates node creation to `IGraph`). Data holders: reserved node properties `__group__`, `__suffix__`.

`TraitGroup` is an optional graph trait providing group membership management for nodes. The trait does **not** own NodeID generation — callers control IDs via `IGraph.addNode`, or use the convenience `addGroupNode` which generates an opaque ID internally.

See `model.md` for group membership semantics and invariants. See `spec.md` for the ID generation algorithm.

---

## Class / Type Specifications

### TraitGroup

**Responsibility:** Manages node-to-group assignment, group-scoped lookup, and group enumeration. Decoupled from NodeID format.

**Reserved Properties:**

| Property | Stored on | Type | Content |
|----------|-----------|------|---------|
| `__group__` | Node | `StrVal` | Group name this node belongs to |
| `__suffix__` | Node | `StrVal` | Suffix identifying this node within its group |
| `__grp_cnt_<group>` | Storage meta | `NumVal` | Monotonically increasing per-group counter (auto-suffix only) |
| `__grp_global_cnt__` | Storage meta | `NumVal` | Global monotonic counter for NodeID generation across all groups |

**State / Fields:**

```kotlin
interface TraitGroup<N : AbcNode, E : AbcEdge> : IGraph<N, E> {
    val groupPrefix: String
    val groupedNodesCounter: MutableMap<String, Int>
    val suffixIndex: MutableMap<Pair<String, String>, NodeID>
}
```

- `groupPrefix` -- string prefix for auto-generated NodeIDs (e.g., `"AST"`, `"ADG"`).
- `groupedNodesCounter` -- per-group counters. Restored by `rebuildGroupCaches`.
- `suffixIndex` -- maps `(group, suffix)` pairs to NodeIDs. Rebuilt by `rebuildGroupCaches`.

**Methods:**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `registerGroup(group)` | Registers a group name for use with `addGroupNode` and `assignGroup`. Idempotent — repeated calls with the same name are no-ops. | `group`: non-empty string | -- | `IllegalArgumentException` if group empty |
| `assignGroup(node, group, suffix?)` | Sets `__group__` and `__suffix__` properties on an existing node. Updates suffix index. Does not modify the node's ID. When `suffix` is null, increments the per-group counter and uses it as suffix. When `suffix` is provided, uses it directly without incrementing the counter. | `node`: existing node; `group`: non-empty string; `suffix`: optional (defaults to counter) | -- | `IllegalArgumentException` if group empty or not registered |
| `addGroupNode(group, suffix?)` | Generates an opaque NodeID (`{groupPrefix}_{globalCounter}`), calls `addNode`, then `assignGroup`. Convenience method combining creation and assignment. | `group`: registered group; `suffix`: optional | `N` | `IllegalArgumentException` if group not registered |
| `addGroupNode(sameGroupNode, suffix?)` | Reads group from existing node's `__group__` property, delegates to `addGroupNode(group, suffix)`. | `sameGroupNode`: node with `__group__` property; `suffix`: optional | `N` | `IllegalArgumentException` if node has no group |
| `getGroupNode(group, suffix)` | Looks up NodeID in suffix index, returns node or null. | `group`; `suffix` | `N?` | `IllegalArgumentException` if group or suffix empty |
| `getGroupName(node)` | Reads `__group__` property from node. | `node`: any `AbcNode` | `String?` | -- |
| `getGroupSuffix(node)` | Reads `__suffix__` property from node. | `node`: any `AbcNode` | `String?` | -- |
| `getGroupNodes(group)` | Returns all nodes whose `__group__` equals the given group. | `group` | `Sequence<N>` | -- |
| `rebuildGroupCaches()` | Scans all nodes to restore `suffixIndex` and `groupedNodesCounter` from `__group__`/`__suffix__` properties and storage meta. Call after `rebuild()`. | -- | -- | -- |

See `spec.md` for the node ID generation algorithm and counter persistence details.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `IllegalArgumentException` | Group name empty; group not registered in `groupedNodesCounter`; suffix empty when explicitly provided; node has no `__group__` property (for `addGroupNode(sameGroupNode)`) |


See `model.md` for group invariants (non-empty names, suffix uniqueness, counter monotonicity).
