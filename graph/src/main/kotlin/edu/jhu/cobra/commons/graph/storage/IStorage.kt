package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
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
    fun addNode(
        id: NodeID,
        properties: Map<String, IValue> = emptyMap(),
    )

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
     * Returns a single property value of a node by name.
     *
     * Default delegates to [getNodeProperties]; implementations may override
     * for O(1) direct access without constructing the full property map.
     *
     * @param id The ID of the node.
     * @param name The property name.
     * @return The property value, or null if the property is absent.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun getNodeProperty(
        id: NodeID,
        name: String,
    ): IValue? = getNodeProperties(id)[name]

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
    fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    )

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
    fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue> = emptyMap(),
    )

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
     * Returns a single property value of an edge by name.
     *
     * Default delegates to [getEdgeProperties]; implementations may override
     * for O(1) direct access without constructing the full property map.
     *
     * @param id The ID of the edge.
     * @param name The property name.
     * @return The property value, or null if the property is absent.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun getEdgeProperty(
        id: EdgeID,
        name: String,
    ): IValue? = getEdgeProperties(id)[name]

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
    fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    )

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
     * Returns all metadata property names currently in storage.
     *
     * @return Set of metadata property names.
     * @throws AccessClosedStorageException If storage is closed.
     */
    val metaNames: Set<String>

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
    fun setMeta(
        name: String,
        value: IValue?,
    )

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

    /**
     * Transfers all data from this storage into [target].
     *
     * Copies all nodes (with properties), all edges (with properties), and all
     * metadata into [target]. Nodes are transferred before edges to satisfy
     * edge source/destination existence constraints.
     *
     * This storage is not modified. [target] must be empty or accept the transferred
     * entities without ID conflicts.
     *
     * @param target The destination storage to receive all data.
     * @throws EntityAlreadyExistException if [target] already contains a transferred entity.
     */
    fun transferTo(target: IStorage) {
        for (nodeId in nodeIDs) {
            target.addNode(nodeId, getNodeProperties(nodeId))
        }
        for (edgeId in edgeIDs) {
            target.addEdge(edgeId, getEdgeProperties(edgeId))
        }
        for (name in metaNames) {
            target.setMeta(name, getMeta(name))
        }
    }
}
