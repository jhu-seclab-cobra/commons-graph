package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.*
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.strVal
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe implementation of [DeltaStorageImpl] that manages a sequence of delta slices (snapshots).
 * Each delta represents a state of the storage and contributes to the final state of the storage system.
 *
 * This implementation is designed for use in multi-threaded environments, providing thread-safe access
 * to the underlying delta storage. It uses concurrent collections and synchronization to ensure that
 * operations are atomic and consistent across threads.
 *
 *  Please pay attention to that the individual modifications for the delta storage will BREAK the correctness of the
 *  storage, so that all modifications should be done through the method existing in the [DeltaConcurStorageImpl].
 *
 * Modifications are always applied to the latest delta, and newer deltas take precedence over older ones
 * in terms of data priority.
 *
 * @param baseDelta The base storage that serves as the foundation for all deltas.
 * @param presentDelta Optional additional storage for current changes. Defaults to [NativeStorageImpl].
 */
class DeltaConcurStorageImpl(
    private val baseDelta: IStorage,
    private val presentDelta: IStorage = NativeStorageImpl(),
) : IStorage {

    private val nodeCounter = AtomicInteger(0)
    private val edgeCounter = AtomicInteger(0)
    private val deletedNodesHolder = CopyOnWriteArraySet<NodeID>()
    private val deletedEdgesHolder = CopyOnWriteArraySet<EdgeID>()

    private val lock = ReentrantReadWriteLock()
    private val isClosed = AtomicBoolean(false)

    init {
        // Initialize counters with the current size of the base and present deltas
        val baseNodeSize = baseDelta.nodeSize
        val baseEdgeSize = baseDelta.edgeSize
        val presentNodeSize = presentDelta.nodeIDsSequence.count { it !in baseDelta }
        val presentEdgeSize = presentDelta.edgeIDsSequence.count { it !in baseDelta }

        nodeCounter.set(baseNodeSize + presentNodeSize)
        edgeCounter.set(baseEdgeSize + presentEdgeSize)
    }

    override val nodeSize: Int
        get() {
            // Check if storage is closed before any operation
            if (isClosed.get()) throw AccessClosedStorageException()
            return nodeCounter.get()
        }

    override val edgeSize: Int
        get() {
            // Check if storage is closed before any operation
            if (isClosed.get()) throw AccessClosedStorageException()
            return edgeCounter.get()
        }

    override val nodeIDsSequence: Sequence<NodeID>
        get() {
            // Check if storage is closed before any operation
            if (isClosed.get()) throw AccessClosedStorageException()
            return lock.read {
                (baseDelta.nodeIDsSequence + presentDelta.nodeIDsSequence).filter { it !in deletedNodesHolder }
                    .distinct()
            }
        }

    override val edgeIDsSequence: Sequence<EdgeID>
        get() {
            // Check if storage is closed before any operation
            if (isClosed.get()) throw AccessClosedStorageException()
            return lock.read {
                (baseDelta.edgeIDsSequence + presentDelta.edgeIDsSequence).filter { it !in deletedEdgesHolder }
                    .distinct()
            }
        }

    override fun containsNode(id: NodeID): Boolean {
        // Check if storage is closed before any operation
        if (isClosed.get()) throw AccessClosedStorageException()
        return lock.read {
            if (deletedNodesHolder.contains(id)) false
            else presentDelta.containsNode(id) || baseDelta.containsNode(id)
        }
    }

    override fun containsEdge(id: EdgeID): Boolean {
        // Check if storage is closed before any operation
        if (isClosed.get()) throw AccessClosedStorageException()
        return lock.read {
            if (deletedEdgesHolder.contains(id)) false
            else presentDelta.containsEdge(id) || baseDelta.containsEdge(id)
        }
    }

    override fun addNode(id: NodeID, vararg newProperties: Pair<String, IValue>) {
        lock.write {
            if (containsNode(id)) throw EntityAlreadyExistException(id)
            nodeCounter.incrementAndGet()
            deletedNodesHolder.remove(element = id)
            if (baseDelta.containsNode(id) || presentDelta.containsNode(id)) return@write
            presentDelta.addNode(id, *newProperties)
        }
    }

    override fun addEdge(id: EdgeID, vararg newProperties: Pair<String, IValue>) {
        lock.write {
            if (containsEdge(id)) throw EntityAlreadyExistException(id)
            if (!containsNode(id.srcNid)) throw EntityNotExistException(id.srcNid)
            if (!containsNode(id.dstNid)) throw EntityNotExistException(id.dstNid)
            edgeCounter.incrementAndGet()
            deletedEdgesHolder.remove(element = id)
            if (baseDelta.containsEdge(id) || presentDelta.containsEdge(id)) return@write
            if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
            if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
            presentDelta.addEdge(id, newProperties = newProperties)
        }
    }

    override fun getNodeProperties(id: NodeID): Map<String, IValue> {
        if (!containsNode(id)) throw EntityNotExistException(id)
        return lock.read {
            val foundProps = if (baseDelta.containsNode(id)) baseDelta.getNodeProperties(id) else emptyMap()
            val presentProps = if (presentDelta.containsNode(id)) presentDelta.getNodeProperties(id) else emptyMap()
            (foundProps + presentProps).filterValues { v -> v.core != "_deleted_" }
        }
    }

    override fun getNodeProperty(id: NodeID, byName: String): IValue? {
        if (!containsNode(id)) throw EntityNotExistException(id)
        return lock.read {
            // First check if the property is deleted in the present delta
            val presentProp = if (presentDelta.containsNode(id)) presentDelta.getNodeProperty(id, byName) else null
            if (presentProp != null) {
                // If the property exists in present delta and is marked as deleted, return null
                if (presentProp.core == "_deleted_") return@read null
                return@read presentProp
            }

            // If not in present delta, check base delta
            if (baseDelta.containsNode(id)) baseDelta.getNodeProperty(id, byName) else null
        }
    }

    override fun getEdgeProperties(id: EdgeID): Map<String, IValue> {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        return lock.read {
            val foundProps = if (baseDelta.containsEdge(id)) baseDelta.getEdgeProperties(id) else emptyMap()
            val presentProps = if (presentDelta.containsEdge(id)) presentDelta.getEdgeProperties(id) else emptyMap()
            (foundProps + presentProps).filterValues { v -> v.core != "_deleted_" }
        }
    }

    override fun getEdgeProperty(id: EdgeID, byName: String): IValue? {
        if (!containsEdge(id)) throw EntityNotExistException(id)
        return lock.read {
            if (!presentDelta.containsEdge(id)) {
                baseDelta.getEdgeProperty(id, byName)
            } else {
                presentDelta.getEdgeProperty(id, byName)?.takeIf { it.core != "_deleted_" }
                    ?: if (baseDelta.containsEdge(id)) baseDelta.getEdgeProperty(id, byName) else null
            }
        }
    }

    override fun setNodeProperties(id: NodeID, vararg newProperties: Pair<String, IValue?>) {
        lock.write {
            if (!containsNode(id)) throw EntityNotExistException(id)
            val nullSafeProps = newProperties.map { (k, v) -> k to (v ?: "_deleted_".strVal) }.toTypedArray()
            if (!presentDelta.containsNode(id)) presentDelta.addNode(id, *nullSafeProps)
            else presentDelta.setNodeProperties(id, newProperties = nullSafeProps)
        }
    }

    override fun setEdgeProperties(id: EdgeID, vararg newProperties: Pair<String, IValue?>) {
        lock.write {
            if (id !in this) throw EntityNotExistException(id)
            val nullSafeProps = newProperties.map { (k, v) -> k to (v ?: "_deleted_".strVal) }.toTypedArray()
            if (!presentDelta.containsNode(id.srcNid)) presentDelta.addNode(id.srcNid)
            if (!presentDelta.containsNode(id.dstNid)) presentDelta.addNode(id.dstNid)
            if (!presentDelta.containsEdge(id)) presentDelta.addEdge(id, *nullSafeProps)
            else presentDelta.setEdgeProperties(id, newProperties = nullSafeProps)
        }
    }

    override fun deleteNode(id: NodeID) = lock.write {
        if (!containsNode(id)) throw EntityNotExistException(id) else nodeCounter.decrementAndGet()
        if (presentDelta.containsNode(id)) {
            val prevCount = presentDelta.getOutgoingEdges(id).size + presentDelta.getIncomingEdges(id).size
            edgeCounter.accumulateAndGet(prevCount) { a, b -> a - b }
            presentDelta.deleteNode(id = id)
        }
        if (!baseDelta.containsNode(id)) return@write
        baseDelta.getOutgoingEdges(id).forEach {
            deletedEdgesHolder.add(it)
            edgeCounter.decrementAndGet()
        }
        baseDelta.getIncomingEdges(id).forEach {
            deletedEdgesHolder.add(it)
            edgeCounter.decrementAndGet()
        }
        deletedNodesHolder.add(id)
    }

    override fun deleteEdge(id: EdgeID) {
        lock.write {
            if (!containsEdge(id)) throw EntityNotExistException(id) else edgeCounter.decrementAndGet()
            if (presentDelta.containsEdge(id)) presentDelta.deleteEdge(id = id)
            if (baseDelta.containsEdge(id)) deletedEdgesHolder.add(id)
        }
    }

    override fun getIncomingEdges(id: NodeID): Set<EdgeID> {
        return lock.read {
            val found = if (!baseDelta.containsNode(id)) emptySequence()
            else baseDelta.getIncomingEdges(id = id).asSequence()
            val present = if (!presentDelta.containsNode(id)) emptySequence()
            else presentDelta.getIncomingEdges(id = id).asSequence()
            (found + present).filter { it !in deletedEdgesHolder }.toSet()
        }
    }

    override fun getOutgoingEdges(id: NodeID): Set<EdgeID> {
        return lock.read {
            val found = if (!baseDelta.containsNode(id)) emptySequence()
            else baseDelta.getOutgoingEdges(id = id).asSequence()
            val present = if (!presentDelta.containsNode(id)) emptySequence()
            else presentDelta.getOutgoingEdges(id = id).asSequence()
            (found + present).filter { it !in deletedEdgesHolder }.toSet()
        }
    }

    override fun getEdgesBetween(from: NodeID, to: NodeID): Set<EdgeID> {
        return lock.read {
            getOutgoingEdges(from).filter { it.dstNid == to }.toSet()
        }
    }

    override fun clear(): Boolean {
        return lock.write {
            presentDelta.clear()
        }
    }

    override fun close() {
        lock.write {
            isClosed.set(true)
            presentDelta.close()
        }
    }

}
