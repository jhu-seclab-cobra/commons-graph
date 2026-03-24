package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.utils.EntityPropertyMap
import edu.jhu.cobra.commons.value.IValue
import org.mapdb.DB
import org.mapdb.DBException
import org.mapdb.DBMaker

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
    private val edgeSrcMap = HashMap<Int, Int>()
    private val edgeDstMap = HashMap<Int, Int>()
    private val edgeTagMap = HashMap<Int, String>()

    // Adjacency lists
    private val outEdges = HashMap<Int, MutableSet<Int>>()
    private val inEdges = HashMap<Int, MutableSet<Int>>()

    private fun ensureOpen() {
        if (dbManager.isClosed()) throw AccessClosedStorageException()
    }

    override fun close() {
        if (!dbManager.isClosed()) dbManager.close()
    }

    override val nodeIDs: Set<Int>
        get() {
            ensureOpen()
            return nodeProperties.keys.toSet()
        }

    override val edgeIDs: Set<Int>
        get() {
            ensureOpen()
            return edgeProperties.keys.toSet()
        }

    override fun containsNode(id: Int): Boolean {
        ensureOpen()
        return nodeProperties.contains(id)
    }

    override fun containsEdge(id: Int): Boolean {
        ensureOpen()
        return edgeProperties.contains(id)
    }

    override fun addNode(properties: Map<String, IValue>): Int {
        ensureOpen()
        val nodeId = nodeCounter++
        nodeProperties[nodeId] = properties
        outEdges[nodeId] = HashSet()
        inEdges[nodeId] = HashSet()
        return nodeId
    }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int {
        ensureOpen()
        if (!containsNode(src)) throw EntityNotExistException(src)
        if (!containsNode(dst)) throw EntityNotExistException(dst)
        val id = edgeCounter++
        edgeSrcMap[id] = src
        edgeDstMap[id] = dst
        edgeTagMap[id] = tag
        outEdges[src]!!.add(id)
        inEdges[dst]!!.add(id)
        edgeProperties[id] = properties
        return id
    }

    override fun getNodeProperties(id: Int): Map<String, IValue> {
        ensureOpen()
        return nodeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeProperties(id: Int): Map<String, IValue> {
        ensureOpen()
        return edgeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (nodePropMap + properties).filterValues { it != null }.mapValues { it.value!! }
        nodeProperties[id] = merged
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
        val merged = (curEdgeProps + properties).filterValues { it != null }.mapValues { it.value!! }
        edgeProperties[id] = merged
    }

    override fun deleteNode(id: Int) {
        ensureOpen()
        if (!containsNode(id)) throw EntityNotExistException(id)
        // Delete incident edges (copy sets to avoid concurrent modification)
        HashSet(inEdges[id] ?: emptySet()).forEach { deleteEdge(it) }
        HashSet(outEdges[id] ?: emptySet()).forEach { deleteEdge(it) }
        nodeProperties.remove(id)
        outEdges.remove(id)
        inEdges.remove(id)
    }

    override fun deleteEdge(id: Int) {
        ensureOpen()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val src = edgeSrcMap.remove(id)!!
        val dst = edgeDstMap.remove(id)!!
        edgeTagMap.remove(id)
        outEdges[src]?.remove(id)
        inEdges[dst]?.remove(id)
        edgeProperties.remove(id)
    }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure {
        ensureOpen()
        val src = edgeSrcMap[id] ?: throw EntityNotExistException(id)
        val dst = edgeDstMap[id] ?: throw EntityNotExistException(id)
        val tag = edgeTagMap[id] ?: throw EntityNotExistException(id)
        return IStorage.EdgeStructure(src, dst, tag)
    }

    override fun getIncomingEdges(id: Int): Set<Int> {
        ensureOpen()
        if (!containsNode(id)) throw EntityNotExistException(id)
        return inEdges[id] ?: emptySet()
    }

    override fun getOutgoingEdges(id: Int): Set<Int> {
        ensureOpen()
        if (!containsNode(id)) throw EntityNotExistException(id)
        return outEdges[id] ?: emptySet()
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

    @Suppress("SwallowedException")
    override fun clear() {
        ensureOpen()
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

    override fun transferTo(target: IStorage): Map<Int, Int> {
        ensureOpen()
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
        return idMap
    }
}
