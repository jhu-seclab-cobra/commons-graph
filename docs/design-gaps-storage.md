[Storage design](design-storage.md) owns these open questions.

# Storage Open Gaps

- Does the per-entity `RowProperties` name scan need a different representation for rows with many properties? A row read and removal visit only that row; a named read scans its names, and a one-property row still allocates its row array.
- Which measured callers require a different `GraphEntityCache` wrapper lifetime? The current cache uses SoftReference, so replacing it needs retained-heap and allocation evidence.
- Can `AbcJgraphtStorage.deleteNode` collect property/mapping cleanup once while `removeVertex` performs topology deletion, instead of traversing incident edges twice?
- Which caller needs a different JGraphT property-map return contract? Current `getNodeProperties` and `getEdgeProperties` copy their outer maps; a live view would change the collection lifetime contract.
- Do boxed integer keys in JGraphT structural maps justify primitive-keyed maps on a measured workload?
- Would a MapDB per-edge composite key avoid full identity-set serialization without changing reopen and export contracts?
- Would MapDB native primitive serializers or a whole-row property value improve a measured workload enough to justify different collection layouts?
- How much does MapDB `concurrencyDisable()` change a fixed workload’s cost after serialization is included?
- Can Neo4j `transferTo` use a bounded transaction scope while preserving its error and resource-lifetime contracts?
- Which Neo4j properties can use native values without changing the `IValue` serialization contract?
- Can `Neo4jEntityProperties.keys` remove its distinct operation while preserving reserved-name filtering?
- Which profile evidence attributes time to locking, serialization, allocation or transaction commit in each backend? Retained benchmark throughput alone does not establish those causes.
