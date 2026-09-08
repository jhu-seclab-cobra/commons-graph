package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.AbcSimpleGraph
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

// Shared fixtures for poset tests: a simple graph with PosetTrait backed by PosetDftImpl.

internal class TestNode : AbcNode() {
    override val type: AbcNode.Type =
        object : AbcNode.Type {
            override val name = "TN"
        }
}

internal class TestEdge : AbcEdge() {
    override val type: AbcEdge.Type =
        object : AbcEdge.Type {
            override val name = "TE"
        }
}

internal class TestGraph :
    AbcSimpleGraph<TestNode, TestEdge>(),
    PosetTrait<TestNode, TestEdge> {
    override val storage = NativeStorageImpl()
    override val graphId: String = "TestPoset"
    val posetStorage = NativeStorageImpl()
    override val poset: IPoset = PosetDftImpl(posetStorage)

    override fun newNodeObj() = TestNode()

    override fun newEdgeObj() = TestEdge()
}
