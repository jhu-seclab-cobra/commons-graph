package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.utils.EntityPropertyMap
import edu.jhu.cobra.commons.graph.utils.MapDbValSerializer
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.SetVal
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
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()
    private val nodeProperties = EntityPropertyMap<NodeID>(dbManager, "nodeProps")
    private val edgeProperties = EntityPropertyMap<EdgeID>(dbManager, "edgeProps")
    private val graphStructure =
        dbManager
            .hashMap(
                "structure",
                Serializer.STRING,
                MapDbValSerializer<SetVal>(),
            ).create()

    override fun close() = dbLock.write { if (!dbManager.isClosed()) dbManager.close() }

    override val nodeIDs: Set<NodeID>
        get() =
            dbLock.read {
                if (dbManager.isClosed()) throw AccessClosedStorageException()
                nodeProperties.keys.toSet()
            }

    override val edgeIDs: Set<EdgeID>
        get() =
            dbLock.read {
                if (dbManager.isClosed()) throw AccessClosedStorageException()
                edgeProperties.keys.toSet()
            }

    override fun containsNode(id: NodeID): Boolean =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            nodeProperties.contains(id)
        }

    override fun containsEdge(id: EdgeID): Boolean =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeProperties.contains(id)
        }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (nodeProperties.contains(id)) throw EntityAlreadyExistException(id)
        nodeProperties[id] = properties
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (edgeProperties.contains(id)) throw EntityAlreadyExistException(id)
        if (!nodeProperties.contains(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!nodeProperties.contains(id.dstNid)) throw EntityNotExistException(id.dstNid)

        val (srcName, dstName) = id.srcNid.name to id.dstNid.name
        val prevSrcEdges = graphStructure[srcName].orEmpty()
        graphStructure[srcName] = prevSrcEdges + id.serialize
        val prevDstEdges = graphStructure[dstName].orEmpty()
        graphStructure[dstName] = prevDstEdges + id.serialize
        edgeProperties[id] = properties
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            nodeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
        }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            edgeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
        }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (nodePropMap + properties).filterValues { it != null }.mapValues { it.value!! }
        nodeProperties[id] = merged
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) = dbLock.write {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (curEdgeProps + properties).filterValues { it != null }.mapValues { it.value!! }
        edgeProperties[id] = merged
    }

    override fun deleteNode(id: NodeID) {
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (!nodeProperties.contains(id)) throw EntityNotExistException(id)

            val incomingEdges = getIncomingEdgesWithoutLock(id)
            val outgoingEdges = getOutgoingEdgesWithoutLock(id)

            incomingEdges.forEach { deleteEdgeWithoutLock(it) }
            outgoingEdges.forEach { deleteEdgeWithoutLock(it) }

            nodeProperties.remove(id)
            graphStructure.remove(id.name)
        }
    }

    private fun getIncomingEdgesWithoutLock(id: NodeID): Set<EdgeID> {
        if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
        val allSerialized = graphStructure[id.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.dstNid == id }.toSet()
    }

    private fun getOutgoingEdgesWithoutLock(id: NodeID): Set<EdgeID> {
        if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
        val allSerialized = graphStructure[id.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.srcNid == id }.toSet()
    }

    private fun deleteEdgeWithoutLock(id: EdgeID) {
        edgeProperties.remove(key = id)
        val prevSrcEdges = graphStructure[id.srcNid.name].orEmpty()
        graphStructure[id.srcNid.name] = prevSrcEdges.also { it.core -= id.serialize }
        val prevDstEdges = graphStructure[id.dstNid.name].orEmpty()
        graphStructure[id.dstNid.name] = prevDstEdges.also { it.core -= id.serialize }
    }

    override fun deleteEdge(id: EdgeID) =
        dbLock.write {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            if (!edgeProperties.contains(id)) throw EntityNotExistException(id)
            deleteEdgeWithoutLock(id)
        }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> =
        dbLock.read {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            getIncomingEdgesWithoutLock(id)
        }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> =
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
                graphStructure.clear()
                edgeProperties.clear()
                nodeProperties.clear()
                metaProperties.clear()
                graphStructure.isEmpty() && edgeProperties.isEmpty() && nodeProperties.isEmpty()
            } catch (e: DBException.VolumeIOError) {
                false
            }
        }
}
