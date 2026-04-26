package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.FrozenLayerModificationException
import edu.jhu.cobra.commons.value.IValue
import java.util.Collections

/**
 * Multi-layer freeze-and-stack storage for phased analysis pipelines.
 *
 * Active layer data is stored directly in this class using global Int IDs.
 * Frozen layer is an independent [IStorage] instance with its own local IDs.
 * ID mapping (global ↔ frozen local) is maintained internally.
 *
 * Each [freeze] merges active + frozen data into a new frozen layer and resets
 * the active layer, keeping query depth at O(1).
 *
 * Query resolution:
 * - Properties: active layer first, then frozen layer (overlay semantics).
 * - Adjacency: returns union set views merging both layers.
 *
 * Deletion is restricted to the active layer. Attempting to delete a frozen-layer
 * entity throws [FrozenLayerModificationException].
 *
 * @param frozenLayerFactory Factory for creating storage instances used as frozen layers.
 * @see FrozenLayerModificationException
 */
@Suppress("TooManyFunctions")
class LayeredStorageImpl(
    private val frozenLayerFactory: () -> IStorage = { NativeStorageImpl() },
) : IStorage {
    private var closed = false

    // Global ID counters
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    // ---- Active layer (embedded, uses global IDs directly) ----
    private val activeNodeColumns = HashMap<String, HashMap<Int, IValue>>()
    private val activeEdgeColumns = HashMap<String, HashMap<Int, IValue>>()
    private val activeEdgeEndpoints = HashMap<Int, IStorage.EdgeStructure>()
    private val activeOutEdges = HashMap<Int, MutableSet<Int>>()
    private val activeInEdges = HashMap<Int, MutableSet<Int>>()
    private val activeMetaProperties = HashMap<String, IValue>()

    // ---- Frozen layer (separate IStorage with local IDs) ----
    private var frozenLayer: IStorage? = null

    // Global ↔ frozen local ID mappings
    private val frozenNodeGlobalToLocal = HashMap<Int, Int>()
    private val frozenNodeLocalToGlobal = HashMap<Int, Int>()
    private val frozenEdgeGlobalToLocal = HashMap<Int, Int>()
    private val frozenEdgeLocalToGlobal = HashMap<Int, Int>()

    // Cache for translated frozen edge structures (global IDs)
    private val frozenEdgeStructureCache = HashMap<Int, IStorage.EdgeStructure>()

    private fun ensureOpen() {
        if (closed) throw AccessClosedStorageException()
    }

    private fun isActiveNode(id: Int): Boolean = id in activeOutEdges

    private fun isFrozenNode(id: Int): Boolean = id in frozenNodeGlobalToLocal

    private fun isActiveEdge(id: Int): Boolean = id in activeEdgeEndpoints

    private fun isFrozenEdge(id: Int): Boolean = id in frozenEdgeGlobalToLocal

    // ============================================================================
    // LAYERED STORAGE API
    // ============================================================================

    val layerCount: Int get() = if (frozenLayer != null) 2 else 1

    fun freeze() {
        ensureOpen()
        val merged = frozenLayerFactory()
        val frozen = frozenLayer

        val frozenOldToNewNode = freezeTransferFrozenNodes(frozen, merged)
        val (newNodeG2L, newNodeL2G) = freezeMergeNodes(merged, frozenOldToNewNode)
        val (newEdgeG2L, newEdgeL2G) = freezeMergeEdges(frozen, merged, frozenOldToNewNode, newNodeG2L)
        freezeTransferMetadata(frozen, merged)

        frozen?.close()
        swapFrozenLayer(merged, newNodeG2L, newNodeL2G, newEdgeG2L, newEdgeL2G)
        clearActiveLayer()
    }

    private fun freezeTransferFrozenNodes(
        frozen: IStorage?,
        merged: IStorage,
    ): HashMap<Int, Int> {
        val oldToNew = HashMap<Int, Int>()
        if (frozen == null) return oldToNew
        for (frozenLocalId in frozen.nodeIDs) {
            oldToNew[frozenLocalId] = merged.addNode(frozen.getNodeProperties(frozenLocalId))
        }
        return oldToNew
    }

    private fun freezeMergeNodes(
        merged: IStorage,
        frozenOldToNew: HashMap<Int, Int>,
    ): Pair<HashMap<Int, Int>, HashMap<Int, Int>> {
        val g2l = HashMap<Int, Int>()
        val l2g = HashMap<Int, Int>()
        for ((globalId, oldLocalId) in frozenNodeGlobalToLocal) {
            val newLocalId = frozenOldToNew[oldLocalId]!!
            if (isActiveNode(globalId)) {
                val overlay = collectActiveNodeProperties(globalId)
                if (overlay.isNotEmpty()) merged.setNodeProperties(newLocalId, overlay)
            }
            g2l[globalId] = newLocalId
            l2g[newLocalId] = globalId
        }
        for (globalId in activeOutEdges.keys) {
            if (globalId in frozenNodeGlobalToLocal) continue
            val newLocalId = merged.addNode(collectActiveNodeProperties(globalId))
            g2l[globalId] = newLocalId
            l2g[newLocalId] = globalId
        }
        return g2l to l2g
    }

    private fun freezeMergeEdges(
        frozen: IStorage?,
        merged: IStorage,
        frozenOldToNewNode: HashMap<Int, Int>,
        newNodeG2L: HashMap<Int, Int>,
    ): Pair<HashMap<Int, Int>, HashMap<Int, Int>> {
        val g2l = HashMap<Int, Int>()
        val l2g = HashMap<Int, Int>()
        if (frozen != null) {
            for (frozenLocalEdgeId in frozen.edgeIDs) {
                val structure = frozen.getEdgeStructure(frozenLocalEdgeId)
                val globalEdgeId = frozenEdgeLocalToGlobal[frozenLocalEdgeId]!!
                val props = freezeResolveEdgeProps(frozen, frozenLocalEdgeId, globalEdgeId)
                val newId = merged.addEdge(frozenOldToNewNode[structure.src]!!, frozenOldToNewNode[structure.dst]!!, structure.tag, props)
                g2l[globalEdgeId] = newId
                l2g[newId] = globalEdgeId
            }
        }
        for ((globalEdgeId, structure) in activeEdgeEndpoints) {
            if (globalEdgeId in frozenEdgeGlobalToLocal) continue
            val props = collectActiveEdgeProperties(globalEdgeId)
            val newId = merged.addEdge(newNodeG2L[structure.src]!!, newNodeG2L[structure.dst]!!, structure.tag, props)
            g2l[globalEdgeId] = newId
            l2g[newId] = globalEdgeId
        }
        return g2l to l2g
    }

    private fun freezeResolveEdgeProps(
        frozen: IStorage,
        frozenLocalEdgeId: Int,
        globalEdgeId: Int,
    ): Map<String, IValue> {
        if (!isActiveEdge(globalEdgeId)) return frozen.getEdgeProperties(frozenLocalEdgeId)
        val base = frozen.getEdgeProperties(frozenLocalEdgeId)
        val overlay = collectActiveEdgeProperties(globalEdgeId)
        if (overlay.isEmpty()) return base
        return HashMap(base).also { it.putAll(overlay) }
    }

    private fun freezeTransferMetadata(
        frozen: IStorage?,
        merged: IStorage,
    ) {
        if (frozen != null) {
            for (name in frozen.metaNames) {
                merged.setMeta(name, frozen.getMeta(name))
            }
        }
        for ((name, value) in activeMetaProperties) {
            merged.setMeta(name, value)
        }
    }

    private fun swapFrozenLayer(
        merged: IStorage,
        nodeG2L: HashMap<Int, Int>,
        nodeL2G: HashMap<Int, Int>,
        edgeG2L: HashMap<Int, Int>,
        edgeL2G: HashMap<Int, Int>,
    ) {
        frozenLayer = merged
        frozenNodeGlobalToLocal.clear()
        frozenNodeGlobalToLocal.putAll(nodeG2L)
        frozenNodeLocalToGlobal.clear()
        frozenNodeLocalToGlobal.putAll(nodeL2G)
        frozenEdgeGlobalToLocal.clear()
        frozenEdgeGlobalToLocal.putAll(edgeG2L)
        frozenEdgeLocalToGlobal.clear()
        frozenEdgeLocalToGlobal.putAll(edgeL2G)
    }

    /**
     * Clears all data in the active layer while preserving the frozen layer.
     *
     * Removes all active-layer nodes, edges, properties, and metadata.
     * The frozen layer and its ID mappings remain intact, so subsequent
     * reads still resolve against frozen data.
     *
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun clearActiveLayer() {
        ensureOpen()
        activeOutEdges.clear()
        activeInEdges.clear()
        activeEdgeEndpoints.clear()
        activeEdgeColumns.clear()
        activeNodeColumns.clear()
        activeMetaProperties.clear()
        frozenEdgeStructureCache.clear()
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() {
            ensureOpen()
            val frozenGlobalIds = frozenNodeGlobalToLocal.keys
            if (activeOutEdges.isEmpty()) return frozenGlobalIds
            if (frozenGlobalIds.isEmpty()) return activeOutEdges.keys
            return UnionSet(frozenGlobalIds, activeOutEdges.keys)
        }

    override fun containsNode(id: Int): Boolean {
        ensureOpen()
        return isActiveNode(id) || isFrozenNode(id)
    }

    override fun addNode(properties: Map<String, IValue>): Int {
        ensureOpen()
        val id = nodeCounter++
        activeOutEdges[id] = HashSet()
        activeInEdges[id] = HashSet()
        for ((key, value) in properties) {
            activeNodeColumns.getOrPut(key) { HashMap() }[id] = value
        }
        return id
    }

    override fun getNodeProperties(id: Int): Map<String, IValue> {
        ensureOpen()
        val inActive = id in activeOutEdges
        val frozenLocalId = frozenNodeGlobalToLocal[id]
        if (!inActive && frozenLocalId == null) throw EntityNotExistException(id.toString())
        if (frozenLocalId == null) return ActiveColumnViewMap(id, activeNodeColumns)
        val frozenProps = frozenLayer!!.getNodeProperties(frozenLocalId)
        if (!inActive) return frozenProps
        return LazyMergedMap(frozenProps, ActiveColumnViewMap(id, activeNodeColumns))
    }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? {
        ensureOpen()
        val inActive = id in activeOutEdges
        val frozenLocalId = frozenNodeGlobalToLocal[id]
        if (!inActive && frozenLocalId == null) throw EntityNotExistException(id.toString())
        if (inActive) {
            activeNodeColumns[name]?.get(id)?.let { return it }
        }
        if (frozenLocalId != null) return frozenLayer!!.getNodeProperty(frozenLocalId, name)
        return null
    }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (!isActiveNode(id) && !isFrozenNode(id)) throw EntityNotExistException(id.toString())
        ensureNodeInActiveLayer(id)
        setActiveColumnarProperties(id, properties, activeNodeColumns)
    }

    override fun deleteNode(id: Int) {
        ensureOpen()
        if (!isActiveNode(id)) {
            if (isFrozenNode(id)) throw FrozenLayerModificationException(id.toString())
            throw EntityNotExistException(id.toString())
        }
        val outSet = activeOutEdges[id] ?: emptySet<Int>()
        val inSet = activeInEdges[id] ?: emptySet<Int>()
        for (eid in outSet.toList()) deleteActiveIncidentEdge(eid)
        for (eid in inSet.toList()) deleteActiveIncidentEdge(eid)
        activeOutEdges.remove(id)
        activeInEdges.remove(id)
        removeEntityFromColumns(id, activeNodeColumns)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<Int>
        get() {
            ensureOpen()
            val frozenGlobalIds = frozenEdgeGlobalToLocal.keys
            if (activeEdgeEndpoints.isEmpty()) return frozenGlobalIds
            if (frozenGlobalIds.isEmpty()) return activeEdgeEndpoints.keys
            return UnionSet(frozenGlobalIds, activeEdgeEndpoints.keys)
        }

    override fun containsEdge(id: Int): Boolean {
        ensureOpen()
        return isActiveEdge(id) || isFrozenEdge(id)
    }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int {
        ensureOpen()
        ensureNodeInActiveLayer(src)
        ensureNodeInActiveLayer(dst)
        val id = edgeCounter++
        activeEdgeEndpoints[id] = IStorage.EdgeStructure(src, dst, tag)
        activeOutEdges[src]!!.add(id)
        activeInEdges[dst]!!.add(id)
        for ((key, value) in properties) {
            activeEdgeColumns.getOrPut(key) { HashMap() }[id] = value
        }
        return id
    }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure {
        ensureOpen()
        activeEdgeEndpoints[id]?.let { return it }
        frozenEdgeStructureCache[id]?.let { return it }
        val frozenLocalId = frozenEdgeGlobalToLocal[id] ?: throw EntityNotExistException(id.toString())
        val frozenStructure = frozenLayer!!.getEdgeStructure(frozenLocalId)
        val translated =
            IStorage.EdgeStructure(
                frozenNodeLocalToGlobal[frozenStructure.src]!!,
                frozenNodeLocalToGlobal[frozenStructure.dst]!!,
                frozenStructure.tag,
            )
        frozenEdgeStructureCache[id] = translated
        return translated
    }

    override fun getEdgeProperties(id: Int): Map<String, IValue> {
        ensureOpen()
        val inActive = id in activeEdgeEndpoints
        val frozenLocalId = frozenEdgeGlobalToLocal[id]
        if (!inActive && frozenLocalId == null) throw EntityNotExistException(id.toString())
        if (frozenLocalId == null) return ActiveColumnViewMap(id, activeEdgeColumns)
        val frozenProps = frozenLayer!!.getEdgeProperties(frozenLocalId)
        if (!inActive) return frozenProps
        return LazyMergedMap(frozenProps, ActiveColumnViewMap(id, activeEdgeColumns))
    }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? {
        ensureOpen()
        val inActive = id in activeEdgeEndpoints
        val frozenLocalId = frozenEdgeGlobalToLocal[id]
        if (!inActive && frozenLocalId == null) throw EntityNotExistException(id.toString())
        if (inActive) {
            activeEdgeColumns[name]?.get(id)?.let { return it }
        }
        if (frozenLocalId != null) return frozenLayer!!.getEdgeProperty(frozenLocalId, name)
        return null
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (!isActiveEdge(id) && !isFrozenEdge(id)) throw EntityNotExistException(id.toString())
        if (!isActiveEdge(id)) {
            // Promote frozen edge to active layer for writes
            val structure = getEdgeStructure(id)
            ensureNodeInActiveLayer(structure.src)
            ensureNodeInActiveLayer(structure.dst)
            activeEdgeEndpoints[id] = structure
            activeOutEdges[structure.src]!!.add(id)
            activeInEdges[structure.dst]!!.add(id)
        }
        setActiveColumnarProperties(id, properties, activeEdgeColumns)
    }

    override fun deleteEdge(id: Int) {
        ensureOpen()
        if (!isActiveEdge(id)) {
            if (isFrozenEdge(id)) throw FrozenLayerModificationException(id.toString())
            throw EntityNotExistException(id.toString())
        }
        deleteActiveIncidentEdge(id)
    }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> {
        ensureOpen()
        val activeEdges = activeInEdges[id]
        val frozenLocalNodeId = frozenNodeGlobalToLocal[id]
        if (activeEdges == null && frozenLocalNodeId == null) throw EntityNotExistException(id.toString())
        val frozenEdges =
            if (frozenLocalNodeId != null) {
                MappedEdgeSet(frozenLayer!!.getIncomingEdges(frozenLocalNodeId), frozenEdgeLocalToGlobal, frozenEdgeGlobalToLocal)
            } else {
                emptySet()
            }
        if (activeEdges == null || activeEdges.isEmpty()) return frozenEdges
        if (frozenEdges.isEmpty()) return Collections.unmodifiableSet(activeEdges)
        return UnionSet(frozenEdges, activeEdges)
    }

    override fun getOutgoingEdges(id: Int): Set<Int> {
        ensureOpen()
        val activeEdges = activeOutEdges[id]
        val frozenLocalNodeId = frozenNodeGlobalToLocal[id]
        if (activeEdges == null && frozenLocalNodeId == null) throw EntityNotExistException(id.toString())
        val frozenEdges =
            if (frozenLocalNodeId != null) {
                MappedEdgeSet(frozenLayer!!.getOutgoingEdges(frozenLocalNodeId), frozenEdgeLocalToGlobal, frozenEdgeGlobalToLocal)
            } else {
                emptySet()
            }
        if (activeEdges == null || activeEdges.isEmpty()) return frozenEdges
        if (frozenEdges.isEmpty()) return Collections.unmodifiableSet(activeEdges)
        return UnionSet(frozenEdges, activeEdges)
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() {
            ensureOpen()
            val frozenNames = frozenLayer?.metaNames ?: emptySet()
            if (activeMetaProperties.isEmpty()) return frozenNames
            if (frozenNames.isEmpty()) return activeMetaProperties.keys
            return UnionSet(frozenNames, activeMetaProperties.keys)
        }

    override fun getMeta(name: String): IValue? {
        ensureOpen()
        activeMetaProperties[name]?.let { return it }
        return frozenLayer?.getMeta(name)
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        ensureOpen()
        if (value == null) activeMetaProperties.remove(name) else activeMetaProperties[name] = value
    }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun clear() {
        ensureOpen()
        frozenLayer?.close()
        frozenLayer = null
        frozenNodeGlobalToLocal.clear()
        frozenNodeLocalToGlobal.clear()
        frozenEdgeGlobalToLocal.clear()
        frozenEdgeLocalToGlobal.clear()
        frozenEdgeStructureCache.clear()
        activeOutEdges.clear()
        activeInEdges.clear()
        activeEdgeEndpoints.clear()
        activeEdgeColumns.clear()
        activeNodeColumns.clear()
        activeMetaProperties.clear()
        nodeCounter = 0
        edgeCounter = 0
    }

    override fun transferTo(target: IStorage): Map<Int, Int> {
        ensureOpen()
        val nodeIdMap = HashMap<Int, Int>()
        for (nodeId in nodeIDs) {
            val newId = target.addNode(getNodeProperties(nodeId))
            nodeIdMap[nodeId] = newId
        }
        for (edgeId in edgeIDs) {
            val structure = getEdgeStructure(edgeId)
            val newSrc = nodeIdMap[structure.src]!!
            val newDst = nodeIdMap[structure.dst]!!
            target.addEdge(newSrc, newDst, structure.tag, getEdgeProperties(edgeId))
        }
        for (name in metaNames) {
            target.setMeta(name, getMeta(name))
        }
        return nodeIdMap
    }

    override fun close() {
        if (closed) return
        frozenLayer?.close()
        frozenLayer = null
        closed = true
    }

    // ============================================================================
    // INTERNAL HELPERS
    // ============================================================================

    private fun ensureNodeInActiveLayer(id: Int) {
        if (isActiveNode(id)) return
        val frozenLocalId = frozenNodeGlobalToLocal[id] ?: throw EntityNotExistException(id.toString())
        val props = frozenLayer!!.getNodeProperties(frozenLocalId)
        activeOutEdges[id] = HashSet()
        activeInEdges[id] = HashSet()
        for ((key, value) in props) {
            activeNodeColumns.getOrPut(key) { HashMap() }[id] = value
        }
    }

    private fun deleteActiveIncidentEdge(eid: Int) {
        val edge = activeEdgeEndpoints.remove(eid) ?: return
        activeOutEdges[edge.src]?.remove(eid)
        activeInEdges[edge.dst]?.remove(eid)
        removeEntityFromColumns(eid, activeEdgeColumns)
    }

    private fun collectActiveNodeProperties(id: Int): Map<String, IValue> {
        val result = HashMap<String, IValue>()
        for ((colName, col) in activeNodeColumns) {
            val v = col[id] ?: continue
            result[colName] = v
        }
        return result
    }

    private fun collectActiveEdgeProperties(id: Int): Map<String, IValue> {
        val result = HashMap<String, IValue>()
        for ((colName, col) in activeEdgeColumns) {
            val v = col[id] ?: continue
            result[colName] = v
        }
        return result
    }

    private fun removeEntityFromColumns(
        id: Int,
        columns: HashMap<String, HashMap<Int, IValue>>,
    ) {
        val colIter = columns.values.iterator()
        while (colIter.hasNext()) {
            val col = colIter.next()
            col.remove(id)
            if (col.isEmpty()) colIter.remove()
        }
    }

    private fun setActiveColumnarProperties(
        id: Int,
        properties: Map<String, IValue?>,
        columns: HashMap<String, HashMap<Int, IValue>>,
    ) {
        for ((key, value) in properties) {
            if (value != null) {
                columns.getOrPut(key) { HashMap() }[id] = value
            } else {
                val col = columns[key] ?: continue
                col.remove(id)
                if (col.isEmpty()) columns.remove(key)
            }
        }
    }

    // ============================================================================
    // VIEW TYPES
    // ============================================================================

    private class ActiveColumnViewMap(
        private val entityId: Int,
        private val columns: HashMap<String, HashMap<Int, IValue>>,
    ) : AbstractMap<String, IValue>() {
        private var cachedEntries: Set<Map.Entry<String, IValue>>? = null

        override val entries: Set<Map.Entry<String, IValue>>
            get() {
                cachedEntries?.let { return it }
                val result = LinkedHashMap<String, IValue>()
                for ((colName, col) in columns) {
                    val v = col[entityId] ?: continue
                    result[colName] = v
                }
                return result.entries.also { cachedEntries = it }
            }

        override fun get(key: String): IValue? = columns[key]?.get(entityId)

        override fun containsKey(key: String): Boolean = columns[key]?.containsKey(entityId) == true

        override val size: Int get() = entries.size

        override fun isEmpty(): Boolean {
            for (col in columns.values) {
                if (col.containsKey(entityId)) return false
            }
            return true
        }
    }

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

    private class MappedEdgeSet(
        private val localIds: Set<Int>,
        private val localToGlobal: Map<Int, Int>,
        private val globalToLocal: Map<Int, Int>,
    ) : AbstractSet<Int>() {
        override val size: Int get() = localIds.size

        override fun iterator(): Iterator<Int> {
            val iter = localIds.iterator()
            return object : Iterator<Int> {
                override fun hasNext() = iter.hasNext()

                override fun next(): Int {
                    val localId = iter.next()
                    return localToGlobal[localId] ?: throw NoSuchElementException("No global ID for local $localId")
                }
            }
        }

        override fun contains(element: Int): Boolean {
            val localId = globalToLocal[element] ?: return false
            return localId in localIds
        }

        override fun isEmpty(): Boolean = localIds.isEmpty()
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
