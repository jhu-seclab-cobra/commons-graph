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

### Typed indexed families

A registered indexed `PropertyKey<T>` owns a private primitive pair-key directory of immutable cells. `CellReader<T>` borrows access within the [read scope](design-storage.md#typed-storage-capabilities), selects one cell without cloning the directory, and rejects use after scope exit. Only immutable selected `T` may escape. The writer captures each changed payload once and updates cells and labels atomically under the storage guard. Legacy names project this one family, without an independent decoded cache.

The first active write to a frozen call family starts with an empty label set and empty input collection, never seeded from the frozen payload. Frozen label `L` with input `n7` and active label `L2` with input `n9` expose only the active family: `L2` cannot inherit frozen input `L`. Later active writes retain earlier active cells; revert restores the complete frozen family. Layer selection applies to labels and inputs together in named/cell reads, whole-row reads and Freeze. Caller schemas own call-specific policy; storage provides atomic family mechanics.

Typed labels use one immutable sorted distinct primitive-ID set; membership uses binary search and union visits both sets. Cells do not become thousands of property names or a copied directory per write. Full compatibility snapshots enumerate requested cells and payloads. A cell update includes new-payload capture and any label union; the whole family update is not constant-time.

`StorageReadScope.edgeCells(entity,key,layer): CellReader<T>` returns a scoped reader; the family writer validates schema, presence and capture before publishing labels and cells together. Wrong type, expired reader and unsupported family semantics fail distinctly. Registered fields and scoped cells are constructed with `LayeredStorageImpl(StorageSchema(keys))`; the fixed compatibility declaration remains available to legacy callers.

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

Register an `IndexedPropertyKey` before constructing schema storage, write its labels/cells through the guarded writer, and select one immutable payload through `edgeCells(entity,key,layer)` inside a read scope. A reader used after scope exit fails; the selected payload remains valid.

## Open Gaps

[Typed storage implementation](design-gaps-storage.md#typed-storage-implementation) tracks the remaining physical layout, lifetime and acceptance requirements.
