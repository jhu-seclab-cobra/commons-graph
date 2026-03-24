package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.utils.EntityPropertyMap
import edu.jhu.cobra.commons.value.IValue
import org.mapdb.DB
import org.mapdb.DBException
import org.mapdb.DBMaker
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
    private val edgeSrcMap = HashMap<Int, Int>()
    private val edgeDstMap = HashMap<Int, Int>()
    private val edgeTagMap = HashMap<Int, String>()

    // Adjacency lists
    private val outEdges = HashMap<Int, MutableSet<Int>>()
    private val inEdges = HashMap<Int, MutableSet<Int>>()

    override fun close() = dbLock.write { if (!dbManager.isClosed()) dbManager.close() }

    override val nodeIDs: Set<Int>
        get() =
            dbLock.read {
                if (dbManager.isClosed()) throw AccessClosedStorageException()
                nodeProperties.keys.toSet()
            }

    override val edgeIDs: Set<Int>
        get() =
            dbLock.read {
                if (dbManager.isClosed()) throw AccessClosedStorageException()
                edgeProperties.keys.toSet()
            }

    override fun containsNode(id: Int): Boolean =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            nodeProperties.contains(id)
        }

    override fun containsEdge(id: Int): Boolean =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeProperties.contains(id)
        }

    override fun addNode(properties: Map<String, IValue>): Int =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            val nodeId = nodeCounter++
            nodeProperties[nodeId] = properties
            outEdges[nodeId] = HashSet()
            inEdges[nodeId] = HashSet()
            nodeId
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (!nodeProperties.contains(src)) throw EntityNotExistException(src)
            if (!nodeProperties.contains(dst)) throw EntityNotExistException(dst)
            val id = edgeCounter++
            edgeSrcMap[id] = src
            edgeDstMap[id] = dst
            edgeTagMap[id] = tag
            outEdges[src]!!.add(id)
            inEdges[dst]!!.add(id)
            edgeProperties[id] = properties
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            nodeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (nodePropMap + properties).filterValues { it != null }.mapValues { it.value!! }
        nodeProperties[id] = merged
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (curEdgeProps + properties).filterValues { it != null }.mapValues { it.value!! }
        edgeProperties[id] = merged
    }

    override fun deleteNode(id: Int) {
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (!nodeProperties.contains(id)) throw EntityNotExistException(id)

            val incoming = getIncomingEdgesWithoutLock(id)
            val outgoing = getOutgoingEdgesWithoutLock(id)

            incoming.forEach { deleteEdgeWithoutLock(it) }
            outgoing.forEach { deleteEdgeWithoutLock(it) }

            nodeProperties.remove(id)
            outEdges.remove(id)
            inEdges.remove(id)
        }
    }

    private fun getIncomingEdgesWithoutLock(id: Int): Set<Int> {
        if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
        return HashSet(inEdges[id] ?: emptySet())
    }

    private fun getOutgoingEdgesWithoutLock(id: Int): Set<Int> {
        if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
        return HashSet(outEdges[id] ?: emptySet())
    }

    private fun deleteEdgeWithoutLock(id: Int) {
        val src = edgeSrcMap.remove(id) ?: return
        val dst = edgeDstMap.remove(id) ?: return
        edgeTagMap.remove(id)
        outEdges[src]?.remove(id)
        inEdges[dst]?.remove(id)
        edgeProperties.remove(id)
    }

    override fun deleteEdge(id: Int) =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (!edgeProperties.contains(id)) throw EntityNotExistException(id)
            deleteEdgeWithoutLock(id)
        }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            val src = edgeSrcMap[id] ?: throw EntityNotExistException(id)
            val dst = edgeDstMap[id] ?: throw EntityNotExistException(id)
            val tag = edgeTagMap[id] ?: throw EntityNotExistException(id)
            IStorage.EdgeStructure(src, dst, tag)
        }

    override fun getIncomingEdges(id: Int): Set<Int> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            getIncomingEdgesWithoutLock(id)
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
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
    override fun clear() {
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            try {
                nodeCounter = 0
                edgeCounter = 0
                edgeProperties.clear()
                nodeProperties.clear()
                edgeSrcMap.clear()
                edgeDstMap.clear()
                edgeTagMap.clear()
                outEdges.clear()
                inEdges.clear()
                metaProperties.clear()
            } catch (e: DBException.VolumeIOError) {
                // swallow
            }
        }
    }

    override fun transferTo(target: IStorage): Map<Int, Int> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            val idMap = HashMap<Int, Int>()
            for (nodeId in nodeProperties.keys) {
                idMap[nodeId] = target.addNode(nodeProperties[nodeId]!!)
            }
            for (edgeId in edgeProperties.keys) {
                val src = edgeSrcMap[edgeId]!!
                val dst = edgeDstMap[edgeId]!!
                val tag = edgeTagMap[edgeId]!!
                val newSrc = idMap[src] ?: src
                val newDst = idMap[dst] ?: dst
                target.addEdge(newSrc, newDst, tag, edgeProperties[edgeId]!!)
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
            idMap
        }
}
