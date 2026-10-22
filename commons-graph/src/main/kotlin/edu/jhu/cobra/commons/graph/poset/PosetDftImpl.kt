package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.IRevertibleStorage
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.criticalSection
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal

// Poset-store node property holding the label core; fixed by the label design (design-label.md).
private const val LABEL_PROP = "label"

/**
 * Default [IPoset] implementation backed by an [IStorage] for label DAG persistence.
 *
 * Ancestor queries use a memoized ancestor-closure table, correct on arbitrary
 * DAGs including labels with multiple parents. The table is built lazily on the
 * first query. Once built, [setParents] extends it by one row when the label has no
 * descendants (a new label, the common case), keeps it when the parents are unchanged,
 * and discards it otherwise. A cycle in the hierarchy is detected during the build and
 * rejected.
 * On an [IRevertibleStorage], a revert drops the rows of the removed labels and keeps
 * the other rows, unless [setParents] changed the parents of an existing label since the
 * last revert: the revert may restore its earlier parent edges, so the table is discarded.
 * All caching state is private. Consumers interact only through the [IPoset] interface.
 *
 * Every public operation runs inside [criticalSection] on [storage] — the same
 * lock instance used by graphs sharing this storage — so a caching read/write
 * or a closure rebuild is never interleaved with a concurrent mutation.
 *
 * @param storage The storage instance for label DAG persistence.
 */
public class PosetDftImpl(
    private val storage: IStorage,
) : IPoset {
    private val labelIdCache = HashMap<String, Int>()
    private val intToLabel = HashMap<Int, String>()
    private var cacheReady = false

    // Invariant while non-null: one row per label in intToLabel, each equal to the label's ancestors.
    private var ancestorClosure: MutableMap<Int, Set<Int>>? = null

    // Set when setParents changed the parents of an existing label; a revert may undo that change.
    private var existingLabelRelabeled = false

    init {
        (storage as? IRevertibleStorage)?.addRevertListener { removedNodes, _ -> onRevert(removedNodes) }
    }

    // A revert restores the tombstoned parent edges of a surviving label and drops the edges
    // added to it since, with no signal beyond the removed IDs. So a surviving row is stale
    // only when setParents changed an existing label; every other surviving row is unchanged,
    // because a surviving label gains no parent edge without such a call.
    private fun onRevert(removedNodes: Set<Int>) {
        for (nodeId in removedNodes) {
            ancestorClosure?.remove(nodeId)
            val labelCore = intToLabel.remove(nodeId) ?: continue
            labelIdCache.remove(labelCore)
        }
        if (existingLabelRelabeled) ancestorClosure = null
        existingLabelRelabeled = false
    }

    private fun ensureCache() {
        if (cacheReady) return
        for (nodeId in storage.nodeIDs) {
            val labelCore = storage.getNodeProperty(nodeId, LABEL_PROP) as? StrVal
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
        val storageId = storage.addNode(mapOf(LABEL_PROP to label.core.strVal))
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
        get() =
            storage.criticalSection {
                ensureCache()
                val labels = labelIdCache.keys.mapTo(LinkedHashSet()) { Label(it) }
                labels + Label.INFIMUM + Label.SUPREMUM
            }

    override fun getParents(label: Label): Map<String, Label> =
        storage.criticalSection {
            val storageId = resolveLabelId(label) ?: return@criticalSection emptyMap()
            val result = LinkedHashMap<String, Label>()
            for (edgeId in storage.getOutgoingEdges(storageId)) {
                val (_, parentInt, name) = storage.getEdgeStructure(edgeId)
                val parentLabelCore = intToLabel[parentInt] ?: continue
                result[name] = Label(parentLabelCore)
            }
            result
        }

    override fun setParents(
        label: Label,
        parents: Map<String, Label>,
    ): Unit =
        storage.criticalSection {
            val existingId = resolveLabelId(label)
            if (existingId != null && parentsUnchanged(existingId, parents)) return@criticalSection
            val storageId = existingId ?: ensureLabelNode(label)
            // A label without incoming edges has no descendant row to update.
            val hasDescendants = existingId != null && storage.getIncomingEdges(storageId).isNotEmpty()
            for (edgeId in storage.getOutgoingEdges(storageId).toList()) {
                storage.deleteEdge(edgeId)
            }
            val parentIds =
                parents.map { (name, parentLabel) ->
                    ensureLabelNode(parentLabel).also { storage.addEdge(storageId, it, name, emptyMap()) }
                }
            if (existingId != null) existingLabelRelabeled = true
            extendClosure(storageId, parentIds, hasDescendants)
        }

    /** True when [labelId] already holds exactly [parents], by name and by label. */
    private fun parentsUnchanged(
        labelId: Int,
        parents: Map<String, Label>,
    ): Boolean {
        val stored = storage.getOutgoingEdges(labelId)
        if (stored.size != parents.size) return false
        return stored.all { edgeId ->
            val (_, parentInt, name) = storage.getEdgeStructure(edgeId)
            val parent = parents[name] ?: return false
            parentInt == resolveLabelId(parent)
        }
    }

    // Recomputes the row of [labelId] alone when no other row can change; otherwise discards
    // the table. A parent that is the label itself closes a cycle, which the next build reports.
    private fun extendClosure(
        labelId: Int,
        parentIds: List<Int>,
        hasDescendants: Boolean,
    ) {
        val closure = ancestorClosure ?: return
        if (hasDescendants || labelId in parentIds) {
            ancestorClosure = null
            return
        }
        val row = HashSet<Int>()
        for (parentInt in parentIds) {
            row.add(parentInt)
            row.addAll(closure.getOrPut(parentInt) { emptySet() })
        }
        closure[labelId] = row
    }

    override fun getAncestors(label: Label): Sequence<Label> =
        storage.criticalSection {
            val startId = resolveLabelId(label)
            if (startId == null) {
                emptySequence()
            } else {
                val visited = hashSetOf(startId)
                val queue = ArrayDeque<Int>()
                for (edgeId in storage.getOutgoingEdges(startId)) {
                    val parentInt = storage.getEdgeStructure(edgeId).dst
                    if (visited.add(parentInt)) queue.add(parentInt)
                }
                val result = mutableListOf<Label>()
                while (queue.isNotEmpty()) {
                    val currentId = queue.removeFirst()
                    val labelCore = intToLabel[currentId]
                    if (labelCore == null) continue
                    result.add(Label(labelCore))
                    for (edgeId in storage.getOutgoingEdges(currentId)) {
                        val parentInt = storage.getEdgeStructure(edgeId).dst
                        if (visited.add(parentInt)) queue.add(parentInt)
                    }
                }
                result.asSequence()
            }
        }

    override fun compare(
        a: Label,
        b: Label,
    ): Int? =
        storage.criticalSection {
            if (a == b) return@criticalSection 0
            if (a == Label.SUPREMUM || b == Label.INFIMUM) return@criticalSection 1
            if (b == Label.SUPREMUM || a == Label.INFIMUM) return@criticalSection -1
            val aId = resolveLabelId(a) ?: return@criticalSection null
            val bId = resolveLabelId(b) ?: return@criticalSection null
            if (isAncestor(aId, bId)) return@criticalSection 1
            if (isAncestor(bId, aId)) return@criticalSection -1
            null
        }
}
