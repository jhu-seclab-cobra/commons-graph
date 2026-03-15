package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue

/**
 * In-memory graph storage using Int-keyed HashMaps.
 *
 * O(1) average time for all operations. Not thread-safe.
 * Uses split incoming/outgoing adjacency sets for directional edge queries.
 * Node properties use columnar layout: one HashMap per property name.
 *
 * @constructor Creates a new empty storage instance.
 * @see NativeConcurStorageImpl
 */
@Suppress("TooManyFunctions")
class NativeStorageImpl : IStorage {
    private var isClosed: Boolean = false
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    // Columnar node property storage: one HashMap per property name (column).
    // Reduces per-node object count from O(N) MutableMap instances to O(K) columns
    // where K = number of distinct property names, typically K << N.
    private val nodeSet = HashSet<Int>()
    private val nodeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Edge endpoint index (edge ID → src, dst, type)
    private data class EdgeEndpoints(val src: Int, val dst: Int, val type: String)

    private val edgeEndpoints = HashMap<Int, EdgeEndpoints>()
    private val edgeProperties = HashMap<Int, MutableMap<String, IValue>>()

    // Adjacency lists
    private val outEdges = HashMap<Int, MutableSet<Int>>()
    private val inEdges = HashMap<Int, MutableSet<Int>>()

    // Metadata
    private val metaProperties = HashMap<String, IValue>()

    private fun hasNode(id: Int): Boolean = id in nodeSet

    private fun hasEdge(id: Int): Boolean = id in edgeEndpoints

    private fun ensureOpen() {
        if (isClosed) throw AccessClosedStorageException()
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() {
            ensureOpen()
            return nodeSet
        }

    override fun containsNode(id: Int): Boolean {
        ensureOpen()
        return hasNode(id)
    }

    override fun addNode(properties: Map<String, IValue>): Int {
        ensureOpen()
        val nodeId = nodeCounter++
        nodeSet.add(nodeId)
        for ((key, value) in properties) {
            nodeColumns.getOrPut(key) { HashMap() }[nodeId] = value
        }
        outEdges[nodeId] = HashSet()
        inEdges[nodeId] = HashSet()
        return nodeId
    }

    internal fun addNodeWithId(
        properties: Map<String, IValue>,
        id: Int,
    ): Int {
        ensureOpen()
        if (hasNode(id)) throw EntityAlreadyExistException(id)
        nodeSet.add(id)
        for ((key, value) in properties) {
            nodeColumns.getOrPut(key) { HashMap() }[id] = value
        }
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
        if (id >= nodeCounter) nodeCounter = id + 1
        return id
    }

    override fun getNodeProperties(id: Int): Map<String, IValue> {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return ColumnViewMap(id, nodeColumns)
    }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return nodeColumns[name]?.get(id)
    }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
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

    override fun deleteNode(id: Int) {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        for (eid in outEdges[id]!!) {
            val edge = edgeEndpoints[eid]!!
            inEdges[edge.dst]?.remove(eid)
            edgeEndpoints.remove(eid)
            edgeProperties.remove(eid)
        }
        for (eid in inEdges[id]!!) {
            val edge = edgeEndpoints[eid]!!
            outEdges[edge.src]?.remove(eid)
            edgeEndpoints.remove(eid)
            edgeProperties.remove(eid)
        }
        outEdges.remove(id)
        inEdges.remove(id)
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

    override val edgeIDs: Set<Int>
        get() {
            ensureOpen()
            return edgeEndpoints.keys
        }

    override fun containsEdge(id: Int): Boolean {
        ensureOpen()
        return hasEdge(id)
    }

    override fun addEdge(
        src: Int,
        dst: Int,
        type: String,
        properties: Map<String, IValue>,
    ): Int {
        ensureOpen()
        if (!hasNode(src)) throw EntityNotExistException(src)
        if (!hasNode(dst)) throw EntityNotExistException(dst)
        val id = edgeCounter++
        edgeEndpoints[id] = EdgeEndpoints(src, dst, type)
        outEdges[src]!!.add(id)
        inEdges[dst]!!.add(id)
        edgeProperties[id] = properties.toMutableMap()
        return id
    }

    @Suppress("ThrowsCount")
    internal fun addEdgeWithId(
        src: Int,
        dst: Int,
        type: String,
        properties: Map<String, IValue>,
        id: Int,
    ): Int {
        ensureOpen()
        if (hasEdge(id)) throw EntityAlreadyExistException(id)
        if (!hasNode(src)) throw EntityNotExistException(src)
        if (!hasNode(dst)) throw EntityNotExistException(dst)
        edgeEndpoints[id] = EdgeEndpoints(src, dst, type)
        outEdges[src]!!.add(id)
        inEdges[dst]!!.add(id)
        edgeProperties[id] = properties.toMutableMap()
        if (id >= edgeCounter) edgeCounter = id + 1
        return id
    }

    internal fun setCounterStart(
        nodeStart: Int,
        edgeStart: Int,
    ) {
        if (nodeStart > nodeCounter) nodeCounter = nodeStart
        if (edgeStart > edgeCounter) edgeCounter = edgeStart
    }

    override fun getEdgeSrc(id: Int): Int {
        ensureOpen()
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).src
    }

    override fun getEdgeDst(id: Int): Int {
        ensureOpen()
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).dst
    }

    override fun getEdgeType(id: Int): String {
        ensureOpen()
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).type
    }

    override fun getEdgeProperties(id: Int): Map<String, IValue> {
        ensureOpen()
        return edgeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? {
        ensureOpen()
        return (edgeProperties[id] ?: throw EntityNotExistException(id))[name]
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (key, value) ->
            if (value != null) container[key] = value else container.remove(key)
        }
    }

    override fun deleteEdge(id: Int) {
        ensureOpen()
        val edge = edgeEndpoints.remove(id) ?: throw EntityNotExistException(id)
        outEdges[edge.src]?.remove(id)
        inEdges[edge.dst]?.remove(id)
        edgeProperties.remove(id)
    }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return inEdges[id] ?: emptySet()
    }

    override fun getOutgoingEdges(id: Int): Set<Int> {
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
    // LIFECYCLE
    // ============================================================================

    override fun clear() {
        ensureOpen()
        nodeCounter = 0
        edgeCounter = 0
        outEdges.clear()
        inEdges.clear()
        edgeEndpoints.clear()
        edgeProperties.clear()
        nodeSet.clear()
        nodeColumns.clear()
        metaProperties.clear()
    }

    override fun transferTo(target: IStorage) {
        ensureOpen()
        val idMap = HashMap<Int, Int>()
        for (nodeId in nodeSet) {
            idMap[nodeId] = target.addNode(getNodeProperties(nodeId))
        }
        for (edgeId in edgeEndpoints.keys) {
            val edge = edgeEndpoints[edgeId]!!
            val newSrc = idMap[edge.src] ?: edge.src
            val newDst = idMap[edge.dst] ?: edge.dst
            target.addEdge(newSrc, newDst, edge.type, edgeProperties[edgeId]!!)
        }
        for (name in metaProperties.keys) {
            target.setMeta(name, metaProperties[name])
        }
    }

    override fun close() {
        if (!isClosed) clear()
        isClosed = true
    }

    /**
     * Read-only view assembling a single node's properties from columnar storage.
     * [get] is O(1); iteration scans all K columns (K = distinct property count).
     */
    private class ColumnViewMap(
        private val nodeId: Int,
        private val columns: HashMap<String, HashMap<Int, IValue>>,
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
