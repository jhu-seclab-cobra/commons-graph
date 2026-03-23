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
 * Uses auto-generated Int IDs externally, mapping them to Neo4j element IDs
 * internally. The Int ID is persisted as a `storageID` property on Neo4j entities
 * for recovery after database restart.
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
    private val neo4jToIntNode = HashMap<String, Int>()
    private val intToNeo4jEdge = HashMap<Int, String>()
    private val neo4jToIntEdge = HashMap<String, Int>()

    // Edge structural info cached in memory
    private val edgeSrcMap = HashMap<Int, Int>()
    private val edgeDstMap = HashMap<Int, Int>()
    private val edgeTagMap = HashMap<Int, String>()

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
            for (node in getAllNodes()) {
                val intId = node.storageID.toInt()
                intToNeo4jNode[intId] = node.elementId
                neo4jToIntNode[node.elementId] = intId
                if (intId >= nodeCounter) nodeCounter = intId + 1
            }
            for (rel in getAllRelationships()) {
                val intId = rel.storageID.toInt()
                intToNeo4jEdge[intId] = rel.elementId
                neo4jToIntEdge[rel.elementId] = intId
                edgeSrcMap[intId] = neo4jToIntNode[rel.startNode.elementId]!!
                edgeDstMap[intId] = neo4jToIntNode[rel.endNode.elementId]!!
                edgeTagMap[intId] = rel.type.name()
                if (intId >= edgeCounter) edgeCounter = intId + 1
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
                val id = nodeCounter++
                val newNode = createNode()
                newNode.storageID = id.toString()
                intToNeo4jNode[id] = newNode.elementId
                neo4jToIntNode[newNode.elementId] = id
                for ((name, value) in properties) {
                    newNode[name] = value
                }
                id
            }
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int =
        storageLock.write {
            writeTx {
                if (!intToNeo4jNode.containsKey(src)) throw EntityNotExistException(src)
                if (!intToNeo4jNode.containsKey(dst)) throw EntityNotExistException(dst)
                val id = edgeCounter++
                val srcNode = getNodeByElementId(intToNeo4jNode[src]!!)
                val dstNode = getNodeByElementId(intToNeo4jNode[dst]!!)
                val newEdge = srcNode.createRelationshipTo(dstNode, RelationshipType.withName(tag))
                newEdge.storageID = id.toString()
                intToNeo4jEdge[id] = newEdge.elementId
                neo4jToIntEdge[newEdge.elementId] = id
                edgeSrcMap[id] = src
                edgeDstMap[id] = dst
                edgeTagMap[id] = tag
                for ((name, value) in properties) {
                    newEdge[name] = value
                }
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
            for ((name, value) in properties) {
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
            for ((name, value) in properties) {
                if (value != null) edge[name] = value else edge.removeProperty(name)
            }
        }
    }

    override fun deleteNode(id: Int) =
        storageLock.write {
            writeTx {
                if (!intToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val neo4jId = intToNeo4jNode.remove(id)!!
                neo4jToIntNode.remove(neo4jId)
                val node = getNodeByElementId(neo4jId)
                for (edge in node.relationships) {
                    val edgeIntId = neo4jToIntEdge.remove(edge.elementId)
                    if (edgeIntId != null) {
                        intToNeo4jEdge.remove(edgeIntId)
                        edgeSrcMap.remove(edgeIntId)
                        edgeDstMap.remove(edgeIntId)
                        edgeTagMap.remove(edgeIntId)
                    }
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
                neo4jToIntEdge.remove(neo4jId)
                edgeSrcMap.remove(id)
                edgeDstMap.remove(id)
                edgeTagMap.remove(id)
                getRelationshipByElementId(neo4jId).delete()
            }
        }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val src = edgeSrcMap[id] ?: throw EntityNotExistException(id)
            val dst = edgeDstMap[id] ?: throw EntityNotExistException(id)
            val tag = edgeTagMap[id] ?: throw EntityNotExistException(id)
            IStorage.EdgeStructure(src, dst, tag)
        }

    override fun getIncomingEdges(id: Int): Set<Int> =
        storageLock.read {
            readTx {
                if (!intToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val node = getNodeByElementId(intToNeo4jNode[id]!!)
                node.getRelationships(Direction.INCOMING).mapNotNull { neo4jToIntEdge[it.elementId] }.toSet()
            }
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        storageLock.read {
            readTx {
                if (!intToNeo4jNode.containsKey(id)) throw EntityNotExistException(id)
                val node = getNodeByElementId(intToNeo4jNode[id]!!)
                node.getRelationships(Direction.OUTGOING).mapNotNull { neo4jToIntEdge[it.elementId] }.toSet()
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
                for (rel in getAllRelationships()) rel.delete()
                for (node in getAllNodes()) node.delete()
                intToNeo4jNode.clear()
                neo4jToIntNode.clear()
                intToNeo4jEdge.clear()
                neo4jToIntEdge.clear()
                edgeSrcMap.clear()
                edgeDstMap.clear()
                edgeTagMap.clear()
                metaProperties.clear()
                nodeCounter = 0
                edgeCounter = 0
            }
        }

    override fun transferTo(target: IStorage): Map<Int, Int> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val idMap = HashMap<Int, Int>()
            for (nodeId in intToNeo4jNode.keys) {
                idMap[nodeId] = target.addNode(getNodeProperties(nodeId))
            }
            for (edgeId in intToNeo4jEdge.keys) {
                val structure = getEdgeStructure(edgeId)
                val newSrc = idMap[structure.src] ?: structure.src
                val newDst = idMap[structure.dst] ?: structure.dst
                target.addEdge(newSrc, newDst, structure.tag, getEdgeProperties(edgeId))
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
            idMap
        }

    override fun close(): Unit =
        storageLock.write {
            if (!isClosed) managementService.shutdown()
            isClosed = true
        }
}
