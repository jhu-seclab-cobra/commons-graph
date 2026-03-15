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

    override fun close() {
        if (!dbManager.isClosed()) dbManager.close()
    }

    override val nodeIDs: Set<String>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return nodeProperties.keys.toSet()
        }

    override val edgeIDs: Set<String>
        get() {
            if (dbManager.isClosed()) throw AccessClosedStorageException()
            return edgeProperties.keys.toSet()
        }

    override fun containsNode(id: String): Boolean {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return nodeProperties.contains(id)
    }

    override fun containsEdge(id: String): Boolean {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeProperties.contains(id)
    }

    override fun addNode(properties: Map<String, IValue>): String {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodeId = (nodeCounter++).toString()
        nodeProperties[nodeId] = properties
        return nodeId
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
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeIndex[edgeKey(src, dst, type)]
    }

    override fun addEdge(
        src: String,
        dst: String,
        type: String,
        properties: Map<String, IValue>,
    ): String {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val key = edgeKey(src, dst, type)
        if (key in edgeIndex) throw EntityAlreadyExistException(edgeIndex[key]!!)
        if (!containsNode(src)) throw EntityNotExistException(src)
        if (!containsNode(dst)) throw EntityNotExistException(dst)
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
        return id
    }

    override fun getNodeProperties(id: String): Map<String, IValue> {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return nodeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeProperties(id: String): Map<String, IValue> {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (nodePropMap + properties).filterValues { it != null }.mapValues { it.value!! }
        nodeProperties[id] = merged
    }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (curEdgeProps + properties).filterValues { it != null }.mapValues { it.value!! }
        edgeProperties[id] = merged
    }

    override fun deleteNode(id: String) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id).forEach { deleteEdge(it) }
        getOutgoingEdges(id).forEach { deleteEdge(it) }
        nodeProperties.remove(id)
        graphStructure.remove(id)
    }

    override fun deleteEdge(id: String) {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val src = edgeSrcMap.remove(id)!!
        val dst = edgeDstMap.remove(id)!!
        val type = edgeTypeMap.remove(id)!!
        edgeIndex.remove(edgeKey(src, dst, type))
        edgeProperties.remove(id)
        val prevSrcEdges = graphStructure[src].orEmpty()
        graphStructure[src] = prevSrcEdges.also { it.core -= StrVal(id) }
        val prevDstEdges = graphStructure[dst].orEmpty()
        graphStructure[dst] = prevDstEdges.also { it.core -= StrVal(id) }
    }

    override fun getEdgeSrc(id: String): String {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeSrcMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeDst(id: String): String {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeDstMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeType(id: String): String {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
        return edgeTypeMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getIncomingEdges(id: String): Set<String> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val allEdgeIds = graphStructure[id] ?: return emptySet()
        return allEdgeIds
            .asSequence()
            .map { (it as StrVal).core }
            .filter { edgeDstMap[it] == id }
            .toSet()
    }

    override fun getOutgoingEdges(id: String): Set<String> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val allEdgeIds = graphStructure[id] ?: return emptySet()
        return allEdgeIds
            .asSequence()
            .map { (it as StrVal).core }
            .filter { edgeSrcMap[it] == id }
            .toSet()
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
