# Exact Adjacency Design

## Design Overview

- **Classes:** public `AdjacencyProjection`, `ExactAdjacencyReads`, `ExactAdjacencyStorage`; internal `ExactAdjacencyIndex`.
- **Relationships:** `NativeStorageImpl` and `LayeredStorageImpl` implement `ExactAdjacencyStorage`, optionally
  selecting one fixed projection in their constructor. Each storage owns one physical index over its existing edges.
- **Data holders:** `AdjacencyProjection` holds an edge tag and two destination property names. The index identifies
  physical adjacency rows; it creates no graph node, edge, metadata entry or domain-level answer.
- **Dependencies:** domain-specific callers provide their existing typed property values. Storage knows no PHP names,
  labels, or graph schema. Existing full-row and full-adjacency APIs retain their behavior.
- **Exceptions:** missing entities use `EntityNotExistException`; missing projected fields, conflicting attachment
  and changes to attached key fields use `IllegalArgumentException`; an exact operation without a configured
  projection uses `IllegalStateException`.

## Class / Type Specifications

### AdjacencyProjection

One immutable constructor input: `edgeTag`, `kindProperty`, `keyProperty`. The projection is present before any row
is created. It has no registration method, caller predicate, lazy build or dynamic registry. Edges with another tag
are not indexed. A matching edge requires both destination fields.

`LayeredStorageImpl(projection, frozenLayerFactory = ...)` configures the projection; the factory-only constructor selects null. Supported construction forms include positional, trailing-lambda and named factories, Kotlin default arguments, the factory JVM descriptor and Java no-argument construction.

The exact key is the source integer ID and the complete typed values of those two destination fields. Key hashing
is followed by equality; a hash collision never identifies two distinct keys. Index keys snapshot the supplied
values with `deepCopy`. Callers do not mutate values held by storage in place. While a projected edge targets a
node, setting its projected fields to different values or deleting those fields is rejected before mutation.
Other properties remain writable. This fixed-field contract applies only to a storage constructed with a projection.

### ExactAdjacencyStorage

`attachmentProjection` reports attachment support independently of exact reads. Its default is `adjacencyProjection`; forwarding adapters return the underlying attachment capability or null while preserving supported reads. Implementers supply every required query overload; binary compatibility with precompiled implementations lacking those members is not guaranteed.

`getExactOutgoingEdges(source, kind, key)` returns a snapshot of matching existing edge IDs. It creates nothing and
retains no miss history. Raw `addEdge` retains storage's allocation semantics and can create several physical rows
with one key; exact selection returns every such row.

`attachExact(source, target, properties)` checks the relevant bucket under the owning storage's normal mutation
guard. It reuses a matching edge to that target, without changing its properties. If the bucket contains an edge
to another target, it fails before mutation. Otherwise it adds one edge with the projection's tag, initialized by
`properties`. It never allocates a target node: that node is caller-owned even when attachment fails. It performs no
source-adjacency duplicate scan or general triple-index lookup.

Domain and missing-field failures precede relation mutation. This is a prevalidation and concurrent-exclusion
contract, not a transaction across arbitrary backend or VM failures. No storage failure rollback is promised.

### ExactAdjacencyIndex

The index stores composite key buckets of physical edge IDs, their projected destination identities, and target
membership counts. The target count permits fixed-field validation without scanning incoming adjacency. All
structures are owned and mutated by the existing storage; no graph object or wrapper holds a second name table.

Insertion reads two existing destination fields and snapshots their values before adding the row. Edge and incident
node deletion remove those entries along with the physical row. Clearing storage clears the index. The ordinary
storage representation remains authoritative; this access path is not an allocation root or a publication policy.

The Native implementation is single-threaded like its existing operations. The Layered implementation takes its
existing read or write lock and returns selected snapshots before releasing the lock.

### Layered lifecycle

The Layered index addresses global physical edge IDs. Freeze changes frozen local mappings while preserving global
IDs, so visible index entries remain valid without a second copy. A visible deletion removes its index entry and
the existing frozen tombstone masks its row. `clearActiveLayer` removes and restores index entries for the notice's
actual affected edges after layer restoration, before listeners read the storage. No full container rebuild occurs.

Transfer/import builds a configured destination's access through its existing node and edge creation calls, including
the normal integer-ID remapping. A new graph wrapper over the same storage uses the same physical access. Attaching
a projection to a populated unconfigured storage, persistence/reopen of external backends, supplied reads and buffered
write overlays are not provided by these operations.

## Function Specifications

For key size B and R selected rows, selection uses expected O(B + R) time and O(R) result space. Attachment checks R
relevant rows, reads/snapshots two fixed fields, then adds at most one row. Ordinary insertion/deletion adds expected
key work per affected projected edge, without unrelated source or destination adjacency enumeration. Hashing retains
its ordinary collision-dependent worst case. The index retains O(E + K) entries for E indexed edges and K distinct
keys, plus their actual key bytes; repeated misses allocate no retained entry. Existing node-deletion and freeze/import
passes retain their existing scopes, with bounded added index work per row they already handle.

## Read Capability

`ExactAdjacencyReads` exposes the nullable constructor-fixed `adjacencyProjection` and `getExactOutgoingEdges`.
A null declaration means the access has not configured exact reads. Native and Layered return the declaration of
their existing index; there is no independent registration or first-query rebuild. `ExactAdjacencyStorage` extends
this read capability with `attachExact`. A read-only interpretation access need not claim attachment support.
The multi-kind `getExactOutgoingEdges(source, kinds, key)` overload unions only the requested buckets in one Storage operation, with unspecified result order. Delegating wrappers forward both overloads and the projection declaration; unavailable capability and a supported empty result are distinct.

## Exception / Error Types

`EntityNotExistException`, `IllegalArgumentException` and `IllegalStateException` have the conditions stated above.
Unconfigured storages keep all existing `IStorage` behavior; their exact operations reject use rather than silently
falling back to a table scan.

## Caller Examples

```kotlin
import edu.jhu.cobra.commons.graph.storage.AdjacencyProjection
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.StrVal

val storage = NativeStorageImpl(AdjacencyProjection("member", "kind", "key"))
val source = storage.addNode()
val target = storage.addNode(mapOf("kind" to StrVal("field"), "key" to StrVal("x")))
val edge = storage.attachExact(source, target)
check(storage.getExactOutgoingEdges(source, StrVal("field"), StrVal("x")) == setOf(edge))
```

## Open Gaps

- Which measured caller requires exact adjacency in NativeConcurStorageImpl or an external backend? These implementations expose no projection.
- How should a caller integrate acquired property reads, supplier effects and buffered writes with exact attachment? This Storage operation owns physical attachment only, not held-buffer visibility, publication uniqueness or rollback of domain-level insertion.
