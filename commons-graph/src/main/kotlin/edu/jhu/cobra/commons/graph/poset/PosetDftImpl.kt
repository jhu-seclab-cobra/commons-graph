package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal

/**
 * Default [IPoset] implementation backed by an [IStorage] for label DAG persistence.
 *
 * Ancestor queries use a memoized ancestor-closure table, correct on arbitrary
 * DAGs including labels with multiple parents. The table is built lazily on the
 * first query and discarded on any parent mutation. A cycle in the hierarchy is
 * detected during the build and rejected.
 * All caching state is private. Consumers interact only through the [IPoset] interface.
 *
 * @param storage The storage instance for label DAG persistence.
 */
public class PosetDftImpl(
    private val storage: IStorage,
) : IPoset {
    private val labelIdCache = HashMap<String, Int>()
    private val intToLabel = HashMap<Int, String>()
    private var cacheReady = false

    private var ancestorClosure: Map<Int, Set<Int>>? = null

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

    private fun ensureClosure(): Map<Int, Set<Int>> {
        ancestorClosure?.let { return it }
        ensureCache()
        val closure = HashMap<Int, Set<Int>>()
        val onPath = HashSet<Int>()
        for (nodeId in intToLabel.keys) {
            computeAncestors(nodeId, closure, onPath)
        }
        ancestorClosure = closure
        return closure
    }

    // Explicit stack instead of recursion: the build must not consume one call
    // frame per hierarchy level, or a deep chain overflows the call stack.
    private fun computeAncestors(
        rootId: Int,
        closure: MutableMap<Int, Set<Int>>,
        onPath: MutableSet<Int>,
    ) {
        val pending = ArrayDeque<Int>()
        pending.addLast(rootId)
        while (pending.isNotEmpty()) {
            val nodeId = pending.last()
            when {
                nodeId in closure -> pending.removeLast()

                // First visit: expand unresolved parents and revisit after them.
                onPath.add(nodeId) && expandParents(nodeId, closure, onPath, pending) -> Unit

                // Revisit: every parent is resolved; finalize this node.
                else -> finalizeNode(nodeId, closure, onPath, pending)
            }
        }
    }

    /** Unions [nodeId]'s resolved parents into its closure entry and pops it. */
    private fun finalizeNode(
        nodeId: Int,
        closure: MutableMap<Int, Set<Int>>,
        onPath: MutableSet<Int>,
        pending: ArrayDeque<Int>,
    ) {
        val ancestors = HashSet<Int>()
        // Edges go child→parent (outgoing): each parent plus its own ancestors.
        for (edgeId in storage.getOutgoingEdges(nodeId)) {
            val parentInt = storage.getEdgeStructure(edgeId).dst
            ancestors.add(parentInt)
            ancestors.addAll(closure.getValue(parentInt))
        }
        closure[nodeId] = ancestors
        onPath.remove(nodeId)
        pending.removeLast()
    }

    /** Pushes [nodeId]'s unresolved parents onto [pending]; true when any was pushed. */
    private fun expandParents(
        nodeId: Int,
        closure: Map<Int, Set<Int>>,
        onPath: Set<Int>,
        pending: ArrayDeque<Int>,
    ): Boolean {
        var expanded = false
        for (edgeId in storage.getOutgoingEdges(nodeId)) {
            val parentInt = storage.getEdgeStructure(edgeId).dst
            if (parentInt in closure) continue
            check(parentInt !in onPath) { "Label hierarchy cycle through '${intToLabel[parentInt]}'" }
            pending.addLast(parentInt)
            expanded = true
        }
        return expanded
    }

    private fun isAncestor(
        ancestorId: Int,
        descendantId: Int,
    ): Boolean = ancestorId in ensureClosure().getValue(descendantId)

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

    override fun setParents(
        label: Label,
        parents: Map<String, Label>,
    ) {
        val storageId = ensureLabelNode(label)
        for (edgeId in storage.getOutgoingEdges(storageId).toList()) {
            storage.deleteEdge(edgeId)
        }
        for ((name, parentLabel) in parents) {
            val parentInt = ensureLabelNode(parentLabel)
            storage.addEdge(storageId, parentInt, name, emptyMap())
        }
        ancestorClosure = null
    }

    override fun getAncestors(label: Label): Sequence<Label> =
        sequence {
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

    override fun compare(
        a: Label,
        b: Label,
    ): Int? {
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
