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
| `addEdge(src, dst, tag)` | Checks that no outgoing edge from `src` reaches `dst` before delegating to super | `src`, `dst`: NodeID; `tag`: String | `E` | `EntityAlreadyExistException` if any edge from src to dst exists |
| `addEdge(src, dst, tag, label)` | Checks direction uniqueness; if an edge exists between `(src, dst)` but with a different tag, rejects; otherwise delegates to super | `src`, `dst`: NodeID; `tag`: String; `label`: Label | `E` | `EntityAlreadyExistException` if direction conflict |

| Variant | Edge policy | Use case |
|---------|-------------|----------|
| `AbcSimpleGraph` | At most one edge per `(src, dst)` pair of any type | General relation graphs |
| `AbcMultipleGraph` | Multiple edges allowed between same `(src, dst)` pair | Multi-typed or multi-instance edges |

---

## Exception / Error Types

| Exception | When raised |
|-----------|------------|
| `EntityAlreadyExistException` | Adding a second edge between the same `(src, dst)` pair (any tag) |

---

## Validation Rules

### AbcSimpleGraph

- At most one edge per directed `(src, dst)` pair of any type; `addEdge` rejects duplicates with `EntityAlreadyExistException`
- Direction uniqueness check uses `getOutgoingEdges(src).any { it.dstNid == dst }`
