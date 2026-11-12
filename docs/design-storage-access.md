# Storage Structural Access Design

## Design Overview

- **Classes**: `IStorage`, `TagAdjacencyIndex`, `EdgePairIndex`, `FrozenEdgeOrder`.
- **Relationships**: Core Storage implementations maintain directional Tag group buckets and a shared pair/triple index; external backends inherit scan-based defaults.
- **Abstract**: `IStorage` defines structural selection independent of the physical representation.
- **Exceptions**: `EntityNotExistException` reports a missing node in directional reads.
- **Dependency roles**: `TagAdjacencyIndex` stores directional membership; `EdgePairIndex` stores endpoint-pair and complete-triple membership; `FrozenEdgeOrder` retains frozen encounter ranks.

The complete Storage interface is in [Storage design](design-storage.md#istorage). [Tag group](glossary/glossary-storage.md#tag-group) names the selection key. Lookup steps and bounds are in [Graph specification](spec-graph.md#edge-lookup-by-triple).

## Class / Type Specifications

### IStorage

**Responsibility:** Select structural edge IDs by direction, Tag group, endpoint pair or complete triple, without reading edge properties.

`tagGroup` is a constructor-fixed pure function of the complete immutable tag; its default is identity.
The directional overloads copy only matching IDs under the Storage's existing guard, preserving their
encounter order in the full adjacency. A missing node throws `EntityNotExistException`; a supported
miss returns an empty set. `findEdge` retains complete-tag identity and returns null for absent
endpoints or a missing triple. Raw storage can hold duplicate triples; lookup returns the first visible row.

Core storages own directional group buckets and a shared pair/triple lookup, retaining only primitive IDs
and structural keys, O(V + E) entries. Internal `TagAdjacencyIndex` stores singleton IDs inline in primitive
slots and allocates a primitive array only from the second membership. Removal scans/shifts matching buckets; writes update the index
under the same guard as the physical adjacency. No edge property participates in group membership.

Native adjacency preserves insertion order; layered adjacency keeps frozen records before active-only
records, and promotions do not move a frozen record. The selected overloads use that same order.
Default implementations on `IStorage` filter full adjacency under `criticalSection`, allowing the
external backend modules to retain their existing representation and linear scan cost.

Native and concurrent storage accept `tagGroup` in construction; layered storage accepts it beside its
projection. The default frozen factory receives the same function; a custom factory with another
function gets one frozen structural index, with directional ranks preserving that backend's order.

Every freeze also rebuilds the shared pair/triple rows from frozen outgoing adjacency ranks, for
any factory or grouping. Revert inserts restored rows at those ranks; active-only rows follow all
frozen rows. The primitive rank table holds one entry per frozen edge until the next freeze or clear.

`getEdges` copies all rows of a pair, or of its complete tag; absent endpoints return an empty set.
Fixed-set group reads merge only the selected heads in directional encounter order. Freeze rebuilds through its
existing entity transfer, clear releases all entries, and revert drops active buckets with active rows.
A property promotion records its existing structure in the active buckets; tombstones mask frozen IDs.

| Method | Behavior | Input | Output | Errors |
|---|---|---|---|---|
| `getIncomingEdges` / `getOutgoingEdges` | Copy matching IDs in full directional encounter order | Node ID and one Tag group or a set of Tag groups | `Set<Int>` | `EntityNotExistException` if node missing |
| `getEdges` | Copy matching pair rows in adjacency order | Source, destination and optional complete tag | `Set<Int>`; empty if endpoints or match absent | None |
| `findEdge` | Read the first matching pair or complete-triple row | Source, destination and optional complete tag | `Int?`; null if endpoints or match absent | None |

### TagAdjacencyIndex and EdgePairIndex

**Responsibility:** Retain structural membership in core Storage implementations. Membership follows immutable complete tags and endpoint IDs; each physical create/delete updates it under the same guard as adjacency. An edge property write cannot change a Tag group.

### FrozenEdgeOrder

**Responsibility:** Preserve frozen outgoing encounter ranks for the pair/triple index across property promotion and revert. `LayeredStorageImpl` owns this table. The rank table retains one primitive entry per frozen edge until the next Freeze or clear; capacity conversion is checked before allocating primitive rank slots.

## Exception / Error Types

`EntityNotExistException` is raised by directional reads when the node is absent. Pair and complete-triple reads return empty or null for missing endpoints.

## Caller Examples

```kotlin
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

val storage = NativeStorageImpl(tagGroup = { it.substringBefore('@') })
val source = storage.addNode()
val target = storage.addNode()
val edge = storage.addEdge(source, target, "write@1")
check(storage.getIncomingEdges(target, "write") == setOf(edge))
check(storage.getOutgoingEdges(source, setOf("write", "read")) == setOf(edge))
check(storage.findEdge(source, target, "write") == null)
check(storage.findEdge(source, target, "write@1") == edge)
check(storage.getEdges(source, target) == setOf(edge))
```
