package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import java.io.Flushable

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
 * @param N The type of nodes in the graph, must extend [AbcNode].
 * @param E The type of edges in the graph, must extend [AbcEdge].
 */
@Suppress("TooManyFunctions")
public abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> :
    IGraph<N, E>,
    Flushable {
    public companion object {
        internal const val PROP_NODE_ID = "__nid__"
        internal const val PROP_OWNERS = "__owners__"

        // Graph-layer bookkeeping names, filtered from all user-facing node property APIs.
        internal val RESERVED_NODE_PROPS = setOf(PROP_NODE_ID, PROP_OWNERS)
    }

    public abstract val storage: IStorage

    public abstract val graphId: String

    private val cache = GraphEntityCache<N, E>({ storage }, ::newNodeObj, ::newEdgeObj)

    override val nodeIDs: Set<NodeID>
        get() = cache.nodeIds

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

    override fun addNode(withID: NodeID): N {
        if (cache.containsNode(withID)) throw EntityAlreadyExistException(withID)
        val storageId = storage.addNode(mapOf(PROP_NODE_ID to withID.strVal))
        return cache.node(cache.register(withID, storageId))
    }

    override fun claimNode(from: AbcNode): N {
        cache.entryOf(from.id)?.let { return cache.node(it) }
        return cache.node(cache.register(from.id, from.storageId))
    }

    override fun getNode(whoseID: NodeID): N? {
        val entry = cache.entryOf(whoseID) ?: return null
        return cache.node(entry)
    }

    override fun containNode(whoseID: NodeID): Boolean = cache.containsNode(whoseID)

    override fun delNode(whoseID: NodeID) {
        val entry = cache.entryOf(whoseID) ?: return
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
        cache.entries
            .asSequence()
            .map { cache.node(it) }
            .filter(doSatisfy)

    // endregion

    // region Edge operations

    override fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E {
        val srcEntry = cache.entryOf(src) ?: throw EntityNotExistException(src)
        val dstEntry = cache.entryOf(dst) ?: throw EntityNotExistException(dst)
        if (findEdge(src, dst, tag) != null) throw EntityAlreadyExistException("$src-$tag-$dst")
        val storageId = storage.addEdge(srcEntry.storageId, dstEntry.storageId, tag, emptyMap())
        return cache.edge(storageId)
    }

    override fun getEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E? {
        if (!cache.containsNode(src)) return null
        if (!cache.containsNode(dst)) return null
        val storageId = findEdge(src, dst, tag) ?: return null
        return cache.edge(storageId)
    }

    override fun containEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Boolean {
        if (!cache.containsNode(src)) return false
        if (!cache.containsNode(dst)) return false
        return findEdge(src, dst, tag) != null
    }

    override fun delEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ) {
        if (!cache.containsNode(src)) return
        if (!cache.containsNode(dst)) return
        val storageId = findEdge(src, dst, tag) ?: return
        cache.evictEdge(storageId)
        storage.deleteEdge(storageId)
    }

    override fun getAllEdges(doSatisfy: (E) -> Boolean): Sequence<E> =
        storage.edgeIDs
            .asSequence()
            .filter { edgeId ->
                val structure = storage.getEdgeStructure(edgeId)
                cache.containsStorageId(structure.src) && cache.containsStorageId(structure.dst)
            }.map { cache.edge(it) }
            .filter(doSatisfy)

    // endregion

    // region Graph structure queries

    override fun getOutgoingEdges(of: NodeID): Sequence<E> {
        val entry = cache.entryOf(of) ?: return emptySequence()
        return storage
            .getOutgoingEdges(entry.storageId)
            .asSequence()
            .filter { cache.containsStorageId(storage.getEdgeStructure(it).dst) }
            .map { cache.edge(it) }
    }

    override fun getIncomingEdges(of: NodeID): Sequence<E> {
        val entry = cache.entryOf(of) ?: return emptySequence()
        return storage
            .getIncomingEdges(entry.storageId)
            .asSequence()
            .filter { cache.containsStorageId(storage.getEdgeStructure(it).src) }
            .map { cache.edge(it) }
    }

    override fun getParents(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> =
        getIncomingEdges(of).filter(edgeCond).mapNotNull { edge ->
            val entry = cache.entryOf(edge.srcNid) ?: return@mapNotNull null
            cache.node(entry)
        }

    override fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> =
        getOutgoingEdges(of).filter(edgeCond).mapNotNull { edge ->
            val entry = cache.entryOf(edge.dstNid) ?: return@mapNotNull null
            cache.node(entry)
        }

    override fun getAncestors(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> = bfsTraversal(of, edgeCond, storage::getIncomingEdges) { it.src }

    override fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> = bfsTraversal(of, edgeCond, storage::getOutgoingEdges) { it.dst }

    private fun bfsTraversal(
        of: NodeID,
        edgeCond: (E) -> Boolean,
        adjacentEdges: (Int) -> Set<Int>,
        neighborId: (IStorage.EdgeStructure) -> Int,
    ) = sequence {
        val startEntry = cache.entryOf(of) ?: return@sequence
        // Mark at enqueue: a node reachable through several edges (diamond, parallel
        // edges, cycle back to the start) is yielded at most once, and never the start.
        val visited = hashSetOf(startEntry.storageId)
        val queue = ArrayDeque<Int>().apply { add(startEntry.storageId) }
        while (queue.isNotEmpty()) {
            val currentInt = queue.removeFirst()
            adjacentEdges(currentInt).forEach { edgeIntId ->
                val nextInt = neighborId(storage.getEdgeStructure(edgeIntId))
                if (!cache.containsStorageId(nextInt)) return@forEach
                val edge = cache.edge(edgeIntId)
                if (!edgeCond(edge)) return@forEach
                if (!visited.add(nextInt)) return@forEach
                yield(cache.node(nextInt))
                queue.add(nextInt)
            }
        }
    }

    // endregion

    /**
     * Rebuilds graph-layer caches from storage state.
     *
     * Restores the NodeID↔Int index from persisted properties. Uses [PROP_OWNERS]
     * to determine which nodes belong to this graph (written by [flush]). Falls
     * back to loading all nodes when [PROP_OWNERS] is absent (first run before
     * any flush).
     */
    protected fun rebuild() {
        cache.clear()
        for (storageId in storage.nodeIDs) {
            val nodeIdVal = storage.getNodeProperty(storageId, PROP_NODE_ID) as? StrVal
            val owners = storage.getNodeProperty(storageId, PROP_OWNERS) as? SetVal
            val ownedByThis = owners == null || owners.contains(StrVal(graphId))
            if (nodeIdVal == null || !ownedByThis) continue
            cache.register(nodeIdVal.core, storageId)
        }
    }

    /**
     * Writes this graph's ownership mark ([PROP_OWNERS]) onto every cached node.
     *
     * The marks are the sole input [rebuild] uses to reattach nodes to this graph,
     * so flush is all-or-nothing: a cached node missing from storage means the
     * graph cache and storage have diverged, and flush fails fast instead of
     * persisting an incomplete ownership set.
     *
     * @throws EntityNotExistException if a cached node no longer exists in storage.
     */
    override fun flush() {
        for (storageId in cache.storageIds) {
            val existing = storage.getNodeProperty(storageId, PROP_OWNERS) as? SetVal ?: SetVal()
            if (!existing.contains(StrVal(graphId))) {
                val updated = SetVal(existing.core + StrVal(graphId))
                storage.setNodeProperties(storageId, mapOf(PROP_OWNERS to updated))
            }
        }
    }
}
