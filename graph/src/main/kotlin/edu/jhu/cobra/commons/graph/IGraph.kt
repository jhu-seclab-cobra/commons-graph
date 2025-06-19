package edu.jhu.cobra.commons.graph

/**
 * Interface representing a generic graph structure where nodes and edges are modeled by the types [N] and [E],
 * respectively. This interface provides operations to manipulate and query the graph, including adding/removing
 * nodes and edges, retrieving related nodes, and traversing the graph.
 *
 * @param N The type of nodes in the graph, which must extend [AbcNode].
 * @param E The type of edges in the graph, which must extend [AbcEdge].
 */
interface IGraph<N : AbcNode, E : AbcEdge> {

    /**
     * The name of the graph.
     */
    val graphName: String

    /**
     * The total number of entities (nodes and edges) in the graph.
     */
    val entitySize: Int

    /**
     * Checks if the specified node is contained in the graph.
     *
     * @param node The node to check for containment in the graph.
     * @return `true` if the node is contained in the graph, `false` otherwise.
     */
    fun containNode(node: AbcNode): Boolean

    /**
     * Checks if the graph contains the specified edge.
     *
     * @param edge The edge to check for existence in the graph.
     * @return `true` if the edge is present in the graph, `false` otherwise.
     */
    fun containEdge(edge: AbcEdge): Boolean

    /**
     * Adds a new node to the graph with the specified identifier.
     *
     * @param whoseID The identifier of the node to be added.
     * @return The newly added node of type [N].
     * @throws EntityAlreadyExistException If a node with the specified identifier already exists.
     */
    fun addNode(whoseID: NodeID): N

    /**
     * Adds a directed edge between two nodes in the graph.
     *
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @param type The type of the edge.
     * @return The newly added edge of type [E].
     * @throws EntityNotExistException If the source or destination node does not exist in the graph.
     * @throws EntityAlreadyExistException If an edge of the specified type between the nodes already exists.
     */
    fun addEdge(from: AbcNode, to: AbcNode, type: String): E

    /**
     * Wraps a generic [AbcNode] into its specific graph-context type [N].
     *
     * @param node The generic node to be wrapped.
     * @return The node converted to the specific type [N].
     * @throws EntityNotExistException If the node does not exist in the graph.
     */
    fun wrapNode(node: AbcNode): N

    /**
     * Retrieves a node from the graph based on its identifier.
     *
     * @param whoseID The identifier of the node to retrieve.
     * @return The node if it exists, `null` otherwise.
     */
    fun getNode(whoseID: NodeID): N?

    /**
     * Retrieves an edge from the graph based on its identifier.
     *
     * @param whoseID The identifier of the edge to retrieve.
     * @return The edge if it exists, `null` otherwise.
     */
    fun getEdge(whoseID: EdgeID): E?

    /**
     * Retrieves an edge between two nodes with the specified type.
     *
     * @param from The source node from which the edge starts.
     * @param to The destination node to which the edge points.
     * @param type The type of the edge.
     * @return The edge if it exists, `null` otherwise.
     */
    fun getEdge(from: AbcNode, to: AbcNode, type: String): E?

    /**
     * Retrieves all nodes in the graph that satisfy the given predicate.
     *
     * @param doSatisfy The predicate to filter nodes.
     * @return A sequence of nodes that satisfy the predicate.
     */
    fun getAllNodes(doSatisfy: (N) -> Boolean = { true }): Sequence<N>

    /**
     * Retrieves all edges in the graph that satisfy the given predicate.
     *
     * @param doSatisfy The predicate to filter edges.
     * @return A sequence of edges that satisfy the predicate.
     */
    fun getAllEdges(doSatisfy: (E) -> Boolean = { true }): Sequence<E>

    /**
     * Deletes a node and all its associated edges from the graph.
     *
     * @param node The node to be deleted.
     */
    fun delNode(node: N)

    /**
     * Deletes an edge from the graph.
     *
     * @param edge The edge to be deleted.
     */
    fun delEdge(edge: E)

    /**
     * Retrieves all incoming edges to the specified node.
     *
     * @param of The node whose incoming edges are to be retrieved.
     * @return A sequence of incoming edges.
     */
    fun getIncomingEdges(of: AbcNode): Sequence<E>

    /**
     * Retrieves all outgoing edges from the specified node.
     *
     * @param of The node whose outgoing edges are to be retrieved.
     * @return A sequence of outgoing edges.
     */
    fun getOutgoingEdges(of: AbcNode): Sequence<E>

    /**
     * Retrieves all child nodes of the specified node that are connected by edges
     * satisfying the given predicate.
     *
     * @param of The node whose children are to be retrieved.
     * @param edgeCond The predicate to filter connecting edges.
     * @return A sequence of child nodes.
     */
    fun getChildren(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    /**
     * Retrieves all parent nodes of the specified node that are connected by edges
     * satisfying the given predicate.
     *
     * @param of The node whose parents are to be retrieved.
     * @param edgeCond The predicate to filter connecting edges.
     * @return A sequence of parent nodes.
     */
    fun getParents(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    /**
     * Retrieves all descendant nodes of the specified node that are connected by edges
     * satisfying the given predicate.
     *
     * @param of The node whose descendants are to be retrieved.
     * @param edgeCond The predicate to filter connecting edges.
     * @return A sequence of descendant nodes.
     */
    fun getDescendants(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    /**
     * Retrieves all ancestor nodes of the specified node that are connected by edges
     * satisfying the given predicate.
     *
     * @param of The node whose ancestors are to be retrieved.
     * @param edgeCond The predicate to filter connecting edges.
     * @return A sequence of ancestor nodes.
     */
    fun getAncestors(of: AbcNode, edgeCond: (E) -> Boolean = { true }): Sequence<N>
}
