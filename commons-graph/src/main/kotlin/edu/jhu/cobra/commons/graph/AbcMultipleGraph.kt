package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import java.io.Closeable
import java.lang.ref.SoftReference

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
abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> :
    IGraph<N, E>,
    Closeable {
    companion object {
        internal const val PROP_NODE_ID = "__nid__"
    }

    abstract val storage: IStorage

    private class NodeEntry<N>(
        val nodeId: NodeID,
        val storageId: Int,
        var ref: SoftReference<N>?,
    )

    private val nodeEntries = HashMap<NodeID, NodeEntry<N>>()
    private val nodeByStorageId = HashMap<Int, NodeEntry<N>>()

    private val edgeCache = HashMap<Int, SoftReference<E>>()

    override val nodeIDs: Set<NodeID>
        get() = nodeEntries.keys

    protected abstract fun newNodeObj(): N

    protected abstract fun newEdgeObj(): E

    private fun cachedNode(entry: NodeEntry<N>): N {
        entry.ref?.get()?.let { return it }
        val node = newNodeObj()
        node.bind(storage, entry.storageId, entry.nodeId)
        entry.ref = SoftReference(node)
        return node
    }

    private fun cachedNode(storageId: Int): N {
        val entry = nodeByStorageId[storageId]!!
        return cachedNode(entry)
    }

    private fun cachedEdge(storageId: Int): E {
        edgeCache[storageId]?.get()?.let { return it }
        val structure = storage.getEdgeStructure(storageId)
        val srcEntry = nodeByStorageId[structure.src]!!
        val dstEntry = nodeByStorageId[structure.dst]!!
        val edge = newEdgeObj()
        edge.bind(storage, storageId, srcEntry.nodeId, dstEntry.nodeId, structure.tag)
        edgeCache[storageId] = SoftReference(edge)
        return edge
    }

    private fun findEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Int? {
        val srcEntry = nodeEntries[src] ?: return null
        val dstEntry = nodeEntries[dst] ?: return null
        return storage.getOutgoingEdges(srcEntry.storageId).firstOrNull { edgeId ->
            val s = storage.getEdgeStructure(edgeId)
            s.dst == dstEntry.storageId && s.tag == tag
        }
    }

    // region Node operations

    override fun addNode(withID: NodeID): N {
        if (withID in nodeEntries) throw EntityAlreadyExistException(withID)
        val storageId = storage.addNode(mapOf(PROP_NODE_ID to withID.strVal))
        val entry = NodeEntry<N>(withID, storageId, null)
        nodeEntries[withID] = entry
        nodeByStorageId[storageId] = entry
        return cachedNode(entry)
    }

    override fun getNode(whoseID: NodeID): N? {
        val entry = nodeEntries[whoseID] ?: return null
        return cachedNode(entry)
    }

    override fun containNode(whoseID: NodeID): Boolean = whoseID in nodeEntries

    override fun delNode(whoseID: NodeID) {
        val entry = nodeEntries[whoseID] ?: return
        val allEdges =
            (storage.getIncomingEdges(entry.storageId) + storage.getOutgoingEdges(entry.storageId)).toList()
        for (edgeIntId in allEdges) {
            edgeCache.remove(edgeIntId)
            storage.deleteEdge(edgeIntId)
        }
        nodeEntries.remove(whoseID)
        nodeByStorageId.remove(entry.storageId)
        storage.deleteNode(entry.storageId)
    }

    override fun getAllNodes(doSatfy: (N) -> Boolean): Sequence<N> =
        nodeEntries.values
            .asSequence()
            .map { cachedNode(it) }
            .filter(doSatfy)

    // endregion

    // region Edge operations

    override fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E {
        val srcEntry = nodeEntries[src] ?: throw EntityNotExistException(src)
        val dstEntry = nodeEntries[dst] ?: throw EntityNotExistException(dst)
        if (findEdge(src, dst, tag) != null) throw EntityAlreadyExistException("$src-$tag-$dst")
        val storageId = storage.addEdge(srcEntry.storageId, dstEntry.storageId, tag, emptyMap())
        return cachedEdge(storageId)
    }

    override fun getEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E? {
        if (src !in nodeEntries) return null
        if (dst !in nodeEntries) return null
        val storageId = findEdge(src, dst, tag) ?: return null
        return cachedEdge(storageId)
    }

    override fun containEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Boolean {
        if (src !in nodeEntries) return false
        if (dst !in nodeEntries) return false
        return findEdge(src, dst, tag) != null
    }

    override fun delEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ) {
        if (src !in nodeEntries) return
        if (dst !in nodeEntries) return
        val storageId = findEdge(src, dst, tag) ?: return
        edgeCache.remove(storageId)
        storage.deleteEdge(storageId)
    }

    override fun getAllEdges(doSatfy: (E) -> Boolean): Sequence<E> =
        storage.edgeIDs
            .asSequence()
            .map { cachedEdge(it) }
            .filter(doSatfy)

    // endregion

    // region Graph structure queries

    override fun getOutgoingEdges(of: NodeID): Sequence<E> {
        val entry = nodeEntries[of] ?: return emptySequence()
        return storage.getOutgoingEdges(entry.storageId).asSequence().map { cachedEdge(it) }
    }

    override fun getIncomingEdges(of: NodeID): Sequence<E> {
        val entry = nodeEntries[of] ?: return emptySequence()
        return storage.getIncomingEdges(entry.storageId).asSequence().map { cachedEdge(it) }
    }

    override fun getParents(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> =
        getIncomingEdges(of).filter(edgeCond).mapNotNull { edge ->
            val entry = nodeEntries[edge.srcNid] ?: return@mapNotNull null
            cachedNode(entry)
        }

    override fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> =
        getOutgoingEdges(of).filter(edgeCond).mapNotNull { edge ->
            val entry = nodeEntries[edge.dstNid] ?: return@mapNotNull null
            cachedNode(entry)
        }

    override fun getAncestors(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ) = sequence {
        val startEntry = nodeEntries[of] ?: return@sequence
        val visited = hashSetOf<Int>()
        val queue = ArrayDeque<Int>().apply { add(startEntry.storageId) }
        while (queue.isNotEmpty()) {
            val currentInt = queue.removeFirst()
            if (!visited.add(currentInt)) continue
            storage.getIncomingEdges(currentInt).forEach { edgeIntId ->
                val parentInt = storage.getEdgeStructure(edgeIntId).src
                val edge = cachedEdge(edgeIntId)
                if (!edgeCond(edge)) return@forEach
                yield(cachedNode(parentInt))
                queue.add(parentInt)
            }
        }
    }

    override fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ) = sequence {
        val startEntry = nodeEntries[of] ?: return@sequence
        val visited = hashSetOf<Int>()
        val queue = ArrayDeque<Int>().apply { add(startEntry.storageId) }
        while (queue.isNotEmpty()) {
            val currentInt = queue.removeFirst()
            if (!visited.add(currentInt)) continue
            storage.getOutgoingEdges(currentInt).forEach { edgeIntId ->
                val childInt = storage.getEdgeStructure(edgeIntId).dst
                val edge = cachedEdge(edgeIntId)
                if (!edgeCond(edge)) return@forEach
                yield(cachedNode(childInt))
                queue.add(childInt)
            }
        }
    }

    // endregion

    /**
     * Rebuilds graph-layer caches from storage state.
     *
     * Restores [nodeEntries] and [nodeByStorageId] by reading persisted
     * [PROP_NODE_ID] node properties. Call after deserializing or re-opening
     * a storage that was previously populated.
     */
    protected fun rebuild() {
        nodeEntries.clear()
        nodeByStorageId.clear()
        edgeCache.clear()
        for (storageId in storage.nodeIDs) {
            val nodeIdVal = storage.getNodeProperty(storageId, PROP_NODE_ID) as? StrVal ?: continue
            val nodeId: NodeID = nodeIdVal.core
            val entry = NodeEntry<N>(nodeId, storageId, null)
            nodeEntries[nodeId] = entry
            nodeByStorageId[storageId] = entry
        }
    }

    override fun close() {
        nodeEntries.clear()
        nodeByStorageId.clear()
        edgeCache.clear()
    }
}
