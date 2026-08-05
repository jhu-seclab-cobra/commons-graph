package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import java.lang.ref.SoftReference

/**
 * Entity cache and ID index for [AbcMultipleGraph].
 *
 * Owns the bidirectional NodeID-to-storage-Int mapping and the softly referenced
 * node/edge wrapper objects, recreating a wrapper on demand via the supplied
 * factories when its soft reference has been collected. [storage] is a supplier
 * because the owning graph exposes storage as an abstract property that is not
 * initialized when this cache is constructed.
 */
internal class GraphEntityCache<N : AbcNode, E : AbcEdge>(
    private val storage: () -> IStorage,
    private val newNode: () -> N,
    private val newEdge: () -> E,
) {
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

    val storageIds: Set<Int> get() = nodeByStorageId.keys

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
        node.bind(storage(), entry.storageId, entry.nodeId)
        entry.ref = SoftReference(node)
        return node
    }

    fun node(storageId: Int): N = node(nodeByStorageId.getValue(storageId))

    fun edge(storageId: Int): E {
        edgeCache[storageId]?.get()?.let { return it }
        val boundStorage = storage()
        val structure = boundStorage.getEdgeStructure(storageId)
        val srcEntry = nodeByStorageId.getValue(structure.src)
        val dstEntry = nodeByStorageId.getValue(structure.dst)
        val edge = newEdge()
        edge.bind(boundStorage, storageId, srcEntry.nodeId, dstEntry.nodeId, structure.tag)
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

    fun clear() {
        nodeEntries.clear()
        nodeByStorageId.clear()
        edgeCache.clear()
    }
}
