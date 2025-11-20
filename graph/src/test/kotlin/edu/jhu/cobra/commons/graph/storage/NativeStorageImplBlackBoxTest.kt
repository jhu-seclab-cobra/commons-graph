package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * Black-box tests for NativeStorageImpl focusing on public API contracts, inputs, outputs, and documented exceptions.
 * Tests from user perspective without knowledge of internal implementation.
 */
class NativeStorageImplBlackBoxTest {

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
    // PROPERTIES AND STATISTICS TESTS
    // ============================================================================

    @Test
    fun `test empty storage properties`() {
        // Act & Assert
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test nodeIDs property returns all nodes`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)

        // Act
        val nodeIds = storage.nodeIDs

        // Assert
        assertEquals(3, nodeIds.size)
        assertTrue(nodeIds.contains(node1))
        assertTrue(nodeIds.contains(node2))
        assertTrue(nodeIds.contains(node3))
    }

    @Test
    fun `test edgeIDs property returns all edges`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

        // Act
        val edgeIds = storage.edgeIDs

        // Assert
        assertEquals(3, edgeIds.size)
        assertTrue(edgeIds.contains(edge1))
        assertTrue(edgeIds.contains(edge2))
        assertTrue(edgeIds.contains(edge3))
    }

    @Test
    fun `test nodeIDs throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.nodeIDs
        }
    }

    @Test
    fun `test edgeIDs throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.edgeIDs
        }
    }

    // ============================================================================
    // NODE OPERATIONS TESTS
    // ============================================================================

    @Test
    fun `test containsNode returns false for non-existent node`() {
        // Act & Assert
        assertFalse(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode returns true for existing node`() {
        // Arrange
        storage.addNode(node1)

        // Act & Assert
        assertTrue(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.containsNode(node1)
        }
    }

    @Test
    fun `test addNode with empty properties`() {
        // Act
        storage.addNode(node1)

        // Assert
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.getNodeProperties(node1).isEmpty())
    }

    @Test
    fun `test addNode with properties`() {
        // Arrange
        val properties = mapOf(
            "name" to "Node1".strVal,
            "age" to 25.numVal,
            "weight" to 1.5.numVal,
            "active" to true.boolVal
        )

        // Act
        storage.addNode(node1, properties)

        // Assert
        assertTrue(storage.containsNode(node1))
        val props = storage.getNodeProperties(node1)
        assertEquals(4, props.size)
        assertEquals("Node1", (props["name"] as StrVal).core)
        assertEquals(25, (props["age"] as NumVal).core)
        assertEquals(1.5, (props["weight"] as NumVal).core)
        assertEquals(true, (props["active"] as BoolVal).core)
    }

    @Test
    fun `test addNode throws EntityAlreadyExistException when node exists`() {
        // Arrange
        storage.addNode(node1)

        // Act & Assert
        assertFailsWith<EntityAlreadyExistException> {
            storage.addNode(node1)
        }
    }

    @Test
    fun `test addNode throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.addNode(node1)
        }
    }

    @Test
    fun `test getNodeProperties returns all properties`() {
        // Arrange
        val properties = mapOf(
            "prop1" to "value1".strVal,
            "prop2" to 42.numVal
        )
        storage.addNode(node1, properties)

        // Act
        val props = storage.getNodeProperties(node1)

        // Assert
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test getNodeProperties throws EntityNotExistException when node does not exist`() {
        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.getNodeProperties(node1)
        }
    }

    @Test
    fun `test getNodeProperties throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.getNodeProperties(node1)
        }
    }

    @Test
    fun `test setNodeProperties updates existing properties`() {
        // Arrange
        storage.addNode(node1, mapOf("prop1" to "value1".strVal))

        // Act
        storage.setNodeProperties(node1, mapOf("prop1" to "updated".strVal, "prop2" to 42.numVal))

        // Assert
        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("updated", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties deletes properties with null values`() {
        // Arrange
        storage.addNode(node1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        // Act
        storage.setNodeProperties(node1, mapOf("prop1" to null))

        // Assert
        val props = storage.getNodeProperties(node1)
        assertEquals(1, props.size)
        assertFalse(props.containsKey("prop1"))
        assertTrue(props.containsKey("prop2"))
    }

    @Test
    fun `test setNodeProperties throws EntityNotExistException when node does not exist`() {
        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(node1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test setNodeProperties throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.setNodeProperties(node1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test deleteNode removes node`() {
        // Arrange
        storage.addNode(node1)

        // Act
        storage.deleteNode(node1)

        // Assert
        assertFalse(storage.containsNode(node1))
    }

    @Test
    fun `test deleteNode removes connected edges`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge3)

        // Act
        storage.deleteNode(node1)

        // Assert
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
        assertFalse(storage.containsEdge(edge3))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsNode(node3))
    }

    @Test
    fun `test deleteNode throws EntityNotExistException when node does not exist`() {
        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.deleteNode(node1)
        }
    }

    @Test
    fun `test deleteNode throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.deleteNode(node1)
        }
    }

    // ============================================================================
    // EDGE OPERATIONS TESTS
    // ============================================================================

    @Test
    fun `test containsEdge returns false for non-existent edge`() {
        // Act & Assert
        assertFalse(storage.containsEdge(edge1))
    }

    @Test
    fun `test containsEdge returns true for existing edge`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        // Act & Assert
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test containsEdge throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.containsEdge(edge1)
        }
    }

    @Test
    fun `test addEdge with empty properties`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)

        // Act
        storage.addEdge(edge1)

        // Assert
        assertTrue(storage.containsEdge(edge1))
        assertTrue(storage.getEdgeProperties(edge1).isEmpty())
    }

    @Test
    fun `test addEdge with properties`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        val properties = mapOf(
            "weight" to 1.5.numVal,
            "label" to "relation".strVal
        )

        // Act
        storage.addEdge(edge1, properties)

        // Assert
        assertTrue(storage.containsEdge(edge1))
        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals(1.5, (props["weight"] as NumVal).core)
        assertEquals("relation", (props["label"] as StrVal).core)
    }

    @Test
    fun `test addEdge throws EntityAlreadyExistException when edge exists`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        // Act & Assert
        assertFailsWith<EntityAlreadyExistException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when source node does not exist`() {
        // Arrange
        storage.addNode(node2)

        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when destination node does not exist`() {
        // Arrange
        storage.addNode(node1)

        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test addEdge throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test getEdgeProperties returns all properties`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        val properties = mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal)
        storage.addEdge(edge1, properties)

        // Act
        val props = storage.getEdgeProperties(edge1)

        // Assert
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test getEdgeProperties throws EntityNotExistException when edge does not exist`() {
        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.getEdgeProperties(edge1)
        }
    }

    @Test
    fun `test getEdgeProperties throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.getEdgeProperties(edge1)
        }
    }

    @Test
    fun `test setEdgeProperties updates existing properties`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal))

        // Act
        storage.setEdgeProperties(edge1, mapOf("prop1" to "updated".strVal, "prop2" to 42.numVal))

        // Assert
        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals("updated", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties deletes properties with null values`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        // Act
        storage.setEdgeProperties(edge1, mapOf("prop1" to null))

        // Assert
        val props = storage.getEdgeProperties(edge1)
        assertEquals(1, props.size)
        assertFalse(props.containsKey("prop1"))
        assertTrue(props.containsKey("prop2"))
    }

    @Test
    fun `test setEdgeProperties throws EntityNotExistException when edge does not exist`() {
        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.setEdgeProperties(edge1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test setEdgeProperties throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.setEdgeProperties(edge1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test deleteEdge removes edge`() {
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
    }

    @Test
    fun `test deleteEdge throws EntityNotExistException when edge does not exist`() {
        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.deleteEdge(edge1)
        }
    }

    @Test
    fun `test deleteEdge throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.deleteEdge(edge1)
        }
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERIES TESTS
    // ============================================================================

    @Test
    fun `test getIncomingEdges returns empty set for node with no incoming edges`() {
        // Arrange
        storage.addNode(node1)

        // Act
        val incoming = storage.getIncomingEdges(node1)

        // Assert
        assertTrue(incoming.isEmpty())
    }

    @Test
    fun `test getIncomingEdges returns correct edges for nodes with mixed connections`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3
        storage.addEdge(edge3) // node1 -> node3

        // Act & Assert: Single incoming edge
        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))

        // Act & Assert: Multiple incoming edges
        val incoming3 = storage.getIncomingEdges(node3)
        assertEquals(2, incoming3.size)
        assertTrue(incoming3.contains(edge2))
        assertTrue(incoming3.contains(edge3))
    }

    @Test
    fun `test getIncomingEdges throws EntityNotExistException when node does not exist`() {
        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.getIncomingEdges(node1)
        }
    }

    @Test
    fun `test getIncomingEdges throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.getIncomingEdges(node1)
        }
    }

    @Test
    fun `test getOutgoingEdges returns empty set for node with no outgoing edges`() {
        // Arrange
        storage.addNode(node1)

        // Act
        val outgoing = storage.getOutgoingEdges(node1)

        // Assert
        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun `test getOutgoingEdges returns correct edges for nodes with mixed connections`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3
        storage.addEdge(edge3) // node1 -> node3

        // Act & Assert: Multiple outgoing edges
        val outgoing1 = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing1.size)
        assertTrue(outgoing1.contains(edge1))
        assertTrue(outgoing1.contains(edge3))

        // Act & Assert: Single outgoing edge
        val outgoing2 = storage.getOutgoingEdges(node2)
        assertEquals(1, outgoing2.size)
        assertTrue(outgoing2.contains(edge2))
    }

    @Test
    fun `test getOutgoingEdges throws EntityNotExistException when node does not exist`() {
        // Act & Assert
        assertFailsWith<EntityNotExistException> {
            storage.getOutgoingEdges(node1)
        }
    }

    @Test
    fun `test getOutgoingEdges throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.addNode(node1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.getOutgoingEdges(node1)
        }
    }

    // ============================================================================
    // METADATA OPERATIONS TESTS
    // ============================================================================

    @Test
    fun `test getMeta returns null for non-existent metadata`() {
        // Act & Assert
        assertNull(storage.getMeta("nonexistent"))
    }

    @Test
    fun `test setMeta and getMeta work together`() {
        // Arrange & Act
        storage.setMeta("version", "1.0".strVal)

        // Assert
        val value = storage.getMeta("version")
        assertNotNull(value)
        assertEquals("1.0", (value as StrVal).core)
    }

    @Test
    fun `test getMeta throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.getMeta("key")
        }
    }

    @Test
    fun `test setMeta deletes metadata when value is null`() {
        // Arrange
        storage.setMeta("version", "1.0".strVal)

        // Act
        storage.setMeta("version", null)

        // Assert
        assertNull(storage.getMeta("version"))
    }

    @Test
    fun `test setMeta throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.setMeta("key", "value".strVal)
        }
    }

    // ============================================================================
    // UTILITY OPERATIONS TESTS
    // ============================================================================

    @Test
    fun `test clear removes all data including graph structure`() {
        // Arrange
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.setMeta("key", "value".strVal)

        // Act
        val result = storage.clear()

        // Assert
        assertTrue(result)
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `test clear on empty storage returns true`() {
        // Act
        val result = storage.clear()

        // Assert
        assertTrue(result)
    }

    @Test
    fun `test clear throws AccessClosedStorageException when closed`() {
        // Arrange
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> {
            storage.clear()
        }
    }

    @Test
    fun `test close prevents all operations`() {
        // Arrange
        storage.addNode(node1)
        storage.close()

        // Act & Assert
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(node2) }
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(node1, mapOf()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(edge1) }
        assertFailsWith<AccessClosedStorageException> { storage.addEdge(edge1) }
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperties(edge1) }
        assertFailsWith<AccessClosedStorageException> { storage.setEdgeProperties(edge1, mapOf()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteEdge(edge1) }
        assertFailsWith<AccessClosedStorageException> { storage.getIncomingEdges(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.getOutgoingEdges(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("key") }
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("key", "value".strVal) }
        assertFailsWith<AccessClosedStorageException> { storage.clear() }
    }

    // ============================================================================
    // INTEGRATION TESTS
    // ============================================================================

    @Test
    fun `test complex graph operations`() {
        // Arrange: Add nodes with properties
        storage.addNode(node1, mapOf("name" to "Node1".strVal))
        storage.addNode(node2, mapOf("name" to "Node2".strVal))
        storage.addNode(node3, mapOf("name" to "Node3".strVal))

        // Arrange: Add edges with properties
        storage.addEdge(edge1, mapOf("weight" to 1.0.numVal))
        storage.addEdge(edge2, mapOf("weight" to 2.0.numVal))
        storage.addEdge(edge3, mapOf("weight" to 3.0.numVal))

        // Assert: Verify structure
        assertEquals(3, storage.nodeIDs.size)
        assertEquals(3, storage.edgeIDs.size)

        // Assert: Verify incoming/outgoing edges
        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))

        val outgoing1 = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing1.size)
        assertTrue(outgoing1.contains(edge1))
        assertTrue(outgoing1.contains(edge3))

        // Act: Update properties
        storage.setNodeProperties(node1, mapOf("name" to "UpdatedNode1".strVal, "newProp" to 100.numVal))
        val props1 = storage.getNodeProperties(node1)
        assertEquals("UpdatedNode1", (props1["name"] as StrVal).core)
        assertEquals(100, (props1["newProp"] as NumVal).core)

        // Act: Delete edge
        storage.deleteEdge(edge1)
        assertEquals(2, storage.edgeIDs.size)
        assertFalse(storage.containsEdge(edge1))

        // Act: Delete node (should remove connected edges)
        storage.deleteNode(node1)
        assertEquals(2, storage.nodeIDs.size)
        assertFalse(storage.containsEdge(edge3))
    }
}
