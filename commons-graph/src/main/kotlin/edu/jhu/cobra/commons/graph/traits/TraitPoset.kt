package edu.jhu.cobra.commons.graph.traits

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.poset.IPoset
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal

interface TraitPoset<N : AbcNode, E : AbcEdge> : IGraph<N, E>, IPoset {

    val posetStorage: IStorage

    val posetState: PosetState

    class PosetState {
        val labelIdCache = HashMap<String, Int>()
        val intToLabel = HashMap<Int, String>()
        val queryCache = mutableMapOf<Pair<Label, Label>, Int?>()
        var cacheReady = false

        fun clear() {
            labelIdCache.clear()
            intToLabel.clear()
            queryCache.clear()
            cacheReady = false
        }

        fun ensureCache(storage: IStorage) {
            if (cacheReady) return
            for (nodeId in storage.nodeIDs) {
                val labelCore = storage.getNodeProperty(nodeId, "label") as? StrVal
                if (labelCore != null) {
                    labelIdCache[labelCore.core] = nodeId
                    intToLabel[nodeId] = labelCore.core
                }
            }
            cacheReady = true
        }

        fun resolveLabelId(label: Label, storage: IStorage): Int? {
            ensureCache(storage)
            return labelIdCache[label.core]
        }

        fun ensureLabelNode(label: Label, storage: IStorage): Int {
            resolveLabelId(label, storage)?.let { return it }
            val storageId = storage.addNode(mapOf("label" to label.core.strVal))
            labelIdCache[label.core] = storageId
            intToLabel[storageId] = label.core
            return storageId
        }
    }

    // region IPoset implementation

    override val allLabels: Set<Label>
        get() {
            posetState.ensureCache(posetStorage)
            val labels = posetState.labelIdCache.keys.mapTo(LinkedHashSet()) { Label(it) }
            return labels + Label.INFIMUM + Label.SUPREMUM
        }

    override var Label.parents: Map<String, Label>
        get() {
            val storageId = posetState.resolveLabelId(this, posetStorage) ?: return emptyMap()
            val result = LinkedHashMap<String, Label>()
            for (edgeId in posetStorage.getOutgoingEdges(storageId)) {
                val (_, parentInt, name) = posetStorage.getEdgeStructure(edgeId)
                val parentLabelCore = posetState.intToLabel[parentInt] ?: continue
                result[name] = Label(parentLabelCore)
            }
            return result
        }
        set(value) {
            val storageId = posetState.ensureLabelNode(this, posetStorage)
            for (edgeId in posetStorage.getOutgoingEdges(storageId).toList()) {
                posetStorage.deleteEdge(edgeId)
            }
            for ((name, parentLabel) in value) {
                val parentInt = posetState.ensureLabelNode(parentLabel, posetStorage)
                posetStorage.addEdge(storageId, parentInt, name, emptyMap())
            }
            posetState.queryCache.clear()
        }

    override val Label.ancestors: Sequence<Label>
        get() = sequence {
            val startId = posetState.resolveLabelId(this@ancestors, posetStorage) ?: return@sequence
            val visited = hashSetOf<Int>()
            val stack = ArrayDeque<Int>().also { it.add(startId) }
            while (stack.isNotEmpty()) {
                val currentId = stack.removeFirst()
                if (!visited.add(currentId)) continue
                for (edgeId in posetStorage.getOutgoingEdges(currentId)) {
                    val parentInt = posetStorage.getEdgeStructure(edgeId).dst
                    val parentLabelCore = posetState.intToLabel[parentInt] ?: continue
                    yield(Label(parentLabelCore))
                    stack.add(parentInt)
                }
            }
        }

    override fun Label.compareTo(other: Label): Int? {
        if (this == other) return 0
        if (this == Label.SUPREMUM || other == Label.INFIMUM) return 1
        if (other == Label.SUPREMUM || this == Label.INFIMUM) return -1
        val key = this to other
        posetState.queryCache[key]?.let { return it }
        val reverseKey = other to this
        posetState.queryCache[reverseKey]?.let { return -it }
        other.ancestors.forEach { label ->
            if (label != this) return@forEach
            posetState.queryCache[key] = 1
            return 1
        }
        this.ancestors.forEach { label ->
            if (label != other) return@forEach
            posetState.queryCache[key] = -1
            return -1
        }
        return null
    }

    // endregion

    // region Label-filtered graph operations

    fun addEdge(src: NodeID, dst: NodeID, tag: String, label: Label): E {
        val existing = getEdge(src, dst, tag)
        val edge = existing ?: addEdge(src, dst, tag)
        edge.labels = edge.labels + label
        return edge
    }

    fun delEdge(src: NodeID, dst: NodeID, tag: String, label: Label) {
        val edge = getEdge(src, dst, tag) ?: return
        val remaining = edge.labels - label
        edge.labels = remaining
        if (remaining.isEmpty()) delEdge(src, dst, tag)
    }

    fun getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E> {
        if (label == Label.SUPREMUM) return getOutgoingEdges(of).filter(cond)
        return doFilterVisitable(getOutgoingEdges(of).filter(cond), label)
    }

    fun getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E> {
        if (label == Label.SUPREMUM) return getIncomingEdges(of).filter(cond)
        return doFilterVisitable(getIncomingEdges(of).filter(cond), label)
    }

    fun getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N> =
        getOutgoingEdges(of, label, cond).mapNotNull { getNode(whoseID = it.dstNid) }

    fun getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N> =
        getIncomingEdges(of, label, cond).mapNotNull { getNode(whoseID = it.srcNid) }

    fun getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N> =
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

    fun getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N> =
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

    fun doFilterVisitable(edges: Sequence<E>, by: Label): Sequence<E> {
        val edgesWithLabels = ArrayList<Pair<E, Set<Label>>>()
        val allVisitable = HashSet<Label>()
        for (e in edges) {
            val labels = e.labels
            edgesWithLabels.add(e to labels)
            for (l in labels) {
                if (by == l || by.compareTo(l)?.let { it > 0 } == true) {
                    allVisitable.add(l)
                }
            }
        }
        if (allVisitable.size <= 1) {
            return edgesWithLabels.asSequence()
                .filter { (_, labels) -> labels.any { it in allVisitable } }
                .map { it.first }
        }
        val allNotCovered = allVisitable.filterTo(HashSet()) { cur ->
            allVisitable.none { other ->
                other != cur && other.compareTo(cur)?.let { it > 0 } == true
            }
        }
        return edgesWithLabels.asSequence()
            .filter { (_, labels) -> labels.any { it in allNotCovered } }
            .map { it.first }
    }

    // endregion
}
