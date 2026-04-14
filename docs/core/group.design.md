# TraitNodeGroup Design

## Design Overview

- **Classes**: `TraitNodeGroup`
- **Relationships**: `TraitNodeGroup` extends `IGraph<N, E>` (interface mixin)
- **Abstract**: `TraitNodeGroup` (implemented by concrete graph classes alongside `AbcSimpleGraph` / `AbcMultipleGraph`)
- **Exceptions**: `IllegalArgumentException` raised on invalid group/suffix; `EntityAlreadyExistException` raised by `IGraph.addNode` on duplicate generated ID
- **Dependency roles**: Orchestrator: `TraitNodeGroup` (delegates node creation to `IGraph`). Data holders: `NodeID` (structured group-scoped identity).

`TraitNodeGroup` is an optional graph trait that adds structured group-scoped node naming on top of standard `IGraph` operations. It owns a `groupPrefix` property, a group registry with monotonically increasing per-group counters, structured `NodeID` generation with the format `groupPrefix@group#suffix`, and convenience methods for creating and retrieving group-scoped nodes. It does **not** own storage, property management, or graph traversal.

---

## Class / Type Specifications

### TraitNodeGroup

**Responsibility:** Provides structured group-scoped `NodeID` generation and retrieval on top of `IGraph`.

**Node ID Format:**

```
<groupPrefix>@<group>#<suffix>
```

- `groupPrefix` -- the trait's `groupPrefix` property with `@` chars stripped (prevents ambiguous splitting)
- `group` -- a non-empty string containing neither `@` nor `#`
- `suffix` -- a non-empty string containing no `#`; auto-generated as an integer if not provided

**State / Fields:**

```kotlin
interface TraitNodeGroup<N : AbcNode, E : AbcEdge> : IGraph<N, E> {
    val groupPrefix: String
    val groupedNodesCounter: MutableMap<String, Int>
    fun addGroupNode(group: String, suffix: String? = null): N
    fun addGroupNode(sameGroupNode: AbcNode, suffix: String? = null): N
    fun getGroupNode(group: String, suffix: String): N?
    fun getGroupName(node: AbcNode): String?
}
```

**`groupPrefix`**
A string prefix for all generated node IDs. `@` characters stripped when constructing node IDs.

**`groupedNodesCounter`**
Maps each registered group name to its next auto-increment value. Group must be registered (key present) before `addGroupNode` can be called.

**Methods:**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addGroupNode(group, suffix?)` | Generates `NodeID` from `(groupPrefix, group, suffix)` and delegates to `addNode`. Counter increments regardless of whether suffix is provided. | `group`: registered group name; `suffix`: optional custom suffix | `N` | `IllegalArgumentException` if group/suffix invalid or group not registered; `EntityAlreadyExistException` if generated ID exists |
| `addGroupNode(sameGroupNode, suffix?)` | Extracts group from existing node's ID and delegates to `addGroupNode(group, suffix)` | `sameGroupNode`: node whose group to reuse; `suffix`: optional | `N` | Same as above |
| `getGroupNode(group, suffix)` | Constructs `NodeID` from `(groupPrefix, group, suffix)` and delegates to `getNode` | `group`; `suffix` | `N?` | `IllegalArgumentException` if group/suffix invalid |
| `getGroupName(node)` | Parses `node.id` to extract the group segment between `@` and `#` | `node`: any `AbcNode` | `String?` -- group name or null if ID does not conform | -- |

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `IllegalArgumentException` | Group name is empty, contains `@` or `#`; suffix is empty string or contains `#`; group not in `groupedNodesCounter` |
| `EntityAlreadyExistException` | Generated node ID already exists in the graph (from `IGraph.addNode`) |

---

## Validation Rules

### TraitNodeGroup

- Group name must be non-empty and must not contain `@` or `#`
- Suffix (when provided) must be non-empty and must not contain `#`
- Group must be registered in `groupedNodesCounter` (key present) before calling `addGroupNode`
- `groupPrefix` is sanitized by stripping `@` characters to prevent ambiguous ID splitting
