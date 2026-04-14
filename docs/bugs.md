# Known Bugs and Technical Debt

## Open Bugs

### B1. LayeredStorageImpl delete on promoted entities reverts to frozen snapshot

**Severity:** Low (design ambiguity, not a bug)

When `deleteNode(id)` targets a node promoted from frozen to active layer (via `ensureNodeInActiveLayer`), deletion removes only the active-layer shadow. The `frozenNodeGlobalToLocal` mapping persists, so `containsNode` returns `true` and the node reverts to its frozen-layer state. Same for `deleteEdge`.

This is consistent with the overlay semantics: frozen layer is an immutable snapshot, active layer is an overlay. Deleting the overlay reverts to the snapshot. However, this means promoted entities cannot be fully deleted — only pure active-layer entities can be deleted.

**Status:** Accepted as design behavior. Document as a constraint in `storage-layered.design.md`.

---

### B3. AbcEntity nullable EntityProperty setter ignores null

**Severity:** Medium

The nullable `EntityProperty` delegate's `setValue` uses `value?.let { ... }`, making `node.prop = null` a no-op instead of removing the property. This contradicts `IEntity.set(name, null)` which specifies "null removes."

**Reproduction:** Set a property via the delegate, then assign `null`. Query `contains(name)` — returns `true`.

---

### B4. AbcMultipleGraph queryCache not invalidation-safe

**Severity:** Medium

`queryCache` is a plain `MutableMap`. The `Label.parents` setter calls `queryCache.clear()`, but `Label.ancestors` returns a lazy `Sequence` that reads `queryCache` during iteration. If `parents` is set while an `ancestors` sequence is being consumed, `ConcurrentModificationException` can occur in single-threaded code.

---

## Technical Debt

### D1. AbcMultipleGraph god object (570 lines, 25+ public methods)

Implements `IGraph`, `IPoset`, and `Closeable` — three distinct responsibilities. Exceeds detekt 600-line class limit. Split into: graph CRUD core, poset mixin, traversal mixin.

### D2. LayeredStorageImpl file length (695 lines)

Contains inline storage, layer management, freeze logic, and 5 inner view classes. Exceeds detekt limits. Extract inner classes to top-level internal classes.

### D3. NativeCsvIOImpl deserialization swallows exceptions

`runCatching { }.getOrNull()` silently drops deserialization errors. Corrupted CSV data produces null values without error. Same pattern in `JgraphtGmlIOImpl`.

### D4. MapDBStorageImpl clear() swallows DBException

`clear()` catches `DBException.VolumeIOError` and silently ignores it. I/O failures during cleanup go unreported.

### D5. Six transferTo implementations use silent fallback

`idMap[src] ?: src` in edge remapping silently falls back to unmapped IDs instead of throwing. Masks bugs in ID mapping logic. Affects: JGraphT, MapDB, Neo4j (both regular and concurrent variants).

### D6. Neo4j property access NPE on corrupted data

`node[it]!!` in property iteration throws NPE if a stored byte array fails deserialization. Should use `requireNotNull` with context message.

### D7. filterVisitable materializes sequence (4-pass)

`AbcMultipleGraph.filterVisitable` calls `.toList()` on the edge sequence, then iterates it three more times. Not on the hot path but wasteful.
