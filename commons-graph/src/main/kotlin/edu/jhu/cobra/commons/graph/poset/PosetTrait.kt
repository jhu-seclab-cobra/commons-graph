package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.NodeID

/**
 * Graph trait that adds label-filtered operations via a pluggable [IPoset].
 *
 * Implementors provide [poset]; default methods combine [IGraph] traversals
 * with poset visibility filtering.
 *
 * @param N The node type.
 * @param E The edge type.
 * @see IPoset
 */
interface PosetTrait<N : AbcNode, E : AbcEdge> : IGraph<N, E> {

    /** The pluggable poset module for label hierarchy operations. */
    val poset: IPoset

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

    private fun doFilterVisitable(edges: Sequence<E>, by: Label): Sequence<E> {
        val edgesWithLabels = ArrayList<Pair<E, Set<Label>>>()
        val allVisitable = HashSet<Label>()
        for (e in edges) {
            val labels = e.labels
            edgesWithLabels.add(e to labels)
            for (l in labels) {
                if (by == l || poset.compare(by, l)?.let { it > 0 } == true) {
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
                other != cur && poset.compare(other, cur)?.let { it > 0 } == true
            }
        }
        return edgesWithLabels.asSequence()
            .filter { (_, labels) -> labels.any { it in allNotCovered } }
            .map { it.first }
    }
}
