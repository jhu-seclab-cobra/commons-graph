package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.poset.Label

/**
 * Abstract simple directed graph where at most one edge exists between any
 * two nodes in a given direction.
 *
 * @param N The node type.
 * @param E The edge type.
 */
abstract class AbcSimpleGraph<N : AbcNode, E : AbcEdge> : AbcMultipleGraph<N, E>() {
    override fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E {
        val existing = getOutgoingEdges(src).any { it.dstNid == dst }
        if (existing) throw EntityAlreadyExistException("$src->$dst")
        return super.addEdge(src, dst, tag)
    }

    override fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
        label: Label,
    ): E {
        val existing = getOutgoingEdges(src).any { it.dstNid == dst }
        if (existing && getEdge(src, dst, tag) == null) {
            throw EntityAlreadyExistException("$src->$dst")
        }
        return super.addEdge(src, dst, tag, label)
    }
}
