package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue

/**
 * In-memory graph storage implementation using HashMap-based data structures.
 *
 * Provides efficient storage with O(1) average time complexity. Not thread-safe.
 * Uses separate incoming/outgoing adjacency indices for O(1) directional edge queries.
 *
 * @constructor Creates a new empty storage instance.
 * @see NativeConcurStorageImpl
 */
class NativeStorageImpl : IStorage {
    private var isClosed: Boolean = false

    // Entity property stores — LinkedHashMap preserves insertion order for nodeIDs/edgeIDs
    private val nodeProperties: MutableMap<NodeID, MutableMap<String, IValue>> = mutableMapOf()
    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = mutableMapOf()
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()

    // Split adjacency indices: O(1) directional lookups without filtering
    private val outEdges = HashMap<NodeID, MutableSet<EdgeID>>()
    private val inEdges = HashMap<NodeID, MutableSet<EdgeID>>()

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() = if (!isClosed) nodeProperties.keys else throw AccessClosedStorageException()

    override val edgeIDs: Set<EdgeID>
        get() = if (!isClosed) edgeProperties.keys else throw AccessClosedStorageException()

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in nodeProperties
    }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        if (containsNode(id)) throw EntityAlreadyExistException(id = id)
        nodeProperties[id] = properties.toMutableMap()
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return nodeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) ->
            if (value != null) container[key] = value else container.remove(key)
        }
    }

    override fun deleteNode(id: NodeID) {
        if (isClosed) throw AccessClosedStorageException()
        if (!containsNode(id)) throw EntityNotExistException(id)
        // Snapshot connected edges before mutating adjacency sets
        val connected = HashSet<EdgeID>(outEdges[id]!!.size + inEdges[id]!!.size)
        connected.addAll(outEdges[id]!!)
        connected.addAll(inEdges[id]!!)
        connected.forEach { deleteEdge(it) }
        outEdges.remove(id)
        inEdges.remove(id)
        nodeProperties.remove(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in edgeProperties
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        if (containsEdge(id)) throw EntityAlreadyExistException(id = id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        outEdges[id.srcNid]!!.add(id)
        inEdges[id.dstNid]!!.add(id)
        edgeProperties[id] = properties.toMutableMap()
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return edgeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) ->
            if (value != null) container[key] = value else container.remove(key)
        }
    }

    override fun deleteEdge(id: EdgeID) {
        if (isClosed) throw AccessClosedStorageException()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        outEdges[id.srcNid]?.remove(id)
        inEdges[id.dstNid]?.remove(id)
        edgeProperties.remove(id)
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        if (!containsNode(id)) throw EntityNotExistException(id)
        return inEdges[id] ?: emptySet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        if (!containsNode(id)) throw EntityNotExistException(id)
        return outEdges[id] ?: emptySet()
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return metaProperties.keys.toSet()
        }

    override fun getMeta(name: String): IValue? {
        if (isClosed) throw AccessClosedStorageException()
        return metaProperties[name]
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        if (value == null) {
            metaProperties.remove(name)
        } else {
            metaProperties[name] = value
        }
    }

    // ============================================================================
    // UTILITY OPERATIONS
    // ============================================================================

    override fun clear(): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        outEdges.clear()
        inEdges.clear()
        edgeProperties.clear()
        nodeProperties.clear()
        metaProperties.clear()
        return true
    }

    override fun close() {
        if (!isClosed) clear()
        isClosed = true
    }
}
