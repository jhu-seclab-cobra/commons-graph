[design-label](design-label.md) owns these open questions.

# Open Gaps

- **Reparenting a label with descendants discards the closure.**
  `PosetDftImpl.setParents` keeps the ancestor closure when the parents are
  unchanged and extends it by one row for a label without child labels
  ([spec-poset.md](spec-poset.md) Steps — Maintain closure). When the label has
  a child label, the whole closure is discarded and the next query rebuilds it
  in O(V · (V + E)). A workload that alternates such reparenting with queries
  rebuilds the closure once per reparenting; the spec's estimate of tens of
  labels is an assumption, not a bound the library enforces. Open question:
  whether to update the rows of the label's descendants in place.
