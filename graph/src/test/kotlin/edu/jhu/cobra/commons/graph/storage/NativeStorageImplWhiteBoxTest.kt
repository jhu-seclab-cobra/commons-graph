package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * White-box tests for NativeStorageImpl focusing on internal implementation details,
 * boundary conditions, state consistency, and edge cases within internal logic.
 */
class NativeStorageImplWhiteBoxTest {

    private lateinit var storage: NativeStorageImpl

    private val node1 = StorageTestUtils.node1
    private val node2 = StorageTestUtils.node2
    private val node3 = StorageTestUtils.node3
    private val edge1 = StorageTestUtils.edge1
    private val edge2 = StorageTestUtils.edge2
    private val edge3 = StorageTestUtils.edge3

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // PROPERTY UPDATE BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test setNodeProperties with empty map does not change properties`() {
        // Arrange
        storage.addNode(node1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        // Act
        storage.setNodeProperties(node1, emptyMap())

        // Assert
        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties with mixed null and non-null values`() {
        // Arrange
        storage.addNode(node1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        // Act
        storage.setNodeProperties(node1, mapOf(
            "prop1" to null,
            "prop2" to "updated".strVal,
            "prop3" to 100.numVal
        ))

        // Assert
        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertFalse(props.containsKey("prop1"))
        assertEquals("updated", (props["prop2"] as StrVal).core)
        assertEquals(100, (props["prop3"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties with empty map does not change properties`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        // Act
        storage.setEdgeProperties(edge1, emptyMap())

        // Assert
        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    // ============================================================================
    // GRAPH STRUCTURE BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test deleteEdge removes edge from graph structure leaving empty sets`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        // Act
        storage.deleteEdge(edge1)

        // Assert
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    @Test
    fun `test deleteEdge handles self-loop edge`() {
        // Arrange
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

        // Act
        storage.deleteEdge(selfLoop)

        // Assert
        assertFalse(storage.containsEdge(selfLoop))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node1).isEmpty())
    }

    @Test
    fun `test getIncomingEdges handles self-loop edge`() {
        // Arrange
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

        // Act
        val incoming = storage.getIncomingEdges(node1)

        // Assert
        assertTrue(incoming.contains(selfLoop))
    }

    @Test
    fun `test getOutgoingEdges handles self-loop edge`() {
        // Arrange
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

        // Act
        val outgoing = storage.getOutgoingEdges(node1)

        // Assert
        assertTrue(outgoing.contains(selfLoop))
    }

    @Test
    fun `test clear removes all graph structure entries`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.setMeta("key", "value".strVal)

        // Act
        storage.clear()

        // Assert
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
        storage.addNode(node1)
        storage.addNode(node2)
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    // ============================================================================
    // STATE CONSISTENCY TESTS
    // ============================================================================

    @Test
    fun `test graph structure consistency after multiple edge deletions`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

        // Act
        storage.deleteEdge(edge1)
        assertTrue(storage.getOutgoingEdges(node1).contains(edge3))
        assertTrue(storage.getIncomingEdges(node2).isEmpty())

        storage.deleteEdge(edge3)
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node3).contains(edge2))

        storage.deleteEdge(edge2)
        assertTrue(storage.getOutgoingEdges(node2).isEmpty())
        assertTrue(storage.getIncomingEdges(node3).isEmpty())

        // Assert
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsNode(node3))
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test graph structure consistency when node has both incoming and outgoing edges`() {
        // Arrange: Node2 has both incoming and outgoing edges
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3

        // Act & Assert: Verify both directions
        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))

        val outgoing2 = storage.getOutgoingEdges(node2)
        assertEquals(1, outgoing2.size)
        assertTrue(outgoing2.contains(edge2))

        // Act
        storage.deleteEdge(edge1)
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
        assertTrue(storage.getOutgoingEdges(node2).contains(edge2))

        storage.deleteEdge(edge2)
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
        assertTrue(storage.getOutgoingEdges(node2).isEmpty())
    }
}
