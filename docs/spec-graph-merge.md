# Merge Law Specifications

Rules: `model.md` Merge Laws. Declarations: `design-graph.md` GraphMergeLaws, `design-label.md` Label merge. Every algorithm here is pure and takes the base value, each writer's before-image and after-image, and nothing else.

## Label Merge

### Problem

Given the base label set and the label changes of several writers, find the merged label set, or the label whose changes oppose. Correct when each writer's change is read against its own before-image and the result does not depend on writer order.

### Steps

1. For each writer, its additions are `after - before`; its removals are `before - after`.
2. Collect the union of all additions and the union of all removals.
3. If a label is in both unions, fail the whole set.
4. Return `(base + additions) - removals`.

### Invariants

- A label no writer changes keeps its base state, whatever the writers' before-images held.
- Two writers adding one label, or removing one label, give the same result as one writer.

### Termination

One pass over the writers. Time O(W * L) for W writers and L labels per image.

## Owner Union

### Problem

Given the base owner set and the owner sets of several writers, find the merged owner set. Correct when every owner a writer added is present and the result is equal in either order.

### Steps

1. For each writer, its added owners are `after - before`.
2. If no writer added an owner, return the base unchanged (an absent base stays absent).
3. Otherwise return the base owners plus the union of the added owners.

### Invariants

- The result contains the base owners; no writer removes an owner.
- A union is commutative and idempotent, so the writer order and repeated writers do not change the result.

### Termination

One pass over the writers. Time O(W * S) for owner sets of size S.

## Property Merge

### Problem

Given the base value of one property and the changes of several writers, find the merged value, or the opposing writes. A change is a writer whose after-image differs from its before-image; null is an absent key.

### Steps — default law

1. Keep the changing writers. If none, return the base.
2. If their after-images are not all equal, fail the whole set.
3. Let `v` be the common after-image. If the base equals `v`, or every changing writer's before-image equals the base, return `v`.
4. Otherwise the base moved away from a writer's before-image to another value: fail.

### Steps — immutable law

1. If the base is absent, apply the default law.
2. Otherwise if a changing writer's after-image differs from the base, fail; else return the base.

### Invariants

- Equal writes are idempotent. No result depends on writer order. A writer's stale before-image never overwrites a base value that differs from what it wrote.

### Termination

One pass over the writers. Time O(W).

## Edge Coalescing

### Problem

Given prospective edge additions, already translated to final node IDs and tags, and the edges present, find the distinct edges to add or the shared declared key. Correct when edges with one `(src, dst, tag)` triple coalesce and no two distinct edges share the components of one declared unique key.

### Steps

1. Collect the additions into a set of triples.
2. Index the declared keys of the present edges: declaration name and components to the first edge holding them.
3. For each coalesced edge and each of its declared keys, look the key up. If another edge holds it, fail the whole set; else record the edge as holder.
4. Return the coalesced set.

### Invariants

- An addition equal to a present edge never conflicts with it.
- The result set does not depend on the order of the additions; a failure does not depend on it either.

### Termination

One pass over the present edges and one over the coalesced edges. Time O(P + A * K) for P present edges, A additions and K declarations.
