package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.AbcNode
import edu.jhu.cobra.commons.graph.entity.EdgeID
import java.util.*

/**
 * Abstract class representing a multi-graph, where multiple edges between the same pair of nodes are allowed.
 * This class represents a directed multi-graph with each edge having its own identifier.
 * It extends the functionality of [AbcBasicGraph] to support multiple edges between nodes.
 *
 * For more details on the mathematical concept of a directed multi-graph, refer to the
 * [Directed multi-graph](https://en.wikipedia.org/wiki/Multigraph#Directed_multigraph_(edges_with_own_identity)) page.
 *
 * @param N The type of nodes in the graph, which extends [AbcNode].
 * @param E The type of edges in the graph, which extends [AbcEdge].
 * @param nType The class type of the nodes, can be `null` by default.
 */
abstract class AbcMultiGraph<N : AbcNode, E : AbcEdge>(nType: Class<N>? = null) : AbcBasicGraph<N, E>(nType) {

    /**
     * Adds a directed edge between two nodes with the specified type.
     * The edge type is automatically prefixed with the graph name (e.g., "MyGraph:relation").
     * Non-existent nodes are automatically wrapped.
     *
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @param type The type of the edge, which will be prefixed with the graph name.
     * @return The newly created edge of type [E].
     * @throws EntityAlreadyExistException if an edge with the same ID already exists.
     */
    override fun addEdge(from: AbcNode, to: AbcNode, type: String): E {
        val edgeID = EdgeID(from.id, to.id, eType = "$graphName:$type")
        if (edgeID in cacheEIDs) throw EntityAlreadyExistException(edgeID)
        sequenceOf(from, to).forEach { if (it.id !in cacheNIDs) wrapNode(node = it) }
        if (!storage.containsEdge(id = edgeID)) storage.addEdge(id = edgeID)
        return newEdgeObj(edgeID.also { cacheEIDs.add(element = it) })
    }

    /**
     * Retrieves an edge between two nodes with the specified type.
     * The edge type must include the graph name prefix (e.g., "MyGraph:relation").
     *
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @param type The type of the edge, which should include the graph name prefix.
     * @return The edge if it exists, `null` otherwise.
     */
    override fun getEdge(from: AbcNode, to: AbcNode, type: String): E? {
        val edgeID = EdgeID(srcNid = from.id, dstNid = to.id, "$graphName:$type")
        if (edgeID !in cacheEIDs || !storage.containsEdge(edgeID)) return null
        return newEdgeObj(eid = edgeID)
    }

    /**
     * Adds a directed edge between two nodes with a randomly generated UUID as the edge type.
     * The UUID is automatically prefixed with the graph name.
     * 
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @return The newly created edge of type [E].
     */
    fun addEdge(from: AbcNode, to: AbcNode): E = addEdge(from = from, to = to, type = UUID.randomUUID().toString())

    /**
     * Retrieves all edges between two nodes, regardless of their types.
     * 
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @return A sequence of edges of type [E] between the specified nodes.
     */
    fun getEdges(from: AbcNode, to: AbcNode): Sequence<E> =
        storage.getEdgesBetween(from.id, to.id).asSequence().filter { it in cacheEIDs }.map(::newEdgeObj)
}
