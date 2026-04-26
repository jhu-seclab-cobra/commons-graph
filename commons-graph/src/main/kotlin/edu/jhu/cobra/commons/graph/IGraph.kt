package edu.jhu.cobra.commons.graph

/**
 * Core directed graph interface.
 *
 * Edges are identified by their `(src, dst, tag)` triple.
 * Label-aware operations (visibility filtering, label assignment) are provided
 * by [AbcMultipleGraph], which combines this interface with [IPoset][edu.jhu.cobra.commons.graph.poset.IPoset].
 *
 * @param N The node type.
 * @param E The edge type.
 */
@Suppress("TooManyFunctions")
interface IGraph<N : AbcNode, E : AbcEdge> {
    val nodeIDs: Set<NodeID>

    // region Node CRUD

    fun addNode(withID: NodeID): N

    fun claimNode(from: AbcNode): N

    fun getNode(whoseID: NodeID): N?

    fun containNode(whoseID: NodeID): Boolean

    fun delNode(whoseID: NodeID)

    fun getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>

    // endregion

    // region Edge CRUD

    fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E

    fun getEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E?

    fun containEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Boolean

    fun delEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    )

    fun getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>

    // endregion

    // region Graph structure queries

    fun getIncomingEdges(of: NodeID): Sequence<E>

    fun getOutgoingEdges(of: NodeID): Sequence<E>

    fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getParents(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getAncestors(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    // endregion
}
