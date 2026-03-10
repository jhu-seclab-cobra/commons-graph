package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.strVal
import java.util.concurrent.CopyOnWriteArraySet
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

    private val deletedNodesHolder = CopyOnWriteArraySet<NodeID>()
    private val deletedEdgesHolder = CopyOnWriteArraySet<EdgeID>()

    private val lock = ReentrantReadWriteLock()
    private val isClosed = AtomicBoolean(false)

    // ============================================================================
    // PROPERTIES AND STATISTICS
    // ============================================================================

    override val nodeIDs: Set<NodeID>
        get() {
            if (isClosed.get()) throw AccessClosedStorageException()
            return lock.read {
                (baseDelta.nodeIDs + presentDelta.nodeIDs)
                    .filter { it !in deletedNodesHolder }
                    .toSet()
            }
        }

    override val edgeIDs: Set<EdgeID>
        get() {
            if (isClosed.get()) throw AccessClosedStorageException()
            return lock.read {
                (baseDelta.edgeIDs + presentDelta.edgeIDs)
                    .filter { it !in deletedEdgesHolder }
                    .toSet()
            }
        }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override fun containsNode(id: NodeID): Boolean {
        if (isClosed.get()) throw AccessClosedStorageException()
        return lock.read {
            if (deletedNodesHolder.contains(id)) false
            else presentDelta.containsNode(id) || baseDelta.containsNode(id)
        }
    }

    override fun addNode(id: NodeID, properties: Map<String, IValue>) {
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
            val baseProps = if (baseDelta.containsNode(id)) baseDelta.getNodeProperties(id) else emptyMap()
            val presentProps = if (presentDelta.containsNode(id)) presentDelta.getNodeProperties(id) else emptyMap()
            (baseProps + presentProps).filterValues { it.core != "_deleted_" }
        }
    }

    override fun setNodeProperties(id: NodeID, properties: Map<String, IValue?>) {
        lock.write {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val sentinelProps = properties.mapValues { (_, v) -> v ?: "_deleted_".strVal }
            if (!presentDelta.containsNode(id)) presentDelta.addNode(id, sentinelProps)
            else presentDelta.setNodeProperties(id, sentinelProps)
        }
    }

    override fun deleteNode(id: NodeID): Unit = lock.write {
        if (!containsNode(id)) throw EntityNotExistException(id)
        if (presentDelta.containsNode(id)) presentDelta.deleteNode(id)
        if (!baseDelta.containsNode(id)) return@write
        baseDelta.getOutgoingEdges(id).forEach { deletedEdgesHolder.add(it) }
        baseDelta.getIncomingEdges(id).forEach { deletedEdgesHolder.add(it) }
        deletedNodesHolder.add(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override fun containsEdge(id: EdgeID): Boolean {
        if (isClosed.get()) throw AccessClosedStorageException()
        return lock.read {
            if (deletedEdgesHolder.contains(id)) false
            else presentDelta.containsEdge(id) || baseDelta.containsEdge(id)
        }
    }

    override fun addEdge(id: EdgeID, properties: Map<String, IValue>) {
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
            val baseProps = if (baseDelta.containsEdge(id)) baseDelta.getEdgeProperties(id) else emptyMap()
            val presentProps = if (presentDelta.containsEdge(id)) presentDelta.getEdgeProperties(id) else emptyMap()
            (baseProps + presentProps).filterValues { it.core != "_deleted_" }
        }
    }

    override fun setEdgeProperties(id: EdgeID, properties: Map<String, IValue?>) {
        lock.write {
            if (!containsEdge(id)) throw EntityNotExistException(id)
            val sentinelProps = properties.mapValues { (_, v) -> v ?: "_deleted_".strVal }
            if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
            if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
            if (!presentDelta.containsEdge(id)) presentDelta.addEdge(id, sentinelProps)
            else presentDelta.setEdgeProperties(id, sentinelProps)
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

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        return lock.read {
            val base = if (baseDelta.containsNode(id)) baseDelta.getIncomingEdges(id) else emptySet()
            val present = if (presentDelta.containsNode(id)) presentDelta.getIncomingEdges(id) else emptySet()
            (base + present).filter { it !in deletedEdgesHolder }.toSet()
        }
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        return lock.read {
            val base = if (baseDelta.containsNode(id)) baseDelta.getOutgoingEdges(id) else emptySet()
            val present = if (presentDelta.containsNode(id)) presentDelta.getOutgoingEdges(id) else emptySet()
            (base + present).filter { it !in deletedEdgesHolder }.toSet()
        }
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override fun getMeta(name: String): IValue? {
        if (isClosed.get()) throw AccessClosedStorageException()
        return lock.read {
            presentDelta.getMeta(name) ?: baseDelta.getMeta(name)
        }
    }

    override fun setMeta(name: String, value: IValue?) {
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
