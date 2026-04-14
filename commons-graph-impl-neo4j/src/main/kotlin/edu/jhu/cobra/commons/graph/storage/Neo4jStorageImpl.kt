package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.utils.get
import edu.jhu.cobra.commons.graph.utils.keys
import edu.jhu.cobra.commons.graph.utils.set
import edu.jhu.cobra.commons.value.IValue
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.dbms.api.DatabaseManagementService
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.graphdb.Direction
import org.neo4j.graphdb.Label
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.Transaction
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists

internal const val SID = "__sid__"
internal const val TAG = "__tag__"
internal val NODE_LABEL: Label = Label.label("_N")
internal val EDGE_TYPE: RelationshipType = RelationshipType.withName("_E")

/**
 * Non-concurrent [IStorage] using Neo4j 5.x embedded mode with zero in-memory ID mappings.
 *
 * All entity lookups go through Neo4j transactions and indexed properties.
 * Nodes use a schema index on [SID]; edges use a range index on [SID] via
 * the single relationship type [EDGE_TYPE]. The edge tag is stored as a
 * native string property [TAG] on each relationship.
 *
 * This trades per-operation latency for unlimited capacity — the only limit
 * is disk space, not JVM heap.
 *
 * For concurrent access, use [Neo4jConcurStorageImpl] instead.
 *
 * @param graphPath The file path where the Neo4j database will be stored.
 */
@Suppress("TooManyFunctions")
class Neo4jStorageImpl(
    private val graphPath: Path,
) : IStorage,
    AutoCloseable {
    private var isClosed: Boolean = false
    private val metaProperties = HashMap<String, IValue>()

    private val managementService: DatabaseManagementService by lazy {
        if (graphPath.notExists()) graphPath.createDirectories()
        DatabaseManagementServiceBuilder(graphPath).build()
    }

    private val database by lazy {
        val db = managementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME)
        db.beginTx().use { tx ->
            val schema = tx.schema()
            if (schema.indexes.none { it.isNodeIndex && SID in it.propertyKeys }) {
                schema.indexFor(NODE_LABEL).on(SID).withName("idx_node_sid").create()
            }
            tx.commit()
        }
        db.beginTx().use { tx ->
            if (tx.schema().indexes.none { it.isRelationshipIndex && SID in it.propertyKeys }) {
                tx.execute("CREATE RANGE INDEX idx_rel_sid FOR ()-[r:_E]-() ON (r.`$SID`)")
                tx.commit()
            }
        }
        db.beginTx().use { tx ->
            tx.schema().awaitIndexesOnline(30, TimeUnit.SECONDS)
        }
        db
    }

    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    init {
        database.beginTx().use { tx ->
            var maxNodeSid = -1
            for (node in tx.findNodes(NODE_LABEL)) {
                val sid = (node.getProperty(SID) as Long).toInt()
                if (sid > maxNodeSid) maxNodeSid = sid
            }
            nodeCounter = maxNodeSid + 1

            var maxEdgeSid = -1
            for (rel in tx.findRelationships(EDGE_TYPE)) {
                val sid = (rel.getProperty(SID) as Long).toInt()
                if (sid > maxEdgeSid) maxEdgeSid = sid
            }
            edgeCounter = maxEdgeSid + 1
        }
    }

    private fun <R> readTx(action: Transaction.() -> R): R {
        ensureOpen()
        return database.beginTx().use { tx -> tx.action() }
    }

    private fun <R> writeTx(action: Transaction.() -> R): R {
        ensureOpen()
        return database.beginTx().use { tx ->
            val result = tx.action()
            tx.commit()
            result
        }
    }

    private fun ensureOpen() {
        if (isClosed) throw AccessClosedStorageException()
    }

    private fun Transaction.findNodeBySid(id: Int) =
        findNode(NODE_LABEL, SID, id.toLong())

    private fun Transaction.findEdgeBySid(id: Int) =
        findRelationship(EDGE_TYPE, SID, id.toLong())

    override val nodeIDs: Set<Int>
        get() = readTx {
            val ids = mutableSetOf<Int>()
            for (node in findNodes(NODE_LABEL)) {
                ids.add((node.getProperty(SID) as Long).toInt())
            }
            ids
        }

    override val edgeIDs: Set<Int>
        get() = readTx {
            val ids = mutableSetOf<Int>()
            for (rel in findRelationships(EDGE_TYPE)) {
                ids.add((rel.getProperty(SID) as Long).toInt())
            }
            ids
        }

    override fun containsNode(id: Int): Boolean =
        readTx { findNodeBySid(id) != null }

    override fun containsEdge(id: Int): Boolean =
        readTx { findEdgeBySid(id) != null }

    override fun addNode(properties: Map<String, IValue>): Int =
        writeTx {
            val id = nodeCounter++
            val newNode = createNode(NODE_LABEL)
            newNode.setProperty(SID, id.toLong())
            for ((name, value) in properties) {
                newNode[name] = value
            }
            id
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int =
        writeTx {
            val srcNode = findNodeBySid(src) ?: throw EntityNotExistException(src)
            val dstNode = findNodeBySid(dst) ?: throw EntityNotExistException(dst)
            val id = edgeCounter++
            val newEdge = srcNode.createRelationshipTo(dstNode, EDGE_TYPE)
            newEdge.setProperty(SID, id.toLong())
            newEdge.setProperty(TAG, tag)
            for ((name, value) in properties) {
                newEdge[name] = value
            }
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        readTx {
            val node = findNodeBySid(id) ?: throw EntityNotExistException(id)
            node.keys.associateWith { key ->
                requireNotNull(node[key]) { "Property '$key' on node $id has corrupted data" }
            }
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        readTx {
            val edge = findEdgeBySid(id) ?: throw EntityNotExistException(id)
            edge.keys.associateWith { key ->
                requireNotNull(edge[key]) { "Property '$key' on edge $id has corrupted data" }
            }
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = writeTx {
        val node = findNodeBySid(id) ?: throw EntityNotExistException(id)
        for ((name, value) in properties) {
            if (value != null) node[name] = value else node.removeProperty(name)
        }
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = writeTx {
        val edge = findEdgeBySid(id) ?: throw EntityNotExistException(id)
        for ((name, value) in properties) {
            if (value != null) edge[name] = value else edge.removeProperty(name)
        }
    }

    override fun deleteNode(id: Int) =
        writeTx {
            val node = findNodeBySid(id) ?: throw EntityNotExistException(id)
            for (edge in node.relationships) {
                edge.delete()
            }
            node.delete()
        }

    override fun deleteEdge(id: Int): Unit =
        writeTx {
            val edge = findEdgeBySid(id) ?: throw EntityNotExistException(id)
            edge.delete()
        }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure =
        readTx {
            val edge = findEdgeBySid(id) ?: throw EntityNotExistException(id)
            val src = (edge.startNode.getProperty(SID) as Long).toInt()
            val dst = (edge.endNode.getProperty(SID) as Long).toInt()
            val tag = edge.getProperty(TAG) as String
            IStorage.EdgeStructure(src, dst, tag)
        }

    override fun getIncomingEdges(id: Int): Set<Int> =
        readTx {
            val node = findNodeBySid(id) ?: throw EntityNotExistException(id)
            node.getRelationships(Direction.INCOMING)
                .map { (it.getProperty(SID) as Long).toInt() }
                .toSet()
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        readTx {
            val node = findNodeBySid(id) ?: throw EntityNotExistException(id)
            node.getRelationships(Direction.OUTGOING)
                .map { (it.getProperty(SID) as Long).toInt() }
                .toSet()
        }

    override val metaNames: Set<String>
        get() {
            ensureOpen()
            return metaProperties.keys.toSet()
        }

    override fun getMeta(name: String): IValue? {
        ensureOpen()
        return metaProperties[name]
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        ensureOpen()
        if (value == null) metaProperties.remove(name) else metaProperties[name] = value
    }

    override fun clear() =
        writeTx {
            for (rel in findRelationships(EDGE_TYPE)) rel.delete()
            for (node in findNodes(NODE_LABEL)) node.delete()
            metaProperties.clear()
            nodeCounter = 0
            edgeCounter = 0
        }

    override fun transferTo(target: IStorage): Map<Int, Int> =
        readTx {
            val idMap = HashMap<Int, Int>()
            for (node in findNodes(NODE_LABEL)) {
                val oldId = (node.getProperty(SID) as Long).toInt()
                val props = node.keys.associateWith { key ->
                    requireNotNull(node[key]) { "Property '$key' on node $oldId has corrupted data" }
                }
                idMap[oldId] = target.addNode(props)
            }
            for (rel in findRelationships(EDGE_TYPE)) {
                val src = (rel.startNode.getProperty(SID) as Long).toInt()
                val dst = (rel.endNode.getProperty(SID) as Long).toInt()
                val tag = rel.getProperty(TAG) as String
                val relSid = (rel.getProperty(SID) as Long).toInt()
                val props = rel.keys.associateWith { key ->
                    requireNotNull(rel[key]) { "Property '$key' on edge $relSid has corrupted data" }
                }
                val newSrc = idMap.getValue(src)
                val newDst = idMap.getValue(dst)
                target.addEdge(newSrc, newDst, tag, props)
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
            idMap
        }

    override fun close() {
        if (!isClosed) managementService.shutdown()
        isClosed = true
    }
}
