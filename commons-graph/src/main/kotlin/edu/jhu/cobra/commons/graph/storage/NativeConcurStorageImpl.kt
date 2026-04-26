package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Thread-safe in-memory graph storage using read-write locks with auto-generated Int IDs.
 *
 * Concurrent reads, exclusive writes. Suitable for read-heavy workloads.
 * Uses internal non-locking helpers to avoid lock re-entrance overhead.
 * Int-based keys throughout with columnar property storage.
 * Adjacency uses snapshot-on-demand: mutable sets for O(1) writes,
 * cached immutable snapshots for O(1) repeated reads (invalidated on write).
 *
 * @constructor Creates a new empty thread-safe storage instance.
 * @see NativeStorageImpl
 */
@Suppress("TooManyFunctions")
class NativeConcurStorageImpl : IStorage {
    @Volatile
    private var isClosed: Boolean = false

    private val lock = ReentrantReadWriteLock()

    // Auto-increment counters (protected by write lock)
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    // Deduplicates property key strings across entities
    private val keyPool = HashMap<String, String>()

    // Columnar node property storage: one HashMap per property name (column)
    private val nodeColumns = HashMap<String, HashMap<Int, IValue>>()

    // Edge endpoint index (edge ID -> src, dst, tag)
    private val edgeEndpoints = HashMap<Int, IStorage.EdgeStructure>()

    // Columnar edge property storage
    private val edgeColumns = HashMap<String, HashMap<Int, IValue>>()

    /**
     * Adjacency entry: mutable set for O(1) writes, cached snapshot for repeated reads.
     * Writers mutate the set and invalidate the snapshot. Readers return the cached snapshot
     * or lazily rebuild it. Under read lock the mutable set is stable, so the snapshot is safe
     * to construct and cache via a volatile write (benign race: concurrent readers may each
     * build an equivalent snapshot; one wins the volatile store).
     */
    private class AdjEntry {
        val set = HashSet<Int>()

        @Volatile
        var cached: Set<Int>? = emptySet()

        fun add(id: Int) {
            set.add(id)
            cached = null
        }

        fun remove(id: Int) {
            set.remove(id)
            cached = null
        }

        fun snapshot(): Set<Int> {
            cached?.let { return it }
            val snap = java.util.Set.copyOf(set)
            cached = snap
            return snap
        }
    }

    // Adjacency lists with snapshot-on-demand
    private val outEdges = HashMap<Int, AdjEntry>()
    private val inEdges = HashMap<Int, AdjEntry>()

    // Metadata
    private val metaProperties = HashMap<String, IValue>()

    // Internal helpers (callers must hold appropriate lock)
    private fun internKey(key: String): String = keyPool.getOrPut(key) { key }

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
    ) = ColumnarUtils.removeEntityFromColumns(id, columns)

    private fun setColumnarProperties(
        id: Int,
        properties: Map<String, IValue?>,
        columns: HashMap<String, HashMap<Int, IValue>>,
    ) = ColumnarUtils.setColumnarProperties(id, properties, columns, ::internKey)

    private fun deleteIncidentEdge(eid: Int) {
        val edge = edgeEndpoints[eid] ?: return
        inEdges[edge.dst]?.remove(eid)
        outEdges[edge.src]?.remove(eid)
        edgeEndpoints.remove(eid)
        removeEntityFromColumns(eid, edgeColumns)
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                java.util.Set.copyOf(outEdges.keys)
            }

    override fun containsNode(id: Int): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            id in outEdges
        }

    override fun addNode(properties: Map<String, IValue>): Int =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val id = nodeCounter++
            outEdges[id] = AdjEntry()
            inEdges[id] = AdjEntry()
            for ((key, value) in properties) {
                nodeColumns.getOrPut(internKey(key)) { HashMap() }[id] = value
            }
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id.toString())
            collectNodeProperties(id)
        }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id.toString())
            nodeColumns[name]?.get(id)
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in outEdges) throw EntityNotExistException(id.toString())
        setColumnarProperties(id, properties, nodeColumns)
    }

    override fun deleteNode(id: Int): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id.toString())
            val outEdgeIds = outEdges[id]?.set?.toList() ?: emptyList()
            val inEdgeIds = inEdges[id]?.set?.toList() ?: emptyList()
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
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                java.util.Set.copyOf(edgeEndpoints.keys)
            }

    override fun containsEdge(id: Int): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            id in edgeEndpoints
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (src !in outEdges) throw EntityNotExistException(src.toString())
            if (dst !in outEdges) throw EntityNotExistException(dst.toString())
            val id = edgeCounter++
            edgeEndpoints[id] = IStorage.EdgeStructure(src, dst, tag)
            outEdges[src]!!.add(id)
            inEdges[dst]!!.add(id)
            for ((key, value) in properties) {
                edgeColumns.getOrPut(internKey(key)) { HashMap() }[id] = value
            }
            id
        }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            edgeEndpoints[id] ?: throw EntityNotExistException(id.toString())
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
            collectEdgeProperties(id)
        }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
            edgeColumns[name]?.get(id)
        }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
        setColumnarProperties(id, properties, edgeColumns)
    }

    override fun deleteEdge(id: Int): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
            deleteIncidentEdge(id)
        }

    // ============================================================================
    // ADJACENCY QUERIES — cached immutable snapshots, rebuilt on demand
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id.toString())
            inEdges[id]?.snapshot() ?: emptySet()
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id.toString())
            outEdges[id]?.snapshot() ?: emptySet()
        }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                java.util.Set.copyOf(metaProperties.keys)
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
            if (value == null) metaProperties.remove(name) else metaProperties[name] = value
        }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun clear(): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            outEdges.clear()
            inEdges.clear()
            edgeEndpoints.clear()
            edgeColumns.clear()
            nodeColumns.clear()
            metaProperties.clear()
            keyPool.clear()
            nodeCounter = 0
            edgeCounter = 0
        }

    override fun transferTo(target: IStorage): Map<Int, Int> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            val nodeIdMap = HashMap<Int, Int>()
            for (nodeId in outEdges.keys) {
                val newId = target.addNode(collectNodeProperties(nodeId))
                nodeIdMap[nodeId] = newId
            }
            for ((edgeId, ep) in edgeEndpoints) {
                val newSrc = nodeIdMap[ep.src]!!
                val newDst = nodeIdMap[ep.dst]!!
                target.addEdge(newSrc, newDst, ep.tag, collectEdgeProperties(edgeId))
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
            nodeIdMap
        }

    override fun close(): Unit =
        lock.writeLock().withLock {
            if (!isClosed) clear()
            isClosed = true
        }
}
