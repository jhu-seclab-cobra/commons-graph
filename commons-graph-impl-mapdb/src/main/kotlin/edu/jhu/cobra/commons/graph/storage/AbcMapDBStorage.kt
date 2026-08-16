package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import org.mapdb.DB
import org.mapdb.Serializer

/**
 * Shared [IStorage] engine backed by MapDB for off-heap storage of nodes and edges.
 *
 * Entity properties live in MapDB-backed [EntityPropertyMap]s; edge structure and
 * metadata live in MapDB maps as well, so a database reopened from file restores the
 * full graph. Adjacency indices are on-heap and rebuilt from the persisted edge
 * structure on open. Property maps are returned as snapshot copies, never live views.
 *
 * Subclasses supply the database instance and the concurrency guards: [MapDBStorageImpl]
 * disables MapDB concurrency and passes actions through unguarded; [MapDBConcurStorageImpl]
 * wraps them in a read-write lock.
 *
 * @param dbManager The MapDB database holding the entity property maps.
 */
@Suppress("TooManyFunctions")
public abstract class AbcMapDBStorage protected constructor(
    private val dbManager: DB,
) : IStorage,
    AutoCloseable {
    private val metaProperties: MutableMap<String, IValue> =
        dbManager.hashMap("metaProps", Serializer.STRING, EntityPropertyMap.SERIALIZER_IVALUE).createOrOpen()
    private val nodeProperties = EntityPropertyMap(dbManager, "nodeProps")
    private val edgeProperties = EntityPropertyMap(dbManager, "edgeProps")

    // Counters resume past the highest persisted ID so a database reopened from
    // file never hands out an ID that overwrites an existing entity.
    private var nodeCounter: Int = (nodeProperties.keys.maxOrNull() ?: -1) + 1
    private var edgeCounter: Int = (edgeProperties.keys.maxOrNull() ?: -1) + 1
    private val edgeSrcMap: MutableMap<Int, Int> =
        dbManager.hashMap("edgeSrc", Serializer.INTEGER, Serializer.INTEGER).createOrOpen()
    private val edgeDstMap: MutableMap<Int, Int> =
        dbManager.hashMap("edgeDst", Serializer.INTEGER, Serializer.INTEGER).createOrOpen()
    private val edgeTagMap: MutableMap<Int, String> =
        dbManager.hashMap("edgeTag", Serializer.INTEGER, Serializer.STRING).createOrOpen()

    // On-heap adjacency index, rebuilt from the persisted edge structure on open.
    private val outEdges = HashMap<Int, MutableSet<Int>>()
    private val inEdges = HashMap<Int, MutableSet<Int>>()

    init {
        for (nodeId in nodeProperties.keys) {
            outEdges[nodeId] = HashSet()
            inEdges[nodeId] = HashSet()
        }
        for ((edgeId, src) in edgeSrcMap) {
            outEdges.getValue(src).add(edgeId)
            inEdges.getValue(edgeDstMap.getValue(edgeId)).add(edgeId)
        }
    }

    /** Runs [action] under this implementation's read guard. */
    protected abstract fun <R> readGuarded(action: () -> R): R

    /** Runs [action] under this implementation's write guard. */
    protected abstract fun <R> writeGuarded(action: () -> R): R

    override fun close(): Unit = writeGuarded { if (!dbManager.isClosed()) dbManager.close() }

    override fun flush() {
        // No buffered writes; mutations are applied immediately.
    }

    override val nodeIDs: Set<Int>
        get() = readGuarded { nodeProperties.keys.toSet() }

    override val edgeIDs: Set<Int>
        get() = readGuarded { edgeProperties.keys.toSet() }

    override fun containsNode(id: Int): Boolean = readGuarded { nodeProperties.contains(id) }

    override fun containsEdge(id: Int): Boolean = readGuarded { edgeProperties.contains(id) }

    override fun addNode(properties: Map<String, IValue>): Int =
        writeGuarded {
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
        writeGuarded {
            if (!nodeProperties.contains(src)) throw EntityNotExistException(src)
            if (!nodeProperties.contains(dst)) throw EntityNotExistException(dst)
            val id = edgeCounter++
            edgeSrcMap[id] = src
            edgeDstMap[id] = dst
            edgeTagMap[id] = tag
            outEdges.getValue(src).add(id)
            inEdges.getValue(dst).add(id)
            edgeProperties[id] = properties
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        readGuarded {
            nodeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        readGuarded {
            edgeProperties[id]?.toMap() ?: throw EntityNotExistException(id)
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        writeGuarded {
            val nodePropMap = nodeProperties[id] ?: throw EntityNotExistException(id)
            nodePropMap.update(properties)
        }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        writeGuarded {
            val edgePropMap = edgeProperties[id] ?: throw EntityNotExistException(id)
            edgePropMap.update(properties)
        }

    override fun deleteNode(id: Int): Unit =
        writeGuarded {
            if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
            HashSet(inEdges[id] ?: emptySet()).forEach { removeEdgeUnguarded(it) }
            HashSet(outEdges[id] ?: emptySet()).forEach { removeEdgeUnguarded(it) }
            nodeProperties.remove(id)
            outEdges.remove(id)
            inEdges.remove(id)
        }

    override fun deleteEdge(id: Int): Unit =
        writeGuarded {
            if (!edgeProperties.contains(id)) throw EntityNotExistException(id)
            removeEdgeUnguarded(id)
        }

    private fun removeEdgeUnguarded(id: Int) {
        val src = edgeSrcMap.remove(id) ?: return
        val dst = edgeDstMap.remove(id) ?: return
        edgeTagMap.remove(id)
        outEdges[src]?.remove(id)
        inEdges[dst]?.remove(id)
        edgeProperties.remove(id)
    }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure =
        readGuarded {
            val src = edgeSrcMap[id] ?: throw EntityNotExistException(id)
            val dst = edgeDstMap[id] ?: throw EntityNotExistException(id)
            val tag = edgeTagMap[id] ?: throw EntityNotExistException(id)
            IStorage.EdgeStructure(src, dst, tag)
        }

    override fun getIncomingEdges(id: Int): Set<Int> =
        readGuarded {
            if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
            HashSet(inEdges[id] ?: emptySet())
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        readGuarded {
            if (!nodeProperties.contains(id)) throw EntityNotExistException(id)
            HashSet(outEdges[id] ?: emptySet())
        }

    override val metaNames: Set<String>
        get() = readGuarded { metaProperties.keys.toSet() }

    override fun getMeta(name: String): IValue? = readGuarded { metaProperties[name] }

    override fun setMeta(
        name: String,
        value: IValue?,
    ): Unit =
        writeGuarded {
            if (value == null) metaProperties.remove(name) else metaProperties[name] = value
        }

    override fun clear(): Unit =
        writeGuarded {
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
        }

    override fun transferTo(target: IStorage): Map<Int, Int> =
        readGuarded {
            val idMap = HashMap<Int, Int>()
            for (nodeId in nodeProperties.keys) {
                idMap[nodeId] = target.addNode(nodeProperties.getValue(nodeId))
            }
            for (edgeId in edgeProperties.keys) {
                val src = edgeSrcMap.getValue(edgeId)
                val dst = edgeDstMap.getValue(edgeId)
                val tag = edgeTagMap.getValue(edgeId)
                target.addEdge(idMap.getValue(src), idMap.getValue(dst), tag, edgeProperties.getValue(edgeId))
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
            idMap
        }
}
