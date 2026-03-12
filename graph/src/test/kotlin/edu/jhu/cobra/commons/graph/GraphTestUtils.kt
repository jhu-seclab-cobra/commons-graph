package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

/**
 * Test utilities for graph tests providing shared test data and helper functions.
 */
object GraphTestUtils {
    val nodeId1 = NodeID("node1")
    val nodeId2 = NodeID("node2")
    val nodeId3 = NodeID("node3")
    val nodeId4 = NodeID("node4")
    val nodeId5 = NodeID("node5")

    val edgeId1 = EdgeID(nodeId1, nodeId2, "edge1")
    val edgeId2 = EdgeID(nodeId2, nodeId3, "edge2")
    val edgeId3 = EdgeID(nodeId1, nodeId3, "edge3")
    val edgeId4 = EdgeID(nodeId3, nodeId4, "edge4")
    val edgeId5 = EdgeID(nodeId4, nodeId5, "edge5")

    class TestNode(
        storage: IStorage,
        override val id: NodeID,
    ) : AbcNode(storage) {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "TestNode"
            }
    }

    class TestEdge(
        storage: IStorage,
        override val id: EdgeID,
    ) : AbcEdge(storage) {
        override val type: AbcEdge.Type =
            object : AbcEdge.Type {
                override val name = "TestEdge"
            }
    }

    fun createTestMultipleGraph(storage: IStorage = NativeStorageImpl()): AbcMultipleGraph<TestNode, TestEdge> =
        object : AbcMultipleGraph<TestNode, TestEdge>() {
            override val storage: IStorage = storage

            override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)

            override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
        }

    class TestMultipleGraph(
        storage: IStorage,
    ) : AbcMultipleGraph<TestNode, TestEdge>() {
        override val storage: IStorage = storage

        override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)

        override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
    }

    fun createTestSimpleGraph(storage: IStorage = NativeStorageImpl()): AbcSimpleGraph<TestNode, TestEdge> =
        object : AbcSimpleGraph<TestNode, TestEdge>() {
            override val storage: IStorage = storage

            override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)

            override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
        }

    class TestSimpleGraph(
        storage: IStorage,
    ) : AbcSimpleGraph<TestNode, TestEdge>() {
        override val storage: IStorage = storage

        override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)

        override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
    }
}
