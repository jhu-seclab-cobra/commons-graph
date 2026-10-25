package edu.jhu.cobra.commons.graph.storage

/**
 * A layer of a [LayeredStorageImpl] that a layer-specific read addresses.
 *
 * The reads of [IStorage] resolve over both layers ([VISIBLE]); a layer-specific read returns only what that
 * one layer holds, so a caller can compare the revertible active rows with the frozen rows they shadow.
 */
public enum class StorageLayer {
    /** The ordinary view: the active row if the active layer holds the edge, else the frozen row; null if absent. */
    VISIBLE,

    /** The active layer's own rows: null when the edge was not added or written since the last freeze or clear. */
    ACTIVE,

    /** The frozen layer's rows as of the last freeze: tombstones in the active layer do not hide them. */
    FROZEN,
}
