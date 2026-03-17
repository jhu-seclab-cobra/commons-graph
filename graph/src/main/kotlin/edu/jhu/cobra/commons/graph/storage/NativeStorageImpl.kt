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
 * Node and edge properties use columnar layout: one HashMap per property name.
 *
 * @constructor Creates a new empty storage instance.
 * @see NativeConcurStorageImpl
 */
@Suppress("TooManyFunctions")
class NativeStorageImpl : IStorage {
    private var isClosed: Boolean = false
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    // String ↔ Int translation layers
    private val nodeStringToInt = HashMap<String, Int>()
    private val nodeIntToString = HashMap<Int, String>()
    private val edgeStringToInt = HashMap<String, Int>()
    private val edgeIntToString = HashMap<Int, String>()

    // Columnar node property storage: one HashMap per property name (column).
    // Reduces per-node object count from O(N) MutableMap instances to O(K) columns
    // where K = number of distinct property names, typically K << N.
    private val nodeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Edge endpoint index (edge ID → src, dst, type)
    private data class EdgeEndpoints(val src: Int, val dst: Int, val type: String)

    private val edgeEndpoints = HashMap<Int, EdgeEndpoints>()

    // Columnar edge property storage: same layout as nodeColumns.
    // Sparse edges (no properties) consume zero space in the columns.
    private val edgeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Adjacency lists
    private val outEdges = HashMap<Int, MutableSet<Int>>()
    private val inEdges = HashMap<Int, MutableSet<Int>>()

    // Metadata
    private val metaProperties = HashMap<String, IValue>()

    private fun hasNode(id: Int): Boolean = id in nodeIntToString

    private fun hasEdge(id: Int): Boolean = id in edgeEndpoints

    private fun ensureOpen() {
        if (isClosed) throw AccessClosedStorageException()
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

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<String>
        get() {
            ensureOpen()
            return nodeIntToString.values.toSet()
        }

    override fun containsNode(id: String): Boolean {
        ensureOpen()
        return id in nodeStringToInt
    }

    override fun addNode(nodeId: String, properties: Map<String, IValue>): String {
        ensureOpen()
        if (nodeId in nodeStringToInt) throw EntityAlreadyExistException(nodeId)
        val internalId = nodeCounter++
        nodeStringToInt[nodeId] = internalId
        nodeIntToString[internalId] = nodeId
        for ((key, value) in properties) {
            nodeColumns.getOrPut(key) { HashMap() }[internalId] = value
        }
        outEdges[internalId] = HashSet()
        inEdges[internalId] = HashSet()
        return nodeId
    }

    internal fun addNodeWithId(
        properties: Map<String, IValue>,
        id: Int,
    ): Int {
        ensureOpen()
        if (hasNode(id)) throw EntityAlreadyExistException(id)
        for ((key, value) in properties) {
            nodeColumns.getOrPut(key) { HashMap() }[id] = value
        }
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
        if (id >= nodeCounter) nodeCounter = id + 1
        return id
    }

    override fun getNodeProperties(id: String): Map<String, IValue> {
        ensureOpen()
        val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
        return ColumnViewMap(internalId, nodeColumns)
    }

    override fun getNodeProperty(
        id: String,
        name: String,
    ): IValue? {
        ensureOpen()
        val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
        return nodeColumns[name]?.get(internalId)
    }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
        setColumnarProperties(internalId, properties, nodeColumns)
    }

    override fun deleteNode(id: String) {
        ensureOpen()
        val internalId = nodeStringToInt.remove(id) ?: throw EntityNotExistException(id)
        nodeIntToString.remove(internalId)
        val outEdgeIds = outEdges[internalId]?.toList() ?: emptyList()
        val inEdgeIds = inEdges[internalId]?.toList() ?: emptyList()
        for (eid in outEdgeIds) {
            deleteIncidentEdge(eid)
        }
        for (eid in inEdgeIds) {
            deleteIncidentEdge(eid)
        }
        outEdges.remove(internalId)
        inEdges.remove(internalId)
        removeEntityFromColumns(internalId, nodeColumns)
    }

    private fun deleteIncidentEdge(eid: Int) {
        val edge = edgeEndpoints[eid] ?: return
        inEdges[edge.dst]?.remove(eid)
        outEdges[edge.src]?.remove(eid)
        edgeEndpoints.remove(eid)
        val edgeStringId = edgeIntToString.remove(eid)
        if (edgeStringId != null) {
            edgeStringToInt.remove(edgeStringId)
        }
        removeEntityFromColumns(eid, edgeColumns)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<String>
        get() {
            ensureOpen()
            return edgeIntToString.values.toSet()
        }

    override fun containsEdge(id: String): Boolean {
        ensureOpen()
        return id in edgeStringToInt
    }

    override fun addEdge(
        src: String,
        dst: String,
        edgeId: String,
        type: String,
        properties: Map<String, IValue>,
    ): String {
        ensureOpen()
        val srcInternal = nodeStringToInt[src] ?: throw EntityNotExistException(src)
        val dstInternal = nodeStringToInt[dst] ?: throw EntityNotExistException(dst)
        if (edgeId in edgeStringToInt) throw EntityAlreadyExistException(edgeId)
        val internalId = edgeCounter++
        edgeStringToInt[edgeId] = internalId
        edgeIntToString[internalId] = edgeId
        edgeEndpoints[internalId] = EdgeEndpoints(srcInternal, dstInternal, type)
        outEdges[srcInternal]!!.add(internalId)
        inEdges[dstInternal]!!.add(internalId)
        for ((key, value) in properties) {
            edgeColumns.getOrPut(key) { HashMap() }[internalId] = value
        }
        return edgeId
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
        for ((key, value) in properties) {
            edgeColumns.getOrPut(key) { HashMap() }[id] = value
        }
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

    // ============================================================================
    // INTERNAL METHODS USING INT IDS
    // ============================================================================

    internal fun getStringToIntNodeMapping(): Map<String, Int> = nodeStringToInt

    internal fun getIntToStringNodeMapping(): Map<Int, String> = nodeIntToString

    internal fun getStringToIntEdgeMapping(): Map<String, Int> = edgeStringToInt

    internal fun getIntToStringEdgeMapping(): Map<Int, String> = edgeIntToString

    internal fun getInternalNodeIDs(): Set<Int> = nodeIntToString.keys

    internal fun getInternalEdgeIDs(): Set<Int> = edgeEndpoints.keys

    internal fun getEdgeSrcInternal(id: Int): Int {
        ensureOpen()
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).src
    }

    internal fun getEdgeDstInternal(id: Int): Int {
        ensureOpen()
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).dst
    }

    internal fun getEdgeTypeInternal(id: Int): String {
        ensureOpen()
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).type
    }

    internal fun getIncomingEdgesInternal(id: Int): Set<Int> {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return inEdges[id] ?: emptySet()
    }

    internal fun getOutgoingEdgesInternal(id: Int): Set<Int> {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return outEdges[id] ?: emptySet()
    }

    internal fun getNodePropertiesInternal(id: Int): Map<String, IValue> {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return ColumnViewMap(id, nodeColumns)
    }

    internal fun getNodePropertyInternal(
        id: Int,
        name: String,
    ): IValue? {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        return nodeColumns[name]?.get(id)
    }

    internal fun getEdgePropertiesInternal(id: Int): Map<String, IValue> {
        ensureOpen()
        if (!hasEdge(id)) throw EntityNotExistException(id)
        return ColumnViewMap(id, edgeColumns)
    }

    internal fun getEdgePropertyInternal(
        id: Int,
        name: String,
    ): IValue? {
        ensureOpen()
        if (!hasEdge(id)) throw EntityNotExistException(id)
        return edgeColumns[name]?.get(id)
    }

    internal fun setNodePropertiesInternal(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (!hasNode(id)) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, nodeColumns)
    }

    internal fun setEdgePropertiesInternal(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (!hasEdge(id)) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, edgeColumns)
    }

    override fun getEdgeSrc(id: String): String {
        ensureOpen()
        val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
        val endpoint = edgeEndpoints[internalId] ?: throw EntityNotExistException(id)
        return nodeIntToString[endpoint.src] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeDst(id: String): String {
        ensureOpen()
        val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
        val endpoint = edgeEndpoints[internalId] ?: throw EntityNotExistException(id)
        return nodeIntToString[endpoint.dst] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeType(id: String): String {
        ensureOpen()
        val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
        return (edgeEndpoints[internalId] ?: throw EntityNotExistException(id)).type
    }

    override fun getEdgeProperties(id: String): Map<String, IValue> {
        ensureOpen()
        val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
        return ColumnViewMap(internalId, edgeColumns)
    }

    override fun getEdgeProperty(
        id: String,
        name: String,
    ): IValue? {
        ensureOpen()
        val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
        return edgeColumns[name]?.get(internalId)
    }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
        setColumnarProperties(internalId, properties, edgeColumns)
    }

    override fun deleteEdge(id: String) {
        ensureOpen()
        val internalId = edgeStringToInt.remove(id) ?: throw EntityNotExistException(id)
        edgeIntToString.remove(internalId)
        val edge = edgeEndpoints.remove(internalId) ?: throw EntityNotExistException(id)
        outEdges[edge.src]?.remove(internalId)
        inEdges[edge.dst]?.remove(internalId)
        removeEntityFromColumns(internalId, edgeColumns)
    }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: String): Set<String> {
        ensureOpen()
        val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
        val internalEdgeIds = inEdges[internalId] ?: emptySet()
        return internalEdgeIds.mapNotNull { edgeIntToString[it] }.toSet()
    }

    override fun getOutgoingEdges(id: String): Set<String> {
        ensureOpen()
        val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
        val internalEdgeIds = outEdges[internalId] ?: emptySet()
        return internalEdgeIds.mapNotNull { edgeIntToString[it] }.toSet()
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
        nodeStringToInt.clear()
        nodeIntToString.clear()
        edgeStringToInt.clear()
        edgeIntToString.clear()
        outEdges.clear()
        inEdges.clear()
        edgeEndpoints.clear()
        edgeColumns.clear()
        nodeColumns.clear()
        metaProperties.clear()
    }

    override fun transferTo(target: IStorage) {
        ensureOpen()
        for (nodeStringId in nodeIntToString.values) {
            target.addNode(nodeStringId, getNodeProperties(nodeStringId))
        }
        for (edgeStringId in edgeIntToString.values) {
            val srcString = getEdgeSrc(edgeStringId)
            val dstString = getEdgeDst(edgeStringId)
            val edgeType = getEdgeType(edgeStringId)
            target.addEdge(srcString, dstString, edgeStringId, edgeType, getEdgeProperties(edgeStringId))
        }
        for (name in metaProperties.keys) {
            target.setMeta(name, metaProperties[name])
        }
    }

    override fun close() {
        if (!isClosed) clear()
        isClosed = true
    }

    // ============================================================================
    // INTERNAL HELPERS
    // ============================================================================

    private fun setColumnarProperties(
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

    /**
     * Read-only view assembling a single entity's properties from columnar storage.
     * [get] is O(1); iteration scans all K columns (K = distinct property count).
     */
    private class ColumnViewMap(
        private val entityId: Int,
        private val columns: HashMap<String, HashMap<Int, IValue>>,
    ) : AbstractMap<String, IValue>() {
        override val entries: Set<Map.Entry<String, IValue>>
            get() {
                val result = LinkedHashMap<String, IValue>()
                for ((colName, col) in columns) {
                    val v = col[entityId] ?: continue
                    result[colName] = v
                }
                return result.entries
            }

        override fun get(key: String): IValue? = columns[key]?.get(entityId)

        override fun containsKey(key: String): Boolean = columns[key]?.containsKey(entityId) == true

        override val size: Int
            get() {
                var count = 0
                for (col in columns.values) {
                    if (col.containsKey(entityId)) count++
                }
                return count
            }

        override fun isEmpty(): Boolean {
            for (col in columns.values) {
                if (col.containsKey(entityId)) return false
            }
            return true
        }
    }
}
