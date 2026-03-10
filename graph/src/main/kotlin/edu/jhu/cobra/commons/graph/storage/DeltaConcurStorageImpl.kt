package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.strVal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe two-layer overlay storage composing a frozen base and a mutable present layer.
 *
 * Wraps the same delta semantics as [DeltaStorageImpl] with [ReentrantReadWriteLock]
 * for concurrent access. Multiple concurrent reads are allowed; writes are exclusive.
 *
 * Uses [HashSet] for deleted-entity holders (protected by the existing RW lock),
 * providing O(1) contains checks instead of CopyOnWriteArraySet's O(n) linear scan.
 *
 * The base layer is never mutated. `close()` only closes the present layer;
 * the base layer lifecycle is managed externally.
 *
 * @param baseDelta The frozen/read-only base layer (injected).
 * @param presentDelta The mutable overlay layer. Defaults to [NativeStorageImpl].
 */
class DeltaConcurStorageImpl(
    private val baseDelta: IStorage,
    private val presentDelta: IStorage = NativeStorageImpl(),
) : IStorage {
    // HashSet protected by the RW lock — O(1) contains vs CopyOnWriteArraySet's O(n)
    private val deletedNodesHolder = HashSet<NodeID>()
    private val deletedEdgesHolder = HashSet<EdgeID>()

    private val lock = ReentrantReadWriteLock()
    private val isClosed = AtomicBoolean(false)

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() {
            if (isClosed.get()) throw AccessClosedStorageException()
            return lock.read {
                val result = HashSet<NodeID>(baseDelta.nodeIDs.size + presentDelta.nodeIDs.size)
                result.addAll(baseDelta.nodeIDs)
                result.addAll(presentDelta.nodeIDs)
                result.removeAll(deletedNodesHolder)
                result
            }
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            if (isClosed.get()) throw AccessClosedStorageException()
            return lock.read {
                val result = HashSet<EdgeID>(baseDelta.edgeIDs.size + presentDelta.edgeIDs.size)
                result.addAll(baseDelta.edgeIDs)
                result.addAll(presentDelta.edgeIDs)
                result.removeAll(deletedEdgesHolder)
                result
            }
        }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed.get()) throw AccessClosedStorageException()
        return lock.read {
            if (deletedNodesHolder.contains(id)) {
                false
            } else {
                presentDelta.containsNode(id) || baseDelta.containsNode(id)
            }
        }
    }

    override fun addNode(
        id: NodeID,
        properties: Map<String, IValue>,
    ) {
        lock.write {
            if (isClosed.get()) throw AccessClosedStorageException()
            if (containsNode(id)) throw EntityAlreadyExistException(id)
            deletedNodesHolder.remove(id)
            if (baseDelta.containsNode(id) || presentDelta.containsNode(id)) return@write
            presentDelta.addNode(id, properties)
        }
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        return lock.read {
            val inBase = baseDelta.containsNode(id)
            val inPresent = presentDelta.containsNode(id)
            if (inBase && !inPresent) return@read baseDelta.getNodeProperties(id)
            if (inPresent && !inBase) return@read presentDelta.getNodeProperties(id)
            val result = HashMap<String, IValue>()
            result.putAll(baseDelta.getNodeProperties(id))
            result.putAll(presentDelta.getNodeProperties(id))
            result.values.removeAll { it.core == "_deleted_" }
            result
        }
    }

    override fun setNodeProperties(
        id: NodeID,
        properties: Map<String, IValue?>,
    ) {
        lock.write {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val sentinelProps = properties.mapValues { (_, v) -> v ?: "_deleted_".strVal }
            if (!presentDelta.containsNode(id)) {
                presentDelta.addNode(id, sentinelProps)
            } else {
                presentDelta.setNodeProperties(id, sentinelProps)
            }
        }
    }

    override fun deleteNode(id: NodeID): Unit =
        lock.write {
            if (!containsNode(id)) throw EntityNotExistException(id)
            if (presentDelta.containsNode(id)) presentDelta.deleteNode(id)
            if (!baseDelta.containsNode(id)) return@write
            deletedEdgesHolder.addAll(baseDelta.getOutgoingEdges(id))
            deletedEdgesHolder.addAll(baseDelta.getIncomingEdges(id))
            deletedNodesHolder.add(id)
        }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed.get()) throw AccessClosedStorageException()
        return lock.read {
            if (deletedEdgesHolder.contains(id)) {
                false
            } else {
                presentDelta.containsEdge(id) || baseDelta.containsEdge(id)
            }
        }
    }

    override fun addEdge(
        id: EdgeID,
        properties: Map<String, IValue>,
    ) {
        lock.write {
            if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
            if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
            if (containsEdge(id)) throw EntityAlreadyExistException(id)
            deletedEdgesHolder.remove(id)
            if (baseDelta.containsEdge(id) || presentDelta.containsEdge(id)) return@write
            if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
            if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
            presentDelta.addEdge(id, properties)
        }
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        return lock.read {
            val inBase = baseDelta.containsEdge(id)
            val inPresent = presentDelta.containsEdge(id)
            if (inBase && !inPresent) return@read baseDelta.getEdgeProperties(id)
            if (inPresent && !inBase) return@read presentDelta.getEdgeProperties(id)
            val result = HashMap<String, IValue>()
            result.putAll(baseDelta.getEdgeProperties(id))
            result.putAll(presentDelta.getEdgeProperties(id))
            result.values.removeAll { it.core == "_deleted_" }
            result
        }
    }

    override fun setEdgeProperties(
        id: EdgeID,
        properties: Map<String, IValue?>,
    ) {
        lock.write {
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
    }

    override fun deleteEdge(id: EdgeID) {
        lock.write {
            if (!containsEdge(id)) throw EntityNotExistException(id)
            if (presentDelta.containsEdge(id)) presentDelta.deleteEdge(id)
            if (baseDelta.containsEdge(id)) deletedEdgesHolder.add(id)
        }
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> =
        lock.read {
            val base = if (baseDelta.containsNode(id)) baseDelta.getIncomingEdges(id) else emptySet()
            val present = if (presentDelta.containsNode(id)) presentDelta.getIncomingEdges(id) else emptySet()
            if (deletedEdgesHolder.isEmpty()) return@read base + present
            val result = HashSet<EdgeID>(base.size + present.size)
            result.addAll(base)
            result.addAll(present)
            result.removeAll(deletedEdgesHolder)
            result
        }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> =
        lock.read {
            val base = if (baseDelta.containsNode(id)) baseDelta.getOutgoingEdges(id) else emptySet()
            val present = if (presentDelta.containsNode(id)) presentDelta.getOutgoingEdges(id) else emptySet()
            if (deletedEdgesHolder.isEmpty()) return@read base + present
            val result = HashSet<EdgeID>(base.size + present.size)
            result.addAll(base)
            result.addAll(present)
            result.removeAll(deletedEdgesHolder)
            result
        }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() {
            if (isClosed.get()) throw AccessClosedStorageException()
            return lock.read { baseDelta.metaNames + presentDelta.metaNames }
        }

    override fun getMeta(name: String): IValue? {
        if (isClosed.get()) throw AccessClosedStorageException()
        return lock.read {
            presentDelta.getMeta(name) ?: baseDelta.getMeta(name)
        }
    }

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (isClosed.get()) throw AccessClosedStorageException()
        lock.write { presentDelta.setMeta(name, value) }
    }

    // ============================================================================
    // UTILITY OPERATIONS
    // ============================================================================

    override fun clear(): Boolean = lock.write { presentDelta.clear() }

    override fun close() {
        lock.write {
            isClosed.set(true)
            presentDelta.close()
        }
    }
}
