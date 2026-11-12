# Glossary — Merge operations

## Merge operations

### Property merge
- **Definition:** The operation that computes one property value from its base value and each writer’s before-image and after-image, or rejects changes that conflict under the selected law.
- **Example:** Base 1 and two changes from 1 to 2 under `EQUAL_OR_FAIL` → the merged value 2.
- **Source:** `PropertyMerge.kt` `PropertyMergeLaw`; `docs/model.md` Merge Laws

### Owner union
- **Definition:** The operation that adds to a base owner set every owner added by a writer relative to that writer’s before-image.
- **Example:** Base {a}, changes {a} to {a,b} and {a} to {a,c} → {a,b,c}.
- **Source:** `NodeOwners.kt` `LAW`; `docs/model.md` Merge Laws
