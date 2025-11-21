package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

/**
 * Black-box tests for AbcSimpleGraph focusing on public API contracts, inputs, outputs, and documented behaviors.
 * Tests from user perspective without knowledge of internal implementation.
 */
class AbcSimpleGraphBlackBoxTest {

    private lateinit var graph: AbcSimpleGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = GraphTestUtils.createTestSimpleGraph(storage)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // EDGE ADDITION TESTS - Public API
    // ============================================================================

    @Test
    fun `test addEdge with type adds edge successfully`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertNotNull(edge)
        assertTrue(edge.eType.startsWith(graph.graphName))
        assertTrue(edge.eType.contains("relation"))
        assertTrue(graph.containEdge(edge))
    }

    @Test
    fun `test addEdge without type adds edge with empty type`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2)

        // Assert
        assertNotNull(edge)
        assertTrue(edge.eType.startsWith(graph.graphName))
        assertTrue(graph.containEdge(edge))
    }

    @Test
    fun `test addEdge throws EntityAlreadyExistException when edge already exists`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation")

        // Act & Assert
        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(node1, node2, "differentType")
        }
    }

    @Test
    fun `test addEdge throws EntityAlreadyExistException when edge exists without type`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2)

        // Act & Assert
        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(node1, node2, "relation")
        }
    }

    @Test
    fun `test addEdge automatically wraps non-existent source node`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        val node1 = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertNotNull(edge)
        assertTrue(graph.containNode(node1))
    }

    @Test
    fun `test addEdge automatically wraps non-existent destination node`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        storage.addNode(GraphTestUtils.nodeId2)
        val node2 = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertNotNull(edge)
        assertTrue(graph.containNode(node2))
    }

    // ============================================================================
    // EDGE RETRIEVAL TESTS - Public API
    // ============================================================================

    @Test
    fun `test getEdge with type returns edge when it exists`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val addedEdge = graph.addEdge(node1, node2, "relation")

        // Act
        val retrievedEdge = graph.getEdge(node1, node2, "relation")

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals(addedEdge.id, retrievedEdge?.id)
    }

    @Test
    fun `test getEdge with type returns null when edge does not exist`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.getEdge(node1, node2, "nonexistent")

        // Assert
        assertNull(edge)
    }

    @Test
    fun `test getEdge without type returns edge when single edge exists`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val addedEdge = graph.addEdge(node1, node2, "relation")

        // Act
        val retrievedEdge = graph.getEdge(node1, node2)

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals(addedEdge.id, retrievedEdge?.id)
    }

    @Test
    fun `test getEdge without type returns edge when empty type edge exists`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val addedEdge = graph.addEdge(node1, node2)

        // Act
        val retrievedEdge = graph.getEdge(node1, node2)

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals(addedEdge.id, retrievedEdge?.id)
    }

    @Test
    fun `test getEdge without type returns null when no edge exists`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.getEdge(node1, node2)

        // Assert
        assertNull(edge)
    }

    // ============================================================================
    // GRAPH NAME PREFIX TESTS - Public API
    // ============================================================================

    @Test
    fun `test edge type is automatically prefixed with graph name`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertTrue(edge.eType.startsWith(graph.graphName))
        assertTrue(edge.eType.contains("relation"))
    }

    @Test
    fun `test getEdge automatically adds graph name prefix transparently`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val addedEdge = graph.addEdge(node1, node2, "relation")

        // Act
        val retrievedEdge = graph.getEdge(node1, node2, "relation")
        val edgeWithDoublePrefix = graph.getEdge(node1, node2, "${graph.graphName}:relation")

        // Assert
        assertNotNull(retrievedEdge) // Should find edge - graphName prefix is added transparently
        assertEquals(addedEdge.id, retrievedEdge?.id)
        assertNull(edgeWithDoublePrefix) // Should not match - would create double prefix
    }

    // ============================================================================
    // SIMPLE GRAPH CONSTRAINT TESTS - Public API
    // ============================================================================

    @Test
    fun `test only one edge allowed between two nodes in same direction`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation1")

        // Act & Assert
        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(node1, node2, "relation2")
        }
    }

    @Test
    fun `test edges in opposite direction are allowed`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge1 = graph.addEdge(node1, node2, "relation1")

        // Act
        val edge2 = graph.addEdge(node2, node1, "relation2")

        // Assert
        assertNotNull(edge2)
        assertNotEquals(edge1.id, edge2.id)
        assertTrue(graph.containEdge(edge1))
        assertTrue(graph.containEdge(edge2))
    }

    @Test
    fun `test multiple edges allowed between different node pairs`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edge1 = graph.addEdge(node1, node2, "relation1")

        // Act
        val edge2 = graph.addEdge(node1, node3, "relation2")
        val edge3 = graph.addEdge(node2, node3, "relation3")

        // Assert
        assertNotNull(edge2)
        assertNotNull(edge3)
        assertTrue(graph.containEdge(edge1))
        assertTrue(graph.containEdge(edge2))
        assertTrue(graph.containEdge(edge3))
    }

    // ============================================================================
    // INTEGRATION TESTS - Public API
    // ============================================================================

    @Test
    fun `test addEdge and getEdge work together correctly`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)

        // Act
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node2, node3, "relation2")
        val retrievedEdge1 = graph.getEdge(node1, node2, "relation1")
        val retrievedEdge2 = graph.getEdge(node2, node3, "relation2")

        // Assert
        assertNotNull(retrievedEdge1)
        assertNotNull(retrievedEdge2)
        assertEquals(edge1.id, retrievedEdge1?.id)
        assertEquals(edge2.id, retrievedEdge2?.id)
    }

    @Test
    fun `test addEdge without type and getEdge without type work together`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2)
        val retrievedEdge = graph.getEdge(node1, node2)

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals(edge.id, retrievedEdge?.id)
    }
}

