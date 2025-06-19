package edu.jhu.cobra.commons.graph.lattice

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.MapVal


/**
 * Defines a partially ordered set (poset) structure for labels used as visibility markers on graph edges.
 * This interface supports label comparison, hierarchical relationship management, and tracking of label
 * changes across edges.
 */
interface ILabelLattice {

    /**
     * The complete set of labels within the lattice, representing different visibility levels on graph edges.
     * Labels are ordered in a hierarchy that allows comparison of their relative positions.
     */
    val allLabels: Set<Label>

    /**
     * Associates each `Label` with its parent labels, representing its immediate hierarchical dependencies.
     * The map's keys indicate the relationship type, while the values specify the parent labels, forming
     * the basis of the label's position within the lattice.
     */
    var Label.parents: Map<String, Label>

    /**
     * Provides a sequence of all ancestor labels for a `Label`, traversing upwards through the parent hierarchy.
     * Ancestors are all labels in the lineage that define the hierarchical path from this label to the top level.
     */
    val Label.ancestors: Sequence<Label>

    /**
     * Records changes associated with a label across the graph's edges. Any time a label is added or removed
     * from an edge, the edge's ID is added to this set. This allows efficient tracking of visibility changes for each label.
     */
    var Label.changes: Set<EdgeID>

    /**
     * Retrieves or assigns a set of labels to an `AbcEdge`, representing the visibility markers applied to that edge.
     * The labels can be modified directly to update the visibility levels associated with the edge.
     */
    var AbcEdge.labels: Set<Label>

    /**
     * Compares this label with another in terms of their positions in the lattice hierarchy.
     *
     * @param other The label to compare against.
     * @return An [Int?] representing the comparison result:
     * - `0` if the labels are equivalent.
     * - A positive value if this label is higher in the hierarchy than [other].
     * - A negative value if this label is lower than [other].
     * - `null` if the labels are not comparable within the hierarchy.
     */
    fun Label.compareTo(other: Label): Int?

    /**
     * Serializes the lattice's current structure, including label relationships and edge associations,
     * into a [MapVal] format for storage.
     *
     * @param into The [IStorage] instance where the serialized lattice data will be saved.
     */
    fun storeLattice(into: IStorage)

    /**
     * Restores a lattice structure from its serialized [MapVal] format, reestablishing the label hierarchy
     * and edge associations.
     *
     * @param from The [IStorage] instance containing the lattice data to load.
     */
    fun loadLattice(from: IStorage)
}
