package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.ReadWriteLock
import kotlin.concurrent.withLock

/**
 * Thread-safe in-memory graph storage implementation using read-write locks.
 *
 * Provides concurrent access with multiple readers and single writer. Suitable for read-heavy workloads.
 *
 * @constructor Creates a new empty thread-safe storage instance.
 * @see NativeStorageImpl
 */
class NativeConcurStorageImpl : IStorage {

    // ============================================================================
    // STATE MANAGEMENT
    // ============================================================================

    /**
     * Indicates whether the storage has been closed.
     */
    @Volatile
    private var isClosed: Boolean = false

    /**
     * Read-write lock for concurrent access control.
     */
    private val lock: ReadWriteLock = ReentrantReadWriteLock()

    // ============================================================================
    // STORAGE STRUCTURES
    // ============================================================================

    /**
     * Maps node IDs to their property dictionaries.
     */
    private val nodeProperties: MutableMap<NodeID, MutableMap<String, IValue>> = mutableMapOf()

    /**
     * Maps edge IDs to their property dictionaries.
     */
    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = mutableMapOf()

    /**
     * Maps metadata keys to their values.
     */
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()

    // ============================================================================
    // GRAPH STRUCTURE
    // ============================================================================

    /**
     * Adjacency list representation for graph structure.
     */
    private val graphStructure: MutableMap<NodeID, Set<EdgeID>> = mutableMapOf()

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID> 
        get() = lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            nodeProperties.keys
        }

    override val edgeIDs: Set<EdgeID> 
        get() = lock.readLock().withLock {
            if (isClosed) throw AccessClosedStorageException()
            edgeProperties.keys
        }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean = lock.readLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        id in nodeProperties
    }

    override fun addNode(id: NodeID, properties: Map<String, IValue>) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (containsNode(id)) throw EntityAlreadyExistException(id = id)
        nodeProperties[id] = properties.toMutableMap()
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> = lock.readLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        nodeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun setNodeProperties(id: NodeID, properties: Map<String, IValue?>) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) -> 
            if (value != null) container[key] = value else container.remove(key) 
        }
    }

    override fun deleteNode(id: NodeID): Unit = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (!containsNode(id)) throw EntityNotExistException(id)
        
        // Delete all connected edges first
        getIncomingEdges(id).forEach { deleteEdge(it) }
        getOutgoingEdges(id).forEach { deleteEdge(it) }
        
        // Remove node from structures
        graphStructure.remove(id)
        nodeProperties.remove(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean = lock.readLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        id in edgeProperties
    }

    override fun addEdge(id: EdgeID, properties: Map<String, IValue>) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (containsEdge(id)) throw EntityAlreadyExistException(id = id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        
        // Add edge to graph structure
        graphStructure[id.srcNid] = graphStructure[id.srcNid].orEmpty() + id
        graphStructure[id.dstNid] = graphStructure[id.dstNid].orEmpty() + id
        
        // Store edge properties
        edgeProperties[id] = properties.toMutableMap()
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> = lock.readLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        edgeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun setEdgeProperties(id: EdgeID, properties: Map<String, IValue?>) = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) -> 
            if (value != null) container[key] = value else container.remove(key) 
        }
    }

    override fun deleteEdge(id: EdgeID): Unit = lock.writeLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        
        // Remove edge from graph structure
        graphStructure[id.srcNid] = graphStructure[id.srcNid].orEmpty() - id
        graphStructure[id.dstNid] = graphStructure[id.dstNid].orEmpty() - id
        
        // Remove edge properties
        edgeProperties.remove(id)
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> = lock.readLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        graphStructure[id]?.filter { it.dstNid == id }?.toSet().orEmpty()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> = lock.readLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        graphStructure[id]?.filter { it.srcNid == id }?.toSet().orEmpty()
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override fun getMeta(name: String): IValue? = lock.readLock().withLock {
        if (isClosed) throw AccessClosedStorageException()
        metaProperties[name]
    }

    override fun setMeta(name: String, value: IValue?): Unit = lock.writeLock().withLock {
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

    override fun clear(): Boolean = lock.writeLock().withLock {
        graphStructure.clear()
        edgeProperties.clear()
        nodeProperties.clear()
        metaProperties.clear()
        true
    }

    override fun close(): Unit = lock.writeLock().withLock {
        isClosed = true
        clear()
    }
}
