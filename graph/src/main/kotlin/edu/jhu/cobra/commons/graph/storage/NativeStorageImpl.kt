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

    // Columnar node property storage: one HashMap per property name (column)
    // instead of one MutableMap per node (row). Reduces object count from O(N) to O(K)
    // where K = number of distinct property names, typically K << N.
    private val nodeSet: MutableSet<NodeID> = HashSet()
    private val nodeColumns: HashMap<String, HashMap<NodeID, IValue>> = HashMap()

    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = mutableMapOf()
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()

    // Split adjacency indices: O(1) directional lookups without filtering
    private val outEdges = HashMap<NodeID, MutableSet<EdgeID>>()
    private val inEdges = HashMap<NodeID, MutableSet<EdgeID>>()

    // Non-locking helpers — callers must have already checked isClosed
    private fun hasNode(id: NodeID): Boolean = id in nodeSet

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
            return nodeSet
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
        nodeSet.add(id)
        for ((key, value) in properties) {
            nodeColumns.getOrPut(key) { HashMap() }[id] = value
        }
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id = id)
        return ColumnViewMap(id, nodeColumns)
    }

    override fun getNodeProperty(
        id: NodeID,
        name: String,
    ): IValue? {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id = id)
        return nodeColumns[name]?.get(id)
    }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id = id)
        for ((key, value) in properties) {
            if (value != null) {
                nodeColumns.getOrPut(key) { HashMap() }[id] = value
            } else {
                val col = nodeColumns[key] ?: continue
                col.remove(id)
                if (col.isEmpty()) nodeColumns.remove(key)
            }
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
        // Remove node from all property columns
        val colIter = nodeColumns.values.iterator()
        while (colIter.hasNext()) {
            val col = colIter.next()
            col.remove(id)
            if (col.isEmpty()) colIter.remove()
        }
        nodeSet.remove(id)
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
        nodeSet.clear()
        nodeColumns.clear()
        metaProperties.clear()
        return true
    }

    override fun close() {
        if (!isClosed) clear()
        isClosed = true
    }

    // Read-only view assembling a node's properties from column storage.
    // get() is O(1) per column; iteration scans all columns but avoids per-node map allocation.
    private class ColumnViewMap(
        private val nodeId: NodeID,
        private val columns: HashMap<String, HashMap<NodeID, IValue>>,
    ) : AbstractMap<String, IValue>() {
        override val entries: Set<Map.Entry<String, IValue>>
            get() {
                val result = LinkedHashMap<String, IValue>()
                for ((colName, col) in columns) {
                    val v = col[nodeId] ?: continue
                    result[colName] = v
                }
                return result.entries
            }

        override fun get(key: String): IValue? = columns[key]?.get(nodeId)

        override fun containsKey(key: String): Boolean = columns[key]?.containsKey(nodeId) == true

        override val size: Int
            get() {
                var count = 0
                for (col in columns.values) {
                    if (col.containsKey(nodeId)) count++
                }
                return count
            }

        override fun isEmpty(): Boolean {
            for (col in columns.values) {
                if (col.containsKey(nodeId)) return false
            }
            return true
        }
    }
}
