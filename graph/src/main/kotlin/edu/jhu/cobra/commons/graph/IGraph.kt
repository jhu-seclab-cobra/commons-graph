package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage

/**
 * Core graph interface with integrated label-based edge visibility.
 *
 * Labels form a partially ordered set (poset) for controlling edge visibility.
 * Label-parameterized methods filter edges by visibility: an edge is visitable
 * under label `by` if at least one of its labels `l` satisfies `by == l` or
 * `by > l` in the lattice hierarchy.
 *
 * Non-label overloads default to [Label.SUPREMUM], which sees all edges.
 *
 * @param N The node type.
 * @param E The edge type.
 */
interface IGraph<N : AbcNode, E : AbcEdge> {

    val nodeIDs: Set<NodeID>

    val edgeIDs: Set<EdgeID>

    // region Label lattice

    /** All labels in the lattice, including [Label.INFIMUM] and [Label.SUPREMUM]. */
    val allLabels: Set<Label>

    /** Named parent labels forming the basis of a label's position in the lattice. */
    var Label.parents: Map<String, Label>

    /** All ancestor labels traversing upwards through the parent hierarchy. */
    val Label.ancestors: Sequence<Label>

    /** Edge IDs whose label set was modified involving this label. */
    var Label.changes: Set<EdgeID>

    /** Visibility labels assigned to an edge. */
    var AbcEdge.labels: Set<Label>

    /**
     * Compares this label with another in the lattice hierarchy.
     *
     * @param other The label to compare against.
     * @return Positive if this > other, negative if this < other, 0 if equal, null if incomparable.
     */
    fun Label.compareTo(other: Label): Int?

    /** Serializes lattice structure and change records into storage metadata. */
    fun storeLattice(into: IStorage)

    /** Restores lattice structure and change records from storage metadata. */
    fun loadLattice(from: IStorage)

    // endregion

    // region Node CRUD

    fun addNode(withID: NodeID): N

    fun getNode(whoseID: NodeID): N?

    fun containNode(whoseID: NodeID): Boolean

    fun delNode(whoseID: NodeID)

    fun getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>

    // endregion

    // region Edge CRUD

    fun addEdge(withID: EdgeID): E

    fun addEdge(withID: EdgeID, label: Label): E

    fun getEdge(whoseID: EdgeID): E?

    fun containEdge(whoseID: EdgeID): Boolean

    fun delEdge(whoseID: EdgeID)

    fun delEdge(whoseID: EdgeID, label: Label)

    // endregion

    // region Graph structure queries

    fun getAllEdges(doSatfy: (E) -> Boolean = { true }): Sequence<E>

    fun getIncomingEdges(of: NodeID): Sequence<E>

    fun getOutgoingEdges(of: NodeID): Sequence<E>

    fun getOutgoingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>

    fun getIncomingEdges(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<E>

    fun getChildren(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    fun getParents(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    fun getChildren(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>

    fun getParents(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>

    fun getDescendants(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    fun getAncestors(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    fun getDescendants(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>

    fun getAncestors(of: NodeID, label: Label, cond: (E) -> Boolean = { true }): Sequence<N>

    // endregion
}
