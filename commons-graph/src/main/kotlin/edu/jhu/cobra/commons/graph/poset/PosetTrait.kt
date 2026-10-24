package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.criticalSection

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
 * Label assignments go through [addEdge], [delEdge], and [relabel] with a
 * label, which maintain [labelIndex]; [getLabeledEdges] and [relabel] then
 * cost O(candidates of the label) instead of a full edge scan (spec-poset.md,
 * Label Carrier Index). Each of these label operations runs inside
 * [storage]'s critical section, so its label read, label write, and index
 * update are one atomic step. A storage revert repairs the index through
 * [restoreLabelIndex], which the graph calls with the edges the revert restored;
 * the owning graph's endpoint membership, not the index, decides which candidates
 * [getLabeledEdges] returns.
 *
 * @param N The node type.
 * @param E The edge type.
 * @see IPoset
 */
public interface PosetTrait<N : AbcNode, E : AbcEdge> : IGraph<N, E> {
    /** The pluggable poset module for label hierarchy operations. */
    public val poset: IPoset

    /** The label carrier index of this graph instance. */
    public val labelIndex: LabelEdgeIndex<E>

    /** The storage of this graph; its critical section guards every label operation. */
    public val storage: IStorage

    public fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
        label: Label,
    ): E =
        storage.criticalSection {
            labelIndex.ensureBuilt { getAllEdges() }
            val edge = getEdge(src, dst, tag) ?: addEdge(src, dst, tag)
            edge.labels = edge.labels + label
            labelIndex.record(label, edge)
            edge
        }

    public fun delEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
        label: Label,
    ): Unit =
        storage.criticalSection {
            // Build before the removal, so a storage revert restoring the label finds it indexed.
            labelIndex.ensureBuilt { getAllEdges() }
            val edge = getEdge(src, dst, tag) ?: return@criticalSection
            // A label the edge does not carry is a no-op: an unlabeled edge must not be deleted.
            if (label !in edge.labels) return@criticalSection
            val remaining = edge.labels - label
            edge.labels = remaining
            if (remaining.isEmpty()) delEdge(src, dst, tag)
        }

    /** Edges carrying [label] directly, whose endpoints belong to this graph — no covering through the poset. */
    public fun getLabeledEdges(label: Label): Sequence<E> =
        storage.criticalSection {
            labelIndex.ensureBuilt { getAllEdges() }
            val (stored, deleted) = labelIndex.candidates(label).partition { it.isStored() }
            for (edge in deleted) labelIndex.drop(label, edge)
            stored.filter { containNode(it.srcNid) && containNode(it.dstNid) && label in it.labels }.asSequence()
        }

    /**
     * Re-records the final labels of the edges [ids] that [resolve] maps to an edge of this graph. A graph
     * that implements this trait calls it from `onRestoredEdges`, so a storage revert that exposes an edge or
     * its labels again leaves [labelIndex] complete.
     */
    public fun restoreLabelIndex(
        ids: Set<Int>,
        resolve: (Int) -> E?,
    ): Unit = storage.criticalSection { labelIndex.restoreEdges(ids, resolve) }

    /** On every edge carrying [from] that satisfies [cond], replaces [from] by [to]. */
    public fun relabel(
        from: Label,
        to: Label,
        cond: (E) -> Boolean = { true },
    ) {
        if (from == to) return
        storage.criticalSection {
            for (edge in getLabeledEdges(from).filter(cond)) {
                edge.labels = edge.labels - from + to
                labelIndex.record(to, edge)
            }
        }
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
            // Seed with the start node so a cycle back to it never yields it as its own descendant.
            val visited = mutableSetOf(of)
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
            // Seed with the start node so a cycle back to it never yields it as its own ancestor.
            val visited = mutableSetOf(of)
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
