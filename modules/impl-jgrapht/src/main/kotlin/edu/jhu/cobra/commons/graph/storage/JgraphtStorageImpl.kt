package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import org.jgrapht.Graph
import org.jgrapht.graph.DirectedPseudograph

/**
 * Non-concurrent implementation of [IStorage] using JGraphT library for in-memory graph storage.
 * For concurrent access, use [JgraphtConcurStorageImpl] instead.
 *
 * Internally uses JGraphT String-based vertices/edges, with a bidirectional mapping layer
 * between external Int IDs and internal JGraphT String IDs.
 */
class JgraphtStorageImpl : IStorage {
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0
    private var isClosed: Boolean = false

    // Bidirectional mapping: external Int ID <-> JGraphT internal String vertex/edge
    private val intToVertex = HashMap<Int, String>()
    private val vertexToInt = HashMap<String, Int>()
    private val intToEdge = HashMap<Int, String>()
    private val edgeToInt = HashMap<String, Int>()

    private val nodeProperties: MutableMap<Int, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeProperties: MutableMap<Int, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeTypeMap: MutableMap<Int, String> = hashMapOf()
    private val jgtGraph: Graph<String, String> = DirectedPseudograph(String::class.java)
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()

    private fun ensureOpen() {
        if (isClosed) throw AccessClosedStorageException()
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
        return id in nodeProperties
    }

    override fun containsEdge(id: Int): Boolean {
        ensureOpen()
        return id in edgeProperties
    }

    override fun addNode(properties: Map<String, IValue>): Int {
        ensureOpen()
        val id = nodeCounter++
        val vertex = "v$id"
        jgtGraph.addVertex(vertex)
        intToVertex[id] = vertex
        vertexToInt[vertex] = id
        nodeProperties[id] = properties.toMutableMap()
        return id
    }

    override fun addEdge(
        src: Int,
        dst: Int,
        type: String,
        properties: Map<String, IValue>,
    ): Int {
        ensureOpen()
        if (src !in nodeProperties) throw EntityNotExistException(src)
        if (dst !in nodeProperties) throw EntityNotExistException(dst)
        val id = edgeCounter++
        val edgeStr = "e$id"
        val srcVertex = intToVertex[src]!!
        val dstVertex = intToVertex[dst]!!
        jgtGraph.addEdge(srcVertex, dstVertex, edgeStr)
        intToEdge[id] = edgeStr
        edgeToInt[edgeStr] = id
        edgeTypeMap[id] = type
        edgeProperties[id] = properties.toMutableMap()
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
        val container = nodeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        ensureOpen()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun deleteNode(id: Int) {
        ensureOpen()
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
            edgeTypeMap.remove(edgeId)
        }
        jgtGraph.removeVertex(vertex)
        intToVertex.remove(id)
        vertexToInt.remove(vertex)
        nodeProperties.remove(id)
    }

    override fun deleteEdge(id: Int) {
        ensureOpen()
        if (id !in edgeProperties) throw EntityNotExistException(id)
        val edgeStr = intToEdge[id]!!
        jgtGraph.removeEdge(edgeStr)
        intToEdge.remove(id)
        edgeToInt.remove(edgeStr)
        edgeProperties.remove(id)
        edgeTypeMap.remove(id)
    }

    override fun getEdgeSrc(id: Int): Int {
        ensureOpen()
        if (id !in edgeProperties) throw EntityNotExistException(id)
        val edgeStr = intToEdge[id]!!
        val srcVertex = jgtGraph.getEdgeSource(edgeStr)
        return vertexToInt[srcVertex]!!
    }

    override fun getEdgeDst(id: Int): Int {
        ensureOpen()
        if (id !in edgeProperties) throw EntityNotExistException(id)
        val edgeStr = intToEdge[id]!!
        val dstVertex = jgtGraph.getEdgeTarget(edgeStr)
        return vertexToInt[dstVertex]!!
    }

    override fun getEdgeType(id: Int): String {
        ensureOpen()
        return edgeTypeMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getIncomingEdges(id: Int): Set<Int> {
        ensureOpen()
        if (id !in nodeProperties) throw EntityNotExistException(id)
        val vertex = intToVertex[id]!!
        val result = HashSet<Int>()
        for (e in jgtGraph.incomingEdgesOf(vertex)) result.add(edgeToInt[e]!!)
        return result
    }

    override fun getOutgoingEdges(id: Int): Set<Int> {
        ensureOpen()
        if (id !in nodeProperties) throw EntityNotExistException(id)
        val vertex = intToVertex[id]!!
        val result = HashSet<Int>()
        for (e in jgtGraph.outgoingEdgesOf(vertex)) result.add(edgeToInt[e]!!)
        return result
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

    override fun clear() {
        ensureOpen()
        jgtGraph.removeAllEdges(jgtGraph.edgeSet().toSet())
        jgtGraph.removeAllVertices(jgtGraph.vertexSet().toSet())
        intToVertex.clear()
        vertexToInt.clear()
        intToEdge.clear()
        edgeToInt.clear()
        edgeProperties.clear()
        edgeTypeMap.clear()
        nodeProperties.clear()
        metaProperties.clear()
        nodeCounter = 0
        edgeCounter = 0
    }

    override fun transferTo(target: IStorage) {
        ensureOpen()
        val idMap = HashMap<Int, Int>()
        for (nodeId in nodeProperties.keys) {
            idMap[nodeId] = target.addNode(nodeProperties[nodeId]!!)
        }
        for (edgeId in edgeProperties.keys) {
            val src = getEdgeSrc(edgeId)
            val dst = getEdgeDst(edgeId)
            val type = edgeTypeMap[edgeId]!!
            val newSrc = idMap[src] ?: src
            val newDst = idMap[dst] ?: dst
            target.addEdge(newSrc, newDst, type, edgeProperties[edgeId]!!)
        }
        for (name in metaProperties.keys) {
            target.setMeta(name, metaProperties[name])
        }
    }

    override fun close() {
        if (!isClosed) clear()
        isClosed = true
    }
}
