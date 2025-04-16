package cobra.common.graph

import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.entity.*
import edu.jhu.cobra.commons.graph.storage.JgphtStorage

class TestSimpleGraph : AbcSimpleGraph<TestSimpleGraph.Node, TestSimpleGraph.Edge>(null) {
    enum class NType : AbcNode.Type { UNKNOWN }
    enum class EType : AbcEdge.Type { UNKNOWN }
    inner class Node(override val id: NodeID, override val type: NType = NType.UNKNOWN) : AbcNode(storage)
    inner class Edge(override val id: EdgeID, override val type: EType = EType.UNKNOWN) : AbcEdge(storage)

    override val storage = JgphtStorage()
    override val graphName: String get() = "test"

    override fun newNodeObj(nid: NodeID) = Node(nid)
    override fun newEdgeObj(eid: EdgeID) = Edge(eid)

    fun addCache(id: IEntity.ID) = when (id) {
        is NodeID -> cacheNIDs.add(id)
        is EdgeID -> cacheEIDs.add(id)
    }

    fun containCache(id: IEntity.ID) = when (id) {
        is NodeID -> cacheNIDs.contains(id)
        is EdgeID -> cacheEIDs.contains(id)
    }

}

class TestMultipleGraph : AbcSimpleGraph<TestMultipleGraph.Node, TestMultipleGraph.Edge>(null) {
    enum class NType : AbcNode.Type { UNKNOWN }
    enum class EType : AbcEdge.Type { UNKNOWN }
    inner class Node(override val id: NodeID, override val type: NType = NType.UNKNOWN) : AbcNode(storage)
    inner class Edge(override val id: EdgeID, override val type: EType = EType.UNKNOWN) : AbcEdge(storage)

    override val storage = JgphtStorage()
    override val graphName: String get() = "test"

    override fun newNodeObj(nid: NodeID) = Node(nid)
    override fun newEdgeObj(eid: EdgeID) = Edge(eid)

    fun addCache(id: IEntity.ID) = when (id) {
        is NodeID -> cacheNIDs.add(id)
        is EdgeID -> cacheEIDs.add(id)
    }

    fun containCache(id: IEntity.ID) = when (id) {
        is NodeID -> cacheNIDs.contains(id)
        is EdgeID -> cacheEIDs.contains(id)
    }

}

