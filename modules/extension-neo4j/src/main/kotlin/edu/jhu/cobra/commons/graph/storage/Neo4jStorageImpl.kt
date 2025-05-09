package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.utils.get
import edu.jhu.cobra.commons.graph.utils.keys
import edu.jhu.cobra.commons.graph.utils.set
import edu.jhu.cobra.commons.graph.utils.storageID
import edu.jhu.cobra.commons.value.IValue
import org.neo4j.graphdb.Direction
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists

/**
 * Transaction-based implementation of [IStorage] using Neo4j embedded mode.
 * Provides thread-safe graph operations with ACID guarantees.
 *
 * @param graphPath The file path where the Neo4J database will be stored.
 */
class Neo4jStorageImpl(
    private val graphPath: Path
) : IStorage, AutoCloseable {

    private var isClosed: Boolean = false

    private val database: GraphDatabaseService by lazy {
        if (graphPath.notExists()) graphPath.createDirectories()
        val graphDB = GraphDatabaseFactory().newEmbeddedDatabaseBuilder(graphPath.toFile()).newGraphDatabase()
        graphDB.also { Runtime.getRuntime().addShutdownHook(Thread { it.shutdown() }) }
    }

    private fun <R> readTx(action: GraphDatabaseService.() -> R): R =
        if (isClosed) throw AccessClosedStorageException()
        else database.beginTx().use { database.action() }

    private fun <R> writeTx(action: GraphDatabaseService.() -> R): R =
        if (isClosed) throw AccessClosedStorageException()
        else database.beginTx().use { tx ->
            val results = runCatching { database.action() }
            if (results.isSuccess) tx.success() else tx.failure()
            results.getOrThrow()
        }

    private val node2ElementIdMap: ConcurrentMap<NodeID, String> = ConcurrentHashMap()
    private val edge2ElementIdMap: ConcurrentMap<EdgeID, String> = ConcurrentHashMap()

    init {
        readTx {
            allNodes.forEach { node2ElementIdMap[it.storageID] = it.id.toString() }
            allRelationships.forEach { edge2ElementIdMap[it.storageID] = it.id.toString() }
        }
    }

    override val nodeSize: Int
        get() =
            if (isClosed) throw AccessClosedStorageException() else node2ElementIdMap.size

    override val nodeIDsSequence: Sequence<NodeID>
        get() =
            if (isClosed) throw AccessClosedStorageException() else node2ElementIdMap.keys.asSequence()

    override val edgeSize: Int
        get() =
            if (isClosed) throw AccessClosedStorageException() else edge2ElementIdMap.size

    override val edgeIDsSequence: Sequence<EdgeID>
        get() =
            if (isClosed) throw AccessClosedStorageException() else edge2ElementIdMap.keys.asSequence()

    override fun containsNode(id: NodeID): Boolean =
        if (isClosed) throw AccessClosedStorageException() else node2ElementIdMap.containsKey(id)

    override fun containsEdge(id: EdgeID): Boolean =
        if (isClosed) throw AccessClosedStorageException() else edge2ElementIdMap.containsKey(id)

    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) = writeTx {
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        val newNode = createNode()
        newNode.storageID = id
        node2ElementIdMap[id] = newNode.id.toString()
        newProperties.forEach { (name, value) -> newNode[name] = value }
    }

    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) = writeTx {
        if (containsEdge(id)) throw EntityAlreadyExistException(id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        val srcNode = getNodeById(node2ElementIdMap[id.srcNid]!!.toLong())
        val dstNode = getNodeById(node2ElementIdMap[id.dstNid]!!.toLong())
        val newEdge = srcNode.createRelationshipTo(dstNode, RelationshipType.withName(id.eType))
        newEdge.storageID = id
        edge2ElementIdMap[id] = newEdge.id.toString()
        newProperties.forEach { (name, value) -> newEdge[name] = value }
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> = readTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(node2ElementIdMap[id]!!.toLong())
        node.keys.associateWith { node[it]!! }
    }

    override fun getNodeProperty(id: NodeID, byName: String): IValue? = readTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        getNodeById(node2ElementIdMap[id]!!.toLong())[byName]
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> = readTx {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val edge = getRelationshipById(edge2ElementIdMap[id]!!.toLong())
        edge.keys.associateWith { edge[it]!! }
    }

    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? = readTx {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        getRelationshipById(edge2ElementIdMap[id]!!.toLong())[byName]
    }

    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) = writeTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(node2ElementIdMap[id]!!.toLong())
        newProperties.forEach { (name, value) ->
            if (value != null) node[name] = value else node.removeProperty(name)
        }
    }

    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) = writeTx {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val edge = getRelationshipById(edge2ElementIdMap[id]!!.toLong())
        newProperties.forEach { (name, value) ->
            if (value != null) edge[name] = value else edge.removeProperty(name)
        }
    }

    override fun deleteNode(id: NodeID) = writeTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(node2ElementIdMap.remove(id)!!.toLong())
        node.relationships.forEach { edge ->
            edge2ElementIdMap.remove(edge.storageID)
            edge.delete()
        }
        node.delete()
    }

    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) = writeTx {
        node2ElementIdMap.keys.filter(doSatisfyCond).forEach { deleteNode(it) }
    }

    override fun deleteEdge(id: EdgeID) = writeTx {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        getRelationshipById(edge2ElementIdMap.remove(id)!!.toLong()).delete()
    }

    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) = writeTx {
        edge2ElementIdMap.keys.filter(doSatisfyCond).forEach { deleteEdge(it) }
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> = readTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(node2ElementIdMap[id]!!.toLong())
        node.getRelationships(Direction.INCOMING).map { it.storageID }.toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> = readTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(node2ElementIdMap[id]!!.toLong())
        node.getRelationships(Direction.OUTGOING).map { it.storageID }.toSet()
    }

    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> = readTx {
        if (!containsNode(from)) throw EntityNotExistException(from)
        if (!containsNode(to)) throw EntityNotExistException(to)
        val fromNode = getNodeById(node2ElementIdMap[from]!!.toLong())
        val allRelationships = fromNode.getRelationships(Direction.OUTGOING).asSequence()
        allRelationships.filter { it.endNode.storageID == to }.map { it.storageID }.toSet()
    }

    override fun clear(): Boolean = writeTx {
        allNodes.forEach { it.delete() }
        allRelationships.forEach { it.delete() }
        node2ElementIdMap.clear()
        edge2ElementIdMap.clear()
        true
    }

    override fun close() {
        if (!isClosed) database.shutdown()
        isClosed = true
    }
}
