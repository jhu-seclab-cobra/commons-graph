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
- **Definition:** The immutable layer of a Layered Storage, holding the entities and properties merged at the last freeze under its own local identifiers.
- **Example:** A Layered Storage that added nodes `a` and `b` and then froze → the layer holding `a` and `b`.
- **Source:** `storage/FrozenLayer.kt` `FrozenLayer`
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
