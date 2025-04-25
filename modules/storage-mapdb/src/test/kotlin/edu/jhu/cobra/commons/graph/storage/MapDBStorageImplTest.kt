package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * Test the functionality of MapDB implementation of IStorage
 */
class MapDBStorageImplTest {
    private lateinit var storage: MapDBStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge1 = EdgeID(node1, node2, "edge1")
    private val edge2 = EdgeID(node2, node3, "edge2")
    private val edge3 = EdgeID(node1, node3, "edge3")

    @BeforeTest
    fun setup() {
        storage = MapDBStorageImpl { memoryDB() }
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    /**
     * Test basic operations: add and get node and edge properties
     */
    @Test
    fun testPutAndGet() {
        // Test node operations
        storage.addNode(node1, "prop1" to "value1".strVal)
        assertTrue(storage.containsNode(node1))
        assertEquals(1, storage.nodeSize)

        val nodeProps = storage.getNodeProperties(node1)
        assertEquals(1, nodeProps.size)
        assertEquals("value1", (nodeProps["prop1"] as StrVal).core)

        // Test edge operations
        storage.addNode(node2)
        storage.addEdge(edge1, "prop1" to "value1".strVal)

        assertTrue(storage.containsEdge(edge1))
        assertEquals(1, storage.edgeSize)

        val edgeProps = storage.getEdgeProperties(edge1)
        assertEquals(1, edgeProps.size)
        assertEquals("value1", (edgeProps["prop1"] as StrVal).core)

        // Test error cases
        assertFailsWith<EntityAlreadyExistException> { storage.addNode(node1) }
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(NodeID("nonexistent")) }
        assertFailsWith<EntityNotExistException> { storage.addEdge(EdgeID(node1, NodeID("nonexistent"), "edge")) }
    }

    /**
     * Test updating properties for nodes and edges
     */
    @Test
    fun testUpdateProperties() {
        // Setup
        storage.addNode(node1, "prop1" to "value1".strVal)
        storage.addNode(node2)
        storage.addEdge(edge1, "prop1" to "value1".strVal)

        // Test node property updates
        storage.setNodeProperties(node1, "prop1" to "newValue".strVal, "prop2" to 42.numVal)
        val nodeProps = storage.getNodeProperties(node1)
        assertEquals(2, nodeProps.size)
        assertEquals("newValue", (nodeProps["prop1"] as StrVal).core)
        assertEquals(42, (nodeProps["prop2"] as NumVal).core)

        // Test edge property updates
        storage.setEdgeProperties(edge1, "prop1" to "newValue".strVal, "prop2" to 42.numVal)
        val edgeProps = storage.getEdgeProperties(edge1)
        assertEquals(2, edgeProps.size)
        assertEquals("newValue", (edgeProps["prop1"] as StrVal).core)
        assertEquals(42, (edgeProps["prop2"] as NumVal).core)

        // Test removing properties with null
        storage.setNodeProperties(node1, "prop1" to null)
        val updatedNodeProps = storage.getNodeProperties(node1)
        assertEquals(1, updatedNodeProps.size)
        assertNull(updatedNodeProps["prop1"])
        assertEquals(42, (updatedNodeProps["prop2"] as NumVal).core)

        // Test error case
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(NodeID("nonexistent"), "prop" to "value".strVal)
        }
    }

    /**
     * Test removing entities (nodes and edges)
     */
    @Test
    fun testRemoveEntity() {
        // Setup
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge3)

        // Test edge removal
        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))

        // Test node removal with connected edges
        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge3))
        assertTrue(storage.containsNode(node3))

        // Test bulk removal
        storage.addNode(node1)
        storage.addEdge(EdgeID(node1, node2, "newEdge"))
        storage.deleteNodes { it.name.contains("node1") }
        assertFalse(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))

        // Test edge bulk removal
        storage.addNode(node1)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.deleteEdges { it.name.split("-")[1] == "edge1" }
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsEdge(edge2))

        // Test error cases
        assertFailsWith<EntityNotExistException> { storage.deleteNode(NodeID("nonexistent")) }
    }

    /**
     * Test collection views: nodeIDsSequence, edgeIDsSequence, etc.
     */
    @Test
    fun testCollectionViews() {
        // Setup
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        // Test node collection
        val nodeIds = storage.nodeIDsSequence.toList()
        assertEquals(2, nodeIds.size)
        assertTrue(nodeIds.contains(node1))
        assertTrue(nodeIds.contains(node2))

        // Test edge collection
        val edgeIds = storage.edgeIDsSequence.toList()
        assertEquals(1, edgeIds.size)
        assertTrue(edgeIds.contains(edge1))

        // Test getting edges by relationship
        storage.addNode(node3)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

        val incomingEdges = storage.getIncomingEdges(node3)
        assertEquals(2, incomingEdges.size)
        assertTrue(incomingEdges.contains(edge2))
        assertTrue(incomingEdges.contains(edge3))

        val outgoingEdges = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoingEdges.size)
        assertTrue(outgoingEdges.contains(edge1))
        assertTrue(outgoingEdges.contains(edge3))

        val edgesBetween = storage.getEdgesBetween(node1, node3)
        assertEquals(1, edgesBetween.size)
        assertTrue(edgesBetween.contains(edge3))

        // Test empty collections
        val emptyIncoming = storage.getIncomingEdges(node1)
        assertTrue(emptyIncoming.isEmpty())

        // Test error cases
        assertFailsWith<EntityNotExistException> {
            storage.getIncomingEdges(NodeID("nonexistent"))
        }
    }

    /**
     * Test empty, null, and special value handling
     */
    @Test
    fun testEmptyAndNullValues() {
        // Test empty properties
        storage.addNode(node1)
        assertTrue(storage.containsNode(node1))
        assertEquals(0, storage.getNodeProperties(node1).size)

        // Test null property values
        storage.addNode(node2, "nullProp" to NullVal)
        val props = storage.getNodeProperties(node2)
        assertEquals(1, props.size)
        assertTrue(props["nullProp"] is NullVal)

        // Test empty storage operations
        val emptyNodeIds = storage.nodeIDsSequence.toList()
        assertEquals(2, emptyNodeIds.size)  // We added node1 and node2

        storage.clear()
        assertEquals(0, storage.nodeSize)
        assertEquals(0, storage.edgeSize)
        assertTrue(storage.nodeIDsSequence.toList().isEmpty())
    }

    /**
     * Test null value handling in properties
     */
    @Test
    fun testNullValueHandling() {
        // Setup
        storage.addNode(
            node1,
            "nullValue" to NullVal,
            "normalValue" to "normal".strVal
        )

        // Verify properties
        val props = storage.getNodeProperties(node1)
        assertEquals(NullVal, props["nullValue"])
        assertEquals("normal".strVal, props["normalValue"])

        // Set property to null (should remove it)
        storage.setNodeProperties(node1, "normalValue" to null)
        val updatedProps = storage.getNodeProperties(node1)
        assertEquals(1, updatedProps.size)
        assertTrue("normalValue" !in updatedProps)
        assertTrue("nullValue" in updatedProps)
    }

    /**
     * Test edge cases and error conditions
     */
    @Test
    fun testEdgeCases() {
        // Test operations on non-existent entities
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))

        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(node1) }
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(node1, "prop") }
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(edge1) }
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperty(edge1, "prop") }

        // Test adding edge with non-existent nodes
        assertFailsWith<EntityNotExistException> { storage.addEdge(edge1) }

        // Test clearing empty storage
        assertTrue(storage.clear())

        // Test accessing after close
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeSize }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(node1) }
    }

    /**
     * Test complex data structures and property values
     */
    @Test
    fun testComplexDataStructures() {
        // Test complex property values
        val complexValue = mapOf(
            "str" to "test".strVal,
            "num" to 42.numVal,
            "bool" to true.boolVal,
            "list" to listOf(1.numVal, 2.numVal, 3.numVal).listVal,
            "map" to mapOf("nested" to "value".strVal).mapVal
        ).mapVal

        storage.addNode(node1, "complex" to complexValue)
        val props = storage.getNodeProperties(node1)
        assertEquals(complexValue, props["complex"])

        // Test retrieving specific property by name
        val specificProp = storage.getNodeProperty(node1, "complex")
        assertEquals(complexValue, specificProp)

        // Test multiple edges between same nodes
        storage.addNode(node2)
        storage.addEdge(edge1)
        val edge1b = EdgeID(node1, node2, "edge1b")
        storage.addEdge(edge1b)

        val edges = storage.getEdgesBetween(node1, node2)
        assertEquals(2, edges.size)
        assertTrue(edges.contains(edge1))
        assertTrue(edges.contains(edge1b))
    }

    /**
     * Test with large datasets to verify performance and stability
     */
    @Test
    fun testLargeDataSet() {
        val nodeCount = 100
        val edgesPerNode = 5

        // Create nodes
        for (i in 1..nodeCount) {
            storage.addNode(
                NodeID("largeNode$i"),
                "index" to i.numVal,
                "name" to "Node $i".strVal
            )
        }

        // Create edges
        for (i in 1..nodeCount) {
            val srcNode = NodeID("largeNode$i")
            for (j in 1..edgesPerNode) {
                // Connect to subsequent 5 nodes (or wrap around)
                val targetIdx = ((i + j) % nodeCount) + 1
                val dstNode = NodeID("largeNode$targetIdx")
                storage.addEdge(EdgeID(srcNode, dstNode, "largeEdge${i}_${targetIdx}"))
            }
        }

        // Verify counts
        assertEquals(nodeCount, storage.nodeSize)
        assertEquals(nodeCount * edgesPerNode, storage.edgeSize)

        // Test retrieving random nodes and edges
        for (i in listOf(1, 25, 50, 75, 100)) {
            if (i <= nodeCount) {
                val nodeId = NodeID("largeNode$i")
                assertTrue(storage.containsNode(nodeId))

                val props = storage.getNodeProperties(nodeId)
                assertEquals(i, (props["index"] as NumVal).core)
                assertEquals("Node $i", (props["name"] as StrVal).core)

                // Check outgoing edges
                val outEdges = storage.getOutgoingEdges(nodeId)
                assertEquals(edgesPerNode, outEdges.size)
            }
        }

        // Test deleting a batch of nodes
        storage.deleteNodes { (it.name.substringAfter("largeNode").toIntOrNull() ?: 0) > 75 }
        assertEquals(75, storage.nodeSize)
    }

    /**
     * Test comprehensive collection operations with both true/false conditions
     */
    @Test
    fun testComprehensiveCollectionOperations() {
        // Setup for testing
        storage.addNode(node1, "key1" to "value1".strVal)
        storage.addNode(node2, "key2" to "value2".strVal)
        storage.addNode(node3, "key3" to "value3".strVal)

        storage.addEdge(edge1, "edge_key1" to "edge_value1".strVal)
        storage.addEdge(edge2, "edge_key2" to "edge_value2".strVal)

        // Test contains operations - true and false cases
        assertTrue(storage.containsNode(node1))
        assertFalse(storage.containsNode(NodeID("nonexistent")))

        assertTrue(storage.containsEdge(edge1))
        assertFalse(storage.containsEdge(EdgeID(node1, node3, "nonexistent")))

        // Test bulk delete operations - both matching and non-matching conditions
        // Delete matching nodes
        storage.deleteNodes { it == node1 }
        assertFalse(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))

        // Delete with non-matching condition
        storage.deleteNodes { false }
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsNode(node3))

        // Delete matching edges
        storage.deleteEdges { it == edge1 }
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsEdge(edge2))

        // Delete with non-matching condition
        storage.deleteEdges { false }
        assertTrue(storage.containsEdge(edge2))

        // Test clear operation
        assertTrue(storage.clear())
        assertEquals(0, storage.nodeSize)
        assertEquals(0, storage.edgeSize)
    }

    /**
     * Test concurrent modifications and thread safety concerns
     */
    @Test
    fun testConcurrentModifications() {
        // Setup basic data
        storage.addNode(node1, "prop1" to "value1".strVal)
        storage.addNode(node2)
        storage.addEdge(edge1)

        // Test parallel node property modifications
        val thread1 = Thread {
            storage.setNodeProperties(node1, "thread1_prop" to "thread1_value".strVal)
        }

        val thread2 = Thread {
            storage.setNodeProperties(node1, "thread2_prop" to "thread2_value".strVal)
        }

        thread1.start()
        thread2.start()
        thread1.join()
        thread2.join()

        // Verify results - note: specific outcomes may vary since implementation is not thread-safe
        val nodeProps = storage.getNodeProperties(node1)
        assertTrue(nodeProps.size >= 1, "Should have at least original property")

        // Test parallel edge operations
        val thread3 = Thread {
            try {
                storage.addEdge(EdgeID(node1, node2, "thread3_edge"))
            } catch (e: Exception) {
                // Expect potential exceptions due to non-thread safety
            }
        }

        val thread4 = Thread {
            try {
                storage.addEdge(EdgeID(node2, node1, "thread4_edge"))
            } catch (e: Exception) {
                // Expect potential exceptions due to non-thread safety
            }
        }

        thread3.start()
        thread4.start()
        thread3.join()
        thread4.join()

        // Verify the storage is still in a usable state
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
    }

    /**
     * Test internal structure consistency
     */
    @Test
    fun testInternalStructureConsistency() {
        // Setup graph with interconnected nodes and edges
        storage.addNode(node1, "prop1" to "value1".strVal)
        storage.addNode(node2, "prop2" to "value2".strVal)
        storage.addNode(node3, "prop3" to "value3".strVal)

        storage.addEdge(edge1, "edge_prop1" to "edge_value1".strVal)
        storage.addEdge(edge2, "edge_prop2" to "edge_value2".strVal)
        storage.addEdge(edge3, "edge_prop3" to "edge_value3".strVal)

        // Verify edge relationships
        val outgoing1 = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing1.size)
        assertTrue(outgoing1.contains(edge1))
        assertTrue(outgoing1.contains(edge3))

        val incoming3 = storage.getIncomingEdges(node3)
        assertEquals(2, incoming3.size)
        assertTrue(incoming3.contains(edge2))
        assertTrue(incoming3.contains(edge3))

        // Modify structure and verify consistency maintained
        storage.deleteEdge(edge1)

        val outgoing1After = storage.getOutgoingEdges(node1)
        assertEquals(1, outgoing1After.size)
        assertTrue(outgoing1After.contains(edge3))
        assertFalse(outgoing1After.contains(edge1))

        // Delete node and verify all connected edges are removed
        storage.deleteNode(node1)
        assertFalse(storage.containsEdge(edge3))
        assertTrue(storage.containsEdge(edge2))

        val incoming3After = storage.getIncomingEdges(node3)
        assertEquals(1, incoming3After.size)
        assertTrue(incoming3After.contains(edge2))
    }
}
