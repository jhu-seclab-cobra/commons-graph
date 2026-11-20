package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.value.StrVal

public typealias LabelID = String

/**
 * Value object representing a label in the partial-order structure (poset).
 *
 * A label's ordering is not intrinsic — it is defined by the poset structure.
 * [INFIMUM] and [SUPREMUM] are structural bounds. An edge carrying [INFIMUM] is visible under
 * every label; [SUPREMUM] is never assigned to edges.
 *
 * A label is its canonical text, the `core` of a [StrVal]: two texts that stand for the same bytes
 * are one label.
 *
 * @param strVal The label text, whose `core` is canonical.
 */
@JvmInline
public value class Label(
    private val strVal: StrVal,
) {
    /** The label whose text is the canonical text of [core]. */
    public constructor(core: LabelID) : this(StrVal(core))

    /** The canonical text of the label. */
    public val core: LabelID get() = strVal.core

    override fun toString(): String = "Label(core=$core)"

    public companion object {
        /** Greatest Lower Bound — below all labels in the poset. */
        public val INFIMUM: Label = Label(Int.MIN_VALUE.toString())

        /** Least Upper Bound — above all labels in the poset. */
        public val SUPREMUM: Label = Label(Int.MAX_VALUE.toString())
    }
}
