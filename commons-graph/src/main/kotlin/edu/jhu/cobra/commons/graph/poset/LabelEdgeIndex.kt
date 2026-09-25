package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcEdge
import java.util.concurrent.ConcurrentHashMap

/**
 * Inverted index from a label to the edges of one graph that may carry it.
 *
 * Each label maps to a candidate set that is a superset of the label's current
 * carriers: a label removal never removes a candidate, so a storage revert that
 * restores a removed label (a cleared layered-storage active layer) keeps the
 * index complete without invalidation. Readers verify candidates against storage.
 * The algorithm and its preconditions are specified in spec-poset.md (Label
 * Carrier Index).
 *
 * Owned by one graph instance and mutated only by [PosetTrait]; every member is
 * internal. Thread-safe: candidate sets are concurrent, and the initial scan
 * runs once under this index's monitor.
 *
 * @param E The edge type.
 */
public class LabelEdgeIndex<E : AbcEdge> {
    private val candidateSets = ConcurrentHashMap<Label, MutableSet<E>>()

    @Volatile
    private var built = false

    /** Records every label of every edge in [allEdges] on the first call; later calls return at once. */
    internal fun ensureBuilt(allEdges: () -> Sequence<E>) {
        if (built) return
        synchronized(this) {
            if (built) return
            for (edge in allEdges()) {
                for (label in edge.labels) record(label, edge)
            }
            built = true
        }
    }

    internal fun record(
        label: Label,
        edge: E,
    ) {
        candidateSets.computeIfAbsent(label) { ConcurrentHashMap.newKeySet() }.add(edge)
    }

    /** Snapshot of [label]'s candidates, safe to iterate while the index changes. */
    internal fun candidates(label: Label): List<E> = candidateSets[label]?.toList().orEmpty()

    /** Removes [edge] from [label]'s candidates; only for an edge that no longer exists. */
    internal fun drop(
        label: Label,
        edge: E,
    ) {
        candidateSets[label]?.remove(edge)
    }
}
