package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

/**
 * White-box tests for AbcMultiGraph focusing on internal implementation details, boundary conditions, and state consistency.
 */
class AbcMultiGraphWhiteBoxTest {

    private lateinit var graph: GraphTestUtils.TestMultiGraph
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = GraphTestUtils.TestMultiGraph(storage)
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
    fun `test edge type prefix format with UUID includes GraphName prefix`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2)

        // Assert
        assertTrue(edge.eType.startsWith(graph.graphName))
        assertTrue(edge.eType.length > graph.graphName.length + 1) // UUID should be added
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
    // UUID GENERATION TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test addEdge without type generates unique UUID for each edge`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge1 = graph.addEdge(node1, node2)
        val edge2 = graph.addEdge(node1, node2)
        val edge3 = graph.addEdge(node1, node2)

        // Assert
        assertNotEquals(edge1.eType, edge2.eType)
        assertNotEquals(edge1.eType, edge3.eType)
        assertNotEquals(edge2.eType, edge3.eType)
    }

    @Test
    fun `test UUID type includes graph name prefix`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2)

        // Assert
        val parts = edge.eType.split(":")
        assertEquals(2, parts.size)
        assertEquals(graph.graphName, parts[0])
        assertTrue(parts[1].isNotEmpty()) // UUID part should not be empty
    }

    // ============================================================================
    // EDGE ID UNIQUENESS TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test addEdge checks cache for existing edge ID`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act & Assert
        assertTrue(graph.exposeCacheEIDs().contains(edge.id))
    }

    @Test
    fun `test addEdge throws exception when edge ID already in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation")

        // Act & Assert
        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(node1, node2, "relation")
        }
    }

    // ============================================================================
    // NODE WRAPPING TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test addEdge wraps both nodes if not in cache`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        storage.addNode(GraphTestUtils.nodeId2)
        val node1 = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)
        val node2 = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId2)

        // Act
        graph.addEdge(node1, node2, "relation")

        // Assert
        assertTrue(graph.exposeCacheNIDs().contains(GraphTestUtils.nodeId1))
        assertTrue(graph.exposeCacheNIDs().contains(GraphTestUtils.nodeId2))
    }

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
    // GETEDGES IMPLEMENTATION TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test getEdges filters edges by destination node`() {
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

    @Test
    fun `test getEdges filters edges not in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edgeId2 = EdgeID(node1.id, node2.id, "${graph.graphName}:relation2")
        storage.addEdge(edgeId2) // Add to storage but not cache

        // Act
        val edges = graph.getEdges(node1, node2).toList()

        // Assert
        assertEquals(1, edges.size)
        assertEquals(edge1.id, edges.first().id)
    }

    @Test
    fun `test getEdges returns empty sequence when no matching edges`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        graph.addEdge(node1, node3, "relation")

        // Act
        val edges = graph.getEdges(node1, node2).toList()

        // Assert
        assertTrue(edges.isEmpty())
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
    fun `test multiple edges with same type prefix but different UUIDs are unique`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge1 = graph.addEdge(node1, node2)
        val edge2 = graph.addEdge(node1, node2)
        val edge3 = graph.addEdge(node1, node2)

        // Assert
        assertNotEquals(edge1.id, edge2.id)
        assertNotEquals(edge1.id, edge3.id)
        assertNotEquals(edge2.id, edge3.id)
        // All should have graph name prefix
        assertTrue(edge1.eType.startsWith(graph.graphName))
        assertTrue(edge2.eType.startsWith(graph.graphName))
        assertTrue(edge3.eType.startsWith(graph.graphName))
    }
}

