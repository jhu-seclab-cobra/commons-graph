package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.AbcNode
import edu.jhu.cobra.commons.graph.entity.EdgeID

/**
 * An abstract implementation of a simple directed graph where nodes are of type [N] and edges are of type [E].
 * This class extends [AbcBasicGraph] and provides functionality to work with directed simple graphs that have
 * edge identifiers. A simple directed graph is a graph where there is at most one edge between any two nodes
 * in a given direction (ignoring edge types).
 *
 * For more details on the concept of a directed graph, you can refer to the
 * [Directed Graph](https://en.wikipedia.org/wiki/Directed_graph) page.
 *
 * @param N The type of nodes in the graph, which extends [AbcNode].
 * @param E The type of edges in the graph, which extends [AbcEdge].
 * @param nType The class type of the nodes, can be `null` by default.
 */
abstract class AbcSimpleGraph<N : AbcNode, E : AbcEdge>(nType: Class<N>? = null) : AbcBasicGraph<N, E>(nType) {

    /**
     * Adds a directed edge between two nodes with the specified type.
     * Only one edge is allowed between any two nodes in a given direction.
     * The edge type is automatically prefixed with the graph name.
     *
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @param type The type of the edge, which will be prefixed with the graph name.
     * @return The newly created edge of type [E].
     * @throws EntityAlreadyExistException if an edge already exists between the specified nodes.
     */
    override fun addEdge(from: AbcNode, to: AbcNode, type: String): E {
        val allEdges = storage.getEdgesBetween(from = from.id, to = to.id)
        val prevEdge = allEdges.firstOrNull { edgeID -> edgeID in cacheEIDs }
        if (prevEdge != null) throw EntityAlreadyExistException(id = prevEdge)
        val edgeID = EdgeID(from.id, to.id, eType = "$graphName:$type")
        if (edgeID in cacheEIDs) throw EntityAlreadyExistException(edgeID)
        sequenceOf(from, to).forEach { if (it.id !in cacheNIDs) wrapNode(it) }
        if (!storage.containsEdge(edgeID)) storage.addEdge(id = edgeID)
        return newEdgeObj(edgeID.also { cacheEIDs.add(it) })
    }

    /**
     * Retrieves an edge between two nodes with the specified type.
     * The edge type must include the graph name prefix.
     *
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @param type The type of the edge, which should include the graph name prefix.
     * @return The edge if it exists, `null` otherwise.
     */
    override fun getEdge(from: AbcNode, to: AbcNode, type: String): E? {
        val edgeID = EdgeID(from.id, to.id, eType = "$graphName:$type")
        if (edgeID !in cacheEIDs || !storage.containsEdge(edgeID)) return null
        return newEdgeObj(eid = edgeID)
    }

    /**
     * Adds a directed edge between two nodes without specifying an edge type.
     * This is a shorthand for calling [addEdge] with an empty type string.
     *
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @return The newly created edge of type [E].
     * @throws EntityAlreadyExistException If an edge already exists between the specified nodes.
     */
    fun addEdge(from: AbcNode, to: AbcNode): E = addEdge(from = from, to = to, type = "")

    /**
     * Retrieves a directed edge between two nodes without considering the edge type.
     * If no edge exists between the specified nodes, this method returns `null`.
     * This method checks if there is a single edge between the two nodes and returns it.
     *
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @return The edge of type [E] if it exists, or `null` if no such edge exists.
     */
    fun getEdge(from: AbcNode, to: AbcNode): E? {
        val allEdges = storage.getEdgesBetween(from.id, to.id)
        val prevEdge = allEdges.singleOrNull { it in cacheEIDs }
        return prevEdge?.let { newEdgeObj(eid = it) }
    }
}
