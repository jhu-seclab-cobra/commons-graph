package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.FrozenLayerModificationException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.IValue

/**
 * Multi-layer freeze-and-stack storage for phased analysis pipelines.
 *
 * Composes at most one frozen layer (read-only) + one mutable active layer.
 * Each [freeze] merges all existing data (frozen + active) into a single frozen
 * layer and creates a fresh empty active layer, keeping query depth at O(1).
 *
 * Query resolution:
 * - Properties: active layer first, then frozen layer (overlay semantics).
 *   Returns lazy merged views instead of copying into new HashMaps.
 * - Adjacency: returns union set views merging both layers without allocation,
 *   with fast paths when one side is empty.
 *
 * Deletion is restricted to the active layer. Attempting to delete a frozen-layer
 * entity throws [FrozenLayerModificationException].
 *
 * @param frozenLayerFactory Factory for creating storage instances used as frozen layers.
 *   Defaults to [NativeStorageImpl]. Inject a MapDB factory for off-heap frozen storage.
 *
 * @see FrozenLayerModificationException
 */
class LayeredStorageImpl(
    private val frozenLayerFactory: () -> IStorage = { NativeStorageImpl() },
) : IStorage {
    private val frozenLayers = mutableListOf<IStorage>()
    private var activeLayer: IStorage = NativeStorageImpl()
    private var closed = false

    // At most 1 frozen layer after merge-on-freeze (B5-B)
    private val frozenLayer: IStorage? get() = frozenLayers.firstOrNull()

    // ============================================================================
    // LAYERED STORAGE API
    // ============================================================================

    /**
     * Total number of layers (frozen layers + active layer).
     *
     * With merge-on-freeze, this is always 1 (no frozen) or 2 (one frozen + active).
     */
    val layerCount: Int get() = frozenLayers.size + 1

    /**
     * Freezes the current active layer by merging all data into a single frozen layer.
     *
     * Merges existing frozen layer data and active layer data into a new frozen layer
     * created by [frozenLayerFactory], closes old layers, and creates a fresh empty
     * active layer. After freeze, there is always exactly one frozen layer.
     */
    fun freeze() {
        ensureOpen()
        val merged = frozenLayerFactory()
        for (layer in frozenLayers) {
            mergeLayerInto(layer, merged)
            layer.close()
        }
        mergeLayerInto(activeLayer, merged)
        activeLayer.close()
        frozenLayers.clear()
        frozenLayers.add(merged)
        activeLayer = NativeStorageImpl()
    }

    /**
     * Merges the top [topN] frozen layers into a single layer.
     *
     * With merge-on-freeze, there is at most one frozen layer, so this is
     * effectively a no-op when [topN] is 1.
     *
     * @param topN Number of topmost frozen layers to compact.
     * @throws IllegalArgumentException if [topN] is out of range.
     */
    fun compact(topN: Int) {
        ensureOpen()
        require(topN in 1..frozenLayers.size) {
            "topN=$topN out of range [1, ${frozenLayers.size}]"
        }
        if (topN == 1) return
        val startIdx = frozenLayers.size - topN
        val layersToCompact = frozenLayers.subList(startIdx, frozenLayers.size)
        val compacted = frozenLayerFactory()
        for (layer in layersToCompact) {
            mergeLayerInto(layer, compacted)
            layer.close()
        }
        layersToCompact.clear()
        frozenLayers.add(compacted)
    }

    private fun mergeLayerInto(
        source: IStorage,
        target: IStorage,
    ) {
        for (nodeId in source.nodeIDs) {
            if (target.containsNode(nodeId)) {
                val props = source.getNodeProperties(nodeId)
                if (props.isNotEmpty()) {
                    target.setNodeProperties(nodeId, props)
                }
            } else {
                target.addNode(nodeId, source.getNodeProperties(nodeId))
            }
        }
        for (edgeId in source.edgeIDs) {
            if (target.containsEdge(edgeId)) {
                val props = source.getEdgeProperties(edgeId)
                if (props.isNotEmpty()) {
                    target.setEdgeProperties(edgeId, props)
                }
            } else {
                target.addEdge(edgeId, source.getEdgeProperties(edgeId))
            }
        }
        for (name in source.metaNames) {
            target.setMeta(name, source.getMeta(name))
        }
    }

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() {
            ensureOpen()
            val frozen = frozenLayer ?: return activeLayer.nodeIDs
            val activeIds = activeLayer.nodeIDs
            if (activeIds.isEmpty()) return frozen.nodeIDs
            return UnionSet(frozen.nodeIDs, activeIds)
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            ensureOpen()
            val frozen = frozenLayer ?: return activeLayer.edgeIDs
            val activeIds = activeLayer.edgeIDs
            if (activeIds.isEmpty()) return frozen.edgeIDs
            return UnionSet(frozen.edgeIDs, activeIds)
        }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean {
        ensureOpen()
        if (activeLayer.containsNode(id)) return true
        return frozenLayer?.containsNode(id) ?: false
    }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) {
        ensureOpen()
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        activeLayer.addNode(id, properties)
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        ensureOpen()
        val frozen = frozenLayer
        val inActive = activeLayer.containsNode(id)
        val inFrozen = frozen?.containsNode(id) ?: false
        if (!inActive && !inFrozen) throw EntityNotExistException(id)
        if (!inFrozen) return activeLayer.getNodeProperties(id)
        if (!inActive) return frozen!!.getNodeProperties(id)
        return LazyMergedMap(frozen!!.getNodeProperties(id), activeLayer.getNodeProperties(id))
    }

    override fun getNodeProperty(
        id: NodeID,
        name: String,
    ): IValue? {
        ensureOpen()
        val frozen = frozenLayer
        val inActive = activeLayer.containsNode(id)
        val inFrozen = frozen?.containsNode(id) ?: false
        if (!inActive && !inFrozen) throw EntityNotExistException(id)
        if (inActive) {
            activeLayer.getNodeProperty(id, name)?.let { return it }
        }
        if (inFrozen) return frozen!!.getNodeProperty(id, name)
        return null
    }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val inActive = activeLayer.containsNode(id)
        if (!inActive && frozenLayer?.containsNode(id) != true) throw EntityNotExistException(id)
        if (!inActive) activeLayer.addNode(id)
        activeLayer.setNodeProperties(id, properties)
    }

    override fun deleteNode(id: NodeID) {
        ensureOpen()
        if (!activeLayer.containsNode(id)) {
            if (frozenLayer?.containsNode(id) == true) throw FrozenLayerModificationException(id)
            throw EntityNotExistException(id)
        }
        activeLayer.deleteNode(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean {
        ensureOpen()
        if (activeLayer.containsEdge(id)) return true
        return frozenLayer?.containsEdge(id) ?: false
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) {
        ensureOpen()
        if (activeLayer.containsEdge(id) || frozenLayer?.containsEdge(id) == true) {
            throw EntityAlreadyExistException(id)
        }
        ensureNodeInActiveLayer(id.srcNid)
        ensureNodeInActiveLayer(id.dstNid)
        activeLayer.addEdge(id, properties)
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        ensureOpen()
        val frozen = frozenLayer
        val inActive = activeLayer.containsEdge(id)
        val inFrozen = frozen?.containsEdge(id) ?: false
        if (!inActive && !inFrozen) throw EntityNotExistException(id)
        if (!inFrozen) return activeLayer.getEdgeProperties(id)
        if (!inActive) return frozen!!.getEdgeProperties(id)
        return LazyMergedMap(frozen!!.getEdgeProperties(id), activeLayer.getEdgeProperties(id))
    }

    override fun getEdgeProperty(
        id: EdgeID,
        name: String,
    ): IValue? {
        ensureOpen()
        val frozen = frozenLayer
        val inActive = activeLayer.containsEdge(id)
        val inFrozen = frozen?.containsEdge(id) ?: false
        if (!inActive && !inFrozen) throw EntityNotExistException(id)
        if (inActive) {
            activeLayer.getEdgeProperty(id, name)?.let { return it }
        }
        if (inFrozen) return frozen!!.getEdgeProperty(id, name)
        return null
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val inActive = activeLayer.containsEdge(id)
        if (!inActive && frozenLayer?.containsEdge(id) != true) throw EntityNotExistException(id)
        if (!inActive) {
            ensureNodeInActiveLayer(id.srcNid)
            ensureNodeInActiveLayer(id.dstNid)
            activeLayer.addEdge(id)
        }
        activeLayer.setEdgeProperties(id, properties)
    }

    override fun deleteEdge(id: EdgeID) {
        ensureOpen()
        if (!activeLayer.containsEdge(id)) {
            if (frozenLayer?.containsEdge(id) == true) throw FrozenLayerModificationException(id)
            throw EntityNotExistException(id)
        }
        activeLayer.deleteEdge(id)
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        ensureOpen()
        val frozen = frozenLayer
        val inActive = activeLayer.containsNode(id)
        val inFrozen = frozen?.containsNode(id) ?: false
        if (!inActive && !inFrozen) throw EntityNotExistException(id)
        val frozenEdges = if (inFrozen) frozen!!.getIncomingEdges(id) else emptySet()
        val activeEdges = if (inActive) activeLayer.getIncomingEdges(id) else emptySet()
        if (activeEdges.isEmpty()) return frozenEdges
        if (frozenEdges.isEmpty()) return activeEdges
        return UnionSet(frozenEdges, activeEdges)
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        ensureOpen()
        val frozen = frozenLayer
        val inActive = activeLayer.containsNode(id)
        val inFrozen = frozen?.containsNode(id) ?: false
        if (!inActive && !inFrozen) throw EntityNotExistException(id)
        val frozenEdges = if (inFrozen) frozen!!.getOutgoingEdges(id) else emptySet()
        val activeEdges = if (inActive) activeLayer.getOutgoingEdges(id) else emptySet()
        if (activeEdges.isEmpty()) return frozenEdges
        if (frozenEdges.isEmpty()) return activeEdges
        return UnionSet(frozenEdges, activeEdges)
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() {
            ensureOpen()
            val frozen = frozenLayer ?: return activeLayer.metaNames
            val activeNames = activeLayer.metaNames
            if (activeNames.isEmpty()) return frozen.metaNames
            return UnionSet(frozen.metaNames, activeNames)
        }

    override fun getMeta(name: String): IValue? {
        ensureOpen()
        activeLayer.getMeta(name)?.let { return it }
        return frozenLayer?.getMeta(name)
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        ensureOpen()
        activeLayer.setMeta(name, value)
    }

    // ============================================================================
    // UTILITY OPERATIONS
    // ============================================================================

    override fun clear(): Boolean {
        ensureOpen()
        for (layer in frozenLayers) layer.close()
        frozenLayers.clear()
        activeLayer.clear()
        return true
    }

    override fun close() {
        if (closed) return
        for (layer in frozenLayers) layer.close()
        frozenLayers.clear()
        activeLayer.close()
        closed = true
    }

    // ============================================================================
    // GUARDS
    // ============================================================================

    private fun ensureOpen() {
        if (closed) throw AccessClosedStorageException()
    }

    // Ensures a node exists in the active layer; promotes from frozen if needed
    private fun ensureNodeInActiveLayer(id: NodeID) {
        if (activeLayer.containsNode(id)) return
        if (frozenLayer?.containsNode(id) != true) throw EntityNotExistException(id)
        activeLayer.addNode(id)
    }

    // ============================================================================
    // INTERNAL VIEW TYPES
    // ============================================================================

    // Overlays [overlay] on [base] without copying; single-key get() is O(1)
    private class LazyMergedMap(
        private val base: Map<String, IValue>,
        private val overlay: Map<String, IValue>,
    ) : AbstractMap<String, IValue>() {
        override val entries: Set<Map.Entry<String, IValue>>
            get() {
                val result = LinkedHashMap<String, IValue>(base.size + overlay.size)
                result.putAll(base)
                result.putAll(overlay)
                return result.entries
            }

        override fun get(key: String): IValue? = overlay[key] ?: base[key]

        override fun containsKey(key: String): Boolean = overlay.containsKey(key) || base.containsKey(key)

        override val size: Int
            get() {
                if (overlay.isEmpty()) return base.size
                if (base.isEmpty()) return overlay.size
                var count = overlay.size
                for (key in base.keys) {
                    if (key !in overlay) count++
                }
                return count
            }

        override fun isEmpty(): Boolean = base.isEmpty() && overlay.isEmpty()
    }

    // Combines [first] and [second] without copying; deduplicates on iteration
    private class UnionSet<E>(
        private val first: Set<E>,
        private val second: Set<E>,
    ) : AbstractSet<E>() {
        override val size: Int
            get() {
                if (second.isEmpty()) return first.size
                if (first.isEmpty()) return second.size
                var count = first.size
                for (e in second) {
                    if (e !in first) count++
                }
                return count
            }

        override fun iterator(): Iterator<E> =
            iterator {
                yieldAll(first)
                for (e in second) {
                    if (e !in first) yield(e)
                }
            }

        override fun contains(element: E): Boolean = first.contains(element) || second.contains(element)

        override fun isEmpty(): Boolean = first.isEmpty() && second.isEmpty()
    }
}
