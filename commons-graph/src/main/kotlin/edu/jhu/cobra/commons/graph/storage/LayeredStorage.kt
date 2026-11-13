package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/** Combined compatibility access to a layered storage; capability clients use [StorageAccess]. */
public interface LayeredStorage : IRevertibleStorage, LayeredEdgeReads, ExactAdjacencyStorage, AutoCloseable {
    /** Number of physical layers, one before Freeze and two after Freeze. */
    public val layerCount: Int

    /** Merges visible content into one frozen layer under the storage guard. */
    public fun freeze()

    /** Drops active edits and notifies revert listeners under the storage guard. */
    public fun clearActiveLayer()

    /** Captures the selected layer's property row, or null when that layer holds no row. */
    public fun getEdgeProperties(id: Int, layer: StorageLayer): Map<String, IValue>?

    /** Releases the owned data; callers release every graph sharing this storage. */
    override fun close(): Unit = clear()
}
