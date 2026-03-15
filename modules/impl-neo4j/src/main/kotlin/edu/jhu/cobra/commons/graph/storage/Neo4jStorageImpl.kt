package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
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
 * @param graphPath The file path where the Neo4J database will be stored.
 */
class Neo4jStorageImpl(
    private val graphPath: Path,
) : IStorage,
    AutoCloseable {
    private var isClosed: Boolean = false
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

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

    // Maps storage-generated node ID → Neo4j internal element ID
    private val node2ElementIdMap: ConcurrentMap<String, String> = ConcurrentHashMap()

    // Maps storage edge ID → Neo4j internal element ID
    private val edge2ElementIdMap: ConcurrentMap<String, String> = ConcurrentHashMap()

    // Edge structural info
    private val edgeSrcMap: ConcurrentMap<String, String> = ConcurrentHashMap()
    private val edgeDstMap: ConcurrentMap<String, String> = ConcurrentHashMap()
    private val edgeTypeMap: ConcurrentMap<String, String> = ConcurrentHashMap()

    // Reverse index: (src, dst, type) → edgeId for O(1) lookup
    private val edgeIndex: ConcurrentMap<String, String> = ConcurrentHashMap()

    init {
        readTx {
            allNodes.forEach { node ->
                val storageId = node.storageID
                node2ElementIdMap[storageId] = node.id.toString()
                nodeCounter = maxOf(nodeCounter, storageId.toIntOrNull()?.plus(1) ?: nodeCounter)
            }
            allRelationships.forEach { rel ->
                val edgeId = rel.storageID
                edge2ElementIdMap[edgeId] = rel.id.toString()
                val srcId = rel.startNode.storageID
                val dstId = rel.endNode.storageID
                edgeSrcMap[edgeId] = srcId
                edgeDstMap[edgeId] = dstId
                edgeTypeMap[edgeId] = rel.type.name()
                edgeIndex[edgeKey(srcId, dstId, rel.type.name())] = edgeId
                edgeCounter = maxOf(edgeCounter, edgeId.removePrefix("e").toIntOrNull()?.plus(1) ?: edgeCounter)
            }
        }
    }

    override val nodeIDs: Set<String>
        get() =
            if (isClosed) throw AccessClosedStorageException() else node2ElementIdMap.keys.toSet()

    override val edgeIDs: Set<String>
        get() =
            if (isClosed) throw AccessClosedStorageException() else edge2ElementIdMap.keys.toSet()

    override fun containsNode(id: String): Boolean =
        if (isClosed) throw AccessClosedStorageException() else node2ElementIdMap.containsKey(id)

    override fun containsEdge(id: String): Boolean =
        if (isClosed) throw AccessClosedStorageException() else edge2ElementIdMap.containsKey(id)

    override fun addNode(properties: Map<String, IValue>): String =
        writeTx {
            val nodeId = (nodeCounter++).toString()
            val newNode = createNode()
            newNode.storageID = nodeId
            node2ElementIdMap[nodeId] = newNode.id.toString()
            properties.forEach { (name, value) -> newNode[name] = value }
            nodeId
        }

    private fun edgeKey(
        src: String,
        dst: String,
        type: String,
    ): String = "$src\u0000$type\u0000$dst"

    override fun findEdge(
        src: String,
        dst: String,
        type: String,
    ): String? {
        if (isClosed) throw AccessClosedStorageException()
        return edgeIndex[edgeKey(src, dst, type)]
    }

    override fun addEdge(
        src: String,
        dst: String,
        type: String,
        properties: Map<String, IValue>,
    ): String =
        writeTx {
            val key = edgeKey(src, dst, type)
            val existingId = edgeIndex[key]
            if (existingId != null) throw EntityAlreadyExistException(existingId)
            if (!containsNode(src)) throw EntityNotExistException(src)
            if (!containsNode(dst)) throw EntityNotExistException(dst)
            val id = "e${edgeCounter++}"
            val srcNode = getNodeById(node2ElementIdMap[src]!!.toLong())
            val dstNode = getNodeById(node2ElementIdMap[dst]!!.toLong())
            val newEdge = srcNode.createRelationshipTo(dstNode, RelationshipType.withName(type))
            newEdge.storageID = id
            edge2ElementIdMap[id] = newEdge.id.toString()
            edgeIndex[key] = id
            edgeSrcMap[id] = src
            edgeDstMap[id] = dst
            edgeTypeMap[id] = type
            properties.forEach { (name, value) -> newEdge[name] = value }
            id
        }

    override fun getNodeProperties(id: String): Map<String, IValue> =
        readTx {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val node = getNodeById(node2ElementIdMap[id]!!.toLong())
            node.keys.associateWith { node[it]!! }
        }

    override fun getEdgeProperties(id: String): Map<String, IValue> =
        readTx {
            if (!containsEdge(id)) throw EntityNotExistException(id)
            val edge = getRelationshipById(edge2ElementIdMap[id]!!.toLong())
            edge.keys.associateWith { edge[it]!! }
        }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = writeTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(node2ElementIdMap[id]!!.toLong())
        properties.forEach { (name, value) ->
            if (value != null) node[name] = value else node.removeProperty(name)
        }
    }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = writeTx {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val edge = getRelationshipById(edge2ElementIdMap[id]!!.toLong())
        properties.forEach { (name, value) ->
            if (value != null) edge[name] = value else edge.removeProperty(name)
        }
    }

    override fun deleteNode(id: String) =
        writeTx {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val node = getNodeById(node2ElementIdMap.remove(id)!!.toLong())
            node.relationships.forEach { edge ->
                val edgeId = edge.storageID
                edge2ElementIdMap.remove(edgeId)
                val src = edgeSrcMap.remove(edgeId)
                val dst = edgeDstMap.remove(edgeId)
                val type = edgeTypeMap.remove(edgeId)
                if (src != null && dst != null && type != null) edgeIndex.remove(edgeKey(src, dst, type))
                edge.delete()
            }
            node.delete()
        }

    override fun deleteEdge(id: String): Unit =
        writeTx {
            if (!containsEdge(id)) throw EntityNotExistException(id)
            val src = edgeSrcMap.remove(id)!!
            val dst = edgeDstMap.remove(id)!!
            val type = edgeTypeMap.remove(id)!!
            edgeIndex.remove(edgeKey(src, dst, type))
            getRelationshipById(edge2ElementIdMap.remove(id)!!.toLong()).delete()
            Unit
        }

    override fun getEdgeSrc(id: String): String {
        if (isClosed) throw AccessClosedStorageException()
        return edgeSrcMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeDst(id: String): String {
        if (isClosed) throw AccessClosedStorageException()
        return edgeDstMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeType(id: String): String {
        if (isClosed) throw AccessClosedStorageException()
        return edgeTypeMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getIncomingEdges(id: String): Set<String> =
        readTx {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val node = getNodeById(node2ElementIdMap[id]!!.toLong())
            node.getRelationships(Direction.INCOMING).map { it.storageID }.toSet()
        }

    override fun getOutgoingEdges(id: String): Set<String> =
        readTx {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val node = getNodeById(node2ElementIdMap[id]!!.toLong())
            node.getRelationships(Direction.OUTGOING).map { it.storageID }.toSet()
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

    override fun clear(): Boolean =
        writeTx {
            allNodes.forEach { it.delete() }
            allRelationships.forEach { it.delete() }
            nodeCounter = 0
            edgeCounter = 0
            node2ElementIdMap.clear()
            edge2ElementIdMap.clear()
            edgeIndex.clear()
            edgeSrcMap.clear()
            edgeDstMap.clear()
            edgeTypeMap.clear()
            metaProperties.clear()
            true
        }

    override fun close() {
        if (!isClosed) database.shutdown()
        isClosed = true
    }
}
