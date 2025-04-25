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
import org.neo4j.graphdb.*
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Transaction-based implementation of [IStorage] using Neo4j embedded mode.
 * Provides thread-safe graph operations with ACID guarantees.
 *
 * @param graphPath The file path where the Neo4J database will be stored.
 * @param serializer The serializer used for property serialization.
 */
class Neo4jStorageImpl(
    private val graphPath: Path,
    private val serializer: IValSerializer<ByteArray> = DftByteArraySerializerImpl
) : IStorage, AutoCloseable {

    private var isClosed: Boolean = false

    private val database: GraphDatabaseService by lazy {
        graphPath.createDirectories()
        GraphDatabaseFactory()
            .newEmbeddedDatabaseBuilder(graphPath.toFile())
            .newGraphDatabase()
            .also { db ->
                Runtime.getRuntime().addShutdownHook(Thread { db.shutdown() })
            }
    }

    private val node2ElementIdMap: MutableMap<NodeID, String> = mutableMapOf()
    private val edge2ElementIdMap: MutableMap<EdgeID, String> = mutableMapOf()

    init {
        loadExistingData()
    }

    private fun loadExistingData() = readTx {
        allNodes.forEach { node ->
            node2ElementIdMap[node.metaID] = node.id.toString()
        }
        allRelationships.forEach { rel ->
            edge2ElementIdMap[rel.metaID] = rel.id.toString()
        }
    }

    private fun <R> readTx(action: GraphDatabaseService.() -> R): R {
        if (isClosed) throw AccessClosedStorageException()
        return database.beginTx().use { database.action() }
    }

    private fun <R> writeTx(action: GraphDatabaseService.() -> R): R {
        if (isClosed) throw AccessClosedStorageException()
        return database.beginTx().use { tx ->
            try {
                val result = database.action()
                tx.success()
                result
            } catch (e: Exception) {
                tx.failure()
                throw e
            }
        }
    }

    private operator fun PropertyContainer.set(byName: String, newVal: IValue) {
        if (byName.startsWith("_meta_")) throw InvalidPropNameException(byName, null)
        setProperty(byName, serializer.serialize(newVal))
    }

    private operator fun PropertyContainer.get(byName: String): IValue? =
        (getProperty(byName, null) as? ByteArray)?.let(serializer::deserialize)

    private var Node.metaID: NodeID
        get() = (getProperty("_meta_id", null) as? ByteArray)?.let(serializer::deserialize)!!.core.toString().toNid
        set(value) = setProperty("_meta_id", serializer.serialize(value.name.strVal))

    private var Relationship.metaID: EdgeID
        get() = (getProperty("_meta_id", null) as? ByteArray)?.let(serializer::deserialize)!!.core.toString().toEid
        set(value) = setProperty("_meta_id", serializer.serialize(value.name.strVal))

    override val nodeSize: Int get() = node2ElementIdMap.size
    override val nodeIDsSequence: Sequence<NodeID> get() = node2ElementIdMap.keys.asSequence()
    override val edgeSize: Int get() = edge2ElementIdMap.size
    override val edgeIDsSequence: Sequence<EdgeID> get() = edge2ElementIdMap.keys.asSequence()

    override fun containsNode(id: NodeID): Boolean = node2ElementIdMap.containsKey(id)
    override fun containsEdge(id: EdgeID): Boolean = edge2ElementIdMap.containsKey(id)

    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) = writeTx {
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        val newNode = createNode()
        newNode.metaID = id
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
        newEdge.metaID = id
        edge2ElementIdMap[id] = newEdge.id.toString()

        newProperties.forEach { (name, value) -> newEdge[name] = value }
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> = readTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(node2ElementIdMap[id]!!.toLong())
        node.propertyKeys.filter { !it.startsWith("_meta_") }
            .associateWith { node[it]!! }
    }

    override fun getNodeProperty(id: NodeID, byName: String): IValue? = readTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        getNodeById(node2ElementIdMap[id]!!.toLong())[byName]
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> = readTx {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val edge = getRelationshipById(edge2ElementIdMap[id]!!.toLong())
        edge.propertyKeys.filter { !it.startsWith("_meta_") }
            .associateWith { edge[it]!! }
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
            edge2ElementIdMap.remove(edge.metaID)
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
        node.getRelationships(Direction.INCOMING).map { it.metaID }.toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> = readTx {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val node = getNodeById(node2ElementIdMap[id]!!.toLong())
        node.getRelationships(Direction.OUTGOING).map { it.metaID }.toSet()
    }

    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> = readTx {
        if (!containsNode(from)) throw EntityNotExistException(from)
        if (!containsNode(to)) throw EntityNotExistException(to)
        val fromNode = getNodeById(node2ElementIdMap[from]!!.toLong())
        fromNode.getRelationships(Direction.OUTGOING)
            .filter { it.endNode.metaID == to }
            .map { it.metaID }
            .toSet()
    }

    override fun clear(): Boolean = writeTx {
        allNodes.forEach { it.delete() }
        allRelationships.forEach { it.delete() }
        node2ElementIdMap.clear()
        edge2ElementIdMap.clear()
        true
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            database.shutdown()
        }
    }
}
