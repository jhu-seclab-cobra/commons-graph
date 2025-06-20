package edu.jhu.cobra.commons.graph.lattice

import java.util.*

/**
 * A concrete implementation of a label lattice that maintains hierarchical relationships between labels.
 * `DefaultLatticeImpl` defines each label's parent relationships and provides efficient traversal methods
 * for navigating ancestors in the lattice structure.
 *
 * Key features include:
 * - **Dynamic Label Hierarchy**: Supports assigning multiple parents to each label, constructing a flexible
 *   and configurable lattice where hierarchical relationships are defined dynamically.
 * - **Ancestor Traversal**: Efficiently retrieves all ancestors of a label, with cycle prevention, allowing
 *   for accurate hierarchy exploration even in complex or cyclic configurations.
 *
 * This implementation is suitable for use cases requiring runtime definition of label hierarchies
 * and traversal across multi-parent structures in a partially ordered set.
 */
class DefaultLatticeImpl : AbcBasicLabelLattice() {

    private val prevLattice = mutableMapOf<Label, MutableMap<String, Label>>()

    override val allLabels: Set<Label> get() = prevLattice.keys + Label.INFIMUM + Label.SUPREMUM

    override var Label.parents: Map<String, Label>
        get() = prevLattice[this].orEmpty()
        set(value) {
            prevLattice[this] = value.toMutableMap()
        }

    override val Label.ancestors
        get(): Sequence<Label> = sequence {
            val visited = mutableSetOf<Label>()
            val stack = LinkedList<Label>().also { it.add(this@ancestors) }
            while (stack.isNotEmpty()) {
                val current = stack.removeFirst()
                if (current in visited) continue
                visited.add(current)
                val parents = current.parents.values
                yieldAll(elements = parents)
                stack.addAll(elements = parents)
            }
        }
}