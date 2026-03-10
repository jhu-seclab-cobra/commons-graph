package edu.jhu.cobra.commons.graph

/**
 * Abstract simple directed graph where at most one edge exists between any
 * two nodes in a given direction.
 *
 * @param N The node type.
 * @param E The edge type.
 */
abstract class AbcSimpleGraph<N : AbcNode, E : AbcEdge> : AbcMultipleGraph<N, E>() {
    override fun addEdge(withID: EdgeID): E {
        val existing =
            storage
                .getOutgoingEdges(withID.srcNid)
                .any { it.dstNid == withID.dstNid && it in edgeIDs }
        if (existing) throw EntityAlreadyExistException(withID)
        return super.addEdge(withID)
    }
}
