package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/**
 * Test suite for JgraphtStorageImpl to verify its functionality
 */
class JgraphtStorageImplTest {
    private lateinit var storage: JgraphtStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge1 = EdgeID(node1, node2, "edge1")
    private val edge2 = EdgeID(node2, node3, "edge2")
    private val edge3 = EdgeID(node1, node3, "edge3")

    @Before
    fun setup() {
        storage = JgraphtStorageImpl()
    }

    @After
    fun cleanup() {
        storage.close()
    }

    @Test
    fun `test basic CRUD operations`() {
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

    @Test
    fun `test property updates`() {
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

    @Test
    fun `test entity removal`() {
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
        storage.deleteEdges { it.name == edge1.name }
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsEdge(edge2))

        // Test error cases
        assertFailsWith<EntityNotExistException> { storage.deleteNode(NodeID("nonexistent")) }
    }

    @Test
    fun `test collection views`() {
        // Setup
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        // Test node collection
        val nodeIds = storage.nodeIDs.toList()
        assertEquals(2, nodeIds.size)
        assertTrue(nodeIds.contains(node1))
        assertTrue(nodeIds.contains(node2))

        // Test edge collection
        val edgeIds = storage.edgeIDs.toList()
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

    @Test
    fun `test empty and null values`() {
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
        val emptyNodeIds = storage.nodeIDs.toList()
        assertEquals(2, emptyNodeIds.size)  // We added node1 and node2

        storage.clear()
        assertEquals(0, storage.nodeSize)
        assertEquals(0, storage.edgeSize)
        assertTrue(storage.nodeIDs.toList().isEmpty())
    }

    @Test
    fun `test complex data structures`() {
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

    @Test
    fun `test large dataset operations`() {
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

    @Test
    fun `test exception handling`() {
        // Test behavior under various exceptional conditions

        // Try to add a duplicate node
        storage.addNode(node1)
        assertFailsWith<EntityAlreadyExistException> {
            storage.addNode(node1)
        }

        // Try to delete a non-existent node
        assertFailsWith<EntityNotExistException> {
            storage.deleteNode(node2)
        }

        // Try to add an edge connecting non-existent nodes
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edge1)
        }

        // Try operations after closing storage
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeSize }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(node2) }
    }
}