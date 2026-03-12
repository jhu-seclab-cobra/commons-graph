package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.listVal
import edu.jhu.cobra.commons.value.mapVal
import edu.jhu.cobra.commons.value.strVal
import java.io.Closeable

/**
 * Abstract directed multi-graph allowing multiple edges between the same pair of
 * nodes, with integrated label-based edge visibility.
 *
 * Implements [IPartialOrderSet] with write-through persistence: label hierarchy
 * and change records are stored in [IStorage] metadata and always kept in sync.
 *
 * Label-filtered methods use the visibility rule: an edge is visitable under
 * label `by` if at least one of its labels `l` satisfies `by == l` or `by > l`
 * in the poset hierarchy.
 *
 * Node and edge identity is delegated to the underlying [storage].
 *
 * @param N The type of nodes in the graph, must extend [AbcNode].
 * @param E The type of edges in the graph, must extend [AbcEdge].
 */
@Suppress("TooManyFunctions")
abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> :
    IGraph<N, E>,
    IPartialOrderSet,
    Closeable {
    abstract val storage: IStorage

    override val nodeIDs: Set<NodeID> get() = storage.nodeIDs

    override val edgeIDs: Set<EdgeID> get() = storage.edgeIDs

    protected abstract fun newNodeObj(nid: NodeID): N

    protected abstract fun newEdgeObj(eid: EdgeID): E

    // B3-A: wrapper object cache
    private val nodeCache = HashMap<NodeID, N>()
    private val edgeCache = HashMap<EdgeID, E>()

    private fun cachedNode(nid: NodeID): N = nodeCache.getOrPut(nid) { newNodeObj(nid) }

    private fun cachedEdge(eid: EdgeID): E = edgeCache.getOrPut(eid) { newEdgeObj(eid) }

    // region IPartialOrderSet — write-through to storage metadata

    private val queryCache = mutableMapOf<Pair<Label, Label>, Int?>()

    override val allLabels: Set<Label>
        get() {
            val names = storage.metaNames
                .filter { it.startsWith(PARENTS_PREFIX) && it.endsWith(META_SUFFIX) }
                .map { Label(it.removePrefix(PARENTS_PREFIX).removeSuffix(META_SUFFIX)) }
                .toSet()
            return names + Label.INFIMUM + Label.SUPREMUM
        }

    override var Label.parents: Map<String, Label>
        get() {
            val meta = storage.getMeta(parentsKey(this)) as? MapVal ?: return emptyMap()
            return meta.mapValues { (_, v) -> Label((v as StrVal).core) }
        }
        set(value) {
            val serialized = value.mapValues { (_, v) -> v.core.strVal }.mapVal
            storage.setMeta(parentsKey(this), serialized)
            queryCache.clear()
        }

    override val Label.ancestors: Sequence<Label>
        get() =
            sequence {
                val visited = mutableSetOf<Label>()
                val stack = ArrayDeque<Label>().also { it.add(this@ancestors) }
                while (stack.isNotEmpty()) {
                    val current = stack.removeFirst()
                    if (current in visited) continue
                    visited.add(current)
                    val parents = current.parents.values
                    yieldAll(elements = parents)
                    stack.addAll(elements = parents)
                }
            }

    override var Label.changes: Set<EdgeID>
        get() {
            val meta = storage.getMeta(changesKey(this)) as? ListVal ?: return emptySet()
            return meta.map { EdgeID(it as ListVal) }.toSet()
        }
        set(value) {
            if (value.isEmpty()) {
                storage.setMeta(changesKey(this), null)
            } else {
                storage.setMeta(changesKey(this), value.map { it.serialize }.listVal)
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
        storage.addNode(id = withID)
        return newNodeObj(withID)
    }

    override fun getNode(whoseID: NodeID): N? {
        if (!storage.containsNode(whoseID)) return null
        return cachedNode(whoseID)
    }

    override fun containNode(whoseID: NodeID): Boolean = storage.containsNode(whoseID)

    override fun delNode(whoseID: NodeID) {
        if (!storage.containsNode(whoseID)) return
        val allEdges = storage.getOutgoingEdges(whoseID) + storage.getIncomingEdges(whoseID)
        allEdges.forEach { eid ->
            edgeCache.remove(eid)
            storage.deleteEdge(eid)
        }
        nodeCache.remove(whoseID)
        storage.deleteNode(id = whoseID)
    }

    override fun getAllNodes(doSatfy: (N) -> Boolean): Sequence<N> =
        storage.nodeIDs
            .asSequence()
            .map { cachedNode(it) }
            .filter(doSatfy)

    // endregion

    // region Edge operations

    override fun addEdge(withID: EdgeID): E {
        storage.addEdge(id = withID)
        return newEdgeObj(withID)
    }

    override fun addEdge(
        withID: EdgeID,
        label: Label,
    ): E {
        val edge = getEdge(whoseID = withID) ?: addEdge(withID = withID)
        edge.labels = edge.labels + label
        label.changes += withID
        return edge
    }

    override fun getEdge(whoseID: EdgeID): E? {
        if (!storage.containsEdge(whoseID)) return null
        return cachedEdge(whoseID)
    }

    override fun containEdge(whoseID: EdgeID): Boolean = storage.containsEdge(whoseID)

    override fun delEdge(whoseID: EdgeID) {
        if (!storage.containsEdge(whoseID)) return
        edgeCache.remove(whoseID)
        storage.deleteEdge(id = whoseID)
    }

    override fun delEdge(
        whoseID: EdgeID,
        label: Label,
    ) {
        val edge = getEdge(whoseID = whoseID) ?: return
        val oldLabels = edge.labels
        val newLabels = oldLabels - label
        edge.labels = newLabels
        if (label in oldLabels) label.changes -= whoseID
        if (newLabels.isEmpty()) delEdge(whoseID = whoseID)
    }

    override fun getAllEdges(doSatfy: (E) -> Boolean): Sequence<E> =
        storage.edgeIDs
            .asSequence()
            .map { cachedEdge(it) }
            .filter(doSatfy)

    // endregion

    // region Graph structure queries

    override fun getOutgoingEdges(of: NodeID): Sequence<E> {
        if (!storage.containsNode(of)) return emptySequence()
        return storage.getOutgoingEdges(of).asSequence().map { cachedEdge(it) }
    }

    override fun getIncomingEdges(of: NodeID): Sequence<E> {
        if (!storage.containsNode(of)) return emptySequence()
        return storage.getIncomingEdges(of).asSequence().map { cachedEdge(it) }
    }

    override fun getOutgoingEdges(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean,
    ): Sequence<E> = getOutgoingEdges(of).filter(cond).filterVisitable(label)

    override fun getIncomingEdges(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean,
    ): Sequence<E> = getIncomingEdges(of).filter(cond).filterVisitable(label)

    override fun getParents(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> = getIncomingEdges(of).filter(edgeCond).map { cachedNode(it.srcNid) }

    override fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> = getOutgoingEdges(of).filter(edgeCond).map { cachedNode(it.dstNid) }

    override fun getChildren(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean,
    ): Sequence<N> = getOutgoingEdges(of, label, cond).mapNotNull { getNode(it.dstNid) }

    override fun getParents(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean,
    ): Sequence<N> = getIncomingEdges(of, label, cond).mapNotNull { getNode(it.srcNid) }

    override fun getAncestors(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ) = sequence {
        if (!storage.containsNode(of)) return@sequence
        val visited = hashSetOf<NodeID>()
        val queue = ArrayDeque<NodeID>().apply { add(of) }
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            if (!visited.add(currentId)) continue
            storage.getIncomingEdges(currentId).forEach { edgeID ->
                if (!edgeCond(cachedEdge(edgeID))) return@forEach
                yield(cachedNode(edgeID.srcNid))
                queue.add(edgeID.srcNid)
            }
        }
    }

    override fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ) = sequence {
        if (!storage.containsNode(of)) return@sequence
        val visited = hashSetOf<NodeID>()
        val queue = ArrayDeque<NodeID>().apply { add(of) }
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            if (!visited.add(currentId)) continue
            storage.getOutgoingEdges(currentId).forEach { edgeID ->
                if (!edgeCond(cachedEdge(edgeID))) return@forEach
                yield(cachedNode(edgeID.dstNid))
                queue.add(edgeID.dstNid)
            }
        }
    }

    override fun getDescendants(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean,
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

    override fun getAncestors(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean,
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
        val allEdges = this.toList()
        val allVisitable =
            allEdges
                .flatMap { e ->
                    e.labels.filter { l ->
                        by == l || by.compareTo(l)?.let { it > 0 } ?: false
                    }
                }.toSet()
        val allNotCovered =
            allVisitable.filter { cur ->
                !allVisitable.any { other ->
                    other != cur && other.compareTo(cur)?.let { it > 0 } ?: false
                }
            }
        return allEdges
            .filter { edge ->
                edge.labels.any { it in allNotCovered }
            }.asSequence()
    }

    override fun close() {
        nodeCache.clear()
        edgeCache.clear()
    }

    companion object {
        private const val PARENTS_PREFIX = "__lp_"
        private const val CHANGES_PREFIX = "__lc_"
        private const val META_SUFFIX = "__"

        private fun parentsKey(label: Label): String = "$PARENTS_PREFIX${label.core}$META_SUFFIX"

        private fun changesKey(label: Label): String = "$CHANGES_PREFIX${label.core}$META_SUFFIX"
    }
}
