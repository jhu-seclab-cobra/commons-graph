package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import org.mapdb.DB

/**
 * Shared [IStorage] engine backed by MapDB for off-heap storage of nodes and edges.
 *
 * Entity properties live in MapDB-backed [EntityPropertyMap]s; edge structure, adjacency
 * indices, and metadata live on-heap. Property maps are returned as snapshot copies,
 * never live views.
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
            val merged = (nodePropMap + properties).filterValues { it != null }.mapValues { it.value!! }
            nodeProperties[id] = merged
        }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        writeGuarded {
            val curEdgeProps = edgeProperties[id] ?: throw EntityNotExistException(id)
            val merged = (curEdgeProps + properties).filterValues { it != null }.mapValues { it.value!! }
            edgeProperties[id] = merged
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
