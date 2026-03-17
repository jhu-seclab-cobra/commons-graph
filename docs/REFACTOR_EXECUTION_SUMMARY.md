# InternalID Elimination Refactoring - Execution Summary

**Date**: 2026-03-16
**Status**: ✅ COMPLETED (Core :graph module)
**Commits**:
- 619e6d5: Design documentation updated
- 27ffa9c: Core implementation refactoring

## What Was Accomplished

### Phase 1: IStorage Interface ✅
**File**: `graph/src/main/kotlin/edu/jhu/cobra/commons/graph/storage/IStorage.kt`

- Changed all method signatures from `Int` IDs to `String` IDs
- Updated docstrings to reflect new semantic ID model
- Key changes:
  - `val nodeIDs: Set<Int>` → `val nodeIDs: Set<String>`
  - `fun addNode(properties): Int` → `fun addNode(nodeId: String, properties): String`
  - `fun addEdge(src: Int, dst: Int, type): Int` → `fun addEdge(src: String, dst: String, edgeId: String, type): String`
  - `fun getEdgeSrc(id: Int): Int` → `fun getEdgeSrc(id: String): String`
  - All adjacency queries now return `Set<String>`

### Phase 2: Storage Implementations ✅
**Files Modified**:
- `NativeStorageImpl.kt`
- `NativeConcurStorageImpl.kt`
- `LayeredStorageImpl.kt`

**Changes**:
- Added String↔Int translation layers in NativeStorageImpl:
  - `nodeStringToInt: HashMap<String, Int>`
  - `nodeIntToString: HashMap<Int, String>`
  - `edgeStringToInt: HashMap<String, Int>`
  - `edgeIntToString: HashMap<Int, String>`
- Public API uses String IDs
- Internal storage remains Int-based for efficiency
- Automatic duplicate detection via `EntityAlreadyExistException`

### Phase 3: Entity Classes Refactoring ✅
**Files Modified**:
- `AbcNode.kt`
- `AbcEdge.kt`

**AbcNode Changes**:
- Removed `internal val internalId: InternalID`
- Added `protected val nodeId: NodeID` (stored, not read from storage)
- Simplified `id` property to just return `nodeId`
- Updated all storage access: `storage.getNodeProperty(nodeId, name)`
- All operations now use String ID directly

**AbcEdge Changes**:
- Removed `internal val internalId: InternalID`
- Removed `private val nodeIdResolver: (InternalID) -> NodeID`
- Added `protected val edgeId: String`
- Simplified endpoint resolution:
  ```kotlin
  val srcNid: NodeID by lazy {
      storage.getEdgeSrc(edgeId)  // Returns String directly
  }
  ```
- No more resolver function needed
- All storage access uses String ID

### Phase 4: Graph Coordinator Refactoring ✅
**File**: `graph/src/main/kotlin/edu/jhu/cobra/commons/graph/AbcMultipleGraph.kt`

**Major Removals**:
- Eliminated `nodeIdCache: HashMap<NodeID, InternalID>`
- Eliminated `nodeSidCache: HashMap<InternalID, NodeID>`
- Eliminated `labelIdCache: HashMap<String, InternalID>`
- Eliminated `labelSidCache: HashMap<InternalID, Label>`
- Eliminated related cache initialization methods

**Simplified Signatures**:
- Before: `protected abstract fun newNodeObj(internalId: InternalID): N`
- After: `protected abstract fun newNodeObj(nodeId: NodeID): N`

- Before: `protected abstract fun newEdgeObj(internalId: InternalID, nodeIdResolver: (InternalID) -> NodeID): E`
- After: `protected abstract fun newEdgeObj(edgeId: String): E`

**Edge Index Update**:
- Before: `HashMap<Triple<InternalID, InternalID, String>, InternalID>`
- After: `HashMap<Triple<NodeID, NodeID, String>, String>`

**Label Storage**:
- Now uses String node IDs directly
- Removed intermediate ID translation

### Phase 5: Supporting Changes ✅
**Files Modified**:
- `poset/IPoset.kt` — Updated `Label.changes` from `Set<InternalID>` to `Set<String>`
- `storage/nio/IStorageExporter.kt` — Updated `EntityFilter` typealias
- `storage/nio/NativeCsvIOImpl.kt` — Updated for String-based storage API
- Test utilities updated for new signatures

## Design Impact

### Before (Three-Layer ID Confusion)
```
Domain Layer      Graph Layer               Storage Layer
NodeID (String)   → nodeIdCache lookup     →  InternalID (Int)
                    (bidirectional maps)       ↓ storage ops
                                               property storage
```

**Problems**:
- ID translation at every step
- Resolver function passing
- Complex caching logic
- InternalID exposed to AbcNode/AbcEdge

### After (Clean Two-Layer)
```
Domain Layer                Storage Layer
NodeID (String) ————————→ IStorage API (String IDs)
  ↓                          ↓ (internal only)
AbcNode/AbcEdge          Internal Int Mapping
                         (InternalID is impl detail)
```

**Benefits**:
- No ID translation at domain layer
- Direct String passing
- Storage owns the mapping
- InternalID never exposed outside storage

## Compilation Status

### ✅ Core Module (`:graph`)
- Main source code: **COMPILES SUCCESSFULLY**
- No errors or warnings in core graph module
- All interfaces properly updated
- All implementations complete

### ⚠️ Test Files
- Test files need updates for new String-based API (separate work)
- Test utilities partially updated

### ⚠️ External Storage Implementations
- Neo4j, MapDB, JGraphT modules need separate updates
- These are in different modules and maintained separately

## Code Quality

### Checks Performed
```bash
./gradlew :graph:compileKotlin  # ✅ SUCCESSFUL
```

### Pattern Compliance
- ✅ No `Any` types used
- ✅ Explicit types throughout
- ✅ String IDs consistent across layers
- ✅ Clear responsibility boundaries

## API Surface Changes Summary

| Aspect | Before | After |
|--------|--------|-------|
| IStorage.nodeIDs | `Set<Int>` | `Set<String>` |
| IStorage.addNode | Generates Int | Takes `nodeId: String` |
| IStorage.addEdge | Generates Int | Takes `edgeId: String` |
| IStorage.getEdgeSrc | Returns Int | Returns String (NodeID) |
| AbcNode constructor | `(storage, internalId: Int)` | `(storage, nodeId: String)` |
| AbcEdge constructor | `(storage, internalId: Int, resolver)` | `(storage, edgeId: String)` |
| newNodeObj | Takes `internalId: Int` | Takes `nodeId: String` |
| newEdgeObj | Takes `internalId: Int, resolver` | Takes `edgeId: String` |

## Migration Guide for Subclasses

### For Graph Implementations (AbcMultipleGraph subclasses)

**Before**:
```kotlin
class MyGraph : AbcMultipleGraph<MyNode, MyEdge>() {
    override fun newNodeObj(internalId: InternalID): MyNode {
        return MyNode(storage, internalId)
    }

    override fun newEdgeObj(internalId: InternalID, nodeIdResolver: (InternalID) -> NodeID): MyEdge {
        return MyEdge(storage, internalId, nodeIdResolver)
    }
}
```

**After**:
```kotlin
class MyGraph : AbcMultipleGraph<MyNode, MyEdge>() {
    override fun newNodeObj(nodeId: NodeID): MyNode {
        return MyNode(storage, nodeId)
    }

    override fun newEdgeObj(edgeId: String): MyEdge {
        return MyEdge(storage, edgeId)
    }
}
```

### For Node/Edge Subclasses

**Before**:
```kotlin
class MyNode(storage: IStorage, internalId: InternalID) : AbcNode(storage, internalId)

class MyEdge(storage: IStorage, internalId: InternalID, resolver: (InternalID) -> NodeID)
    : AbcEdge(storage, internalId, resolver)
```

**After**:
```kotlin
class MyNode(storage: IStorage, nodeId: NodeID) : AbcNode(storage, nodeId)

class MyEdge(storage: IStorage, edgeId: String) : AbcEdge(storage, edgeId)
```

## Next Steps

1. **Update test files** to use new String-based API
2. **Update external storage implementations** (Neo4j, MapDB, JGraphT) separately
3. **Run full test suite** once test files are updated
4. **Update user documentation** with new API patterns

## Files Modified Summary

```
Graph Module (12 files):
├── IStorage.kt (core interface)
├── NativeStorageImpl.kt
├── NativeConcurStorageImpl.kt
├── LayeredStorageImpl.kt
├── IStorageExporter.kt
├── NativeCsvIOImpl.kt
├── AbcNode.kt
├── AbcEdge.kt
├── AbcMultipleGraph.kt
├── IPoset.kt
└── Test utilities (2 files)
```

## Validation

✅ **Design alignment**: Matches REFACTOR_SUMMARY.md specification
✅ **Code compilation**: Core module compiles without errors
✅ **Type safety**: All explicit types, no `Any`
✅ **API consistency**: String IDs throughout domain layer
✅ **Responsibility**: InternalID fully encapsulated in storage

## Conclusion

The refactoring successfully eliminates InternalID from the domain and entity layers, moving it to a pure storage implementation detail. This achieves:

1. **Clearer abstractions** — Three-layer architecture now properly separated
2. **Simpler APIs** — No ID translation, direct String passing
3. **Reduced complexity** — Eliminated caching, resolvers, bidirectional maps
4. **Better maintainability** — Clearer responsibility boundaries

The core graph module is now clean and ready for test updates and external module integration.
