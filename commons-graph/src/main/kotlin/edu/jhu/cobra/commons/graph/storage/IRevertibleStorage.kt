package edu.jhu.cobra.commons.graph.storage

/**
 * Receives one revert notification from an [IRevertibleStorage].
 *
 * Invoked inside the storage's [criticalSection] after the storage state is final.
 * A listener may read the storage and never writes it.
 */
public fun interface RevertListener {
    /**
     * @param removedNodes Node IDs that existed before the revert and do not exist after it.
     * @param removedEdges Edge IDs that existed before the revert and do not exist after it.
     * @param restoredNodes Node IDs that exist before and after the revert, whose changes since the
     *   last freeze the revert dropped: their properties read as at that freeze again.
     */
    public fun onRevert(
        removedNodes: Set<Int>,
        removedEdges: Set<Int>,
        restoredNodes: Set<Int>,
    )
}

/**
 * An [IStorage] that removes entities without a [deleteNode] or [deleteEdge] call (a revert).
 * Consumers holding caches keyed by storage IDs subscribe to learn which IDs a revert removed or restored.
 */
public interface IRevertibleStorage : IStorage {
    /** Registers [listener] for every later revert; the storage holds it for its own lifetime. */
    public fun addRevertListener(listener: RevertListener)
}
