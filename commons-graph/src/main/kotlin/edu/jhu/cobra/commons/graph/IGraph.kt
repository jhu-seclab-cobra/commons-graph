package edu.jhu.cobra.commons.graph

/**
 * Core directed graph interface.
 *
 * Edges are identified by their `(src, dst, tag)` triple.
 * Label-aware operations (visibility filtering, label assignment) are provided
 * by [PosetTrait][edu.jhu.cobra.commons.graph.poset.PosetTrait].
 *
 * @param N The node type.
 * @param E The edge type.
 */
@Suppress("TooManyFunctions")
public interface IGraph<N : AbcNode, E : AbcEdge> {
    public val nodeIDs: Set<NodeID>

    // region Node CRUD

    public fun addNode(withID: NodeID): N

    public fun claimNode(from: AbcNode): N

    public fun getNode(whoseID: NodeID): N?

    public fun containNode(whoseID: NodeID): Boolean

    public fun delNode(whoseID: NodeID)

    public fun getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>

    // endregion

    // region Edge CRUD

    public fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E

    public fun getEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E?

    public fun containEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): Boolean

    public fun delEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    )

    public fun getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>

    // endregion

    // region Graph structure queries

    public fun getIncomingEdges(of: NodeID): Sequence<E>

    public fun getOutgoingEdges(of: NodeID): Sequence<E>

    public fun getChildren(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    public fun getParents(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    public fun getDescendants(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    public fun getAncestors(
        of: NodeID,
        edgeCond: (E) -> Boolean = { true },
    ): Sequence<N>

    // endregion
}
