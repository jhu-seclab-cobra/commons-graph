package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.entity.*
import edu.jhu.cobra.commons.graph.storage.IStorage
import java.util.*

abstract class AbcBasicGraph<N : AbcNode, E : AbcEdge>(nType: Class<N>?) : IGraph<N, E> {

    /** The storage kernel ([IStorage] type) for managing graph nodes and edges. */
    abstract val storage: IStorage

    protected val cacheNIDs: MutableSet<NodeID> = mutableSetOf() // cache for node IDs in the graph storage
    protected val cacheEIDs: MutableSet<EdgeID> = mutableSetOf() // cache for edge IDs in the graph storage

    override val entitySize: Int get() = cacheNIDs.size + cacheEIDs.size
    override val graphName: String by lazy { this::class.simpleName?.removeSuffix("Graph") ?: "anonymous" }

    private val nodeClass by lazy { nType ?: newNodeObj("__sample__".toNid)::class.java }

    /**
     * Creates a new `[N]` object using the provided node ID.
     * @param nid The identifier for the new node.
     * @return A new instance of `[N]`.
     */
    protected abstract fun newNodeObj(nid: NodeID): N

    /**
     * Creates a new `[E]` object using the provided-edge ID.
     * @param eid The identifier for the new edge.
     * @return A new instance of `[E]`.
     */
    protected abstract fun newEdgeObj(eid: EdgeID): E

    override fun containNode(node: AbcNode): Boolean =
        cacheNIDs.contains(node.id) && storage.containsNode(node.id)

    override fun containEdge(edge: AbcEdge): Boolean =
        cacheEIDs.contains(edge.id) && storage.containsEdge(edge.id)

    override fun addNode(whoseID: NodeID): N {
        if (whoseID in cacheNIDs) throw EntityAlreadyExistException(whoseID)
        if (!storage.containsNode(whoseID)) storage.addNode(id = whoseID)
        return newNodeObj(whoseID.also { cacheNIDs.add(it) })
    }

    @Suppress("UNCHECKED_CAST")
    override fun wrapNode(node: AbcNode): N {
        if (!storage.containsNode(node.id)) throw EntityNotExistException(node.id)
        if (cacheNIDs.add(node.id)) return newNodeObj(node.id)
        val canBeCasted = nodeClass.isInstance(node) && node.doUseStorage(storage)
        return if (canBeCasted) node as N else newNodeObj(node.id)
    }

    override fun getNode(whoseID: NodeID): N? {
        if (whoseID !in cacheNIDs || !storage.containsNode(whoseID)) return null
        return newNodeObj(nid = whoseID) // if the node is not in the storage
    }

    override fun getEdge(whoseID: EdgeID): E? {
        if (whoseID !in cacheEIDs || !storage.containsEdge(whoseID)) return null
        return newEdgeObj(eid = whoseID)
    }

    override fun getAllNodes(doSatisfy: (N) -> Boolean): Sequence<N> =
        cacheNIDs.asSequence().map { newNodeObj(it) }.filter { storage.containsNode(it.id) && doSatisfy(it) }

    override fun getAllEdges(doSatisfy: (E) -> Boolean): Sequence<E> =
        cacheEIDs.asSequence().map { newEdgeObj(it) }.filter { storage.containsEdge(it.id) && doSatisfy(it) }

    override fun getOutgoingEdges(of: AbcNode): Sequence<E> {
        if (of.id !in cacheNIDs || !storage.containsNode(of.id)) return emptySequence()
        val existingIDs = storage.getOutgoingEdges(of.id).filter { it in cacheEIDs }
        return existingIDs.asSequence().map { newEdgeObj(it) }
    }

    override fun getIncomingEdges(of: AbcNode): Sequence<E> {
        if (of.id !in cacheNIDs || !storage.containsNode(of.id)) return emptySequence()
        val existingIDs = storage.getIncomingEdges(of.id).filter { it in cacheEIDs }
        return existingIDs.asSequence().map { newEdgeObj(it) }
    }

    override fun getParents(of: AbcNode, edgeCond: (E) -> Boolean): Sequence<N> =
        getIncomingEdges(of).filter(edgeCond).map { newNodeObj(it.srcNid) }

    override fun getChildren(of: AbcNode, edgeCond: (E) -> Boolean): Sequence<N> =
        getOutgoingEdges(of).filter(edgeCond).map { newNodeObj(it.dstNid) }

    override fun getAncestors(of: AbcNode, edgeCond: (E) -> Boolean) = sequence {
        if (of.id !in cacheNIDs || !storage.containsNode(of.id)) return@sequence
        val loop = hashSetOf<NodeID>()
        val stack = mutableListOf(of.id)
        while (stack.isNotEmpty()) {
            val currentId = stack.removeAt(index = 0) // get cur id
            if (!loop.add(currentId)) continue
            storage.getIncomingEdges(currentId).forEach { edgeID ->
                if (edgeID !in cacheEIDs) return@forEach
                if (!edgeCond(newEdgeObj(edgeID))) return@forEach
                yield(newNodeObj(nid = edgeID.srcNid))
                stack.add(element = edgeID.srcNid)
            }
        }
    }

    override fun getDescendants(of: AbcNode, edgeCond: (E) -> Boolean) = sequence {
        if (of.id !in cacheNIDs || !storage.containsNode(of.id)) return@sequence
        val loop = hashSetOf<NodeID>()
        val stack = LinkedList<NodeID>().apply { add(of.id) }
        while (stack.isNotEmpty()) {
            val currentId = stack.removeFirst() // get cur id
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

    override fun delNode(node: N) {
        if (!cacheNIDs.remove(node.id) || !storage.containsNode(node.id)) return
        val allEdges = getOutgoingEdges(node) + getIncomingEdges(node)
        allEdges.forEach { if (cacheEIDs.remove(it.id)) storage.deleteEdge(it.id) }
        storage.deleteNode(id = node.id)
    }

    override fun delEdge(edge: E) {
        if (!cacheEIDs.remove(edge.id) || !storage.containsEdge(edge.id)) return
        storage.deleteEdge(id = edge.id)
    }

    /**
     * Clears and refreshes the cache for edge and node IDs in the graph storage.
     */
    open fun refreshCache() {
        clearCache() // first remove all data in the cache
        storage.edgeIDsSequence.forEach { canID -> // second reload it base edge IDs
            if (!canID.eType.startsWith(graphName)) return@forEach
            cacheNIDs.add(canID.srcNid); cacheNIDs.add(canID.dstNid)
            cacheEIDs.add(element = canID)
        }
    }

    /**
     * Clears the cache for edge and node IDs in the graph storage.
     */
    open fun clearCache() {
        cacheEIDs.clear() // remove all cached edge ids
        cacheNIDs.clear() // remove all cached node ids
    }

}