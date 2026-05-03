package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.poset.IPoset
import edu.jhu.cobra.commons.graph.poset.PosetDftImpl
import edu.jhu.cobra.commons.graph.poset.PosetTrait
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

object GraphTestUtils {
    const val NODE_ID_1: NodeID = "node1"
    const val NODE_ID_2: NodeID = "node2"
    const val NODE_ID_3: NodeID = "node3"
    const val NODE_ID_4: NodeID = "node4"
    const val NODE_ID_5: NodeID = "node5"

    const val EDGE_TAG_1 = "edge1"
    const val EDGE_TAG_2 = "edge2"
    const val EDGE_TAG_3 = "edge3"
    const val EDGE_TAG_4 = "edge4"
    const val EDGE_TAG_5 = "edge5"

    class TestNode : AbcNode() {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "TestNode"
            }
    }

    class TestEdge : AbcEdge() {
        override val type: AbcEdge.Type =
            object : AbcEdge.Type {
                override val name = "TestEdge"
            }
    }

    fun createTestMultipleGraph(
        storage: IStorage = NativeStorageImpl(),
        posetStorage: IStorage = NativeStorageImpl(),
    ): TestMultipleGraphWithPoset = TestMultipleGraphWithPoset(storage, posetStorage)

    class TestMultipleGraphWithPoset(
        graphStorage: IStorage,
        posetStorage: IStorage = NativeStorageImpl(),
    ) : AbcMultipleGraph<TestNode, TestEdge>(),
        PosetTrait<TestNode, TestEdge> {
        override val storage: IStorage = graphStorage
        override val graphId: String = "TestMultiplePoset"
        override val poset: IPoset = PosetDftImpl(posetStorage)

        override fun newNodeObj() = TestNode()
        override fun newEdgeObj() = TestEdge()
    }

    class TestMultipleGraph(
        graphStorage: IStorage,
        override val graphId: String = "TestMultiple",
    ) : AbcMultipleGraph<TestNode, TestEdge>() {
        override val storage: IStorage = graphStorage

        override fun newNodeObj() = TestNode()
        override fun newEdgeObj() = TestEdge()

        fun doRebuild() = rebuild()
    }

    fun createTestSimpleGraph(
        storage: IStorage = NativeStorageImpl(),
        posetStorage: IStorage = NativeStorageImpl(),
    ): TestSimpleGraphWithPoset = TestSimpleGraphWithPoset(storage, posetStorage)

    class TestSimpleGraphWithPoset(
        graphStorage: IStorage,
        posetStorage: IStorage = NativeStorageImpl(),
    ) : AbcSimpleGraph<TestNode, TestEdge>(),
        PosetTrait<TestNode, TestEdge> {
        override val storage: IStorage = graphStorage
        override val graphId: String = "TestSimplePoset"
        override val poset: IPoset = PosetDftImpl(posetStorage)

        override fun newNodeObj() = TestNode()
        override fun newEdgeObj() = TestEdge()
    }

    class TestSimpleGraph(
        graphStorage: IStorage,
    ) : AbcSimpleGraph<TestNode, TestEdge>() {
        override val storage: IStorage = graphStorage
        override val graphId: String = "TestSimple"

        override fun newNodeObj() = TestNode()
        override fun newEdgeObj() = TestEdge()
    }
}
