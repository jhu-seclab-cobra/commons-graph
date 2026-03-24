package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.value.StrVal

typealias LabelID = String

/**
 * Value object representing a label in the partial-order structure (poset).
 *
 * A label's ordering is not intrinsic — it is defined by the graph's lattice structure.
 * [INFIMUM] and [SUPREMUM] are special sentinel bounds and should not be assigned to edges.
 *
 * @property core The core string representation of the label.
 */
@JvmInline
value class Label(
    val core: LabelID,
) {
    constructor(strVal: StrVal) : this(strVal.core)

    companion object {
        /** Greatest Lower Bound — below all labels in the poset. */
        val INFIMUM: Label = Label(Int.MIN_VALUE.toString())

        /** Least Upper Bound — above all labels in the poset. */
        val SUPREMUM: Label = Label(Int.MAX_VALUE.toString())
    }
}
