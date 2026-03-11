# Performance Analysis

Paired design: `storage.design.md`

---

## 1. Problem Context

In large-scale project analysis, graph data consumes over **30GB** of memory. The two primary IStorage backends have distinct bottlenecks:

| Implementation | Problem | Root Cause |
|---|---|---|
| NativeStorage (HashMap) | Long GC pauses, Full GC can reach seconds | Massive heap object count from 30GB of small objects; GC scan/mark/compact cost scales with object count |
| MapDB | No performance improvement over NativeStorage | Every property access requires serialize/deserialize, negating off-heap GC benefits |

**Data distribution insight:** In static analysis workloads, frozen layers store structural data (AST, CFG) that is relatively small, while the active layer holds analysis state (abstract values, dataflow facts) that dominates memory usage.

---

## 2. Heap Object & GC Root Cause Analysis

### Heap Object Count Estimation

Given N nodes, M edges, average P properties per entity:

| Object Type | Count | Size (approx) | Total |
|---|---|---|---|
| NodeID | N | ~40B (object header + String ref + String internal) | 40N |
| EdgeID | M | ~96B (3 fields + 2 NodeID refs + String) | 96M |
| Property Map entry | (N+M) x P | ~64B (HashMap.Node + key String + IValue) | 64(N+M)P |
| IValue wrapper | (N+M) x P | ~32B (StrVal/NumVal object header + inner value) | 32(N+M)P |
| Adjacency Set entry | 2M | ~32B (HashSet.Node + EdgeID ref) | 64M |

**Example:** 2M nodes + 20M edges + 5 avg properties:
- Object count: ~2M + 20M + 110M + 110M + 40M = **~282M objects**
- Memory: ~40x2M + 96x20M + 64x110M + 32x110M + 64x20M = **~13.8GB** (data only, excluding HashMap internal overhead)

### GC Cost Analysis

| GC Type | 30GB Heap Scenario | Impact |
|---|---|---|
| Minor GC (Young Gen) | Frequency depends on allocation rate; high during graph construction | ~10-50ms each |
| Mixed GC (G1) | Must scan Old Gen references; marking 280M objects is expensive | ~100-500ms each |
| Full GC | Triggered under heap pressure; scans + compacts entire 30GB heap | **~2-10s pause** |
| Long-term | Old Gen fragmentation → frequent Mixed/Full GC | Throughput degrades over time |

**Core issue:** HashMap + IValue wrapping produces many small objects (~2-3 per property). Object count (not memory size) is the primary GC cost driver.

### Why ByteArray Is a GC Leaf

A `ByteArray` is a primitive array — GC marks it as live but does **not** trace any internal references (zero reference fields). In contrast, a `HashMap<String, IValue>` with P entries requires GC to trace ~2P references (keys + values + Node pointers).

For 1M entities × 10 properties:
- HashMap approach: GC traces **~20M references** per Full GC
- ByteArray approach: GC traces **0 references** for cold (uncached) entities

This is the fundamental GC advantage of ByteArray encoding: it converts a reference-dense object graph into a GC leaf node.

---

## 3. MapDB Limitations

### Serialization Cost vs GC Benefit

```
MapDB promise: data off-heap → fewer heap objects → less GC
MapDB reality: every access → temp ByteArray + IValue creation → heap objects increase
```

| Scenario | NativeStorage Heap Objects | MapDB Heap Objects |
|---|---|---|
| Steady state (after construction) | N+M entities + (N+M)P IValues (long-lived, Old Gen) | ~0 (off-heap), but... |
| Per getProp call | 0 (returns reference) | 1x ByteArray + 1x IValue (short-lived, Young Gen) |
| BFS traversal 1000 nodes x 5 props | 0 additional allocations | **5000 temp objects** (Young Gen pressure) |

MapDB shifts GC pressure from Old Gen (long-lived large objects) to Young Gen (high-frequency short-lived small objects). For **traversal-intensive** workloads, MapDB Young Gen GC pressure is higher.

### Serialization Latency Amplification

Graph traversal access patterns are **high-frequency, random, small-data**:

```
BFS/DFS traversal path:
  getOutgoingEdges(nodeId)  → MapDB: deserialize SetVal (full)
  → per edge: getEdge(edgeId)  → MapDB: deserialize edge properties
    → target node: getNode(targetId)  → MapDB: deserialize node properties

NativeStorage total: 3x HashMap.get (~150ns)
MapDB total: 3x off-heap read + 3x deserialize (~3000-6000ns)
Amplification: 20-40x
```

---

## 4. Bottleneck Catalog

### B1: EdgeID Object Overhead

**Location:** `EdgeID` is `data class(srcNid: NodeID, dstNid: NodeID, eType: String)`.

Each edge exists in 3 memory locations:
- `edgeProperties` key (1 EdgeID)
- `outEdges[src]` HashSet (1 EdgeID ref + HashSet.Node wrapper)
- `inEdges[dst]` HashSet (1 EdgeID ref + HashSet.Node wrapper)

**Memory estimate (3M edges):**
- EdgeID body: object header 16B + 2×NodeID ref 8B + String ref 8B + lazy asString 8B + lazy serialize 8B ≈ 48B
- 3M × 48B = 144MB (EdgeID objects only)
- outEdges + inEdges HashSet.Node wrappers: 3M × 2 × 32B = 192MB
- edgeProperties HashMap.Node: 3M × 32B = 96MB
- **EdgeID total: ~430MB for 3M edges**

**Optimizations:**

Option A — EdgeID intern pool:
- Factory method deduplicates identical src+dst+type EdgeIDs, returns same instance
- Reduces duplicate objects in adjacency sets and property maps

Option B — Integer edge indexing:
- Internal `Int edgeSeqId` index, maintain `EdgeID↔Int` mapping
- Adjacency uses `IntOpenHashSet` (no boxing, no HashSet.Node)
- 3M × 2 × (32→4) = 192MB → 24MB, saves ~170MB

### B2: Per-Entity MutableMap Property Storage

**Location:** `NativeStorageImpl` — `nodeProperties: Map<NodeID, MutableMap<String, IValue>>`

Each node/edge holds its own `MutableMap<String, IValue>`:
- 1M nodes × empty LinkedHashMap (initial 16 slots × 8B + header ≈ 180B) = 180MB structure overhead
- IValue objects: `NumVal(core: Number)` — Number boxing 16B + NumVal header 16B = 32B/value
- `StrVal(core: String)` — String 40B + StrVal 16B = 56B/value
- 1M nodes × 5 properties × ~40B avg = 200MB property values
- 1M nodes × 5 properties × HashMap.Node 32B = 160MB HashMap structure
- **Total: ~540MB for 1M nodes × 5 properties**

**Optimizations:**

Option A — Columnar property storage:
- Store by property name: `Map<String, Map<EntityID, IValue>>`
- Eliminates per-entity HashMap overhead; suited for fixed property schemas

Option B — Fixed schema array storage:
- Register property schema: `["type"→0, "weight"→1, ...]`
- Each entity stores `Array<IValue?>(schema.size)` instead of HashMap
- Array 4B/slot vs HashMap 32B/entry → ~7x savings

### B3: Graph Layer Creates Wrapper Objects Per Query

**Location:** `AbcMultipleGraph.kt` — `getNode` calls `newNodeObj(nid)` on every invocation.

```kotlin
override fun getNode(whoseID: NodeID): N? {
    if (whoseID !in nodeIDs || !storage.containsNode(whoseID)) return null
    return newNodeObj(nid = whoseID)  // new allocation every call
}
```

`getOutgoingEdges` calls `newEdgeObj(it)` per edge. Traversing 1000 edges = 1000 short-lived objects → Young Gen GC pressure.

**Optimizations:**

Option A — Node/Edge object cache pool:
- `WeakHashMap<NodeID, N>` / `WeakHashMap<EdgeID, E>` caches created objects
- Repeated `getNode(same id)` returns same instance
- ~80% allocation reduction in traversal-heavy scenarios

Option B — Lightweight flyweight cursor:
- Node/Edge objects are stateless (hold only storage ref + id)
- Reuse a single mutable cursor object (unsafe under concurrency but fast)

### B4: LayeredStorage Cross-Layer Query Multiplier

**Location:** `LayeredStorageImpl` — `containsNode` must check active layer then frozen layers.

Benchmark: single-layer 20.4M ops/s → dual-layer 7.61M ops/s (**2.7x slowdown**).

`getNodeProperties` is worse — must read from multiple HashMaps and merge via `putAll` into a new HashMap.

**Optimizations:**

Option A — containsNode bitmap cache:
- Maintain `BitSet` (or Bloom filter) marking which layer owns each node
- `containsNode`: O(1) bit check replaces multi-layer `HashMap.containsKey`

Option B — Lazy merged Map for getNodeProperties:
- Return `LazyMergedMap(baseMap, presentMap)` implementing `Map` interface
- Lookup checks present first, then base — no new HashMap creation
- Materializes only when iterating all keys
- Single property access: O(1) instead of O(base.size + present.size)

### B5: LayeredStorage Linear Scan of All Frozen Layers

**Location:** `LayeredStorageImpl` — `containsNode` iterates all frozen layers.

Benchmark: 1 layer 35.9M ops/s → 10 layers 7.12M ops/s (**5x slowdown**).

**Optimizations:**

Option A — Global `NodeID→layerIndex` mapping:
- `HashMap<NodeID, Int>` records which layer each node first appears in
- `containsNode`: O(1) HashMap lookup
- `getNodeProperties`: jump directly to correct layer, no scanning

Option B — Merge properties into newest frozen layer on freeze:
- When freezing, if a node exists in multiple frozen layers, merge properties into topmost
- Query only checks activeLayer + newest frozen layer
- Query complexity: O(layer count) → O(1)

### B6: Edge Query Allocates Intermediate HashSet

**Location:** `LayeredStorageImpl` — each edge query creates and fills a new HashSet.

Benchmark: Native 42.8M ops/s → layered 4.2M ops/s (**10x slowdown**), primarily from HashSet allocation + merge.

**Optimizations:**

Option A — Return views instead of copies:
- Custom `ConcatenatedSet<T>(set1, set2)` implementing `Set` interface
- `iterator()` chains both sets lazily
- Zero allocation; computation on demand
- Edge query: O(degree) allocation → O(1) allocation

Option B — For LayeredStorage (no deletion tracking):
- If edge exists in only one layer, return that layer's Set reference directly (zero-copy)
- Multi-layer merge uses `ConcatenatedSet`

### B7: NodeID String Duplication

**Location:** `NodeID` is `data class(val name: String)`.

Each EdgeID holds `srcNid: NodeID` and `dstNid: NodeID`. If node "A" has 100 outgoing edges, there are 100 independent `EdgeID.srcNid == NodeID("A")` instances.

**Optimization — NodeID intern pool:**
- Global `ConcurrentHashMap<String, NodeID>` cache
- `NodeID.of("A")` always returns the same instance
- 1M nodes × avg 3 edges/node → 6M NodeID refs in EdgeIDs reduced from 6M objects to 1M
- Saves ~5M × 40B = 200MB

### B8: Redundant Dual Existence Check in Graph Layer

**Location:** `AbcMultipleGraph.kt`:

```kotlin
fun containNode(whoseID: NodeID): Boolean =
    nodeIDs.contains(whoseID) && storage.containsNode(whoseID)
```

Graph layer independently maintains `nodeIDs: MutableSet<NodeID>` and `edgeIDs: MutableSet<EdgeID>`, redundant with storage:
- Extra 1M NodeID references in Graph's Set (redundant with storage)
- Every `containNode` performs two HashMap lookups

**Optimization:**
- Remove Graph layer's `nodeIDs`/`edgeIDs` Set, delegate directly to `storage.containsNode/containsEdge`
- Or keep only `BitSet` / Bloom filter for fast rejection
- Memory: ID tracking overhead halved; query speed ~2x

---

## 5. Abstract Interpretation Workload Analysis

### Workload Patterns

Abstract interpretation in static analysis has distinct graph access patterns:

```
Phase 1: Graph construction (one-time)
  Parse source → build CFG/call graph/PDG → topology fixed

Phase 2: Fixpoint iteration (high-frequency, core bottleneck)
  repeat:
    for each node in worklist:
      read node properties (current abstract state)
      read incoming edges → read predecessor properties
      compute transfer function → new abstract state
      if state changed:
        write node property (update abstract state)
        add successors to worklist
  until fixpoint
```

| Dimension | Phase 1 (Construction) | Phase 2 (Fixpoint) |
|---|---|---|
| Topology ops | Bulk addNode/addEdge | **Near-zero** (read-only traversal) |
| Property reads | Few (initialization) | **Extremely high** (every transfer function) |
| Property writes | Few (initialization) | **High** (on state change) |
| Access pattern | Sequential bulk | **Random** (worklist-driven) |
| Hot data | None | Loop body nodes accessed repeatedly |

**Core requirement:** O(1) random property read/write + minimal GC overhead + efficient read-only topology traversal

### Analysis State Externalization

Fixpoint iteration state should not go through the IValue/IStorage path. Direct-indexed arrays keyed by node sequential ID:

```kotlin
class AnalysisStateStore<S : AbstractState>(nodeCount: Int) {
    private val states: Array<S?> = arrayOfNulls(nodeCount)

    fun get(nodeSeqId: Int): S? = states[nodeSeqId]       // O(1), zero allocation
    fun set(nodeSeqId: Int, state: S) {                    // O(1), zero allocation
        states[nodeSeqId] = state
    }
}
```

**Per-access performance comparison:**

| Approach | Read Latency | Write Latency | GC Objects/Access | Suitability |
|---|---|---|---|---|
| NativeStorage HashMap | ~50ns | ~50ns | 0 (already on heap) | Good, but 30GB GC issue |
| MapDB | ~3-6us | ~3-6us | 2-3 (ByteArray + IValue) | Poor |
| **Direct array index** | **~5-10ns** | **~5-10ns** | **0** | **Optimal for analysis state** |

**Key advantages:**
- Analysis state array is separate from graph structural properties; fixpoint iteration does not touch graph data
- Array size = node count x state size; for 2M nodes x 64B state = ~128MB (manageable)
- Zero serialization: analysis state is JVM object reference, no IValue wrapping
- GC-friendly: single large array vs millions of HashMap.Node entries

---

## 6. Recommended Architecture

### Layered Storage + State Externalization

```
┌─────────────────────────────────────────────────────────┐
│                    IStorage Interface                     │
├─────────────────────────────────────────────────────────┤
│  Frozen Layers: structural data (AST, CFG, read-only)    │
│  ┌─────────────────────────────────────────────────┐    │
│  │  NativeStorageImpl (HashMap, frozen via factory)  │    │
│  │    O(1) read, ~50ns per access                   │    │
│  └─────────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────────┤
│  Active Layer: current phase mutations (in-heap)         │
│  ┌─────────────────────────────────────────────────┐    │
│  │  NativeStorageImpl (HashMap)                     │    │
│  │    O(1) read/write, full CRUD                    │    │
│  │    Only holds current phase data (bounded size)  │    │
│  └─────────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────────┤
│  External: analysis state (outside IStorage)             │
│  ┌─────────────────────────────────────────────────┐    │
│  │  AnalysisStateStore<S>                           │    │
│  │    Direct array indexed by node.seqId            │    │
│  │    O(1) read/write, zero serialization, zero GC  │    │
│  │    ~5-10ns per access                            │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### Solution Comparison

| Approach | Topology Build | Fixpoint Read/Write | 30GB Graph GC | Dependencies | Complexity |
|---|---|---|---|---|---|
| NativeStorage only | O(1) | ~50ns | **2-10s Full GC** | none | low |
| MapDB frozen layers | O(1) + freeze | ~50ns (hit) / ~3-6us (miss) | low (off-heap) | MapDB | medium |
| RocksDB | O(log n) | ~1-5us (too slow) | low | RocksDB JNI | medium |
| Chronicle Map | O(1) | ~200-500ns | low | OpenHFT | high |
| **Layered + State External + query views** | O(1) + freeze | **~5-10ns (state) / ~50ns (structural)** | **reduced via LayeredStorage optimizations** | **none** | **medium** |

---

## 7. Optimization Evaluation

All optimizations below are **internal implementation changes** — the public API (`IStorage`, `NodeID`, `EdgeID`, `AbcNode`, `AbcEdge`) is unchanged. Library users see no difference.

### Evaluation Criteria

Each optimization is evaluated on two axes:

- **Performance gain**: query latency improvement, GC pause reduction, throughput increase
- **Memory gain**: heap object count reduction, RSS reduction

Score scale: ◎ = major gain, ○ = moderate gain, △ = minor gain, — = no effect, ✗ = negative (trade-off cost)

### B1: EdgeID Overhead

| Option | Performance | Memory | Impl Complexity | Notes |
|---|---|---|---|---|
| A: EdgeID intern pool | △ less GC (fewer objects) | ○ ~200MB/3M edges (dedup instances) | Low | `ConcurrentHashMap<Triple, EdgeID>` factory. Risk: pool itself grows; needs weak refs or bounded size. Effective only when same EdgeID appears in multiple structures. |
| B: Int edge indexing + IntSet | ○ IntSet iteration faster, less cache-miss | ◎ ~170MB/3M edges (48B→4B per ref × 6M refs) | High | Requires `EdgeID↔Int` bidirectional map inside storage. IntSet (eclipse-collections or custom) replaces `HashSet<EdgeID>` for adjacency. Breaks no API — `getOutgoingEdges` returns `Set<EdgeID>` by reconstructing from int index. |

**Recommendation**: Option A (intern pool) is low-hanging fruit. Option B deferred to P5 — high complexity, requires eclipse-collections dependency.

### B2: Per-Entity Property Storage

| Option | Performance | Memory | Impl Complexity | Notes |
|---|---|---|---|---|
| A: Columnar storage | ○ better cache locality for same-property scans | ○ eliminates per-entity HashMap overhead (~340MB→~200MB) | Medium | `Map<String, Map<EntityID, IValue>>`. Good for batch analytics (scan one property across all entities). Worse for single-entity all-props access (must join across columns). |
| B: Fixed schema array | ○ array index vs HashMap lookup | ◎ 7x structure savings (32B/entry→4B/slot) | Medium | Requires schema registration at construction time. `Array<IValue?>(schema.size)` per entity. Handles schema mismatch gracefully (fall back to map for extra keys). Works for both active and frozen layers. |

**Note**: ByteArray packing (CompactFrozenStorageImpl) was evaluated and removed — benchmarks showed only 1.5x memory reduction (SoftReference cache keeps decoded Maps alive for hot data) with 30-58% read speed penalty. Not worth the trade-off.

**Recommendation**: Option B (fixed schema array) is the most versatile — works for both active and frozen layers, no serialization cost, compatible with mutation. Option A is niche (useful only for columnar analytics queries).

### B3: Wrapper Object Allocation

| Option | Performance | Memory | Impl Complexity | Notes |
|---|---|---|---|---|
| A: WeakHashMap cache | ◎ ~80% allocation reduction in traversal | △ cache entries add some overhead | Low | `WeakHashMap<NodeID, N>` in `AbcMultipleGraph`. Repeated `getNode(sameId)` returns same instance. WeakRef allows GC reclaim when no external refs. |
| B: Flyweight cursor | ◎ zero allocation | — neutral | Low | Single mutable object, unsafe under concurrency. Only viable for single-threaded traversal. |

**Recommendation**: Option A — safe, effective, low complexity. Option B only for performance-critical single-threaded paths.

### B4: Cross-Layer Query Multiplier

| Option | Performance | Memory | Impl Complexity | Notes |
|---|---|---|---|---|
| A: BitSet layer ownership | ◎ O(1) bit check vs multi-layer HashMap.containsKey | △ BitSet ~125KB/1M nodes (negligible) | Medium | Maintain `BitSet` per layer, set bit for each node's seqId. `containsNode` checks bits instead of delegating to each layer. Requires node seqId mapping (pairs with P2). |
| B: Lazy merged Map | ◎ single-prop O(1) vs O(base.size) merge | ◎ zero allocation (no new HashMap) | Medium | `getNodeProperties` returns `LazyMergedMap(frozenProps, activeProps)`. Implements `Map` interface. Materializes only on iteration. Single `get(key)` checks active first, then frozen — no copy. |

**Recommendation**: Both are high-value. Option B has the highest single-optimization impact — `getNodeProperties` is called on every property access path in LayeredStorage, and currently allocates a new HashMap + copies all entries. LazyMergedMap eliminates this entirely.

### B5: Linear Frozen Layer Scan

| Option | Performance | Memory | Impl Complexity | Notes |
|---|---|---|---|---|
| A: NodeID→layerIndex map | ◎ O(1) direct jump vs O(layers) scan | △ ~40B/node (HashMap entry) | Low | `HashMap<NodeID, Int>` updated on freeze. `getNodeProperty(id, name)` jumps to correct layer. 5x speedup at 10 layers. |
| B: Merge on freeze | ◎ query always O(1) — only 1 frozen layer | ◎ eliminates duplicate entries across layers | Medium | When freezing, merge node properties from all older layers into newest. Simplifies query to: check active → check single frozen. Trade-off: freeze is slower (merge cost). |

**Recommendation**: Option B is architecturally cleaner — keeps query path simple and eliminates stale duplicates. Freeze is an infrequent operation (per analysis phase), so merge cost is acceptable. Option A is simpler but leaves stale data in old layers.

### B6: Edge Query HashSet Allocation

| Option | Performance | Memory | Impl Complexity | Notes |
|---|---|---|---|---|
| A: ConcatenatedSet view | ◎ zero-copy, O(1) construction | ◎ no allocation | Medium | `ConcatenatedSet<EdgeID>(frozenSet, activeSet)` implements `Set`. Iterator chains both. `contains` delegates to both. `size` sums both. Correct because edges are append-only (no deletion across layers). |
| B: Single-layer fast path | ◎ return reference directly if edge in one layer | — no extra savings over A | Low | `if (activeLayer edges empty) return frozenLayer.getOutgoing(id)` — zero allocation. Combined with A for multi-layer case. |

**Recommendation**: Both — Option B as fast path, Option A as fallback. Together they eliminate all HashSet allocation in edge queries.

### B7: NodeID String Duplication

| Option | Performance | Memory | Impl Complexity | Notes |
|---|---|---|---|---|
| Intern pool | △ less GC (fewer objects) | ○ ~200MB/3M edges (5M duplicate NodeID eliminated) | Low | `ConcurrentHashMap<String, NodeID>` factory method `NodeID.of(name)`. All EdgeID creation uses interned NodeIDs. Existing `NodeID(name)` constructor still works (backward compatible). |

**Recommendation**: Implement — low effort, meaningful memory savings proportional to edge count.

### B8: Redundant nodeIDs Set in Graph Layer

| Option | Performance | Memory | Impl Complexity | Notes |
|---|---|---|---|---|
| Delegate to storage | ○ one lookup instead of two | ○ ~40MB/1M nodes | Low | Remove `AbcMultipleGraph.nodeIDs` Set, delegate `containNode` directly to `storage.containsNode`. Same for edges. |

**Recommendation**: Implement — straightforward, removes redundancy. Only risk: if graph layer uses nodeIDs for iteration independent of storage (need to verify).

### P0: Analysis State Externalization

| Aspect | Assessment |
|---|---|
| Performance | ◎ **5-10ns** per access (array index) vs 50ns (HashMap). Eliminates all IValue wrapping on the hottest code path. |
| Memory | ◎ Single `Array<S?>` vs millions of HashMap entries + IValue wrappers. 2M nodes × 64B state = 128MB vs potentially GBs through IStorage. |
| Complexity | Low — new standalone class, zero coupling to IStorage. |
| API impact | **None** — `AnalysisStateStore<S>` is a new class used by analysis clients alongside IStorage, not replacing it. |

**Recommendation**: **Highest priority**. This is the single most impactful optimization because it removes the highest-frequency read/write path from IStorage entirely. Fixpoint iteration dominates runtime; moving it to direct array access transforms the bottleneck.

### P4: G1GC Tuning

| Aspect | Assessment |
|---|---|
| Performance | ○ Reduces worst-case GC pauses. `-XX:MaxGCPauseMillis=200` caps target; `-XX:G1HeapRegionSize=32m` reduces region tracking overhead for 30GB heap. |
| Memory | — No memory savings; may increase heap reservation with `-XX:InitiatingHeapOccupancyPercent=45`. |
| Complexity | Zero — JVM flags only. |

**Recommendation**: Apply as baseline. No code change, immediate effect. But this is a **mitigation**, not a fix — the fundamental issue is object count, which other optimizations address.

---

## 8. Recommended Plans

### Plan A: Optimize for Performance (minimize query latency)

Priority order based on latency impact on hot paths:

| Step | Optimization | Latency Impact | Memory Impact |
|---|---|---|---|
| 1 | P0: Analysis State Externalization | ◎ 10-50x faster fixpoint iteration | ◎ GBs saved |
| 2 | B4-B: Lazy merged Map | ◎ eliminates HashMap copy per getNodeProperties | ◎ zero allocation |
| 3 | B6-A+B: ConcatenatedSet + fast path | ◎ eliminates HashSet alloc per edge query | ◎ zero allocation |
| 4 | B5-B: Merge on freeze | ◎ query always O(1) layers | ○ removes duplicates |
| 5 | B3-A: Node/Edge WeakHashMap cache | ◎ 80% fewer wrapper allocations | △ cache overhead |
| 6 | B8: Remove redundant ID Sets | ○ halves containsNode lookups | ○ ~40MB |
| 7 | P4: G1GC tuning | ○ caps pause times | — |

This plan focuses on eliminating allocations on every query path. Steps 1-4 together make the hot loop (fixpoint iteration over frozen structural data) near-optimal: direct array for state, lazy views for properties, zero-copy for edges.

### Plan B: Optimize for Memory (minimize heap footprint)

Priority order based on memory reduction:

| Step | Optimization | Memory Impact | Performance Impact |
|---|---|---|---|
| 1 | P0: Analysis State Externalization | ◎ GBs saved (state out of IStorage) | ◎ also faster |
| 2 | B7: NodeID intern pool | ○ ~200MB/3M edges | △ less GC |
| 3 | B1-A: EdgeID intern pool | ○ ~200MB/3M edges | △ less GC |
| 4 | B8: Remove redundant ID Sets | ○ ~40MB/1M nodes | ○ faster |
| 5 | B5-B: Merge on freeze | ○ removes cross-layer duplicates | ◎ faster queries |
| 6 | B2-B: Fixed schema array | ◎ ~7x per-entity savings | ○ array index faster than HashMap |
| 7 | B1-B: Int edge indexing | ◎ ~170MB/3M edges | ○ faster iteration |

This plan prioritizes shrinking the heap. Steps 1-5 are low-to-medium effort. Steps 6-7 are deferred unless memory remains problematic.

### Balanced Recommendation

For a static analysis workload with 2M nodes + 20M edges + 5 avg properties:

| Phase | Optimizations | Expected Effect |
|---|---|---|
| **Phase 1** (immediate) | P0 + P4 + B8 | Fixpoint 10-50x faster; GC tuned; redundancy removed |
| **Phase 2** (LayeredStorage views) | B4-B (LazyMergedMap) + B6-A (ConcatenatedSet) + B5-B (merge on freeze) | Zero-allocation property/edge queries; O(1) layer depth |
| **Phase 3** (object dedup) | B7 (NodeID intern) + B1-A (EdgeID intern) + B3-A (node cache) | ~400MB saved; 80% fewer wrapper allocs |
| **Phase 4** (if needed) | B1-B (int edge index) + B2-B (schema array for active layer) | Further 170MB + 7x active layer savings |

**Estimated total impact** (30GB baseline → after all phases):
- Memory: 30GB → ~10-15GB (state externalized, dedup, query views)
- GC pause: 2-10s Full GC → <500ms Mixed GC (282M objects → ~50M objects)
- Fixpoint latency: dominated by array access (5-10ns) instead of HashMap (50ns)

---

## 9. Phase 1 Benchmark Results

Benchmarked on macOS (Apple Silicon), JDK 21, G1GC with `-XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m`.

Run with: `./gradlew :graph:test --tests "*.Phase1BenchmarkTest" -PincludePerformanceTests`

### B8: Redundant ID Set Removal

Graph-level `nodeIDs`/`edgeIDs` sets removed; all entity lookups delegate directly to `IStorage`.

**Memory (50K nodes, 150K edges):**

| Metric | Before | After | Change |
|---|---|---|---|
| Storage only | 82.2 MB | 89.6 MB | baseline |
| Storage + graph | 101.2 MB | 92.1 MB | — |
| Graph overhead (ID sets) | **19.1 MB** | **2.5 MB** | **-87%** |

At 2M nodes + 20M edges, the projected saving is ~250 MB.

**Query speed (50K nodes, 200K queries):**

| Operation | Before | After | Change |
|---|---|---|---|
| `graph.containNode` | 34.01M ops/s | 46.15M ops/s | **+36%** |
| Overhead vs raw `storage.containsNode` | 1.30x | 1.17x | closer to storage speed |

**Edge query (10K nodes, 5 edges/node, 100K queries):**

| Operation | Before | After |
|---|---|---|
| `graph.getOutgoingEdges` | 5.11M ops/s | 6.79M ops/s |

Remaining graph→storage overhead comes from virtual dispatch and wrapper object allocation (B3).

### P0: Analysis State Externalization

`AnalysisStateStore<S>` provides direct array-indexed access, bypassing IStorage for fixpoint iteration.

**Speed (50K nodes, 200K iterations):**

| Approach | Read | Write |
|---|---|---|
| IStorage (HashMap) | 38–44M ops/s | 11–24M ops/s |
| AnalysisStateStore (array) | 1800–2059M ops/s | 163–173M ops/s |
| **Speedup** | **42–62x** | **7–14x** |

**Memory (100K nodes, 1 state per node):**

| Approach | Heap | Savings |
|---|---|---|
| IStorage (HashMap per node) | 45.0 MB | — |
| Direct `Array<IValue>` | 3.4 MB | **13.1x** |

### P4: G1GC Tuning

Applied as JVM flags for performance test tasks:

```
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=32m
-XX:InitiatingHeapOccupancyPercent=45
```

Recommended for production deployments with heap > 4GB. Caps GC pause target; does not reduce object count (addressed by other optimizations).

---

## External Library Assessment

| Library | Use Case | Overhead | Verdict |
|---|---|---|---|
| RocksDB JNI | Off-heap KV with prefix seek | ~100ns JNI/call, native resource management, platform-specific libs | Not needed — frozen layer is point-query only |
| Chronicle Map | Off-heap HashMap with flyweight | mmap setup, entry pre-sizing, variable-length value complexity | Overhead not justified for current use case |
| Eclipse Collections | Primitive-specialized collections | Near-zero (pure Java) | Consider for Phase 4 if HashMap overhead remains |
| MapDB | Off-heap B-tree storage | ~3-6us serialize/deserialize per access | Not suitable — serialization overhead negates GC benefit |
