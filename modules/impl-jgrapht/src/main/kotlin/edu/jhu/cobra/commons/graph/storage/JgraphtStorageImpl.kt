package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
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
    private val nodeProperties: MutableMap<NodeID, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = linkedMapOf()
    private val jgtGraph: Graph<NodeID, EdgeID> = DirectedPseudograph(EdgeID::class.java)
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()
    private var isClosed: Boolean = false

    override val nodeIDs: Set<NodeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return nodeProperties.keys.toSet()
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return edgeProperties.keys.toSet()
        }

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in nodeProperties
    }

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in edgeProperties
    }

    override fun addNode(id: NodeID, properties: Map<String, IValue>) {
        if (containsNode(id)) throw EntityAlreadyExistException(id = id)
        jgtGraph.addVertex(id)
        nodeProperties[id] = properties.toMutableMap()
    }

    override fun addEdge(id: EdgeID, properties: Map<String, IValue>) {
        if (containsEdge(id = id)) throw EntityAlreadyExistException(id = id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        jgtGraph.addEdge(id.srcNid, id.dstNid, id)
        edgeProperties[id] = properties.toMutableMap()
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return nodeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return edgeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun setNodeProperties(id: NodeID, properties: Map<String, IValue?>) {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun setEdgeProperties(id: EdgeID, properties: Map<String, IValue?>) {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun deleteNode(id: NodeID) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id = id).forEach { this.deleteEdge(it) }
        getOutgoingEdges(id = id).forEach { this.deleteEdge(it) }
        jgtGraph.removeVertex(id)
        nodeProperties.remove(id)
    }

    override fun deleteEdge(id: EdgeID) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        jgtGraph.removeEdge(id)
        edgeProperties.remove(id)
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        if (id !in nodeProperties) throw EntityNotExistException(id)
        return jgtGraph.incomingEdgesOf(id).toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
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

    override fun setMeta(name: String, value: IValue?) {
        if (isClosed) throw AccessClosedStorageException()
        if (value == null) metaProperties.remove(name) else metaProperties[name] = value
    }

    override fun clear(): Boolean {
        if (isClosed) return false
        jgtGraph.removeAllEdges(edgeProperties.keys)
        edgeProperties.clear()
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
