# Storage Performance Analysis

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

## 2. NativeStorage GC Root Cause Analysis

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

## 4. Solution: CompactFrozenStorageImpl

### Design Rationale

External libraries (RocksDB, Chronicle Map) introduce JNI overhead, resource management complexity, and platform-specific issues without proportional benefit for the read-only frozen layer use case. The frozen layer needs no write support, no transactions, no range queries — just fast read-only property access with minimal heap objects.

`CompactFrozenStorageImpl` encodes each entity's properties into a single `ByteArray`. This is purpose-built for the frozen layer's read-only access pattern.

### How It Works

```
Freeze time (one-time cost, can be slow):
  Traverse all entities → encode properties into contiguous ByteArray → build index

Read time (high-frequency, must be fast):
  HashMap lookup entityId → ByteArray → scan to target property → decode single value
```

**Per-entity ByteArray layout:**

```
┌──────────┬──────────────────────────────┬──────────┬────────┐
│ propCount│ key (UTF-8, length-prefixed) │ type tag │ value  │ ... repeat
│ 2 bytes  │ 2 bytes (len) + bytes        │ 1 byte   │ varies │
└──────────┴──────────────────────────────┴──────────┴────────┘
```

### Key Property: On-Demand Single-Property Decode

Unlike MapDB which deserializes the entire entry, `getNodeProperty(id, name)` scans the ByteArray only to the matched key and decodes that single value. Unmatched properties are skipped without object allocation.

### SoftReference Cache

Decoded `Map<String, IValue>` results are cached via `SoftReference`:
- Cache hit: return existing map reference (~50ns, zero allocation)
- Cache miss: decode from ByteArray (~100-200ns, 1 IValue allocation)
- Memory pressure: GC reclaims cache entries automatically, no OOM risk

### Performance Comparison

| Metric | NativeStorage | MapDB | CompactFrozenStorage |
|---|---|---|---|
| Heap objects per entity | ~2P+1 | 0 | **1** |
| Single property read | ~50ns | ~3-6us | **~100-200ns (miss) / ~50ns (hit)** |
| Full property read | ~50ns | ~3-6us | **~200-500ns (miss) / ~50ns (hit)** |
| GC impact (2M entities, P=5) | ~22M objects | ~0 | **~2M objects (10x reduction)** |
| External dependencies | none | MapDB | **none** |

### Integration with LayeredStorageImpl

Inject as the frozen layer factory — zero changes to upper-layer code:

```kotlin
val storage = LayeredStorageImpl(
    frozenLayerFactory = { CompactFrozenStorageImpl() }
)
```

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
| CompactFrozenStorage | ~100-200ns | N/A (read-only) | 0-1 (cache) | Good for structural data |
| **Direct array index** | **~5-10ns** | **~5-10ns** | **0** | **Optimal for analysis state** |

**Key advantages:**
- Analysis state array is separate from graph structural properties; fixpoint iteration does not touch graph data
- Array size = node count x state size; for 2M nodes x 64B state = ~128MB (manageable)
- Zero serialization: analysis state is JVM object reference, no IValue wrapping
- GC-friendly: single large array vs millions of HashMap.Node entries

---

## 6. Recommended Architecture

### Layered Storage with CompactFrozen + State Externalization

```
┌─────────────────────────────────────────────────────────┐
│                    IStorage Interface                     │
├─────────────────────────────────────────────────────────┤
│  Frozen Layers: structural data (AST, CFG, read-only)    │
│  ┌─────────────────────────────────────────────────┐    │
│  │  CompactFrozenStorageImpl                        │    │
│  │    1 ByteArray per entity, SoftReference cache   │    │
│  │    ~100-200ns read, ~2M heap objects for 2M ents │    │
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
| **CompactFrozen + State External** | O(1) + freeze | **~5-10ns (state) / ~100-200ns (structural)** | **low (~2M objects)** | **none** | **medium** |

---

## 7. Implementation Priority

### P0: Analysis State Externalization

Move fixpoint iteration abstract state out of IStorage properties into independent `Array<S>` indexed by node seqId. This is the **highest priority** — directly eliminates all serialization and HashMap overhead on the hottest code path.

### P1: CompactFrozenStorageImpl

Implement `CompactFrozenStorageImpl` as the frozen layer backend for `LayeredStorageImpl`. Reduces frozen layer heap objects by ~10x with zero external dependencies.

### P2: Node SeqId Mapping

Assign contiguous integer IDs to NodeID, enabling array indexing for P0.

### P3: G1GC Tuning

`-XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m -XX:InitiatingHeapOccupancyPercent=45`

### P4: Further Optimizations (if needed)

| Optimization | Effect | When Needed |
|---|---|---|
| Eclipse Collections | ~30% memory reduction from HashMap/HashSet | Active layer memory still too large |
| mmap backing for CompactFrozen | Move frozen ByteArrays to off-heap via Panama MemorySegment | Frozen layer data exceeds available heap |
| CSR adjacency table | flatgraph-style compressed sparse row format | Topology traversal becomes bottleneck |
| Columnar property storage | Pack properties by type into primitive arrays | Extreme memory efficiency needed |

---

## External Library Assessment

| Library | Use Case | Overhead | Verdict |
|---|---|---|---|
| RocksDB JNI | Off-heap KV with prefix seek | ~100ns JNI/call, native resource management, platform-specific libs | Not needed — frozen layer is point-query only |
| Chronicle Map | Off-heap HashMap with flyweight | mmap setup, entry pre-sizing, variable-length value complexity | Not needed — CompactFrozen achieves similar read latency without dependency |
| Eclipse Collections | Primitive-specialized collections | Near-zero (pure Java) | Consider for P4 if HashMap overhead remains |
| MapDB | Off-heap B-tree storage | ~3-6us serialize/deserialize per access | Replaced by CompactFrozenStorageImpl for frozen layers |
