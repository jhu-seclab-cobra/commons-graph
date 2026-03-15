package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.FrozenLayerModificationException
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
 * - Adjacency: returns union set views merging both layers without allocation.
 *
 * Deletion is restricted to the active layer. Attempting to delete a frozen-layer
 * entity throws [FrozenLayerModificationException].
 *
 * @param frozenLayerFactory Factory for creating storage instances used as frozen layers.
 * @see FrozenLayerModificationException
 */
class LayeredStorageImpl(
    private val frozenLayerFactory: () -> IStorage = { NativeStorageImpl() },
) : IStorage {
    private val frozenLayers = mutableListOf<IStorage>()
    private var activeLayer = NativeStorageImpl()
    private var closed = false

    private val frozenLayer: IStorage? get() = frozenLayers.firstOrNull()

    // ============================================================================
    // LAYERED STORAGE API
    // ============================================================================

    val layerCount: Int get() = frozenLayers.size + 1

    fun freeze() {
        ensureOpen()
        val merged = frozenLayerFactory()
        val nodeIdMap = HashMap<Int, Int>()
        val edgeIdMap = HashMap<Int, Int>()
        for (layer in frozenLayers) {
            mergeLayerInto(layer, merged, nodeIdMap, edgeIdMap)
            layer.close()
        }
        mergeLayerInto(activeLayer, merged, nodeIdMap, edgeIdMap)
        activeLayer.close()
        frozenLayers.clear()
        frozenLayers.add(merged)
        activeLayer = NativeStorageImpl().apply {
            setCounterStart(merged.nodeIDs.size, merged.edgeIDs.size)
        }
    }

    fun compact(topN: Int) {
        ensureOpen()
        require(topN in 1..frozenLayers.size) {
            "topN=$topN out of range [1, ${frozenLayers.size}]"
        }
        if (topN == 1) return
        val startIdx = frozenLayers.size - topN
        val layersToCompact = frozenLayers.subList(startIdx, frozenLayers.size)
        val compacted = frozenLayerFactory()
        val nodeIdMap = HashMap<Int, Int>()
        val edgeIdMap = HashMap<Int, Int>()
        for (layer in layersToCompact) {
            mergeLayerInto(layer, compacted, nodeIdMap, edgeIdMap)
            layer.close()
        }
        layersToCompact.clear()
        frozenLayers.add(compacted)
    }

    private fun mergeLayerInto(
        source: IStorage,
        target: IStorage,
        nodeIdMap: HashMap<Int, Int>,
        edgeIdMap: HashMap<Int, Int>,
    ) {
        for (nodeId in source.nodeIDs) {
            if (nodeId in nodeIdMap) {
                target.setNodeProperties(nodeIdMap[nodeId]!!, source.getNodeProperties(nodeId))
            } else {
                nodeIdMap[nodeId] = target.addNode(source.getNodeProperties(nodeId))
            }
        }
        for (edgeId in source.edgeIDs) {
            val srcId = nodeIdMap[source.getEdgeSrc(edgeId)]!!
            val dstId = nodeIdMap[source.getEdgeDst(edgeId)]!!
            val edgeType = source.getEdgeType(edgeId)
            if (edgeId in edgeIdMap) {
                val props = source.getEdgeProperties(edgeId)
                if (props.isNotEmpty()) target.setEdgeProperties(edgeIdMap[edgeId]!!, props)
            } else {
                edgeIdMap[edgeId] = target.addEdge(srcId, dstId, edgeType, source.getEdgeProperties(edgeId))
            }
        }
        for (name in source.metaNames) {
            target.setMeta(name, source.getMeta(name))
        }
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() {
            ensureOpen()
            val frozen = frozenLayer ?: return activeLayer.nodeIDs
            val activeIds = activeLayer.nodeIDs
            if (activeIds.isEmpty()) return frozen.nodeIDs
            return UnionSet(frozen.nodeIDs, activeIds)
        }

    override fun containsNode(id: Int): Boolean {
        ensureOpen()
        if (activeLayer.containsNode(id)) return true
        return frozenLayer?.containsNode(id) ?: false
    }

    override fun addNode(properties: Map<String, IValue>): Int {
        ensureOpen()
        return activeLayer.addNode(properties)
    }

    override fun getNodeProperties(id: Int): Map<String, IValue> {
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
        id: Int,
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
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val inActive = activeLayer.containsNode(id)
        if (!inActive && frozenLayer?.containsNode(id) != true) throw EntityNotExistException(id)
        if (!inActive) ensureNodeInActiveLayer(id)
        activeLayer.setNodeProperties(id, properties)
    }

    override fun deleteNode(id: Int) {
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

    override val edgeIDs: Set<Int>
        get() {
            ensureOpen()
            val frozen = frozenLayer ?: return activeLayer.edgeIDs
            val activeIds = activeLayer.edgeIDs
            if (activeIds.isEmpty()) return frozen.edgeIDs
            return UnionSet(frozen.edgeIDs, activeIds)
        }

    override fun containsEdge(id: Int): Boolean {
        ensureOpen()
        if (activeLayer.containsEdge(id)) return true
        return frozenLayer?.containsEdge(id) ?: false
    }

    override fun addEdge(
        src: Int,
        dst: Int,
        type: String,
        properties: Map<String, IValue>,
    ): Int {
        ensureOpen()
        ensureNodeInActiveLayer(src)
        ensureNodeInActiveLayer(dst)
        return activeLayer.addEdge(src, dst, type, properties)
    }

    override fun getEdgeSrc(id: Int): Int {
        ensureOpen()
        if (activeLayer.containsEdge(id)) return activeLayer.getEdgeSrc(id)
        return frozenLayer?.getEdgeSrc(id) ?: throw EntityNotExistException(id)
    }

    override fun getEdgeDst(id: Int): Int {
        ensureOpen()
        if (activeLayer.containsEdge(id)) return activeLayer.getEdgeDst(id)
        return frozenLayer?.getEdgeDst(id) ?: throw EntityNotExistException(id)
    }

    override fun getEdgeType(id: Int): String {
        ensureOpen()
        if (activeLayer.containsEdge(id)) return activeLayer.getEdgeType(id)
        return frozenLayer?.getEdgeType(id) ?: throw EntityNotExistException(id)
    }

    override fun getEdgeProperties(id: Int): Map<String, IValue> {
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
        id: Int,
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
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val inActive = activeLayer.containsEdge(id)
        if (!inActive && frozenLayer?.containsEdge(id) != true) throw EntityNotExistException(id)
        if (!inActive) {
            val src = getEdgeSrc(id)
            val dst = getEdgeDst(id)
            val type = getEdgeType(id)
            ensureNodeInActiveLayer(src)
            ensureNodeInActiveLayer(dst)
            activeLayer.addEdgeWithId(src, dst, type, emptyMap(), id)
        }
        activeLayer.setEdgeProperties(id, properties)
    }

    override fun deleteEdge(id: Int) {
        ensureOpen()
        if (!activeLayer.containsEdge(id)) {
            if (frozenLayer?.containsEdge(id) == true) throw FrozenLayerModificationException(id)
            throw EntityNotExistException(id)
        }
        activeLayer.deleteEdge(id)
    }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> {
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

    override fun getOutgoingEdges(id: Int): Set<Int> {
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
    // LIFECYCLE
    // ============================================================================

    override fun clear() {
        ensureOpen()
        for (layer in frozenLayers) layer.close()
        frozenLayers.clear()
        activeLayer.clear()
    }

    override fun transferTo(target: IStorage) {
        ensureOpen()
        val idMap = HashMap<Int, Int>()
        for (nodeId in nodeIDs) {
            idMap[nodeId] = target.addNode(getNodeProperties(nodeId))
        }
        for (edgeId in edgeIDs) {
            val newSrc = idMap[getEdgeSrc(edgeId)]!!
            val newDst = idMap[getEdgeDst(edgeId)]!!
            target.addEdge(newSrc, newDst, getEdgeType(edgeId), getEdgeProperties(edgeId))
        }
        for (name in metaNames) {
            target.setMeta(name, getMeta(name))
        }
    }

    override fun close() {
        if (closed) return
        for (layer in frozenLayers) layer.close()
        frozenLayers.clear()
        activeLayer.close()
        closed = true
    }

    // ============================================================================
    // INTERNAL
    // ============================================================================

    private fun ensureOpen() {
        if (closed) throw AccessClosedStorageException()
    }

    private fun ensureNodeInActiveLayer(id: Int) {
        if (activeLayer.containsNode(id)) return
        if (frozenLayer?.containsNode(id) != true) throw EntityNotExistException(id)
        activeLayer.addNodeWithId(frozenLayer!!.getNodeProperties(id), id)
    }

    // ============================================================================
    // VIEW TYPES
    // ============================================================================

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
