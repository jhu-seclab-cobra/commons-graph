package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
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
 * Stores caller-provided String IDs as the `storageID` property on Neo4j nodes and
 * relationships, and maintains a bidirectional map between String IDs and Neo4j element IDs
 * for fast lookup without full-graph scans.
 *
 * Edge structural info (src, dst, tag) is stored as the Neo4j relationship type (tag) and
 * endpoints (src/dst), and recovered via [getEdgeStructure].
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

    private val storageLock = ReentrantReadWriteLock()

    // Bidirectional mapping: IStorage String ID <-> Neo4j element ID
    private val stringToNeo4jNode = HashMap<String, String>()
    private val neo4jToStringNode = HashMap<String, String>()
    private val stringToNeo4jEdge = HashMap<String, String>()
    private val neo4jToStringEdge = HashMap<String, String>()

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
                val sid = node.storageID
                stringToNeo4jNode[sid] = node.elementId
                neo4jToStringNode[node.elementId] = sid
            }
            getAllRelationships().forEach { rel ->
                val sid = rel.storageID
                stringToNeo4jEdge[sid] = rel.elementId
                neo4jToStringEdge[rel.elementId] = sid
            }
        }
    }

    override val nodeIDs: Set<String>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                stringToNeo4jNode.keys.toSet()
            }

    override val edgeIDs: Set<String>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                stringToNeo4jEdge.keys.toSet()
            }

    override fun containsNode(id: String): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            stringToNeo4jNode.containsKey(id)
        }

    override fun containsEdge(id: String): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            stringToNeo4jEdge.containsKey(id)
        }

    override fun addNode(nodeId: String, properties: Map<String, IValue>): String =
        storageLock.write {
            writeTx {
                if (stringToNeo4jNode.containsKey(nodeId)) throw EntityAlreadyExistException(nodeId)
                val newNode = createNode()
                newNode.storageID = nodeId
                stringToNeo4jNode[nodeId] = newNode.elementId
                neo4jToStringNode[newNode.elementId] = nodeId
                properties.forEach { (name, value) -> newNode[name] = value }
                nodeId
            }
        }

    override fun addEdge(
        src: String,
        dst: String,
        edgeId: String,
        tag: String,
        properties: Map<String, IValue>,
    ): String =
        storageLock.write {
            writeTx {
                if (!stringToNeo4jNode.containsKey(src)) throw EntityNotExistException(src)
                if (!stringToNeo4jNode.containsKey(dst)) throw EntityNotExistException(dst)
                if (stringToNeo4jEdge.containsKey(edgeId)) throw EntityAlreadyExistException(edgeId)
                val srcNode = getNodeByElementId(stringToNeo4jNode[src]!!)
                val dstNode = getNodeByElementId(stringToNeo4jNode[dst]!!)
                val newEdge = srcNode.createRelationshipTo(dstNode, RelationshipType.withName(tag))
                newEdge.storageID = edgeId
                stringToNeo4jEdge[edgeId] = newEdge.elementId
                neo4jToStringEdge[newEdge.elementId] = edgeId
                properties.forEach { (name, value) -> newEdge[name] = value }
                edgeId
            }
        }

    override fun getNodeProperties(id: String): Map<String, IValue> =
        storageLock.read {
            readTx {
                if (!stringToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val node = getNodeByElementId(stringToNeo4jNode[id]!!)
                node.keys.associateWith { node[it]!! }
            }
        }

    override fun getEdgeProperties(id: String): Map<String, IValue> =
        storageLock.read {
            readTx {
                if (!stringToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
                val edge = getRelationshipByElementId(stringToNeo4jEdge[id]!!)
                edge.keys.associateWith { edge[it]!! }
            }
        }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        writeTx {
            if (!stringToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
            val node = getNodeByElementId(stringToNeo4jNode[id]!!)
            properties.forEach { (name, value) ->
                if (value != null) node[name] = value else node.removeProperty(name)
            }
        }
    }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        writeTx {
            if (!stringToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
            val edge = getRelationshipByElementId(stringToNeo4jEdge[id]!!)
            properties.forEach { (name, value) ->
                if (value != null) edge[name] = value else edge.removeProperty(name)
            }
        }
    }

    override fun deleteNode(id: String) =
        storageLock.write {
            writeTx {
                if (!stringToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val neo4jId = stringToNeo4jNode.remove(id)!!
                neo4jToStringNode.remove(neo4jId)
                val node = getNodeByElementId(neo4jId)
                node.relationships.forEach { edge ->
                    val edgeStrId = neo4jToStringEdge.remove(edge.elementId)
                    if (edgeStrId != null) stringToNeo4jEdge.remove(edgeStrId)
                    edge.delete()
                }
                node.delete()
            }
        }

    override fun deleteEdge(id: String): Unit =
        storageLock.write {
            writeTx {
                if (!stringToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
                val neo4jId = stringToNeo4jEdge.remove(id)!!
                neo4jToStringEdge.remove(neo4jId)
                getRelationshipByElementId(neo4jId).delete()
            }
        }

    override fun getEdgeStructure(id: String): IStorage.EdgeStructure =
        storageLock.read {
            readTx {
                if (!stringToNeo4jEdge.containsKey(id)) throw EntityNotExistException(id)
                val rel = getRelationshipByElementId(stringToNeo4jEdge[id]!!)
                val src = neo4jToStringNode[rel.startNode.elementId]!!
                val dst = neo4jToStringNode[rel.endNode.elementId]!!
                IStorage.EdgeStructure(src, dst, rel.type.name())
            }
        }

    override fun getIncomingEdges(id: String): Set<String> =
        storageLock.read {
            readTx {
                if (!stringToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val node = getNodeByElementId(stringToNeo4jNode[id]!!)
                node.getRelationships(Direction.INCOMING).mapNotNull { neo4jToStringEdge[it.elementId] }.toSet()
            }
        }

    override fun getOutgoingEdges(id: String): Set<String> =
        storageLock.read {
            readTx {
                if (!stringToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val node = getNodeByElementId(stringToNeo4jNode[id]!!)
                node.getRelationships(Direction.OUTGOING).mapNotNull { neo4jToStringEdge[it.elementId] }.toSet()
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
                stringToNeo4jNode.clear()
                neo4jToStringNode.clear()
                stringToNeo4jEdge.clear()
                neo4jToStringEdge.clear()
                metaProperties.clear()
            }
        }

    override fun transferTo(target: IStorage) {
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            for (nodeId in stringToNeo4jNode.keys) {
                target.addNode(nodeId, getNodeProperties(nodeId))
            }
            for (edgeId in stringToNeo4jEdge.keys) {
                val structure = getEdgeStructure(edgeId)
                target.addEdge(structure.src, structure.dst, edgeId, structure.tag, getEdgeProperties(edgeId))
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
