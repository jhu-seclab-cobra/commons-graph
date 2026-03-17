package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

/**
 * Test utilities for graph tests providing shared test data and helper functions.
 */
object GraphTestUtils {
    const val NODE_ID_1: NodeID = "node1"
    const val NODE_ID_2: NodeID = "node2"
    const val NODE_ID_3: NodeID = "node3"
    const val NODE_ID_4: NodeID = "node4"
    const val NODE_ID_5: NodeID = "node5"

    const val EDGE_TYPE_1 = "edge1"
    const val EDGE_TYPE_2 = "edge2"
    const val EDGE_TYPE_3 = "edge3"
    const val EDGE_TYPE_4 = "edge4"
    const val EDGE_TYPE_5 = "edge5"

    class TestNode(
        storage: IStorage,
        nodeId: NodeID,
    ) : AbcNode(storage, nodeId) {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "TestNode"
            }
    }

    class TestEdge(
        storage: IStorage,
        edgeId: String,
    ) : AbcEdge(storage, edgeId) {
        override val type: AbcEdge.Type =
            object : AbcEdge.Type {
                override val name = "TestEdge"
            }
    }

    fun createTestMultipleGraph(
        storage: IStorage = NativeStorageImpl(),
        posetStorage: IStorage = NativeStorageImpl(),
    ): AbcMultipleGraph<TestNode, TestEdge> =
        object : AbcMultipleGraph<TestNode, TestEdge>() {
            override val storage: IStorage = storage
            override val posetStorage: IStorage = posetStorage

            override fun newNodeObj(nodeId: NodeID) = TestNode(storage, nodeId)

            override fun newEdgeObj(edgeId: String) =
                TestEdge(storage, edgeId)
        }

    class TestMultipleGraph(
        storage: IStorage,
        posetStorage: IStorage = NativeStorageImpl(),
    ) : AbcMultipleGraph<TestNode, TestEdge>() {
        override val storage: IStorage = storage
        override val posetStorage: IStorage = posetStorage

        override fun newNodeObj(nodeId: NodeID) = TestNode(storage, nodeId)

        override fun newEdgeObj(edgeId: String) =
            TestEdge(storage, edgeId)
    }

    fun createTestSimpleGraph(
        storage: IStorage = NativeStorageImpl(),
        posetStorage: IStorage = NativeStorageImpl(),
    ): AbcSimpleGraph<TestNode, TestEdge> =
        object : AbcSimpleGraph<TestNode, TestEdge>() {
            override val storage: IStorage = storage
            override val posetStorage: IStorage = posetStorage

            override fun newNodeObj(nodeId: NodeID) = TestNode(storage, nodeId)

            override fun newEdgeObj(edgeId: String) =
                TestEdge(storage, edgeId)
        }

    class TestSimpleGraph(
        storage: IStorage,
        posetStorage: IStorage = NativeStorageImpl(),
    ) : AbcSimpleGraph<TestNode, TestEdge>() {
        override val storage: IStorage = storage
        override val posetStorage: IStorage = posetStorage

        override fun newNodeObj(nodeId: NodeID) = TestNode(storage, nodeId)

        override fun newEdgeObj(edgeId: String) =
            TestEdge(storage, edgeId)
    }
}
