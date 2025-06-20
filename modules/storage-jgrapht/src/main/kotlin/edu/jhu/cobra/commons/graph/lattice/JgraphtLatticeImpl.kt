package edu.jhu.cobra.commons.graph.lattice

import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.SimpleDirectedGraph
import java.util.*

/**
 * A lattice implementation using the JGraphT library to manage labels and their hierarchical relationships
 * within a directed graph structure. `JgraphtLatticeImpl` enables complex relationship handling through
 * directed edges and supports multi-parent hierarchies.
 *
 * Key features include:
 * - **Graph-Based Hierarchy Management**: Utilizes `SimpleDirectedGraph` from JGraphT to maintain and manage
 *   labels and their relationships, allowing flexible, graph-based control over label hierarchies.
 * - **Named Edges for Relationship Types**: Each edge has an associated name to represent the specific relationship
 *   type between parent and child labels, making it suitable for scenarios where labeled dependencies are essential.
 * - **Immutable Parent Relationships**: Enforces immutability for parent relationships once established, ensuring
 *   consistency in hierarchical dependencies and preventing accidental modifications.
 * - **Ancestor Traversal**: Retrieves ancestors of a label using a depth-first approach, iterating through all
 *   incoming edges and enabling efficient navigation through multi-parent hierarchies.
 */
class JgraphtLatticeImpl : AbcBasicLabelLattice() {

    private data class NamedEdge(val src: String, val dst: String, val name: String) : DefaultEdge()

    private val lattice = SimpleDirectedGraph<Label, NamedEdge>(NamedEdge::class.java)

    override val allLabels: Set<Label> get() = lattice.vertexSet() + Label.INFIMUM + Label.SUPREMUM

    override var Label.parents: Map<String, Label>
        get() {
            return if (!lattice.containsVertex(this)) emptyMap()
            else lattice.incomingEdgesOf(this).associate { it.name to Label(it.src) }
        }
        set(value) {
            if (!lattice.containsVertex(this)) lattice.addVertex(this)
            val prev = lattice.incomingEdgesOf(this).associate { it.name to Label(it.src) }
            if (prev.isNotEmpty() && value != prev) throw IllegalArgumentException("parent of $this is immutable")
            val valid = value.filter { it.value != Label.INFIMUM && it.value != Label.SUPREMUM }
            valid.forEach { (name, parent) ->
                if (!lattice.containsVertex(parent)) lattice.addVertex(parent)
                val edge = NamedEdge(src = parent.core, dst = core, name = name)
                lattice.addEdge(parent, this, edge)
            }
        }

    override val Label.ancestors: Sequence<Label> get() = getAncestor(this)

    private fun getAncestor(of: Label) = sequence {
        val stack = LinkedList<Label>().apply { add(of) }
        while (stack.isNotEmpty()) {
            val curLabel = stack.removeFirst()
            if (!lattice.containsVertex(curLabel)) continue
            val inEdges = lattice.incomingEdgesOf(curLabel)
            val namedParents = inEdges.map { Label(it.src) }
            namedParents.forEach { yield(it); stack.add(it) }
        }
    }
}