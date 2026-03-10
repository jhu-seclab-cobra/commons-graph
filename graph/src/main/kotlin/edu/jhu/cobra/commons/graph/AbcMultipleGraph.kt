package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.*
import java.io.Closeable
import java.util.LinkedList

/**
 * Abstract directed multi-graph allowing multiple edges between the same pair of
 * nodes, with integrated label-based edge visibility.
 *
 * Label hierarchy, change tracking, comparison caching, and storage serialization
 * are implemented directly. Label-filtered methods use the visibility rule: an edge
 * is visitable under label `by` if at least one of its labels `l` satisfies
 * `by == l` or `by > l` in the lattice hierarchy.
 *
 * Auto-persists lattice state on [close].
 *
 * @param N The type of nodes in the graph, must extend [AbcNode].
 * @param E The type of edges in the graph, must extend [AbcEdge].
 */
@Suppress("TooManyFunctions")
abstract class AbcMultipleGraph<N : AbcNode, E : AbcEdge> :
    IGraph<N, E>,
    Closeable {
    abstract val storage: IStorage

    override val nodeIDs: MutableSet<NodeID> = mutableSetOf()

    override val edgeIDs: MutableSet<EdgeID> = mutableSetOf()

    protected abstract fun newNodeObj(nid: NodeID): N

    protected abstract fun newEdgeObj(eid: EdgeID): E

    // region ILabelLattice implementation

    private val hierarchy = mutableMapOf<Label, MutableMap<String, Label>>()
    private val changeRecorder = mutableMapOf<Label, Set<EdgeID>>()
    private val queryCache = mutableMapOf<Pair<Label, Label>, Int?>()

    override val allLabels: Set<Label> get() = hierarchy.keys + Label.INFIMUM + Label.SUPREMUM

    override var Label.parents: Map<String, Label>
        get() = hierarchy[this].orEmpty()
        set(value) {
            hierarchy[this] = value.toMutableMap()
        }

    override val Label.ancestors: Sequence<Label>
        get() =
            sequence {
                val visited = mutableSetOf<Label>()
                val stack = LinkedList<Label>().also { it.add(this@ancestors) }
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
        get() = changeRecorder[this].orEmpty()
        set(value) {
            changeRecorder[this] = value.toMutableSet()
        }

    override var AbcEdge.labels: Set<Label>
        get() {
            val labelList = getTypeProp<ListVal>(name = "labels")?.core.orEmpty()
            return labelList.map { Label(core = it.core.toString()) }.toSet()
        }
        set(values) {
            val labelList = getTypeProp<ListVal>(name = "labels")?.core.orEmpty()
            val curLabels = labelList.map { Label(core = it.core.toString()) }.toSet()
            (curLabels - values).forEach { delete -> delete.changes -= id }
            (values - curLabels).forEach { newAdd -> newAdd.changes += id }
            setProp(name = "labels", value = values.map { it.core }.listVal)
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

    override fun storeLattice(into: IStorage) {
        val curMap = mutableMapOf<String, IValue>()
        allLabels.forEach { curLabel ->
            if (curLabel == Label.INFIMUM || curLabel == Label.SUPREMUM) return@forEach
            curMap[curLabel.core] = curLabel.parents.mapValues { (_, vs) -> vs.core.strVal }.mapVal
        }
        val preMap = into.getMeta("__lattice__") as? MapVal
        val newMap = preMap?.core.orEmpty() + curMap
        into.setMeta("__lattice__", newMap.mapVal)
        val preChanges = into.getMeta("__changes__") as? MapVal
        val curChanges = changeRecorder.map { (l, cs) -> l.core to cs.map { it.serialize }.listVal }
        val newChanges = preChanges?.core.orEmpty() + curChanges
        into.setMeta("__changes__", newChanges.mapVal)
    }

    override fun loadLattice(from: IStorage) {
        val loadLattice = from.getMeta("__lattice__") as? MapVal ?: return
        loadLattice.forEach { (labelName, parentsName) ->
            val parentMap = parentsName as MapVal
            val parents = parentMap.mapValues { (_, prev) -> Label(prev as StrVal) }
            Label(labelName).parents += parents
        }
        val loadRecords = from.getMeta("__changes__") as? MapVal ?: return
        loadRecords.core.forEach { (labelCore, changesCore) ->
            val loadLabel = Label(core = labelCore)
            val curChanges = changeRecorder[loadLabel].orEmpty()
            val loadChanges = (changesCore as ListVal).map { EdgeID(it as ListVal) }
            changeRecorder[loadLabel] = curChanges + loadChanges
        }
    }

    // endregion

    // region Node operations

    override fun addNode(withID: NodeID): N {
        if (withID in nodeIDs) throw EntityAlreadyExistException(withID)
        if (!storage.containsNode(withID)) storage.addNode(id = withID)
        return newNodeObj(withID.also { nodeIDs.add(it) })
    }

    override fun getNode(whoseID: NodeID): N? {
        if (whoseID !in nodeIDs || !storage.containsNode(whoseID)) return null
        return newNodeObj(nid = whoseID)
    }

    override fun containNode(whoseID: NodeID): Boolean = nodeIDs.contains(whoseID) && storage.containsNode(whoseID)

    override fun delNode(whoseID: NodeID) {
        if (!nodeIDs.remove(whoseID) || !storage.containsNode(whoseID)) return
        val allEdges = storage.getOutgoingEdges(whoseID) + storage.getIncomingEdges(whoseID)
        allEdges.forEach { if (edgeIDs.remove(it)) storage.deleteEdge(it) }
        storage.deleteNode(id = whoseID)
    }

    override fun getAllNodes(doSatfy: (N) -> Boolean): Sequence<N> =
        nodeIDs.asSequence().map { newNodeObj(it) }.filter { storage.containsNode(it.id) && doSatfy(it) }

    // endregion

    // region Edge operations

    override fun addEdge(withID: EdgeID): E {
        if (withID in edgeIDs) throw EntityAlreadyExistException(withID)
        if (!storage.containsEdge(withID)) storage.addEdge(id = withID)
        return newEdgeObj(withID.also { edgeIDs.add(it) })
    }

    override fun addEdge(
        withID: EdgeID,
        label: Label,
    ): E {
        val edge = getEdge(whoseID = withID) ?: addEdge(withID = withID)
        edge.labels += label
        return edge
    }

    override fun getEdge(whoseID: EdgeID): E? {
        if (whoseID !in edgeIDs || !storage.containsEdge(whoseID)) return null
        return newEdgeObj(eid = whoseID)
    }

    override fun containEdge(whoseID: EdgeID): Boolean = edgeIDs.contains(whoseID) && storage.containsEdge(whoseID)

    override fun delEdge(whoseID: EdgeID) {
        if (!edgeIDs.remove(whoseID) || !storage.containsEdge(whoseID)) return
        storage.deleteEdge(id = whoseID)
    }

    override fun delEdge(
        whoseID: EdgeID,
        label: Label,
    ) {
        val edge = getEdge(whoseID = whoseID) ?: return
        edge.labels -= label
        if (edge.labels.isEmpty()) delEdge(whoseID = whoseID)
    }

    override fun getAllEdges(doSatfy: (E) -> Boolean): Sequence<E> =
        edgeIDs.asSequence().map { newEdgeObj(it) }.filter { storage.containsEdge(it.id) && doSatfy(it) }

    // endregion

    // region Graph structure queries

    override fun getOutgoingEdges(of: NodeID): Sequence<E> {
        if (of !in nodeIDs || !storage.containsNode(of)) return emptySequence()
        return storage
            .getOutgoingEdges(of)
            .filter { it in edgeIDs }
            .asSequence()
            .map { newEdgeObj(it) }
    }

    override fun getIncomingEdges(of: NodeID): Sequence<E> {
        if (of !in nodeIDs || !storage.containsNode(of)) return emptySequence()
        return storage
            .getIncomingEdges(of)
            .filter { it in edgeIDs }
            .asSequence()
            .map { newEdgeObj(it) }
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
    ): Sequence<N> = getIncomingEdges(of).filter(edgeCond).map { newNodeObj(it.srcNid) }

    override fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ): Sequence<N> = getOutgoingEdges(of).filter(edgeCond).map { newNodeObj(it.dstNid) }

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
        if (of !in nodeIDs || !storage.containsNode(of)) return@sequence
        val visited = hashSetOf<NodeID>()
        val stack = mutableListOf(of)
        while (stack.isNotEmpty()) {
            val currentId = stack.removeAt(0)
            if (!visited.add(currentId)) continue
            storage.getIncomingEdges(currentId).forEach { edgeID ->
                if (edgeID !in edgeIDs) return@forEach
                if (!edgeCond(newEdgeObj(edgeID))) return@forEach
                yield(newNodeObj(nid = edgeID.srcNid))
                stack.add(edgeID.srcNid)
            }
        }
    }

    override fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean,
    ) = sequence {
        if (of !in nodeIDs || !storage.containsNode(of)) return@sequence
        val visited = hashSetOf<NodeID>()
        val queue = LinkedList<NodeID>().apply { add(of) }
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            if (!visited.add(currentId)) continue
            storage.getOutgoingEdges(currentId).forEach { edgeID ->
                if (edgeID !in edgeIDs) return@forEach
                if (!edgeCond(newEdgeObj(edgeID))) return@forEach
                yield(newNodeObj(nid = edgeID.dstNid))
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
            val queue = LinkedList<NodeID>().apply { add(of) }
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
            val queue = LinkedList<NodeID>().apply { add(of) }
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
        storeLattice(storage)
        edgeIDs.clear()
        nodeIDs.clear()
    }
}
