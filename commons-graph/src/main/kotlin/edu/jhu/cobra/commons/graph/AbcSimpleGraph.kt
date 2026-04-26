package edu.jhu.cobra.commons.graph

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
}
