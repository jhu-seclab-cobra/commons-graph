package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.RevertListener
import java.lang.ref.SoftReference

/**
 * Entity cache and ID index for [AbcMultipleGraph].
 *
 * Owns the bidirectional NodeID-to-storage-Int mapping and the softly referenced
 * node/edge wrapper objects, recreating a wrapper on demand via the supplied
 * factories when its soft reference has been collected. As a [RevertListener] it
 * evicts the node entries and edge wrappers whose storage IDs a revert removed.
 */
internal class GraphEntityCache<N : AbcNode, E : AbcEdge>(
    private val storage: IStorage,
    private val newNode: () -> N,
    private val newEdge: () -> E,
) : RevertListener {
    class NodeEntry<N>(
        val nodeId: NodeID,
        val storageId: Int,
        var ref: SoftReference<N>?,
    )

    private val nodeEntries = HashMap<NodeID, NodeEntry<N>>()
    private val nodeByStorageId = HashMap<Int, NodeEntry<N>>()
    private val edgeCache = HashMap<Int, SoftReference<E>>()

    val nodeIds: Set<NodeID> get() = nodeEntries.keys

    val entries: Collection<NodeEntry<N>> get() = nodeEntries.values

    fun entryOf(nodeId: NodeID): NodeEntry<N>? = nodeEntries[nodeId]

    fun containsNode(nodeId: NodeID): Boolean = nodeId in nodeEntries

    fun containsStorageId(storageId: Int): Boolean = storageId in nodeByStorageId

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

    // A removed storage node need not belong to this graph: graphs and posets share storage.
    override fun onRevert(
        removedNodes: Set<Int>,
        removedEdges: Set<Int>,
    ) {
        for (storageId in removedNodes) {
            val entry = nodeByStorageId.remove(storageId) ?: continue
            nodeEntries.remove(entry.nodeId)
        }
        for (storageId in removedEdges) edgeCache.remove(storageId)
    }

    fun clear() {
        nodeEntries.clear()
        nodeByStorageId.clear()
        edgeCache.clear()
    }
}
