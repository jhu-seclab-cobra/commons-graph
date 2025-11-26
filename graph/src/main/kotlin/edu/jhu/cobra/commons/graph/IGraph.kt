package edu.jhu.cobra.commons.graph

interface IGraph<N : AbcNode, E : AbcEdge> {

    val nodeIDs: Set<NodeID>

    val edgeIDs: Set<EdgeID>

    // Node CURD Operations
    fun addNode(withID: NodeID): N

    fun getNode(whoseID: NodeID): N?

    fun containNode(whoseID: NodeID): Boolean

    fun delNode(whoseID: NodeID)

    fun getAllNodes(doSatfy: (N) -> Boolean = { true }): Sequence<N>

    // Edge CURD Operations
    fun addEdge(withID: EdgeID): E

    fun getEdge(whoseID: EdgeID): E?

    fun containEdge(whoseID: EdgeID): Boolean

    fun delEdge(whoseID: EdgeID)

    // Graph Structure queries
    fun getAllEdges(doSatfy: (E) -> Boolean = { true }): Sequence<E>

    fun getIncomingEdges(of: NodeID): Sequence<E>

    fun getOutgoingEdges(of: NodeID): Sequence<E>

    fun getChildren(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    fun getParents(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    fun getDescendants(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>

    fun getAncestors(of: NodeID, edgeCond: (E) -> Boolean = { true }): Sequence<N>
}
