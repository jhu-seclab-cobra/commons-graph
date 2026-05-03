# commons-graph — Tasks

---

## [ ] Refactor: Remove TraitGroup
  Acceptance: `TraitGroup.kt` deleted; zero references; all domain modules use auto-ID from base graph classes

Replace `TraitGroup` trait with auto-ID generation in base graph classes. Move suffix-based indexing to domain modules. See `design-group.md` for current API surface.

### [ ] 1.1 Add auto-ID to base graph classes
  Acceptance: `AbcMultipleGraph` and `AbcSimpleGraph` support `addNode()` with auto-generated ID; `graphId` used as prefix
  - [ ] Add `private var nodeCounter: Int` to `AbcMultipleGraph`
  - [ ] Add `fun addNode(): N` that generates `"{graphId}_{++nodeCounter}"` and delegates to `addNode(withID)`
  - [ ] Persist counter to storage meta on increment; restore on `rebuild()`
  - [ ] Same for `AbcSimpleGraph`
  - [ ] Unit tests for auto-ID generation and persistence
  - [ ] `./gradlew build` — compiles clean
  - [ ] `./gradlew test` — all pass

### [ ] 1.2 Migrate domain modules to auto-ID
  Depends on: 1.1
  Acceptance: AST, ADG, PDG, CCG no longer implement `TraitGroup`; all `addGroupNode` calls replaced
  - [ ] Each graph class: remove `TraitGroup` from supertypes, remove `groupedNodesCounter`, `suffixIndex`, `groupPrefix` overrides
  - [ ] Replace `addGroupNode(group)` with `addNode()` + set group property
  - [ ] Replace `addGroupNode(group, suffix)` with domain-specific index + `addNode()`
  - [ ] Replace `getGroupNode(group, suffix)` with domain-specific index lookup
  - [ ] Replace `registerGroup` / `putIfAbsent` calls with nothing (auto-registration)
  - [ ] `./gradlew build` — compiles clean
  - [ ] `./gradlew test` — all pass

### [ ] 1.3 Delete TraitGroup
  Depends on: 1.2
  Acceptance: `TraitGroup.kt` deleted; zero references in codebase
  - [ ] Delete `TraitGroup.kt`
  - [ ] Delete `TraitGroupTest.kt` and `AbcTraitGroupTest.kt`
  - [ ] Remove `design-group.md` or replace with auto-ID documentation
  - [ ] Update `index.md` and `README.md`
  - [ ] `./gradlew build` — compiles clean
  - [ ] `./gradlew test` — all pass
