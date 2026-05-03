package edu.jhu.cobra.commons.graph.poset

/**
 * Contract for a label partial-order set (poset).
 *
 * Labels form a hierarchy through parent relationships. Ordering is determined
 * by ancestry: [compare] returns positive when [a] is an ancestor of [b],
 * negative when [a] is a descendant, zero when equal, null when incomparable.
 *
 * [Label.INFIMUM] and [Label.SUPREMUM] are structural bounds and should not be assigned to edges.
 *
 * @see PosetDftImpl
 */
interface IPoset {

    /** All labels registered in the poset, including [Label.INFIMUM] and [Label.SUPREMUM]. */
    val allLabels: Set<Label>

    /** Named parent labels forming the basis of a label's position in the poset. */
    fun getParents(label: Label): Map<String, Label>

    /** Sets the named parent labels for [label], replacing any previous parents. */
    fun setParents(label: Label, parents: Map<String, Label>)

    /** All ancestor labels traversing upwards through the parent hierarchy. */
    fun getAncestors(label: Label): Sequence<Label>

    /**
     * Compares two labels in the poset hierarchy.
     *
     * @return Positive if [a] > [b], negative if [a] < [b], 0 if equal, null if incomparable.
     */
    fun compare(a: Label, b: Label): Int?
}
