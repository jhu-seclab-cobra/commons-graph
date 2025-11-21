package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

/**
 * White-box tests for AbcSimpleGraph focusing on internal implementation details, boundary conditions, and state consistency.
 */
class AbcSimpleGraphWhiteBoxTest {

    private lateinit var graph: GraphTestUtils.TestSimpleGraph
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = GraphTestUtils.TestSimpleGraph(storage)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // EDGE TYPE PREFIXING TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test edge type prefix format is GraphName type`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertEquals("${graph.graphName}:relation", edge.eType)
    }

    @Test
    fun `test edge type prefix format with empty type is GraphName prefix`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2)

        // Assert
        assertEquals("${graph.graphName}:", edge.eType)
    }

    @Test
    fun `test getEdge with type constructs EdgeID with prefix`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act
        val retrievedEdge = graph.getEdge(node1, node2, "relation")

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals("${graph.graphName}:relation", retrievedEdge?.eType)
    }

    // ============================================================================
    // EDGE UNIQUENESS CHECK TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test getEdge without type checks default empty type first`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2) // Empty type

        // Act
        val retrievedEdge = graph.getEdge(node1, node2)

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals(edge.id, retrievedEdge?.id)
    }

    @Test
    fun `test getEdge without type searches incoming edges when default not found`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act
        val retrievedEdge = graph.getEdge(node1, node2)

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals(edge.id, retrievedEdge.id)
    }

    @Test
    fun `test getEdge without type returns null when multiple edges exist`() {
        // Arrange
        // This test verifies that getEdge without type returns null when multiple edges exist
        // However, in AbcSimpleGraph, only one edge is allowed, so we need to test the edge case
        // where the storage might have multiple edges but cache has only one
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation")

        // Manually add another edge to storage (bypassing graph constraints)
        val edgeId2 = EdgeID(node1.id, node2.id, "${graph.graphName}:relation2")
        storage.addEdge(edgeId2)
        graph.exposeCacheEIDs().add(edgeId2)

        // Act
        val retrievedEdge = graph.getEdge(node1, node2)

        // Assert
        assertNull(retrievedEdge) // Should return null when multiple edges exist
    }

    @Test
    fun `test getEdge without type returns null when no edges exist`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.getEdge(node1, node2)

        // Assert
        assertNull(edge)
    }

    // ============================================================================
    // NODE WRAPPING TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test addEdge wraps source node if not in cache`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        val node1 = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        graph.addEdge(node1, node2, "relation")

        // Assert
        assertTrue(graph.exposeCacheNIDs().contains(GraphTestUtils.nodeId1))
    }

    @Test
    fun `test addEdge wraps destination node if not in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        storage.addNode(GraphTestUtils.nodeId2)
        val node2 = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId2)

        // Act
        graph.addEdge(node1, node2, "relation")

        // Assert
        assertTrue(graph.exposeCacheNIDs().contains(GraphTestUtils.nodeId2))
    }

    @Test
    fun `test addEdge does not wrap nodes already in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val initialCacheSize = graph.exposeCacheNIDs().size

        // Act
        graph.addEdge(node1, node2, "relation")

        // Assert
        assertEquals(initialCacheSize, graph.exposeCacheNIDs().size)
    }

    // ============================================================================
    // EDGE ID CONSTRUCTION TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test addEdge creates EdgeID with correct source and destination`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertEquals(node1.id, edge.srcNid)
        assertEquals(node2.id, edge.dstNid)
    }

    @Test
    fun `test addEdge creates EdgeID with prefixed type`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertEquals("${graph.graphName}:relation", edge.id.eType)
    }

    // ============================================================================
    // STORAGE INTEGRATION TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test addEdge adds edge to storage if not present`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertTrue(storage.containsEdge(edge.id))
    }

    @Test
    fun `test addEdge does not add edge to storage if already present`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "${graph.graphName}:relation")
        storage.addEdge(edgeId)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertTrue(storage.containsEdge(edge.id))
        assertEquals(edge.id, edgeId)
    }

    @Test
    fun `test getEdge checks cache before querying storage`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "${graph.graphName}:relation")
        storage.addEdge(edgeId) // Add to storage but not cache

        // Act
        val edge = graph.getEdge(node1, node2, "relation")

        // Assert
        assertNull(edge) // Should return null because not in cache
    }

    // ============================================================================
    // BOUNDARY CONDITIONS TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test addEdge with empty string type creates edge with GraphName prefix`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "")

        // Assert
        assertEquals("${graph.graphName}:", edge.eType)
    }

    @Test
    fun `test getEdge with empty string type matches empty type edge`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "")

        // Act
        val retrievedEdge = graph.getEdge(node1, node2, "")

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals(edge.id, retrievedEdge?.id)
    }

    @Test
    fun `test getEdge without type handles case when default edge exists`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val defaultEdgeId = EdgeID(node1.id, node2.id, "${graph.graphName}:")
        storage.addEdge(defaultEdgeId)
        graph.exposeCacheEIDs().add(defaultEdgeId)

        // Act
        val edge = graph.getEdge(node1, node2)

        // Assert
        assertNotNull(edge)
        assertEquals(defaultEdgeId, edge?.id)
    }
}

