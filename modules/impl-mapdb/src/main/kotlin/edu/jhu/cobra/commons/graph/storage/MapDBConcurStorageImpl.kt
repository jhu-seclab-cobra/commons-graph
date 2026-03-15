package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.utils.EntityPropertyMap
import edu.jhu.cobra.commons.graph.utils.MapDbValSerializer
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.orEmpty
import org.mapdb.DB
import org.mapdb.DBException
import org.mapdb.DBMaker
import org.mapdb.Serializer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe implementation of the [IStorage] interface using MapDB for off-heap storage.
 * This implementation uses a [ReentrantReadWriteLock] to ensure thread safety.
 * There are performance overheads for the concurrency features.
 * For normal use cases, use [MapDBStorageImpl] instead.
 *
 * @param config Configuration function for initializing the MapDB database.
 *              Defaults to a temporary file-based off-heap configuration.
 */
class MapDBConcurStorageImpl(
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() },
) : IStorage {
    private val dbManager: DB = DBMaker.config().closeOnJvmShutdown().make()

    private val dbLock = ReentrantReadWriteLock()
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()
    private val nodeProperties = EntityPropertyMap(dbManager, "nodeProps")
    private val edgeProperties = EntityPropertyMap(dbManager, "edgeProps")
    private val edgeSrcMap = HashMap<String, String>()
    private val edgeDstMap = HashMap<String, String>()
    private val edgeTypeMap = HashMap<String, String>()
    private val edgeIndex = HashMap<String, String>()
    private val graphStructure =
        dbManager
            .hashMap(
                "structure",
                Serializer.STRING,
                MapDbValSerializer<SetVal>(),
            ).create()

    override fun close() = dbLock.write { if (!dbManager.isClosed()) dbManager.close() }

    override val nodeIDs: Set<String>
        get() =
            dbLock.read {
                if (dbManager.isClosed()) throw AccessClosedStorageException()
                nodeProperties.keys.toSet()
            }

    override val edgeIDs: Set<String>
        get() =
            dbLock.read {
                if (dbManager.isClosed()) throw AccessClosedStorageException()
                edgeProperties.keys.toSet()
            }

    override fun containsNode(id: String): Boolean =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            nodeProperties.contains(id)
        }

    override fun containsEdge(id: String): Boolean =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeProperties.contains(id)
        }

    override fun addNode(properties: Map<String, IValue>): String =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            val nodeId = (nodeCounter++).toString()
            nodeProperties[nodeId] = properties
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
    ): String? =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeIndex[edgeKey(src, dst, type)]
        }

    override fun addEdge(
        src: String,
        dst: String,
        type: String,
        properties: Map<String, IValue>,
    ): String =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            val key = edgeKey(src, dst, type)
            if (key in edgeIndex) throw EntityAlreadyExistException(edgeIndex[key]!!)
            if (!nodeProperties.contains(src)) throw EntityNotExistException(src)
            if (!nodeProperties.contains(dst)) throw EntityNotExistException(dst)
            val id = "e${edgeCounter++}"
            edgeIndex[key] = id
            edgeSrcMap[id] = src
            edgeDstMap[id] = dst
            edgeTypeMap[id] = type
            val prevSrcEdges = graphStructure[src].orEmpty()
            graphStructure[src] = prevSrcEdges + StrVal(id)
            val prevDstEdges = graphStructure[dst].orEmpty()
            graphStructure[dst] = prevDstEdges + StrVal(id)
            edgeProperties[id] = properties
            id
        }

    override fun getNodeProperties(id: String): Map<String, IValue> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            nodeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
        }

    override fun getEdgeProperties(id: String): Map<String, IValue> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
        }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (nodePropMap + properties).filterValues { it != null }.mapValues { it.value!! }
        nodeProperties[id] = merged
    }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (curEdgeProps + properties).filterValues { it != null }.mapValues { it.value!! }
        edgeProperties[id] = merged
    }

    override fun deleteNode(id: String) {
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (!nodeProperties.contains(id)) throw EntityNotExistException(id)

            val incomingEdges = getIncomingEdgesWithoutLock(id)
            val outgoingEdges = getOutgoingEdgesWithoutLock(id)

            incomingEdges.forEach { deleteEdgeWithoutLock(it) }
            outgoingEdges.forEach { deleteEdgeWithoutLock(it) }

            nodeProperties.remove(id)
            graphStructure.remove(id)
        }
    }

    private fun getIncomingEdgesWithoutLock(id: String): Set<String> {
        if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
        val allEdgeIds = graphStructure[id] ?: return emptySet()
        return allEdgeIds
            .asSequence()
            .map { (it as StrVal).core }
            .filter { edgeDstMap[it] == id }
            .toSet()
    }

    private fun getOutgoingEdgesWithoutLock(id: String): Set<String> {
        if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
        val allEdgeIds = graphStructure[id] ?: return emptySet()
        return allEdgeIds
            .asSequence()
            .map { (it as StrVal).core }
            .filter { edgeSrcMap[it] == id }
            .toSet()
    }

    private fun deleteEdgeWithoutLock(id: String) {
        val src = edgeSrcMap.remove(id) ?: return
        val dst = edgeDstMap.remove(id) ?: return
        val type = edgeTypeMap.remove(id) ?: return
        edgeIndex.remove(edgeKey(src, dst, type))
        edgeProperties.remove(id)
        val prevSrcEdges = graphStructure[src].orEmpty()
        graphStructure[src] = prevSrcEdges.also { it.core -= StrVal(id) }
        val prevDstEdges = graphStructure[dst].orEmpty()
        graphStructure[dst] = prevDstEdges.also { it.core -= StrVal(id) }
    }

    override fun deleteEdge(id: String) =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (!edgeProperties.contains(id)) throw EntityNotExistException(id)
            deleteEdgeWithoutLock(id)
        }

    override fun getEdgeSrc(id: String): String =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeSrcMap[id] ?: throw EntityNotExistException(id)
        }

    override fun getEdgeDst(id: String): String =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeDstMap[id] ?: throw EntityNotExistException(id)
        }

    override fun getEdgeType(id: String): String =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeTypeMap[id] ?: throw EntityNotExistException(id)
        }

    override fun getIncomingEdges(id: String): Set<String> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            getIncomingEdgesWithoutLock(id)
        }

    override fun getOutgoingEdges(id: String): Set<String> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            getOutgoingEdgesWithoutLock(id)
        }

    override val metaNames: Set<String>
        get() =
            dbLock.read {
                if (dbManager.isClosed()) throw AccessClosedStorageException()
                metaProperties.keys.toSet()
            }

    override fun getMeta(name: String): IValue? =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            metaProperties[name]
        }

    override fun setMeta(
        name: String,
        value: IValue?,
    ): Unit =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (value == null) metaProperties.remove(name) else metaProperties[name] = value
        }

    @Suppress("SwallowedException")
    override fun clear(): Boolean =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()

            try {
                nodeCounter = 0
                edgeCounter = 0
                graphStructure.clear()
                edgeProperties.clear()
                nodeProperties.clear()
                edgeIndex.clear()
                edgeSrcMap.clear()
                edgeDstMap.clear()
                edgeTypeMap.clear()
                metaProperties.clear()
                graphStructure.isEmpty() && edgeProperties.isEmpty() && nodeProperties.isEmpty()
            } catch (e: DBException.VolumeIOError) {
                false
            }
        }
}
