package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
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

    // Non-locking helpers — callers must have already checked isClosed
    private fun hasNode(id: NodeID): Boolean = id in nodeProperties

    private fun hasEdge(id: EdgeID): Boolean = id in edgeProperties

    private fun ensureOpen() {
        if (isClosed) throw AccessClosedStorageException()
    }

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() {
            ensureOpen()
            return nodeProperties.keys
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            ensureOpen()
            return edgeProperties.keys
        }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean {
        ensureOpen()
        return hasNode(id)
    }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) {
        ensureOpen()
        if (hasNode(id)) throw EntityAlreadyExistException(id = id)
        nodeProperties[id] = properties.toMutableMap()
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        ensureOpen()
        return nodeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun getNodeProperty(
        id: NodeID,
        name: String,
    ): IValue? {
        ensureOpen()
        return (nodeProperties[id] ?: throw EntityNotExistException(id = id))[name]
    }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) ->
            if (value != null) container[key] = value else container.remove(key)
        }
    }

    override fun deleteNode(id: NodeID) {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        // Inline edge removal: avoid per-edge ensureOpen + hasEdge re-checks
        for (eid in outEdges[id]!!) {
            inEdges[eid.dstNid]?.remove(eid)
            edgeProperties.remove(eid)
        }
        for (eid in inEdges[id]!!) {
            outEdges[eid.srcNid]?.remove(eid)
            edgeProperties.remove(eid)
        }
        outEdges.remove(id)
        inEdges.remove(id)
        nodeProperties.remove(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean {
        ensureOpen()
        return hasEdge(id)
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) {
        ensureOpen()
        if (hasEdge(id)) throw EntityAlreadyExistException(id = id)
        if (!hasNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!hasNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        outEdges[id.srcNid]!!.add(id)
        inEdges[id.dstNid]!!.add(id)
        edgeProperties[id] = properties.toMutableMap()
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        ensureOpen()
        return edgeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun getEdgeProperty(
        id: EdgeID,
        name: String,
    ): IValue? {
        ensureOpen()
        return (edgeProperties[id] ?: throw EntityNotExistException(id = id))[name]
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) ->
            if (value != null) container[key] = value else container.remove(key)
        }
    }

    override fun deleteEdge(id: EdgeID) {
        ensureOpen()
        if (!hasEdge(id)) throw EntityNotExistException(id)
        outEdges[id.srcNid]?.remove(id)
        inEdges[id.dstNid]?.remove(id)
        edgeProperties.remove(id)
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return inEdges[id] ?: emptySet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return outEdges[id] ?: emptySet()
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() {
            ensureOpen()
            return metaProperties.keys
        }

    override fun getMeta(name: String): IValue? {
        ensureOpen()
        return metaProperties[name]
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        ensureOpen()
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
        ensureOpen()
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
