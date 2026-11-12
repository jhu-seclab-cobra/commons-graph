# Storage property families

## Design Overview

- **Classes:** public `EdgePropertyFamilies` and `LayeredEdgeReads`; internal `ActiveLayer` and `LayerStack` consume the declaration.
- **Relationships:** `LayeredStorageImpl` owns one nullable declaration and supplies a stable accessor to both internal layers.
- **Exceptions:** `IllegalStateException` for an unequal rebinding.
- **Dependency roles:** The caller declares the family; Storage owns its binding and layer selection.

## Class / Type Specifications

### EdgePropertyFamilies

`EdgePropertyFamilies(edgeTag: String, memberPrefix: String, anchorName: String)` is an immutable declaration with no stored list, callback, registry or mutable member. `NONE` is null. A property belongs to the family when its edge has `edgeTag` and its name equals `anchorName` or starts with `memberPrefix`.

### LayeredEdgeReads

`propertyFamilies: EdgePropertyFamilies?` exposes the bound declaration. `getEdgeProperty(id: Int, name: String, layer: StorageLayer): IValue?` reads one selected property; `hasActiveEdgeProperty(id: Int, name: String): Boolean` distinguishes an explicit null edit from no edit. Constructor overloads configure the same slot; they do not allocate separate declarations.

## Function Specifications

`IStorage.bindPropertyFamilies(families: EdgePropertyFamilies): Unit` binds a declaration during construction, before or after import, without inspecting or migrating rows. Flat stores inherit its no-op default; forwarding stores delegate it. `LayeredStorageImpl` binds under its write lock: an absent declaration is assigned, an equal tuple succeeds without mutation, and an unequal tuple fails without changing Storage. Freeze, clear and revert retain the declaration; a separate Storage instance binds independently.

`EdgePropertyFamilies?.anchor(tag: String, name: String): String?` classifies a property, returning its family anchor or null. Classification compares one tag, anchor and prefix, with string work bounded by the addressed key and declaration lengths; binding compares one fixed tuple independent of Graph size.

The [model](model.md#property-families-in-layered-storage) owns family visibility. `ActiveLayer.composePromotedEdgeProperties` implements the whole-row composition used by reads and Freeze: while copying frozen properties, omit family members whose anchor has an explicit active edit, then apply every explicit active edit, null removals included. Other properties retain sparse fallback. Capturing the declaration once adds classification work per visited property; no per-edge, per-label, per-write or per-buffer registration state is retained.

## Exception / Error Types

`bindPropertyFamilies` throws `IllegalStateException` for an unequal rebind and preserves the declaration and data. `getEdgeProperty` returns null for an absent selected property; `hasActiveEdgeProperty` remains true for an explicit null edit.

## Caller Examples

```kotlin
import edu.jhu.cobra.commons.graph.storage.EdgePropertyFamilies
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl

val storage = LayeredStorageImpl()
storage.bindPropertyFamilies(EdgePropertyFamilies("call", "p_", "labels"))
check(storage.propertyFamilies?.anchorName == "labels")
```
