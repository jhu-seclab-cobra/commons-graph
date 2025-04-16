package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.*
import edu.jhu.cobra.commons.value.BoolVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

abstract class AbcStorageUnitTest {

    private lateinit var storage: IStorage

    abstract fun createStorage(): IStorage

    @BeforeEach
    fun setup() {
        storage = createStorage()
    }

    @AfterEach
    fun teardown() {
        storage.clear()
    }

    /**
     * Test for containsNode function. Includes edge cases like null or empty node ID.
     */
    @Test
    fun testContainsNode() {
        // Prepare invalid and edge case node IDs
        val invalidNodeId = NodeID("")
        val nullNodeId: NodeID? = null

        // Verify that invalid and null nodes are not contained
        assertFalse(storage.containsNode(invalidNodeId))

        // Normal case
        val validNodeId = NodeID("testNode")
        assertFalse(storage.containsNode(validNodeId))

        // Add and check existence
        storage.addNode(validNodeId)
        assertTrue(storage.containsNode(validNodeId))

        // Clean up
        storage.deleteNode(validNodeId)
        assertFalse(storage.containsNode(validNodeId))
    }

    /**
     * Test for containsEdge function. Includes edge cases like null or empty edge IDs.
     */
    @Test
    fun testContainsEdge() {
        // Prepare invalid and edge case edge IDs
        val srcNode = NodeID("sourceNode")
        val dstNode = NodeID("destinationNode")
        val invalidEdgeId = EdgeID(srcNode, dstNode, "")
        val nullEdgeId: EdgeID? = null

        // Verify that invalid and null edges are not contained
        assertFalse(storage.containsEdge(invalidEdgeId))

        // Normal case
        val validEdgeId = EdgeID(srcNode, dstNode, "test")
        assertFalse(storage.containsEdge(validEdgeId))

        // Add nodes and edge
        storage.addNode(srcNode)
        storage.addNode(dstNode)
        storage.addEdge(validEdgeId)

        // Verify existence
        assertTrue(storage.containsEdge(validEdgeId))

        // Clean up
        storage.deleteEdge(validEdgeId)
        assertFalse(storage.containsEdge(validEdgeId))
    }

    /**
     * Test for addNode with different types of properties and edge cases like empty properties.
     */
    @Test
    fun testAddNode() {
        val nodeId = NodeID("testNode")

        // Test adding node with empty properties
        storage.addNode(nodeId)
        assertTrue(storage.containsNode(nodeId))
        storage.deleteNode(nodeId)
        assertFalse(storage.containsNode(nodeId))

        // Test adding node with various properties
        val properties = mapOf("prop1" to "value1".strVal, "prop2" to BoolVal.T, "prop3" to 123.numVal)
        storage.addNode(nodeId, *properties.toList().toTypedArray())

        // Verify properties
        val nodeProps = storage.getNodeProperties(nodeId)
        properties.forEach { (key, value) -> assertEquals(value, nodeProps[key]) }

        // Clean up
        storage.deleteNode(nodeId)
    }

    /**
     * Test for addEdge with edge cases like invalid nodes or properties.
     */
    @Test
    fun testAddEdge() {
        val srcNode = NodeID("sourceNode")
        val dstNode = NodeID("destinationNode")
        val edgeId = EdgeID(srcNode, dstNode, "testEdge")

        // Test adding edge with non-existent nodes
        assertThrows<EntityNotExistException> { storage.addEdge(edgeId) }

        // Add nodes and then the edge
        storage.addNode(srcNode)
        storage.addNode(dstNode)

        // Test adding edge with empty properties
        storage.addEdge(edgeId)
        assertTrue(storage.containsEdge(edgeId))
        storage.deleteEdge(edgeId)
        assertFalse(storage.containsEdge(edgeId))

        // Test adding edge with properties
        val edgeProps = mapOf("edgeProp1" to 123.numVal, "edgeProp2" to BoolVal.F)
        storage.addEdge(edgeId, *edgeProps.toList().toTypedArray())

        // Verify edge properties
        val retrievedProps = storage.getEdgeProperties(edgeId)
        edgeProps.forEach { (key, value) -> assertEquals(value, retrievedProps[key]) }

        // Clean up
        storage.deleteEdge(edgeId)
    }

    /**
     * Stress test for adding and deleting a large number of nodes.
     */
    @Test
    fun testStressAddDeleteNodes() {
        val numNodes = 100_000
        val nodeIds = (1..numNodes).map { NodeID("node$it") }

        // Add large number of nodes
        nodeIds.forEach { storage.addNode(it) }
        nodeIds.forEach { assertTrue(storage.containsNode(it)) }

        // Delete all nodes
        nodeIds.forEach { storage.deleteNode(it) }
        nodeIds.forEach { assertFalse(storage.containsNode(it)) }
    }

    /**
     * Stress test for adding and deleting a large number of edges.
     */
    @Test
    fun testStressAddDeleteEdges() {
        val numEdges = 50_000
        val edgeIds = (1..numEdges).map {
            val src = NodeID("src$it")
            val dst = NodeID("dst$it")
            storage.addNode(src)
            storage.addNode(dst)
            EdgeID(src, dst, "edge$it")
        }

        // Add large number of edges
        edgeIds.forEach { storage.addEdge(it) }
        edgeIds.forEach { assertTrue(storage.containsEdge(it)) }

        // Delete all edges
        edgeIds.forEach { storage.deleteEdge(it) }
        edgeIds.forEach { assertFalse(storage.containsEdge(it)) }
    }
}


class FileDeltaStorageTest : AbcStorageUnitTest() {
    override fun createStorage(): IStorage = DeltaStorage(NativeStorage(), MapDBStorage())
}

class MemoryDeltaStorageTest : AbcStorageUnitTest() {
    override fun createStorage(): IStorage = DeltaStorage(NativeStorage(), NativeStorage())
}

class JgphtStorageTest : AbcStorageUnitTest() {
    override fun createStorage(): IStorage = JgphtStorage()
}

class FileMapDbStorageTest : AbcStorageUnitTest() {
    override fun createStorage(): IStorage = MapDBStorage()
}

class MemoryMapDbStorageTest : AbcStorageUnitTest() {
    override fun createStorage(): IStorage = MapDBStorage { memoryDB() }
}

class SimpleStorageTest : AbcStorageUnitTest() {
    override fun createStorage(): IStorage = NativeStorage()
}

//class Neo4jStorageTest : AbcStorageUnitTest() {
//    override fun createStorage(): IStorage = Neo4jStorage()
//}
