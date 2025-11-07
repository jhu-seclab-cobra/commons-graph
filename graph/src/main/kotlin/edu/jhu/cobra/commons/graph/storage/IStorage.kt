package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.IValue
import java.io.Closeable


/**
 * Core interface for managing storage of nodes and edges.
 *
 * Provides essential operations for graph data management with clear separation of concerns.
 * Implementations should focus on efficient storage and retrieval operations.
 *
 * @see NodeID
 * @see EdgeID
 * @see IEntity
 */
interface IStorage : Closeable {
    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    /**
     * Returns all node IDs currently in storage.
     *
     * @return Set of [NodeID] values.
     * @throws AccessClosedStorageException If storage is closed.
     */
    val nodeIDs: Set<NodeID>

    /**
     * Returns all edge IDs currently in storage.
     *
     * @return Set of [EdgeID] values.
     * @throws AccessClosedStorageException If storage is closed.
     */
    val edgeIDs: Set<EdgeID>

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    /**
     * Checks if a node exists in storage.
     *
     * @param id The ID of the node.
     * @return True if the node exists, false otherwise.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun containsNode(id: NodeID): Boolean

    /**
     * Adds a node with the given ID and properties.
     *
     * @param id The ID of the node to add.
     * @param properties Map of property names to values.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityAlreadyExistException If node already exists.
     */
    fun addNode(id: NodeID, properties: Map<String, IValue> = emptyMap())

    /**
     * Returns all properties associated with a specific node.
     *
     * @param id The ID of the node.
     * @return Map containing all node properties.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun getNodeProperties(id: NodeID): Map<String, IValue>

    /**
     * Updates properties of a specific node.
     *
     * Properties with null values will be deleted from the node.
     *
     * @param id The ID of the node to update.
     * @param properties Map of property names to values. Use null values to delete properties.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun setNodeProperties(id: NodeID, properties: Map<String, IValue?>)

    /**
     * Deletes a node from storage.
     *
     * @param id The ID of the node to delete.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun deleteNode(id: NodeID)

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    /**
     * Checks if an edge exists in storage.
     *
     * @param id The ID of the edge.
     * @return True if the edge exists, false otherwise.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun containsEdge(id: EdgeID): Boolean

    /**
     * Adds an edge with the given ID and properties.
     *
     * @param id The ID of the edge to add.
     * @param properties Map of property names to values.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityAlreadyExistException If edge already exists.
     */
    fun addEdge(id: EdgeID, properties: Map<String, IValue> = emptyMap())

    /**
     * Returns all properties associated with a specific edge.
     *
     * @param id The ID of the edge.
     * @return Map containing all edge properties.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun getEdgeProperties(id: EdgeID): Map<String, IValue>

    /**
     * Updates properties of a specific edge.
     *
     * Properties with null values will be deleted from the edge.
     *
     * @param id The ID of the edge to update.
     * @param properties Map of property names to values. Use null values to delete properties.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun setEdgeProperties(id: EdgeID, properties: Map<String, IValue?>)

    /**
     * Deletes an edge from storage.
     *
     * @param id The ID of the edge to delete.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun deleteEdge(id: EdgeID)

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    /**
     * Returns all incoming edges to a specific node.
     *
     * @param id The ID of the node.
     * @return Set of edge IDs representing the incoming edges.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun getIncomingEdges(id: NodeID): Set<EdgeID>

    /**
     * Returns all outgoing edges from a specific node.
     *
     * @param id The ID of the node.
     * @return Set of edge IDs representing the outgoing edges.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun getOutgoingEdges(id: NodeID): Set<EdgeID>

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    /**
     * Returns a metadata value by name.
     *
     * @param name The name of the metadata property.
     * @return The metadata value, or null if not found.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun getMeta(name: String): IValue?

    /**
     * Sets a metadata value by name.
     *
     * Passing null as the value will delete the metadata property.
     *
     * @param name The name of the metadata property.
     * @param value The metadata value, or null to delete the property.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun setMeta(name: String, value: IValue?)

    // ============================================================================
    // UTILITY OPERATIONS
    // ============================================================================

    /**
     * Clears all nodes and edges from storage.
     *
     * @return True if the operation was successful, false otherwise.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun clear(): Boolean
}
