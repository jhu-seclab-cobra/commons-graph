package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

/**
 * Black-box tests for AbcMultiGraph focusing on public API contracts, inputs, outputs, and documented behaviors.
 * Tests from user perspective without knowledge of internal implementation.
 */
class AbcMultiGraphBlackBoxTest {

    private lateinit var graph: AbcMultiGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = GraphTestUtils.createTestMultiGraph(storage)
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
    fun `test addEdge without type adds edge with UUID type`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2)

        // Assert
        assertNotNull(edge)
        assertTrue(edge.eType.startsWith(graph.graphName))
        assertTrue(edge.eType.length > graph.graphName.length + 1) // UUID should be added
    }

    @Test
    fun `test addEdge throws EntityAlreadyExistException when edge with same ID exists`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation")

        // Act & Assert
        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(node1, node2, "relation")
        }
    }

    @Test
    fun `test addEdge allows multiple edges between same nodes with different types`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge1 = graph.addEdge(node1, node2, "relation1")

        // Act
        val edge2 = graph.addEdge(node1, node2, "relation2")

        // Assert
        assertNotNull(edge2)
        assertNotEquals(edge1.id, edge2.id)
        assertTrue(graph.containEdge(edge1))
        assertTrue(graph.containEdge(edge2))
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
    fun `test getEdges returns all edges between two nodes`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node1, node2, "relation2")
        val edge3 = graph.addEdge(node1, node2) // UUID type

        // Act
        val edges = graph.getEdges(node1, node2).toList()

        // Assert
        assertEquals(3, edges.size)
        assertTrue(edges.any { it.id == edge1.id })
        assertTrue(edges.any { it.id == edge2.id })
        assertTrue(edges.any { it.id == edge3.id })
    }

    @Test
    fun `test getEdges returns empty sequence when no edges exist`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edges = graph.getEdges(node1, node2).toList()

        // Assert
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getEdges returns only edges between specified nodes`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node1, node2, "relation2")
        val edge3 = graph.addEdge(node1, node3, "relation3")

        // Act
        val edges = graph.getEdges(node1, node2).toList()

        // Assert
        assertEquals(2, edges.size)
        assertTrue(edges.any { it.id == edge1.id })
        assertTrue(edges.any { it.id == edge2.id })
        assertFalse(edges.any { it.id == edge3.id })
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
    // MULTI-GRAPH CONSTRAINT TESTS - Public API
    // ============================================================================

    @Test
    fun `test multiple edges allowed between same nodes with different types`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge1 = graph.addEdge(node1, node2, "relation1")

        // Act
        val edge2 = graph.addEdge(node1, node2, "relation2")
        val edge3 = graph.addEdge(node1, node2, "relation3")

        // Assert
        assertNotNull(edge2)
        assertNotNull(edge3)
        assertNotEquals(edge1.id, edge2.id)
        assertNotEquals(edge1.id, edge3.id)
        assertNotEquals(edge2.id, edge3.id)
        assertTrue(graph.containEdge(edge1))
        assertTrue(graph.containEdge(edge2))
        assertTrue(graph.containEdge(edge3))
    }

    @Test
    fun `test multiple edges allowed between same nodes with UUID types`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge1 = graph.addEdge(node1, node2)

        // Act
        val edge2 = graph.addEdge(node1, node2)
        val edge3 = graph.addEdge(node1, node2)

        // Assert
        assertNotNull(edge2)
        assertNotNull(edge3)
        assertNotEquals(edge1.id, edge2.id)
        assertNotEquals(edge1.id, edge3.id)
        assertNotEquals(edge2.id, edge3.id)
        assertTrue(graph.containEdge(edge1))
        assertTrue(graph.containEdge(edge2))
        assertTrue(graph.containEdge(edge3))
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
    fun `test addEdge and getEdges work together correctly`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node1, node2, "relation2")
        val edge3 = graph.addEdge(node1, node2)
        val edges = graph.getEdges(node1, node2).toList()

        // Assert
        assertEquals(3, edges.size)
        assertTrue(edges.any { it.id == edge1.id })
        assertTrue(edges.any { it.id == edge2.id })
        assertTrue(edges.any { it.id == edge3.id })
    }
}

