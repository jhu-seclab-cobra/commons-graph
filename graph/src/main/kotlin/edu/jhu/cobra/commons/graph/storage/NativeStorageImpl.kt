package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import java.util.Collections

/**
 * In-memory graph storage using String-keyed HashMaps.
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

    // Columnar node property storage: one HashMap per property name (column)
    private val nodeColumns = HashMap<String, HashMap<String, IValue>>()

    // Columnar edge property storage
    private val edgeColumns = HashMap<String, HashMap<String, IValue>>()

    // Edge endpoint index (edge ID -> src, dst, tag)
    private val edgeEndpoints = HashMap<String, IStorage.EdgeStructure>()

    // Adjacency lists (also serve as node existence index via outEdges.keys)
    private val outEdges = HashMap<String, MutableSet<String>>()
    private val inEdges = HashMap<String, MutableSet<String>>()

    // Metadata
    private val metaProperties = HashMap<String, IValue>()

    private fun ensureOpen() {
        if (isClosed) throw AccessClosedStorageException()
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<String>
        get() {
            ensureOpen()
            return Collections.unmodifiableSet(outEdges.keys)
        }

    override fun containsNode(id: String): Boolean {
        ensureOpen()
        return id in outEdges
    }

    override fun addNode(
        nodeId: String,
        properties: Map<String, IValue>,
    ): String {
        ensureOpen()
        if (nodeId in outEdges) throw EntityAlreadyExistException(nodeId)
        outEdges[nodeId] = HashSet()
        inEdges[nodeId] = HashSet()
        for ((key, value) in properties) {
            nodeColumns.getOrPut(key) { HashMap() }[nodeId] = value
        }
        return nodeId
    }

    override fun getNodeProperties(id: String): Map<String, IValue> {
        ensureOpen()
        if (id !in outEdges) throw EntityNotExistException(id)
        return ColumnViewMap(id, nodeColumns)
    }

    override fun getNodeProperty(
        id: String,
        name: String,
    ): IValue? {
        ensureOpen()
        if (id !in outEdges) throw EntityNotExistException(id)
        return nodeColumns[name]?.get(id)
    }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (id !in outEdges) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, nodeColumns)
    }

    override fun deleteNode(id: String) {
        ensureOpen()
        val outSet = outEdges[id] ?: throw EntityNotExistException(id)
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

    override val edgeIDs: Set<String>
        get() {
            ensureOpen()
            return Collections.unmodifiableSet(edgeEndpoints.keys)
        }

    override fun containsEdge(id: String): Boolean {
        ensureOpen()
        return id in edgeEndpoints
    }

    override fun addEdge(
        src: String,
        dst: String,
        edgeId: String,
        tag: String,
        properties: Map<String, IValue>,
    ): String {
        ensureOpen()
        val srcOut = outEdges[src] ?: throw EntityNotExistException(src)
        val dstIn = inEdges[dst] ?: throw EntityNotExistException(dst)
        if (edgeId in edgeEndpoints) throw EntityAlreadyExistException(edgeId)
        edgeEndpoints[edgeId] = IStorage.EdgeStructure(src, dst, tag)
        srcOut.add(edgeId)
        dstIn.add(edgeId)
        for ((key, value) in properties) {
            edgeColumns.getOrPut(key) { HashMap() }[edgeId] = value
        }
        return edgeId
    }

    override fun getEdgeStructure(id: String): IStorage.EdgeStructure {
        ensureOpen()
        return edgeEndpoints[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeProperties(id: String): Map<String, IValue> {
        ensureOpen()
        if (id !in edgeEndpoints) throw EntityNotExistException(id)
        return ColumnViewMap(id, edgeColumns)
    }

    override fun getEdgeProperty(
        id: String,
        name: String,
    ): IValue? {
        ensureOpen()
        if (id !in edgeEndpoints) throw EntityNotExistException(id)
        return edgeColumns[name]?.get(id)
    }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        if (id !in edgeEndpoints) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, edgeColumns)
    }

    override fun deleteEdge(id: String) {
        ensureOpen()
        val edge = edgeEndpoints.remove(id) ?: throw EntityNotExistException(id)
        outEdges[edge.src]?.remove(id)
        inEdges[edge.dst]?.remove(id)
        removeEntityFromColumns(id, edgeColumns)
    }

    private fun deleteIncidentEdge(eid: String) {
        val edge = edgeEndpoints[eid] ?: return
        inEdges[edge.dst]?.remove(eid)
        outEdges[edge.src]?.remove(eid)
        edgeEndpoints.remove(eid)
        removeEntityFromColumns(eid, edgeColumns)
    }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: String): Set<String> {
        ensureOpen()
        val edges = inEdges[id] ?: throw EntityNotExistException(id)
        return Collections.unmodifiableSet(edges)
    }

    override fun getOutgoingEdges(id: String): Set<String> {
        ensureOpen()
        val edges = outEdges[id] ?: throw EntityNotExistException(id)
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
    }

    override fun transferTo(target: IStorage) {
        ensureOpen()
        for (nodeId in outEdges.keys) {
            target.addNode(nodeId, getNodeProperties(nodeId))
        }
        for ((edgeId, ep) in edgeEndpoints) {
            target.addEdge(ep.src, ep.dst, edgeId, ep.tag, getEdgeProperties(edgeId))
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

    private fun removeEntityFromColumns(
        id: String,
        columns: HashMap<String, HashMap<String, IValue>>,
    ) {
        val colIter = columns.values.iterator()
        while (colIter.hasNext()) {
            val col = colIter.next()
            col.remove(id)
            if (col.isEmpty()) colIter.remove()
        }
    }

    private fun setColumnarProperties(
        id: String,
        properties: Map<String, IValue?>,
        columns: HashMap<String, HashMap<String, IValue>>,
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
        private val entityId: String,
        private val columns: HashMap<String, HashMap<String, IValue>>,
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
