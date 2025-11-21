package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

/**
 * Test to verify that GraphName prefix handling for String-based vs NodeID-based methods.
 */
class GraphNamePrefixTest {

    private lateinit var graph: AbcBasicGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = GraphTestUtils.createTestBasicGraph(storage)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // String-based methods (with transparent prefix)
    // ============================================================================

    @Test
    fun `test addNode with String transparently adds graph name prefix`() {
        // Arrange - user provides String name
        val userName = "myNode"
        
        // Act - addNode(String) should transparently add prefix
        val node = graph.addNode(userName)
        
        // Assert - internal NodeID should have prefix
        assertEquals("${graph.graphName}:$userName", node.id.asString)
    }

    @Test
    fun `test getNode with String transparently adds graph name prefix`() {
        // Arrange - add node with String
        val userName = "myNode"
        graph.addNode(userName)
        
        // Act - getNode(String) should transparently add prefix for lookup
        val retrievedNode = graph.getNode(userName)
        
        // Assert - should find the node
        assertNotNull(retrievedNode)
        assertEquals("${graph.graphName}:$userName", retrievedNode?.id?.asString)
    }

    // ============================================================================
    // NodeID-based methods (no modification)
    // ============================================================================

    @Test
    fun `test addNode with NodeID does not modify the ID`() {
        // Arrange - user provides pre-created NodeID
        val userNodeID = NodeID("myNode")
        
        // Act - addNode(NodeID) should NOT modify the ID
        val node = graph.addNode(userNodeID)
        
        // Assert - NodeID should remain exactly as provided
        assertEquals("myNode", node.id.asString)
        assertEquals(userNodeID, node.id)
    }

    @Test
    fun `test getNode with NodeID does not add prefix`() {
        // Arrange - add node with pre-created NodeID
        val userNodeID = NodeID("myNode")
        graph.addNode(userNodeID)
        
        // Act - getNode(NodeID) should use the ID as-is
        val retrievedNode = graph.getNode(userNodeID)
        
        // Assert - should find the node
        assertNotNull(retrievedNode)
        assertEquals("myNode", retrievedNode?.id?.asString)
        assertEquals(userNodeID, retrievedNode?.id)
    }

    // ============================================================================
    // Mixed usage tests
    // ============================================================================

    @Test
    fun `test String-based and NodeID-based methods create different nodes`() {
        // Arrange & Act
        val nodeFromString = graph.addNode("myNode")  // Creates NodeID("GraphName:myNode")
        val nodeFromNodeID = graph.addNode(NodeID("myNode"))  // Uses NodeID("myNode") as-is
        
        // Assert - they are different nodes
        assertNotEquals(nodeFromString.id, nodeFromNodeID.id)
        assertEquals("${graph.graphName}:myNode", nodeFromString.id.asString)
        assertEquals("myNode", nodeFromNodeID.id.asString)
    }

    @Test
    fun `test getNode String vs NodeID retrieve different nodes`() {
        // Arrange - add both types
        graph.addNode("myNode")  // Creates NodeID("GraphName:myNode")
        graph.addNode(NodeID("myNode"))  // Creates NodeID("myNode")
        
        // Act
        val nodeFromString = graph.getNode("myNode")  // Looks for NodeID("GraphName:myNode")
        val nodeFromNodeID = graph.getNode(NodeID("myNode"))  // Looks for NodeID("myNode")
        
        // Assert - both should be found, but different
        assertNotNull(nodeFromString)
        assertNotNull(nodeFromNodeID)
        assertNotEquals(nodeFromString?.id, nodeFromNodeID?.id)
    }

    @Test
    fun `test addEdge works with String-based created nodes`() {
        // Arrange - add nodes with String-based method
        val node1 = graph.addNode("node1")
        val node2 = graph.addNode("node2")
        
        // Act - addEdge should work
        val edge = graph.addEdge(node1, node2, "relation")
        
        // Assert - edge should reference correctly prefixed NodeIDs
        assertEquals("${graph.graphName}:node1", edge.srcNid.asString)
        assertEquals("${graph.graphName}:node2", edge.dstNid.asString)
        assertTrue(edge.eType.startsWith(graph.graphName))
    }
}

