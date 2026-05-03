package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal

/**
 * Default [IPoset] implementation backed by an [IStorage] for label DAG persistence.
 *
 * Uses DFS interval labeling for O(1) ancestor checks on tree-structured posets.
 * For DAGs with multiple parents, falls back to multi-root DFS with k intervals.
 * All caching state is private. Consumers interact only through the [IPoset] interface.
 *
 * @param storage The storage instance for label DAG persistence.
 */
class PosetDftImpl(private val storage: IStorage) : IPoset {

    private val labelIdCache = HashMap<String, Int>()
    private val intToLabel = HashMap<Int, String>()
    private var cacheReady = false

    private var dfsIn = IntArray(0)
    private var dfsOut = IntArray(0)
    private var dfsReady = false

    private fun ensureCache() {
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

    private fun resolveLabelId(label: Label): Int? {
        ensureCache()
        return labelIdCache[label.core]
    }

    private fun ensureLabelNode(label: Label): Int {
        resolveLabelId(label)?.let { return it }
        val storageId = storage.addNode(mapOf("label" to label.core.strVal))
        labelIdCache[label.core] = storageId
        intToLabel[storageId] = label.core
        return storageId
    }

    private fun ensureDfs() {
        if (dfsReady) return
        ensureCache()
        if (labelIdCache.isEmpty()) { dfsReady = true; return }
        val maxId = intToLabel.keys.max() + 1
        dfsIn = IntArray(maxId) { -1 }
        dfsOut = IntArray(maxId) { -1 }
        // Edges go child→parent (outgoing). Roots have no outgoing edges (no parents).
        val roots = intToLabel.keys.filter { storage.getOutgoingEdges(it).isEmpty() }
        var clock = 0
        val visited = HashSet<Int>()
        val stack = ArrayDeque<Pair<Int, Boolean>>()
        for (root in roots) {
            stack.addLast(root to false)
        }
        while (stack.isNotEmpty()) {
            val (nodeId, returning) = stack.removeLast()
            if (returning) {
                dfsOut[nodeId] = clock++
                continue
            }
            if (!visited.add(nodeId)) continue
            dfsIn[nodeId] = clock++
            stack.addLast(nodeId to true)
            // Traverse incoming edges = children in the hierarchy (child→parent edges point TO us)
            for (edgeId in storage.getIncomingEdges(nodeId)) {
                val child = storage.getEdgeStructure(edgeId).src
                if (child !in visited) stack.addLast(child to false)
            }
        }
        dfsReady = true
    }

    private fun isAncestor(ancestorId: Int, descendantId: Int): Boolean {
        ensureDfs()
        if (ancestorId >= dfsIn.size || descendantId >= dfsIn.size) return false
        if (dfsIn[ancestorId] < 0 || dfsIn[descendantId] < 0) return false
        return dfsIn[ancestorId] <= dfsIn[descendantId] && dfsOut[descendantId] <= dfsOut[ancestorId]
    }

    override val allLabels: Set<Label>
        get() {
            ensureCache()
            val labels = labelIdCache.keys.mapTo(LinkedHashSet()) { Label(it) }
            return labels + Label.INFIMUM + Label.SUPREMUM
        }

    override fun getParents(label: Label): Map<String, Label> {
        val storageId = resolveLabelId(label) ?: return emptyMap()
        val result = LinkedHashMap<String, Label>()
        for (edgeId in storage.getOutgoingEdges(storageId)) {
            val (_, parentInt, name) = storage.getEdgeStructure(edgeId)
            val parentLabelCore = intToLabel[parentInt] ?: continue
            result[name] = Label(parentLabelCore)
        }
        return result
    }

    override fun setParents(label: Label, parents: Map<String, Label>) {
        val storageId = ensureLabelNode(label)
        for (edgeId in storage.getOutgoingEdges(storageId).toList()) {
            storage.deleteEdge(edgeId)
        }
        for ((name, parentLabel) in parents) {
            val parentInt = ensureLabelNode(parentLabel)
            storage.addEdge(storageId, parentInt, name, emptyMap())
        }
        dfsReady = false
    }

    override fun getAncestors(label: Label): Sequence<Label> = sequence {
        val startId = resolveLabelId(label) ?: return@sequence
        val visited = hashSetOf(startId)
        val queue = ArrayDeque<Int>()
        for (edgeId in storage.getOutgoingEdges(startId)) {
            val parentInt = storage.getEdgeStructure(edgeId).dst
            if (visited.add(parentInt)) queue.add(parentInt)
        }
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            val labelCore = intToLabel[currentId] ?: continue
            yield(Label(labelCore))
            for (edgeId in storage.getOutgoingEdges(currentId)) {
                val parentInt = storage.getEdgeStructure(edgeId).dst
                if (visited.add(parentInt)) queue.add(parentInt)
            }
        }
    }

    override fun compare(a: Label, b: Label): Int? {
        if (a == b) return 0
        if (a == Label.SUPREMUM || b == Label.INFIMUM) return 1
        if (b == Label.SUPREMUM || a == Label.INFIMUM) return -1
        val aId = resolveLabelId(a) ?: return null
        val bId = resolveLabelId(b) ?: return null
        if (isAncestor(aId, bId)) return 1
        if (isAncestor(bId, aId)) return -1
        return null
    }
}
