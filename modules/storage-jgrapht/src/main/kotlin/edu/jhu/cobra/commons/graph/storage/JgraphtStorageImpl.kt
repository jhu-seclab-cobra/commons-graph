package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.EdgeID
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

    /** Node ID to properties mapping. */
    private val nodeProperties: MutableMap<NodeID, MutableMap<String, IValue>> = linkedMapOf()

    /** Edge ID to properties mapping. */
    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = linkedMapOf()

    /** JGraphT graph structure for storing nodes and edges. */
    private val jgtGraph: Graph<NodeID, EdgeID> = DirectedPseudograph(EdgeID::class.java)

    /** Storage closed state flag. */
    private var isClosed: Boolean = false

    override val nodeSize: Int
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return nodeProperties.size
        }

    override val nodeIDsSequence: Sequence<NodeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return nodeProperties.keys.asSequence()
        }

    override val edgeSize: Int
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return edgeProperties.size
        }

    override val edgeIDsSequence: Sequence<EdgeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return edgeProperties.keys.asSequence()
        }

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in nodeProperties // check whether it is in the graph
    }

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return id in edgeProperties // check whether it is in the graph
    }

    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) {
        if (containsNode(id)) throw EntityAlreadyExistException(id = id)
        jgtGraph.addVertex(id); nodeProperties[id] = mutableMapOf(*newProperties)
    }

    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) {
        if (containsEdge(id = id)) throw EntityAlreadyExistException(id = id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        jgtGraph.addEdge(id.srcNid, id.dstNid, id)
        edgeProperties[id] = mutableMapOf(*newProperties)
    }

    override fun getNodeProperties(id: NodeID): MutableMap<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return nodeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun getNodeProperty(id: NodeID, byName: String) = getNodeProperties(id)[byName]

    override fun getEdgeProperties(id: EdgeID): MutableMap<String, IValue> {
        if (isClosed) throw AccessClosedStorageException()
        return edgeProperties[id] ?: throw EntityNotExistException(id = id)
    }

    override fun getEdgeProperty(id: EdgeID, byName: String) = getEdgeProperties(id)[byName]

    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) {
        if (isClosed) throw AccessClosedStorageException()
        val container = nodeProperties[id] ?: throw EntityNotExistException(id = id)
        newProperties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) {
        if (isClosed) throw AccessClosedStorageException()
        val container = edgeProperties[id] ?: throw EntityNotExistException(id = id)
        newProperties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
    }

    override fun deleteNode(id: NodeID) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id = id).forEach { this.deleteEdge(it) }
        getOutgoingEdges(id = id).forEach { this.deleteEdge(it) }
        jgtGraph.removeVertex(id); nodeProperties.remove(id)
    }

    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) {
        if (isClosed) throw AccessClosedStorageException()
        val iterator = nodeProperties.keys.iterator()
        while (iterator.hasNext()) {
            val curNodeID = iterator.next()
            if (!doSatisfyCond(curNodeID)) continue
            getIncomingEdges(curNodeID).forEach(::deleteEdge)
            getOutgoingEdges(curNodeID).forEach(::deleteEdge)
            jgtGraph.removeVertex(curNodeID)
            iterator.remove()
        }
    }

    override fun deleteEdge(id: EdgeID) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        jgtGraph.removeEdge(id); edgeProperties.remove(id)
    }

    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) {
        if (isClosed) throw AccessClosedStorageException()
        val iterator = edgeProperties.keys.iterator()
        while (iterator.hasNext()) {
            val curEdgeID = iterator.next()
            if (!doSatisfyCond(curEdgeID)) continue
            jgtGraph.removeEdge(curEdgeID)
            iterator.remove()
        }
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

    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        return jgtGraph.getAllEdges(from, to).toSet()
    }

    override fun clear(): Boolean {
        if (isClosed) return false
        jgtGraph.removeAllEdges(edgeProperties.keys)
        edgeProperties.clear() // Clear edge properties
        jgtGraph.removeAllVertices(nodeProperties.keys)
        nodeProperties.clear() // Clear node properties
        return jgtGraph.edgeSet().isEmpty() && edgeProperties.isEmpty()
                && jgtGraph.vertexSet().isEmpty() && nodeProperties.isEmpty()
    }

    override fun close() {
        isClosed = clear()
    }
}
