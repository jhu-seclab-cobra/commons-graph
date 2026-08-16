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
 * Visibility semantics (pure covering): an edge is visible under a query label
 * `by` iff at least one of its labels `l` satisfies `by == l` or `by > l` per
 * [IPoset.compare]. Covered labels never shadow one another — every edge
 * carrying at least one covered label is returned, regardless of which other
 * covered labels coexist in the same adjacency result. [Label.SUPREMUM]
 * bypasses filtering and returns all edges, labeled or not; edges with no
 * labels are otherwise visible only through unfiltered queries.
 *
 * @param N The node type.
 * @param E The edge type.
 * @see IPoset
 */
public interface PosetTrait<N : AbcNode, E : AbcEdge> : IGraph<N, E> {
    /** The pluggable poset module for label hierarchy operations. */
    public val poset: IPoset

    public fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
        label: Label,
    ): E {
        val existing = getEdge(src, dst, tag)
        val edge = existing ?: addEdge(src, dst, tag)
        edge.labels = edge.labels + label
        return edge
    }

    public fun delEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
        label: Label,
    ) {
        val edge = getEdge(src, dst, tag) ?: return
        // A label the edge does not carry is a no-op: an unlabeled edge must not be deleted.
        if (label !in edge.labels) return
        val remaining = edge.labels - label
        edge.labels = remaining
        if (remaining.isEmpty()) delEdge(src, dst, tag)
    }

    public fun getOutgoingEdges(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<E> {
        if (label == Label.SUPREMUM) return getOutgoingEdges(of).filter(cond)
        return doFilterVisitable(getOutgoingEdges(of).filter(cond), label)
    }

    public fun getIncomingEdges(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<E> {
        if (label == Label.SUPREMUM) return getIncomingEdges(of).filter(cond)
        return doFilterVisitable(getIncomingEdges(of).filter(cond), label)
    }

    public fun getChildren(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N> = getOutgoingEdges(of, label, cond).mapNotNull { getNode(whoseID = it.dstNid) }

    public fun getParents(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N> = getIncomingEdges(of, label, cond).mapNotNull { getNode(whoseID = it.srcNid) }

    public fun getDescendants(
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

    public fun getAncestors(
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

    // Pure covering per the interface visibility semantics: by == label or by > label.
    private fun covers(
        by: Label,
        label: Label,
    ): Boolean = by == label || poset.compare(by, label)?.let { it > 0 } == true

    private fun doFilterVisitable(
        edges: Sequence<E>,
        by: Label,
    ): Sequence<E> = edges.filter { edge -> edge.labels.any { covers(by, it) } }
}
