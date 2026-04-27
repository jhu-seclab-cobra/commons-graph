# Node Grouping Design

## Design Overview

- **Classes**: `TraitGroup`
- **Relationships**: `TraitGroup` extends `IGraph<N, E>` (interface mixin)
- **Abstract**: `TraitGroup` (implemented by concrete graph classes alongside `AbcSimpleGraph` / `AbcMultipleGraph`)
- **Exceptions**: `IllegalArgumentException` raised on invalid group/suffix
- **Dependency roles**: Orchestrator: `TraitGroup` (manages group metadata, delegates node creation to `IGraph`). Data holders: reserved node properties `__group__`, `__suffix__`.

`TraitGroup` is an optional graph trait providing group membership management for nodes. Group membership is stored as **node properties** (`__group__`, `__suffix__`), not encoded in the NodeID. Per-group counters are persisted via **storage metadata** (`__group_counter_*`). The trait does **not** own NodeID generation — callers control IDs via `IGraph.addNode`, or use the convenience `addGroupNode` which generates an opaque ID internally.

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

- `groupPrefix` -- string prefix for auto-generated NodeIDs (e.g., `"AST"`, `"ADG"`). Only used by `addGroupNode` for ID generation. Not encoded into group metadata.
- `groupedNodesCounter` -- in-memory cache of per-group counters. Persisted to storage meta on every increment. Restored by `rebuildGroupCaches`.
- `suffixIndex` -- in-memory cache mapping `(group, suffix)` pairs to NodeIDs for O(1) lookup. Rebuilt from node properties on `rebuildGroupCaches`.

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

**Node ID Generation:**

`addGroupNode` generates IDs as `{groupPrefix}_{globalCounter}` where `globalCounter` is a storage-meta-persisted monotonic integer. The ID is an opaque token — no group information is encoded.

Callers needing custom IDs use `addNode(withID)` followed by `assignGroup(node, group, suffix)`.

**Counter Persistence:**

Per-group counters are written to storage meta (`__grp_cnt_<group>`) when auto-generated suffix is used (i.e., `suffix` parameter is null). Explicit suffix calls do not increment or persist the counter. `rebuildGroupCaches` restores counters from meta, so counters survive serialization/deserialization cycles. Global counter (`__grp_global_cnt__`) is incremented on every `addGroupNode` call for NodeID generation.

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `IllegalArgumentException` | Group name empty; group not registered in `groupedNodesCounter`; suffix empty when explicitly provided; node has no `__group__` property (for `addGroupNode(sameGroupNode)`) |

---

## Validation Rules

- Group name must be non-empty. No character restrictions (arbitrary strings allowed).
- Suffix (when provided) must be non-empty.
- Group must be registered via `registerGroup(group)` before calling `addGroupNode` or `assignGroup`.
- `rebuildGroupCaches` must be called after `rebuild()` to restore in-memory caches.
