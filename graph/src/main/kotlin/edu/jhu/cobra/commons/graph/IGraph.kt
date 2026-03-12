package edu.jhu.cobra.commons.graph

/**
 * Core graph interface with label-based edge visibility.
 *
 * Label-parameterized methods filter edges by visibility: an edge is visitable
 * under label `by` if at least one of its labels `l` satisfies `by == l` or
 * `by > l` in the poset hierarchy.
 *
 * Non-label overloads default to [Label.SUPREMUM], which sees all edges.
 *
 * @param N The node type.
 * @param E The edge type.
 */
@Suppress("TooManyFunctions")
interface IGraph<N : AbcNode, E : AbcEdge> {
    val nodeIDs: Set<NodeID>

    val edgeIDs: Set<EdgeID>

    // region Node CRUD

    fun addNode(withID: NodeID): N

    fun getNode(whoseID: NodeID): N?

    fun containNode(whoseID: NodeID): Boolean

    fun delNode(whoseID: NodeID)

    fun getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>

    // endregion

    // region Edge CRUD

    fun addEdge(withID: EdgeID): E

    fun addEdge(
        withID: EdgeID,
        label: Label,
    ): E

    fun getEdge(whoseID: EdgeID): E?

    fun containEdge(whoseID: EdgeID): Boolean

    fun delEdge(whoseID: EdgeID)

    fun delEdge(
        whoseID: EdgeID,
        label: Label,
    )

    // endregion

    // region Graph structure queries

    fun getAllEdges(doSatfy: (E) -> Boolean = { true }): Sequence<E>

    fun getIncomingEdges(of: NodeID): Sequence<E>

    fun getOutgoingEdges(of: NodeID): Sequence<E>

    fun getOutgoingEdges(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<E>

    fun getIncomingEdges(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<E>

    fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getParents(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getChildren(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getParents(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getAncestors(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getDescendants(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N>

    fun getAncestors(
        of: NodeID,
        label: Label,
        cond: (E) -> Boolean = { true },
    ): Sequence<N>

    // endregion
}
