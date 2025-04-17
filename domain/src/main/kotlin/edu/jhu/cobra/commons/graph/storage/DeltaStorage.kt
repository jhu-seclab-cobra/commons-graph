package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.strVal

/**
 * DeltaStorage is an implementation of the [IStorage] interface that manages a sequence of delta slices (snapshots).
 * Each delta represents a state of the storage and contributes to the final state of the storage system.
 * Deltas are organized in an ordered list, and new deltas can be added or removed as needed.
 *
 * Modifications are always applied to the latest delta, and newer deltas take precedence over older ones in terms of data priority.
 *
 * @param foundDelta The base storage that serves as the foundation for all deltas. Defaults to [NativeStorage].
 * @param presentDelta Optional additional named delta slices to initialize the storage with.
 */
class DeltaStorage(
    private val foundDelta: IStorage,
    private val presentDelta: IStorage = NativeStorage(),
) : IStorage {

    private val deletedHolder = hashSetOf<IEntity.ID>()

    private var isClosed: Boolean = false

    override val nodeSize: Int get() = foundDelta.nodeSize + presentDelta.nodeSize - deletedHolder.size

    override val nodeIDsSequence: Sequence<NodeID>
        get() = (foundDelta.nodeIDsSequence + presentDelta.nodeIDsSequence).filter { it !in deletedHolder }

    override val edgeSize: Int get() = foundDelta.edgeSize + presentDelta.edgeSize - deletedHolder.size

    override val edgeIDsSequence: Sequence<EdgeID>
        get() = (foundDelta.edgeIDsSequence + presentDelta.edgeIDsSequence).filter { it !in deletedHolder }

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return if (deletedHolder.contains(id)) false
        else presentDelta.containsNode(id) || foundDelta.containsNode(id)
    }

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        return if (deletedHolder.contains(id)) false
        else presentDelta.containsEdge(id) || foundDelta.containsEdge(id)
    }

    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) {
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        deletedHolder.remove(element = id)
        if (foundDelta.containsNode(id) || presentDelta.containsNode(id)) return
        presentDelta.addNode(id, *newProperties)
    }

    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) {
        if (containsEdge(id)) throw EntityAlreadyExistException(id)
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        deletedHolder.remove(element = id)
        if (foundDelta.containsEdge(id) || presentDelta.containsEdge(id)) return
        if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
        if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
        presentDelta.addEdge(id, newProperties = newProperties)
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val foundProps = if (foundDelta.containsNode(id)) foundDelta.getNodeProperties(id) else emptyMap()
        val presentProps = if (presentDelta.containsNode(id)) presentDelta.getNodeProperties(id) else emptyMap()
        return (foundProps + presentProps).filterValues { v -> v.core != "_deleted_" }
    }

    override fun getNodeProperty(id: NodeID, byName: String): IValue? {
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (!presentDelta.containsNode(id)) return foundDelta.getNodeProperty(id, byName)
        presentDelta.getNodeProperty(id, byName)?.also { return it.takeIf { it.core != "_deleted_" } }
        return if (foundDelta.containsNode(id)) foundDelta.getNodeProperty(id, byName) else null
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val foundProps = if (foundDelta.containsEdge(id)) foundDelta.getEdgeProperties(id) else emptyMap()
        val presentProps = if (presentDelta.containsEdge(id)) presentDelta.getEdgeProperties(id) else emptyMap()
        return (foundProps + presentProps).filterValues { v -> v.core != "_deleted_" }
    }

    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        if (!presentDelta.containsEdge(id)) return foundDelta.getEdgeProperty(id, byName)
        presentDelta.getEdgeProperty(id, byName)?.also { return it.takeIf { it.core != "_deleted_" } }
        return if (foundDelta.containsEdge(id)) foundDelta.getEdgeProperty(id, byName) else null
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
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (presentDelta.containsNode(id)) presentDelta.deleteNode(id = id)
        if (!foundDelta.containsNode(id)) return
        deletedHolder.addAll(foundDelta.getOutgoingEdges(id))
        deletedHolder.addAll(foundDelta.getIncomingEdges(id))
        deletedHolder.add(id)
    }

    override fun deleteNodes(doSatisfyCond: (NodeID) -> Boolean) {
        nodeIDsSequence.filter(doSatisfyCond).toSet().forEach(::deleteNode)
    }


    override fun deleteEdge(id: EdgeID) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        if (presentDelta.containsEdge(id)) presentDelta.deleteEdge(id = id)
        if (foundDelta.containsEdge(id)) deletedHolder.add(id)
    }

    override fun deleteEdges(doSatisfyCond: (EdgeID) -> Boolean) {
        edgeIDsSequence.filter(doSatisfyCond).toSet().forEach(::deleteEdge)
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        val found = if (!foundDelta.containsNode(id)) emptySequence()
        else foundDelta.getIncomingEdges(id = id).asSequence()
        val present = if (!presentDelta.containsNode(id)) emptySequence()
        else presentDelta.getIncomingEdges(id = id).asSequence()
        return (found + present).filter { it !in deletedHolder }.toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        val found = if (!foundDelta.containsNode(id)) emptySequence()
        else foundDelta.getOutgoingEdges(id = id).asSequence()
        val present = if (!presentDelta.containsNode(id)) emptySequence()
        else presentDelta.getOutgoingEdges(id = id).asSequence()
        return (found + present).filter { it !in deletedHolder }.toSet()
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