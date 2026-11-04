# Glossary — Storage

Layered storage: the composition of an immutable frozen layer and a mutable active layer behind one storage contract.

---

## Layered Storage

### Layered Storage
- **Definition:** A storage that composes one immutable frozen layer and one mutable active layer into a single storage view, so that the data of a completed phase of an analysis becomes read-only.
- **Example:** A `LayeredStorageImpl` after one `freeze` → one frozen layer holding every entity added so far and an empty active layer.
- **Source:** `storage/LayeredStorageImpl.kt` `LayeredStorageImpl`; `docs/design-storage.md` LayeredStorageImpl
- **Reference:** none

### Frozen layer
- **Definition:** The immutable layer of a layered storage, holding the entities and properties merged at the last freeze under its own local identifiers.
- **Example:** A layered storage that added nodes `a` and `b` and then froze → the layer holding `a` and `b`.
- **Source:** `storage/FrozenLayer.kt` `FrozenLayer`
- **Reference:** none

### Active layer
- **Definition:** The mutable layer of a layered storage that receives every write, records edits and deletions of frozen entities, and lets a read fall through to the frozen layer for what it does not hold.
- **Example:** A frozen node `a` whose property `p` is set after the freeze → the active layer holds the edit of `p`; a read of another property of `a` falls through to the frozen layer.
- **Source:** `storage/ActiveLayer.kt` `ActiveLayer`; `storage/LayeredStorageImpl.kt` `clearActiveLayer`
- **Reference:** none

### Freeze
- **Definition:** The operation of a layered storage that merges the frozen layer and the active layer into a new frozen layer and resets the active layer, so that exactly one frozen layer remains.
- **Example:** An active layer holding node `c` over a frozen layer holding `a` → a new frozen layer holding `a` and `c`, and an empty active layer.
- **Source:** `storage/LayeredStorageImpl.kt` `freeze`
- **Reference:** none
