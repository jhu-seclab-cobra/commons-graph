package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
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
    
    /**
     * Creates a test node class for graph testing.
     */
    class TestNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
        override val type: AbcNode.Type = object : AbcNode.Type {
            override val name = "TestNode"
        }
    }
    
    /**
     * Creates a test edge class for graph testing.
     */
    class TestEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
        override val type: AbcEdge.Type = object : AbcEdge.Type {
            override val name = "TestEdge"
        }
    }
    
    /**
     * Creates a concrete implementation of AbcBasicGraph for testing.
     */
    fun createTestBasicGraph(storage: IStorage = NativeStorageImpl()): AbcBasicGraph<TestNode, TestEdge> {
        return object : AbcBasicGraph<TestNode, TestEdge>(null) {
            override val storage: IStorage = storage
            override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
            override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
            
            // Stub implementations for abstract methods
            override fun addEdge(from: AbcNode, to: AbcNode, type: String): TestEdge {
                val edgeID = EdgeID(from.id, to.id, eType = "$graphName:$type")
                if (from.id !in cacheNIDs) wrapNode(from)
                if (to.id !in cacheNIDs) wrapNode(to)
                if (!storage.containsEdge(edgeID)) storage.addEdge(id = edgeID)
                return newEdgeObj(edgeID.also { cacheEIDs.add(it) })
            }
            
            override fun getEdge(from: AbcNode, to: AbcNode, type: String): TestEdge? {
                val edgeID = EdgeID(from.id, to.id, eType = "$graphName:$type")
                if (edgeID !in cacheEIDs || !storage.containsEdge(edgeID)) return null
                return newEdgeObj(eid = edgeID)
            }
        }
    }
    
    /**
     * Test subclass of AbcBasicGraph that exposes protected members for white-box testing.
     */
    class TestBasicGraph(storage: IStorage) : AbcBasicGraph<TestNode, TestEdge>(null) {
        override val storage: IStorage = storage
        override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
        override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
        
        // Expose protected members for white-box testing
        fun exposeCacheNIDs(): MutableSet<NodeID> = cacheNIDs
        fun exposeCacheEIDs(): MutableSet<EdgeID> = cacheEIDs
        
        // Stub implementations for abstract methods (not tested in AbcBasicGraph white-box tests)
        override fun addEdge(from: AbcNode, to: AbcNode, type: String): TestEdge {
            val edgeID = EdgeID(from.id, to.id, eType = "$graphName:$type")
            if (from.id !in cacheNIDs) wrapNode(from)
            if (to.id !in cacheNIDs) wrapNode(to)
            if (!storage.containsEdge(edgeID)) storage.addEdge(id = edgeID)
            return newEdgeObj(edgeID.also { cacheEIDs.add(it) })
        }
        
        override fun getEdge(from: AbcNode, to: AbcNode, type: String): TestEdge? {
            val edgeID = EdgeID(from.id, to.id, eType = "$graphName:$type")
            if (edgeID !in cacheEIDs || !storage.containsEdge(edgeID)) return null
            return newEdgeObj(eid = edgeID)
        }
    }
    
    /**
     * Creates a concrete implementation of AbcSimpleGraph for testing.
     */
    fun createTestSimpleGraph(storage: IStorage = NativeStorageImpl()): AbcSimpleGraph<TestNode, TestEdge> {
        return object : AbcSimpleGraph<TestNode, TestEdge>(null) {
            override val storage: IStorage = storage
            override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
            override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
        }
    }
    
    /**
     * Test subclass of AbcSimpleGraph that exposes protected members for white-box testing.
     */
    class TestSimpleGraph(storage: IStorage) : AbcSimpleGraph<TestNode, TestEdge>(null) {
        override val storage: IStorage = storage
        override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
        override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
        
        // Expose protected members for white-box testing
        fun exposeCacheNIDs(): MutableSet<NodeID> = cacheNIDs
        fun exposeCacheEIDs(): MutableSet<EdgeID> = cacheEIDs
    }
    
    /**
     * Creates a concrete implementation of AbcMultiGraph for testing.
     */
    fun createTestMultiGraph(storage: IStorage = NativeStorageImpl()): AbcMultiGraph<TestNode, TestEdge> {
        return object : AbcMultiGraph<TestNode, TestEdge>(null) {
            override val storage: IStorage = storage
            override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
            override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
        }
    }
    
    /**
     * Test subclass of AbcMultiGraph that exposes protected members for white-box testing.
     */
    class TestMultiGraph(storage: IStorage) : AbcMultiGraph<TestNode, TestEdge>(null) {
        override val storage: IStorage = storage
        override fun newNodeObj(nid: NodeID) = TestNode(storage, nid)
        override fun newEdgeObj(eid: EdgeID) = TestEdge(storage, eid)
        
        // Expose protected members for white-box testing
        fun exposeCacheNIDs(): MutableSet<NodeID> = cacheNIDs
        fun exposeCacheEIDs(): MutableSet<EdgeID> = cacheEIDs
    }
}

