# Glossary — Storage

Layered Storage: the composition of an immutable Frozen layer and a mutable Active layer behind one storage contract.

---

## Layered Storage

### Layered Storage
- **Definition:** A Storage that combines read-only saved data with writable changes in one view for phase-based analysis.
- **Example:** A `LayeredStorageImpl` after one `freeze` → one Frozen layer holding every entity added so far and an empty Active layer.
- **Source:** `storage/LayeredStorageImpl.kt` `LayeredStorageImpl`; `docs/design-storage.md` LayeredStorageImpl
- **Reference:** none

### Frozen layer
- **Definition:** The immutable layer of a Layered Storage, holding the entities and properties merged at the last Freeze, independent of the physical identifier layout.
- **Example:** A Layered Storage that added nodes `a` and `b` and then froze → the layer holding `a` and `b`.
- **Source:** `docs/design-storage-access.md` Typed adjacency and frozen arrays; `storage/FrozenLayer.kt` `FrozenLayer`
- **Reference:** none

### Active layer
- **Definition:** The mutable layer of a Layered Storage that receives every write, records edits and deletions of frozen entities, and lets a read fall through to the Frozen layer for what it does not hold.
- **Example:** A frozen node `a` whose property `p` is set after the freeze → the Active layer holds the edit of `p`; a read of another property of `a` falls through to the Frozen layer.
- **Source:** `storage/ActiveLayer.kt` `ActiveLayer`; `storage/LayeredStorageImpl.kt` `clearActiveLayer`
- **Reference:** none

### Freeze
- **Definition:** The operation of a Layered Storage that merges the Frozen layer and the Active layer into a new Frozen layer and resets the Active layer, so that exactly one Frozen layer remains.
- **Example:** An Active layer holding node `c` over a Frozen layer holding `a` → a new Frozen layer holding `a` and `c`, and an empty Active layer.
- **Source:** `storage/LayeredStorageImpl.kt` `freeze`
- **Reference:** none

## Structural Access

### Tag group
- **Definition:** The string a storage's constructor-fixed pure function assigns to an immutable complete edge tag for directional adjacency selection.
- **Example:** `tagGroup = { it.substringBefore('@') }` maps `write@1` and `write@2` to `write`.
- **Source:** `storage/IStorage.kt` `tagGroup`; `docs/design-storage.md` IStorage

### Read scope
- **Definition:** The guarded lifetime during which a reader can borrow one consistent Storage view.
- **Example:** `reader.read(action)` → the action may consume a borrowed cell reader; that reader expires when the action returns.
- **Source:** `docs/design-storage.md` Typed storage capabilities

### Compressed sparse row (CSR)
- **Definition:** An adjacency representation grouping a node's incident edge identifiers into contiguous ranges addressed by offsets.
- **Example:** Offsets `[0,2,2]` and edges `[e1,e2]` → the first node has two selected edges and the second has none.
- **Source:** `docs/design-storage-access.md` Typed adjacency and frozen arrays
- **Literature:** [@graphflow2021, sec. 4.1.1] — borrowed
