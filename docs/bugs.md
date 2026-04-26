# Known Bugs and Technical Debt

## Open Bugs

None.

---

## Technical Debt

### D1. AbcMultipleGraph god object (570 lines, 25+ public methods)

Implements `IGraph`, `IPoset`, and `Flushable` — three distinct responsibilities. Exceeds detekt 600-line class limit. Split into: graph CRUD core, poset mixin, traversal mixin.

### D2. LayeredStorageImpl file length (695 lines)

Contains inline storage, layer management, freeze logic, and 5 inner view classes. Exceeds detekt limits. Extract inner classes to top-level internal classes.

---

## Resolved

### B5. getAllEdges iterates foreign edges in shared-storage scenario

**Resolution:** Fixed. Added `nodeByStorageId` membership filter in `getAllEdges`: edges whose src or dst is not registered in this graph are skipped before `cachedEdge`.

### B6. claimNode not implemented — shared-storage node claiming

**Resolution:** Fixed. Added `claimNode(from: AbcNode): N` to `IGraph` and `AbcMultipleGraph`. Registers an existing storage row (by `from.storageId`) in this graph's node caches without creating a new storage row. Consumer `wrapNode` replaced by `claimNode`.

### B7. rebuild() claims all storage nodes regardless of graph ownership

**Resolution:** Fixed. `rebuild(ownerPrefix)` accepts optional prefix parameter. When provided, only nodes whose NodeID starts with the prefix are restored. Claimed nodes must be re-claimed after rebuild.

### B1. LayeredStorageImpl delete on promoted entities reverts to frozen snapshot

**Resolution:** Not a bug — correct overlay design behavior.

Frozen layer is immutable. Delete removes the active-layer overlay, reverting to the frozen snapshot. Alternatives investigated (tombstone/whiteout, CoW rebuild, deletion log) all require compaction/vacuum mechanisms. RocksDB documents tombstone accumulation as a known performance problem. PostgreSQL dead tuples without VACUUM cause disk bloat and transaction ID wraparound. Current behavior is the simplest correct design for a 2-layer system where the caller manages layers explicitly.

**References:** [OverlayFS](https://www.kernel.org/doc/html/latest/filesystems/overlayfs.html), [RocksDB DeleteRange](https://rocksdb.org/blog/2018/11/21/delete-range.html), [PostgreSQL VACUUM](https://www.postgresql.org/docs/current/routine-vacuuming.html)

### B3. AbcEntity nullable EntityProperty setter ignores null

**Resolution:** Fixed. Changed `value?.let { thisRef[name] = it }` to `thisRef[name] = value`. Null now correctly propagates to `IEntity.set(name, null)` which removes the property.

### D3. NativeCsvIOImpl deserialization swallows exceptions

**Resolution:** Fixed. Removed `runCatching { }.getOrNull()` wrappers in NativeCsvIOImpl and JgraphtGmlIOImpl. Deserialization errors now propagate.

### D4. MapDBStorageImpl clear() swallows DBException

**Resolution:** Fixed. Removed `try/catch(DBException.VolumeIOError)` blocks in MapDBStorageImpl and MapDBConcurStorageImpl. I/O errors now propagate.

### D5. Six transferTo implementations use silent fallback

**Resolution:** Fixed. Replaced `idMap[src] ?: src` with `idMap.getValue(src)` in JGraphT, MapDB, Neo4j (both regular and concurrent). Missing keys now throw `NoSuchElementException`.

### D6. Neo4j property access NPE on corrupted data

**Resolution:** Fixed. Replaced `node[it]!!` with `requireNotNull(node[key]) { "Property '$key' on entity $id has corrupted data" }` in Neo4jStorageImpl and Neo4jConcurStorageImpl.

### B4. AbcMultipleGraph queryCache not invalidation-safe

**Resolution:** Not a bug. `compareTo` does not iterate `queryCache` entries — it only performs `in` checks and single-key reads/writes. `clear()` in the `parents` setter does not conflict with these operations. `ConcurrentModificationException` requires an active iterator on the map, which `compareTo` never creates. Verified by test.

### D7. filterVisitable materializes sequence (4-pass)

**Resolution:** Fixed. Reduced from 4 passes to 2 in AbcMultipleGraph. Pass 1 materializes edges while collecting visitable labels. Pass 2 filters by uncovered labels.
