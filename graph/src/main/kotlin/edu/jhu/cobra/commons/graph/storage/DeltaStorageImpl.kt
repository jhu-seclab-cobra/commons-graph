package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.strVal

/**
 * DeltaStorage is an implementation of the [IStorage] interface that manages a sequence of delta slices (snapshots).
 * Each delta represents a state of the storage and contributes to the final state of the storage system.
 * Deltas are organized in an ordered list, and new deltas can be added or removed as needed.
 * Please note that this implementation is used for sharing the base delta between multiple storage instances in
 * multi-thread environments, but the storage itself is not thread-safe.
 *
 * Please pay attention to that the individual modifications for the delta storage will BREAK the correctness of the
 * storage, so that all modifications should be done through the method existing in the [DeltaStorageImpl].
 *
 * Modifications are always applied to the latest delta, and newer deltas take precedence over older ones in terms of data priority.
 *
 * @param baseDelta The base storage that serves as the foundation for all deltas. Defaults to [NativeStorageImpl].
 * @param presentDelta Optional additional named delta slices to initialize the storage with
 */
class DeltaStorageImpl(
    private val baseDelta: IStorage,
    private val presentDelta: IStorage = NativeStorageImpl(),
) : IStorage {

    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0
    private val deletedNodesHolder = hashSetOf<NodeID>()
    private val deletedEdgesHolder = hashSetOf<EdgeID>()

    private var isClosed: Boolean = false

    init {
        nodeCounter = baseDelta.nodeSize + presentDelta.nodeIDsSequence.count { it !in baseDelta }
        edgeCounter = baseDelta.edgeSize + presentDelta.edgeIDsSequence.count { it !in baseDelta }
    }

    override val nodeSize: Int get() = if (isClosed) throw AccessClosedStorageException() else nodeCounter
    override val edgeSize: Int get() = if (isClosed) throw AccessClosedStorageException() else edgeCounter

    override val nodeIDsSequence: Sequence<NodeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return (baseDelta.nodeIDsSequence + presentDelta.nodeIDsSequence).filter { it !in deletedNodesHolder }
                .distinct()
        }

    override val edgeIDsSequence: Sequence<EdgeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return (baseDelta.edgeIDsSequence + presentDelta.edgeIDsSequence).filter { it !in deletedEdgesHolder }
                .distinct()
        }

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return if (deletedNodesHolder.contains(id)) false
        else presentDelta.containsNode(id) || baseDelta.containsNode(id)
    }

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return if (deletedEdgesHolder.contains(id)) false
        else presentDelta.containsEdge(id) || baseDelta.containsEdge(id)
    }

    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) {
        if (isClosed) throw AccessClosedStorageException()
        if (containsNode(id)) throw EntityAlreadyExistException(id) else nodeCounter += 1
        deletedNodesHolder.remove(element = id)
        if (baseDelta.containsNode(id) || presentDelta.containsNode(id)) return
        presentDelta.addNode(id, *newProperties)
    }

    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) {
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        if (containsEdge(id)) throw EntityAlreadyExistException(id) else edgeCounter += 1
        deletedEdgesHolder.remove(element = id)
        if (baseDelta.containsEdge(id) || presentDelta.containsEdge(id)) return
        if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
        if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
        presentDelta.addEdge(id, newProperties = newProperties)
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val foundProps = if (baseDelta.containsNode(id)) baseDelta.getNodeProperties(id) else emptyMap()
        val presentProps = if (presentDelta.containsNode(id)) presentDelta.getNodeProperties(id) else emptyMap()
        return (foundProps + presentProps).filterValues { v -> v.core != "_deleted_" }
    }

    override fun getNodeProperty(id: NodeID, byName: String): IValue? {
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (!presentDelta.containsNode(id)) return baseDelta.getNodeProperty(id, byName)
        presentDelta.getNodeProperty(id, byName)?.also { return it.takeIf { it.core != "_deleted_" } }
        return if (baseDelta.containsNode(id)) baseDelta.getNodeProperty(id, byName) else null
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val foundProps = if (baseDelta.containsEdge(id)) baseDelta.getEdgeProperties(id) else emptyMap()
        val presentProps = if (presentDelta.containsEdge(id)) presentDelta.getEdgeProperties(id) else emptyMap()
        return (foundProps + presentProps).filterValues { v -> v.core != "_deleted_" }
    }

    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        if (!presentDelta.containsEdge(id)) return baseDelta.getEdgeProperty(id, byName)
        presentDelta.getEdgeProperty(id, byName)?.also { return it.takeIf { it.core != "_deleted_" } }
        return if (baseDelta.containsEdge(id)) baseDelta.getEdgeProperty(id, byName) else null
    }

    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val nullSafeProps = newProperties.map { (k, v) -> k to (v ?: "_deleted_".strVal) }.toTypedArray()
        if (!presentDelta.containsNode(id)) presentDelta.addNode(id, *nullSafeProps)
        else presentDelta.setNodeProperties(id, newProperties = nullSafeProps)
    }

    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) {
        if (id !in this) throw EntityNotExistException(id)
        val nullSafeProps = newProperties.map { (k, v) -> k to (v ?: "_deleted_".strVal) }.toTypedArray()
        if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
        if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
        if (!presentDelta.containsEdge(id)) presentDelta.addEdge(id, *nullSafeProps)
        else presentDelta.setEdgeProperties(id, newProperties = nullSafeProps)
    }

    override fun deleteNode(id: NodeID) {
        if (!containsNode(id)) throw EntityNotExistException(id) else nodeCounter -= 1
        if (presentDelta.containsNode(id)) {
            edgeCounter -= presentDelta.getOutgoingEdges(id).size
            edgeCounter -= presentDelta.getIncomingEdges(id).size
            presentDelta.deleteNode(id = id)
        }
        if (!baseDelta.containsNode(id)) return
        val outgoingEdges = baseDelta.getOutgoingEdges(id)
        deletedEdgesHolder.addAll(outgoingEdges)
        edgeCounter -= outgoingEdges.size
        val incomingEdges = baseDelta.getIncomingEdges(id)
        deletedEdgesHolder.addAll(incomingEdges)
        edgeCounter -= incomingEdges.size
        deletedNodesHolder.add(id)
    }

    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) {
        nodeIDsSequence.filter(doSatisfyCond).toSet().forEach(::deleteNode)
    }


    override fun deleteEdge(id: EdgeID) {
        if (!containsEdge(id)) throw EntityNotExistException(id) else edgeCounter -= 1
        if (presentDelta.containsEdge(id)) presentDelta.deleteEdge(id = id)
        if (baseDelta.containsEdge(id)) deletedEdgesHolder.add(id)
    }

    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) {
        edgeIDsSequence.filter(doSatisfyCond).toSet().forEach(::deleteEdge)
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        val found = if (!baseDelta.containsNode(id)) emptySequence()
        else baseDelta.getIncomingEdges(id = id).asSequence()
        val present = if (!presentDelta.containsNode(id)) emptySequence()
        else presentDelta.getIncomingEdges(id = id).asSequence()
        return (found + present).filter { it !in deletedEdgesHolder }.toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        val found = if (!baseDelta.containsNode(id)) emptySequence()
        else baseDelta.getOutgoingEdges(id = id).asSequence()
        val present = if (!presentDelta.containsNode(id)) emptySequence()
        else presentDelta.getOutgoingEdges(id = id).asSequence()
        return (found + present).filter { it !in deletedEdgesHolder }.toSet()
    }

    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> {
        return getOutgoingEdges(from).filter { it.dstNid == to }.toSet()
    }

    override fun clear(): Boolean = presentDelta.clear()

    override fun close() {
        isClosed = true
        presentDelta.close()
    }

}
