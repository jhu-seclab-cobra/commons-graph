package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

/**
 * White-box tests for AbcBasicGraph focusing on internal implementation details, boundary conditions, and state consistency.
 */
class AbcBasicGraphWhiteBoxTest {

    private lateinit var graph: GraphTestUtils.TestBasicGraph
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = GraphTestUtils.TestBasicGraph(storage)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // CACHE STATE CONSISTENCY TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test cacheNIDs is synchronized after adding node`() {
        // Arrange
        val nodeId = GraphTestUtils.nodeId1

        // Act
        graph.addNode(nodeId)

        // Assert - verify cache contains node ID
        assertTrue(graph.exposeCacheNIDs().contains(nodeId))
        assertEquals(1, graph.exposeCacheNIDs().size)
    }

    @Test
    fun `test cacheEIDs is synchronized after adding edge`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert - verify cache contains edge ID
        assertTrue(graph.exposeCacheEIDs().contains(edge.id))
        assertEquals(1, graph.exposeCacheEIDs().size)
    }

    @Test
    fun `test cacheNIDs is synchronized after deleting node`() {
        // Arrange
        val node = graph.addNode(GraphTestUtils.nodeId1)

        // Act
        graph.delNode(node)

        // Assert - verify cache no longer contains node ID
        assertFalse(graph.exposeCacheNIDs().contains(node.id))
        assertEquals(0, graph.exposeCacheNIDs().size)
    }

    @Test
    fun `test cacheEIDs is synchronized after deleting edge`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act
        graph.delEdge(edge)

        // Assert - verify cache no longer contains edge ID
        assertFalse(graph.exposeCacheEIDs().contains(edge.id))
        assertEquals(0, graph.exposeCacheEIDs().size)
    }

    @Test
    fun `test cacheEIDs is cleared when node is deleted with associated edges`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node2, node3, "relation2")
        val edge3 = graph.addEdge(node1, node3, "relation3")

        // Act
        graph.delNode(node1)

        // Assert - verify all edges connected to node1 are removed from cache
        assertFalse(graph.exposeCacheEIDs().contains(edge1.id))
        assertFalse(graph.exposeCacheEIDs().contains(edge3.id))
        assertTrue(graph.exposeCacheEIDs().contains(edge2.id)) // edge2 not connected to node1
    }

    @Test
    fun `test entitySize reflects cache state correctly`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert - verify entitySize matches cache sizes
        assertEquals(graph.exposeCacheNIDs().size + graph.exposeCacheEIDs().size, graph.entitySize)
        assertEquals(3, graph.entitySize)
    }

    // ============================================================================
    // BOUNDARY CONDITIONS TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test containNode returns false when node not in cache but in storage`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act & Assert
        assertFalse(graph.containNode(node))
    }

    @Test
    fun `test containNode returns false when node in cache but not in storage`() {
        // Arrange
        graph.exposeCacheNIDs().add(GraphTestUtils.nodeId1)
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act & Assert
        assertFalse(graph.containNode(node))
    }

    @Test
    fun `test containEdge returns false when edge not in cache but in storage`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "relation")
        storage.addEdge(edgeId)
        val edge = GraphTestUtils.TestEdge(storage, edgeId)

        // Act & Assert
        assertFalse(graph.containEdge(edge))
    }

    @Test
    fun `test containEdge returns false when edge in cache but not in storage`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "relation")
        graph.exposeCacheEIDs().add(edgeId)
        val edge = GraphTestUtils.TestEdge(storage, edgeId)

        // Act & Assert
        assertFalse(graph.containEdge(edge))
    }

    @Test
    fun `test getNode returns null when node not in cache`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)

        // Act
        val node = graph.getNode(GraphTestUtils.nodeId1)

        // Assert
        assertNull(node)
    }

    @Test
    fun `test getNode returns null when node in cache but not in storage`() {
        // Arrange
        graph.exposeCacheNIDs().add(GraphTestUtils.nodeId1)

        // Act
        val node = graph.getNode(GraphTestUtils.nodeId1)

        // Assert
        assertNull(node)
    }

    @Test
    fun `test getEdge returns null when edge not in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "relation")
        storage.addEdge(edgeId)

        // Act
        val edge = graph.getEdge(edgeId)

        // Assert
        assertNull(edge)
    }

    @Test
    fun `test getEdge returns null when edge in cache but not in storage`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "relation")
        graph.exposeCacheEIDs().add(edgeId)

        // Act
        val edge = graph.getEdge(edgeId)

        // Assert
        assertNull(edge)
    }

    @Test
    fun `test getAllNodes filters nodes not in storage`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        graph.exposeCacheNIDs().add(GraphTestUtils.nodeId2) // Add to cache but not storage

        // Act
        val nodes = graph.getAllNodes().toList()

        // Assert
        assertEquals(1, nodes.size)
        assertEquals(GraphTestUtils.nodeId1, nodes.first().id)
    }

    @Test
    fun `test getAllEdges filters edges not in storage`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edgeId2 = EdgeID(node1.id, node2.id, "relation2")
        graph.exposeCacheEIDs().add(edgeId2) // Add to cache but not storage

        // Act
        val edges = graph.getAllEdges().toList()

        // Assert
        assertEquals(1, edges.size)
        assertEquals(edge1.id, edges.first().id)
    }

    // ============================================================================
    // EDGE CASE TESTS - Internal Implementation
    // ============================================================================

    @Test
    fun `test getOutgoingEdges returns empty sequence when node not in cache`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act
        val edges = graph.getOutgoingEdges(node).toList()

        // Assert
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getOutgoingEdges filters edges not in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "relation")
        storage.addEdge(edgeId) // Add to storage but not cache

        // Act
        val edges = graph.getOutgoingEdges(node1).toList()

        // Assert
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getIncomingEdges returns empty sequence when node not in cache`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act
        val edges = graph.getIncomingEdges(node).toList()

        // Assert
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getIncomingEdges filters edges not in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "relation")
        storage.addEdge(edgeId) // Add to storage but not cache

        // Act
        val edges = graph.getIncomingEdges(node2).toList()

        // Assert
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getDescendants returns empty sequence when node not in cache`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act
        val descendants = graph.getDescendants(node).toList()

        // Assert
        assertTrue(descendants.isEmpty())
    }

    @Test
    fun `test getDescendants filters edges not in cache during traversal`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edgeId1 = EdgeID(node1.id, node2.id, "relation1")
        val edgeId2 = EdgeID(node2.id, node3.id, "relation2")
        storage.addEdge(edgeId1)
        graph.exposeCacheEIDs().add(edgeId1) // Add to cache
        storage.addEdge(edgeId2) // Add to storage but not cache

        // Act
        val descendants = graph.getDescendants(node1).toList()

        // Assert
        assertEquals(1, descendants.size)
        assertEquals(GraphTestUtils.nodeId2, descendants.first().id)
    }

    @Test
    fun `test getAncestors returns empty sequence when node not in cache`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act
        val ancestors = graph.getAncestors(node).toList()

        // Assert
        assertTrue(ancestors.isEmpty())
    }

    @Test
    fun `test getAncestors filters edges not in cache during traversal`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edgeId1 = EdgeID(node1.id, node2.id, "relation1")
        val edgeId2 = EdgeID(node2.id, node3.id, "relation2")
        storage.addEdge(edgeId1)
        graph.exposeCacheEIDs().add(edgeId1) // Add to cache
        storage.addEdge(edgeId2) // Add to storage but not cache

        // Act
        val ancestors = graph.getAncestors(node3).toList()

        // Assert
        assertEquals(1, ancestors.size)
        assertEquals(GraphTestUtils.nodeId2, ancestors.first().id)
    }

    @Test
    fun `test getDescendants handles cycles correctly`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node2, node1, "relation2") // Create cycle

        // Act
        val descendants = graph.getDescendants(node1).toList()

        // Assert
        assertEquals(1, descendants.size)
        assertEquals(GraphTestUtils.nodeId2, descendants.first().id)
    }

    @Test
    fun `test getAncestors handles cycles correctly`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node2, node1, "relation2") // Create cycle

        // Act
        val ancestors = graph.getAncestors(node1).toList()

        // Assert
        assertEquals(1, ancestors.size)
        assertEquals(GraphTestUtils.nodeId2, ancestors.first().id)
    }

    @Test
    fun `test delNode does nothing when node not in cache`() {
        // Arrange
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)
        storage.addNode(GraphTestUtils.nodeId1)

        // Act
        graph.delNode(node)

        // Assert
        assertTrue(storage.containsNode(GraphTestUtils.nodeId1))
    }

    @Test
    fun `test delEdge does nothing when edge not in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edgeId = EdgeID(node1.id, node2.id, "relation")
        storage.addEdge(edgeId)
        val edge = GraphTestUtils.TestEdge(storage, edgeId)

        // Act
        graph.delEdge(edge)

        // Assert
        assertTrue(storage.containsEdge(edgeId))
    }

    @Test
    fun `test refreshCache adds nodes from edge endpoints`() {
        // Arrange
        val edgeId = EdgeID(GraphTestUtils.nodeId1, GraphTestUtils.nodeId2, "${graph.graphName}:relation")
        storage.addEdge(edgeId)

        // Act
        graph.refreshCache()

        // Assert
        assertTrue(graph.exposeCacheNIDs().contains(GraphTestUtils.nodeId1))
        assertTrue(graph.exposeCacheNIDs().contains(GraphTestUtils.nodeId2))
        assertTrue(graph.exposeCacheEIDs().contains(edgeId))
    }

    @Test
    fun `test refreshCache clears existing cache before loading`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")
        storage.deleteNode(GraphTestUtils.nodeId1)
        storage.deleteNode(GraphTestUtils.nodeId2)
        storage.deleteEdge(edge.id)

        // Act
        graph.refreshCache()

        // Assert
        assertEquals(0, graph.exposeCacheNIDs().size)
        assertEquals(0, graph.exposeCacheEIDs().size)
    }

    @Test
    fun `test clearCache does not affect storage`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act
        graph.clearCache()

        // Assert
        assertTrue(storage.containsNode(GraphTestUtils.nodeId1))
        assertTrue(storage.containsNode(GraphTestUtils.nodeId2))
        assertTrue(storage.containsEdge(edge.id))
    }

    @Test
    fun `test wrapNode adds node to cache if not present`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act
        graph.wrapNode(node)

        // Assert
        assertTrue(graph.exposeCacheNIDs().contains(GraphTestUtils.nodeId1))
    }

    @Test
    fun `test wrapNode returns cached node when already in cache`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val genericNode = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act
        val wrappedNode = graph.wrapNode(genericNode)

        // Assert
        assertTrue(graph.exposeCacheNIDs().contains(GraphTestUtils.nodeId1))
        assertEquals(GraphTestUtils.nodeId1, wrappedNode.id)
    }
}

