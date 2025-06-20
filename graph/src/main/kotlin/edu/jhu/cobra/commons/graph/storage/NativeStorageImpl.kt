package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue

/**
 * In-memory graph storage implementation using HashMap-based data structures.
 *
 * Provides efficient storage with O(1) average time complexity. Not thread-safe.
 *
 * @constructor Creates a new empty storage instance.
 * @see NativeConcurStorageImpl
 */
class NativeStorageImpl : IStorage {

    // ============================================================================
    // STATE MANAGEMENT
    // ============================================================================

    /**
     * Indicates whether the storage has been closed.
     */
    private var isClosed: Boolean = false

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
        get() = if (!isClosed) nodeProperties.keys else throw AccessClosedStorageException()

    override val edgeIDs: Set<EdgeID>
        get() = if (!isClosed) edgeProperties.keys else throw AccessClosedStorageException()

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in nodeProperties
    }

    override fun addNode(id: NodeID, properties: Map<String, IValue>) {
        if (isClosed) throw AccessClosedStorageException()
        if (containsNode(id)) throw EntityAlreadyExistException(id = id)
        nodeProperties[id] = properties.toMutableMap()
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return nodeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun setNodeProperties(id: NodeID, properties: Map<String, IValue?>) {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) -> 
            if (value != null) container[key] = value else container.remove(key) 
        }
    }

    override fun deleteNode(id: NodeID) {
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

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in edgeProperties
    }

    override fun addEdge(id: EdgeID, properties: Map<String, IValue>) {
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

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return edgeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun setEdgeProperties(id: EdgeID, properties: Map<String, IValue?>) {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (key, value) -> 
            if (value != null) container[key] = value else container.remove(key) 
        }
    }

    override fun deleteEdge(id: EdgeID) {
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

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        return graphStructure[id]?.filter { it.dstNid == id }?.toSet().orEmpty()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        return graphStructure[id]?.filter { it.srcNid == id }?.toSet().orEmpty()
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override fun getMeta(name: String): IValue? {
        if (isClosed) throw AccessClosedStorageException()
        return metaProperties[name]
    }

    override fun setMeta(name: String, value: IValue?) {
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

    override fun clear(): Boolean {
        graphStructure.clear()
        edgeProperties.clear()
        nodeProperties.clear()
        metaProperties.clear()
        return true
    }

    override fun close() {
        isClosed = true
        clear()
    }
}
