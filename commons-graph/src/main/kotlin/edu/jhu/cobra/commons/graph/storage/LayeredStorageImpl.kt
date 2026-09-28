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
 * [criticalSectionLock] is its write lock; revert listeners run while it is held.
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

    private val active = ActiveLayer()
    private var frozen: FrozenLayer? = null

    // ============================================================================
    // LAYERED STORAGE API
    // ============================================================================

    public val layerCount: Int get() = lock.readLock().withLock { if (frozen != null) 2 else 1 }

    public fun freeze() {
        lock.writeLock().withLock {
            val merged = FrozenLayer.merge(frozen, active, frozenLayerFactory())
            frozen?.close()
            frozen = merged
            active.clear()
        }
    }

    /**
     * Clears all data in the active layer while preserving the frozen layer.
     *
     * Removes all active-layer nodes, edges, properties, metadata, and tombstones.
     * The frozen layer and its ID mappings remain intact, so subsequent
     * reads still resolve against frozen data. Revert listeners receive the
     * active-only node and edge IDs.
     */
    public fun clearActiveLayer(): Unit =
        lock.writeLock().withLock {
            val (removedNodes, removedEdges) = activeOnlyIdsUnlocked()
            active.clear()
            notifyRevert(removedNodes, removedEdges)
        }

    override fun addRevertListener(listener: RevertListener) {
        revertListeners.add(listener)
    }

    // Promoted entities and tombstoned edges survive a revert in the frozen layer.
    private fun activeOnlyIdsUnlocked(): Pair<Set<Int>, Set<Int>> {
        val frozenLayer = frozen ?: return active.nodeIds.toSet() to active.edgeEndpoints.keys.toSet()
        val nodes = active.nodeIds.filterTo(HashSet()) { !frozenLayer.containsNode(it) }
        val edges = active.edgeEndpoints.keys.filterTo(HashSet()) { !frozenLayer.containsEdge(it) }
        return nodes to edges
    }

    private fun notifyRevert(
        removedNodes: Set<Int>,
        removedEdges: Set<Int>,
    ) {
        for (listener in revertListeners) listener.onRevert(removedNodes, removedEdges)
    }

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() = lock.readLock().withLock { java.util.Set.copyOf(nodeIdsUnlocked()) }

    private fun nodeIdsUnlocked(): Set<Int> {
        val frozenIds = frozen?.nodeIds ?: emptySet()
        if (active.nodeIds.isEmpty()) return frozenIds
        if (frozenIds.isEmpty()) return active.nodeIds
        return UnionSet(frozenIds, active.nodeIds)
    }

    override fun containsNode(id: Int): Boolean = lock.readLock().withLock { containsNodeUnlocked(id) }

    private fun containsNodeUnlocked(id: Int): Boolean = active.containsNode(id) || frozen?.containsNode(id) == true

    override fun addNode(properties: Map<String, IValue>): Int =
        lock.writeLock().withLock {
            val id = nodeCounter++
            active.addNode(id, properties)
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        lock.readLock().withLock { java.util.Map.copyOf(getNodePropertiesUnlocked(id)) }

    private fun getNodePropertiesUnlocked(id: Int): Map<String, IValue> {
        if (active.containsNode(id)) return ColumnViewMap(id, active.nodeColumns)
        return frozen?.nodeProperties(id) ?: throw EntityNotExistException(id.toString())
    }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            when {
                active.containsNode(id) -> active.nodeColumns[name]?.get(id)
                frozen?.containsNode(id) == true -> frozen?.nodeProperty(id, name)
                else -> throw EntityNotExistException(id.toString())
            }
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        lock.writeLock().withLock {
            if (!containsNodeUnlocked(id)) throw EntityNotExistException(id.toString())
            ensureNodeInActiveLayerUnlocked(id)
            active.setNodeProperties(id, properties)
        }

    override fun deleteNode(id: Int): Unit =
        lock.writeLock().withLock {
            // A promoted node still exists in the frozen layer; deleting only the active
            // copy would resurrect the frozen one, so the frozen guard runs first.
            if (frozen?.containsNode(id) == true) throw FrozenLayerModificationException(id.toString())
            if (!active.containsNode(id)) throw EntityNotExistException(id.toString())
            active.removeNode(id)
        }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<Int>
        get() = lock.readLock().withLock { java.util.Set.copyOf(edgeIdsUnlocked()) }

    private fun edgeIdsUnlocked(): Set<Int> {
        val frozenIds = frozen?.edgeIds?.let(::withoutTombstones) ?: emptySet()
        if (active.edgeEndpoints.isEmpty()) return frozenIds
        if (frozenIds.isEmpty()) return active.edgeEndpoints.keys
        return UnionSet(frozenIds, active.edgeEndpoints.keys)
    }

    override fun containsEdge(id: Int): Boolean = lock.readLock().withLock { containsEdgeUnlocked(id) }

    private fun containsEdgeUnlocked(id: Int): Boolean = active.containsEdge(id) || containsFrozenEdgeUnlocked(id)

    // A frozen edge is visible unless the active layer tombstoned it.
    private fun containsFrozenEdgeUnlocked(id: Int): Boolean = frozen?.containsEdge(id) == true && id !in active.deletedEdgeIds

    private fun withoutTombstones(frozenEdges: Set<Int>): Set<Int> =
        if (active.deletedEdgeIds.isEmpty()) frozenEdges else ExcludingSet(frozenEdges, active.deletedEdgeIds)

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int =
        lock.writeLock().withLock {
            ensureNodeInActiveLayerUnlocked(src)
            ensureNodeInActiveLayerUnlocked(dst)
            val id = edgeCounter++
            active.addEdge(id, IStorage.EdgeStructure(src, dst, tag), properties)
            id
        }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure = lock.readLock().withLock { getEdgeStructureUnlocked(id) }

    private fun getEdgeStructureUnlocked(id: Int): IStorage.EdgeStructure {
        active.edgeEndpoints[id]?.let { return it }
        if (!containsFrozenEdgeUnlocked(id)) throw EntityNotExistException(id.toString())
        return checkNotNull(frozen?.edgeStructure(id))
    }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        lock.readLock().withLock { java.util.Map.copyOf(getEdgePropertiesUnlocked(id)) }

    private fun getEdgePropertiesUnlocked(id: Int): Map<String, IValue> {
        if (active.containsEdge(id)) return ColumnViewMap(id, active.edgeColumns)
        if (!containsFrozenEdgeUnlocked(id)) throw EntityNotExistException(id.toString())
        return checkNotNull(frozen?.edgeProperties(id))
    }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? =
        lock.readLock().withLock {
            when {
                active.containsEdge(id) -> active.edgeColumns[name]?.get(id)
                containsFrozenEdgeUnlocked(id) -> frozen?.edgeProperty(id, name)
                else -> throw EntityNotExistException(id.toString())
            }
        }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        lock.writeLock().withLock {
            if (!active.containsEdge(id)) {
                // Promote the frozen edge by copying all frozen properties, making the
                // active copy authoritative for subsequent reads and deletions.
                val frozenProps = getEdgePropertiesUnlocked(id)
                val structure = getEdgeStructureUnlocked(id)
                ensureNodeInActiveLayerUnlocked(structure.src)
                ensureNodeInActiveLayerUnlocked(structure.dst)
                active.addEdge(id, structure, frozenProps)
            }
            active.setEdgeProperties(id, properties)
        }

    override fun deleteEdge(id: Int): Unit =
        lock.writeLock().withLock {
            val frozenEdge = containsFrozenEdgeUnlocked(id)
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
            copyAdjacency(id, active.inEdges[id], frozen?.incomingEdges(id)?.let(::withoutTombstones))
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        lock.readLock().withLock {
            copyAdjacency(id, active.outEdges[id], frozen?.outgoingEdges(id)?.let(::withoutTombstones))
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
        get() = lock.readLock().withLock { java.util.Set.copyOf(metaNamesUnlocked()) }

    private fun metaNamesUnlocked(): Set<String> {
        val frozenNames = (frozen?.metaNames ?: emptySet()) - active.deletedMetaNames
        if (active.metaProperties.isEmpty()) return frozenNames
        if (frozenNames.isEmpty()) return active.metaProperties.keys
        return UnionSet(frozenNames, active.metaProperties.keys)
    }

    override fun getMeta(name: String): IValue? = lock.readLock().withLock { getMetaUnlocked(name) }

    private fun getMetaUnlocked(name: String): IValue? {
        active.metaProperties[name]?.let { return it }
        if (name in active.deletedMetaNames) return null
        return frozen?.meta(name)
    }

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
            val removedNodes = nodeIdsUnlocked().toSet()
            val removedEdges = edgeIdsUnlocked().toSet()
            frozen?.close()
            frozen = null
            active.clear()
            nodeCounter = 0
            edgeCounter = 0
            notifyRevert(removedNodes, removedEdges)
        }

    override fun transferTo(target: IStorage): Map<Int, Int> {
        // Copying into the iterated storage mutates it mid-iteration; reject up front.
        require(target !== this) { "Cannot transfer a storage into itself" }
        return lock.readLock().withLock {
            val nodeIdMap = HashMap<Int, Int>()
            for (nodeId in nodeIdsUnlocked()) {
                nodeIdMap[nodeId] = target.addNode(getNodePropertiesUnlocked(nodeId))
            }
            for (edgeId in edgeIdsUnlocked()) {
                val structure = getEdgeStructureUnlocked(edgeId)
                val newSrc = nodeIdMap.getValue(structure.src)
                val newDst = nodeIdMap.getValue(structure.dst)
                target.addEdge(newSrc, newDst, structure.tag, getEdgePropertiesUnlocked(edgeId))
            }
            for (name in metaNamesUnlocked()) {
                target.setMeta(name, getMetaUnlocked(name))
            }
            nodeIdMap
        }
    }

    override fun flush() {
        // No buffered writes; mutations are applied immediately.
    }

    // ============================================================================
    // INTERNAL HELPERS
    // ============================================================================

    // Writes always land in the active layer; a frozen node is promoted by copying
    // its frozen properties into the active columns first.
    private fun ensureNodeInActiveLayerUnlocked(id: Int) {
        if (active.containsNode(id)) return
        val props = frozen?.nodeProperties(id) ?: throw EntityNotExistException(id.toString())
        active.addNode(id, props)
    }
}
