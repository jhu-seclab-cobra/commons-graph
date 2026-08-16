package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.FrozenLayerModificationException
import edu.jhu.cobra.commons.value.IValue
import java.util.Collections

/**
 * Multi-layer freeze-and-stack storage for phased analysis pipelines.
 *
 * Active layer data lives in an [ActiveLayer] using global Int IDs.
 * The frozen layer is a [FrozenLayer]: an independent [IStorage] snapshot with
 * its own local IDs plus global-to-local ID mappings.
 *
 * Each [freeze] merges active + frozen data into a new frozen layer and resets
 * the active layer, keeping query depth at O(1).
 *
 * Query resolution:
 * - Properties: the active copy is authoritative for entities present in the active
 *   layer — promotion copies all frozen properties, and a property deleted from the
 *   copy stays deleted. Frozen-only entities read from the frozen layer.
 * - Adjacency: returns union set views merging both layers.
 *
 * Deletion is restricted to entities that exist only in the active layer. Attempting
 * to delete a frozen-layer entity — including one promoted into the active layer —
 * throws [FrozenLayerModificationException].
 *
 * @param frozenLayerFactory Factory for creating storage instances used as frozen layers.
 * @see FrozenLayerModificationException
 */
@Suppress("TooManyFunctions")
public class LayeredStorageImpl(
    private val frozenLayerFactory: () -> IStorage = { NativeStorageImpl() },
) : IStorage {
    // Global ID counters
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    private val active = ActiveLayer()
    private var frozen: FrozenLayer? = null

    // ============================================================================
    // LAYERED STORAGE API
    // ============================================================================

    public val layerCount: Int get() = if (frozen != null) 2 else 1

    public fun freeze() {
        val merged = FrozenLayer.merge(frozen, active, frozenLayerFactory())
        frozen?.close()
        frozen = merged
        active.clear()
    }

    /**
     * Clears all data in the active layer while preserving the frozen layer.
     *
     * Removes all active-layer nodes, edges, properties, and metadata.
     * The frozen layer and its ID mappings remain intact, so subsequent
     * reads still resolve against frozen data.
     */
    public fun clearActiveLayer() {
        active.clear()
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() {
            val frozenIds = frozen?.nodeIds ?: emptySet()
            if (active.nodeIds.isEmpty()) return frozenIds
            if (frozenIds.isEmpty()) return active.nodeIds
            return UnionSet(frozenIds, active.nodeIds)
        }

    override fun containsNode(id: Int): Boolean = active.containsNode(id) || frozen?.containsNode(id) == true

    override fun addNode(properties: Map<String, IValue>): Int {
        val id = nodeCounter++
        active.addNode(id, properties)
        return id
    }

    override fun getNodeProperties(id: Int): Map<String, IValue> {
        if (active.containsNode(id)) return ActiveColumnViewMap(id, active.nodeColumns)
        return frozen?.nodeProperties(id) ?: throw EntityNotExistException(id.toString())
    }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? {
        if (active.containsNode(id)) return active.nodeColumns[name]?.get(id)
        if (frozen?.containsNode(id) != true) throw EntityNotExistException(id.toString())
        return frozen?.nodeProperty(id, name)
    }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        if (!containsNode(id)) throw EntityNotExistException(id.toString())
        ensureNodeInActiveLayer(id)
        active.setNodeProperties(id, properties)
    }

    override fun deleteNode(id: Int) {
        // A promoted node still exists in the frozen layer; deleting only the active
        // copy would resurrect the frozen one, so the frozen guard runs first.
        if (frozen?.containsNode(id) == true) throw FrozenLayerModificationException(id.toString())
        if (!active.containsNode(id)) throw EntityNotExistException(id.toString())
        active.removeNode(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<Int>
        get() {
            val frozenIds = frozen?.edgeIds ?: emptySet()
            if (active.edgeEndpoints.isEmpty()) return frozenIds
            if (frozenIds.isEmpty()) return active.edgeEndpoints.keys
            return UnionSet(frozenIds, active.edgeEndpoints.keys)
        }

    override fun containsEdge(id: Int): Boolean = active.containsEdge(id) || frozen?.containsEdge(id) == true

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int {
        ensureNodeInActiveLayer(src)
        ensureNodeInActiveLayer(dst)
        val id = edgeCounter++
        active.addEdge(id, IStorage.EdgeStructure(src, dst, tag), properties)
        return id
    }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure {
        active.edgeEndpoints[id]?.let { return it }
        return frozen?.edgeStructure(id) ?: throw EntityNotExistException(id.toString())
    }

    override fun getEdgeProperties(id: Int): Map<String, IValue> {
        if (active.containsEdge(id)) return ActiveColumnViewMap(id, active.edgeColumns)
        return frozen?.edgeProperties(id) ?: throw EntityNotExistException(id.toString())
    }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? {
        if (active.containsEdge(id)) return active.edgeColumns[name]?.get(id)
        if (frozen?.containsEdge(id) != true) throw EntityNotExistException(id.toString())
        return frozen?.edgeProperty(id, name)
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        if (!active.containsEdge(id)) {
            // Promote the frozen edge by copying all frozen properties, making the
            // active copy authoritative for subsequent reads and deletions.
            val frozenProps = frozen?.edgeProperties(id) ?: throw EntityNotExistException(id.toString())
            val structure = getEdgeStructure(id)
            ensureNodeInActiveLayer(structure.src)
            ensureNodeInActiveLayer(structure.dst)
            active.addEdge(id, structure, frozenProps)
        }
        active.setEdgeProperties(id, properties)
    }

    override fun deleteEdge(id: Int) {
        // A promoted edge still exists in the frozen layer; deleting only the active
        // copy would resurrect the frozen one, so the frozen guard runs first.
        if (frozen?.containsEdge(id) == true) throw FrozenLayerModificationException(id.toString())
        if (!active.containsEdge(id)) throw EntityNotExistException(id.toString())
        active.removeEdge(id)
    }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> {
        val activeEdges = active.inEdges[id]
        val frozenEdges = frozen?.incomingEdges(id)
        if (activeEdges == null && frozenEdges == null) throw EntityNotExistException(id.toString())
        if (activeEdges == null || activeEdges.isEmpty()) return frozenEdges ?: emptySet()
        if (frozenEdges == null || frozenEdges.isEmpty()) return Collections.unmodifiableSet(activeEdges)
        return UnionSet(frozenEdges, activeEdges)
    }

    override fun getOutgoingEdges(id: Int): Set<Int> {
        val activeEdges = active.outEdges[id]
        val frozenEdges = frozen?.outgoingEdges(id)
        if (activeEdges == null && frozenEdges == null) throw EntityNotExistException(id.toString())
        if (activeEdges == null || activeEdges.isEmpty()) return frozenEdges ?: emptySet()
        if (frozenEdges == null || frozenEdges.isEmpty()) return Collections.unmodifiableSet(activeEdges)
        return UnionSet(frozenEdges, activeEdges)
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() {
            val frozenNames = (frozen?.metaNames ?: emptySet()) - active.deletedMetaNames
            if (active.metaProperties.isEmpty()) return frozenNames
            if (frozenNames.isEmpty()) return active.metaProperties.keys
            return UnionSet(frozenNames, active.metaProperties.keys)
        }

    override fun getMeta(name: String): IValue? {
        active.metaProperties[name]?.let { return it }
        if (name in active.deletedMetaNames) return null
        return frozen?.meta(name)
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (value == null) {
            // Tombstone the name so a frozen-layer value does not resurface on reads.
            active.metaProperties.remove(name)
            active.deletedMetaNames.add(name)
        } else {
            active.deletedMetaNames.remove(name)
            active.metaProperties[name] = value
        }
    }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun clear() {
        frozen?.close()
        frozen = null
        active.clear()
        nodeCounter = 0
        edgeCounter = 0
    }

    override fun transferTo(target: IStorage): Map<Int, Int> {
        val nodeIdMap = HashMap<Int, Int>()
        for (nodeId in nodeIDs) {
            nodeIdMap[nodeId] = target.addNode(getNodeProperties(nodeId))
        }
        for (edgeId in edgeIDs) {
            val structure = getEdgeStructure(edgeId)
            val newSrc = nodeIdMap.getValue(structure.src)
            val newDst = nodeIdMap.getValue(structure.dst)
            target.addEdge(newSrc, newDst, structure.tag, getEdgeProperties(edgeId))
        }
        for (name in metaNames) {
            target.setMeta(name, getMeta(name))
        }
        return nodeIdMap
    }

    override fun flush() {
        // No buffered writes; mutations are applied immediately.
    }

    // ============================================================================
    // INTERNAL HELPERS
    // ============================================================================

    // Writes always land in the active layer; a frozen node is promoted by copying
    // its frozen properties into the active columns first.
    private fun ensureNodeInActiveLayer(id: Int) {
        if (active.containsNode(id)) return
        val props = frozen?.nodeProperties(id) ?: throw EntityNotExistException(id.toString())
        active.addNode(id, props)
    }
}
