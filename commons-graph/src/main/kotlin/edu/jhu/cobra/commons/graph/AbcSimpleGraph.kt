package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.criticalSection

/**
 * Abstract simple directed graph where at most one edge exists between any
 * two nodes in a given direction.
 *
 * @param N The node type.
 * @param E The edge type.
 */
public abstract class AbcSimpleGraph<N : AbcNode, E : AbcEdge> : AbcMultipleGraph<N, E>() {
    override fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E =
        storage.criticalSection {
            val existing = getOutgoingEdges(src).any { it.dstNid == dst }
            if (existing) throw EntityAlreadyExistException("$src->$dst")
            super.addEdge(src, dst, tag)
        }

    /** The pair key: at most one edge joins a source and a destination, whatever the tag. */
    override fun edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>> = mapOf(PAIR_KEY to listOf(edge.src, edge.dst))

    private companion object {
        // Name of the unique-key declaration that limits a source and a destination to one edge.
        const val PAIR_KEY = "pair"
    }
}
