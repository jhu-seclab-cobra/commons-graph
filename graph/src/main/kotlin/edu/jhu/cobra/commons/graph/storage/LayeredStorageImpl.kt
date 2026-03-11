package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue

/**
 * Multi-layer freeze-and-stack storage for phased analysis pipelines.
 *
 * Composes N frozen layers (read-only) + one mutable active layer. Each
 * [freeze] transfers the active layer data into a new frozen layer
 * and creates a fresh empty active layer.
 *
 * Query resolution:
 * - Properties: active layer first, then frozen layers in reverse order (overlay semantics).
 * - Adjacency: merge results from all layers (edges are append-only across layers).
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

    // ============================================================================
    // LAYERED STORAGE API
    // ============================================================================

    /**
     * Total number of layers (frozen layers + active layer).
     */
    val layerCount: Int get() = frozenLayers.size + 1

    /**
     * Freezes the current active layer and pushes it onto the frozen stack.
     *
     * Transfers all active layer data into a new frozen layer created by [frozenLayerFactory],
     * closes the old active layer, and creates a fresh empty active layer.
     */
    fun freeze() {
        ensureOpen()
        val target = frozenLayerFactory()
        activeLayer.transferTo(target)
        activeLayer.close()
        frozenLayers.add(target)
        activeLayer = NativeStorageImpl()
    }

    /**
     * Merges the top [topN] frozen layers into a single layer.
     *
     * Reduces query chain length when many layers have accumulated.
     *
     * @param topN Number of topmost frozen layers to compact.
     * @throws IllegalArgumentException if [topN] is out of range.
     */
    fun compact(topN: Int) {
        ensureOpen()
        require(topN in 1..frozenLayers.size) {
            "topN=$topN out of range [1, ${frozenLayers.size}]"
        }
        val startIdx = frozenLayers.size - topN
        val layersToCompact = frozenLayers.subList(startIdx, frozenLayers.size)
        val compacted = frozenLayerFactory()
        for (layer in layersToCompact) {
            layer.transferTo(compacted)
            layer.close()
        }
        layersToCompact.clear()
        frozenLayers.add(compacted)
    }

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() {
            ensureOpen()
            val result = mutableSetOf<NodeID>()
            for (layer in frozenLayers) result.addAll(layer.nodeIDs)
            result.addAll(activeLayer.nodeIDs)
            return result
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            ensureOpen()
            val result = mutableSetOf<EdgeID>()
            for (layer in frozenLayers) result.addAll(layer.edgeIDs)
            result.addAll(activeLayer.edgeIDs)
            return result
        }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean {
        ensureOpen()
        if (activeLayer.containsNode(id)) return true
        return frozenLayers.asReversed().any { it.containsNode(id) }
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
        if (!containsNode(id)) throw EntityNotExistException(id)
        val merged = mutableMapOf<String, IValue>()
        for (layer in frozenLayers) {
            if (layer.containsNode(id)) merged.putAll(layer.getNodeProperties(id))
        }
        if (activeLayer.containsNode(id)) merged.putAll(activeLayer.getNodeProperties(id))
        return merged
    }

    override fun getNodeProperty(id: NodeID, name: String): IValue? {
        ensureOpen()
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (activeLayer.containsNode(id)) {
            activeLayer.getNodeProperty(id, name)?.let { return it }
        }
        for (layer in frozenLayers.asReversed()) {
            if (layer.containsNode(id)) {
                layer.getNodeProperty(id, name)?.let { return it }
            }
        }
        return null
    }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (!activeLayer.containsNode(id)) {
            activeLayer.addNode(id)
        }
        activeLayer.setNodeProperties(id, properties)
    }

    override fun deleteNode(id: NodeID) {
        ensureOpen()
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (!activeLayer.containsNode(id)) throw FrozenLayerModificationException(id)
        activeLayer.deleteNode(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean {
        ensureOpen()
        if (activeLayer.containsEdge(id)) return true
        return frozenLayers.asReversed().any { it.containsEdge(id) }
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) {
        ensureOpen()
        if (containsEdge(id)) throw EntityAlreadyExistException(id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        if (!activeLayer.containsNode(id.srcNid)) activeLayer.addNode(id.srcNid)
        if (!activeLayer.containsNode(id.dstNid)) activeLayer.addNode(id.dstNid)
        activeLayer.addEdge(id, properties)
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        ensureOpen()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val merged = mutableMapOf<String, IValue>()
        for (layer in frozenLayers) {
            if (layer.containsEdge(id)) merged.putAll(layer.getEdgeProperties(id))
        }
        if (activeLayer.containsEdge(id)) merged.putAll(activeLayer.getEdgeProperties(id))
        return merged
    }

    override fun getEdgeProperty(id: EdgeID, name: String): IValue? {
        ensureOpen()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        if (activeLayer.containsEdge(id)) {
            activeLayer.getEdgeProperty(id, name)?.let { return it }
        }
        for (layer in frozenLayers.asReversed()) {
            if (layer.containsEdge(id)) {
                layer.getEdgeProperty(id, name)?.let { return it }
            }
        }
        return null
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        if (!activeLayer.containsEdge(id)) {
            if (!activeLayer.containsNode(id.srcNid)) activeLayer.addNode(id.srcNid)
            if (!activeLayer.containsNode(id.dstNid)) activeLayer.addNode(id.dstNid)
            activeLayer.addEdge(id)
        }
        activeLayer.setEdgeProperties(id, properties)
    }

    override fun deleteEdge(id: EdgeID) {
        ensureOpen()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        if (!activeLayer.containsEdge(id)) throw FrozenLayerModificationException(id)
        activeLayer.deleteEdge(id)
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        ensureOpen()
        if (!containsNode(id)) throw EntityNotExistException(id)
        val result = mutableSetOf<EdgeID>()
        for (layer in frozenLayers) {
            if (layer.containsNode(id)) result.addAll(layer.getIncomingEdges(id))
        }
        if (activeLayer.containsNode(id)) result.addAll(activeLayer.getIncomingEdges(id))
        return result
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        ensureOpen()
        if (!containsNode(id)) throw EntityNotExistException(id)
        val result = mutableSetOf<EdgeID>()
        for (layer in frozenLayers) {
            if (layer.containsNode(id)) result.addAll(layer.getOutgoingEdges(id))
        }
        if (activeLayer.containsNode(id)) result.addAll(activeLayer.getOutgoingEdges(id))
        return result
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() {
            ensureOpen()
            val names = mutableSetOf<String>()
            for (layer in frozenLayers) {
                names.addAll(layer.metaNames)
            }
            names.addAll(activeLayer.metaNames)
            return names
        }

    override fun getMeta(name: String): IValue? {
        ensureOpen()
        activeLayer.getMeta(name)?.let { return it }
        for (layer in frozenLayers.asReversed()) {
            layer.getMeta(name)?.let { return it }
        }
        return null
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
}
