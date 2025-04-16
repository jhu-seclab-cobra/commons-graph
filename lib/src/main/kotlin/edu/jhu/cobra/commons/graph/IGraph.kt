package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.entity.AbcEdge
import edu.jhu.cobra.commons.graph.entity.AbcNode
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID

/**
 * Interface representing a generic graph structure where nodes and edges are modeled by the types [N] and [E],
 * respectively. This interface provides operations to manipulate and query the graph, including adding/removing
 * nodes and edges, retrieving related nodes, and traversing the graph.
 *
 * @param N The type of nodes in the graph, which must extend [AbcNode].
 * @param E The type of edges in the graph, which must extend [AbcEdge].
 */
interface IGraph<N : AbcNode, E : AbcEdge> {

    val graphName: String

    /**
     * The number of entities (nodes and edges) in the graph.
     */
    val entitySize: Int

    /**
     * Checks if the specified node is contained in the graph.
     * @param node The node to check for containment in the graph.
     * @return true if the node is contained in the graph, false otherwise.
     */
    fun containNode(node: AbcNode): Boolean

    /**
     * Checks if the graph contains the specified edge.
     *
     * @param edge The edge to check for existence in the graph.
     * @return true if the edge is present in the graph, false otherwise.
     */
    fun containEdge(edge: AbcEdge): Boolean

    /**
     * Adds a new node to the graph, optionally with a specific node ID.
     *
     * @param whoseID The optional ID of the node to be added. If null, an ID will be automatically assigned.
     * @return The newly added node of type [N].
     * @throws EntityAlreadyExistException If a node with the specified ID already exists.
     */
    fun addNode(whoseID: NodeID): N

    /**
     * Adds a directed edge between two nodes in the graph.
     *
     * @param from The source node from which the edge starts.
     * @param to The target node to which the edge points.
     * @param type The optional type name of the edge
     * @return The newly added edge of type [E].
     * @throws EntityNotExistException If the source or target node does not exist in the graph.
     * @throws EntityAlreadyExistException If an edge of the specified type between the nodes already exists.
     */
    fun addEdge(from: AbcNode, to: AbcNode, type: String): E

    /**
     * Wraps an existing node as type [N].
     *
     * @param node The node to be wrapped.
     * @return The wrapped node of type [N].
     * @throws InvalidPropNameException If the node has an invalid property name.
     */
    fun wrapNode(node: AbcNode): N

    /**
     * Retrieves a node by its ID.
     *
     * @param whoseID The ID of the node to be retrieved.
     * @return The node with the specified ID, or null if no such node exists.
     * @throws EntityNotExistException If a node with the specified ID does not exist.
     */
    fun getNode(whoseID: NodeID): N?

    /**
     * Retrieves an edge by its ID.
     *
     * @param whoseID The ID of the edge to be retrieved.
     * @return The edge corresponding to the specified ID, or null if no such edge exists.
     */
    fun getEdge(whoseID: EdgeID): E?

    /**
     * Retrieves an edge between two nodes with a specific type.
     *
     * @param from The source node of the edge.
     * @param to The target node of the edge.
     * @param type The type of the edge to be retrieved.
     * @return The edge that matches the specified criteria, or null if no such edge exists.
     * @throws EntityNotExistException If the source node, target node, or edge does not exist.
     */
    fun getEdge(from: AbcNode, to: AbcNode, type: String): E?

    /**
     * Retrieves all nodes that satisfy a given condition.
     *
     * @param doSatisfy A predicate function to filter nodes. By default, it returns all nodes.
     * @return A sequence of nodes that satisfy the given condition.
     */
    fun getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>

    /**
     * Retrieves all edges that satisfy a given condition.
     *
     * @param doSatisfy A predicate function to filter edges. By default, it returns all edges.
     * @return A sequence of edges that satisfy the given condition.
     */
    fun getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>

    /**
     * Removes a node from the graph.
     *
     * @param node The node to be deleted.
     * @throws EntityNotExistException If the node does not exist in the graph.
     */
    fun delNode(node: N)

    /**
     * Removes an edge from the graph.
     *
     * @param edge The edge to be deleted.
     * @throws EntityNotExistException If the edge does not exist in the graph.
     */
    fun delEdge(edge: E)

    /**
     * Retrieves all incoming edges for a given node.
     *
     * @param of The node for which incoming edges should be retrieved.
     * @return A sequence of incoming edges for the specified node.
     * @throws EntityNotExistException If the node does not exist in the graph.
     */
    fun getIncomingEdges(of: AbcNode): Sequence<E>

    /**
     * Retrieves all outgoing edges for a given node.
     *
     * @param of The node for which outgoing edges should be retrieved.
     * @return A sequence of outgoing edges for the specified node.
     * @throws EntityNotExistException If the node does not exist in the graph.
     */
    fun getOutgoingEdges(of: AbcNode): Sequence<E>

    /**
     * Retrieves all child nodes of a given node, based on edge conditions.
     *
     * @param of The node for which child nodes should be retrieved.
     * @param edgeCond A predicate function to filter edges. By default, it considers all edges.
     * @return A sequence of child nodes connected to the specified node.
     * @throws EntityNotExistException If the node does not exist in the graph.
     */
    fun getChildren(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    /**
     * Retrieves all parent nodes of a given node, based on edge conditions.
     *
     * @param of The node for which parent nodes should be retrieved.
     * @param edgeCond A predicate function to filter edges. By default, it considers all edges.
     * @return A sequence of parent nodes connected to the specified node.
     * @throws EntityNotExistException If the node does not exist in the graph.
     */
    fun getParents(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    /**
     * Retrieves all descendant nodes of a given node, based on edge conditions.
     *
     * @param of The node for which descendant nodes should be retrieved.
     * @param edgeCond A predicate function to filter edges. By default, it considers all edges.
     * @return A sequence of descendant nodes connected to the specified node.
     * @throws EntityNotExistException If the node does not exist in the graph.
     */
    fun getDescendants(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    /**
     * Retrieves all ancestor nodes of a given node, based on edge conditions.
     *
     * @param of The node for which ancestor nodes should be retrieved.
     * @param edgeCond A predicate function to filter edges. By default, it considers all edges.
     * @return A sequence of ancestor nodes connected to the specified node.
     * @throws EntityNotExistException If the node does not exist in the graph.
     */
    fun getAncestors(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>
}
