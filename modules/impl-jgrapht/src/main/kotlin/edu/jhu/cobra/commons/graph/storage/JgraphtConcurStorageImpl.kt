package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.IValue
import org.jgrapht.Graph
import org.jgrapht.graph.DirectedPseudograph
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe implementation of the [IStorage] interface using total in-memory storage backed by the JGraphT library.
 * Please notice that there are performance overheads for the concurrency features.
 * For normal use cases, use [JgraphtStorageImpl] instead (about 20% quicker for basic operations).
 *
 * It leverages the JGraphT library to represent the graph structure and provides efficient graph operations like
 * adding, deleting, and retrieving nodes and edges.
 */
class JgraphtConcurStorageImpl : IStorage {

    private var isClosed: Boolean = false

    // Lock for ensuring cross-collections atomic operations
    private val storageLock = ReentrantReadWriteLock()

    /** A HashMap to store node properties with NodeId as the key and a PropDict as the value. */
    private val nodeProperties: MutableMap<NodeID, MutableMap<String, IValue>> = linkedMapOf()

    /** A HashMap to store edge properties with EdgeId as the key and a PropDict as the value. */
    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = linkedMapOf()

    /** A Directed Pseudo-graph from JGraphT library to store nodes and edges. */
    private val jgtGraph: Graph<NodeID, EdgeID> = DirectedPseudograph(EdgeID::class.java)

    /**
     * Retrieves the total number of nodes in the storage.
     * This operation acquires a read lock.
     *
     * @return The number of nodes.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val nodeSize: Int
        get() = storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
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
    override val nodeIDs: Sequence<NodeID>
        get() = storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
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
        get() = storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
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
    override val edgeIDs: Sequence<EdgeID>
        get() = storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
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
    override fun containsNode(id: NodeID): Boolean = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        return id in nodeProperties // check whether it is in the graph
    }

    /**
     * Checks if an edge exists in the storage.
     * This operation acquires a read lock.
     *
     * @param id The edge identifier to check.
     * @return `true` if the edge exists, `false` otherwise.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun containsEdge(id: EdgeID): Boolean = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        return id in edgeProperties // check whether it is in the graph
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
    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        if (id in nodeProperties) throw EntityAlreadyExistException(id = id)
        jgtGraph.addVertex(id)
        nodeProperties[id] = mutableMapOf(*newProperties)
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
    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        if (id in edgeProperties) throw EntityAlreadyExistException(id = id)
        if (id.srcNid !in nodeProperties) throw EntityNotExistException(id.srcNid)
        if (id.dstNid !in nodeProperties) throw EntityNotExistException(id.dstNid)
        jgtGraph.addEdge(id.srcNid, id.dstNid, id)
        edgeProperties[id] = mutableMapOf(*newProperties)
    }

    /**
     * Retrieves all properties of a node.
     * This operation acquires a read lock and creates a defensive copy.
     *
     * @param id The identifier of the node.
     * @return A map of property names to their values.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getNodeProperties(id: NodeID): Map<String, IValue> = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        val props = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        return HashMap(props) // Create a defensive copy to ensure thread safety after lock release
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
    override fun getNodeProperty(id: NodeID, byName: String): IValue? = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        val props = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        return props[byName]
    }

    /**
     * Retrieves all properties of an edge.
     * This operation acquires a read lock and creates a defensive copy.
     *
     * @param id The identifier of the edge.
     * @return A map of property names to their values.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        val props = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        return HashMap(props) // Create a defensive copy to ensure thread safety after lock release
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
    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        val props = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        return props[byName]
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
    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        newProperties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
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
    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        newProperties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    /**
     * Removes a node and all its associated edges and properties from the storage.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @param id The identifier of the node to remove.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNode(id: NodeID): Unit = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in nodeProperties) throw EntityNotExistException(id)

        // First collect all edges to delete
        val incomingEdges = jgtGraph.incomingEdgesOf(id).toSet()
        val outgoingEdges = jgtGraph.outgoingEdgesOf(id).toSet()

        // Then delete all edges
        incomingEdges.forEach { edgeID ->
            jgtGraph.removeEdge(edgeID)
            edgeProperties.remove(edgeID)
        }

        outgoingEdges.forEach { edgeID ->
            jgtGraph.removeEdge(edgeID)
            edgeProperties.remove(edgeID)
        }

        // Finally delete the node
        jgtGraph.removeVertex(id)
        nodeProperties.remove(id)
    }

    /**
     * Removes all nodes that satisfy the given condition.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @param doSatisfyCond The condition to check for each node.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()

        // First collect all nodes to delete to avoid concurrent modification issues
        val nodesToDelete = nodeProperties.keys.filter(doSatisfyCond)

        // Then delete each node
        nodesToDelete.forEach { nodeID ->
            // Get incoming and outgoing edges
            val incomingEdges = jgtGraph.incomingEdgesOf(nodeID).toSet()
            val outgoingEdges = jgtGraph.outgoingEdgesOf(nodeID).toSet()

            // Delete all associated edges
            incomingEdges.forEach { edgeID ->
                jgtGraph.removeEdge(edgeID)
                edgeProperties.remove(edgeID)
            }

            outgoingEdges.forEach { edgeID ->
                jgtGraph.removeEdge(edgeID)
                edgeProperties.remove(edgeID)
            }

            // Delete the node
            jgtGraph.removeVertex(nodeID)
            nodeProperties.remove(nodeID)
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
    override fun deleteEdge(id: EdgeID): Unit = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in edgeProperties) throw EntityNotExistException(id)
        jgtGraph.removeEdge(id)
        edgeProperties.remove(id)
    }

    /**
     * Removes all edges that satisfy the given condition.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @param doSatisfyCond The condition to check for each edge.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()

        // First collect all edges to delete to avoid concurrent modification issues
        val edgesToDelete = edgeProperties.keys.filter(doSatisfyCond)

        // Then delete each edge
        edgesToDelete.forEach { edgeID ->
            jgtGraph.removeEdge(edgeID)
            edgeProperties.remove(edgeID)
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
    override fun getIncomingEdges(id: NodeID): Set<EdgeID> = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in nodeProperties) throw EntityNotExistException(id)
        return jgtGraph.incomingEdgesOf(id).toSet()
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
    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in nodeProperties) throw EntityNotExistException(id)
        return jgtGraph.outgoingEdgesOf(id).toSet()
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
    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> = storageLock.read {
        if (isClosed) throw AccessClosedStorageException()
        if (from !in nodeProperties) throw EntityNotExistException(from)
        if (to !in nodeProperties) throw EntityNotExistException(to)
        return jgtGraph.getAllEdges(from, to).toSet()
    }

    /**
     * Removes all nodes, edges, and their properties from the storage.
     * This operation acquires a write lock to ensure atomic updates to multiple data structures.
     *
     * @return `true` if the storage was successfully cleared, `false` otherwise.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun clear(): Boolean = storageLock.write {
        if (isClosed) return@write false
        jgtGraph.removeAllEdges(edgeProperties.keys)
        edgeProperties.clear() // Clear edge properties
        jgtGraph.removeAllVertices(nodeProperties.keys)
        nodeProperties.clear() // Clear node properties
        return jgtGraph.edgeSet().isEmpty() && edgeProperties.isEmpty()
                && jgtGraph.vertexSet().isEmpty() && nodeProperties.isEmpty()
    }

    /**
     * Closes the storage and releases all associated resources.
     * This operation acquires a write lock to ensure no other operations are in progress.
     *
     * @throws AccessClosedStorageException if the storage is already closed.
     */
    override fun close(): Unit = storageLock.write { isClosed = clear() }
}
