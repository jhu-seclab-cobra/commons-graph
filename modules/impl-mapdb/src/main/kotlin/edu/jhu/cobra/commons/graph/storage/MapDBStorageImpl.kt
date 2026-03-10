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

/**
 * Implementation of the [IStorage] interface using MapDB for off-heap storage of nodes and edges.
 * This class provides efficient memory management by storing data outside the Java heap,
 * reducing garbage collection overhead and improving performance for large datasets.
 * Please notice that this implementation is not thread-safe.
 * If you need to use it in a concurrent environment, consider using [MapDBConcurStorageImpl].
 *
 * @param config Configuration function for initializing the MapDB database.
 *              Defaults to a temporary file-based off-heap configuration.
 */
class MapDBStorageImpl(
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() },
) : IStorage {
    private val dbManager: DB =
        DBMaker
            .config()
            .concurrencyDisable()
            .closeOnJvmShutdown()
            .make()

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

    override fun close() {
        if (!dbManager.isClosed()) dbManager.close()
    }

    override val nodeIDs: Set<NodeID>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return nodeProperties.keys.toSet()
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return edgeProperties.keys.toSet()
        }

    override fun containsNode(id: NodeID): Boolean {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return nodeProperties.contains(id)
    }

    override fun containsEdge(id: EdgeID): Boolean {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeProperties.contains(id)
    }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) {
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        nodeProperties[id] = properties
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) {
        if (containsEdge(id)) throw EntityAlreadyExistException(id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        val (srcName, dstName) = id.srcNid.name to id.dstNid.name
        val prevSrcEdges = graphStructure[srcName].orEmpty()
        graphStructure[srcName] = prevSrcEdges + id.serialize
        val prevDstEdges = graphStructure[dstName].orEmpty()
        graphStructure[dstName] = prevDstEdges + id.serialize
        edgeProperties[id] = properties
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return nodeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (nodePropMap + properties).filterValues { it != null }.mapValues { it.value!! }
        nodeProperties[id] = merged
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (curEdgeProps + properties).filterValues { it != null }.mapValues { it.value!! }
        edgeProperties[id] = merged
    }

    override fun deleteNode(id: NodeID) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id).forEach { deleteEdge(it) }
        getOutgoingEdges(id).forEach { deleteEdge(it) }
        nodeProperties.remove(id)
        graphStructure.remove(id.name)
    }

    override fun deleteEdge(id: EdgeID) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        edgeProperties.remove(key = id)
        val prevSrcEdges = graphStructure[id.srcNid.name].orEmpty()
        graphStructure[id.srcNid.name] = prevSrcEdges.also { it.core -= id.serialize }
        val prevDstEdges = graphStructure[id.dstNid.name].orEmpty()
        graphStructure[id.dstNid.name] = prevDstEdges.also { it.core -= id.serialize }
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val allSerialized = graphStructure[id.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.dstNid == id }.toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        if (!containsNode(id = id)) throw EntityNotExistException(id = id)
        val allSerialized = graphStructure[id.name] ?: return emptySet()
        val allEdgeIDs = allSerialized.asSequence().map { EdgeID(it as ListVal) }
        return allEdgeIDs.filter { it.srcNid == id }.toSet()
    }

    override val metaNames: Set<String>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return metaProperties.keys.toSet()
        }

    override fun getMeta(name: String): IValue? {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return metaProperties[name]
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (value == null) metaProperties.remove(name) else metaProperties[name] = value
    }

    @Suppress("SwallowedException")
    override fun clear(): Boolean =
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
