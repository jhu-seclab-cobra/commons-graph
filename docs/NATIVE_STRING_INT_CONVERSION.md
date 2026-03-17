# NativeStorageImpl: String ↔ Int Conversion Implementation

**Date**: 2026-03-16
**Status**: ✅ IMPLEMENTED AND VERIFIED

## Overview

NativeStorageImpl successfully implements the **String→Int conversion layer** as required by the refactored IStorage interface. Internally, the storage still uses integers for compactness and performance, but the public API exposes only String IDs.

## Translation Layer Architecture

### Data Structures

```kotlin
// String ↔ Int translation mappings
private val nodeStringToInt = HashMap<String, Int>()      // NodeID → InternalID
private val nodeIntToString = HashMap<Int, String>()      // InternalID → NodeID
private val edgeStringToInt = HashMap<String, Int>()      // EdgeID → InternalID
private val edgeIntToString = HashMap<Int, String>()      // InternalID → EdgeID

// Internal storage (remains Int-based)
private val nodeSet = HashSet<Int>()
private val nodeColumns = HashMap<String, HashMap<Int, IValue>>()
private data class EdgeEndpoints(val src: Int, val dst: Int, val type: String)
private val edgeEndpoints = HashMap<Int, EdgeEndpoints>()
private val outEdges = HashMap<Int, MutableSet<Int>>()
private val inEdges = HashMap<Int, MutableSet<Int>>()
```

## Public API Implementation (String-based)

### Node Operations

#### addNode(nodeId: String, properties): String
```kotlin
override fun addNode(nodeId: String, properties: Map<String, IValue>): String {
    ensureOpen()
    if (nodeId in nodeStringToInt) throw EntityAlreadyExistException(nodeId)
    val internalId = nodeCounter++
    nodeStringToInt[nodeId] = internalId       // Store mapping
    nodeIntToString[internalId] = nodeId       // Reverse mapping
    nodeSet.add(internalId)                    // Use internal Int
    // ... properties setup using internalId
    return nodeId
}
```

**Conversion flow**:
1. Check if String nodeId already exists
2. Generate new internal Int ID
3. Store bidirectional mappings
4. Use internal Int for all storage operations
5. Return the same String nodeId

#### getNodeProperties(id: String): Map<String, IValue>
```kotlin
override fun getNodeProperties(id: String): Map<String, IValue> {
    ensureOpen()
    val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
    return ColumnViewMap(internalId, nodeColumns)  // Use internal Int
}
```

**Conversion flow**:
1. Look up String ID in mapping → get internal Int
2. Use internal Int to access columnar storage
3. Return properties map

#### deleteNode(id: String)
```kotlin
override fun deleteNode(id: String) {
    ensureOpen()
    val internalId = nodeStringToInt.remove(id) ?: throw EntityNotExistException(id)
    nodeIntToString.remove(internalId)  // Clean up reverse mapping
    // ... cascade delete edges using internalId
}
```

**Conversion flow**:
1. Remove String→Int mapping and get internal Int
2. Clean up Int→String reverse mapping
3. Use internal Int to cascade delete edges

### Edge Operations

#### addEdge(src: String, dst: String, edgeId: String, type, properties): String
```kotlin
override fun addEdge(
    src: String,
    dst: String,
    edgeId: String,
    type: String,
    properties: Map<String, IValue>,
): String {
    ensureOpen()
    val srcInternal = nodeStringToInt[src] ?: throw EntityNotExistException(src)
    val dstInternal = nodeStringToInt[dst] ?: throw EntityNotExistException(dst)
    if (edgeId in edgeStringToInt) throw EntityAlreadyExistException(edgeId)
    val internalId = edgeCounter++
    edgeStringToInt[edgeId] = internalId       // Store mapping
    edgeIntToString[internalId] = edgeId       // Reverse mapping
    edgeEndpoints[internalId] = EdgeEndpoints(srcInternal, dstInternal, type)
    // ... use internal Int IDs
    return edgeId
}
```

**Conversion flow**:
1. Convert src String → srcInternal Int
2. Convert dst String → dstInternal Int
3. Validate both nodes exist (using internal Int)
4. Generate new internal Int for edge
5. Store bidirectional edge ID mappings
6. Store edge structure using internal Int IDs
7. Return the same String edgeId

#### getEdgeSrc(id: String): String
```kotlin
override fun getEdgeSrc(id: String): String {
    ensureOpen()
    val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
    val srcInternal = (edgeEndpoints[internalId] ?: throw EntityNotExistException(id)).src
    return nodeIntToString[srcInternal] ?: throw EntityNotExistException(id)
}
```

**Conversion flow**:
1. Convert edgeId String → internalId Int
2. Look up edge structure → get srcInternal Int
3. Convert srcInternal Int → srcNodeId String
4. Return the String NodeID

#### getEdgeDst(id: String): String
```kotlin
override fun getEdgeDst(id: String): String {
    ensureOpen()
    val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
    val dstInternal = (edgeEndpoints[internalId] ?: throw EntityNotExistException(id)).dst
    return nodeIntToString[dstInternal] ?: throw EntityNotExistException(id)
}
```

**Same as getEdgeSrc but for destination**

### Adjacency Queries

#### getIncomingEdges(id: String): Set<String>
```kotlin
override fun getIncomingEdges(id: String): Set<String> {
    ensureOpen()
    val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
    val internalEdgeIds = inEdges[internalId] ?: emptySet()
    return internalEdgeIds.mapNotNull { edgeIntToString[it] }.toSet()
}
```

**Conversion flow**:
1. Convert nodeId String → internalId Int
2. Look up incoming edges (internal Int Set)
3. Convert each internal edge Int → edge String ID
4. Return Set<String>

## Performance Characteristics

### Benefits of Internal Int-based Storage

1. **Memory Efficiency**
   - Int (4 bytes) vs String (variable, typically 20+ bytes)
   - HashMap keys are 4 bytes instead of String overhead
   - EdgeEndpoints stores 2 × 4-byte Ints instead of Strings

2. **Lookup Speed**
   - Int hash is O(1) single multiplication
   - String hash requires character iteration (O(n) for hash, O(n) for comparison)
   - HashMap operations faster with Int keys

3. **Storage Efficiency**
   - Columnar layout indexed by Int is compact
   - No redundant String storage in properties layer

### Conversion Overhead

- **addNode/addEdge**: O(1) HashMap insert for bidirectional mapping
- **getEdgeSrc/getDst**: O(1) HashMap lookups (3 total: edgeId→Int, Int→structure, Int→String)
- **Adjacency queries**: O(k) where k = number of edges (mapNotNull + HashMap lookups per edge)

### Overall Impact

- Internal operations: **100% Int-based (fast)**
- Public API: **100% String-based (user-friendly)**
- Conversion: **Minimal overhead** (HashMap operations are O(1))

## Verification

### ✅ Node Operations
- [x] addNode creates bidirectional mapping
- [x] containsNode checks String mapping
- [x] getNodeProperties converts String → Int
- [x] deleteNode cleans up both mappings
- [x] Duplicate detection via EntityAlreadyExistException

### ✅ Edge Operations
- [x] addEdge creates bidirectional mapping
- [x] containsEdge checks String mapping
- [x] getEdgeSrc converts String → Int → String (src node)
- [x] getEdgeDst converts String → Int → String (dst node)
- [x] getEdgeType retrieves directly (no String conversion needed)
- [x] getEdgeProperties converts String → Int
- [x] deleteEdge cleans up both mappings
- [x] Cascade deletion uses internal Int

### ✅ Adjacency
- [x] getIncomingEdges converts Set<Int> → Set<String>
- [x] getOutgoingEdges converts Set<Int> → Set<String>

### ✅ Error Handling
- [x] EntityNotExistException on missing node/edge
- [x] EntityAlreadyExistException on duplicate
- [x] Null safety in reverse lookups

## Backward Compatibility

Internal methods still available for LayeredStorageImpl and other internal use:
```kotlin
internal fun getStringToIntNodeMapping(): Map<String, Int>
internal fun getIntToStringNodeMapping(): Map<Int, String>
internal fun getEdgeSrcInternal(id: Int): Int
// ... etc
```

## Design Rationale

### Why Two-Layer?

1. **API Simplicity** — Users see only String IDs
2. **Performance** — Storage uses Int internally
3. **No Compromise** — Best of both worlds

### Why Bidirectional Mapping?

1. **getEdgeSrc/getDst** — Need to convert internal Int → NodeId String
2. **nodeIDs/edgeIDs** — Need to return String sets
3. **Consistency** — Both directions are O(1)

### Why HashMap for Mapping?

1. **O(1) average** — Constant time lookup
2. **Simple** — No complex structures needed
3. **Proven** — Kotlin HashMap is well-optimized

## Conclusion

NativeStorageImpl successfully implements the **String→Int conversion pattern**:
- ✅ Public API: 100% String-based
- ✅ Internal storage: 100% Int-based
- ✅ Conversion overhead: Minimal (O(1) HashMap operations)
- ✅ Performance: Maintained through internal Int usage
- ✅ Type safety: Guaranteed by IStorage interface

The implementation is **complete, correct, and production-ready**.
