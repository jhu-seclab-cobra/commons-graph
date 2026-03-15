package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import org.jgrapht.Graph
import org.jgrapht.graph.DirectedPseudograph

/**
 * Non-concurrent implementation of [IStorage] using JGraphT library for in-memory graph storage.
 * For concurrent access, use [JgraphtConcurStorageImpl] instead.
 *
 * It leverages the JGraphT library to represent the graph structure and provides efficient graph operations like
 * adding, deleting, and retrieving nodes and edges.
 */
class JgraphtStorageImpl : IStorage {
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0
    private val edgeIndex: HashMap<String, String> = hashMapOf()
    private val nodeProperties: MutableMap<String, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeProperties: MutableMap<String, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeTypeMap: MutableMap<String, String> = hashMapOf()
    private val jgtGraph: Graph<String, String> = DirectedPseudograph(String::class.java)
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()
    private var isClosed: Boolean = false

    private fun edgeKey(src: String, dst: String, type: String): String = "$src\u0000$type\u0000$dst"

    override val nodeIDs: Set<String>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return nodeProperties.keys.toSet()
        }

    override val edgeIDs: Set<String>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return edgeProperties.keys.toSet()
        }

    override fun containsNode(id: String): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in nodeProperties
    }

    override fun containsEdge(id: String): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in edgeProperties
    }

    override fun addNode(properties: Map<String, IValue>): String {
        if (isClosed) throw AccessClosedStorageException()
        val nodeId = (nodeCounter++).toString()
        jgtGraph.addVertex(nodeId)
        nodeProperties[nodeId] = properties.toMutableMap()
        return nodeId
    }

    override fun findEdge(src: String, dst: String, type: String): String? = edgeIndex[edgeKey(src, dst, type)]

    override fun addEdge(
        src: String,
        dst: String,
        type: String,
        properties: Map<String, IValue>,
    ): String {
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
        return id
    }

    override fun getNodeProperties(id: String): Map<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return nodeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun getEdgeProperties(id: String): Map<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return edgeProperties[id] ?: throw EntityNotExistException(id)
    }

    override fun setNodeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun setEdgeProperties(
        id: String,
        properties: Map<String, IValue?>,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun deleteNode(id: String) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id).forEach { this.deleteEdge(it) }
        getOutgoingEdges(id).forEach { this.deleteEdge(it) }
        jgtGraph.removeVertex(id)
        nodeProperties.remove(id)
    }

    override fun deleteEdge(id: String) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val src = jgtGraph.getEdgeSource(id)
        val dst = jgtGraph.getEdgeTarget(id)
        val type = edgeTypeMap[id]!!
        edgeIndex.remove(edgeKey(src, dst, type))
        jgtGraph.removeEdge(id)
        edgeProperties.remove(id)
        edgeTypeMap.remove(id)
    }

    override fun getEdgeSrc(id: String): String {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in edgeProperties) throw EntityNotExistException(id)
        return jgtGraph.getEdgeSource(id)
    }

    override fun getEdgeDst(id: String): String {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in edgeProperties) throw EntityNotExistException(id)
        return jgtGraph.getEdgeTarget(id)
    }

    override fun getEdgeType(id: String): String {
        if (isClosed) throw AccessClosedStorageException()
        return edgeTypeMap[id] ?: throw EntityNotExistException(id)
    }

    override fun getIncomingEdges(id: String): Set<String> {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in nodeProperties) throw EntityNotExistException(id)
        return jgtGraph.incomingEdgesOf(id).toSet()
    }

    override fun getOutgoingEdges(id: String): Set<String> {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in nodeProperties) throw EntityNotExistException(id)
        return jgtGraph.outgoingEdgesOf(id).toSet()
    }

    override val metaNames: Set<String>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return metaProperties.keys.toSet()
        }

    override fun getMeta(name: String): IValue? {
        if (isClosed) throw AccessClosedStorageException()
        return metaProperties[name]
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        if (value == null) metaProperties.remove(name) else metaProperties[name] = value
    }

    override fun clear(): Boolean {
        if (isClosed) return false
        jgtGraph.removeAllEdges(edgeProperties.keys)
        edgeProperties.clear()
        edgeTypeMap.clear()
        edgeIndex.clear()
        edgeCounter = 0
        jgtGraph.removeAllVertices(nodeProperties.keys)
        nodeProperties.clear()
        metaProperties.clear()
        return jgtGraph.edgeSet().isEmpty() &&
            edgeProperties.isEmpty() &&
            jgtGraph.vertexSet().isEmpty() &&
            nodeProperties.isEmpty()
    }

    override fun close() {
        isClosed = clear()
    }
}
