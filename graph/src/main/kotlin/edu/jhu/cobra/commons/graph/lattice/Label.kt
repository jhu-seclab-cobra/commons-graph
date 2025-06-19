package edu.jhu.cobra.commons.graph.lattice

import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.value.StrVal
import java.util.*

/**
 * Data class representing a label that acts as a visibility modifier on edges in a graph.
 * Mathematically, a label is part of a partially ordered set (poset), where labels can be compared
 * to establish an order between them. These labels help define relationships between edges in a
 * graph based on their visibility.
 *
 * @property core The core string representation of the label.
 */
data class Label(val core: String) {

    constructor(strVal: StrVal) : this(strVal.core)

    companion object {

        /**
         * the `infimum` label in the poset is the GLB (Greatest Lower Bound) of all elements
         */
        val INFIMUM: Label = Label("infimum")

        /**
         * the `supremum` label in the poset is the LUB (Least Upper Bound) of all elements
         */
        val SUPREMUM: Label = Label("supremum")
    }

}

context(ILabelLattice)
fun Label.isGreaterThan(vararg anyOneOf: Label) =
    anyOneOf.any { oth -> compareTo(oth)?.let { it > 0 } ?: false }

context(ILabelLattice)
fun Label.isGreaterThan(anyOneOf: Collection<Label>) =
    anyOneOf.any { oth -> compareTo(oth)?.let { it > 0 } ?: false }

context(ILabelLattice)
private fun <E : AbcEdge> Sequence<E>.filterVisitable(by: Label): Sequence<E> {
    if (by == Label.SUPREMUM) return this // no need to filter it is the supremum case
    val allEdges = this.toSet() // convert it into edge sets to avoid the duplicate iteration
    // It means that all those visitable labels are under the `by` label
    val allVisitable = allEdges.flatMap { e -> e.labels.filter { by == it || by.isGreaterThan(it) } }.toSet()
    // Need to filter out those labels which are covered by the new label
    val allNotCovered = allVisitable.filter { cur -> !allVisitable.any { it.isGreaterThan(cur) } }
    // return the edge with label not covered as sequence
    return allEdges.filter { edge -> edge.labels.any { it in allNotCovered } }.asSequence()
}

/**
 * Adds a directed edge between two nodes with the specified type and label.
 * The label is associated with the edge, and the edge's ID is recorded as a change for the label.
 *
 * @param from The source node from which the edge starts.
 * @param to The destination node to which the edge points.
 * @param type The type of the edge, typically a string representing the edge's type.
 * @param label The label to be associated with the edge.
 * @return The newly created edge of type [E].
 */
context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.addEdge(from: AbcNode, to: AbcNode, type: String, label: Label): E {
    val rawEdge = getEdge(from, to, type) ?: addEdge(from, to, type)
//    if (label in rawEdge.labels) throw EntityAlreadyExistException(rawEdge.id)
    return rawEdge.also { it.labels += label }
}

context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getEdge(from: AbcNode, to: AbcNode, type: String, label: Label): E? =
    getEdge(from, to, type)?.takeIf { e -> label in e.labels }

/**
 * Removes a label from the specified edge. If the edge has no more labels after the removal, the edge is deleted from the graph.
 *
 * @param target The edge from which the label should be removed.
 * @param label The label to be removed from the edge.
 */
context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.delEdge(target: E, label: Label) {
    target.labels -= label // remove this labels
    if (target.labels.isEmpty()) delEdge(target)
}

/**
 * Retrieves the outgoing edges from a given node that are filtered by the specified label and an additional condition.
 *
 * @param of The node from which the outgoing edges are retrieved.
 * @param label The label used to filter the outgoing edges.
 * @param cond An additional condition to further filter the edges. Defaults to always true.
 * @return A sequence of outgoing edges from the node that satisfy the label and condition.
 */
context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getOutgoingEdges(
    of: AbcNode, label: Label, cond: (E) -> Boolean = { true }
) = getOutgoingEdges(of).filter(cond).filterVisitable(label)

/**
 * Retrieves the incoming edges to a given node that are filtered by the specified label and an additional condition.
 *
 * @param of The node to which the incoming edges are retrieved.
 * @param label The label used to filter the incoming edges.
 * @param cond An additional condition to further filter the edges. Defaults to always true.
 * @return A sequence of incoming edges to the node that satisfy the label and condition.
 */
context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getIncomingEdges(
    of: AbcNode, label: Label, cond: (E) -> Boolean = { true }
) = getIncomingEdges(of).filter(cond).filterVisitable(label)

/**
 * Retrieves the child nodes of a given node that are connected by edges that are filtered by the specified label and an additional condition.
 *
 * @param of The node whose child nodes are being retrieved.
 * @param label The label used to filter the outgoing edges.
 * @param cond An additional condition to further filter the edges. Defaults to always true.
 * @return A sequence of child nodes connected to the given node by edges satisfying the label and condition.
 */
context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getChildren(
    of: AbcNode, label: Label, cond: (E) -> Boolean = { true }
) = getOutgoingEdges(of, label, cond).mapNotNull { getNode(it.dstNid) }

/**
 * Retrieves the parent nodes of a given node that are connected by edges that are filtered by the specified label and an additional condition.
 *
 * @param of The node whose parent nodes are being retrieved.
 * @param label The label used to filter the incoming edges.
 * @param cond An additional condition to further filter the edges. Defaults to always true.
 * @return A sequence of parent nodes connected to the given node by edges satisfying the label and condition.
 */
context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getParents(
    of: AbcNode, label: Label, cond: (E) -> Boolean = { true }
) = getIncomingEdges(of, label, cond).mapNotNull { getNode(it.srcNid) }

/**
 * Retrieves all the descendant nodes of a given node that are connected by visitable edges based on the specified label and condition.
 *
 * This function performs a depth-first traversal of the graph starting from the given node to gather all reachable descendants.
 *
 * @param of The node whose descendants are being retrieved.
 * @param label The label used to filter the outgoing edges.
 * @param cond An additional condition to further filter the edges. Defaults to always true.
 * @return A sequence of descendant nodes connected to the given node.
 */
context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getDescendants(
    of: AbcNode, label: Label, cond: (E) -> Boolean = { true }
) = sequence {
    val recorder = mutableSetOf<AbcNode>()
    val execStack = LinkedList<AbcNode>().apply { add(of) }
    while (execStack.any()) {
        val curNode = execStack.removeFirst()
        val children = getChildren(curNode, label, cond)
        val unvisited = children.filter(recorder::add)
        unvisited.forEach { yield(it); execStack.add(it) }
    }
}

/**
 * Retrieves all the ancestor nodes of a given node that are connected by visitable edges based on the specified label and condition.
 *
 * This function performs a depth-first traversal of the graph starting from the given node to gather all reachable ancestors.
 *
 * @param of The node whose ancestors are being retrieved.
 * @param label The label used to filter the incoming edges.
 * @param cond An additional condition to further filter the edges. Defaults to always true.
 * @return A sequence of ancestor nodes connected to the given node.
 */
context(ILabelLattice)
fun <N : AbcNode, E : AbcEdge> IGraph<N, E>.getAncestors(
    of: AbcNode, label: Label, cond: (E) -> Boolean = { true }
) = sequence {
    val recorder = mutableSetOf<AbcNode>()
    val execStack = LinkedList<AbcNode>().apply { add(of) }
    while (execStack.any()) {
        val curNode = execStack.removeFirst()
        val parents = getParents(curNode, label, cond)
        val unvisited = parents.filter(recorder::add)
        unvisited.forEach { yield(it); execStack.add(it) }
    }
}
