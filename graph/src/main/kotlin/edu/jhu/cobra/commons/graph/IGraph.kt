package edu.jhu.cobra.commons.graph

/**
 * Core directed graph interface.
 *
 * Edges are identified by their `(src, dst, type)` triple.
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

    fun getNode(whoseID: NodeID): N?

    fun containNode(whoseID: NodeID): Boolean

    fun delNode(whoseID: NodeID)

    fun getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>

    // endregion

    // region Edge CRUD

    fun addEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
    ): E

    fun getEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
    ): E?

    fun containEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
    ): Boolean

    fun delEdge(
        src: NodeID,
        dst: NodeID,
        type: String,
    )

    fun getAllEdges(doSatfy: (E) -> Boolean = { true }): Sequence<E>

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
