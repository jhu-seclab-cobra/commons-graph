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
 * String-based keys throughout with columnar property storage.
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

    // Deduplicates property key strings across entities
    private val keyPool = HashMap<String, String>()

    // Columnar node property storage: one HashMap per property name (column)
    private val nodeColumns = HashMap<String, HashMap<String, IValue>>()

    // Edge endpoint index (edge ID -> src, dst, tag)
    private val edgeEndpoints = HashMap<String, IStorage.EdgeStructure>()

    // Columnar edge property storage
    private val edgeColumns = HashMap<String, HashMap<String, IValue>>()

    /**
     * Adjacency entry: mutable set for O(1) writes, cached snapshot for repeated reads.
     * Writers mutate the set and invalidate the snapshot. Readers return the cached snapshot
     * or lazily rebuild it. Under read lock the mutable set is stable, so the snapshot is safe
     * to construct and cache via a volatile write (benign race: concurrent readers may each
     * build an equivalent snapshot; one wins the volatile store).
     */
    private class AdjEntry {
        val set = HashSet<String>()

        @Volatile
        var cached: Set<String>? = emptySet()

        fun add(id: String) {
            set.add(id)
            cached = null
        }

        fun remove(id: String) {
            set.remove(id)
            cached = null
        }

        fun snapshot(): Set<String> {
            cached?.let { return it }
            val snap = java.util.Set.copyOf(set)
            cached = snap
            return snap
        }
    }

    // Adjacency lists with snapshot-on-demand
    private val outEdges = HashMap<String, AdjEntry>()
    private val inEdges = HashMap<String, AdjEntry>()

    // Metadata
    private val metaProperties = HashMap<String, IValue>()

    // Internal helpers (callers must hold appropriate lock)
    private fun internKey(key: String): String = keyPool.getOrPut(key) { key }

    private fun collectNodeProperties(id: String): Map<String, IValue> {
        val result = HashMap<String, IValue>()
        for ((colName, col) in nodeColumns) {
            val v = col[id] ?: continue
            result[colName] = v
        }
        return result
    }

    private fun collectEdgeProperties(id: String): Map<String, IValue> {
        val result = HashMap<String, IValue>()
        for ((colName, col) in edgeColumns) {
            val v = col[id] ?: continue
            result[colName] = v
        }
        return result
    }

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
                columns.getOrPut(internKey(key)) { HashMap() }[id] = value
            } else {
                val col = columns[key] ?: continue
                col.remove(id)
                if (col.isEmpty()) columns.remove(key)
            }
        }
    }

    private fun deleteIncidentEdge(eid: String) {
        val edge = edgeEndpoints[eid] ?: return
        inEdges[edge.dst]?.remove(eid)
        outEdges[edge.src]?.remove(eid)
        edgeEndpoints.remove(eid)
        removeEntityFromColumns(eid, edgeColumns)
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<String>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                java.util.Set.copyOf(outEdges.keys)
            }

    override fun containsNode(id: String): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            id in outEdges
        }

    override fun addNode(
        nodeId: String,
        properties: Map<String, IValue>,
    ): String =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (nodeId in outEdges) throw EntityAlreadyExistException(nodeId)
            outEdges[nodeId] = AdjEntry()
            inEdges[nodeId] = AdjEntry()
            for ((key, value) in properties) {
                nodeColumns.getOrPut(internKey(key)) { HashMap() }[nodeId] = value
            }
            nodeId
        }

    override fun getNodeProperties(id: String): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id)
            collectNodeProperties(id)
        }

    override fun getNodeProperty(
        id: String,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id)
            nodeColumns[name]?.get(id)
        }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in outEdges) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, nodeColumns)
    }

    override fun deleteNode(id: String): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id)
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

    override val edgeIDs: Set<String>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                java.util.Set.copyOf(edgeEndpoints.keys)
            }

    override fun containsEdge(id: String): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            id in edgeEndpoints
        }

    override fun addEdge(
        src: String,
        dst: String,
        edgeId: String,
        tag: String,
        properties: Map<String, IValue>,
    ): String =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (src !in outEdges) throw EntityNotExistException(src)
            if (dst !in outEdges) throw EntityNotExistException(dst)
            if (edgeId in edgeEndpoints) throw EntityAlreadyExistException(edgeId)
            edgeEndpoints[edgeId] = IStorage.EdgeStructure(src, dst, tag)
            outEdges[src]!!.add(edgeId)
            inEdges[dst]!!.add(edgeId)
            for ((key, value) in properties) {
                edgeColumns.getOrPut(internKey(key)) { HashMap() }[edgeId] = value
            }
            edgeId
        }

    override fun getEdgeStructure(id: String): IStorage.EdgeStructure =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            edgeEndpoints[id] ?: throw EntityNotExistException(id)
        }

    override fun getEdgeProperties(id: String): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeEndpoints) throw EntityNotExistException(id)
            collectEdgeProperties(id)
        }

    override fun getEdgeProperty(
        id: String,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeEndpoints) throw EntityNotExistException(id)
            edgeColumns[name]?.get(id)
        }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in edgeEndpoints) throw EntityNotExistException(id)
        setColumnarProperties(id, properties, edgeColumns)
    }

    override fun deleteEdge(id: String): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeEndpoints) throw EntityNotExistException(id)
            deleteIncidentEdge(id)
        }

    // ============================================================================
    // ADJACENCY QUERIES — cached immutable snapshots, rebuilt on demand
    // ============================================================================

    override fun getIncomingEdges(id: String): Set<String> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id)
            inEdges[id]?.snapshot() ?: emptySet()
        }

    override fun getOutgoingEdges(id: String): Set<String> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in outEdges) throw EntityNotExistException(id)
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
        }

    override fun transferTo(target: IStorage): Unit =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            for (nodeId in outEdges.keys) {
                target.addNode(nodeId, collectNodeProperties(nodeId))
            }
            for ((edgeId, ep) in edgeEndpoints) {
                target.addEdge(ep.src, ep.dst, edgeId, ep.tag, collectEdgeProperties(edgeId))
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
