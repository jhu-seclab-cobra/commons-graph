package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.entity.toEid
import edu.jhu.cobra.commons.graph.entity.toNid
import edu.jhu.cobra.commons.value.*
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import edu.jhu.cobra.commons.value.serializer.asByteArray
import org.mapdb.*

/**
 * Implementation of the [IStorage] interface using MapDB for off-heap storage of nodes and edges.
 *
 * This class is designed to store and manage nodes and edges, along with their associated properties, using MapDB
 * as the underlying database. MapDB provides off-heap storage capabilities, which allow for more efficient memo usage,
 * especially for large datasets, by storing data outside the Java heap. This can reduce garbage collection overhead
 * and improve performance in memory-constrained environments.
 *
 * The class also integrates with the JGraphT library for graph-related operations, allowing the storage of node
 * and edge structures in a directed pseudo graph.
 *
 * @property serializer The serializer used to serialize and deserialize [IValue] objects.
 *          Defaults to [DftByteArraySerializerImpl].
 * @param config Configuration function for initializing the MapDB database.
 *          Defaults to a temporary file-based off-heap configuration.
 */
class MapDBStorage(
    val serializer: IValSerializer<ByteArray>,
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() }
) : IStorage {

    private val dbManager: DB = DBMaker.config().make()

    private val nodeIDs: HTreeMap<String, IValue>
    private val nodeProperties: HTreeMap<String, IValue>

    private val edgeIDs: HTreeMap<String, IValue>
    private val edgeProperties: HTreeMap<String, IValue>

    private val graphStructure: HTreeMap<String, IValue>

    private val valueSerializer = object : Serializer<IValue> {

        override fun isTrusted(): Boolean = true

        override fun serialize(out: DataOutput2, value: IValue) =
            out.write(serializer.serialize(value = value))

        override fun deserialize(input: DataInput2, available: Int): IValue =
            if (available == 0) NullVal else serializer.deserialize(input.asByteArray(available))

    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { dbManager.close() })
        nodeIDs = dbManager.hashMap("nodeIDs", Serializer.STRING, valueSerializer).create()
        edgeIDs = dbManager.hashMap("edgeIDs", Serializer.STRING, valueSerializer).create()
        nodeProperties = dbManager.hashMap("nodeProps", Serializer.STRING, valueSerializer).create()
        edgeProperties = dbManager.hashMap("edgeProps", Serializer.STRING, valueSerializer).create()
        graphStructure = dbManager.hashMap("structure", Serializer.STRING, valueSerializer).create()
    }

    constructor(config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() })
            : this(DftByteArraySerializerImpl, config)

    /**
     * Commits all changes made to the in-memory data structures or the underlying database.
     *
     * This method ensures that all pending modifications to nodes, edges, and their properties
     * are saved persistently in the database. It is typically used in systems that support transactions
     * or need to periodically ensure data integrity.
     *
     * In this case, it is a no-op for in-memory data structures but can be overridden or used
     * with an underlying database if needed.
     */
    fun commit() = dbManager.commit()

    /**
     * Closes the storage and releases any resources, including database connections and file handles.
     *
     * This method clears all in-memory data (nodes, edges, and their properties) and then closes the
     * underlying database connection, if applicable. It should be called when the storage is no longer needed
     * to ensure proper resource management and prevent memory leaks.
     *
     * After calling this method, the storage should not be used anymore.
     */
    override fun close() {
        if (!dbManager.isClosed()) dbManager.close()
    }

    override val nodeSize: Int
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return nodeIDs.size
        }

    override val nodeIDsSequence: Sequence<NodeID>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return nodeIDs.keys.asSequence().map { it.toNid }
        }

    override val edgeSize: Int
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return edgeIDs.size
        }

    override val edgeIDsSequence: Sequence<EdgeID>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return edgeIDs.keys.asSequence().map { it.toEid }
        }

    override fun containsNode(id: NodeID): Boolean {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return nodeIDs.contains(key = id.name)
    }

    override fun containsEdge(id: EdgeID): Boolean {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeIDs.contains(key = id.name)
    }

    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) {
        // Check if the node already exists.
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        // Add the properties to the node's property map.
        nodeIDs[id.name] = SetVal(newProperties.map { (key) -> key.strVal }) // first, we add a key map
        newProperties.forEach { (key, value) -> nodeProperties["${id.name}->$key"] = value }
    }

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

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val allPropKeys = (nodeIDs[id.name] as? SetVal)?.core ?: throw EntityNotExistException(id)
        return allPropKeys.associate { it.core.toString() to nodeProperties["${id.name}->${it.core}"]!! }
    }

    override fun getNodeProperty(id: NodeID, byName: String): IValue? {
        if (!containsNode(id)) throw EntityNotExistException(id)
        return nodeProperties["${id.name}->${byName}"]
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val edgeProps = (edgeIDs[id.name] as? SetVal)?.core ?: throw EntityNotExistException(id)
        return edgeProps.associate { it.core.toString() to edgeProperties["${id.name}->${it.core}"]!! }
    }

    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        return edgeProperties["${id.name}->$byName"]
    }

    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val curKeys = (nodeIDs[id.name] as SetVal).map { it.core.toString() }
        val updatedKeys = LinkedHashSet<String>(curKeys) // new key set
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


    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val curKeys = (edgeIDs[id.name] as SetVal).map { (it as StrVal).core }
        val updatedKeys = LinkedHashSet<String>(curKeys) // new key set
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

    override fun deleteNode(id: NodeID) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id).forEach { deleteEdge(it) }
        getOutgoingEdges(id).forEach { deleteEdge(it) }
        val allProps = (nodeIDs.remove(id.name) as SetVal)
        allProps.forEach { nodeProperties.remove("${id.name}->${it.core}") }
        graphStructure.remove(id.name)
    }

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

    override fun deleteEdge(id: EdgeID) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val edgePropNames = edgeIDs.remove(id.name) as SetVal
        edgePropNames.forEach { propName -> edgeProperties.remove(key = "${id.name}->$propName") }
        val prevSrcEdges = graphStructure[id.srcNid.name] as SetVal
        graphStructure[id.srcNid.name] = prevSrcEdges.also { it.core -= id.serialize }
        val prevDstEdges = graphStructure[id.dstNid.name] as SetVal
        graphStructure[id.dstNid.name] = prevDstEdges.also { it.core -= id.serialize }
    }

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
        }
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val allSerialized = graphStructure[id.name] as? SetVal ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.dstNid == id }.toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        if (!containsNode(id)) throw EntityNotExistException(id = id)
        val allSerialized = graphStructure[id.name] as? SetVal ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.srcNid == id }.toSet()
    }

    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> {
        if (!containsNode(from)) throw EntityNotExistException(id = from)
        if (!containsNode(id = to)) throw EntityNotExistException(id = to)
        val allSerialized = graphStructure[from.name] as? SetVal ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.srcNid == from && it.dstNid == to }.toSet()
    }

    override fun clear(): Boolean = try {
        graphStructure.clear()
        edgeIDs.clear(); edgeProperties.clear() // Clear edge properties
        nodeIDs.clear(); nodeProperties.clear() // Clear node properties
        graphStructure.isEmpty() && edgeProperties.isEmpty() && nodeProperties.isEmpty()
    } catch (e: DBException.VolumeIOError) {
        false
    }
}