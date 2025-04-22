package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.value.IValue

/**
 * Implementation of the [IStorage] interface using an in-memory storage backed by custom structures.
 *
 * This class manages nodes and edges along with their associated properties using in-memory data structures.
 * It leverages HashMaps for storing node and edge properties, and maintains the graph structure using an
 * adjacency list implemented as a map of node IDs to sets of edge IDs.
 *
 * This implementation is not thread-safe and is intended for use in single-threaded environments. It provides
 * efficient operations for adding, deleting, and querying nodes and edges, and ensures that all operations
 * respect the closed state of the storage.
 */
class NativeConcurStorageImpl : IStorage {

    private var isClosed: Boolean = false

    /** A HashMap to store node properties with NodeId as the key and a PropDict as the value. */
    private val nodeProperties: MutableMap<NodeID, MutableMap<String, IValue>> = mutableMapOf()

    /** A HashMap to store edge properties with EdgeId as the key and a PropDict as the value. */
    private val edgeProperties: MutableMap<EdgeID, MutableMap<String, IValue>> = mutableMapOf()

    /** A Directed Pseudo-graph from JGraphT library to store nodes and edges. */
    private val graphStructure: MutableMap<NodeID, Set<EdgeID>> = mutableMapOf()

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
        if (isClosed) throw AccessClosedStorageException()
        if (containsNode(id)) throw EntityAlreadyExistException(id = id)
        nodeProperties[id] = mutableMapOf(*newProperties)
    }

    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) {
        if (isClosed) throw AccessClosedStorageException()
        if (containsEdge(id = id)) throw EntityAlreadyExistException(id = id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        graphStructure[id.srcNid] = graphStructure[id.srcNid].orEmpty() + id
        graphStructure[id.dstNid] = graphStructure[id.dstNid].orEmpty() + id
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
        if (isClosed) throw AccessClosedStorageException()
        if (!containsNode(id)) throw EntityNotExistException(id)
        getIncomingEdges(id = id).forEach { this.deleteEdge(it) }
        getOutgoingEdges(id = id).forEach { this.deleteEdge(it) }
        graphStructure.remove(id)
        nodeProperties.remove(id)
    }

    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) {
        if (isClosed) throw AccessClosedStorageException()
        val iterator = nodeProperties.keys.iterator()
        while (iterator.hasNext()) {
            val curNodeID = iterator.next()
            if (!doSatisfyCond(curNodeID)) continue
            getIncomingEdges(curNodeID).forEach(::deleteEdge)
            getOutgoingEdges(curNodeID).forEach(::deleteEdge)
            graphStructure.remove(curNodeID)
            iterator.remove()
        }
    }

    override fun deleteEdge(id: EdgeID) {
        if (isClosed) throw AccessClosedStorageException()
        if (!containsEdge(id)) throw EntityNotExistException(id)
        edgeProperties.remove(id)
        graphStructure[id.srcNid] = graphStructure[id.srcNid].orEmpty() - id
        graphStructure[id.dstNid] = graphStructure[id.dstNid].orEmpty() - id
    }

    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) {
        if (isClosed) throw AccessClosedStorageException()
        val iterator = edgeProperties.keys.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (!doSatisfyCond(id)) continue
            graphStructure[id.srcNid] = graphStructure[id.srcNid].orEmpty() - id
            graphStructure[id.dstNid] = graphStructure[id.dstNid].orEmpty() - id
            iterator.remove()
        }
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        return graphStructure[id]?.filter { it.dstNid == id }?.toSet().orEmpty()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        return graphStructure[id]?.filter { it.srcNid == id }?.toSet().orEmpty()
    }

    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> {
        if (isClosed) throw AccessClosedStorageException()
        return graphStructure[from]?.filter { it.srcNid == from && it.dstNid == to }?.toSet().orEmpty()
    }

    override fun clear(): Boolean {
        graphStructure.clear();edgeProperties.clear(); nodeProperties.clear() // Clear node properties
        return graphStructure.isEmpty() && edgeProperties.isEmpty() && nodeProperties.isEmpty()
    }

    override fun close() {
        isClosed = true
        clear()
    }
}
