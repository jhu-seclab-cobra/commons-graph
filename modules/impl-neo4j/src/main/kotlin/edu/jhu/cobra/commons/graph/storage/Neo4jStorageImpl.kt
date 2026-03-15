package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
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
 * Maps Int-based IStorage IDs to Neo4j's internal Long IDs via bidirectional hash maps.
 *
 * @param graphPath The file path where the Neo4J database will be stored.
 */
class Neo4jStorageImpl(
    private val graphPath: Path,
) : IStorage,
    AutoCloseable {
    private var isClosed: Boolean = false
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    // Bidirectional mapping: IStorage Int ID <-> Neo4j Long ID
    private val intToNeo4jNode = HashMap<Int, Long>()
    private val neo4jNodeToInt = HashMap<Long, Int>()
    private val intToNeo4jEdge = HashMap<Int, Long>()
    private val neo4jEdgeToInt = HashMap<Long, Int>()

    // Edge structural info
    private val edgeSrcMap: ConcurrentMap<Int, Int> = ConcurrentHashMap()
    private val edgeDstMap: ConcurrentMap<Int, Int> = ConcurrentHashMap()
    private val edgeTypeMap: ConcurrentMap<Int, String> = ConcurrentHashMap()

    private val database: GraphDatabaseService by lazy {
        if (graphPath.notExists()) graphPath.createDirectories()
        val graphDB = GraphDatabaseFactory().newEmbeddedDatabaseBuilder(graphPath.toFile()).newGraphDatabase()
        graphDB.also { Runtime.getRuntime().addShutdownHook(Thread { it.shutdown() }) }
    }

    private fun <R> readTx(action: GraphDatabaseService.() -> R): R =
        if (isClosed) {
            throw AccessClosedStorageException()
        } else {
            database.beginTx().use { database.action() }
        }

    private fun <R> writeTx(action: GraphDatabaseService.() -> R): R =
        if (isClosed) {
            throw AccessClosedStorageException()
        } else {
            database.beginTx().use { tx ->
                val results = runCatching { database.action() }
                if (results.isSuccess) tx.success() else tx.failure()
                results.getOrThrow()
            }
        }

    private val metaProperties: ConcurrentMap<String, IValue> = ConcurrentHashMap()

    init {
        readTx {
            allNodes.forEach { node ->
                val storageIdStr = node.storageID
                val storageId = storageIdStr.toInt()
                intToNeo4jNode[storageId] = node.id
                neo4jNodeToInt[node.id] = storageId
                nodeCounter = maxOf(nodeCounter, storageId + 1)
            }
            allRelationships.forEach { rel ->
                val storageIdStr = rel.storageID
                val storageId = storageIdStr.toInt()
                intToNeo4jEdge[storageId] = rel.id
                neo4jEdgeToInt[rel.id] = storageId
                val srcNeo4jId = rel.startNode.id
                val dstNeo4jId = rel.endNode.id
                val srcInt = neo4jNodeToInt[srcNeo4jId]!!
                val dstInt = neo4jNodeToInt[dstNeo4jId]!!
                edgeSrcMap[storageId] = srcInt
                edgeDstMap[storageId] = dstInt
                edgeTypeMap[storageId] = rel.type.name()
                edgeCounter = maxOf(edgeCounter, storageId + 1)
            }
        }
    }

    override val nodeIDs: Set<Int>
        get() =
            if (isClosed) throw AccessClosedStorageException() else intToNeo4jNode.keys.toSet()

    override val edgeIDs: Set<Int>
        get() =
            if (isClosed) throw AccessClosedStorageException() else intToNeo4jEdge.keys.toSet()

    override fun containsNode(id: Int): Boolean =
        if (isClosed) throw AccessClosedStorageException() else intToNeo4jNode.containsKey(id)

    override fun containsEdge(id: Int): Boolean =
        if (isClosed) throw AccessClosedStorageException() else intToNeo4jEdge.containsKey(id)

    override fun addNode(properties: Map<String, IValue>): Int =
        writeTx {
            val nodeId = nodeCounter++
            val newNode = createNode()
            newNode.storageID = nodeId.toString()
            intToNeo4jNode[nodeId] = newNode.id
            neo4jNodeToInt[newNode.id] = nodeId
            properties.forEach { (name, value) -> newNode[name] = value }
            nodeId
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        type: String,
        properties: Map<String, IValue>,
    ): Int =
        writeTx {
            if (!containsNode(src)) throw EntityNotExistException(src)
            if (!containsNode(dst)) throw EntityNotExistException(dst)
            val id = edgeCounter++
            val srcNode = getNodeById(intToNeo4jNode[src]!!)
            val dstNode = getNodeById(intToNeo4jNode[dst]!!)
            val newEdge = srcNode.createRelationshipTo(dstNode, RelationshipType.withName(type))
            newEdge.storageID = id.toString()
            intToNeo4jEdge[id] = newEdge.id
            neo4jEdgeToInt[newEdge.id] = id
            edgeSrcMap[id] = src
            edgeDstMap[id] = dst
            edgeTypeMap[id] = type
            properties.forEach { (name, value) -> newEdge[name] = value }
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        readTx {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val node = getNodeById(intToNeo4jNode[id]!!)
            node.keys.associateWith { node[it]!! }
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        readTx {
            if (!containsEdge(id)) throw EntityNotExistException(id)
            val edge = getRelationshipById(intToNeo4jEdge[id]!!)
            edge.keys.associateWith { edge[it]!! }
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = writeTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(intToNeo4jNode[id]!!)
        properties.forEach { (name, value) ->
            if (value != null) node[name] = value else node.removeProperty(name)
        }
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = writeTx {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val edge = getRelationshipById(intToNeo4jEdge[id]!!)
        properties.forEach { (name, value) ->
            if (value != null) edge[name] = value else edge.removeProperty(name)
        }
    }

    override fun deleteNode(id: Int) =
        writeTx {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val neo4jId = intToNeo4jNode.remove(id)!!
            neo4jNodeToInt.remove(neo4jId)
            val node = getNodeById(neo4jId)
            node.relationships.forEach { edge ->
                val edgeNeo4jId = edge.id
                val edgeIntId = neo4jEdgeToInt.remove(edgeNeo4jId)
                if (edgeIntId != null) {
                    intToNeo4jEdge.remove(edgeIntId)
                    edgeSrcMap.remove(edgeIntId)
                    edgeDstMap.remove(edgeIntId)
                    edgeTypeMap.remove(edgeIntId)
                }
                edge.delete()
            }
            node.delete()
        }

    override fun deleteEdge(id: Int): Unit =
        writeTx {
            if (!containsEdge(id)) throw EntityNotExistException(id)
            edgeSrcMap.remove(id)
            edgeDstMap.remove(id)
            edgeTypeMap.remove(id)
            val neo4jId = intToNeo4jEdge.remove(id)!!
            neo4jEdgeToInt.remove(neo4jId)
            getRelationshipById(neo4jId).delete()
            Unit
        }

    override fun getEdgeSrc(id: Int): Int {
        if (isClosed) throw AccessClosedStorageException()
        return edgeSrcMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeDst(id: Int): Int {
        if (isClosed) throw AccessClosedStorageException()
        return edgeDstMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeType(id: Int): String {
        if (isClosed) throw AccessClosedStorageException()
        return edgeTypeMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getIncomingEdges(id: Int): Set<Int> =
        readTx {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val node = getNodeById(intToNeo4jNode[id]!!)
            node.getRelationships(Direction.INCOMING).mapNotNull { neo4jEdgeToInt[it.id] }.toSet()
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        readTx {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val node = getNodeById(intToNeo4jNode[id]!!)
            node.getRelationships(Direction.OUTGOING).mapNotNull { neo4jEdgeToInt[it.id] }.toSet()
        }

    override val metaNames: Set<String>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return metaProperties.keys.toSet()
        }

    override fun getMeta(name: String): IValue? {
        if (isClosed) throw AccessClosedStorageException()
        return metaProperties[name]
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        if (value == null) metaProperties.remove(name) else metaProperties[name] = value
    }

    override fun clear() =
        writeTx {
            allRelationships.forEach { it.delete() }
            allNodes.forEach { it.delete() }
            nodeCounter = 0
            edgeCounter = 0
            intToNeo4jNode.clear()
            neo4jNodeToInt.clear()
            intToNeo4jEdge.clear()
            neo4jEdgeToInt.clear()
            edgeSrcMap.clear()
            edgeDstMap.clear()
            edgeTypeMap.clear()
            metaProperties.clear()
        }

    override fun transferTo(target: IStorage) {
        if (isClosed) throw AccessClosedStorageException()
        val idMap = HashMap<Int, Int>()
        for (nodeId in intToNeo4jNode.keys) {
            idMap[nodeId] = target.addNode(getNodeProperties(nodeId))
        }
        for (edgeId in intToNeo4jEdge.keys) {
            val src = edgeSrcMap[edgeId]!!
            val dst = edgeDstMap[edgeId]!!
            val type = edgeTypeMap[edgeId]!!
            val newSrc = idMap[src] ?: src
            val newDst = idMap[dst] ?: dst
            target.addEdge(newSrc, newDst, type, getEdgeProperties(edgeId))
        }
        for (name in metaProperties.keys) {
            target.setMeta(name, metaProperties[name])
        }
    }

    override fun close() {
        if (!isClosed) database.shutdown()
        isClosed = true
    }
}
