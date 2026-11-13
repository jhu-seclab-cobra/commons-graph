# Storage capability adapters

## Design Overview

- **Classes:** public `StorageAccess`, `GraphReader`, `GraphWriter`, `StorageReadScope`, `StorageWriteScope` and `LayeredStorage` in the core module.
- **Relationships:** `StorageAccess` owns one `LayeredStorage`; its reader, writer and compatibility adapter share that storage and its guard. `LayeredStorageImpl` implements `LayeredStorage`.
- **Abstract:** `GraphReader` exposes only guarded reads; `GraphWriter` exposes guarded writes and layer lifecycle. `LayeredStorage` is the existing combined compatibility contract.
- **Exceptions:** `IllegalStateException` rejects expired access, cross-thread scope use and mutation within a read scope. Existing entity and frozen-node errors propagate.
- **Dependency roles:** the caller selects and owns an implementation; the adapter owns access lifetime; the existing storage owns data, identity, ordering, indexes and notifications.

## Class / Type Specifications

### StorageAccess

`StorageAccess(storage: LayeredStorage)` takes exclusive ownership of the supplied storage. A caller retains no direct access to it after construction. The storage must expose a non-null `criticalSectionLock`; construction rejects an unsupported guard before publishing capabilities. The adapter allocates no identity map or property store. Existing `NodeID` mapping and opaque source/destination/tag identity remain graph-owned; storage IDs are forwarded without parsing or translation.

`reader: GraphReader`, `writer: GraphWriter` and `compatibility: LayeredStorage` share one lifetime. `close()` is idempotent, clears the owned storage once, closes an owned closeable storage, and invalidates every capability even if cleanup throws. The storage-owned listener lifetime ends when the owner and its graphs are released. The compatibility contract preserves selected Tag group, pair, exact adjacency and physical-layer reads; it does not fall back to a flat view.

### Read scope

`GraphReader.read(action: (StorageReadScope) -> R): R` supplies a reader valid only on the entering thread until the action exits, including exceptional exit. Nested reads have independent lifetimes. A saved scope or iterator fails with `IllegalStateException` after exit, on another thread or after owner closure. An iterator checks lifetime on both `hasNext` and `next`. No scope object exposes a writer or the underlying storage.

The adapter holds the storage's existing exclusive critical section for the whole action. This conservative adapter excludes writers and other threads' reads; it promises no parallel-read throughput. It never upgrades a read lock. A write, freeze, revert, clear, close or compatibility critical-section acquisition attempted by that thread inside a read scope fails before mutation. A caller snapshots selected IDs, exits the scope and then writes.

`StorageReadScope` selects node IDs, edge IDs, edge structure, directional Tag group IDs, pair/triple IDs and node/edge/meta properties through the current storage APIs. A missing node in a directional read retains its entity error; absent pair/triple lookup retains empty/null. Borrowed outgoing and incoming iterators preserve the selected storage encounter order. Callback iteration consumes the same iterator within the action. Snapshot collections and copied property content may escape.

### Write scope

`GraphWriter.write(action: (StorageWriteScope) -> R): R` holds the same exclusive guard across all calls of the action. Other threads cannot observe or interleave a partial compound operation. The write scope expires on exit and is confined to the entering thread. It provides entity creation/deletion, property/meta updates and family binding; it exposes no read capability. A read needed for a read-modify-write operation uses a nested reader action before the update.

Each property map and mutable `IValue` descendant is captured with `deepCopy` before the corresponding storage mutation. Null in an update deletes a name; `NullVal` is stored content; a missing name remains absent. Capture failure changes no property of that call. The callback is not a transaction: an exception in a later call does not roll back earlier successful calls. Endpoint, frozen-node and family-binding errors retain the existing storage contract.

`GraphWriter.freeze()`, `clearActiveLayer()` and `clear()` preserve the current repeated Freeze/revert lifecycle, surviving IDs, encounter order and notices. `clear()` ends the stored entity lifetime; scopes already expire at action exit, and raw integer IDs are not persistent handles. IDs must be reacquired after clear. Close ends the adapter lifetime permanently.

### Compatibility snapshots

The compatibility adapter captures admission values and returns independent property values, property maps, ID sets and metadata names. Each map preserves the storage's promised order and copies mutable descendants, so a later write, Freeze, clear/revert or mutation of a returned descendant cannot change stored content or an earlier snapshot. The caller can mutate its detached descendants without changing another snapshot. Snapshot cost includes every requested property and its mutable descendants; point reads copy only the selected value. There is no decoded-value cache.

`LayeredStorage` extends `IRevertibleStorage`, `LayeredEdgeReads` and `ExactAdjacencyStorage` with `freeze`, `clearActiveLayer`, `layerCount` and `getEdgeProperties(id, layer)`. Its implementations retain the physical-layer null/error rules. This combined API exists for callers migrating from `IStorage`; read-only clients take `GraphReader`. `StorageCompatibility` is internal and forwards compound lock acquisition to the same guard. Transfer rejects the compatibility adapter itself as its destination before allocation and captures node, edge and metadata admission values before an unadapted destination can retain them; it reuses the existing transfer loop and ID mapping.

## Function Specifications

- `StorageReadScope.getNodeIDs()` and `getEdgeIDs()` return fixed ID snapshots; their names identify copying operations rather than constant-time state properties.
- `StorageReadScope.getNodeProperties(id)` and `getEdgeProperties(id, layer?)` return fixed maps, with the selected-layer overload returning null when that layer has no row.
- `getNodeProperty(id, name)`, `getEdgeProperty(id, name)` and `getMeta(name)` return a detached value or null for absence.
- `getIncomingEdges(id, group?)` and `getOutgoingEdges(id, group?)` return borrowed iterators; omitting the group selects full adjacency.
- `StorageWriteScope.addNode(properties)` and `addEdge(src, dst, tag, properties)` return the existing stable storage IDs. Their input and error rules are those of `IStorage`; no typed field schema is implied.
- `setNodeProperties`, `setEdgeProperties`, `setMeta`, `deleteNode`, `deleteEdge` and `bindPropertyFamilies` apply the corresponding compatibility operation under the write scope.

## Exception / Error Types

`IllegalArgumentException` rejects a storage with no compound guard or a self-transfer. `IllegalStateException` rejects scope/lifetime misuse and mutation in a read scope. `EntityNotExistException` and `FrozenLayerModificationException` retain their existing conditions. Cleanup and storage failures propagate without selecting another implementation.

## Caller Examples

```kotlin
val access = StorageAccess(LayeredStorageImpl())
access.use {
    val source = it.writer.write { write -> write.addNode() }
    val target = it.writer.write { write -> write.addNode() }
    it.writer.write { write -> write.addEdge(source, target, "calls") }
    val ids = it.reader.read { read -> read.getOutgoingEdges(source).asSequence().toList() }
    check(ids.size == 1)
    it.writer.freeze()
    it.writer.clearActiveLayer()
}
```

## Open Gaps

Registered `PropertyKey<T>`, typed indexed cells, immutable structural payloads and frozen arrays remain specified by [Storage design](design-storage.md#typed-storage-capabilities). These adapters retain `IValue` at compatibility boundaries and the existing representation. Layout and read-scope throughput need the [pending measurements](design-gaps-storage.md#pending-acceptance-measurements); no speed or retained-heap improvement is established by adapter construction.
