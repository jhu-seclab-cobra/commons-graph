package edu.jhu.cobra.commons.graph.impl

import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.graph.storage.StorageTestUtils
import kotlin.test.*

/**
 * Black-box tests for AbcBasicGraph focusing on public API contracts, inputs, outputs, and documented behaviors.
 * Tests from user perspective without knowledge of internal implementation.
 */
class AbcBasicGraphBlackBoxTest {

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
    // PROPERTY TESTS - Public API
    // ============================================================================

    @Test
    fun `test graphName property returns graph name`() {
        // Assert
        assertNotNull(graph.graphName)
        assertTrue(graph.graphName.isNotEmpty())
    }

    @Test
    fun `test entitySize property returns zero for empty graph`() {
        // Assert
        assertEquals(0, graph.entitySize)
    }

    @Test
    fun `test entitySize property returns correct count after adding nodes`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Assert
        assertEquals(2, graph.entitySize)
    }

    @Test
    fun `test entitySize property returns correct count after adding nodes and edges`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertEquals(3, graph.entitySize)
    }

    // ============================================================================
    // CONTAINMENT TESTS - Public API
    // ============================================================================

    @Test
    fun `test containNode returns true for existing node`() {
        // Arrange
        val node = graph.addNode(GraphTestUtils.nodeId1)

        // Act & Assert
        assertTrue(graph.containNode(node))
    }

    @Test
    fun `test containNode returns false for non-existent node`() {
        // Arrange
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act & Assert
        assertFalse(graph.containNode(node))
    }

    @Test
    fun `test containEdge returns true for existing edge`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act & Assert
        assertTrue(graph.containEdge(edge))
    }

    @Test
    fun `test containEdge returns false for non-existent edge`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = GraphTestUtils.TestEdge(storage, GraphTestUtils.edgeId1)

        // Act & Assert
        assertFalse(graph.containEdge(edge))
    }

    // ============================================================================
    // NODE OPERATIONS TESTS - Public API
    // ============================================================================

    @Test
    fun `test addNode adds new node successfully`() {
        // Act
        val node = graph.addNode(GraphTestUtils.nodeId1)

        // Assert
        assertNotNull(node)
        assertEquals(GraphTestUtils.nodeId1, node.id)
        assertTrue(graph.containNode(node))
    }

    @Test
    fun `test addNode throws EntityAlreadyExistException when node already exists`() {
        // Arrange
        graph.addNode(GraphTestUtils.nodeId1)

        // Act & Assert
        assertFailsWith<EntityAlreadyExistException> {
            graph.addNode(GraphTestUtils.nodeId1)
        }
    }

    @Test
    fun `test getNode returns node when it exists`() {
        // Arrange
        val addedNode = graph.addNode(GraphTestUtils.nodeId1)

        // Act
        val retrievedNode = graph.getNode(GraphTestUtils.nodeId1)

        // Assert
        assertNotNull(retrievedNode)
        assertEquals(addedNode.id, retrievedNode?.id)
    }

    @Test
    fun `test getNode returns null when node does not exist`() {
        // Act
        val node = graph.getNode(GraphTestUtils.nodeId1)

        // Assert
        assertNull(node)
    }

    @Test
    fun `test wrapNode wraps existing node successfully`() {
        // Arrange
        val node = graph.addNode(GraphTestUtils.nodeId1)
        val genericNode = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act
        val wrappedNode = graph.wrapNode(genericNode)

        // Assert
        assertNotNull(wrappedNode)
        assertEquals(node.id, wrappedNode.id)
    }

    @Test
    fun `test wrapNode throws EntityNotExistException when node does not exist`() {
        // Arrange
        val node = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)

        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            graph.wrapNode(node)
        }
    }

    @Test
    fun `test delNode deletes node and associated edges`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act
        graph.delNode(node1)

        // Assert
        assertFalse(graph.containNode(node1))
        assertFalse(graph.containEdge(edge))
        assertTrue(graph.containNode(node2))
    }

    // ============================================================================
    // EDGE OPERATIONS TESTS - Public API
    // ============================================================================

    @Test
    fun `test addEdge adds new edge successfully`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.addEdge(node1, node2, "relation")

        // Assert
        assertNotNull(edge)
        assertEquals(node1.id, edge.srcNid)
        assertEquals(node2.id, edge.dstNid)
        assertTrue(graph.containEdge(edge))
    }

    @Test
    fun `test addEdge throws EntityNotExistException when source node does not exist`() {
        // Arrange
        val node1 = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            graph.addEdge(node1, node2, "relation")
        }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when destination node does not exist`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = GraphTestUtils.TestNode(storage, GraphTestUtils.nodeId2)

        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            graph.addEdge(node1, node2, "relation")
        }
    }

    @Test
    fun `test getEdge by ID returns edge when it exists`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val addedEdge = graph.addEdge(node1, node2, "relation")

        // Act
        val retrievedEdge = graph.getEdge(addedEdge.id)

        // Assert
        assertNotNull(retrievedEdge)
        assertEquals(addedEdge.id, retrievedEdge?.id)
    }

    @Test
    fun `test getEdge by ID returns null when edge does not exist`() {
        // Act
        val edge = graph.getEdge(GraphTestUtils.edgeId1)

        // Assert
        assertNull(edge)
    }

    @Test
    fun `test getEdge by nodes and type returns edge when it exists`() {
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
    fun `test getEdge by nodes and type returns null when edge does not exist`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)

        // Act
        val edge = graph.getEdge(node1, node2, "nonexistent")

        // Assert
        assertNull(edge)
    }

    @Test
    fun `test delEdge deletes edge successfully`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act
        graph.delEdge(edge)

        // Assert
        assertFalse(graph.containEdge(edge))
        assertTrue(graph.containNode(node1))
        assertTrue(graph.containNode(node2))
    }

    // ============================================================================
    // QUERY OPERATIONS TESTS - Public API
    // ============================================================================

    @Test
    fun `test getAllNodes returns all nodes when no predicate`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)

        // Act
        val nodes = graph.getAllNodes().toList()

        // Assert
        assertEquals(3, nodes.size)
        assertTrue(nodes.any { it.id == GraphTestUtils.nodeId1 })
        assertTrue(nodes.any { it.id == GraphTestUtils.nodeId2 })
        assertTrue(nodes.any { it.id == GraphTestUtils.nodeId3 })
    }

    @Test
    fun `test getAllNodes returns empty sequence for empty graph`() {
        // Act
        val nodes = graph.getAllNodes().toList()

        // Assert
        assertTrue(nodes.isEmpty())
    }

    @Test
    fun `test getAllNodes filters nodes with predicate`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)

        // Act
        val nodes = graph.getAllNodes { it.id == GraphTestUtils.nodeId1 }.toList()

        // Assert
        assertEquals(1, nodes.size)
        assertEquals(GraphTestUtils.nodeId1, nodes.first().id)
    }

    @Test
    fun `test getAllEdges returns all edges when no predicate`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node2, node3, "relation2")

        // Act
        val edges = graph.getAllEdges().toList()

        // Assert
        assertEquals(2, edges.size)
        assertTrue(edges.any { it.id == edge1.id })
        assertTrue(edges.any { it.id == edge2.id })
    }

    @Test
    fun `test getAllEdges returns empty sequence for empty graph`() {
        // Act
        val edges = graph.getAllEdges().toList()

        // Assert
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getAllEdges filters edges with predicate`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node2, node3, "relation2")

        // Act
        val edges = graph.getAllEdges { it.srcNid == GraphTestUtils.nodeId1 }.toList()

        // Assert
        assertEquals(1, edges.size)
        assertEquals(edge1.id, edges.first().id)
    }

    @Test
    fun `test getIncomingEdges returns incoming edges for node`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node3, node2, "relation2")

        // Act
        val incomingEdges = graph.getIncomingEdges(node2).toList()

        // Assert
        assertEquals(2, incomingEdges.size)
        assertTrue(incomingEdges.any { it.id == edge1.id })
        assertTrue(incomingEdges.any { it.id == edge2.id })
    }

    @Test
    fun `test getIncomingEdges returns empty sequence when no incoming edges`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation")

        // Act
        val incomingEdges = graph.getIncomingEdges(node1).toList()

        // Assert
        assertTrue(incomingEdges.isEmpty())
    }

    @Test
    fun `test getOutgoingEdges returns outgoing edges for node`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val edge1 = graph.addEdge(node1, node2, "relation1")
        val edge2 = graph.addEdge(node1, node3, "relation2")

        // Act
        val outgoingEdges = graph.getOutgoingEdges(node1).toList()

        // Assert
        assertEquals(2, outgoingEdges.size)
        assertTrue(outgoingEdges.any { it.id == edge1.id })
        assertTrue(outgoingEdges.any { it.id == edge2.id })
    }

    @Test
    fun `test getOutgoingEdges returns empty sequence when no outgoing edges`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        graph.addEdge(node1, node2, "relation")

        // Act
        val outgoingEdges = graph.getOutgoingEdges(node2).toList()

        // Assert
        assertTrue(outgoingEdges.isEmpty())
    }

    // ============================================================================
    // TRAVERSAL OPERATIONS TESTS - Public API
    // ============================================================================

    @Test
    fun `test getChildren returns child nodes`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node1, node3, "relation2")

        // Act
        val children = graph.getChildren(node1).toList()

        // Assert
        assertEquals(2, children.size)
        assertTrue(children.any { it.id == GraphTestUtils.nodeId2 })
        assertTrue(children.any { it.id == GraphTestUtils.nodeId3 })
    }

    @Test
    fun `test getChildren filters by edge condition`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node1, node3, "relation2")

        // Act
        val children = graph.getChildren(node1) { it.eType.contains("relation1") }.toList()

        // Assert
        assertEquals(1, children.size)
        assertEquals(GraphTestUtils.nodeId2, children.first().id)
    }

    @Test
    fun `test getParents returns parent nodes`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node3, node2, "relation2")

        // Act
        val parents = graph.getParents(node2).toList()

        // Assert
        assertEquals(2, parents.size)
        assertTrue(parents.any { it.id == GraphTestUtils.nodeId1 })
        assertTrue(parents.any { it.id == GraphTestUtils.nodeId3 })
    }

    @Test
    fun `test getParents filters by edge condition`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node3, node2, "relation2")

        // Act
        val parents = graph.getParents(node2) { it.eType.contains("relation1") }.toList()

        // Assert
        assertEquals(1, parents.size)
        assertEquals(GraphTestUtils.nodeId1, parents.first().id)
    }

    @Test
    fun `test getDescendants returns all descendant nodes using BFS`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val node4 = graph.addNode(GraphTestUtils.nodeId4)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node2, node3, "relation2")
        graph.addEdge(node3, node4, "relation3")

        // Act
        val descendants = graph.getDescendants(node1).toList()

        // Assert
        assertEquals(3, descendants.size)
        assertTrue(descendants.any { it.id == GraphTestUtils.nodeId2 })
        assertTrue(descendants.any { it.id == GraphTestUtils.nodeId3 })
        assertTrue(descendants.any { it.id == GraphTestUtils.nodeId4 })
    }

    @Test
    fun `test getDescendants filters by edge condition`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node2, node3, "relation2")

        // Act
        val descendants = graph.getDescendants(node1) { it.eType.contains("relation1") }.toList()

        // Assert
        assertEquals(1, descendants.size)
        assertEquals(GraphTestUtils.nodeId2, descendants.first().id)
    }

    @Test
    fun `test getAncestors returns all ancestor nodes using DFS`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        val node4 = graph.addNode(GraphTestUtils.nodeId4)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node2, node3, "relation2")
        graph.addEdge(node3, node4, "relation3")

        // Act
        val ancestors = graph.getAncestors(node4).toList()

        // Assert
        assertEquals(3, ancestors.size)
        assertTrue(ancestors.any { it.id == GraphTestUtils.nodeId1 })
        assertTrue(ancestors.any { it.id == GraphTestUtils.nodeId2 })
        assertTrue(ancestors.any { it.id == GraphTestUtils.nodeId3 })
    }

    @Test
    fun `test getAncestors filters by edge condition`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val node3 = graph.addNode(GraphTestUtils.nodeId3)
        graph.addEdge(node1, node2, "relation1")
        graph.addEdge(node2, node3, "relation2")

        // Act
        val ancestors = graph.getAncestors(node3) { it.eType.contains("relation2") }.toList()

        // Assert
        assertEquals(1, ancestors.size)
        assertEquals(GraphTestUtils.nodeId2, ancestors.first().id)
    }

    // ============================================================================
    // CACHE OPERATIONS TESTS - Public API
    // ============================================================================

    @Test
    fun `test refreshCache loads nodes and edges from storage`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        storage.addNode(GraphTestUtils.nodeId2)
        val edgeId = GraphTestUtils.edgeId1.copy(eType = "${graph.graphName}:relation")
        storage.addEdge(edgeId)

        // Act
        graph.refreshCache()

        // Assert
        assertEquals(3, graph.entitySize)
        assertNotNull(graph.getNode(GraphTestUtils.nodeId1))
        assertNotNull(graph.getNode(GraphTestUtils.nodeId2))
        assertNotNull(graph.getEdge(edgeId))
    }

    @Test
    fun `test refreshCache only loads edges with graph name prefix`() {
        // Arrange
        storage.addNode(GraphTestUtils.nodeId1)
        storage.addNode(GraphTestUtils.nodeId2)
        val matchingEdge = GraphTestUtils.edgeId1.copy(eType = "${graph.graphName}:relation")
        val nonMatchingEdge = GraphTestUtils.edgeId2.copy(eType = "OtherGraph:relation")
        storage.addEdge(matchingEdge)
        storage.addEdge(nonMatchingEdge)

        // Act
        graph.refreshCache()

        // Assert
        assertNotNull(graph.getEdge(matchingEdge))
        assertNull(graph.getEdge(nonMatchingEdge))
    }

    @Test
    fun `test clearCache clears cache without affecting storage`() {
        // Arrange
        val node1 = graph.addNode(GraphTestUtils.nodeId1)
        val node2 = graph.addNode(GraphTestUtils.nodeId2)
        val edge = graph.addEdge(node1, node2, "relation")

        // Act
        graph.clearCache()

        // Assert
        assertEquals(0, graph.entitySize)
        assertFalse(graph.containNode(node1))
        assertFalse(graph.containEdge(edge))
        assertTrue(storage.containsNode(GraphTestUtils.nodeId1))
        assertTrue(storage.containsEdge(edge.id))
    }
}

