package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IRevertibleStorage
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.criticalSection
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal

/**
 * Abstract directed multi-graph allowing multiple edges between the same pair of
 * nodes in a given direction.
 *
 * Node IDs are user-provided mandatory strings. Edge IDs are deterministic
 * ("$src-$tag-$dst"). The graph layer maintains bidirectional NodeID↔Int mapping,
 * delegating to IStorage via auto-generated Int IDs. Edge lookups by (src, dst, tag)
 * scan the source node's adjacency list in storage — O(out-degree) per query.
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
public abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> : IGraph<N, E> {
    public companion object {
        internal const val PROP_NODE_ID = "__nid__"
        internal const val PROP_OWNERS = "__owners__"

        // Graph-layer bookkeeping names, filtered from all user-facing node property APIs.
        internal val RESERVED_NODE_PROPS = setOf(PROP_NODE_ID, PROP_OWNERS)
    }

    public abstract val storage: IStorage

    public abstract val graphId: String

    // Created on first use: the abstract storage is not initialized during this constructor.
    private val cache by lazy {
        GraphEntityCache<N, E>(storage, ::newNodeObj, ::newEdgeObj).also { created ->
            (storage as? IRevertibleStorage)?.addRevertListener(created)
        }
    }

    override val nodeIDs: Set<NodeID>
        get() = storage.criticalSection { cache.nodeIds.toSet() }

    protected abstract fun newNodeObj(): N

    protected abstract fun newEdgeObj(): E

    private fun findEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Int? {
        val srcEntry = cache.entryOf(src) ?: return null
        val dstEntry = cache.entryOf(dst) ?: return null
        return storage.getOutgoingEdges(srcEntry.storageId).firstOrNull { edgeId ->
            val structure = storage.getEdgeStructure(edgeId)
            structure.dst == dstEntry.storageId && structure.tag == tag
        }
    }

    // region Node operations

    override fun addNode(withID: NodeID): N =
        storage.criticalSection {
            if (cache.containsNode(withID)) throw EntityAlreadyExistException(withID)
            val storageId = storage.addNode(mapOf(PROP_NODE_ID to withID.strVal, PROP_OWNERS to ownerMark()))
            cache.node(cache.register(withID, storageId))
        }

    override fun claimNode(from: AbcNode): N =
        storage.criticalSection {
            cache.entryOf(from.id)?.let { return@criticalSection cache.node(it) }
            markOwner(from.storageId)
            cache.node(cache.register(from.id, from.storageId))
        }

    override fun getNode(whoseID: NodeID): N? =
        storage.criticalSection {
            val entry = cache.entryOf(whoseID) ?: return@criticalSection null
            cache.node(entry)
        }

    override fun containNode(whoseID: NodeID): Boolean = storage.criticalSection { cache.containsNode(whoseID) }

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
            storage.edgeIDs
                .filter { edgeId ->
                    val structure = storage.getEdgeStructure(edgeId)
                    cache.containsStorageId(structure.src) && cache.containsStorageId(structure.dst)
                }.map { cache.edge(it) }
                .filter(doSatisfy)
                .asSequence()
        }

    // endregion

    // region Graph structure queries

    override fun getOutgoingEdges(of: NodeID): Sequence<E> =
        storage.criticalSection {
            val entry = cache.entryOf(of)
            if (entry == null) {
                emptySequence()
            } else {
                storage
                    .getOutgoingEdges(entry.storageId)
                    .filter { cache.containsStorageId(storage.getEdgeStructure(it).dst) }
                    .map { cache.edge(it) }
                    .asSequence()
            }
        }

    override fun getIncomingEdges(of: NodeID): Sequence<E> =
        storage.criticalSection {
            val entry = cache.entryOf(of)
            if (entry == null) {
                emptySequence()
            } else {
                storage
                    .getIncomingEdges(entry.storageId)
                    .filter { cache.containsStorageId(storage.getEdgeStructure(it).src) }
                    .map { cache.edge(it) }
                    .asSequence()
            }
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

    private fun ownerMark(): SetVal = SetVal(setOf(StrVal(graphId)))

    /** Adds this graph to the owner set of the row [storageId]; writes only when the graph is missing from the set. */
    private fun markOwner(storageId: Int) {
        val existing = storage.getNodeProperty(storageId, PROP_OWNERS) as? SetVal ?: SetVal()
        if (existing.contains(StrVal(graphId))) return
        storage.setNodeProperties(storageId, mapOf(PROP_OWNERS to SetVal(existing.core + StrVal(graphId))))
    }

    /**
     * Rebuilds graph-layer caches from storage state.
     *
     * Restores the NodeID↔Int index from persisted properties. A node belongs to
     * this graph when its [PROP_OWNERS] set holds [graphId]; [addNode] and [claimNode]
     * write that mark when the graph takes the node. A node with no mark belongs
     * to no graph.
     */
    protected fun rebuild(): Unit =
        storage.criticalSection {
            cache.clear()
            for (storageId in storage.nodeIDs) {
                val nodeIdVal = storage.getNodeProperty(storageId, PROP_NODE_ID) as? StrVal
                val owners = storage.getNodeProperty(storageId, PROP_OWNERS) as? SetVal
                if (nodeIdVal == null || owners?.contains(StrVal(graphId)) != true) continue
                cache.register(nodeIdVal.core, storageId)
            }
        }
}
