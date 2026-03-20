package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import java.io.Closeable

/**
 * Core interface for graph storage with auto-generated Int IDs.
 *
 * Manages nodes and edges identified by auto-generated `Int` IDs. Callers
 * provide properties and structural info (src, dst, tag for edges); the
 * storage assigns monotonically increasing Int identifiers. This eliminates
 * String hashing overhead on all internal lookups and adjacency operations.
 *
 * @see NativeStorageImpl
 * @see NativeConcurStorageImpl
 */
@Suppress("TooManyFunctions")
interface IStorage : Closeable {
    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    /**
     * All node IDs currently in storage.
     *
     * @throws AccessClosedStorageException If storage is closed.
     */
    val nodeIDs: Set<Int>

    /**
     * Checks if a node exists.
     *
     * @param id The node ID.
     * @return True if the node exists.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun containsNode(id: Int): Boolean

    /**
     * Adds a node with the given properties.
     *
     * @param properties Initial property map.
     * @return The auto-generated node ID.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun addNode(properties: Map<String, IValue> = emptyMap()): Int

    /**
     * Returns all properties of a node.
     *
     * @param id The node ID.
     * @return Property map.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun getNodeProperties(id: Int): Map<String, IValue>

    /**
     * Returns a single property value of a node.
     *
     * Default delegates to [getNodeProperties]; implementations may override
     * for O(1) direct access.
     *
     * @param id The node ID.
     * @param name The property name.
     * @return The property value, or null if absent.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? = getNodeProperties(id)[name]

    /**
     * Updates properties of a node. Null values delete the corresponding property.
     *
     * @param id The node ID.
     * @param properties Property updates.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    )

    /**
     * Deletes a node and all its incident edges.
     *
     * @param id The node ID.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun deleteNode(id: Int)

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    /**
     * All edge IDs currently in storage.
     *
     * @throws AccessClosedStorageException If storage is closed.
     */
    val edgeIDs: Set<Int>

    /**
     * Checks if an edge exists.
     *
     * @param id The edge ID.
     * @return True if the edge exists.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun containsEdge(id: Int): Boolean

    /**
     * Adds an edge between two existing nodes.
     *
     * @param src The source node ID.
     * @param dst The destination node ID.
     * @param tag The edge tag name.
     * @param properties Initial property map.
     * @return The auto-generated edge ID.
     * @throws EntityNotExistException If source or destination node does not exist.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue> = emptyMap(),
    ): Int

    /**
     * Structural info of an edge: source node, destination node, and tag.
     *
     * @property src The source node ID.
     * @property dst The destination node ID.
     * @property tag The edge tag name.
     */
    data class EdgeStructure(
        val src: Int,
        val dst: Int,
        val tag: String,
    )

    /**
     * Returns the structural info (source, destination, tag) of an edge in a single lookup.
     *
     * @param id The edge ID.
     * @return The edge structure containing src, dst, and tag.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun getEdgeStructure(id: Int): EdgeStructure

    /**
     * Returns the source node ID of an edge.
     *
     * Default delegates to [getEdgeStructure]; implementations may override for efficiency.
     *
     * @param id The edge ID.
     * @return The source node ID.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun getEdgeSrc(id: Int): Int = getEdgeStructure(id).src

    /**
     * Returns the destination node ID of an edge.
     *
     * Default delegates to [getEdgeStructure]; implementations may override for efficiency.
     *
     * @param id The edge ID.
     * @return The destination node ID.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun getEdgeDst(id: Int): Int = getEdgeStructure(id).dst

    /**
     * Returns the tag of an edge.
     *
     * Default delegates to [getEdgeStructure]; implementations may override for efficiency.
     *
     * @param id The edge ID.
     * @return The edge tag name.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun getEdgeTag(id: Int): String = getEdgeStructure(id).tag

    /**
     * Returns all properties of an edge.
     *
     * @param id The edge ID.
     * @return Property map.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun getEdgeProperties(id: Int): Map<String, IValue>

    /**
     * Returns a single property value of an edge.
     *
     * Default delegates to [getEdgeProperties]; implementations may override
     * for O(1) direct access.
     *
     * @param id The edge ID.
     * @param name The property name.
     * @return The property value, or null if absent.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? = getEdgeProperties(id)[name]

    /**
     * Updates properties of an edge. Null values delete the corresponding property.
     *
     * @param id The edge ID.
     * @param properties Property updates.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    )

    /**
     * Deletes an edge from storage.
     *
     * @param id The edge ID.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If edge does not exist.
     */
    fun deleteEdge(id: Int)

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    /**
     * Returns all incoming edge IDs to a node.
     *
     * @param id The node ID.
     * @return Set of incoming edge IDs.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun getIncomingEdges(id: Int): Set<Int>

    /**
     * Returns all outgoing edge IDs from a node.
     *
     * @param id The node ID.
     * @return Set of outgoing edge IDs.
     * @throws AccessClosedStorageException If storage is closed.
     * @throws EntityNotExistException If node does not exist.
     */
    fun getOutgoingEdges(id: Int): Set<Int>

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    /**
     * All metadata property names currently in storage.
     *
     * @throws AccessClosedStorageException If storage is closed.
     */
    val metaNames: Set<String>

    /**
     * Returns a metadata value by name.
     *
     * @param name The metadata property name.
     * @return The value, or null if not found.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun getMeta(name: String): IValue?

    /**
     * Sets a metadata value. Passing null deletes the property.
     *
     * @param name The metadata property name.
     * @param value The value, or null to delete.
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun setMeta(
        name: String,
        value: IValue?,
    )

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    /**
     * Removes all nodes, edges, and metadata from storage.
     *
     * @throws AccessClosedStorageException If storage is closed.
     */
    fun clear()

    /**
     * Transfers all data (nodes, edges, metadata) into [target].
     *
     * Nodes are transferred before edges to satisfy existence constraints.
     * Returns the node ID mapping (source ID → target ID) for caller use.
     *
     * @param target The destination storage.
     * @return Node ID mapping from this storage's IDs to target storage's IDs.
     */
    fun transferTo(target: IStorage): Map<Int, Int>
}
