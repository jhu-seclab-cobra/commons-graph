package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.IValue
import java.io.Closeable


/**
 * Interface for managing storage of nodes and edges, allowing operations such as adding, updating, and deleting
 * nodes and edges, as well as retrieving their properties.
 *
 * This interface provides a common abstraction for different storage implementations, such as in-memory or
 * persistent storage, and defines a set of operations for managing nodes and edges in a graph.
 */
interface IStorage : Closeable {

    /**
     * Represents the total number of nodes currently stored in the implementation of the storage system.
     *
     * This field provides quick access to the count of nodes without requiring specific queries or iterations over the data structure.
     * It is particularly useful for assessing the scale or usage of the storage and can serve as a reference for capacity-related operations.
     */
    val nodeSize: Int

    /**
     * A set of all node IDs currently in the storage.
     * @throws AccessClosedStorageException
     */
    val nodeIDsSequence: Sequence<NodeID>

    /**
     * The total number of edges currently stored in the storage.
     * This value represents the count of edges that have been added to the storage
     * and are not deleted. It is updated as edges are added or removed.
     */
    val edgeSize: Int

    /**
     * A set of all edge IDs currently in the storage.
     * @throws AccessClosedStorageException
     */
    val edgeIDsSequence: Sequence<EdgeID>

    /**
     * Checks if a node exists in the storage.
     *
     * @param id The ID of the node.
     * @return True if the node exists, false otherwise.
     * @throws AccessClosedStorageException
     */
    fun containsNode(id: NodeID): Boolean

    /**
     * Checks if an edge exists in the storage.
     *
     * @param id The ID of the edge.
     * @return True if the edge exists, false otherwise.
     * @throws AccessClosedStorageException
     */
    fun containsEdge(id: EdgeID): Boolean

    /**
     * Adds a node with a given ID and an optional list of properties.
     *
     * @param id The ID of the node to add.
     * @param newProperties Key-value pairs representing the node properties.
     * @throws AccessClosedStorageException
     */
    fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>)

    /**
     * Adds an edge with a given ID and an optional list of properties.
     *
     * @param id The ID of the edge to add.
     * @param newProperties Key-value pairs representing the edge properties.
     * @throws AccessClosedStorageException
     */
    fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>)

    /**
     * Retrieves all properties associated with a specific node.
     *
     * @param id The ID of the node.
     * @return A map containing all node properties.
     * @throws AccessClosedStorageException
     */
    fun getNodeProperties(id: NodeID): Map<String, IValue>

    /**
     * Retrieves a specific property of a node by name.
     *
     * @param id The ID of the node.
     * @param byName The name of the property to retrieve.
     * @return The value of the specified property, or null if not found.
     * @throws AccessClosedStorageException
     */
    fun getNodeProperty(id: NodeID, byName: String): IValue?

    /**
     * Retrieves all properties associated with a specific edge.
     *
     * @param id The ID of the edge.
     * @return A map containing all edge properties.
     * @throws AccessClosedStorageException
     */
    fun getEdgeProperties(id: EdgeID): Map<String, IValue>

    /**
     * Retrieves a specific property of an edge by name.
     *
     * @param id The ID of the edge.
     * @param byName The name of the property to retrieve.
     * @return The value of the specified property, or null if not found.
     * @throws AccessClosedStorageException
     */
    fun getEdgeProperty(id: EdgeID, byName: String): IValue?

    /**
     * Updates properties of a specific node.
     *
     * @param id The ID of the node to update.
     * @param newProperties Key-value pairs representing the updated properties.
     * @throws AccessClosedStorageException
     */
    fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>)

    /**
     * Updates properties of a specific edge.
     *
     * @param id The ID of the edge to update.
     * @param newProperties Key-value pairs representing the updated properties.
     * @throws AccessClosedStorageException
     */
    fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>)

    /**
     * Deletes a node from the storage.
     *
     * @param id The ID of the node to delete.
     * @throws AccessClosedStorageException
     */
    fun deleteNode(id: NodeID)

    /**
     * Deletes nodes that satisfy a given condition.
     *
     * @param doSatisfyCond A lambda that returns true for nodes that should be deleted.
     * @throws AccessClosedStorageException
     */
    fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean)

    /**
     * Deletes an edge from the storage.
     *
     * @param id The ID of the edge to delete.
     * @throws AccessClosedStorageException
     */
    fun deleteEdge(id: EdgeID)

    /**
     * Deletes edges that satisfy a given condition.
     *
     * @param doSatisfyCond A lambda that returns true for edges that should be deleted.
     * @throws AccessClosedStorageException
     */
    fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean)

    /**
     * Retrieves all incoming edges to a specific node.
     *
     * @param id The ID of the node.
     * @return A set of edge IDs representing the incoming edges.
     * @throws AccessClosedStorageException
     */
    fun getIncomingEdges(id: NodeID): Set<EdgeID>

    /**
     * Retrieves all outgoing edges from a specific node.
     *
     * @param id The ID of the node.
     * @return A set of edge IDs representing the outgoing edges.
     * @throws AccessClosedStorageException
     */
    fun getOutgoingEdges(id: NodeID): Set<EdgeID>

    /**
     * Retrieves all edges between two specific nodes.
     *
     * @param from The ID of the source node.
     * @param to The ID of the target node.
     * @return A set of edge IDs representing the edges between the nodes.
     * @throws AccessClosedStorageException
     */
    fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID>

    /**
     * Clears all nodes and edges from the storage.
     *
     * @return True if the operation was successful, false otherwise.
     * @throws AccessClosedStorageException
     */
    fun clear(): Boolean
}
