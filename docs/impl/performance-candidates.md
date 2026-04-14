# commons-graph Performance — Candidates & Rejected

Paired design: `storage.design.md`
See `performance.md` for current baseline, completed optimizations, and key insights.

---

## Candidates

### P6-1: NativeConcurStorageImpl lock overhead on read-heavy paths

**Problem**: `NativeConcurStorageImpl` acquires `ReentrantReadWriteLock.readLock()` on every operation. Property read (17.10M vs 57.79M) shows 3.4x slowdown vs NativeStorage.

**Analysis**: The property read gap is larger than expected from read-lock overhead alone (~20-50ns). Likely caused by `internKeys` in write path creating HashMap copies, plus write-lock contention on `setNodeProperties` affecting JIT compilation of the read path.

**Proposed fix**: Not actionable without JMH-level isolation.

**Risk**: N/A (analysis only).

### P6-2: NativeStorage cold getNode slower than warm

**Problem**: Cold query getNode (29.73M) vs warm query getNode (57.60M) shows 1.9x slowdown on NativeStorage. Gap greatly reduced from pre-optimization 3.2x, validating that meta property elimination (I4) was the dominant factor.

**Proposed fix**: Further improvement requires eliminating the SoftReference wrapper or using a direct LRU cache.

**Risk**: Medium. Changing cache strategy affects GC behavior.

---

## Evaluated & Rejected

| ID | Title | Result | Reason |
|---|---|---|---|
| P6-3 | Pre-sized HashMap in collectNodeProperties/collectEdgeProperties | -20% read regression | `HashMap(nodeColumns.size)` over-allocates when entities use subset of columns; larger table hurts cache locality |
| P6-4 | Pure copy-on-write adjacency (immutable Set + rebuild) | Edge query +300%, but edge add -86% at 1M, mixed -42500% | Kotlin `Set + element` rebuilds full LinkedHashSet per addition; hub nodes with 50K edges cause O(n^2) write cost |
| P6-5 | Replace SoftReference cache with direct HashMap | Not measured | SoftReference provides GC elasticity for large graphs (1M+ nodes); pinning all wrappers in heap risks OOM under memory pressure |
| P6-6 | Set.copyOf() in getIncomingEdges/getOutgoingEdges | -21% out, -19% in regression | `Set.copyOf()` re-hashes to detect duplicates; `HashSet(set)` copies table structure directly, cheaper for adjacency snapshots |
| P6-7 | toTypedArray() in deleteNode edge snapshot | -18% regression | `toTypedArray()` on generic `Set<String>` requires reflective array creation; `toList()` uses `Object[]` directly |
