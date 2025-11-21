package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

/**
 * Black-box tests for NodeID focusing on public API contracts, inputs, outputs, and documented behaviors.
 * Tests from user perspective without knowledge of internal implementation.
 */
class NodeIDBlackBoxTest {

    // ============================================================================
    // CONSTRUCTION TESTS - Public API
    // ============================================================================

    @Test
    fun `test NodeID construction from string`() {
        // Act
        val nodeId = NodeID("node1")

        // Assert
        assertEquals("node1", nodeId.asString)
        assertEquals("node1", nodeId.toString())
    }

    @Test
    fun `test NodeID construction from StrVal`() {
        // Arrange
        val strVal: StrVal = "node2".strVal

        // Act
        val nodeId = NodeID(strVal)

        // Assert
        assertEquals("node2", nodeId.asString)
        assertEquals("node2", nodeId.toString())
    }

    // ============================================================================
    // SERIALIZATION TESTS - Public API
    // ============================================================================

    @Test
    fun `test NodeID serialize returns StrVal`() {
        // Arrange
        val nodeId = NodeID("node1")

        // Act
        val serialized = nodeId.serialize

        // Assert
        assertTrue(serialized is StrVal)
        assertEquals("node1", serialized.core)
    }

    @Test
    fun `test NodeID serialize matches name`() {
        // Arrange
        val nodeId = NodeID("testNode")

        // Act
        val serialized = nodeId.serialize

        // Assert
        assertEquals(nodeId.asString, serialized.core)
    }

    // ============================================================================
    // EQUALITY TESTS - Public API
    // ============================================================================

    @Test
    fun `test NodeID equality with same name`() {
        // Arrange
        val nodeId1 = NodeID("node1")
        val nodeId2 = NodeID("node1")

        // Assert
        assertEquals(nodeId1, nodeId2)
        assertEquals(nodeId1.hashCode(), nodeId2.hashCode())
    }

    @Test
    fun `test NodeID inequality with different names`() {
        // Arrange
        val nodeId1 = NodeID("node1")
        val nodeId2 = NodeID("node2")

        // Assert
        assertNotEquals(nodeId1, nodeId2)
    }

    @Test
    fun `test NodeID equality with StrVal constructor`() {
        // Arrange
        val nodeId1 = NodeID("node1")
        val nodeId2 = NodeID("node1".strVal)

        // Assert
        assertEquals(nodeId1, nodeId2)
    }

    // ============================================================================
    // IEntity.ID INTERFACE TESTS - Public API Contract
    // ============================================================================

    @Test
    fun `test NodeID implements IEntity ID`() {
        // Arrange
        val nodeId = NodeID("node1")

        // Assert
        assertTrue(nodeId is IEntity.ID)
    }

    @Test
    fun `test NodeID name property from IEntity ID`() {
        // Arrange
        val nodeId: IEntity.ID = NodeID("test")

        // Assert
        assertEquals("test", nodeId.asString)
    }

    @Test
    fun `test NodeID serialize property from IEntity ID`() {
        // Arrange
        val nodeId: IEntity.ID = NodeID("test")

        // Act
        val serialized = nodeId.serialize

        // Assert
        assertTrue(serialized is StrVal)
        assertEquals("test", (serialized as StrVal).core)
    }
}

