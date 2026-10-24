package edu.jhu.cobra.commons.graph.storage

/**
 * Storage IDs of nodes and edges, in the namespace of the storage that names them.
 *
 * @property nodes Node IDs.
 * @property edges Edge IDs.
 */
public data class StorageEntityIds(
    public val nodes: Set<Int>,
    public val edges: Set<Int>,
)

/**
 * What one revert changed. Every set and map is a snapshot in the notifying storage's ID namespace.
 *
 * @property removed IDs visible before the revert and absent after it.
 * @property restored IDs visible after the revert whose presence or stored image the revert exposed again by
 *   dropping the reverted writes, including a promoted row whose dropped write happened to leave the same value.
 *   It is disjoint from [removed] for each entity kind.
 * @property edgeStructuresBefore The source, destination and tag, read before the revert, of every affected edge
 *   visible before it: the removed edges and the restored edges visible before. A restored edge missing from the
 *   map has reappeared; one present in it survived and had its stored image restored.
 */
public data class RevertNotice(
    public val removed: StorageEntityIds,
    public val restored: StorageEntityIds,
    public val edgeStructuresBefore: Map<Int, IStorage.EdgeStructure>,
)

/**
 * Receives one revert notification from an [IRevertibleStorage].
 *
 * Invoked once for every successful revert, empty ones included, inside the storage's [criticalSection]
 * after the storage state and the ID translation are final. A listener may read the storage and never writes it.
 */
public fun interface RevertListener {
    public fun onRevert(notice: RevertNotice)
}

/**
 * An [IStorage] that removes entities, or exposes dropped entities and property images again, without a
 * [deleteNode] or [deleteEdge] call (a revert). Consumers holding state derived from storage IDs subscribe to
 * learn which IDs a revert removed or restored.
 */
public interface IRevertibleStorage : IStorage {
    /** Registers [listener] for every later revert; the storage holds it for its own lifetime. */
    public fun addRevertListener(listener: RevertListener)
}
