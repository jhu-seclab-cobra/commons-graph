package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Thread-safe in-memory graph storage implementation using read-write locks.
 *
 * Provides concurrent access with multiple readers and single writer. Suitable for read-heavy workloads.
 * Uses internal non-locking helpers to avoid lock re-entrance overhead in compound operations.
 *
 * @constructor Creates a new empty thread-safe storage instance.
 * @see NativeStorageImpl
 */
class NativeConcurStorageImpl : IStorage {
    @Volatile
    private var isClosed: Boolean = false

    private val lock = ReentrantReadWriteLock()

    // Entity property stores — LinkedHashMap preserves insertion order for nodeIDs/edgeIDs
    private val nodeProperties: MutableMap<NodeID, MutableMap<String, IValue>> = mutableMapOf()
    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = mutableMapOf()
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()

    // Split adjacency indices: O(1) directional lookups without filtering
    private val outEdges = HashMap<NodeID, MutableSet<EdgeID>>()
    private val inEdges = HashMap<NodeID, MutableSet<EdgeID>>()

    // ============================================================================
    // INTERNAL HELPERS (no locking — callers must hold appropriate lock)
    // ============================================================================

    private fun hasNode(id: NodeID): Boolean = id in nodeProperties

    private fun hasEdge(id: EdgeID): Boolean = id in edgeProperties

    private fun removeEdgeInternal(id: EdgeID) {
        outEdges[id.srcNid]?.remove(id)
        inEdges[id.dstNid]?.remove(id)
        edgeProperties.remove(id)
    }

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                nodeProperties.keys
            }

    override val edgeIDs: Set<EdgeID>
        get() =
            lock.readLock().withLock {
                if (isClosed) throw AccessClosedStorageException()
                edgeProperties.keys
            }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            hasNode(id)
        }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (hasNode(id)) throw EntityAlreadyExistException(id = id)
        nodeProperties[id] = properties.toMutableMap()
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            nodeProperties[id] ?: throw EntityNotExistException(id = id)
        }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) ->
            if (value != null) container[key] = value else container.remove(key)
        }
    }

    override fun deleteNode(id: NodeID): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasNode(id)) throw EntityNotExistException(id)
            val connected = HashSet<EdgeID>(outEdges[id]!!.size + inEdges[id]!!.size)
            connected.addAll(outEdges[id]!!)
            connected.addAll(inEdges[id]!!)
            connected.forEach { removeEdgeInternal(it) }
            outEdges.remove(id)
            inEdges.remove(id)
            nodeProperties.remove(id)
        }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            hasEdge(id)
        }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (hasEdge(id)) throw EntityAlreadyExistException(id = id)
        if (!hasNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!hasNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        outEdges[id.srcNid]!!.add(id)
        inEdges[id.dstNid]!!.add(id)
        edgeProperties[id] = properties.toMutableMap()
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            edgeProperties[id] ?: throw EntityNotExistException(id = id)
        }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) ->
            if (value != null) container[key] = value else container.remove(key)
        }
    }

    override fun deleteEdge(id: EdgeID): Unit =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasEdge(id)) throw EntityNotExistException(id)
            removeEdgeInternal(id)
        }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> =
        lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            if (!hasNode(id)) throw EntityNotExistException(id)
            inEdges[id] ?: emptySet()
        }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> =
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
    // UTILITY OPERATIONS
    // ============================================================================

    override fun clear(): Boolean =
        lock.writeLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            outEdges.clear()
            inEdges.clear()
            edgeProperties.clear()
            nodeProperties.clear()
            metaProperties.clear()
            true
        }

    override fun close(): Unit =
        lock.writeLock().withLock {
            if (!isClosed) clear()
            isClosed = true
        }
}
