package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IRevertibleStorage
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.RevertNotice
import edu.jhu.cobra.commons.graph.storage.criticalSection
import edu.jhu.cobra.commons.value.strVal

/**
 * Abstract directed multi-graph allowing multiple edges between the same pair of
 * nodes in a given direction.
 *
 * Node IDs are user-provided mandatory strings. Edge IDs are deterministic
 * ("$src-$tag-$dst"). The graph layer maintains bidirectional NodeID↔Int mapping,
 * delegating to IStorage via auto-generated Int IDs. Exact triple lookups use
 * storage selection. Subclasses narrow enumerated edge visibility only through
 * [isEdgeVisible]; whole-adjacency overrides are not a supported way to hide edges.
 * BFS traversals use Int visited sets for identity-function hashCode.
 *
 * Every public operation runs inside [criticalSection] on [storage], serializing
 * compound (multi-call) sequences against concurrent callers sharing the same
 * storage instance. Sequence-returning queries materialize their result inside
 * that critical section before returning, trading lazy evaluation for a
 * consistent snapshot under concurrent mutation.
 *
 * @param N The type of nodes in the graph, must extend [AbcNode].
 * @param E The type of edges in the graph, must extend [AbcEdge].
 */
@Suppress("TooManyFunctions")
public abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> :
    IGraph<N, E>,
    GraphMergeLaws {
    public companion object {
        internal const val PROP_NODE_ID = "__nid__"
        internal const val PROP_OWNERS = "__owners__"

        // Graph-layer bookkeeping names, filtered from all user-facing node property APIs.
        internal val RESERVED_NODE_PROPS = setOf(PROP_NODE_ID, PROP_OWNERS)
    }

    public abstract val storage: IStorage

    public abstract val graphId: String

    // Created on first use: the abstract storage and graph ID are not initialized during this constructor.
    private val owners by lazy { NodeOwners(storage, graphId) }

    private val cache by lazy {
        GraphEntityCache<N, E>(storage, ::newNodeObj, ::newEdgeObj, owners::holds).also { created ->
            (storage as? IRevertibleStorage)?.addRevertListener { notice -> repairAfterRevert(notice) }
        }
    }

    override val nodeIDs: Set<NodeID>
        get() = storage.criticalSection { cache.nodeIds.toSet() }

    protected abstract fun newNodeObj(): N

    protected abstract fun newEdgeObj(): E

    /**
     * Whether [edge] belongs to this graph's enumerated view; the default accepts every edge.
     *
     * Override this declaration to narrow full/grouped adjacency, all-edge reads, restored-edge resolution
     * and simple-pair duplicate checks. Whole-adjacency overrides do not declare visibility.
     * This classification must be side-effect-free, read only fixed edge fields and never traverse adjacency.
     * Both endpoints are registered before it is called, inside the existing storage critical section.
     *
     * @param edge A bound edge of the shared storage.
     * @return True when this graph's enumerated view includes the edge.
     */
    protected open fun isEdgeVisible(edge: E): Boolean = true

    /**
     * Called once per revert that may have changed edges, inside the critical section, after the node registry
     * is reconciled. [ids] are the visible edges whose presence, properties or endpoint registrations changed.
     * A graph that derives state from edge properties repairs it here; the default does nothing.
     */
    protected open fun onRestoredEdges(ids: Set<Int>) {}

    /** The visible edge under [storageId] with both endpoints registered, or null. */
    protected fun edgeByStorageId(storageId: Int): E? {
        val (src, dst) = storage.getEdgeStructure(storageId)
        if (!cache.containsStorageId(src) || !cache.containsStorageId(dst)) return null
        return cache.edge(storageId).takeIf(::isEdgeVisible)
    }

    // Registry first, so edges whose endpoints this graph newly owns resolve; then the derived state.
    private fun repairAfterRevert(notice: RevertNotice) {
        val affected = notice.restored.edges + cache.incidentEdges(cache.onRevert(notice))
        if (affected.isNotEmpty()) onRestoredEdges(affected)
    }

    private fun findEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Int? {
        val srcEntry = cache.entryOf(src) ?: return null
        val dstEntry = cache.entryOf(dst) ?: return null
        return storage.findEdge(srcEntry.storageId, dstEntry.storageId, tag)
    }

    // region Node operations

    override fun addNode(withID: NodeID): N =
        storage.criticalSection {
            if (cache.containsNode(withID)) throw EntityAlreadyExistException(withID)
            val storageId = storage.addNode(mapOf(PROP_NODE_ID to withID.strVal, PROP_OWNERS to owners.initial()))
            cache.node(cache.register(withID, storageId))
        }

    override fun claimNode(from: NodeIdentity): N =
        storage.criticalSection {
            cache.entryOf(from.id)?.let { return@criticalSection cache.node(it) }
            owners.mark(from.storageId)
            cache.node(cache.register(from.id, from.storageId))
        }

    override fun getNode(whoseID: NodeID): N? =
        storage.criticalSection {
            val entry = cache.entryOf(whoseID) ?: return@criticalSection null
            cache.node(entry)
        }

    override fun readNode(from: NodeIdentity): N =
        storage.criticalSection {
            val id = from.id
            val row = from.storageId
            cache.entryOf(id)?.let { entry ->
                require(entry.storageId == row) { "Node $id is not registered at row $row" }
                return@criticalSection cache.node(entry)
            }
            require(storage.containsNode(row) && storage.getNodeProperty(row, PROP_NODE_ID)?.core == id) {
                "Node $id does not exist at row $row"
            }
            newNodeObj().also { it.bind(storage, row, id) }
        }

    override fun containNode(whoseID: NodeID): Boolean = storage.criticalSection { cache.containsNode(whoseID) }

    override fun resolveNodeId(storageId: Int): NodeID? = storage.criticalSection { cache.resolveNodeId(storageId) }

    override fun delNode(whoseID: NodeID): Unit =
        storage.criticalSection {
            val entry = cache.entryOf(whoseID) ?: return@criticalSection
            val allEdges =
                (storage.getIncomingEdges(entry.storageId) + storage.getOutgoingEdges(entry.storageId)).toList()
            for (edgeIntId in allEdges) {
                cache.evictEdge(edgeIntId)
                storage.deleteEdge(edgeIntId)
            }
            cache.removeNode(entry)
            storage.deleteNode(entry.storageId)
        }

    override fun getAllNodes(doSatisfy: (N) -> Boolean): Sequence<N> =
        storage.criticalSection {
            cache.entries
                .map { cache.node(it) }
                .filter(doSatisfy)
                .asSequence()
        }

    // endregion

    // region Edge operations

    override fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E =
        storage.criticalSection {
            val srcEntry = cache.entryOf(src) ?: throw EntityNotExistException(src)
            val dstEntry = cache.entryOf(dst) ?: throw EntityNotExistException(dst)
            if (findEdge(src, dst, tag) != null) throw EntityAlreadyExistException("$src-$tag-$dst")
            val storageId = storage.addEdge(srcEntry.storageId, dstEntry.storageId, tag, emptyMap())
            cache.edge(storageId)
        }

    override fun getEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E? =
        storage.criticalSection {
            if (!cache.containsNode(src)) return@criticalSection null
            if (!cache.containsNode(dst)) return@criticalSection null
            val storageId = findEdge(src, dst, tag) ?: return@criticalSection null
            cache.edge(storageId)
        }

    override fun containEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Boolean =
        storage.criticalSection {
            if (!cache.containsNode(src)) return@criticalSection false
            if (!cache.containsNode(dst)) return@criticalSection false
            findEdge(src, dst, tag) != null
        }

    override fun delEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Unit =
        storage.criticalSection {
            if (!cache.containsNode(src)) return@criticalSection
            if (!cache.containsNode(dst)) return@criticalSection
            val storageId = findEdge(src, dst, tag) ?: return@criticalSection
            cache.evictEdge(storageId)
            storage.deleteEdge(storageId)
        }

    override fun getAllEdges(doSatisfy: (E) -> Boolean): Sequence<E> =
        storage.criticalSection {
            storage.edgeIDs.mapNotNull(::edgeByStorageId).filter(doSatisfy).asSequence()
        }

    // endregion

    // region Graph structure queries

    override fun getOutgoingEdges(of: NodeID): Sequence<E> = storage.criticalSection {
        val entry = cache.entryOf(of) ?: return@criticalSection emptySequence()
        storage.getOutgoingEdges(entry.storageId).mapNotNull(::edgeByStorageId).asSequence()
    }

    override fun getIncomingEdges(of: NodeID): Sequence<E> = storage.criticalSection {
        val entry = cache.entryOf(of) ?: return@criticalSection emptySequence()
        storage.getIncomingEdges(entry.storageId).mapNotNull(::edgeByStorageId).asSequence()
    }

    override fun getOutgoingEdges(of: NodeID, group: String): Sequence<E> = storage.criticalSection {
        val entry = cache.entryOf(of) ?: return@criticalSection emptySequence()
        storage.getOutgoingEdges(entry.storageId, group).mapNotNull(::edgeByStorageId).asSequence()
    }

    override fun getIncomingEdges(of: NodeID, group: String): Sequence<E> = storage.criticalSection {
        val entry = cache.entryOf(of) ?: return@criticalSection emptySequence()
        storage.getIncomingEdges(entry.storageId, group).mapNotNull(::edgeByStorageId).asSequence()
    }

    override fun getOutgoingEdges(of: NodeID, groups: Set<String>): Sequence<E> = storage.criticalSection {
        val entry = cache.entryOf(of) ?: return@criticalSection emptySequence()
        storage.getOutgoingEdges(entry.storageId, groups).mapNotNull(::edgeByStorageId).asSequence()
    }

    override fun getIncomingEdges(of: NodeID, groups: Set<String>): Sequence<E> = storage.criticalSection {
        val entry = cache.entryOf(of) ?: return@criticalSection emptySequence()
        storage.getIncomingEdges(entry.storageId, groups).mapNotNull(::edgeByStorageId).asSequence()
    }

    override fun getParents(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> =
        storage.criticalSection {
            getIncomingEdges(of)
                .filter(edgeCond)
                .mapNotNull { edge -> cache.entryOf(edge.srcNid)?.let(cache::node) }
                .toList()
                .asSequence()
        }

    override fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> =
        storage.criticalSection {
            getOutgoingEdges(of)
                .filter(edgeCond)
                .mapNotNull { edge -> cache.entryOf(edge.dstNid)?.let(cache::node) }
                .toList()
                .asSequence()
        }

    override fun getAncestors(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> = storage.criticalSection { bfsTraversal(of, edgeCond, ::getIncomingEdges) { it.srcNid }.asSequence() }

    override fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> = storage.criticalSection { bfsTraversal(of, edgeCond, ::getOutgoingEdges) { it.dstNid }.asSequence() }

    // Walks the overridable typed edge queries (spec-graph.md BFS Traversal).
    private fun bfsTraversal(
        of: NodeID,
        edgeCond: (E) -> Boolean,
        adjacentEdges: (NodeID) -> Sequence<E>,
        neighborId: (E) -> NodeID,
    ): List<N> {
        val startEntry = cache.entryOf(of) ?: return emptyList()
        // Mark at enqueue: a node reachable through several edges (diamond, parallel
        // edges, cycle back to the start) is included at most once, and never the start.
        val visited = hashSetOf(startEntry.storageId)
        val queue = ArrayDeque<NodeID>().apply { add(of) }
        val result = mutableListOf<N>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val unseen =
                adjacentEdges(current)
                    .filter(edgeCond)
                    .mapNotNull { cache.entryOf(neighborId(it)) }
                    .filter { visited.add(it.storageId) }
            for (next in unseen) {
                result.add(cache.node(next))
                queue.add(next.nodeId)
            }
        }
        return result
    }

    // endregion

    /** Rebuilds the NodeID-to-storage-ID index from the rows whose [PROP_OWNERS] set holds [graphId]. */
    protected fun rebuild(): Unit = storage.criticalSection { cache.rebuild() }
}
