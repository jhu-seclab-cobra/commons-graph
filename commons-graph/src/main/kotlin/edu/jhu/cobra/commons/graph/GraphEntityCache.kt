package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.RevertNotice
import edu.jhu.cobra.commons.value.StrVal
import java.lang.ref.SoftReference

/**
 * Entity cache and ID index for [AbcMultipleGraph].
 *
 * Owns the bidirectional NodeID-to-storage-Int mapping and the softly referenced
 * node/edge wrapper objects, recreating a wrapper on demand via the supplied
 * factories when its soft reference has been collected. [onRevert] reconciles the registry
 * with a revert: it evicts the node entries and edge wrappers the revert removed, evicts the
 * entries of restored rows whose owner mark no longer names the graph, and registers the
 * restored rows whose owner mark names the graph and have no entry.
 *
 * @param isOwned Whether the storage row with the given ID is marked as owned by the graph.
 */
internal class GraphEntityCache<N : AbcNode, E : AbcEdge>(
    private val storage: IStorage,
    private val newNode: () -> N,
    private val newEdge: () -> E,
    private val isOwned: (Int) -> Boolean,
) {
    class NodeEntry<N>(
        val nodeId: NodeID,
        val storageId: Int,
        var ref: SoftReference<N>?,
    )

    private var nodeEntries: MutableMap<NodeID, NodeEntry<N>> = HashMap()
    private val nodeByStorageId = HashMap<Int, NodeEntry<N>>()
    private val edgeCache = HashMap<Int, SoftReference<E>>()

    val nodeIds: Set<NodeID> get() = nodeEntries.keys

    val entries: Collection<NodeEntry<N>> get() = nodeEntries.values

    /**
     * The entry of [nodeId] or of its canonical text. Keys are canonical text, so a hit costs no canonical
     * scan; only a miss computes the canonical text, which is [nodeId] itself for canonical text.
     */
    fun entryOf(nodeId: NodeID): NodeEntry<N>? {
        nodeEntries[nodeId]?.let { return it }
        val canonical = canonicalId(nodeId)
        return if (canonical === nodeId) null else nodeEntries[canonical]
    }

    /** The entry of [canonicalId], which is already canonical text. */
    fun entryOfCanonical(canonicalId: NodeID): NodeEntry<N>? = nodeEntries[canonicalId]

    fun containsNode(nodeId: NodeID): Boolean = entryOf(nodeId) != null

    fun containsStorageId(storageId: Int): Boolean = storageId in nodeByStorageId

    fun resolveNodeId(storageId: Int): NodeID? = nodeByStorageId[storageId]?.nodeId

    /** Registers [nodeId], canonical text, at [storageId]. */
    fun register(
        nodeId: NodeID,
        storageId: Int,
    ): NodeEntry<N> {
        val entry = NodeEntry<N>(nodeId, storageId, null)
        nodeEntries[nodeId] = entry
        nodeByStorageId[storageId] = entry
        return entry
    }

    fun node(entry: NodeEntry<N>): N {
        entry.ref?.get()?.let { return it }
        val node = newNode()
        node.bind(storage, entry.storageId, entry.nodeId)
        entry.ref = SoftReference(node)
        return node
    }

    fun node(storageId: Int): N = node(nodeByStorageId.getValue(storageId))

    fun edge(storageId: Int): E {
        edgeCache[storageId]?.get()?.let { return it }
        val structure = storage.getEdgeStructure(storageId)
        val srcEntry = nodeByStorageId.getValue(structure.src)
        val dstEntry = nodeByStorageId.getValue(structure.dst)
        val edge = newEdge()
        edge.bind(storage, storageId, srcEntry.nodeId, dstEntry.nodeId, structure.tag)
        edgeCache[storageId] = SoftReference(edge)
        return edge
    }

    fun evictEdge(storageId: Int) {
        edgeCache.remove(storageId)
    }

    fun removeNode(entry: NodeEntry<N>) {
        nodeEntries.remove(entry.nodeId)
        nodeByStorageId.remove(entry.storageId)
    }

    /** Storage IDs of the edges, visible in storage, that touch any node of [storageIds]. */
    fun incidentEdges(storageIds: Set<Int>): Set<Int> =
        storageIds.flatMapTo(HashSet()) { storage.getIncomingEdges(it) + storage.getOutgoingEdges(it) }

    /**
     * Reconciles the registry with [notice] and returns the storage IDs of the nodes it newly registered.
     *
     * A reverted storage node need not belong to this graph: graphs and posets share storage. A restored row
     * reads as at the last freeze, so a claim made since then has lost its mark: the entry leaves with it and
     * the next claim marks the row again. A restored row that holds this graph's mark and has no entry is
     * registered.
     */
    fun onRevert(notice: RevertNotice): Set<Int> {
        for (storageId in notice.removed.nodes) nodeByStorageId[storageId]?.let(::removeNode)
        for (storageId in notice.removed.edges) edgeCache.remove(storageId)
        val registered = HashSet<Int>()
        for (storageId in notice.restored.nodes) {
            val entry = nodeByStorageId[storageId]
            when {
                entry != null && !isOwned(storageId) -> removeNode(entry)
                entry == null && registerRestored(storageId) -> registered.add(storageId)
            }
        }
        return registered
    }

    private fun registerRestored(storageId: Int): Boolean {
        if (!isOwned(storageId)) return false
        val nodeId = storage.getNodeProperty(storageId, AbcMultipleGraph.PROP_NODE_ID) as? StrVal ?: return false
        register(nodeId.core, storageId)
        return true
    }

    /** Replaces every entry by the rows of storage that hold this graph's owner mark. */
    fun rebuild() {
        clear()
        for (storageId in storage.nodeIDs) registerRestored(storageId)
    }

    fun rebuildInNodeOrder(storageIds: List<Int>) {
        clear()
        nodeEntries = LinkedHashMap()
        for (storageId in storageIds) registerRestored(storageId)
        for (storageId in storage.nodeIDs) {
            if (!containsStorageId(storageId)) registerRestored(storageId)
        }
    }

    fun clear() {
        nodeEntries.clear()
        nodeByStorageId.clear()
        edgeCache.clear()
    }
}
