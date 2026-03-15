package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.InternalID

/**
 * Contract for a label partial-order set (poset) controlling edge visibility.
 *
 * Labels form a hierarchy through parent relationships. Visibility is determined
 * by comparing labels: an edge is visitable under label `by` if at least one of
 * its labels `l` satisfies `by == l` or `by > l` in the hierarchy.
 *
 * [Label.INFIMUM] and [Label.SUPREMUM] are structural bounds and should not be assigned to edges.
 *
 * @see Label
 */
interface IPoset {
    /** All labels registered in the poset, including [Label.INFIMUM] and [Label.SUPREMUM]. */
    val allLabels: Set<Label>

    /** Named parent labels forming the basis of a label's position in the poset. */
    var Label.parents: Map<String, Label>

    /** All ancestor labels traversing upwards through the parent hierarchy. */
    val Label.ancestors: Sequence<Label>

    /** Edge IDs whose label set was modified involving this label. */
    var Label.changes: Set<InternalID>

    /**
     * Compares this label with another in the poset hierarchy.
     *
     * @param other The label to compare against.
     * @return Positive if this > other, negative if this < other, 0 if equal, null if incomparable.
     */
    fun Label.compareTo(other: Label): Int?
}
