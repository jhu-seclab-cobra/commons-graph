package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.IGraph
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import java.util.LinkedList

/**
 * Abstract base class implementing [edu.jhu.cobra.commons.graph.IGraph] interface with a caching mechanism.
 * Maintains synchronized caches for node and edge identifiers.
 *
 * @param N The type of nodes in the graph, must extend [edu.jhu.cobra.commons.graph.AbcNode].
 * @param E The type of edges in the graph, must extend [edu.jhu.cobra.commons.graph.AbcEdge].
 * @param nType The class object representing the node type, used for runtime type checking.
 */
abstract class AbcBasicGraph<N : AbcNode, E : AbcEdge>(nType: Class<N>?) : IGraph<N, E> {

    /**
     * The storage kernel for managing graph nodes and edges.
     * Must be implemented by concrete graph implementations.
     */
    abstract val storage: IStorage

    /**
     * Cache for node IDs in the graph storage.
     */
    protected val cacheNIDs: MutableSet<NodeID> = mutableSetOf()

    /**
     * Cache for edge IDs in the graph storage.
     */
    protected val cacheEIDs: MutableSet<EdgeID> = mutableSetOf()

    /**
     * The total number of entities (nodes and edges) in the graph.
     */
    override val entitySize: Int get() = cacheNIDs.size + cacheEIDs.size

    /**
     * The name of the graph, derived from the class name by removing the "Graph" suffix.
     * Defaults to "anonymous" if class name cannot be determined.
     */
    override val graphName: String by lazy { this::class.simpleName?.removeSuffix("Graph") ?: "anonymous" }

    /**
     * The class object representing the node type, used for runtime type checking.
     * If not provided in the constructor, determined from a sample node.
     */
    private val nodeClass by lazy { nType ?: newNodeObj(NodeID("__sample__"))::class.java }

    /**
     * Creates a new node object of type [N] using the provided node ID.
     *
     * @param nid The identifier for the new node.
     * @return A new instance of type [N].
     */
    protected abstract fun newNodeObj(nid: NodeID): N

    /**
     * Creates a new edge object of type [E] using the provided edge ID.
     *
     * @param eid The identifier for the new edge.
     * @return A new instance of type [E].
     */
    protected abstract fun newEdgeObj(eid: EdgeID): E

    /**
     * Checks if the graph contains the specified node.
     * Returns true only if the node exists in both cache and storage.
     *
     * @param node The node to check for existence.
     * @return `true` if the node exists in both cache and storage, `false` otherwise.
     */
    override fun containNode(node: AbcNode): Boolean =
        cacheNIDs.contains(node.id) && storage.containsNode(node.id)

    /**
     * Checks if the graph contains the specified edge.
     * Returns true only if the edge exists in both cache and storage.
     *
     * @param edge The edge to check for existence.
     * @return `true` if the edge exists in both cache and storage, `false` otherwise.
     */
    override fun containEdge(edge: AbcEdge): Boolean =
        cacheEIDs.contains(edge.id) && storage.containsEdge(edge.id)

    /**
     * Adds a new node to the graph with the specified identifier.
     *
     * @param whoseID The identifier for the new node.
     * @return The newly created node.
     * @throws edu.jhu.cobra.commons.graph.EntityAlreadyExistException if a node with the given ID already exists.
     */
    override fun addNode(whoseID: NodeID): N {
        if (whoseID in cacheNIDs) throw EntityAlreadyExistException(whoseID)
        if (!storage.containsNode(whoseID)) storage.addNode(id = whoseID)
        return newNodeObj(whoseID.also { cacheNIDs.add(it) })
    }

    /**
     * Adds a new node to the graph with the specified name.
     * The graph name prefix is automatically added to create the internal NodeID transparently.
     *
     * @param name The name for the new node (without graph name prefix).
     * @return The newly created node with transparently prefixed NodeID.
     * @throws edu.jhu.cobra.commons.graph.EntityAlreadyExistException if a node with the given name already exists.
     */
    fun addNode(name: String): N {
        val nodeID = NodeID("$graphName:$name")
        return addNode(nodeID)
    }

    /**
     * Wraps a generic [AbcNode] into its specific graph-context type [N].
     *
     * @param node The generic node to be wrapped.
     * @return The node converted to the specific type [N].
     * @throws edu.jhu.cobra.commons.graph.EntityNotExistException if the node does not exist in the storage.
     */
    @Suppress("UNCHECKED_CAST")
    override fun wrapNode(node: AbcNode): N {
        if (!storage.containsNode(node.id)) throw EntityNotExistException(node.id)
        if (cacheNIDs.add(node.id)) return newNodeObj(node.id)
        val canBeCasted = nodeClass.isInstance(node) && node.doUseStorage(storage)
        return if (canBeCasted) node as N else newNodeObj(node.id)
    }

    /**
     * Retrieves a node from the graph based on its identifier.
     * Returns null if the node does not exist in both cache and storage.
     *
     * @param whoseID The identifier of the node to retrieve.
     * @return The node if it exists in both cache and storage, `null` otherwise.
     */
    override fun getNode(whoseID: NodeID): N? {
        if (whoseID !in cacheNIDs || !storage.containsNode(whoseID)) return null
        return newNodeObj(nid = whoseID)
    }

    /**
     * Retrieves a node from the graph based on its name.
     * The graph name prefix is automatically added to create the internal NodeID transparently.
     *
     * @param name The name of the node to retrieve (without graph name prefix).
     * @return The node if it exists, `null` otherwise.
     */
    fun getNode(name: String): N? {
        val nodeID = NodeID("$graphName:$name")
        return getNode(nodeID)
    }

    /**
     * Retrieves an edge from the graph based on its identifier.
     * Returns null if the edge does not exist in both cache and storage.
     *
     * @param whoseID The identifier of the edge to retrieve.
     * @return The edge if it exists in both cache and storage, `null` otherwise.
     */
    override fun getEdge(whoseID: EdgeID): E? {
        if (whoseID !in cacheEIDs || !storage.containsEdge(whoseID)) return null
        return newEdgeObj(eid = whoseID)
    }

    /**
     * Retrieves all nodes in the graph that satisfy the given predicate.
     *
     * @param doSatisfy The predicate to filter nodes.
     * @return A sequence of nodes that satisfy the predicate.
     */
    override fun getAllNodes(doSatisfy: (N) -> Boolean): Sequence<N> =
        cacheNIDs.asSequence().map { newNodeObj(it) }.filter { storage.containsNode(it.id) && doSatisfy(it) }

    /**
     * Retrieves all edges in the graph that satisfy the given predicate.
     *
     * @param doSatisfy The predicate to filter edges.
     * @return A sequence of edges that satisfy the predicate.
     */
    override fun getAllEdges(doSatisfy: (E) -> Boolean): Sequence<E> =
        cacheEIDs.asSequence().map { newEdgeObj(it) }.filter { storage.containsEdge(it.id) && doSatisfy(it) }

    /**
     * Retrieves all outgoing edges from the specified node.
     * Returns an empty sequence if the node does not exist.
     *
     * @param of The node whose outgoing edges are to be retrieved.
     * @return A sequence of outgoing edges if the node exists, empty sequence otherwise.
     */
    override fun getOutgoingEdges(of: AbcNode): Sequence<E> {
        if (of.id !in cacheNIDs || !storage.containsNode(of.id)) return emptySequence()
        val existingIDs = storage.getOutgoingEdges(of.id).filter { it in cacheEIDs }
        return existingIDs.asSequence().map { newEdgeObj(it) }
    }

    /**
     * Retrieves all incoming edges to the specified node.
     * Returns an empty sequence if the node does not exist.
     *
     * @param of The node whose incoming edges are to be retrieved.
     * @return A sequence of incoming edges if the node exists, empty sequence otherwise.
     */
    override fun getIncomingEdges(of: AbcNode): Sequence<E> {
        if (of.id !in cacheNIDs || !storage.containsNode(of.id)) return emptySequence()
        val existingIDs = storage.getIncomingEdges(of.id).filter { it in cacheEIDs }
        return existingIDs.asSequence().map { newEdgeObj(it) }
    }

    /**
     * Retrieves all parent nodes of the specified node that are connected by edges
     * satisfying the given predicate.
     *
     * @param of The node whose parents are to be retrieved.
     * @param edgeCond The predicate to filter connecting edges.
     * @return A sequence of parent nodes.
     */
    override fun getParents(of: AbcNode, edgeCond: (E) -> Boolean): Sequence<N> =
        getIncomingEdges(of).filter(edgeCond).map { newNodeObj(it.srcNid) }

    /**
     * Retrieves all child nodes of the specified node that are connected by edges
     * satisfying the given predicate.
     *
     * @param of The node whose children are to be retrieved.
     * @param edgeCond The predicate to filter connecting edges.
     * @return A sequence of child nodes.
     */
    override fun getChildren(of: AbcNode, edgeCond: (E) -> Boolean): Sequence<N> =
        getOutgoingEdges(of).filter(edgeCond).map { newNodeObj(it.dstNid) }

    /**
     * Retrieves all ancestor nodes of the specified node that are connected by edges
     * satisfying the given predicate.
     * Uses depth-first search to traverse the graph.
     *
     * @param of The node whose ancestors are to be retrieved.
     * @param edgeCond The predicate to filter connecting edges.
     * @return A sequence of ancestor nodes.
     */
    override fun getAncestors(of: AbcNode, edgeCond: (E) -> Boolean) = sequence {
        if (of.id !in cacheNIDs || !storage.containsNode(of.id)) return@sequence
        val loop = hashSetOf<NodeID>()
        val stack = mutableListOf(of.id)
        while (stack.isNotEmpty()) {
            val currentId = stack.removeAt(index = 0)
            if (!loop.add(currentId)) continue
            storage.getIncomingEdges(currentId).forEach { edgeID ->
                if (edgeID !in cacheEIDs) return@forEach
                if (!edgeCond(newEdgeObj(edgeID))) return@forEach
                yield(newNodeObj(nid = edgeID.srcNid))
                stack.add(element = edgeID.srcNid)
            }
        }
    }

    /**
     * Retrieves all descendant nodes of the specified node that are connected by edges
     * satisfying the given predicate.
     * Uses breadth-first search to traverse the graph.
     *
     * @param of The node whose descendants are to be retrieved.
     * @param edgeCond The predicate to filter connecting edges.
     * @return A sequence of descendant nodes.
     */
    override fun getDescendants(of: AbcNode, edgeCond: (E) -> Boolean) = sequence {
        if (of.id !in cacheNIDs || !storage.containsNode(of.id)) return@sequence
        val loop = hashSetOf<NodeID>()
        val stack = LinkedList<NodeID>().apply { add(of.id) }
        while (stack.isNotEmpty()) {
            val currentId = stack.removeFirst()
            if (!loop.add(currentId)) continue
            val outgoings = storage.getOutgoingEdges(currentId)
            outgoings.forEach { edgeID ->
                if (edgeID !in cacheEIDs) return@forEach
                if (!edgeCond(newEdgeObj(edgeID))) return@forEach
                yield(newNodeObj(nid = edgeID.dstNid))
                stack.add(element = edgeID.dstNid)
            }
        }
    }

    /**
     * Deletes a node and all its associated edges from the graph.
     * Also removes the node and its edges from both cache and storage.
     *
     * @param node The node to be deleted.
     */
    override fun delNode(node: N) {
        if (!cacheNIDs.remove(node.id) || !storage.containsNode(node.id)) return
        val allEdges = getOutgoingEdges(node) + getIncomingEdges(node)
        allEdges.forEach { if (cacheEIDs.remove(it.id)) storage.deleteEdge(it.id) }
        storage.deleteNode(id = node.id)
    }

    /**
     * Deletes an edge from the graph.
     * Also removes the edge from both cache and storage.
     *
     * @param edge The edge to be deleted.
     */
    override fun delEdge(edge: E) {
        if (!cacheEIDs.remove(edge.id) || !storage.containsEdge(edge.id)) return
        storage.deleteEdge(id = edge.id)
    }

    /**
     * Clears and refreshes the cache for edge and node IDs in the graph storage.
     * Only loads edges whose type starts with the graph name.
     */
    open fun refreshCache() {
        clearCache()
        storage.edgeIDs.forEach { canID ->
            if (!canID.eType.startsWith(graphName)) return@forEach
            cacheNIDs.add(canID.srcNid); cacheNIDs.add(canID.dstNid)
            cacheEIDs.add(element = canID)
        }
    }

    /**
     * Clears the cache for edge and node IDs in the graph storage.
     * Does not affect the actual storage.
     */
    open fun clearCache() {
        cacheEIDs.clear()
        cacheNIDs.clear()
    }
}