# Known Bugs and Technical Debt

## Open Bugs

None.

---

## Technical Debt

### D1. AbcMultipleGraph god object (570 lines, 25+ public methods)

Implements `IGraph` and `Flushable` directly; `IPoset` mixed in via `PosetTrait`. Still exceeds detekt 600-line class limit. Split into: graph CRUD core, traversal mixin.

### D2. LayeredStorageImpl file length (695 lines)

Contains inline storage, layer management, freeze logic, and 5 inner view classes. Exceeds detekt limits. Extract inner classes to top-level internal classes.
