package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
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
 */
class JgraphtConcurStorageImpl : IStorage {
    private var isClosed: Boolean = false
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    private val storageLock = ReentrantReadWriteLock()

    // Bidirectional mapping: external Int ID <-> JGraphT internal String vertex/edge
    private val intToVertex = HashMap<Int, String>()
    private val vertexToInt = HashMap<String, Int>()
    private val intToEdge = HashMap<Int, String>()
    private val edgeToInt = HashMap<String, Int>()

    private val nodeProperties: MutableMap<Int, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeProperties: MutableMap<Int, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeTagMap: MutableMap<Int, String> = hashMapOf()
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()
    private val jgtGraph: Graph<String, String> = DirectedPseudograph(String::class.java)

    override val nodeIDs: Set<Int>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                nodeProperties.keys.toSet()
            }

    override val edgeIDs: Set<Int>
        get() =
            storageLock.read {
                if (isClosed) throw AccessClosedStorageException()
                edgeProperties.keys.toSet()
            }

    override fun containsNode(id: Int): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            id in nodeProperties
        }

    override fun containsEdge(id: Int): Boolean =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            id in edgeProperties
        }

    override fun addNode(properties: Map<String, IValue>): Int =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            val id = nodeCounter++
            val vertex = "v$id"
            jgtGraph.addVertex(vertex)
            intToVertex[id] = vertex
            vertexToInt[vertex] = id
            nodeProperties[id] = properties.toMutableMap()
            id
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            if (src !in nodeProperties) throw EntityNotExistException(src)
            if (dst !in nodeProperties) throw EntityNotExistException(dst)
            val id = edgeCounter++
            val edgeStr = "e$id"
            val srcVertex = intToVertex[src]!!
            val dstVertex = intToVertex[dst]!!
            jgtGraph.addEdge(srcVertex, dstVertex, edgeStr)
            intToEdge[id] = edgeStr
            edgeToInt[edgeStr] = id
            edgeTagMap[id] = tag
            edgeProperties[id] = properties.toMutableMap()
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val props = nodeProperties[id] ?: throw EntityNotExistException(id)
            HashMap(props)
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val props = edgeProperties[id] ?: throw EntityNotExistException(id)
            HashMap(props)
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) = storageLock.write {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun deleteNode(id: Int): Unit =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in nodeProperties) throw EntityNotExistException(id)
            val vertex = intToVertex[id]!!
            val incoming = jgtGraph.incomingEdgesOf(vertex).toSet()
            val outgoing = jgtGraph.outgoingEdgesOf(vertex).toSet()
            for (edgeStr in incoming + outgoing) {
                val edgeId = edgeToInt[edgeStr]!!
                jgtGraph.removeEdge(edgeStr)
                intToEdge.remove(edgeId)
                edgeToInt.remove(edgeStr)
                edgeProperties.remove(edgeId)
                edgeTagMap.remove(edgeId)
            }
            jgtGraph.removeVertex(vertex)
            intToVertex.remove(id)
            vertexToInt.remove(vertex)
            nodeProperties.remove(id)
        }

    override fun deleteEdge(id: Int): Unit =
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeProperties) throw EntityNotExistException(id)
            val edgeStr = intToEdge[id]!!
            jgtGraph.removeEdge(edgeStr)
            intToEdge.remove(id)
            edgeToInt.remove(edgeStr)
            edgeProperties.remove(id)
            edgeTagMap.remove(id)
        }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in edgeProperties) throw EntityNotExistException(id)
            val edgeStr = intToEdge[id]!!
            val src = vertexToInt[jgtGraph.getEdgeSource(edgeStr)]!!
            val dst = vertexToInt[jgtGraph.getEdgeTarget(edgeStr)]!!
            val tag = edgeTagMap[id] ?: throw EntityNotExistException(id)
            IStorage.EdgeStructure(src, dst, tag)
        }

    override fun getIncomingEdges(id: Int): Set<Int> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in nodeProperties) throw EntityNotExistException(id)
            val vertex = intToVertex[id]!!
            val result = HashSet<Int>()
            for (e in jgtGraph.incomingEdgesOf(vertex)) result.add(edgeToInt[e]!!)
            result
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            if (id !in nodeProperties) throw EntityNotExistException(id)
            val vertex = intToVertex[id]!!
            val result = HashSet<Int>()
            for (e in jgtGraph.outgoingEdgesOf(vertex)) result.add(edgeToInt[e]!!)
            result
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

    override fun clear() {
        storageLock.write {
            if (isClosed) throw AccessClosedStorageException()
            jgtGraph.removeAllEdges(jgtGraph.edgeSet().toSet())
            jgtGraph.removeAllVertices(jgtGraph.vertexSet().toSet())
            intToVertex.clear()
            vertexToInt.clear()
            intToEdge.clear()
            edgeToInt.clear()
            edgeProperties.clear()
            edgeTagMap.clear()
            nodeProperties.clear()
            metaProperties.clear()
            nodeCounter = 0
            edgeCounter = 0
        }
    }

    override fun transferTo(target: IStorage): Map<Int, Int> =
        storageLock.read {
            if (isClosed) throw AccessClosedStorageException()
            val idMap = HashMap<Int, Int>()
            for (nodeId in nodeProperties.keys) {
                idMap[nodeId] = target.addNode(nodeProperties[nodeId]!!)
            }
            for (edgeId in edgeProperties.keys) {
                val structure = getEdgeStructure(edgeId)
                val newSrc = idMap[structure.src] ?: structure.src
                val newDst = idMap[structure.dst] ?: structure.dst
                target.addEdge(newSrc, newDst, structure.tag, edgeProperties[edgeId]!!)
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
            idMap
        }

    override fun close(): Unit =
        storageLock.write {
            if (!isClosed) {
                jgtGraph.removeAllEdges(jgtGraph.edgeSet().toSet())
                jgtGraph.removeAllVertices(jgtGraph.vertexSet().toSet())
                intToVertex.clear()
                vertexToInt.clear()
                intToEdge.clear()
                edgeToInt.clear()
                edgeProperties.clear()
                edgeTagMap.clear()
                nodeProperties.clear()
                metaProperties.clear()
            }
            isClosed = true
        }
}
