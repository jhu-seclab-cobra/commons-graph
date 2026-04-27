package edu.jhu.cobra.commons.graph.storage

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

    // Bidirectional mapping: external Int ID <-> JGraphT internal String vertex/edge
    private val intToVertex = HashMap<Int, String>()
    private val vertexToInt = HashMap<String, Int>()
    private val intToEdge = HashMap<Int, String>()
    private val edgeToInt = HashMap<String, Int>()

    private val nodeProperties: MutableMap<Int, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeProperties: MutableMap<Int, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeTagMap: MutableMap<Int, String> = hashMapOf()
    private val jgtGraph: Graph<String, String> = DirectedPseudograph(String::class.java)
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()

    override val nodeIDs: Set<Int>
        get() = nodeProperties.keys.toSet()

    override val edgeIDs: Set<Int>
        get() = edgeProperties.keys.toSet()

    override fun containsNode(id: Int): Boolean = id in nodeProperties

    override fun containsEdge(id: Int): Boolean = id in edgeProperties

    override fun addNode(properties: Map<String, IValue>): Int {
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
        tag: String,
        properties: Map<String, IValue>,
    ): Int {
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
        return id
    }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        nodeProperties[id] ?: throw EntityNotExistException(id)

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        edgeProperties[id] ?: throw EntityNotExistException(id)

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        val container = nodeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        val container = edgeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun deleteNode(id: Int) {
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

    override fun deleteEdge(id: Int) {
        if (id !in edgeProperties) throw EntityNotExistException(id)
        val edgeStr = intToEdge[id]!!
        jgtGraph.removeEdge(edgeStr)
        intToEdge.remove(id)
        edgeToInt.remove(edgeStr)
        edgeProperties.remove(id)
        edgeTagMap.remove(id)
    }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure {
        if (id !in edgeProperties) throw EntityNotExistException(id)
        val edgeStr = intToEdge[id]!!
        val src = vertexToInt[jgtGraph.getEdgeSource(edgeStr)]!!
        val dst = vertexToInt[jgtGraph.getEdgeTarget(edgeStr)]!!
        val tag = edgeTagMap[id] ?: throw EntityNotExistException(id)
        return IStorage.EdgeStructure(src, dst, tag)
    }

    override fun getIncomingEdges(id: Int): Set<Int> {
        if (id !in nodeProperties) throw EntityNotExistException(id)
        val vertex = intToVertex[id]!!
        val result = HashSet<Int>()
        for (e in jgtGraph.incomingEdgesOf(vertex)) result.add(edgeToInt[e]!!)
        return result
    }

    override fun getOutgoingEdges(id: Int): Set<Int> {
        if (id !in nodeProperties) throw EntityNotExistException(id)
        val vertex = intToVertex[id]!!
        val result = HashSet<Int>()
        for (e in jgtGraph.outgoingEdgesOf(vertex)) result.add(edgeToInt[e]!!)
        return result
    }

    override val metaNames: Set<String>
        get() = metaProperties.keys.toSet()

    override fun getMeta(name: String): IValue? = metaProperties[name]

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (value == null) metaProperties.remove(name) else metaProperties[name] = value
    }

    override fun flush() {}

    override fun clear() {
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

    override fun transferTo(target: IStorage): Map<Int, Int> {
        val idMap = HashMap<Int, Int>()
        for (nodeId in nodeProperties.keys) {
            idMap[nodeId] = target.addNode(nodeProperties[nodeId]!!)
        }
        for (edgeId in edgeProperties.keys) {
            val structure = getEdgeStructure(edgeId)
            val newSrc = idMap.getValue(structure.src)
            val newDst = idMap.getValue(structure.dst)
            target.addEdge(newSrc, newDst, structure.tag, edgeProperties[edgeId]!!)
        }
        for (name in metaProperties.keys) {
            target.setMeta(name, metaProperties[name])
        }
        return idMap
    }
}
