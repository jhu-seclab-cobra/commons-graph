package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcEdge

/**
 * Inverted index from a label to the edges of one graph that may carry it.
 *
 * Each label maps to a candidate set that is a superset of the label's current
 * carriers: a label removal never removes a candidate, and a candidate leaves only
 * when a reader finds its edge absent from storage. A storage revert can expose an
 * absent edge again, or restore the labels of a surviving one, so the graph re-records
 * the final labels of every edge the revert notice names ([restoreEdges]). Readers
 * verify candidates against storage. Candidates are keyed by edge storage ID: an edge
 * deleted and re-added under the same `(src, dst, tag)` is a separate candidate. The
 * algorithm and its preconditions are specified in spec-poset.md (Label Carrier Index).
 *
 * Owned by one graph instance and mutated only by [PosetTrait]; every member is
 * internal. Not synchronized: [PosetTrait] calls every member inside the owning
 * graph's storage critical section.
 *
 * @param E The edge type.
 */
public class LabelEdgeIndex<E : AbcEdge> {
    private val candidateSets = HashMap<Label, MutableMap<Int, E>>()

    private var built = false

    /** Records every label of every edge in [allEdges] on the first call; later calls return at once. */
    internal fun ensureBuilt(allEdges: () -> Sequence<E>) {
        if (built) return
        for (edge in allEdges()) {
            for (label in edge.labels) record(label, edge)
        }
        built = true
    }

    /** Adds [edge] to [label]'s candidates, replacing an entry under the same storage ID. */
    internal fun record(
        label: Label,
        edge: E,
    ) {
        candidateSets.getOrPut(label) { HashMap() }[edge.storageId] = edge
    }

    /**
     * Records every current label of each edge that [resolve] returns for an ID of [ids]; an ID it resolves
     * to null (an edge outside the graph) is skipped. An index not yet built stays unbuilt, so its first
     * scan reads the final storage.
     */
    internal fun restoreEdges(
        ids: Set<Int>,
        resolve: (Int) -> E?,
    ) {
        if (!built) return
        for (edge in ids.mapNotNull(resolve)) {
            for (label in edge.labels) record(label, edge)
        }
    }

    /** Snapshot of [label]'s candidates, safe to iterate while the index changes. */
    internal fun candidates(label: Label): List<E> = candidateSets[label]?.values?.toList().orEmpty()

    /** Removes [edge] from [label]'s candidates; only for an edge that no longer exists. */
    internal fun drop(
        label: Label,
        edge: E,
    ) {
        candidateSets[label]?.remove(edge.storageId)
    }
}
