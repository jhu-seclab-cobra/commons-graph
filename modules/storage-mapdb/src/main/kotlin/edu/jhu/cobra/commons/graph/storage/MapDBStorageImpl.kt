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

/**
 * Implementation of the [IStorage] interface using MapDB for off-heap storage of nodes and edges.
 * This class provides efficient memory management by storing data outside the Java heap,
 * reducing garbage collection overhead and improving performance for large datasets.
 * Please notice that this implementation is not thread-safe.
 * If you need to use it in a concurrent environment, consider using [ConcurMapDBStorageImpl].
 *
 * @param config Configuration function for initializing the MapDB database.
 *              Defaults to a temporary file-based off-heap configuration.
 */
class MapDBStorageImpl(
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() }
) : IStorage {

    private val dbManager: DB = DBMaker.config().concurrencyDisable().closeOnJvmShutdown().make()

    private val nodeProperties = EntityPropertyMap<NodeID>(dbManager, "nodeProps")
    private val edgeProperties = EntityPropertyMap<EdgeID>(dbManager, "edgeProps")
    private val structSerializer = MapDbValSerializer<SetVal>(DftByteArraySerializerImpl)
    private val graphStructure = dbManager.hashMap("structure", Serializer.STRING, structSerializer).create()

    /**
     * Closes the storage and releases all associated resources.
     *
     * @throws AccessClosedStorageException if the storage is already closed.
     */
    override fun close() {
        if (!dbManager.isClosed()) dbManager.close()
    }

    /**
     * Retrieves the total number of nodes in the storage.
     *
     * @return The number of nodes.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val nodeSize: Int
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return nodeProperties.size
        }

    /**
     * Retrieves a sequence of all node identifiers in the storage.
     *
     * @return A sequence of [NodeID] objects.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val nodeIDsSequence: Sequence<NodeID>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return nodeProperties.keys.asSequence()
        }

    /**
     * Retrieves the total number of edges in the storage.
     *
     * @return The number of edges.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val edgeSize: Int
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return edgeProperties.size
        }

    /**
     * Retrieves a sequence of all edge identifiers in the storage.
     *
     * @return A sequence of [EdgeID] objects.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val edgeIDsSequence: Sequence<EdgeID>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return edgeProperties.keys.asSequence()
        }

    /**
     * Checks if a node exists in the storage.
     *
     * @param id The node identifier to check.
     * @return `true` if the node exists, `false` otherwise.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun containsNode(id: NodeID): Boolean {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return nodeProperties.contains(id)
    }

    /**
     * Checks if an edge exists in the storage.
     *
     * @param id The edge identifier to check.
     * @return `true` if the edge exists, `false` otherwise.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun containsEdge(id: EdgeID): Boolean {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeProperties.contains(id)
    }

    /**
     * Adds a new node to the storage with optional properties.
     *
     * @param id The identifier of the node to add.
     * @param newProperties Optional properties to associate with the node.
     * @throws EntityAlreadyExistException if a node with the same identifier already exists.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) {
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        nodeProperties[id] = mapOf(*newProperties)
    }

    /**
     * Adds a new edge to the storage with optional properties.
     *
     * @param id The identifier of the edge to add.
     * @param newProperties Optional properties to associate with the edge.
     * @throws EntityAlreadyExistException if an edge with the same identifier already exists.
     * @throws EntityNotExistException if the source or destination node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) {
        if (containsEdge(id)) throw EntityAlreadyExistException(id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        val (srcName, dstName) = id.srcNid.name to id.dstNid.name
        val prevSrcEdges = graphStructure[srcName].orEmpty()
        graphStructure[srcName] = prevSrcEdges + id.serialize
        val prevDstEdges = graphStructure[dstName].orEmpty()
        graphStructure[dstName] = prevDstEdges + id.serialize
        edgeProperties[id] = mapOf(*newProperties)
    }

    /**
     * Retrieves all properties of a node.
     *
     * @param id The identifier of the node.
     * @return A map of property names to their values.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return nodeProperties[id] ?: throw EntityNotExistException(id)
    }

    /**
     * Retrieves a specific property of a node.
     *
     * @param id The identifier of the node.
     * @param byName The name of the property to retrieve.
     * @return The property value, or `null` if the property does not exist.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getNodeProperty(id: NodeID, byName: String): IValue? {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        return nodePropMap[byName]
    }

    /**
     * Retrieves all properties of an edge.
     *
     * @param id The identifier of the edge.
     * @return A map of property names to their values.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeProperties[id] ?: throw EntityNotExistException(id)
    }

    /**
     * Retrieves a specific property of an edge.
     *
     * @param id The identifier of the edge.
     * @param byName The name of the property to retrieve.
     * @return The property value, or `null` if the property does not exist.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val edgePropMap = edgeProperties[id] ?: throw EntityNotExistException(id)
        return edgePropMap[byName]
    }

    /**
     * Updates the properties of a node.
     *
     * @param id The identifier of the node.
     * @param newProperties The properties to update or remove.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        val newPropMap = (nodePropMap + newProperties).asSequence().filter { it.value != null }
        nodeProperties[id] = newPropMap.associate { it.key to it.value!! }
    }

    /**
     * Updates the properties of an edge.
     *
     * @param id The identifier of the edge.
     * @param newProperties The properties to update or remove.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
        val newPropMap = (curEdgeProps + newProperties).asSequence().filter { it.value != null }
        edgeProperties[id] = newPropMap.associate { it.key to it.value!! }
    }

    /**
     * Removes a node and all its associated edges and properties from the storage.
     *
     * @param id The identifier of the node to remove.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNode(id: NodeID) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id).forEach { deleteEdge(it) }
        getOutgoingEdges(id).forEach { deleteEdge(it) }
        nodeProperties.remove(id)
        graphStructure.remove(id.name)
    }

    /**
     * Removes all nodes that satisfy the given condition.
     *
     * @param doSatisfyCond The condition to check for each node.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val keyIterator = nodeProperties.keys.iterator()
        while (keyIterator.hasNext()) {
            val nodeID = keyIterator.next()
            if (!doSatisfyCond(nodeID)) continue
            getIncomingEdges(nodeID).forEach(::deleteEdge)
            getOutgoingEdges(nodeID).forEach(::deleteEdge)
            keyIterator.remove()
            graphStructure.remove(nodeID.name)
        }
    }

    /**
     * Removes an edge and all its associated properties from the storage.
     *
     * @param id The identifier of the edge to remove.
     * @throws EntityNotExistException if the edge does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteEdge(id: EdgeID) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        edgeProperties.remove(key = id)
        val prevSrcEdges = graphStructure[id.srcNid.name].orEmpty()
        graphStructure[id.srcNid.name] = prevSrcEdges.also { it.core -= id.serialize }
        val prevDstEdges = graphStructure[id.dstNid.name].orEmpty()
        graphStructure[id.dstNid.name] = prevDstEdges.also { it.core -= id.serialize }
    }

    /**
     * Removes all edges that satisfy the given condition.
     *
     * @param doSatisfyCond The condition to check for each edge.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val keyIterator = edgeProperties.keys.iterator()
        while (keyIterator.hasNext()) {
            val edgeID = keyIterator.next().takeIf(doSatisfyCond) ?: continue
            keyIterator.remove()
            val (srcName, dstName) = edgeID.srcNid.name to edgeID.dstNid.name
            val prevSrcEdges = graphStructure[srcName].orEmpty()
            graphStructure[srcName] = prevSrcEdges - edgeID.serialize
            val prevDstEdges = graphStructure[dstName].orEmpty()
            graphStructure[dstName] = prevDstEdges - edgeID.serialize
        }
    }

    /**
     * Retrieves all incoming edges for a node.
     *
     * @param id The identifier of the node.
     * @return A set of edge identifiers.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val allSerialized = graphStructure[id.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.dstNid == id }.toSet()
    }

    /**
     * Retrieves all outgoing edges for a node.
     *
     * @param id The identifier of the node.
     * @return A set of edge identifiers.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        if (!containsNode(id = id)) throw EntityNotExistException(id = id)
        val allSerialized = graphStructure[id.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.srcNid == id }.toSet()
    }

    /**
     * Retrieves all edges between two nodes.
     *
     * @param from The source node identifier.
     * @param to The destination node identifier.
     * @return A set of edge identifiers.
     * @throws EntityNotExistException if either node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> {
        if (!containsNode(from)) throw EntityNotExistException(id = from)
        if (!containsNode(id = to)) throw EntityNotExistException(id = to)
        val allSerialized = graphStructure[from.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.srcNid == from && it.dstNid == to }.toSet()
    }

    /**
     * Removes all nodes, edges, and their properties from the storage.
     *
     * @return `true` if the storage was successfully cleared, `false` otherwise.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun clear(): Boolean = try {
        graphStructure.clear()
        edgeProperties.clear()
        nodeProperties.clear()
        graphStructure.isEmpty() && edgeProperties.isEmpty() && nodeProperties.isEmpty()
    } catch (e: DBException.VolumeIOError) {
        false
    }
}