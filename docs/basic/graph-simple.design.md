# AbcSimpleGraph Design

## Design Overview

- **Classes**: `AbcSimpleGraph`
- **Relationships**: `AbcSimpleGraph` extends `AbcMultipleGraph`
- **Abstract**: `AbcSimpleGraph` (subclassed by concrete graph implementations)
- **Exceptions**: `EntityAlreadyExistException` raised when adding a second edge between the same `(src, dst)` pair
- **Dependency roles**: Orchestrator: `AbcSimpleGraph` (enforces direction uniqueness, delegates all other behavior to `AbcMultipleGraph`).

`AbcSimpleGraph` is a graph variant enforcing at most one edge per directed `(src, dst)` pair, regardless of tag. All node CRUD, label, and traversal operations are inherited from `AbcMultipleGraph`.

---

## Class / Type Specifications

### AbcSimpleGraph

**Responsibility:** Graph variant enforcing at most one edge per directed `(src, dst)` pair. Extends `AbcMultipleGraph`.

**Overridden Methods:**

| Method | Behavior | Input | Output | Errors |
|--------|----------|-------|--------|--------|
| `addEdge(src, dst, tag)` | Rejects if any edge from `src` to `dst` already exists, then delegates to super | `src`, `dst`: NodeID; `tag`: String | `E` | `EntityAlreadyExistException` if any edge from src to dst exists |
| `addEdge(src, dst, tag, label)` | Rejects if an edge exists between `(src, dst)` with a different tag; otherwise delegates to super | `src`, `dst`: NodeID; `tag`: String; `label`: Label | `E` | `EntityAlreadyExistException` if direction conflict |

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityAlreadyExistException` | Adding a second edge between the same `(src, dst)` pair (any tag) |

---

## Validation Rules

### AbcSimpleGraph

- At most one edge per directed `(src, dst)` pair of any type; `addEdge` rejects duplicates with `EntityAlreadyExistException`
