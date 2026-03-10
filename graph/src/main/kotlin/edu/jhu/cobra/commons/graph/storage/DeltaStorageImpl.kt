package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.strVal

/**
 * Two-layer overlay storage composing a frozen base and a mutable present layer.
 *
 * Reads cascade from present to base; writes target present only.
 * Supports full deletion across both layers via deleted-entity holders
 * and `"_deleted_"` sentinel values for property deletion tracking.
 *
 * The base layer is never mutated. `close()` only closes the present layer;
 * the base layer lifecycle is managed externally.
 *
 * @param baseDelta The frozen/read-only base layer (injected).
 * @param presentDelta The mutable overlay layer. Defaults to [NativeStorageImpl].
 */
class DeltaStorageImpl(
    private val baseDelta: IStorage,
    private val presentDelta: IStorage = NativeStorageImpl(),
) : IStorage {
    private var isClosed: Boolean = false
    private val deletedNodesHolder = hashSetOf<NodeID>()
    private val deletedEdgesHolder = hashSetOf<EdgeID>()

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return (baseDelta.nodeIDs + presentDelta.nodeIDs)
                .filter { it !in deletedNodesHolder }
                .toSet()
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return (baseDelta.edgeIDs + presentDelta.edgeIDs)
                .filter { it !in deletedEdgesHolder }
                .toSet()
        }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        if (deletedNodesHolder.contains(id)) return false
        return presentDelta.containsNode(id) || baseDelta.containsNode(id)
    }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        if (containsNode(id)) throw EntityAlreadyExistException(id)
        deletedNodesHolder.remove(id)
        if (baseDelta.containsNode(id) || presentDelta.containsNode(id)) return
        presentDelta.addNode(id, properties)
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val baseProps = if (baseDelta.containsNode(id)) baseDelta.getNodeProperties(id) else emptyMap()
        val presentProps = if (presentDelta.containsNode(id)) presentDelta.getNodeProperties(id) else emptyMap()
        return (baseProps + presentProps).filterValues { it.core != "_deleted_" }
    }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        val sentinelProps = properties.mapValues { (_, v) -> v ?: "_deleted_".strVal }
        if (!presentDelta.containsNode(id)) {
            presentDelta.addNode(id, sentinelProps)
        } else {
            presentDelta.setNodeProperties(id, sentinelProps)
        }
    }

    override fun deleteNode(id: NodeID) {
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (presentDelta.containsNode(id)) presentDelta.deleteNode(id)
        if (!baseDelta.containsNode(id)) return
        deletedEdgesHolder.addAll(baseDelta.getOutgoingEdges(id))
        deletedEdgesHolder.addAll(baseDelta.getIncomingEdges(id))
        deletedNodesHolder.add(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed) throw AccessClosedStorageException()
        if (deletedEdgesHolder.contains(id)) return false
        return presentDelta.containsEdge(id) || baseDelta.containsEdge(id)
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) {
        if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
        if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
        if (containsEdge(id)) throw EntityAlreadyExistException(id)
        deletedEdgesHolder.remove(id)
        if (baseDelta.containsEdge(id) || presentDelta.containsEdge(id)) return
        if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
        if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
        presentDelta.addEdge(id, properties)
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val baseProps = if (baseDelta.containsEdge(id)) baseDelta.getEdgeProperties(id) else emptyMap()
        val presentProps = if (presentDelta.containsEdge(id)) presentDelta.getEdgeProperties(id) else emptyMap()
        return (baseProps + presentProps).filterValues { it.core != "_deleted_" }
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        val sentinelProps = properties.mapValues { (_, v) -> v ?: "_deleted_".strVal }
        if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
        if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
        if (!presentDelta.containsEdge(id)) {
            presentDelta.addEdge(id, sentinelProps)
        } else {
            presentDelta.setEdgeProperties(id, sentinelProps)
        }
    }

    override fun deleteEdge(id: EdgeID) {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        if (presentDelta.containsEdge(id)) presentDelta.deleteEdge(id)
        if (baseDelta.containsEdge(id)) deletedEdgesHolder.add(id)
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        val base = if (baseDelta.containsNode(id)) baseDelta.getIncomingEdges(id) else emptySet()
        val present = if (presentDelta.containsNode(id)) presentDelta.getIncomingEdges(id) else emptySet()
        return (base + present).filter { it !in deletedEdgesHolder }.toSet()
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        val base = if (baseDelta.containsNode(id)) baseDelta.getOutgoingEdges(id) else emptySet()
        val present = if (presentDelta.containsNode(id)) presentDelta.getOutgoingEdges(id) else emptySet()
        return (base + present).filter { it !in deletedEdgesHolder }.toSet()
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            return baseDelta.metaNames + presentDelta.metaNames
        }

    override fun getMeta(name: String): IValue? {
        if (isClosed) throw AccessClosedStorageException()
        return presentDelta.getMeta(name) ?: baseDelta.getMeta(name)
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (isClosed) throw AccessClosedStorageException()
        presentDelta.setMeta(name, value)
    }

    // ============================================================================
    // UTILITY OPERATIONS
    // ============================================================================

    override fun clear(): Boolean = presentDelta.clear()

    override fun close() {
        isClosed = true
        presentDelta.close()
    }
}
