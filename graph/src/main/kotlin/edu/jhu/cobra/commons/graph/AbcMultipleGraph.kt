package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.poset.IPoset
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import java.io.Closeable
import java.lang.ref.SoftReference

/**
 * Abstract directed multi-graph allowing multiple edges between the same pair of
 * nodes, with integrated label-based edge visibility.
 *
 * Node IDs are user-provided mandatory strings. Edge IDs are deterministic
 * ("$src-$tag-$dst"). Uses dual storage: [storage] for graph data (nodes/edges),
 * [posetStorage] for label hierarchy (labels as nodes with parent/changes properties).
 *
 * The graph layer maintains bidirectional NodeID↔Int mapping and edge index,
 * delegating to IStorage via auto-generated Int IDs for optimal performance.
 * BFS traversals use Int visited sets for identity-function hashCode.
 *
 * @param N The type of nodes in the graph, must extend [AbcNode].
 * @param E The type of edges in the graph, must extend [AbcEdge].
 */
@Suppress("TooManyFunctions")
abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> :
    IGraph<N, E>,
    IPoset,
    Closeable {
    companion object {
        internal const val PROP_NODE_ID = "__nid__"
    }

    abstract val storage: IStorage
    abstract val posetStorage: IStorage

    // P0: Merged node entry — NodeID↔Int mapping + SoftReference cache in one object
    private class NodeEntry<N>(
        val nodeId: NodeID,
        val storageId: Int,
        var ref: SoftReference<N>?,
    )

    private val nodeEntries = HashMap<NodeID, NodeEntry<N>>()
    private val nodeByStorageId = HashMap<Int, NodeEntry<N>>()

    // P1: Nested edge index — src → tag → dst → storageId (zero String allocation for dedup)
    private val edgeIndex = HashMap<NodeID, HashMap<String, HashMap<NodeID, Int>>>()
    private val edgeCache = HashMap<Int, SoftReference<E>>()

    // Label ID cache: Label.core ↔ posetStorage Int label node ID
    private val labelIdCache = HashMap<String, Int>()
    private var labelIdCacheReady = false

    // Poset reverse mapping: posetStorage Int → label core string
    private val posetIntToLabel = HashMap<Int, String>()

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
    ): Int? = edgeIndex[src]?.get(tag)?.get(dst)

    private fun putEdgeIndex(
        src: NodeID,
        dst: NodeID,
        tag: String,
        storageId: Int,
    ) {
        edgeIndex
            .getOrPut(src) { HashMap() }
            .getOrPut(tag) { HashMap() }[dst] = storageId
    }

    private fun removeEdgeIndex(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ) {
        val tagMap = edgeIndex[src] ?: return
        val dstMap = tagMap[tag] ?: return
        dstMap.remove(dst)
        if (dstMap.isEmpty()) tagMap.remove(tag)
        if (tagMap.isEmpty()) edgeIndex.remove(src)
    }

    // region IPoset — write-through to posetStorage (labels as nodes)

    private val queryCache = mutableMapOf<Pair<Label, Label>, Int?>()

    private fun resolveLabelStorageId(label: Label): Int? {
        ensureLabelIdCache()
        return labelIdCache[label.core]
    }

    private fun ensureLabelIdCache() {
        if (labelIdCacheReady) return
        for (nodeId in posetStorage.nodeIDs) {
            val labelCore = posetStorage.getNodeProperty(nodeId, "label") as? StrVal
            if (labelCore != null) {
                labelIdCache[labelCore.core] = nodeId
                posetIntToLabel[nodeId] = labelCore.core
            }
        }
        labelIdCacheReady = true
    }

    private fun ensureLabelNode(label: Label): Int {
        resolveLabelStorageId(label)?.let { return it }
        val storageId = posetStorage.addNode(mapOf("label" to label.core.strVal))
        labelIdCache[label.core] = storageId
        posetIntToLabel[storageId] = label.core
        return storageId
    }

    override val allLabels: Set<Label>
        get() {
            ensureLabelIdCache()
            val labels = labelIdCache.keys.mapTo(LinkedHashSet()) { Label(it) }
            return labels + Label.INFIMUM + Label.SUPREMUM
        }

    override var Label.parents: Map<String, Label>
        get() {
            val storageId = resolveLabelStorageId(this) ?: return emptyMap()
            val result = LinkedHashMap<String, Label>()
            for (edgeId in posetStorage.getOutgoingEdges(storageId)) {
                val (_, parentInt, name) = posetStorage.getEdgeStructure(edgeId)
                val parentLabelCore = posetIntToLabel[parentInt] ?: continue
                result[name] = Label(parentLabelCore)
            }
            return result
        }
        set(value) {
            val storageId = ensureLabelNode(this)
            for (edgeId in posetStorage.getOutgoingEdges(storageId).toList()) {
                posetStorage.deleteEdge(edgeId)
            }
            for ((name, parentLabel) in value) {
                val parentInt = ensureLabelNode(parentLabel)
                posetStorage.addEdge(storageId, parentInt, name, emptyMap())
            }
            queryCache.clear()
        }

    override val Label.ancestors: Sequence<Label>
        get() =
            sequence {
                val startId = resolveLabelStorageId(this@ancestors) ?: return@sequence
                val visited = hashSetOf<Int>()
                val stack = ArrayDeque<Int>().also { it.add(startId) }
                while (stack.isNotEmpty()) {
                    val currentId = stack.removeFirst()
                    if (!visited.add(currentId)) continue
                    for (edgeId in posetStorage.getOutgoingEdges(currentId)) {
                        val parentInt = posetStorage.getEdgeStructure(edgeId).dst
                        val parentLabelCore = posetIntToLabel[parentInt] ?: continue
                        yield(Label(parentLabelCore))
                        stack.add(parentInt)
                    }
                }
            }

    override fun Label.compareTo(other: Label): Int? {
        if (this == other) return 0
        if (this == Label.SUPREMUM || other == Label.INFIMUM) return 1
        if (other == Label.SUPREMUM || this == Label.INFIMUM) return -1
        if (this to other in queryCache) return queryCache[this to other]
        if (other to this in queryCache) return queryCache[other to this]?.let { -it }
        other.ancestors.forEach { label ->
            if (label != this) return@forEach
            queryCache[this to other] = 1
            return 1
        }
        this.ancestors.forEach { label ->
            if (label != other) return@forEach
            queryCache[this to other] = -1
            return -1
        }
        return null
    }

    // endregion

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
            val structure = storage.getEdgeStructure(edgeIntId)
            val srcId = nodeByStorageId[structure.src]!!.nodeId
            val dstId = nodeByStorageId[structure.dst]!!.nodeId
            removeEdgeIndex(srcId, dstId, structure.tag)
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
        putEdgeIndex(src, dst, tag, storageId)
        return cachedEdge(storageId)
    }

    open fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
        label: Label,
    ): E {
        val srcEntry = nodeEntries[src] ?: throw EntityNotExistException(src)
        val dstEntry = nodeEntries[dst] ?: throw EntityNotExistException(dst)
        val existing = findEdge(src, dst, tag)
        val edge: E
        if (existing != null) {
            edge = cachedEdge(existing)
        } else {
            val storageId = storage.addEdge(srcEntry.storageId, dstEntry.storageId, tag, emptyMap())
            putEdgeIndex(src, dst, tag, storageId)
            edge = cachedEdge(storageId)
        }
        edge.labels = edge.labels + label
        return edge
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
        removeEdgeIndex(src, dst, tag)
        edgeCache.remove(storageId)
        storage.deleteEdge(storageId)
    }

    fun delEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
        label: Label,
    ) {
        if (src !in nodeEntries) return
        if (dst !in nodeEntries) return
        val storageId = findEdge(src, dst, tag) ?: return
        val edge = cachedEdge(storageId)
        val oldLabels = edge.labels
        val newLabels = oldLabels - label
        edge.labels = newLabels
        if (newLabels.isNotEmpty()) return
        removeEdgeIndex(src, dst, tag)
        edgeCache.remove(storageId)
        storage.deleteEdge(storageId)
    }

    override fun getAllEdges(doSatfy: (E) -> Boolean): Sequence<E> =
        edgeIndex.values
            .asSequence()
            .flatMap { tagMap -> tagMap.values.asSequence().flatMap { it.values.asSequence() } }
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

    fun getOutgoingEdges(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<E> = getOutgoingEdges(of).filter(cond).filterVisitable(label)

    fun getIncomingEdges(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<E> = getIncomingEdges(of).filter(cond).filterVisitable(label)

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

    fun getChildren(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N> =
        getOutgoingEdges(of, label, cond).mapNotNull { edge ->
            val entry = nodeEntries[edge.dstNid] ?: return@mapNotNull null
            cachedNode(entry)
        }

    fun getParents(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N> =
        getIncomingEdges(of, label, cond).mapNotNull { edge ->
            val entry = nodeEntries[edge.srcNid] ?: return@mapNotNull null
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

    fun getDescendants(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N> =
        sequence {
            val visited = mutableSetOf<NodeID>()
            val queue = ArrayDeque<NodeID>().apply { add(of) }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                getChildren(current, label, cond).forEach { child ->
                    if (visited.add(child.id)) {
                        yield(child)
                        queue.add(child.id)
                    }
                }
            }
        }

    fun getAncestors(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N> =
        sequence {
            val visited = mutableSetOf<NodeID>()
            val queue = ArrayDeque<NodeID>().apply { add(of) }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                getParents(current, label, cond).forEach { parent ->
                    if (visited.add(parent.id)) {
                        yield(parent)
                        queue.add(parent.id)
                    }
                }
            }
        }

    // endregion

    /**
     * Filters edges by label visibility: visitable if at least one label is
     * equal to or below `by` in the hierarchy, excluding labels covered by
     * a higher visitable label.
     */
    private fun Sequence<E>.filterVisitable(by: Label): Sequence<E> {
        if (by == Label.SUPREMUM) return this
        val edgesWithLabels = this.map { e -> e to e.labels }.toList()
        val allVisitable =
            edgesWithLabels
                .flatMap { (_, labels) ->
                    labels.filter { l ->
                        by == l || by.compareTo(l)?.let { it > 0 } ?: false
                    }
                }.toSet()
        val allNotCovered =
            allVisitable
                .filter { cur ->
                    !allVisitable.any { other ->
                        other != cur && other.compareTo(cur)?.let { it > 0 } ?: false
                    }
                }.toSet()
        return edgesWithLabels
            .filter { (_, labels) ->
                labels.any { it in allNotCovered }
            }.map { it.first }
            .asSequence()
    }

    /**
     * Rebuilds graph-layer caches from storage state.
     *
     * Restores [nodeEntries], [nodeByStorageId], and [edgeIndex] by reading
     * persisted [PROP_NODE_ID] node properties and edge structures. Call after
     * deserializing or re-opening a storage that was previously populated.
     */
    protected fun rebuild() {
        nodeEntries.clear()
        nodeByStorageId.clear()
        edgeIndex.clear()
        edgeCache.clear()
        labelIdCache.clear()
        posetIntToLabel.clear()
        labelIdCacheReady = false
        queryCache.clear()
        for (storageId in storage.nodeIDs) {
            val nodeIdVal = storage.getNodeProperty(storageId, PROP_NODE_ID) as? StrVal ?: continue
            val nodeId: NodeID = nodeIdVal.core
            val entry = NodeEntry<N>(nodeId, storageId, null)
            nodeEntries[nodeId] = entry
            nodeByStorageId[storageId] = entry
        }
        for (edgeStorageId in storage.edgeIDs) {
            val structure = storage.getEdgeStructure(edgeStorageId)
            val srcEntry = nodeByStorageId[structure.src] ?: continue
            val dstEntry = nodeByStorageId[structure.dst] ?: continue
            putEdgeIndex(srcEntry.nodeId, dstEntry.nodeId, structure.tag, edgeStorageId)
        }
    }

    override fun close() {
        labelIdCache.clear()
        posetIntToLabel.clear()
        labelIdCacheReady = false
        queryCache.clear()
        nodeEntries.clear()
        nodeByStorageId.clear()
        edgeIndex.clear()
        edgeCache.clear()
    }
}
