package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl

/**
 * Test utilities for graph tests providing shared test data and helper functions.
 */
object GraphTestUtils {
    const val nodeId1: NodeID = "node1"
    const val nodeId2: NodeID = "node2"
    const val nodeId3: NodeID = "node3"
    const val nodeId4: NodeID = "node4"
    const val nodeId5: NodeID = "node5"

    const val edgeType1 = "edge1"
    const val edgeType2 = "edge2"
    const val edgeType3 = "edge3"
    const val edgeType4 = "edge4"
    const val edgeType5 = "edge5"

    class TestNode(
        storage: IStorage,
        internalId: InternalID,
    ) : AbcNode(storage, internalId) {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "TestNode"
            }
    }

    class TestEdge(
        storage: IStorage,
        internalId: InternalID,
    ) : AbcEdge(storage, internalId) {
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

            override fun newNodeObj(internalId: InternalID) = TestNode(storage, internalId)

            override fun newEdgeObj(internalId: InternalID) = TestEdge(storage, internalId)
        }

    class TestMultipleGraph(
        storage: IStorage,
        posetStorage: IStorage = NativeStorageImpl(),
    ) : AbcMultipleGraph<TestNode, TestEdge>() {
        override val storage: IStorage = storage
        override val posetStorage: IStorage = posetStorage

        override fun newNodeObj(internalId: InternalID) = TestNode(storage, internalId)

        override fun newEdgeObj(internalId: InternalID) = TestEdge(storage, internalId)
    }

    fun createTestSimpleGraph(
        storage: IStorage = NativeStorageImpl(),
        posetStorage: IStorage = NativeStorageImpl(),
    ): AbcSimpleGraph<TestNode, TestEdge> =
        object : AbcSimpleGraph<TestNode, TestEdge>() {
            override val storage: IStorage = storage
            override val posetStorage: IStorage = posetStorage

            override fun newNodeObj(internalId: InternalID) = TestNode(storage, internalId)

            override fun newEdgeObj(internalId: InternalID) = TestEdge(storage, internalId)
        }

    class TestSimpleGraph(
        storage: IStorage,
        posetStorage: IStorage = NativeStorageImpl(),
    ) : AbcSimpleGraph<TestNode, TestEdge>() {
        override val storage: IStorage = storage
        override val posetStorage: IStorage = posetStorage

        override fun newNodeObj(internalId: InternalID) = TestNode(storage, internalId)

        override fun newEdgeObj(internalId: InternalID) = TestEdge(storage, internalId)
    }
}
