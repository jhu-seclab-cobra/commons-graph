package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.value.StrVal

public typealias LabelID = String

/**
 * Value object representing a label in the partial-order structure (poset).
 *
 * A label's ordering is not intrinsic — it is defined by the poset structure.
 * [INFIMUM] and [SUPREMUM] are structural bounds and are never assigned to edges.
 *
 * @property core The core string representation of the label.
 */
@JvmInline
public value class Label(
    public val core: LabelID,
) {
    public constructor(strVal: StrVal) : this(strVal.core)

    public companion object {
        /** Greatest Lower Bound — below all labels in the poset. */
        public val INFIMUM: Label = Label(Int.MIN_VALUE.toString())

        /** Least Upper Bound — above all labels in the poset. */
        public val SUPREMUM: Label = Label(Int.MAX_VALUE.toString())
    }
}
