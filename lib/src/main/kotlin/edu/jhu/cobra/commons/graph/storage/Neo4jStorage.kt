package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.entity.toEid
import edu.jhu.cobra.commons.graph.entity.toNid
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import edu.jhu.cobra.commons.value.strVal
import org.neo4j.dbms.api.DatabaseManagementService
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.graphdb.*
import java.nio.file.Path
import kotlin.collections.set
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

/**
 * Implementation of [IStorage] using Neo4J embedded mode.
 *
 * This class uses Neo4J in embedded mode to manage nodes and edges, along with their properties. The database is stored
 * locally, and the nodes and edges are mapped using internal structures for efficient lookup and manipulation.
 *
 * @param graphPath The file path where the Neo4J database will be stored. Defaults to a temporary directory.
 * @param serializer The serializer used to serialize and deserialize properties of nodes and edges. Defaults to
 *                      [DftByteArraySerializerImpl] for byte array serialization.
 */
class Neo4jStorage(
    graphPath: Path,
    private val serializer: IValSerializer<ByteArray>
) : IStorage {

    private var isClosed: Boolean = false

    private val dbManager: DatabaseManagementService by lazy {
        val dbPath = graphPath.resolve("neo4j.db").also { it.createDirectories() }
        if (dbPath.exists()) dbPath.toFile().deleteRecursively()
        val managementService = DatabaseManagementServiceBuilder(dbPath).build()
        Runtime.getRuntime().addShutdownHook(Thread { managementService.shutdown() })
        managementService
    }

    /** Lazily initializes the Neo4J database in embedded mode. */
    private val database: GraphDatabaseService by lazy { dbManager.database("neo4j") }

    /** Lazy initialization of a map that stores the mapping between [NodeID] and the Neo4J element IDs.*/
    private val node2ElementIdMap: MutableMap<NodeID, String> by lazyReadTx {
        val output = mutableMapOf<NodeID, String>() // output for the nodes holder
        output.apply { allNodes.forEach { put(it.metaID, it.elementId) } }
    }

    /** Lazy initialization of a map that stores the mapping between [EdgeID] and the Neo4J element IDs.*/
    private val edge2ElementIdMap: MutableMap<EdgeID, String> by lazyReadTx {
        val output = mutableMapOf<EdgeID, String>() // output for the edges holder
        output.apply { allRelationships.forEach { output[it.metaID] = it.elementId } }
    }

    /**
     * Executes a read-only transaction within the given action block.
     * Throws an exception if the database storage has been closed.
     *
     * @param action The action to perform within the transaction.
     * @throws AccessClosedStorageException if the storage is closed.
     * @return The result of the action performed within the transaction.
     */
    private fun <R> readTx(action: Transaction.() -> R) =
        if (isClosed) throw AccessClosedStorageException()
        else database.beginTx().use(action)

    /**
     * Executes a given action within a write transaction. If the storage is closed, an exception is thrown.
     *
     * @param action The action to perform within the transaction. The action is a lambda with receiver of type Transaction.
     * @throws AccessClosedStorageException if the storage is closed.
     * @return The result of the action.
     */
    private fun <R> writeTx(action: Transaction.() -> R) =
        if (isClosed) throw AccessClosedStorageException()
        else database.beginTx().use { action(it); it.commit() }

    /**
     * Lazy initialization of a transaction action.
     *
     * @param action The action to perform within the transaction.
     * @return The result of the action wrapped in a lazy delegate.
     */
    private fun <R> lazyReadTx(action: Transaction.() -> R) = lazy { database.beginTx().use(action) }

    /**
     * Sets the property value of a Neo4J [Entity].
     *
     * @param byName The name of the property to set.
     * @param newVal The new value to set, which will be serialized before storage.
     */
    private operator fun Entity.set(byName: String, newVal: IValue) =
        setProperty(byName, serializer.serialize(newVal))

    /**
     * Gets the property value of a Neo4J [Entity] by property name.
     *
     * @param byName The name of the property to retrieve.
     * @return The deserialized property value as [IValue], or null if not present.
     */
    private operator fun Entity.get(byName: String) =
        (getProperty(byName) as? ByteArray)?.let(serializer::deserialize)

    private fun Entity.getAll(vararg names: String) =
        getProperties(*names).mapValues { (it.value as ByteArray).let(serializer::deserialize) }

    private var Node.metaID: NodeID
        get() = get("_meta_id")!!.core.toString().toNid
        set(value) = set("_meta_id", value.name.strVal)

    private var Relationship.metaID: EdgeID
        get() = get("_meta_id")!!.core.toString().toEid
        set(value) = set("_meta_id", value.name.strVal)

    override val nodeSize: Int
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return node2ElementIdMap.size
        }

    override val nodeIDsSequence: Sequence<NodeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return node2ElementIdMap.keys.asSequence()
        }

    override val edgeSize: Int
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return edge2ElementIdMap.size
        }

    override val edgeIDsSequence: Sequence<EdgeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return edge2ElementIdMap.keys.asSequence()
        }

    constructor(graphPath: Path = createTempDirectory("graph")) : this(graphPath, DftByteArraySerializerImpl)

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return node2ElementIdMap.containsKey(id)
    }

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return edge2ElementIdMap.containsKey(id)
    }

    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) = writeTx {
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        val newNode = createNode().apply { metaID = id.name.toNid }
        node2ElementIdMap[id] = newNode.elementId
        newProperties.forEach { (propName, propVal) ->
            val isInvalidName = propName.startsWith("_meta_")
            if (isInvalidName) throw InvalidPropNameException(propName, id)
            newNode[propName] = propVal
        }
    }

    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) = writeTx {
        if (containsEdge(id)) throw EntityAlreadyExistException(id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        val fNode = getNodeByElementId(node2ElementIdMap[id.srcNid]!!)
        val tNode = getNodeByElementId(node2ElementIdMap[id.dstNid]!!)
        val newEdge = fNode.createRelationshipTo(tNode) { id.eType }
        newEdge.metaID = id.name.toEid
        edge2ElementIdMap[id] = newEdge.elementId
        newProperties.forEach { (propName, propVal) ->
            val isInvalidName = propName.startsWith("_meta_")
            if (isInvalidName) throw InvalidPropNameException(propName, id)
            newEdge[propName] = propVal
        }
    }


    override fun getNodeProperties(id: NodeID): Map<String, IValue> = readTx {
        if (!containsNode(id = id)) throw EntityNotExistException(id = id)
        val tarNode = getNodeByElementId(node2ElementIdMap[id]!!)
        val propNames = tarNode.propertyKeys.filter { !it.startsWith("_meta_") }
        tarNode.getAll(*propNames.toTypedArray()).mapValues { it.value }
    }

    override fun getNodeProperty(id: NodeID, byName: String): IValue? = readTx {
        if (!containsNode(id = id)) throw EntityNotExistException(id = id)
        val tarNode = getNodeByElementId(node2ElementIdMap[id]!!)
        if (byName !in tarNode.propertyKeys) null else tarNode[byName]
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> = readTx {
        if (!containsEdge(id = id)) throw EntityNotExistException(id = id)
        val tarEdge = getRelationshipByElementId(edge2ElementIdMap[id]!!)
        val propNames = tarEdge.propertyKeys.filter { !it.startsWith("_meta_") }
        tarEdge.getAll(*propNames.toTypedArray())
    }

    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? = readTx {
        if (!containsEdge(id = id)) throw EntityNotExistException(id = id)
        val tarEdge = getRelationshipByElementId(edge2ElementIdMap[id]!!)
        if (byName !in tarEdge.propertyKeys) null else tarEdge[byName]
    }

    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) = writeTx {
        if (!containsNode(id = id)) throw EntityNotExistException(id = id)
        val tarNode = getNodeByElementId(node2ElementIdMap[id]!!)
        val allNodesPropNames = tarNode.propertyKeys.toSet()
        newProperties.forEach { (propName, propValue) ->
            if (propName !in allNodesPropNames) return@forEach
            if (propValue != null) tarNode[propName] = propValue
            else tarNode.removeProperty(propName)
        }
    }

    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) = writeTx {
        if (!containsEdge(id = id)) throw EntityNotExistException(id = id)
        val tarEdge = getRelationshipByElementId(edge2ElementIdMap[id]!!)
        val allEdgePropNames = tarEdge.propertyKeys.toSet()
        newProperties.forEach { (propName, propValue) ->
            if (propName !in allEdgePropNames) return@forEach
            if (propValue != null) tarEdge[propName] = propValue
            else tarEdge.removeProperty(propName)
        }
    }

    override fun deleteNode(id: NodeID): Unit = writeTx {
        if (!containsNode(id = id)) throw EntityNotExistException(id = id)
        val tarNode = getNodeByElementId(node2ElementIdMap.remove(id)!!)
        tarNode.relationships.forEach { edge ->
            edge2ElementIdMap.remove(edge.metaID)
            edge.delete() // delete the edge itself
        }
        node2ElementIdMap.remove(id)
        tarNode.delete() // delete the node itself
    }

    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) = writeTx {
        val nidIterator = node2ElementIdMap.keys.iterator()
        while (nidIterator.hasNext()) {
            val curNodeID = nidIterator.next().takeIf(doSatisfyCond) ?: continue
            val curNode = getNodeByElementId(node2ElementIdMap[curNodeID]!!)
            curNode.relationships.forEach { edge2ElementIdMap.remove(it.metaID); it.delete() }
            nidIterator.remove()
            curNode.delete()
        }
    }

    override fun deleteEdge(id: EdgeID) = writeTx {
        if (!containsEdge(id = id)) throw EntityNotExistException(id = id)
        getRelationshipByElementId(edge2ElementIdMap.remove(id)!!).delete()
    }

    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) = writeTx {
        val eidIterator = edge2ElementIdMap.keys.iterator()
        while (eidIterator.hasNext()) {
            val curEdgeID = eidIterator.next().takeIf(doSatisfyCond) ?: continue
            val curEdge = getRelationshipByElementId(edge2ElementIdMap[curEdgeID]!!)
            curEdge.delete(); eidIterator.remove() // Remove the edge ID
        }
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> = readTx {
        if (!containsNode(id = id)) throw EntityNotExistException(id = id)
        val tarNode = getNodeByElementId(node2ElementIdMap[id]!!)
        tarNode.getRelationships(Direction.INCOMING).map { it.metaID }.toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> = readTx {
        if (!containsNode(id = id)) throw EntityNotExistException(id = id)
        val tarNode = getNodeByElementId(node2ElementIdMap[id]!!)
        tarNode.getRelationships(Direction.OUTGOING).map { it.metaID }.toSet()
    }

    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> = readTx {
        if (!containsNode(id = from)) throw EntityNotExistException(id = from)
        if (!containsNode(id = to)) throw EntityNotExistException(id = to)
        val fromNode = getNodeByElementId(node2ElementIdMap[from]!!)
        val fromOutgoing = fromNode.getRelationships(Direction.OUTGOING)
        fromOutgoing.filter { it.endNode.metaID == to }.map { it.metaID }.toSet()
    }

    override fun clear(): Boolean = runCatching {
        database.beginTx().use { tx ->
            tx.allNodes.forEach { it.delete() }
            tx.allRelationships.forEach { it.delete() }
        } // remove all nodes and edges properties
        node2ElementIdMap.clear() // Clear node IDs map
        edge2ElementIdMap.clear() // Clear edge IDs map
        node2ElementIdMap.isEmpty() && edge2ElementIdMap.isEmpty()
    }.getOrElse { false }

    override fun close() {
        isClosed = true
        dbManager.shutdown()
    }
}
