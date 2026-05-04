# commons-graph — Tasks

---

## [x] Refactor: Replace NumVal with IntVal/FloatVal — DONE

All `NumVal`/`.numVal` replaced with `IntVal`/`FloatVal` in TraitGroup source and 10 test files.

---

## [x] Refactor: Migrate TraitPoset to PosetTrait/PosetDftImpl — DONE

`TraitPoset` mixin replaced with `PosetTrait` interface + `PosetDftImpl` composition. `PosetState` internalized into `PosetDftImpl`. Graph classes now hold `override val poset: IPoset = PosetDftImpl(storage)`.

---

## [ ] Delete TraitGroup
  Acceptance: `TraitGroup.kt` deleted; zero references in commons-graph
  - [ ] Delete `TraitGroup.kt`
  - [ ] Delete `TraitGroupTest.kt` and `AbcTraitGroupTest.kt`
  - [ ] Remove `design-group.md` or replace with historical note
  - [ ] Update `index.md` and `README.md`
  - [ ] Quality gate

Note: no replacement needed in commons-graph. ID generation and domain indexing are consumer responsibilities. CobraPHP graphs implement their own ID strategy internally (see CobraPHP `docs/graphs/design.md` — Node ID Generation).
