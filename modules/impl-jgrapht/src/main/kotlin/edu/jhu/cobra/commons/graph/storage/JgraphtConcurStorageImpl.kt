package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
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

    private val storageLock = ReentrantReadWriteLock()

    private val nodeProperties: MutableMap<NodeID, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = linkedMapOf()
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()
    private val jgtGraph: Graph<NodeID, EdgeID> = DirectedPseudograph(EdgeID::class.java)

    override val nodeIDs: Set<NodeID>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                nodeProperties.keys.toSet()
            }

    override val edgeIDs: Set<EdgeID>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                edgeProperties.keys.toSet()
            }

    override fun containsNode(id: NodeID): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            id in nodeProperties
        }

    override fun containsEdge(id: EdgeID): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            id in edgeProperties
        }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        if (id in nodeProperties) throw EntityAlreadyExistException(id = id)
        jgtGraph.addVertex(id)
        nodeProperties[id] = properties.toMutableMap()
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        if (id in edgeProperties) throw EntityAlreadyExistException(id = id)
        if (id.srcNid !in nodeProperties) throw EntityNotExistException(id.srcNid)
        if (id.dstNid !in nodeProperties) throw EntityNotExistException(id.dstNid)
        jgtGraph.addEdge(id.srcNid, id.dstNid, id)
        edgeProperties[id] = properties.toMutableMap()
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val props = nodeProperties[id] ?: throw EntityNotExistException(id = id)
            HashMap(props)
        }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val props = edgeProperties[id] ?: throw EntityNotExistException(id = id)
            HashMap(props)
        }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun deleteNode(id: NodeID): Unit =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in nodeProperties) throw EntityNotExistException(id)

            val incomingEdges = jgtGraph.incomingEdgesOf(id).toSet()
            val outgoingEdges = jgtGraph.outgoingEdgesOf(id).toSet()

            incomingEdges.forEach { edgeID ->
                jgtGraph.removeEdge(edgeID)
                edgeProperties.remove(edgeID)
            }
            outgoingEdges.forEach { edgeID ->
                jgtGraph.removeEdge(edgeID)
                edgeProperties.remove(edgeID)
            }

            jgtGraph.removeVertex(id)
            nodeProperties.remove(id)
        }

    override fun deleteEdge(id: EdgeID): Unit =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeProperties) throw EntityNotExistException(id)
            jgtGraph.removeEdge(id)
            edgeProperties.remove(id)
        }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in nodeProperties) throw EntityNotExistException(id)
            jgtGraph.incomingEdgesOf(id).toSet()
        }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> =
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
