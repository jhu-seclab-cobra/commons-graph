package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import java.util.Collections

/**
 * In-memory graph storage using Int-keyed HashMaps with auto-generated IDs.
 *
 * O(1) average time for all operations. Not thread-safe.
 * Uses split incoming/outgoing adjacency sets for directional edge queries.
 * Node and edge properties use columnar layout: one HashMap per property name.
 * Adjacency queries return unmodifiable views without allocation.
 *
 * @constructor Creates a new empty storage instance.
 * @see NativeConcurStorageImpl
 */
@Suppress("TooManyFunctions")
class NativeStorageImpl : IStorage {
    private var isClosed: Boolean = false

    // Auto-increment counters
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    // Columnar node property storage: one HashMap per property name (column)
    private val nodeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Columnar edge property storage
    private val edgeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Edge endpoint index (edge ID -> src, dst, tag)
    private val edgeEndpoints = HashMap<Int, IStorage.EdgeStructure>()

    // Adjacency lists (also serve as node existence index via outEdges.keys)
    private val outEdges = HashMap<Int, MutableSet<Int>>()
    private val inEdges = HashMap<Int, MutableSet<Int>>()

    // Metadata
    private val metaProperties = HashMap<String, IValue>()

    private fun ensureOpen() {
        if (isClosed) throw AccessClosedStorageException()
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() {
            ensureOpen()
            return Collections.unmodifiableSet(outEdges.keys)
        }

    override fun containsNode(id: Int): Boolean {
        ensureOpen()
        return id in outEdges
    }

    override fun addNode(properties: Map<String, IValue>): Int {
        ensureOpen()
        val id = nodeCounter++
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
        for ((key, value) in properties) {
            nodeColumns.getOrPut(key) { HashMap() }[id] = value
        }
        return id
    }

    override fun getNodeProperties(id: Int): Map<String, IValue> {
        ensureOpen()
        if (id !in outEdges) throw EntityNotExistException(id.toString())
        return ColumnViewMap(id, nodeColumns)
    }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? {
        ensureOpen()
        if (id !in outEdges) throw EntityNotExistException(id.toString())
        return nodeColumns[name]?.get(id)
    }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (id !in outEdges) throw EntityNotExistException(id.toString())
        setColumnarProperties(id, properties, nodeColumns)
    }

    override fun deleteNode(id: Int) {
        ensureOpen()
        val outSet = outEdges[id] ?: throw EntityNotExistException(id.toString())
        val outEdgeIds = outSet.toList()
        val inEdgeIds = inEdges[id]?.toList() ?: emptyList()
        for (eid in outEdgeIds) deleteIncidentEdge(eid)
        for (eid in inEdgeIds) deleteIncidentEdge(eid)
        outEdges.remove(id)
        inEdges.remove(id)
        removeEntityFromColumns(id, nodeColumns)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<Int>
        get() {
            ensureOpen()
            return Collections.unmodifiableSet(edgeEndpoints.keys)
        }

    override fun containsEdge(id: Int): Boolean {
        ensureOpen()
        return id in edgeEndpoints
    }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int {
        ensureOpen()
        val srcOut = outEdges[src] ?: throw EntityNotExistException(src.toString())
        val dstIn = inEdges[dst] ?: throw EntityNotExistException(dst.toString())
        val id = edgeCounter++
        edgeEndpoints[id] = IStorage.EdgeStructure(src, dst, tag)
        srcOut.add(id)
        dstIn.add(id)
        for ((key, value) in properties) {
            edgeColumns.getOrPut(key) { HashMap() }[id] = value
        }
        return id
    }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure {
        ensureOpen()
        return edgeEndpoints[id] ?: throw EntityNotExistException(id.toString())
    }

    override fun getEdgeProperties(id: Int): Map<String, IValue> {
        ensureOpen()
        if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
        return ColumnViewMap(id, edgeColumns)
    }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? {
        ensureOpen()
        if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
        return edgeColumns[name]?.get(id)
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
        setColumnarProperties(id, properties, edgeColumns)
    }

    override fun deleteEdge(id: Int) {
        ensureOpen()
        val edge = edgeEndpoints.remove(id) ?: throw EntityNotExistException(id.toString())
        outEdges[edge.src]?.remove(id)
        inEdges[edge.dst]?.remove(id)
        removeEntityFromColumns(id, edgeColumns)
    }

    private fun deleteIncidentEdge(eid: Int) {
        val edge = edgeEndpoints[eid] ?: return
        inEdges[edge.dst]?.remove(eid)
        outEdges[edge.src]?.remove(eid)
        edgeEndpoints.remove(eid)
        removeEntityFromColumns(eid, edgeColumns)
    }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> {
        ensureOpen()
        val edges = inEdges[id] ?: throw EntityNotExistException(id.toString())
        return Collections.unmodifiableSet(edges)
    }

    override fun getOutgoingEdges(id: Int): Set<Int> {
        ensureOpen()
        val edges = outEdges[id] ?: throw EntityNotExistException(id.toString())
        return Collections.unmodifiableSet(edges)
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
        if (value == null) metaProperties.remove(name) else metaProperties[name] = value
    }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun clear() {
        ensureOpen()
        outEdges.clear()
        inEdges.clear()
        edgeEndpoints.clear()
        edgeColumns.clear()
        nodeColumns.clear()
        metaProperties.clear()
        nodeCounter = 0
        edgeCounter = 0
    }

    override fun transferTo(target: IStorage): Map<Int, Int> {
        ensureOpen()
        val nodeIdMap = HashMap<Int, Int>()
        for (nodeId in outEdges.keys) {
            val newId = target.addNode(getNodeProperties(nodeId))
            nodeIdMap[nodeId] = newId
        }
        for ((edgeId, ep) in edgeEndpoints) {
            val newSrc = nodeIdMap[ep.src]!!
            val newDst = nodeIdMap[ep.dst]!!
            target.addEdge(newSrc, newDst, ep.tag, getEdgeProperties(edgeId))
        }
        for (name in metaProperties.keys) {
            target.setMeta(name, metaProperties[name])
        }
        return nodeIdMap
    }

    override fun close() {
        if (!isClosed) clear()
        isClosed = true
    }

    // ============================================================================
    // INTERNAL HELPERS
    // ============================================================================

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

    private class ColumnViewMap(
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
            if (cachedEntries != null) return cachedEntries!!.isEmpty()
            for (col in columns.values) {
                if (col.containsKey(entityId)) return false
            }
            return true
        }
    }
}
