package edu.jhu.cobra.commons.graph.storage

/**
 * Receives one revert notification from an [IRevertibleStorage].
 *
 * Invoked inside the storage's [criticalSection] after the storage state is final.
 * A listener does not call back into the storage.
 */
public fun interface RevertListener {
    /**
     * @param removedNodes Node IDs that existed before the revert and do not exist after it.
     * @param removedEdges Edge IDs that existed before the revert and do not exist after it.
     */
    public fun onRevert(
        removedNodes: Set<Int>,
        removedEdges: Set<Int>,
    )
}

/**
 * An [IStorage] that removes entities without a [deleteNode] or [deleteEdge] call (a revert).
 * Consumers holding caches keyed by storage IDs subscribe to learn which IDs a revert removed.
 */
public interface IRevertibleStorage : IStorage {
    /** Registers [listener] for every later revert; the storage holds it for its own lifetime. */
    public fun addRevertListener(listener: RevertListener)
}
