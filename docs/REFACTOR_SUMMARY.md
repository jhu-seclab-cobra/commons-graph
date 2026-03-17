# Refactor Summary: Eliminate InternalID Exposure

**Date**: 2026-03-16
**Status**: Design Completed (Implementation Pending)

## Overview

This refactor moves the `InternalID` (internal integer-based ID) concept from a domain/entity-layer concern to a **pure storage implementation detail**. The goal is to simplify the API, eliminate the `resolver` function pattern, and ensure clear responsibility boundaries.

## Key Changes

### IStorage Interface (Storage Layer)

**Before:**
```kotlin
interface IStorage {
    val nodeIDs: Set<Int>  // Internal IDs
    fun addNode(properties: Map<String, IValue> = emptyMap()): Int  // Returns internal ID
    fun addEdge(src: Int, dst: Int, type: String, ...): Int
    fun getEdgeSrc(id: Int): Int  // Returns internal ID
    fun getEdgeDst(id: Int): Int
    // All methods use Int IDs
}
```

**After:**
```kotlin
interface IStorage {
    val nodeIDs: Set<String>  // Semantic IDs
    fun addNode(nodeId: String, properties: Map<String, IValue> = emptyMap()): String
    fun addEdge(src: String, dst: String, edgeId: String, type: String, ...): String
    fun getEdgeSrc(id: String): String  // Returns NodeID String
    fun getEdgeDst(id: String): String
    // All methods use String IDs
}
```

### Storage Implementation (NativeStorageImpl, etc.)

Storage implementations now maintain internal mapping:
```kotlin
class NativeStorageImpl : IStorage {
    private val nodeStringToInt = HashMap<String, Int>()  // String → InternalID
    private val edgeStringToInt = HashMap<String, Int>()  // String → InternalID

    // Internal ID management is entirely contained here
    // All IStorage methods use String IDs
}
```

### AbcNode (Entity Layer)

**Before:**
```kotlin
abstract class AbcNode(
    protected val storage: IStorage,
    internal val internalId: InternalID,  // Exposed InternalID
)
```

**After:**
```kotlin
abstract class AbcNode(
    protected val storage: IStorage,
    protected val nodeId: NodeID,  // Only NodeID, no InternalID
)
```

### AbcEdge (Entity Layer)

**Before:**
```kotlin
abstract class AbcEdge(
    protected val storage: IStorage,
    internal val internalId: InternalID,  // Exposed InternalID
    private val nodeIdResolver: (InternalID) -> NodeID,  // Awkward resolver function
) {
    val srcNid: NodeID by lazy {
        nodeIdResolver(storage.getEdgeSrc(internalId))  // Need translation
    }
}
```

**After:**
```kotlin
abstract class AbcEdge(
    protected val storage: IStorage,
    protected val edgeId: String,  // Only EdgeID String, no InternalID
) {
    val srcNid: NodeID by lazy {
        storage.getEdgeSrc(edgeId)  // Direct String return, no translation
    }
}
```

### AbcMultipleGraph (Domain Layer)

**Before:**
```kotlin
abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> {
    private val nodeIdCache = HashMap<NodeID, InternalID>()      // Maps user IDs
    private val nodeSidCache = HashMap<InternalID, NodeID>()     // Reverse maps
    private val edgeIndex = HashMap<Triple<InternalID, InternalID, String>, InternalID>()

    protected abstract fun newNodeObj(internalId: InternalID): N
    protected abstract fun newEdgeObj(internalId: InternalID, nodeIdResolver: ...): E
}
```

**After:**
```kotlin
abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> {
    // No more nodeIdCache/nodeSidCache - Storage handles ID mapping
    // edgeIndex still exists but uses String keys
    private val edgeIndex = HashMap<Triple<String, String, String>, String>()

    protected abstract fun newNodeObj(nodeId: NodeID): N
    protected abstract fun newEdgeObj(edgeId: String): E
}
```

## Benefits

1. **Simpler API**
   - Storage layer operates on semantic String IDs
   - No need for resolver functions or ID translation
   - Graph layer doesn't need to maintain dual ID caches

2. **Clear Responsibility**
   - Domain/Entity layers: Semantic String IDs
   - Storage layer: ID management (String ↔ Internal conversion)
   - InternalID never exposed beyond storage implementation

3. **Reduced Complexity**
   - Eliminate `nodeIdCache`, `nodeSidCache`, resolver functions
   - Fewer layers of ID indirection
   - Easier to understand and maintain

4. **Performance (Slightly Better)**
   - Direct String ID passing (no HashMap lookups in graph layer)
   - Storage implementations can optimize internal ID representation

5. **Consistency**
   - All external APIs use String IDs
   - No ambiguity about which ID type to use where

## Scope of Changes

### Files to Modify

1. **IStorage interface** (`graph/src/main/kotlin/edu/jhu/cobra/commons/graph/storage/IStorage.kt`)
   - Change all method signatures from `Int` to `String`
   - Update docstrings

2. **All Storage Implementations**
   - `NativeStorageImpl` — add nodeStringToInt, edgeStringToInt mappings
   - `NativeConcurStorageImpl` — add mappings with lock protection
   - `LayeredStorageImpl` — preserve String IDs across layers
   - External: `Neo4jStorageImpl`, `MapDBStorageImpl`, `JgraphtStorageImpl`

3. **Entity Classes**
   - `AbcNode` — remove internalId, use nodeId instead
   - `AbcEdge` — remove internalId and resolver function

4. **Graph Coordinator**
   - `AbcMultipleGraph` — remove nodeIdCache/nodeSidCache, update factory methods

5. **All Tests**
   - Update to use String IDs instead of Int

### Files NOT Changing (Semantically)

- `docs/idea.md` — ✅ Already updated
- `docs/core/storage.design.md` — ✅ Already updated
- `docs/core/graph.design.md` — ✅ Needs updating (example usage)
- `docs/core/entity.design.md` — ✅ Needs updating (AbcNode/AbcEdge signatures)

## Implementation Order

1. **Phase 1: IStorage Interface**
   - Define new interface signature
   - Update interface documentation

2. **Phase 2: NativeStorageImpl**
   - Add String ↔ Internal ID mappings
   - Update all methods to use new signatures
   - Implement reverse lookup for getEdgeSrc/getEdgeDst

3. **Phase 3: Entity Classes**
   - Update AbcNode/AbcEdge signatures
   - Remove internalId and resolver

4. **Phase 4: Graph Coordinator**
   - Update AbcMultipleGraph methods
   - Remove nodeIdCache/nodeSidCache
   - Update factory method signatures

5. **Phase 5: Tests**
   - Update all tests to use String IDs

6. **Phase 6: Other Implementations**
   - Update external storage implementations (Neo4j, MapDB, JGraphT)
   - Update LayeredStorageImpl
   - Update export/import classes

## Backward Compatibility

This is a **breaking change** to the `IStorage` interface. All code that depends on `IStorage` must be updated.

## Design Philosophy

**Responsibility as the guide:**
- Storage layer: Own the ID management (String ↔ Internal)
- Graph layer: Use String IDs directly, no caching/translation
- Entity layer: Simple property wrappers, String IDs
- **InternalID: Never leave the storage implementation**

This refactor embodies the principle: _"Make the right thing easy, and the wrong thing hard."_ By making `InternalID` inaccessible, we prevent its accidental exposure and misuse.
