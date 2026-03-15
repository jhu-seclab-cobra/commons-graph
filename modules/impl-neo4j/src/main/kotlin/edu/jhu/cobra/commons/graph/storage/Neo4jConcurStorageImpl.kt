package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.utils.get
import edu.jhu.cobra.commons.graph.utils.keys
import edu.jhu.cobra.commons.graph.utils.set
import edu.jhu.cobra.commons.graph.utils.storageID
import edu.jhu.cobra.commons.value.IValue
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.dbms.api.DatabaseManagementService
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.graphdb.Direction
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.Transaction
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists

/**
 * Thread-safe implementation of [IStorage] using Neo4j 5.x embedded mode with [ReentrantReadWriteLock].
 * Provides ACID guarantees from Neo4j transactions plus read/write lock synchronization for
 * in-memory ID mapping structures.
 *
 * Edge structural info (src, dst, type) is stored natively in Neo4j relationships
 * and queried through transactions.
 *
 * For normal use cases without concurrent access, use [Neo4jStorageImpl] instead (lower overhead).
 *
 * @param graphPath The file path where the Neo4j database will be stored.
 */
@Suppress("TooManyFunctions")
class Neo4jConcurStorageImpl(
    private val graphPath: Path,
) : IStorage,
    AutoCloseable {
    private var isClosed: Boolean = false
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    private val storageLock = ReentrantReadWriteLock()

    // Bidirectional mapping: IStorage Int ID <-> Neo4j element ID
    private val intToNeo4jNode = HashMap<Int, String>()
    private val neo4jNodeToInt = HashMap<String, Int>()
    private val intToNeo4jEdge = HashMap<Int, String>()
    private val neo4jEdgeToInt = HashMap<String, Int>()

    private val metaProperties = HashMap<String, IValue>()

    private val managementService: DatabaseManagementService by lazy {
        if (graphPath.notExists()) graphPath.createDirectories()
        DatabaseManagementServiceBuilder(graphPath).build()
    }

    private val database: GraphDatabaseService by lazy {
        managementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME)
    }

    private fun <R> readTx(action: Transaction.() -> R): R {
        if (isClosed) throw AccessClosedStorageException()
        return database.beginTx().use { tx -> tx.action() }
    }

    private fun <R> writeTx(action: Transaction.() -> R): R {
        if (isClosed) throw AccessClosedStorageException()
        return database.beginTx().use { tx ->
            val result = tx.action()
            tx.commit()
            result
        }
    }

    init {
        readTx {
            getAllNodes().forEach { node ->
                val storageId = node.storageID.toInt()
                intToNeo4jNode[storageId] = node.elementId
                neo4jNodeToInt[node.elementId] = storageId
                nodeCounter = maxOf(nodeCounter, storageId + 1)
            }
            getAllRelationships().forEach { rel ->
                val storageId = rel.storageID.toInt()
                intToNeo4jEdge[storageId] = rel.elementId
                neo4jEdgeToInt[rel.elementId] = storageId
                edgeCounter = maxOf(edgeCounter, storageId + 1)
            }
        }
    }

    override val nodeIDs: Set<Int>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                intToNeo4jNode.keys.toSet()
            }

    override val edgeIDs: Set<Int>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                intToNeo4jEdge.keys.toSet()
            }

    override fun containsNode(id: Int): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            intToNeo4jNode.containsKey(id)
        }

    override fun containsEdge(id: Int): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            intToNeo4jEdge.containsKey(id)
        }

    override fun addNode(properties: Map<String, IValue>): Int =
        storageLock.write {
            writeTx {
                val nodeId = nodeCounter++
                val newNode = createNode()
                newNode.storageID = nodeId.toString()
                intToNeo4jNode[nodeId] = newNode.elementId
                neo4jNodeToInt[newNode.elementId] = nodeId
                properties.forEach { (name, value) -> newNode[name] = value }
                nodeId
            }
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        type: String,
        properties: Map<String, IValue>,
    ): Int =
        storageLock.write {
            writeTx {
                if (!intToNeo4jNode.containsKey(src)) throw EntityNotExistException(src)
                if (!intToNeo4jNode.containsKey(dst)) throw EntityNotExistException(dst)
                val id = edgeCounter++
                val srcNode = getNodeByElementId(intToNeo4jNode[src]!!)
                val dstNode = getNodeByElementId(intToNeo4jNode[dst]!!)
                val newEdge = srcNode.createRelationshipTo(dstNode, RelationshipType.withName(type))
                newEdge.storageID = id.toString()
                intToNeo4jEdge[id] = newEdge.elementId
                neo4jEdgeToInt[newEdge.elementId] = id
                properties.forEach { (name, value) -> newEdge[name] = value }
                id
            }
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        storageLock.read {
            readTx {
                if (!intToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val node = getNodeByElementId(intToNeo4jNode[id]!!)
                node.keys.associateWith { node[it]!! }
            }
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        storageLock.read {
            readTx {
                if (!intToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
                val edge = getRelationshipByElementId(intToNeo4jEdge[id]!!)
                edge.keys.associateWith { edge[it]!! }
            }
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        writeTx {
            if (!intToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
            val node = getNodeByElementId(intToNeo4jNode[id]!!)
            properties.forEach { (name, value) ->
                if (value != null) node[name] = value else node.removeProperty(name)
            }
        }
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        writeTx {
            if (!intToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
            val edge = getRelationshipByElementId(intToNeo4jEdge[id]!!)
            properties.forEach { (name, value) ->
                if (value != null) edge[name] = value else edge.removeProperty(name)
            }
        }
    }

    override fun deleteNode(id: Int) =
        storageLock.write {
            writeTx {
                if (!intToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val neo4jId = intToNeo4jNode.remove(id)!!
                neo4jNodeToInt.remove(neo4jId)
                val node = getNodeByElementId(neo4jId)
                node.relationships.forEach { edge ->
                    val edgeIntId = neo4jEdgeToInt.remove(edge.elementId)
                    if (edgeIntId != null) intToNeo4jEdge.remove(edgeIntId)
                    edge.delete()
                }
                node.delete()
            }
        }

    override fun deleteEdge(id: Int): Unit =
        storageLock.write {
            writeTx {
                if (!intToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
                val neo4jId = intToNeo4jEdge.remove(id)!!
                neo4jEdgeToInt.remove(neo4jId)
                getRelationshipByElementId(neo4jId).delete()
            }
        }

    override fun getEdgeSrc(id: Int): Int =
        storageLock.read {
            readTx {
                if (!intToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
                val rel = getRelationshipByElementId(intToNeo4jEdge[id]!!)
                neo4jNodeToInt[rel.startNode.elementId]!!
            }
        }

    override fun getEdgeDst(id: Int): Int =
        storageLock.read {
            readTx {
                if (!intToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
                val rel = getRelationshipByElementId(intToNeo4jEdge[id]!!)
                neo4jNodeToInt[rel.endNode.elementId]!!
            }
        }

    override fun getEdgeType(id: Int): String =
        storageLock.read {
            readTx {
                if (!intToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
                getRelationshipByElementId(intToNeo4jEdge[id]!!).type.name()
            }
        }

    override fun getIncomingEdges(id: Int): Set<Int> =
        storageLock.read {
            readTx {
                if (!intToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val node = getNodeByElementId(intToNeo4jNode[id]!!)
                node.getRelationships(Direction.INCOMING).mapNotNull { neo4jEdgeToInt[it.elementId] }.toSet()
            }
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        storageLock.read {
            readTx {
                if (!intToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val node = getNodeByElementId(intToNeo4jNode[id]!!)
                node.getRelationships(Direction.OUTGOING).mapNotNull { neo4jEdgeToInt[it.elementId] }.toSet()
            }
        }

    override val metaNames: Set<String>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                metaProperties.keys.toSet()
            }

    override fun getMeta(name: String): IValue? =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            metaProperties[name]
        }

    override fun setMeta(
        name: String,
        value: IValue?,
    ): Unit =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            if (value == null) metaProperties.remove(name) else metaProperties[name] = value
        }

    override fun clear() =
        storageLock.write {
            writeTx {
                getAllRelationships().forEach { it.delete() }
                getAllNodes().forEach { it.delete() }
                nodeCounter = 0
                edgeCounter = 0
                intToNeo4jNode.clear()
                neo4jNodeToInt.clear()
                intToNeo4jEdge.clear()
                neo4jEdgeToInt.clear()
                metaProperties.clear()
            }
        }

    override fun transferTo(target: IStorage) {
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val idMap = HashMap<Int, Int>()
            for (nodeId in intToNeo4jNode.keys) {
                idMap[nodeId] = target.addNode(getNodeProperties(nodeId))
            }
            for (edgeId in intToNeo4jEdge.keys) {
                val src = getEdgeSrc(edgeId)
                val dst = getEdgeDst(edgeId)
                val type = getEdgeType(edgeId)
                val newSrc = idMap[src] ?: src
                val newDst = idMap[dst] ?: dst
                target.addEdge(newSrc, newDst, type, getEdgeProperties(edgeId))
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
        }
    }

    override fun close(): Unit =
        storageLock.write {
            if (!isClosed) managementService.shutdown()
            isClosed = true
        }
}
