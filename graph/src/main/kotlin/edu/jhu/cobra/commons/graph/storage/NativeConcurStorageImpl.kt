package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Thread-safe in-memory graph storage using read-write locks.
 *
 * Concurrent reads, exclusive writes. Suitable for read-heavy workloads.
 * Uses internal non-locking helpers to avoid lock re-entrance overhead.
 * String-based public API with internal Int indexing for compactness.
 *
 * @constructor Creates a new empty thread-safe storage instance.
 * @see NativeStorageImpl
 */
@Suppress("TooManyFunctions")
class NativeConcurStorageImpl : IStorage {
    @Volatile
    private var isClosed: Boolean = false
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    private val lock = ReentrantReadWriteLock()

    // String ↔ Int translation layers
    private val nodeStringToInt = HashMap<String, Int>()
    private val nodeIntToString = HashMap<Int, String>()
    private val edgeStringToInt = HashMap<String, Int>()
    private val edgeIntToString = HashMap<Int, String>()

    // Deduplicates property key strings across entities
    private val keyPool = HashMap<String, String>()

    // Node set: tracks allocated node IDs
    private val nodeSet = HashSet<Int>()

    // Node properties: columnar layout (one HashMap per property name)
    private val nodeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Edge endpoint index (edge ID → src, dst, type)
    private data class EdgeEndpoints(
        val src: Int,
        val dst: Int,
        val type: String,
    )

    private val edgeEndpoints = HashMap<Int, EdgeEndpoints>()

    // Columnar edge property storage: one HashMap per property name (column).
    // Sparse edges (no properties) consume zero space in the columns.
    private val edgeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Adjacency lists
    private val outEdges = HashMap<Int, MutableSet<Int>>()
    private val inEdges = HashMap<Int, MutableSet<Int>>()

    // Metadata
    private val metaProperties = HashMap<String, IValue>()

    // Internal helpers (callers must hold appropriate lock)
    private fun hasNode(id: Int): Boolean = id in nodeSet

    private fun hasEdge(id: Int): Boolean = id in edgeEndpoints

    private fun internKey(key: String): String = keyPool.getOrPut(key) { key }

    private fun internKeys(props: Map<String, IValue>): Map<String, IValue> {
        val result = HashMap<String, IValue>(props.size)
        for ((k, v) in props) result[internKey(k)] = v
        return result
    }

    private fun collectNodeProperties(id: Int): Map<String, IValue> {
        val result = HashMap<String, IValue>()
        for ((colName, col) in nodeColumns) {
            val v = col[id] ?: continue
            result[colName] = v
        }
        return result
    }

    private fun collectEdgeProperties(id: Int): Map<String, IValue> {
        val result = HashMap<String, IValue>()
        for ((colName, col) in edgeColumns) {
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

    private fun setColumnarProperties(
        id: Int,
        properties: Map<String, IValue?>,
        columns: HashMap<String, HashMap<Int, IValue>>,
    ) {
        for ((key, value) in properties) {
            if (value != null) {
                columns.getOrPut(internKey(key)) { HashMap() }[id] = value
            } else {
                val col = columns[key] ?: continue
                col.remove(id)
                if (col.isEmpty()) columns.remove(key)
            }
        }
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
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<String>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                nodeIntToString.values.toSet()
            }

    override fun containsNode(id: String): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            id in nodeStringToInt
        }

    override fun addNode(nodeId: String, properties: Map<String, IValue>): String =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (nodeId in nodeStringToInt) throw EntityAlreadyExistException(nodeId)
            val internalId = nodeCounter++
            nodeStringToInt[nodeId] = internalId
            nodeIntToString[internalId] = nodeId
            nodeSet.add(internalId)
            for ((key, value) in internKeys(properties)) {
                nodeColumns.getOrPut(key) { HashMap() }[internalId] = value
            }
            outEdges[internalId] = HashSet()
            inEdges[internalId] = HashSet()
            nodeId
        }

    override fun getNodeProperties(id: String): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
            collectNodeProperties(internalId)
        }

    override fun getNodeProperty(
        id: String,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
            nodeColumns[name]?.get(internalId)
        }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
        setColumnarProperties(internalId, properties, nodeColumns)
    }

    override fun deleteNode(id: String): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
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
            nodeSet.remove(internalId)
        }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<String>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                edgeIntToString.values.toSet()
            }

    override fun containsEdge(id: String): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            id in edgeStringToInt
        }

    override fun addEdge(
        src: String,
        dst: String,
        edgeId: String,
        type: String,
        properties: Map<String, IValue>,
    ): String =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val srcInternal = nodeStringToInt[src] ?: throw EntityNotExistException(src)
            val dstInternal = nodeStringToInt[dst] ?: throw EntityNotExistException(dst)
            if (edgeId in edgeStringToInt) throw EntityAlreadyExistException(edgeId)
            val internalId = edgeCounter++
            edgeStringToInt[edgeId] = internalId
            edgeIntToString[internalId] = edgeId
            edgeEndpoints[internalId] = EdgeEndpoints(srcInternal, dstInternal, type)
            outEdges[srcInternal]!!.add(internalId)
            inEdges[dstInternal]!!.add(internalId)
            for ((key, value) in internKeys(properties)) {
                edgeColumns.getOrPut(key) { HashMap() }[internalId] = value
            }
            edgeId
        }

    override fun getEdgeSrc(id: String): String =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
            val srcInternal = (edgeEndpoints[internalId] ?: throw EntityNotExistException(id)).src
            nodeIntToString[srcInternal] ?: throw EntityNotExistException(id)
        }

    override fun getEdgeDst(id: String): String =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
            val dstInternal = (edgeEndpoints[internalId] ?: throw EntityNotExistException(id)).dst
            nodeIntToString[dstInternal] ?: throw EntityNotExistException(id)
        }

    override fun getEdgeType(id: String): String =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
            (edgeEndpoints[internalId] ?: throw EntityNotExistException(id)).type
        }

    override fun getEdgeProperties(id: String): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
            collectEdgeProperties(internalId)
        }

    override fun getEdgeProperty(
        id: String,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
            edgeColumns[name]?.get(internalId)
        }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        val internalId = edgeStringToInt[id] ?: throw EntityNotExistException(id)
        setColumnarProperties(internalId, properties, edgeColumns)
    }

    override fun deleteEdge(id: String): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = edgeStringToInt.remove(id) ?: throw EntityNotExistException(id)
            deleteIncidentEdge(internalId)
        }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: String): Set<String> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
            if (!hasNode(internalId)) throw EntityNotExistException(id)
            val internalEdgeIds = inEdges[internalId] ?: emptySet()
            internalEdgeIds.mapNotNull { edgeIntToString[it] }.toSet()
        }

    override fun getOutgoingEdges(id: String): Set<String> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val internalId = nodeStringToInt[id] ?: throw EntityNotExistException(id)
            if (!hasNode(internalId)) throw EntityNotExistException(id)
            val internalEdgeIds = outEdges[internalId] ?: emptySet()
            internalEdgeIds.mapNotNull { edgeIntToString[it] }.toSet()
        }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                metaProperties.keys.toSet()
            }

    override fun getMeta(name: String): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            metaProperties[name]
        }

    override fun setMeta(
        name: String,
        value: IValue?,
    ): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (value == null) {
                metaProperties.remove(name)
            } else {
                metaProperties[name] = value
            }
        }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun clear(): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
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
            nodeSet.clear()
            nodeColumns.clear()
            metaProperties.clear()
            keyPool.clear()
        }

    override fun transferTo(target: IStorage): Unit =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            for (nodeStringId in nodeIntToString.values) {
                val props = getNodeProperties(nodeStringId)
                target.addNode(nodeStringId, props)
            }
            for (edgeStringId in edgeIntToString.values) {
                val srcString = getEdgeSrc(edgeStringId)
                val dstString = getEdgeDst(edgeStringId)
                val edgeType = getEdgeType(edgeStringId)
                val edgeProps = getEdgeProperties(edgeStringId)
                target.addEdge(srcString, dstString, edgeStringId, edgeType, edgeProps)
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
        }

    override fun close(): Unit =
        lock.writeLock().withLock {
            if (!isClosed) clear()
            isClosed = true
        }

    // ============================================================================
    // INTERNAL METHODS USING INT IDS (backward compatibility for AbcNode/AbcEdge)
    // ============================================================================

    internal fun getStringToIntNodeMapping(): Map<String, Int> = nodeStringToInt

    internal fun getIntToStringNodeMapping(): Map<Int, String> = nodeIntToString

    internal fun getInternalNodeIDs(): Set<Int> = nodeSet

    internal fun getInternalEdgeIDs(): Set<Int> = edgeEndpoints.keys

    internal fun getEdgeSrcInternal(id: Int): Int {
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).src
    }

    internal fun getEdgeDstInternal(id: Int): Int {
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).dst
    }

    internal fun getEdgeTypeInternal(id: Int): String {
        return (edgeEndpoints[id] ?: throw EntityNotExistException(id)).type
    }

    internal fun getIncomingEdgesInternal(id: Int): Set<Int> {
        if (!hasNode(id)) throw EntityNotExistException(id)
        return inEdges[id] ?: emptySet()
    }

    internal fun getOutgoingEdgesInternal(id: Int): Set<Int> {
        if (!hasNode(id)) throw EntityNotExistException(id)
        return outEdges[id] ?: emptySet()
    }

    internal fun getNodePropertyInternal(
        id: Int,
        name: String,
    ): IValue? {
        if (!hasNode(id)) throw EntityNotExistException(id)
        return nodeColumns[name]?.get(id)
    }

    internal fun getNodePropertiesInternal(id: Int): Map<String, IValue> {
        if (!hasNode(id)) throw EntityNotExistException(id)
        return collectNodeProperties(id)
    }

    internal fun setNodePropertiesInternal(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        if (!hasNode(id)) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, nodeColumns)
    }

    internal fun getEdgePropertyInternal(
        id: Int,
        name: String,
    ): IValue? {
        if (!hasEdge(id)) throw EntityNotExistException(id)
        return edgeColumns[name]?.get(id)
    }

    internal fun getEdgePropertiesInternal(id: Int): Map<String, IValue> {
        if (!hasEdge(id)) throw EntityNotExistException(id)
        return collectEdgeProperties(id)
    }

    internal fun setEdgePropertiesInternal(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        if (!hasEdge(id)) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, edgeColumns)
    }
}
