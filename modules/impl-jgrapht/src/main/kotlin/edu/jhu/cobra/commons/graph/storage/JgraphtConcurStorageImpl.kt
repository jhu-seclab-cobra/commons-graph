package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import org.jgrapht.Graph
import org.jgrapht.graph.DirectedPseudograph
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe implementation of the [IStorage] interface using total in-memory storage backed by the JGraphT library.
 * Please notice that there are performance overheads for the concurrency features.
 * For normal use cases, use [JgraphtStorageImpl] instead (about 20% quicker for basic operations).
 *
 * It leverages the JGraphT library to represent the graph structure and provides efficient graph operations like
 * adding, deleting, and retrieving nodes and edges.
 */
class JgraphtConcurStorageImpl : IStorage {
    private var isClosed: Boolean = false
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0
    private val edgeIndex: HashMap<String, String> = hashMapOf()

    private val storageLock = ReentrantReadWriteLock()

    private val nodeProperties: MutableMap<String, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeProperties: MutableMap<String, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeTypeMap: MutableMap<String, String> = hashMapOf()
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()
    private val jgtGraph: Graph<String, String> = DirectedPseudograph(String::class.java)

    private fun edgeKey(src: String, dst: String, type: String): String = "$src\u0000$type\u0000$dst"

    override val nodeIDs: Set<String>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                nodeProperties.keys.toSet()
            }

    override val edgeIDs: Set<String>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                edgeProperties.keys.toSet()
            }

    override fun containsNode(id: String): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            id in nodeProperties
        }

    override fun containsEdge(id: String): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            id in edgeProperties
        }

    override fun addNode(properties: Map<String, IValue>): String =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            val nodeId = (nodeCounter++).toString()
            jgtGraph.addVertex(nodeId)
            nodeProperties[nodeId] = properties.toMutableMap()
            nodeId
        }

    override fun findEdge(src: String, dst: String, type: String): String? =
        storageLock.read { edgeIndex[edgeKey(src, dst, type)] }

    override fun addEdge(
        src: String,
        dst: String,
        type: String,
        properties: Map<String, IValue>,
    ): String =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            val key = edgeKey(src, dst, type)
            if (key in edgeIndex) throw EntityAlreadyExistException(key)
            if (src !in nodeProperties) throw EntityNotExistException(src)
            if (dst !in nodeProperties) throw EntityNotExistException(dst)
            val id = "e${edgeCounter++}"
            jgtGraph.addEdge(src, dst, id)
            edgeIndex[key] = id
            edgeTypeMap[id] = type
            edgeProperties[id] = properties.toMutableMap()
            id
        }

    override fun getNodeProperties(id: String): Map<String, IValue> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val props = nodeProperties[id] ?: throw EntityNotExistException(id)
            HashMap(props)
        }

    override fun getEdgeProperties(id: String): Map<String, IValue> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val props = edgeProperties[id] ?: throw EntityNotExistException(id)
            HashMap(props)
        }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun deleteNode(id: String): Unit =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in nodeProperties) throw EntityNotExistException(id)

            val incomingEdges = jgtGraph.incomingEdgesOf(id).toSet()
            val outgoingEdges = jgtGraph.outgoingEdgesOf(id).toSet()

            (incomingEdges + outgoingEdges).forEach { edgeID ->
                val src = jgtGraph.getEdgeSource(edgeID)
                val dst = jgtGraph.getEdgeTarget(edgeID)
                val type = edgeTypeMap[edgeID]!!
                edgeIndex.remove(edgeKey(src, dst, type))
                jgtGraph.removeEdge(edgeID)
                edgeProperties.remove(edgeID)
                edgeTypeMap.remove(edgeID)
            }

            jgtGraph.removeVertex(id)
            nodeProperties.remove(id)
        }

    override fun deleteEdge(id: String): Unit =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeProperties) throw EntityNotExistException(id)
            val src = jgtGraph.getEdgeSource(id)
            val dst = jgtGraph.getEdgeTarget(id)
            val type = edgeTypeMap[id]!!
            edgeIndex.remove(edgeKey(src, dst, type))
            jgtGraph.removeEdge(id)
            edgeProperties.remove(id)
            edgeTypeMap.remove(id)
        }

    override fun getEdgeSrc(id: String): String =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeProperties) throw EntityNotExistException(id)
            jgtGraph.getEdgeSource(id)
        }

    override fun getEdgeDst(id: String): String =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeProperties) throw EntityNotExistException(id)
            jgtGraph.getEdgeTarget(id)
        }

    override fun getEdgeType(id: String): String =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            edgeTypeMap[id] ?: throw EntityNotExistException(id)
        }

    override fun getIncomingEdges(id: String): Set<String> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in nodeProperties) throw EntityNotExistException(id)
            jgtGraph.incomingEdgesOf(id).toSet()
        }

    override fun getOutgoingEdges(id: String): Set<String> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in nodeProperties) throw EntityNotExistException(id)
            jgtGraph.outgoingEdgesOf(id).toSet()
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

    override fun clear(): Boolean =
        storageLock.write {
            if (isClosed) return@write false
            jgtGraph.removeAllEdges(edgeProperties.keys)
            edgeProperties.clear()
            edgeTypeMap.clear()
            edgeIndex.clear()
            edgeCounter = 0
            jgtGraph.removeAllVertices(nodeProperties.keys)
            nodeProperties.clear()
            metaProperties.clear()
            jgtGraph.edgeSet().isEmpty() &&
                edgeProperties.isEmpty() &&
                jgtGraph.vertexSet().isEmpty() &&
                nodeProperties.isEmpty()
        }

    override fun close(): Unit = storageLock.write { isClosed = clear() }
}
