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
    private val deletedNodesHolder = HashSet<NodeID>()
    private val deletedEdgesHolder = HashSet<EdgeID>()

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            val result = HashSet<NodeID>(baseDelta.nodeIDs.size + presentDelta.nodeIDs.size)
            result.addAll(baseDelta.nodeIDs)
            result.addAll(presentDelta.nodeIDs)
            result.removeAll(deletedNodesHolder)
            return result
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            if (isClosed) throw AccessClosedStorageException()
            val result = HashSet<EdgeID>(baseDelta.edgeIDs.size + presentDelta.edgeIDs.size)
            result.addAll(baseDelta.edgeIDs)
            result.addAll(presentDelta.edgeIDs)
            result.removeAll(deletedEdgesHolder)
            return result
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
        val inBase = baseDelta.containsNode(id)
        val inPresent = presentDelta.containsNode(id)
        // Fast path: node only in one layer — skip merging
        if (inBase && !inPresent) return baseDelta.getNodeProperties(id)
        if (inPresent && !inBase) return presentDelta.getNodeProperties(id)
        // Merge: base first, present overlays
        val result = HashMap<String, IValue>()
        result.putAll(baseDelta.getNodeProperties(id))
        result.putAll(presentDelta.getNodeProperties(id))
        result.values.removeAll { it.core == "_deleted_" }
        return result
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
        val inBase = baseDelta.containsEdge(id)
        val inPresent = presentDelta.containsEdge(id)
        // Fast path: edge only in one layer — skip merging
        if (inBase && !inPresent) return baseDelta.getEdgeProperties(id)
        if (inPresent && !inBase) return presentDelta.getEdgeProperties(id)
        // Merge: base first, present overlays
        val result = HashMap<String, IValue>()
        result.putAll(baseDelta.getEdgeProperties(id))
        result.putAll(presentDelta.getEdgeProperties(id))
        result.values.removeAll { it.core == "_deleted_" }
        return result
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
        if (deletedEdgesHolder.isEmpty()) return base + present
        val result = HashSet<EdgeID>(base.size + present.size)
        result.addAll(base)
        result.addAll(present)
        result.removeAll(deletedEdgesHolder)
        return result
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        val base = if (baseDelta.containsNode(id)) baseDelta.getOutgoingEdges(id) else emptySet()
        val present = if (presentDelta.containsNode(id)) presentDelta.getOutgoingEdges(id) else emptySet()
        if (deletedEdgesHolder.isEmpty()) return base + present
        val result = HashSet<EdgeID>(base.size + present.size)
        result.addAll(base)
        result.addAll(present)
        result.removeAll(deletedEdgesHolder)
        return result
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
