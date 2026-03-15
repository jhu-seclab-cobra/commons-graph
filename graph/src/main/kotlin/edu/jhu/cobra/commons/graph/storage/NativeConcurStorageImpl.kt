package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Thread-safe in-memory graph storage using read-write locks.
 *
 * Concurrent reads, exclusive writes. Suitable for read-heavy workloads.
 * Uses internal non-locking helpers to avoid lock re-entrance overhead.
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

    // Deduplicates property key strings across entities
    private val keyPool = HashMap<String, String>()

    // Node: id → properties
    private val nodeProperties = HashMap<Int, MutableMap<String, IValue>>()

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
    private fun hasNode(id: Int): Boolean = id in nodeProperties

    private fun hasEdge(id: Int): Boolean = id in edgeEndpoints

    private fun internKey(key: String): String = keyPool.getOrPut(key) { key }

    private fun internKeys(props: Map<String, IValue>): MutableMap<String, IValue> {
        val result = HashMap<String, IValue>(props.size)
        for ((k, v) in props) result[internKey(k)] = v
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

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                nodeProperties.keys
            }

    override fun containsNode(id: Int): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            hasNode(id)
        }

    override fun addNode(properties: Map<String, IValue>): Int =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val nodeId = nodeCounter++
            nodeProperties[nodeId] = internKeys(properties)
            outEdges[nodeId] = HashSet()
            inEdges[nodeId] = HashSet()
            nodeId
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            nodeProperties[id] ?: throw EntityNotExistException(id)
        }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            (nodeProperties[id] ?: throw EntityNotExistException(id))[name]
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (key, value) ->
            if (value != null) container[internKey(key)] = value else container.remove(key)
        }
    }

    override fun deleteNode(id: Int): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasNode(id)) throw EntityNotExistException(id)
            for (eid in outEdges[id]!!) {
                val edge = edgeEndpoints[eid]!!
                inEdges[edge.dst]?.remove(eid)
                edgeEndpoints.remove(eid)
                removeEntityFromColumns(eid, edgeColumns)
            }
            for (eid in inEdges[id]!!) {
                val edge = edgeEndpoints[eid]!!
                outEdges[edge.src]?.remove(eid)
                edgeEndpoints.remove(eid)
                removeEntityFromColumns(eid, edgeColumns)
            }
            outEdges.remove(id)
            inEdges.remove(id)
            nodeProperties.remove(id)
        }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<Int>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                edgeEndpoints.keys
            }

    override fun containsEdge(id: Int): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            hasEdge(id)
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        type: String,
        properties: Map<String, IValue>,
    ): Int =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasNode(src)) throw EntityNotExistException(src)
            if (!hasNode(dst)) throw EntityNotExistException(dst)
            val id = edgeCounter++
            edgeEndpoints[id] = EdgeEndpoints(src, dst, type)
            outEdges[src]!!.add(id)
            inEdges[dst]!!.add(id)
            for ((key, value) in properties) {
                edgeColumns.getOrPut(internKey(key)) { HashMap() }[id] = value
            }
            id
        }

    override fun getEdgeSrc(id: Int): Int =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            (edgeEndpoints[id] ?: throw EntityNotExistException(id)).src
        }

    override fun getEdgeDst(id: Int): Int =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            (edgeEndpoints[id] ?: throw EntityNotExistException(id)).dst
        }

    override fun getEdgeType(id: Int): String =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            (edgeEndpoints[id] ?: throw EntityNotExistException(id)).type
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasEdge(id)) throw EntityNotExistException(id)
            collectEdgeProperties(id)
        }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasEdge(id)) throw EntityNotExistException(id)
            edgeColumns[name]?.get(id)
        }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (!hasEdge(id)) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, edgeColumns)
    }

    override fun deleteEdge(id: Int): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val edge = edgeEndpoints.remove(id) ?: throw EntityNotExistException(id)
            outEdges[edge.src]?.remove(id)
            inEdges[edge.dst]?.remove(id)
            removeEntityFromColumns(id, edgeColumns)
        }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasNode(id)) throw EntityNotExistException(id)
            inEdges[id] ?: emptySet()
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasNode(id)) throw EntityNotExistException(id)
            outEdges[id] ?: emptySet()
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
            outEdges.clear()
            inEdges.clear()
            edgeEndpoints.clear()
            edgeColumns.clear()
            nodeProperties.clear()
            metaProperties.clear()
            keyPool.clear()
        }

    override fun transferTo(target: IStorage): Unit =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val idMap = HashMap<Int, Int>()
            for (nodeId in nodeProperties.keys) {
                idMap[nodeId] = target.addNode(nodeProperties[nodeId]!!)
            }
            for (edgeId in edgeEndpoints.keys) {
                val edge = edgeEndpoints[edgeId]!!
                val newSrc = idMap[edge.src] ?: edge.src
                val newDst = idMap[edge.dst] ?: edge.dst
                target.addEdge(newSrc, newDst, edge.type, collectEdgeProperties(edgeId))
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
}
