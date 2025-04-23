package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.entity.toEid
import edu.jhu.cobra.commons.graph.entity.toNid
import edu.jhu.cobra.commons.graph.storage.utils.MapDbValSerializer
import edu.jhu.cobra.commons.value.*
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import org.mapdb.*

/**
 * Implementation of the [IStorage] interface using MapDB for off-heap storage of nodes and edges.
 * This class provides efficient memory management by storing data outside the Java heap,
 * reducing garbage collection overhead and improving performance for large datasets.
 * Please notice that this implementation is not thread-safe.
 * If you need to use it in a concurrent environment, consider using [ConcurMapDBStorageImpl].
 *
 * @property serializer The serializer used to convert [IValue] objects to and from byte arrays.
 * @param config Configuration function for initializing the MapDB database.
 *              Defaults to a temporary file-based off-heap configuration.
 */
class MapDBStorageImpl(
    val serializer: IValSerializer<ByteArray>,
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() }
) : IStorage {

    private val dbManager: DB = DBMaker.config().concurrencyDisable().closeOnJvmShutdown().make()

    private val nodeIDs: HTreeMap<String, IValue>
    private val nodeProperties: HTreeMap<String, IValue>

    private val edgeIDs: HTreeMap<String, IValue>
    private val edgeProperties: HTreeMap<String, IValue>

    private val graphStructure: HTreeMap<String, IValue>

    private val valueSerializer = MapDbValSerializer(serializer)

    init {
        nodeIDs = dbManager.hashMap("nodeIDs", Serializer.STRING, valueSerializer).counterEnable().create()
        edgeIDs = dbManager.hashMap("edgeIDs", Serializer.STRING, valueSerializer).counterEnable().create()
        nodeProperties = dbManager.hashMap("nodeProps", Serializer.STRING, valueSerializer).create()
        edgeProperties = dbManager.hashMap("edgeProps", Serializer.STRING, valueSerializer).create()
        graphStructure = dbManager.hashMap("structure", Serializer.STRING, valueSerializer).create()
    }

    /**
     * Persists all pending changes to the database.
     *
     * @throws AccessClosedStorageException if the storage is closed.
     */
    fun commit() = dbManager.commit()

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
            return nodeIDs.size
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
            return nodeIDs.keys.asSequence().map { it.toNid }
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
            return edgeIDs.size
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
            return edgeIDs.keys.asSequence().map { it.toEid }
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
        return nodeIDs.contains(key = id.name)
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
        return edgeIDs.contains(key = id.name)
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
        nodeIDs[id.name] = SetVal(newProperties.map { (key) -> key.strVal })
        newProperties.forEach { (key, value) -> nodeProperties["${id.name}->$key"] = value }
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
        val prevSrcEdges = graphStructure[srcName] as? SetVal ?: SetVal()
        graphStructure[srcName] = prevSrcEdges.apply { core += id.serialize }
        val prevDstEdges = graphStructure[dstName] as? SetVal ?: SetVal()
        graphStructure[dstName] = prevDstEdges.apply { core += id.serialize }
        edgeIDs[id.name] = SetVal(newProperties.map { (name) -> name.strVal })
        newProperties.forEach { (key, value) -> edgeProperties["$id->$key"] = value }
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
        val allPropKeys = (nodeIDs[id.name] as? SetVal)?.core ?: throw EntityNotExistException(id)
        return allPropKeys.associate { it.core.toString() to nodeProperties["${id.name}->${it.core}"]!! }
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
        if (!containsNode(id)) throw EntityNotExistException(id)
        return nodeProperties["${id.name}->${byName}"]
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
        val edgeProps = (edgeIDs[id.name] as? SetVal)?.core ?: throw EntityNotExistException(id)
        return edgeProps.associate { it.core.toString() to edgeProperties["${id.name}->${it.core}"]!! }
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
        if (!containsEdge(id)) throw EntityNotExistException(id)
        return edgeProperties["${id.name}->$byName"]
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
        if (!containsNode(id)) throw EntityNotExistException(id)
        val curKeys = (nodeIDs[id.name] as SetVal).map { it.core.toString() }
        val updatedKeys = LinkedHashSet<String>(curKeys)
        val (deleteProps, addNewProps) = newProperties.partition { (_, v) -> v == null }
        addNewProps.forEach { (k, v) ->
            if (k !in curKeys) updatedKeys.add(k)
            nodeProperties["${id.name}->${k}"] = v
        }
        deleteProps.forEach { (k) ->
            if (k !in curKeys) return@forEach
            updatedKeys.remove(element = k)
            nodeProperties.remove("${id.name}->${k}")
        }
        if (curKeys != updatedKeys) nodeIDs[id.name] = updatedKeys.setVal
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
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val curKeys = (edgeIDs[id.name] as SetVal).map { (it as StrVal).core }
        val updatedKeys = LinkedHashSet<String>(curKeys)
        val (deleteProps, addNewProps) = newProperties.partition { (_, v) -> v == null }
        deleteProps.forEach { (k) ->
            if (k !in curKeys) return@forEach
            updatedKeys.remove(element = k)
            edgeProperties.remove(key = "${id.name}->${k}")
        }
        addNewProps.forEach { (k, v) ->
            if (k !in curKeys) updatedKeys.add(k)
            edgeProperties["${id.name}->${k}"] = v
        }
        if (curKeys != updatedKeys) edgeIDs[id.name] = updatedKeys.setVal
    }

    /**
     * Removes a node and all its associated edges and properties from the storage.
     *
     * @param id The identifier of the node to remove.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNode(id: NodeID) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id).forEach { deleteEdge(it) }
        getOutgoingEdges(id).forEach { deleteEdge(it) }
        val allProps = (nodeIDs.remove(id.name) as SetVal)
        allProps.forEach { nodeProperties.remove("${id.name}->${it.core}") }
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
        val keyIterator = nodeIDs.iterator()
        while (keyIterator.hasNext()) {
            val (nodeIDStr, propNames) = keyIterator.next()
            val nodeID = NodeID(name = nodeIDStr)
            if (!doSatisfyCond(nodeID)) continue
            getIncomingEdges(nodeID).forEach(::deleteEdge)
            getOutgoingEdges(nodeID).forEach(::deleteEdge)
            val propNameList = (propNames as SetVal).map { it.core }
            propNameList.forEach { nodeProperties.remove("$nodeIDStr->$it") }
            keyIterator.remove()
            graphStructure.remove(nodeIDStr)
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
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val edgePropNames = edgeIDs.remove(id.name) as SetVal
        edgePropNames.forEach { propName -> edgeProperties.remove(key = "${id.name}->$propName") }
        val prevSrcEdges = graphStructure[id.srcNid.name] as SetVal
        graphStructure[id.srcNid.name] = prevSrcEdges.also { it.core -= id.serialize }
        val prevDstEdges = graphStructure[id.dstNid.name] as SetVal
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
        val keyIterator = edgeIDs.iterator()
        while (keyIterator.hasNext()) {
            val (idStr, properties) = keyIterator.next()
            val eid = EdgeID(idStr).takeIf(doSatisfyCond) ?: continue
            val propNames = (properties as SetVal).map { it.core }
            propNames.forEach { edgeProperties.remove("${eid.name}->$it") }
            val (srcName, dstName) = eid.srcNid.name to eid.dstNid.name
            val prevSrcEdges = graphStructure[srcName] as SetVal
            graphStructure[srcName] = prevSrcEdges.also { it.core -= eid.serialize }
            val prevDstEdges = graphStructure[dstName] as SetVal
            graphStructure[dstName] = prevDstEdges.also { it.core -= eid.serialize }
            edgeIDs.remove(idStr)
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
        val allSerialized = graphStructure[id.name] as? SetVal ?: return emptySet()
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
        val allSerialized = graphStructure[id.name] as? SetVal ?: return emptySet()
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
        val allSerialized = graphStructure[from.name] as? SetVal ?: return emptySet()
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
        edgeIDs.clear(); edgeProperties.clear()
        nodeIDs.clear(); nodeProperties.clear()
        graphStructure.isEmpty() && edgeProperties.isEmpty() && nodeProperties.isEmpty()
    } catch (e: DBException.VolumeIOError) {
        false
    }
}