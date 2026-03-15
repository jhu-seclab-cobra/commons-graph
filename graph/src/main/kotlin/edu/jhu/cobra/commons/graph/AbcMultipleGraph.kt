package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcNode.Companion.META_ID
import edu.jhu.cobra.commons.graph.poset.IPoset
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.listVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import java.io.Closeable
import java.lang.ref.SoftReference

/**
 * Abstract directed multi-graph allowing multiple edges between the same pair of
 * nodes, with integrated label-based edge visibility.
 *
 * Node IDs are user-provided mandatory strings stored as `__id__` meta properties.
 * Edge structural info (src, dst, type) is stored as `__src__`, `__dst__`, `__tag__`
 * meta properties. Storage-internal IDs are opaque and invisible to external code.
 *
 * Uses dual storage: [storage] for graph data (nodes/edges), [posetStorage] for
 * label hierarchy (labels as nodes with parent/changes properties).
 *
 * @param N The type of nodes in the graph, must extend [AbcNode].
 * @param E The type of edges in the graph, must extend [AbcEdge].
 */
@Suppress("TooManyFunctions")
abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> :
    IGraph<N, E>,
    IPoset,
    Closeable {
    abstract val storage: IStorage
    abstract val posetStorage: IStorage

    // Bidirectional node cache: NodeID ↔ storage-internal ID, populated eagerly on first access
    private val nodeIdCache = HashMap<NodeID, InternalID>()
    private val nodeSidCache = HashMap<InternalID, NodeID>()
    private var nodeIdCacheReady = false

    // Bidirectional label cache: Label.core ↔ posetStorage-internal ID
    private val labelIdCache = HashMap<String, InternalID>()
    private val labelSidCache = HashMap<InternalID, Label>()
    private var labelIdCacheReady = false

    // Edge index: (src, dst, type) → edgeId for O(1) findEdge
    private val edgeIndex = HashMap<Triple<InternalID, InternalID, String>, InternalID>()
    private var edgeIndexReady = false

    // SoftReference caches: allow GC to reclaim unused wrapper objects
    private val nodeCache = HashMap<InternalID, SoftReference<N>>()
    private val edgeCache = HashMap<InternalID, SoftReference<E>>()

    private fun ensureNodeIdCache() {
        if (nodeIdCacheReady) return
        for (sid in storage.nodeIDs) {
            val nid = (storage.getNodeProperty(sid, META_ID) as? StrVal)?.core ?: continue
            nodeIdCache[nid] = sid
            nodeSidCache[sid] = nid
        }
        nodeIdCacheReady = true
    }

    private fun ensureLabelIdCache() {
        if (labelIdCacheReady) return
        for (sid in posetStorage.nodeIDs) {
            val lid = (posetStorage.getNodeProperty(sid, META_ID) as? StrVal)?.core ?: continue
            labelIdCache[lid] = sid
            labelSidCache[sid] = Label(lid)
        }
        labelIdCacheReady = true
    }

    override val nodeIDs: Set<NodeID>
        get() {
            ensureNodeIdCache()
            return nodeIdCache.keys.toSet()
        }

    protected abstract fun newNodeObj(internalId: InternalID): N

    protected abstract fun newEdgeObj(
        internalId: InternalID,
        nodeIdResolver: (InternalID) -> NodeID,
    ): E

    private fun resolveStorageId(nodeId: NodeID): InternalID? {
        ensureNodeIdCache()
        return nodeIdCache[nodeId]
    }

    // InternalID → NodeID reverse lookup
    private fun resolveNodeId(internalId: InternalID): NodeID {
        ensureNodeIdCache()
        return nodeSidCache[internalId]
            ?: throw EntityNotExistException("InternalID $internalId has no mapped NodeID")
    }

    private fun cachedNode(internalId: InternalID): N {
        nodeCache[internalId]?.get()?.let { return it }
        val node = newNodeObj(internalId)
        nodeCache[internalId] = SoftReference(node)
        return node
    }

    private fun cachedEdge(eid: InternalID): E {
        edgeCache[eid]?.get()?.let { return it }
        val edge = newEdgeObj(eid, ::resolveNodeId)
        edgeCache[eid] = SoftReference(edge)
        return edge
    }

    private fun ensureEdgeIndex() {
        if (edgeIndexReady) return
        for (eid in storage.edgeIDs) {
            val key = Triple(storage.getEdgeSrc(eid), storage.getEdgeDst(eid), storage.getEdgeType(eid))
            edgeIndex[key] = eid
        }
        edgeIndexReady = true
    }

    // O(1) edge lookup via index
    private fun findEdge(
        srcSid: InternalID,
        dstSid: InternalID,
        type: String,
    ): InternalID? {
        ensureEdgeIndex()
        return edgeIndex[Triple(srcSid, dstSid, type)]
    }

    // region IPoset — write-through to posetStorage (labels as nodes)

    private val queryCache = mutableMapOf<Pair<Label, Label>, Int?>()

    // Resolves Label.core → posetStorage internal ID
    private fun resolveLabelStorageId(label: Label): InternalID? {
        ensureLabelIdCache()
        return labelIdCache[label.core]
    }

    // Creates label node if not exists, returns its posetStorage internal ID
    private fun ensureLabelNode(label: Label): InternalID {
        resolveLabelStorageId(label)?.let { return it }
        val sid = posetStorage.addNode(mapOf(META_ID to label.core.strVal))
        labelIdCache[label.core] = sid
        labelSidCache[sid] = label
        return sid
    }

    override val allLabels: Set<Label>
        get() {
            ensureLabelIdCache()
            val labels = labelIdCache.keys.mapTo(LinkedHashSet()) { Label(it) }
            return labels + Label.INFIMUM + Label.SUPREMUM
        }

    override var Label.parents: Map<String, Label>
        get() {
            val sid = resolveLabelStorageId(this) ?: return emptyMap()
            val result = LinkedHashMap<String, Label>()
            for (eid in posetStorage.getOutgoingEdges(sid)) {
                val parentSid = posetStorage.getEdgeDst(eid)
                val name = posetStorage.getEdgeType(eid)
                val parentLabel = labelSidCache[parentSid] ?: continue
                result[name] = parentLabel
            }
            return result
        }
        set(value) {
            val sid = ensureLabelNode(this)
            // Remove all existing parent edges
            for (eid in posetStorage.getOutgoingEdges(sid).toList()) {
                posetStorage.deleteEdge(eid)
            }
            // Create new parent edges: this → parent, type = name
            for ((name, parentLabel) in value) {
                val parentSid = ensureLabelNode(parentLabel)
                posetStorage.addEdge(sid, parentSid, name)
            }
            queryCache.clear()
        }

    override val Label.ancestors: Sequence<Label>
        get() =
            sequence {
                val startSid = resolveLabelStorageId(this@ancestors) ?: return@sequence
                val visited = hashSetOf<InternalID>()
                val stack = ArrayDeque<InternalID>().also { it.add(startSid) }
                while (stack.isNotEmpty()) {
                    val currentSid = stack.removeFirst()
                    if (!visited.add(currentSid)) continue
                    for (eid in posetStorage.getOutgoingEdges(currentSid)) {
                        val parentSid = posetStorage.getEdgeDst(eid)
                        val parentLabel = labelSidCache[parentSid] ?: continue
                        yield(parentLabel)
                        stack.add(parentSid)
                    }
                }
            }

    override var Label.changes: Set<InternalID>
        get() {
            val sid = resolveLabelStorageId(this) ?: return emptySet()
            val raw = posetStorage.getNodeProperty(sid, PROP_CHANGES) as? ListVal ?: return emptySet()
            return raw.map { (it as NumVal).core.toInt() }.toSet()
        }
        set(value) {
            val sid = ensureLabelNode(this)
            if (value.isEmpty()) {
                posetStorage.setNodeProperties(sid, mapOf(PROP_CHANGES to null))
            } else {
                posetStorage.setNodeProperties(sid, mapOf(PROP_CHANGES to value.map { it.numVal }.listVal))
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
        if (resolveStorageId(withID) != null) throw EntityAlreadyExistException(withID)
        val internalId = storage.addNode(mapOf(META_ID to withID.strVal))
        nodeIdCache[withID] = internalId
        nodeSidCache[internalId] = withID
        return cachedNode(internalId)
    }

    override fun getNode(whoseID: NodeID): N? {
        val internalId = resolveStorageId(whoseID) ?: return null
        return cachedNode(internalId)
    }

    override fun containNode(whoseID: NodeID): Boolean = resolveStorageId(whoseID) != null

    override fun delNode(whoseID: NodeID) {
        val internalId = resolveStorageId(whoseID) ?: return
        val allEdges = storage.getOutgoingEdges(internalId) + storage.getIncomingEdges(internalId)
        allEdges.forEach { eid ->
            edgeIndex.remove(Triple(storage.getEdgeSrc(eid), storage.getEdgeDst(eid), storage.getEdgeType(eid)))
            edgeCache.remove(eid)
            storage.deleteEdge(eid)
        }
        nodeCache.remove(internalId)
        nodeIdCache.remove(whoseID)
        nodeSidCache.remove(internalId)
        storage.deleteNode(internalId)
    }

    override fun getAllNodes(doSatfy: (N) -> Boolean): Sequence<N> =
        storage.nodeIDs
            .asSequence()
            .map { cachedNode(it) }
            .filter(doSatfy)

    // endregion

    // region Edge operations

    override fun addEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
    ): E {
        val srcSid = resolveStorageId(src) ?: throw EntityNotExistException(src)
        val dstSid = resolveStorageId(dst) ?: throw EntityNotExistException(dst)
        val eid = storage.addEdge(srcSid, dstSid, type)
        edgeIndex[Triple(srcSid, dstSid, type)] = eid
        return newEdgeObj(eid, ::resolveNodeId)
    }

    open fun addEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
        label: Label,
    ): E {
        val srcSid = resolveStorageId(src) ?: throw EntityNotExistException(src)
        val dstSid = resolveStorageId(dst) ?: throw EntityNotExistException(dst)
        val existingEid = findEdge(srcSid, dstSid, type)
        val edge =
            if (existingEid != null) {
                cachedEdge(existingEid)
            } else {
                val eid = storage.addEdge(srcSid, dstSid, type)
                edgeIndex[Triple(srcSid, dstSid, type)] = eid
                newEdgeObj(eid, ::resolveNodeId)
            }
        edge.labels = edge.labels + label
        label.changes += edge.internalId
        return edge
    }

    override fun getEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
    ): E? {
        val srcSid = resolveStorageId(src) ?: return null
        val dstSid = resolveStorageId(dst) ?: return null
        val eid = findEdge(srcSid, dstSid, type) ?: return null
        return cachedEdge(eid)
    }

    override fun containEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
    ): Boolean {
        val srcSid = resolveStorageId(src) ?: return false
        val dstSid = resolveStorageId(dst) ?: return false
        return findEdge(srcSid, dstSid, type) != null
    }

    override fun delEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
    ) {
        val srcSid = resolveStorageId(src) ?: return
        val dstSid = resolveStorageId(dst) ?: return
        val eid = findEdge(srcSid, dstSid, type) ?: return
        edgeIndex.remove(Triple(srcSid, dstSid, type))
        edgeCache.remove(eid)
        storage.deleteEdge(eid)
    }

    fun delEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
        label: Label,
    ) {
        val srcSid = resolveStorageId(src) ?: return
        val dstSid = resolveStorageId(dst) ?: return
        val eid = findEdge(srcSid, dstSid, type) ?: return
        val edge = cachedEdge(eid)
        val oldLabels = edge.labels
        val newLabels = oldLabels - label
        edge.labels = newLabels
        if (label in oldLabels) label.changes = label.changes - setOf(eid)
        if (newLabels.isNotEmpty()) return
        edgeIndex.remove(Triple(srcSid, dstSid, type))
        edgeCache.remove(eid)
        storage.deleteEdge(eid)
    }

    override fun getAllEdges(doSatfy: (E) -> Boolean): Sequence<E> =
        storage.edgeIDs
            .asSequence()
            .map { cachedEdge(it) }
            .filter(doSatfy)

    // endregion

    // region Graph structure queries

    override fun getOutgoingEdges(of: NodeID): Sequence<E> {
        val sid = resolveStorageId(of) ?: return emptySequence()
        return storage.getOutgoingEdges(sid).asSequence().map { cachedEdge(it) }
    }

    override fun getIncomingEdges(of: NodeID): Sequence<E> {
        val sid = resolveStorageId(of) ?: return emptySequence()
        return storage.getIncomingEdges(sid).asSequence().map { cachedEdge(it) }
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
    ): Sequence<N> = getIncomingEdges(of).filter(edgeCond).mapNotNull { getNode(it.srcNid) }

    override fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> = getOutgoingEdges(of).filter(edgeCond).mapNotNull { getNode(it.dstNid) }

    fun getChildren(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N> = getOutgoingEdges(of, label, cond).mapNotNull { getNode(it.dstNid) }

    fun getParents(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N> = getIncomingEdges(of, label, cond).mapNotNull { getNode(it.srcNid) }

    override fun getAncestors(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ) = sequence {
        val sid = resolveStorageId(of) ?: return@sequence
        val visited = hashSetOf<InternalID>()
        val queue = ArrayDeque<InternalID>().apply { add(sid) }
        while (queue.isNotEmpty()) {
            val currentSid = queue.removeFirst()
            if (!visited.add(currentSid)) continue
            storage.getIncomingEdges(currentSid).forEach { edgeID ->
                val parentSid = storage.getEdgeSrc(edgeID)
                val edge = cachedEdge(edgeID)
                if (!edgeCond(edge)) return@forEach
                yield(cachedNode(parentSid))
                queue.add(parentSid)
            }
        }
    }

    override fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ) = sequence {
        val sid = resolveStorageId(of) ?: return@sequence
        val visited = hashSetOf<InternalID>()
        val queue = ArrayDeque<InternalID>().apply { add(sid) }
        while (queue.isNotEmpty()) {
            val currentSid = queue.removeFirst()
            if (!visited.add(currentSid)) continue
            storage.getOutgoingEdges(currentSid).forEach { edgeID ->
                val childSid = storage.getEdgeDst(edgeID)
                val edge = cachedEdge(edgeID)
                if (!edgeCond(edge)) return@forEach
                yield(cachedNode(childSid))
                queue.add(childSid)
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
            allVisitable.filter { cur ->
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

    override fun close() {
        nodeIdCache.clear()
        nodeSidCache.clear()
        nodeIdCacheReady = false
        edgeIndex.clear()
        edgeIndexReady = false
        labelIdCache.clear()
        labelSidCache.clear()
        labelIdCacheReady = false
        queryCache.clear()
        nodeCache.clear()
        edgeCache.clear()
    }

    companion object {
        private const val PROP_CHANGES = "changes"
    }
}
