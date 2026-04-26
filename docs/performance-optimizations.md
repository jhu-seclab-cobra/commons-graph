# commons-graph Performance Optimizations

Optimization log for core module. Benchmarks in `impl-performance.md`.

## Key Improvements

| ID | File | Change | Impact |
|---|---|---|---|
| I1 | AbcMultipleGraph | Label.parents as posetStorage edges | Eliminates MapVal serialization per BFS step |
| I2 | AbcMultipleGraph | Label.changes uses direct StrVal | Eliminates type conversion per element |
| I3 | AbcMultipleGraph | filterVisitable reads labels once | Halves storage reads + HashSet allocations |
| I4 | AbcEdge | No meta properties in edge namespace | Cold getNode gap 3.2x → 1.9x |
| I5 | AbcMultipleGraph | Remove edgeIndex; findEdge via adjacency scan | ~600 MB saved at 1M nodes |
| I6 | AbcMultipleGraph | BFS uses storage adjacency directly | Eliminates wrapper per BFS step |
| I7 | AbcEdge | labels uses direct StrVal cast | Avoids toString() + intermediate list |
| I8 | NativeStorageImpl | Columnar node properties | Memory -6%, incoming +97%, delete +50% |
| I9 | NativeConcurStorageImpl | Set.copyOf() snapshot properties | Compact array-backed snapshots |
| I12 | NativeConcurStorageImpl | Snapshot-on-demand adjacency | Outgoing +683%, incoming +831% |
| I14 | NativeStorageImpl | ColumnViewMap.entries lazy cache | Eliminates repeated allocation |
| I15 | AbcNode | hashCode() avoids toString() | Cold getNode +270% |
| I16 | NativeStorageImpl | Eliminate double HashMap lookup | Outgoing +15%, incoming +10% |
| I17 | AbcMultipleGraph | Label.changes avoids intermediate list | One less allocation per read/write |
| I18 | LayeredStorageImpl | MappedEdgeSet.contains() O(1) | Outgoing +29%, incoming +21% |
| I19 | LayeredStorageImpl | Frozen edge structure cache | Eliminates repeated translation |
| I20 | LayeredStorageImpl | ActiveColumnViewMap entries cache | Matches I14 pattern |
| I21 | LayeredStorageImpl | Eliminate double HashMap lookups | One fewer lookup per query |

## Evaluated & Rejected

| ID | Title | Reason |
|---|---|---|
| P6-3 | Pre-sized HashMap | -20% read; hurts cache locality |
| P6-4 | Copy-on-write adjacency | add -86% at 1M; rebuilds full set |
| P6-5 | Replace SoftReference | GC elasticity needed at 1M+ nodes |
| P6-6 | Set.copyOf() adjacency return | -21% out; re-hashes |
| P6-7 | toTypedArray() deleteNode | -18%; reflective array creation |
| P9-2 | Native Int __sid__ | Already native Long |
| P9-5 | Eclipse Collections ID mapping | Zero-mapping architecture |

---

## Candidates

### P10-1: Replace HashMap\<Int, *\> with primitive-key maps

All IStorage implementations use `HashMap<Int, *>` for property columns, adjacency, and edge endpoints. JVM generics force Int boxing (16 bytes/Integer + 32 bytes/Entry). Eclipse Collections `IntObjectHashMap` eliminates boxing entirely. Estimated ~70% reduction in HashMap overhead (~77 MB -> ~23 MB) at 120K nodes / 155K edges. Risk: low. Dependency: `eclipse-collections:11.1.0`.

### P10-2: Replace inner HashSet\<Int\> in adjacency with IntHashSet

Adjacency `MutableSet<Int>` boxes each edge ID. `IntHashSet` stores raw int[]. ~5 MB saved at 155K edges; combined with P10-1: ~18 MB total. Risk: low. Same dependency.

### P10-3: filterVisitable — precompute ancestor set, fast path, O(V·H) maximal

`filterVisitable` calls `by.compareTo(l)` per label per edge. Cache-miss compareTo triggers BFS (O(H)). Coverage elimination is O(V²) pairwise compareTo. Three improvements:
1. **Precompute**: One BFS for `by.ancestors.toHashSet()`, then O(1) membership test per label.
2. **Fast path**: V ≤ 1 → skip coverage pass (>95% of calls in CobraPHP).
3. **Maximal elements**: O(V·H) ancestor scan replaces O(V²) pairwise compareTo.

Secondary bottleneck: `e.labels` getter allocates HashSet + Label objects per call. Hierarchy depth H typically 3-5 but can reach 10-20 in deep branch/call nesting.

**File**: `AbcMultipleGraph.kt`. **Risk**: Low.

### P6-2: NativeStorage cold getNode slower than warm

Cold 29.73M vs warm 57.60M (1.9x). Reduced from 3.2x by I4. Proposed: eliminate SoftReference or LRU cache. Risk: medium.

---

## Key Insights

1. **NativeStorageImpl is fastest single-threaded.** Property read 51M, edge queries 102M/106M.
2. **Columnar storage reduces memory and improves GC paths.** O(K) columns vs O(N) maps.
3. **LayeredStorage property read approaches NativeStorage in single-layer mode.**
4. **Cold vs warm gap ~2x.** Down from 3.2x via `AbcNode.hashCode()` fix (I15).
5. **findEdge via storage adjacency scan — O(out-degree).** Replaced 3-layer nested HashMap. ~600 MB saved at 1M nodes.
6. **Label hierarchy uses native edge encoding.** Poset edges vs MapVal serialization.
7. **JIT cross-contamination requires per-implementation JVM isolation.**
8. **Memory ~97-104 MB at 50K nodes + 150K edges** across implementations.
9. **Snapshot-on-demand beats copy-on-read and copy-on-write.**
10. **SoftReference critical for GC elasticity.** Removing risks OOM at 1M+ nodes.
11. **Double HashMap lookup measurable in hot paths.** 10-15% at 100M+ ops/sec.
12. **`toString().hashCode()` catastrophic in tight loops.** Cold query +270%.
13. **Int boxing in HashMap\<Int, *\> costs ~30 MB at 120K nodes.**
