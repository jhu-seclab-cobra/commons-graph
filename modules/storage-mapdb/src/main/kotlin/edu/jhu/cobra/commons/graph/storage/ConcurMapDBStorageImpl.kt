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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe implementation of the [IStorage] interface using MapDB for off-heap storage.
 * This implementation uses MapDB's built-in concurrency features to ensure thread safety.
 * Please note that this implementation is not suitable for concurrent operations on the same storage instance. There are performance overheads for the concurrency features. For normal use cases, use [MapDBStorageImpl] instead.
 *
 * @property serializer The serializer used to convert [IValue] objects to and from byte arrays.
 * @param config Configuration function for initializing the MapDB database.
 *              Defaults to a temporary file-based off-heap configuration with concurrency enabled.
 */
class ConcurMapDBStorageImpl(
    private val serializer: IValSerializer<ByteArray>,
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() }
) : IStorage {

    private val dbManager: DB = DBMaker.config().transactionEnable().closeOnJvmShutdown().make()
    private val nodeIDs: HTreeMap<String, IValue>
    private val nodeProperties: HTreeMap<String, IValue>
    private val edgeIDs: HTreeMap<String, IValue>
    private val edgeProperties: HTreeMap<String, IValue>
    private val graphStructure: HTreeMap<String, IValue>
    private val valueSerializer = MapDbValSerializer(serializer)

    // Lock for ensuring cross-collections atomic operations
    private val dbLock = ReentrantReadWriteLock()

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
    fun commit() {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        dbManager.commit()
    }

    /**
     * Closes the storage and releases all associated resources.
     *
     * @throws AccessClosedStorageException if the storage is already closed.
     */
    override fun close() = dbLock.write {
        if (!dbManager.isClosed()) dbManager.close()
    }


    /**
     * Retrieves the total number of nodes in the storage.
     *
     * @return The number of nodes.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override val nodeSize: Int
        get() = dbLock.read {
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
        get() = dbLock.read {
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
        get() = dbLock.read {
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
        get() = dbLock.read {
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
    override fun containsNode(id: NodeID): Boolean = dbLock.read {
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
    override fun containsEdge(id: EdgeID): Boolean = dbLock.read {
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
    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            try {
                if (containsNode(id)) throw EntityAlreadyExistException(id)
                nodeIDs[id.name] = SetVal(newProperties.map { (key) -> key.strVal })
                newProperties.forEach { (key, value) -> nodeProperties["${id.name}->$key"] = value }

                // Commit the transaction
                commit()
            } catch (e: Exception) {
                // Rollback on error
                dbManager.rollback()
                throw e
            }
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
    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()

        try {
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

            // Commit the transaction
            commit()
        } catch (e: Exception) {
            // Rollback on error
            dbManager.rollback()
            throw e
        }
    }


    /**
     * Retrieves all properties of a node.
     *
     * @param id The identifier of the node.
     * @return A map of property names to their values.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun getNodeProperties(id: NodeID): Map<String, IValue> = dbLock.read {
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
    override fun getNodeProperty(id: NodeID, byName: String): IValue? = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
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
    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> = dbLock.read {
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
    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
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
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            try {
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

                // Commit the transaction
                commit()
            } catch (e: Exception) {
                // Rollback on error
                dbManager.rollback()
                throw e
            }
        }
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
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            try {
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

                // Commit the transaction
                commit()
            } catch (e: Exception) {
                // Rollback on error
                dbManager.rollback()
                throw e
            }
        }
    }

    /**
     * Removes a node and all its associated edges and properties from the storage.
     *
     * @param id The identifier of the node to remove.
     * @throws EntityNotExistException if the node does not exist.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNode(id: NodeID) {
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            try {
                if (!containsNode(id)) throw EntityNotExistException(id)

                // We need to delete all edges connected to this node
                // We use direct edge deletion to avoid race conditions
                val incomingEdges = getIncomingEdges(id)
                val outgoingEdges = getOutgoingEdges(id)

                // Delete edges inline to maintain transaction atomicity
                incomingEdges.forEach { edge ->
                    if (edgeIDs.containsKey(edge.name)) {
                        val edgePropNames = edgeIDs.remove(edge.name) as SetVal
                        edgePropNames.forEach { propName -> edgeProperties.remove("${edge.name}->$propName") }

                        // Update source node's edge list
                        val srcEdges = graphStructure[edge.srcNid.name] as SetVal
                        graphStructure[edge.srcNid.name] = srcEdges.also { it.core -= edge.serialize }

                        // Update destination node's edge list
                        val dstEdges = graphStructure[edge.dstNid.name] as SetVal
                        graphStructure[edge.dstNid.name] = dstEdges.also { it.core -= edge.serialize }
                    }
                }

                outgoingEdges.forEach { edge ->
                    if (edgeIDs.containsKey(edge.name)) {
                        val edgePropNames = edgeIDs.remove(edge.name) as SetVal
                        edgePropNames.forEach { propName -> edgeProperties.remove("${edge.name}->$propName") }

                        // Update source node's edge list
                        val srcEdges = graphStructure[edge.srcNid.name] as SetVal
                        graphStructure[edge.srcNid.name] = srcEdges.also { it.core -= edge.serialize }

                        // Update destination node's edge list
                        val dstEdges = graphStructure[edge.dstNid.name] as SetVal
                        graphStructure[edge.dstNid.name] = dstEdges.also { it.core -= edge.serialize }
                    }
                }

                // Finally, delete the node itself
                val allProps = (nodeIDs.remove(id.name) as SetVal)
                allProps.forEach { nodeProperties.remove("${id.name}->${it.core}") }
                graphStructure.remove(id.name)

                // Commit the transaction
                commit()
            } catch (e: Exception) {
                // Rollback on error
                dbManager.rollback()
                throw e
            }
        }
    }

    /**
     * Removes all nodes that satisfy the given condition.
     *
     * @param doSatisfyCond The condition to check for each node.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) {
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            try {
                // First identify all nodes to delete
                val nodesToDelete = nodeIDs.keys
                    .map { NodeID(it) }
                    .filter(doSatisfyCond)
                    .toList()

                // Now delete them one by one in a single transaction
                nodesToDelete.forEach { nodeID ->
                    // We need to delete all edges connected to this node
                    val incomingEdges = getIncomingEdges(nodeID)
                    val outgoingEdges = getOutgoingEdges(nodeID)

                    // Delete edges inline to maintain transaction atomicity
                    incomingEdges.forEach { edge ->
                        if (edgeIDs.containsKey(edge.name)) {
                            val edgePropNames = edgeIDs.remove(edge.name) as SetVal
                            edgePropNames.forEach { propName -> edgeProperties.remove("${edge.name}->$propName") }

                            // Update source node's edge list if it still exists
                            if (nodeIDs.containsKey(edge.srcNid.name)) {
                                val srcEdges = graphStructure[edge.srcNid.name] as SetVal
                                graphStructure[edge.srcNid.name] = srcEdges.also { it.core -= edge.serialize }
                            }

                            // Update destination node's edge list if it still exists
                            if (nodeIDs.containsKey(edge.dstNid.name)) {
                                val dstEdges = graphStructure[edge.dstNid.name] as SetVal
                                graphStructure[edge.dstNid.name] = dstEdges.also { it.core -= edge.serialize }
                            }
                        }
                    }

                    outgoingEdges.forEach { edge ->
                        if (edgeIDs.containsKey(edge.name)) {
                            val edgePropNames = edgeIDs.remove(edge.name) as SetVal
                            edgePropNames.forEach { propName -> edgeProperties.remove("${edge.name}->$propName") }

                            // Update source node's edge list if it still exists
                            if (nodeIDs.containsKey(edge.srcNid.name)) {
                                val srcEdges = graphStructure[edge.srcNid.name] as SetVal
                                graphStructure[edge.srcNid.name] = srcEdges.also { it.core -= edge.serialize }
                            }

                            // Update destination node's edge list if it still exists
                            if (nodeIDs.containsKey(edge.dstNid.name)) {
                                val dstEdges = graphStructure[edge.dstNid.name] as SetVal
                                graphStructure[edge.dstNid.name] = dstEdges.also { it.core -= edge.serialize }
                            }
                        }
                    }

                    // Finally, delete the node itself
                    val allProps = (nodeIDs.remove(nodeID.name) as? SetVal) ?: return@forEach
                    allProps.forEach { nodeProperties.remove("${nodeID.name}->${it.core}") }
                    graphStructure.remove(nodeID.name)
                }

                // Commit the transaction
                commit()
            } catch (e: Exception) {
                // Rollback on error
                dbManager.rollback()
                throw e
            }
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
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            try {
                if (!containsEdge(id)) throw EntityNotExistException(id)

                val edgePropNames = edgeIDs.remove(id.name) as SetVal
                edgePropNames.forEach { propName -> edgeProperties.remove(key = "${id.name}->$propName") }

                val prevSrcEdges = graphStructure[id.srcNid.name] as SetVal
                graphStructure[id.srcNid.name] = prevSrcEdges.also { it.core -= id.serialize }

                val prevDstEdges = graphStructure[id.dstNid.name] as SetVal
                graphStructure[id.dstNid.name] = prevDstEdges.also { it.core -= id.serialize }

                // Commit the transaction
                commit()
            } catch (e: Exception) {
                // Rollback on error
                dbManager.rollback()
                throw e
            }
        }
    }

    /**
     * Removes all edges that satisfy the given condition.
     *
     * @param doSatisfyCond The condition to check for each edge.
     * @throws AccessClosedStorageException if the storage is closed.
     */
    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) {
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            try {
                // First identify all edges to delete
                val edgesToDelete = edgeIDs.keys
                    .map { EdgeID(it) }
                    .filter(doSatisfyCond)
                    .toList()

                // Now delete them one by one in a single transaction
                edgesToDelete.forEach { edge ->
                    val edgePropNames = edgeIDs.remove(edge.name) as? SetVal ?: return@forEach
                    edgePropNames.forEach { propName -> edgeProperties.remove(key = "${edge.name}->$propName") }

                    // Update source node's edge list if it exists
                    if (nodeIDs.containsKey(edge.srcNid.name)) {
                        val srcEdges = graphStructure[edge.srcNid.name] as? SetVal ?: return@forEach
                        graphStructure[edge.srcNid.name] = srcEdges.also { it.core -= edge.serialize }
                    }

                    // Update destination node's edge list if it exists
                    if (nodeIDs.containsKey(edge.dstNid.name)) {
                        val dstEdges = graphStructure[edge.dstNid.name] as? SetVal ?: return@forEach
                        graphStructure[edge.dstNid.name] = dstEdges.also { it.core -= edge.serialize }
                    }
                }

                // Commit the transaction
                commit()
            } catch (e: Exception) {
                // Rollback on error
                dbManager.rollback()
                throw e
            }
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
    override fun getIncomingEdges(id: NodeID): Set<EdgeID> = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
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
    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
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
    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> = dbLock.read {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
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
    override fun clear(): Boolean = dbLock.write {
        try {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            // Clear all collections within a single transaction
            graphStructure.clear()
            edgeIDs.clear(); edgeProperties.clear()
            nodeIDs.clear(); nodeProperties.clear()

            // Commit the transaction
            commit()

            graphStructure.isEmpty() && edgeProperties.isEmpty() && nodeProperties.isEmpty()
        } catch (e: DBException.VolumeIOError) {
            // Rollback on error
            try {
                dbManager.rollback()
            } catch (ex: Exception) { /* Ignore */
            }
            false
        }
    }
}