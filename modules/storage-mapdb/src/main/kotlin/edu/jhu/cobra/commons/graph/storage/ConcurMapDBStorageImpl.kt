package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.utils.EntityPropertyMap
import edu.jhu.cobra.commons.graph.storage.utils.MapDbValSerializer
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.orEmpty
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import org.mapdb.DB
import org.mapdb.DBException
import org.mapdb.DBMaker
import org.mapdb.Serializer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe implementation of the [IStorage] interface using MapDB for off-heap storage.
 * This implementation uses MapDB's built-in concurrency features to ensure thread safety.
 * Please note that this implementation is not suitable for concurrent operations on the same storage instance. There are performance overheads for the concurrency features. For normal use cases, use [MapDBStorageImpl] instead.
 *
 * @param config Configuration function for initializing the MapDB database.
 *              Defaults to a temporary file-based off-heap configuration.
 */
class ConcurMapDBStorageImpl(
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() }
) : IStorage {

    private val dbManager: DB = DBMaker.config().closeOnJvmShutdown().make()

    // Lock for ensuring cross-collections atomic operations
    private val dbLock = ReentrantReadWriteLock()
    private val nodeProperties = EntityPropertyMap<NodeID>(dbManager, "nodeProps")
    private val edgeProperties = EntityPropertyMap<EdgeID>(dbManager, "edgeProps")
    private val structSerializer = MapDbValSerializer<SetVal>(DftByteArraySerializerImpl)
    private val graphStructure = dbManager.hashMap("structure", Serializer.STRING, structSerializer).create()

    /**
     * Closes the storage and releases all associated resources.
     * This operation acquires a write lock to ensure no other operations are in progress.
     *
     * @throws AccessClosedStorageException if the storage is already closed.
     */
    override fun close() = dbLock.write { if (!dbManager.isClosed()) dbManager.close() }


    /**
     * Retrieves the total number of nodes in the storage.
     * This operation acquires a read lock.
     *
     * @return The number of nodes.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val nodeSize: Int
        get() = dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            nodeProperties.size
        }

    /**
     * Retrieves a sequence of all node identifiers in the storage.
     * This operation acquires a read lock and collects the result into a list
     * to avoid holding the lock during sequence iteration.
     *
     * @return A sequence of [NodeID] objects.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val nodeIDsSequence: Sequence<NodeID>
        get() = dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            nodeProperties.keys.toList().asSequence()
        }

    /**
     * Retrieves the total number of edges in the storage.
     * This operation acquires a read lock.
     *
     * @return The number of edges.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val edgeSize: Int
        get() = dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeProperties.size
        }

    /**
     * Retrieves a sequence of all edge identifiers in the storage.
     * This operation acquires a read lock and collects the result into a list
     * to avoid holding the lock during sequence iteration.
     *
     * @return A sequence of [EdgeID] objects.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val edgeIDsSequence: Sequence<EdgeID>
        get() = dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeProperties.keys.toList().asSequence()
        }

    /**
     * Checks if a node exists in the storage.
     * This operation acquires a read lock.
     *
     * @param id The node identifier to check.
     * @return `true` if the node exists, `false` otherwise.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun containsNode(id: NodeID): Boolean = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        nodeProperties.contains(id)
    }

    /**
     * Checks if an edge exists in the storage.
     * This operation acquires a read lock.
     *
     * @param id The edge identifier to check.
     * @return `true` if the edge exists, `false` otherwise.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun containsEdge(id: EdgeID): Boolean = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        edgeProperties.contains(id)
    }

    /**
     * Adds a new node to the storage with optional properties.
     * This operation acquires a write lock to ensure atomicity.
     *
     * @param id The identifier of the node to add.
     * @param newProperties Optional properties to associate with the node.
     * @throws EntityAlreadyExistException if a node with the same identifier already exists.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (nodeProperties.contains(id)) throw EntityAlreadyExistException(id)
        nodeProperties[id] = mapOf(*newProperties)
    }

    /**
     * Adds a new edge to the storage with optional properties.
     * This operation acquires a write lock to ensure atomic updates to
     * both edge properties and graph structure.
     *
     * @param id The identifier of the edge to add.
     * @param newProperties Optional properties to associate with the edge.
     * @throws EntityAlreadyExistException if an edge with the same identifier already exists.
     * @throws EntityNotExistException if the source or destination node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (edgeProperties.contains(id)) throw EntityAlreadyExistException(id)
        if (!nodeProperties.contains(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!nodeProperties.contains(id.dstNid)) throw EntityNotExistException(id.dstNid)

        val (srcName, dstName) = id.srcNid.name to id.dstNid.name
        val prevSrcEdges = graphStructure[srcName].orEmpty()
        graphStructure[srcName] = prevSrcEdges + id.serialize
        val prevDstEdges = graphStructure[dstName].orEmpty()
        graphStructure[dstName] = prevDstEdges + id.serialize
        edgeProperties[id] = mapOf(*newProperties)
    }

    /**
     * Retrieves all properties of a node.
     * This operation acquires a read lock.
     *
     * @param id The identifier of the node.
     * @return A map of property names to their values.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getNodeProperties(id: NodeID): Map<String, IValue> = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        nodeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
    }

    /**
     * Retrieves a specific property of a node.
     * This operation acquires a read lock.
     *
     * @param id The identifier of the node.
     * @param byName The name of the property to retrieve.
     * @return The property value, or `null` if the property does not exist.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getNodeProperty(id: NodeID, byName: String): IValue? = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        nodePropMap[byName]
    }

    /**
     * Retrieves all properties of an edge.
     * This operation acquires a read lock.
     *
     * @param id The identifier of the edge.
     * @return A map of property names to their values.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        edgeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
    }

    /**
     * Retrieves a specific property of an edge.
     * This operation acquires a read lock.
     *
     * @param id The identifier of the edge.
     * @param byName The name of the property to retrieve.
     * @return The property value, or `null` if the property does not exist.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val edgePropMap = edgeProperties[id] ?: throw EntityNotExistException(id)
        edgePropMap[byName]
    }

    /**
     * Updates the properties of a node.
     * This operation acquires a write lock.
     *
     * @param id The identifier of the node.
     * @param newProperties The properties to update or remove.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        val newPropMap = (nodePropMap + newProperties).asSequence().filter { it.value != null }
        nodeProperties[id] = newPropMap.associate { it.key to it.value!! }
    }

    /**
     * Updates the properties of an edge.
     * This operation acquires a write lock.
     *
     * @param id The identifier of the edge.
     * @param newProperties The properties to update or remove.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
        val newPropMap = (curEdgeProps + newProperties).asSequence().filter { it.value != null }
        edgeProperties[id] = newPropMap.associate { it.key to it.value!! }
    }

    /**
     * Removes a node and all its associated edges and properties from the storage.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @param id The identifier of the node to remove.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNode(id: NodeID) {
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (!nodeProperties.contains(id)) throw EntityNotExistException(id)

            // First collect all edges to delete
            val incomingEdges = getIncomingEdgesWithoutLock(id)
            val outgoingEdges = getOutgoingEdgesWithoutLock(id)

            // Then delete all edges
            incomingEdges.forEach { deleteEdgeWithoutLock(it) }
            outgoingEdges.forEach { deleteEdgeWithoutLock(it) }

            // Finally delete the node
            nodeProperties.remove(id)
            graphStructure.remove(id.name)
        }
    }

    /**
     * Helper method to get incoming edges without acquiring a new lock.
     * This should only be called from within a method that already holds a lock.
     */
    private fun getIncomingEdgesWithoutLock(id: NodeID): Set<EdgeID> {
        if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
        val allSerialized = graphStructure[id.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.dstNid == id }.toSet()
    }

    /**
     * Helper method to get outgoing edges without acquiring a new lock.
     * This should only be called from within a method that already holds a lock.
     */
    private fun getOutgoingEdgesWithoutLock(id: NodeID): Set<EdgeID> {
        if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
        val allSerialized = graphStructure[id.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.srcNid == id }.toSet()
    }

    /**
     * Helper method to delete an edge without acquiring a new lock.
     * This should only be called from within a method that already holds a lock.
     */
    private fun deleteEdgeWithoutLock(id: EdgeID) {
        edgeProperties.remove(key = id)
        val prevSrcEdges = graphStructure[id.srcNid.name].orEmpty()
        graphStructure[id.srcNid.name] = prevSrcEdges.also { it.core -= id.serialize }
        val prevDstEdges = graphStructure[id.dstNid.name].orEmpty()
        graphStructure[id.dstNid.name] = prevDstEdges.also { it.core -= id.serialize }
    }

    /**
     * Removes all nodes that satisfy the given condition.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @param doSatisfyCond The condition to check for each node.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()

        // First collect all nodes to delete to avoid concurrent modification issues
        val nodesToDelete = nodeProperties.keys.filter(doSatisfyCond)

        // Then delete each node
        nodesToDelete.forEach { nodeID ->
            getIncomingEdgesWithoutLock(nodeID).forEach { deleteEdgeWithoutLock(it) }
            getOutgoingEdgesWithoutLock(nodeID).forEach { deleteEdgeWithoutLock(it) }
            nodeProperties.remove(nodeID)
            graphStructure.remove(nodeID.name)
        }
    }

    /**
     * Removes an edge and all its associated properties from the storage.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @param id The identifier of the edge to remove.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteEdge(id: EdgeID) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (!edgeProperties.contains(id)) return@write
        deleteEdgeWithoutLock(id)
    }

    /**
     * Removes all edges that satisfy the given condition.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @param doSatisfyCond The condition to check for each edge.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()

        // First collect all edges to delete to avoid concurrent modification issues
        val edgesToDelete = edgeProperties.keys.filter(doSatisfyCond)

        // Then delete each edge
        edgesToDelete.forEach { edgeID ->
            edgeProperties.remove(key = edgeID)
            val srcName = edgeID.srcNid.name
            val dstName = edgeID.dstNid.name
            val prevSrcEdges = graphStructure[srcName].orEmpty()
            graphStructure[srcName] = prevSrcEdges - edgeID.serialize
            val prevDstEdges = graphStructure[dstName].orEmpty()
            graphStructure[dstName] = prevDstEdges - edgeID.serialize
        }
    }

    /**
     * Retrieves all incoming edges for a node.
     * This operation acquires a read lock.
     *
     * @param id The identifier of the node.
     * @return A set of edge identifiers.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getIncomingEdges(id: NodeID): Set<EdgeID> = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        getIncomingEdgesWithoutLock(id)
    }

    /**
     * Retrieves all outgoing edges for a node.
     * This operation acquires a read lock.
     *
     * @param id The identifier of the node.
     * @return A set of edge identifiers.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        getOutgoingEdgesWithoutLock(id)
    }

    /**
     * Retrieves all edges between two nodes.
     * This operation acquires a read lock.
     *
     * @param from The source node identifier.
     * @param to The destination node identifier.
     * @return A set of edge identifiers.
     * @throws EntityNotExistException if either node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (!nodeProperties.contains(from)) throw EntityNotExistException(id = from)
        if (!nodeProperties.contains(to)) throw EntityNotExistException(id = to)

        val allSerialized = graphStructure[from.name] ?: return@read emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return@read allEdgeIDs.filter { it.srcNid == from && it.dstNid == to }.toSet()
    }

    /**
     * Removes all nodes, edges, and their properties from the storage.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @return `true` if the storage was successfully cleared, `false` otherwise.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun clear(): Boolean = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()

        try {
            graphStructure.clear()
            edgeProperties.clear()
            nodeProperties.clear()
            graphStructure.isEmpty() && edgeProperties.isEmpty() && nodeProperties.isEmpty()
        } catch (e: DBException.VolumeIOError) {
            false
        }
    }
}