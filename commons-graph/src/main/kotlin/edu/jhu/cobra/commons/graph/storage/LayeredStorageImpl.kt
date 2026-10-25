package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.FrozenLayerModificationException
import edu.jhu.cobra.commons.value.IValue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Multi-layer freeze-and-stack storage for phased analysis pipelines.
 *
 * Active layer data lives in an [ActiveLayer] using global Int IDs.
 * The frozen layer is a [FrozenLayer]: an independent [IStorage] snapshot with
 * its own local IDs plus global-to-local ID mappings.
 *
 * Each [freeze] merges active + frozen data into a new frozen layer and resets
 * the active layer, keeping query depth at O(1).
 *
 * Query resolution:
 * - Properties: the active copy is authoritative for entities present in the active
 *   layer — promotion copies all frozen properties, and a property deleted from the
 *   copy stays deleted. Frozen-only entities read from the frozen layer.
 * - Adjacency: returns the union of both layers.
 *
 * Deleting a frozen-layer edge — including one promoted into the active layer — records
 * a tombstone in the active layer: the edge reads as absent until [clearActiveLayer]
 * drops the tombstone, and [freeze] excludes it from the merged layer. Node deletion is
 * restricted to nodes that exist only in the active layer; deleting a frozen-layer node
 * throws [FrozenLayerModificationException].
 *
 * Thread-safe: every operation acquires [lock], mirroring [NativeConcurStorageImpl]. Every
 * returned collection is a copy taken under the lock; later writes, [freeze], and
 * [clearActiveLayer] never change it.
 * [criticalSectionLock] is its write lock; revert listeners run while it is held. [clearActiveLayer] and
 * [clear] each notify one [RevertNotice] naming the removed, restored and structure-changed entities.
 * Uses internal non-locking helpers to avoid lock re-entrance overhead.
 *
 * @param frozenLayerFactory Factory for creating storage instances used as frozen layers.
 * @see FrozenLayerModificationException
 */
@Suppress("TooManyFunctions")
public class LayeredStorageImpl(
    private val frozenLayerFactory: () -> IStorage = { NativeStorageImpl() },
) : IRevertibleStorage {
    private val lock = ReentrantReadWriteLock()

    override val criticalSectionLock: Lock get() = lock.writeLock()
    private val revertListeners = CopyOnWriteArrayList<RevertListener>()

    // Global ID counters (protected by write lock)
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    private val layers = LayerStack()
    private val active = layers.active

    // ============================================================================
    // LAYERED STORAGE API
    // ============================================================================

    public val layerCount: Int get() = lock.readLock().withLock { if (layers.frozen != null) 2 else 1 }

    public fun freeze() {
        lock.writeLock().withLock {
            val merged = FrozenLayer.merge(layers.frozen, active, frozenLayerFactory())
            layers.frozen?.close()
            layers.frozen = merged
            active.clear()
        }
    }

    /**
     * Clears all data in the active layer while preserving the frozen layer.
     *
     * Removes all active-layer nodes, edges, properties, metadata, and tombstones.
     * The frozen layer and its ID mappings remain intact, so subsequent
     * reads still resolve against frozen data. The revert listeners receive one [RevertNotice]:
     * the active-only nodes and edges as removed; the promoted frozen nodes and edges, and the
     * tombstoned frozen edges, as restored; and the structures of the edges visible before.
     */
    public fun clearActiveLayer(): Unit =
        lock.writeLock().withLock {
            val notice = activeLayerDropNotice(active, layers.frozen)
            active.clear()
            notifyRevert(notice)
        }

    override fun addRevertListener(listener: RevertListener) {
        revertListeners.add(listener)
    }

    private fun notifyRevert(notice: RevertNotice) {
        for (listener in revertListeners) listener.onRevert(notice)
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() = lock.readLock().withLock { java.util.Set.copyOf(layers.nodeIds()) }

    override fun containsNode(id: Int): Boolean = lock.readLock().withLock { layers.containsNode(id) }

    override fun addNode(properties: Map<String, IValue>): Int =
        lock.writeLock().withLock {
            val id = nodeCounter++
            active.addNode(id, properties)
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        lock.readLock().withLock { java.util.Map.copyOf(layers.nodeProperties(id)) }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? =
        lock.readLock().withLock { layers.nodeProperty(id, name) }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        lock.writeLock().withLock {
            if (!layers.containsNode(id)) throw EntityNotExistException(id.toString())
            layers.promoteNode(id)
            active.setNodeProperties(id, properties)
        }

    override fun deleteNode(id: Int): Unit =
        lock.writeLock().withLock {
            // A promoted node still exists in the frozen layer; deleting only the active
            // copy would resurrect the frozen one, so the frozen guard runs first.
            if (layers.frozen?.containsNode(id) == true) throw FrozenLayerModificationException(id.toString())
            if (!active.containsNode(id)) throw EntityNotExistException(id.toString())
            active.removeNode(id)
        }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<Int>
        get() = lock.readLock().withLock { java.util.Set.copyOf(layers.edgeIds()) }

    override fun containsEdge(id: Int): Boolean = lock.readLock().withLock { layers.containsEdge(id) }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int =
        lock.writeLock().withLock {
            layers.promoteNode(src)
            layers.promoteNode(dst)
            val id = edgeCounter++
            active.addEdge(id, IStorage.EdgeStructure(src, dst, tag), properties)
            id
        }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure = lock.readLock().withLock { layers.edgeStructure(id) }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        lock.readLock().withLock { java.util.Map.copyOf(layers.edgeProperties(id)) }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? = lock.readLock().withLock { layers.edgeProperty(id, name) }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        lock.writeLock().withLock {
            if (!active.containsEdge(id)) {
                // Promote the frozen edge by copying all frozen properties, making the
                // active copy authoritative for subsequent reads and deletions.
                val frozenProps = layers.edgeProperties(id)
                val structure = layers.edgeStructure(id)
                layers.promoteNode(structure.src)
                layers.promoteNode(structure.dst)
                active.addEdge(id, structure, frozenProps)
            }
            active.setEdgeProperties(id, properties)
        }

    override fun deleteEdge(id: Int): Unit =
        lock.writeLock().withLock {
            val frozenEdge = layers.containsFrozenEdge(id)
            if (!frozenEdge && !active.containsEdge(id)) throw EntityNotExistException(id.toString())
            active.removeEdge(id)
            // Dropping only the active copy of a promoted edge would resurrect the
            // frozen one; the tombstone masks it until a revert or freeze.
            if (frozenEdge) active.deletedEdgeIds.add(id)
        }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> =
        lock.readLock().withLock {
            copyAdjacency(id, active.inEdges[id], layers.frozen?.incomingEdges(id)?.let(layers::withoutTombstones))
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        lock.readLock().withLock {
            copyAdjacency(id, active.outEdges[id], layers.frozen?.outgoingEdges(id)?.let(layers::withoutTombstones))
        }

    private fun copyAdjacency(
        id: Int,
        activeEdges: Set<Int>?,
        frozenEdges: Set<Int>?,
    ): Set<Int> {
        if (activeEdges == null && frozenEdges == null) throw EntityNotExistException(id.toString())
        return java.util.Set.copyOf(UnionSet(frozenEdges.orEmpty(), activeEdges.orEmpty()))
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() = lock.readLock().withLock { java.util.Set.copyOf(layers.metaNames()) }

    override fun getMeta(name: String): IValue? = lock.readLock().withLock { layers.meta(name) }

    override fun setMeta(
        name: String,
        value: IValue?,
    ): Unit =
        lock.writeLock().withLock {
            if (value == null) {
                // Tombstone the name so a frozen-layer value does not resurface on reads.
                active.metaProperties.remove(name)
                active.deletedMetaNames.add(name)
            } else {
                active.deletedMetaNames.remove(name)
                active.metaProperties[name] = value
            }
        }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun clear(): Unit =
        lock.writeLock().withLock {
            val removedEdges = layers.edgeIds().toSet()
            val notice =
                RevertNotice(
                    removed = StorageEntityIds(layers.nodeIds().toSet(), removedEdges),
                    restored = StorageEntityIds(emptySet(), emptySet()),
                    edgeStructuresBefore = removedEdges.associateWith(layers::edgeStructure),
                )
            layers.frozen?.close()
            layers.frozen = null
            active.clear()
            nodeCounter = 0
            edgeCounter = 0
            notifyRevert(notice)
        }

    override fun transferTo(target: IStorage): Map<Int, Int> {
        // Copying into the iterated storage mutates it mid-iteration; reject up front.
        require(target !== this) { "Cannot transfer a storage into itself" }
        return lock.readLock().withLock {
            val nodeIdMap = HashMap<Int, Int>()
            for (nodeId in layers.nodeIds()) {
                nodeIdMap[nodeId] = target.addNode(layers.nodeProperties(nodeId))
            }
            for (edgeId in layers.edgeIds()) {
                val structure = layers.edgeStructure(edgeId)
                val newSrc = nodeIdMap.getValue(structure.src)
                val newDst = nodeIdMap.getValue(structure.dst)
                target.addEdge(newSrc, newDst, structure.tag, layers.edgeProperties(edgeId))
            }
            for (name in layers.metaNames()) {
                target.setMeta(name, layers.meta(name))
            }
            nodeIdMap
        }
    }

    override fun flush() {
        // No buffered writes; mutations are applied immediately.
    }
}
