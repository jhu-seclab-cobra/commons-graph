package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

/**
 * White-box tests for NodeID focusing on boundary conditions, edge cases, and internal implementation details.
 */
class NodeIDWhiteBoxTest {

    // ============================================================================
    // BOUNDARY CONDITIONS - Empty and Special Characters
    // ============================================================================

    @Test
    fun `test NodeID with empty string`() {
        // Act
        val nodeId = NodeID("")

        // Assert
        assertEquals("", nodeId.name)
        assertEquals("", nodeId.toString())
        assertEquals("", nodeId.serialize.core)
    }

    @Test
    fun `test NodeID with special characters`() {
        // Act
        val nodeId = NodeID("node-123_test")

        // Assert
        assertEquals("node-123_test", nodeId.name)
        assertEquals("node-123_test", nodeId.toString())
    }

    @Test
    fun `test NodeID with unicode characters`() {
        // Act
        val nodeId = NodeID("节点_123")

        // Assert
        assertEquals("节点_123", nodeId.name)
        assertEquals("节点_123", nodeId.serialize.core)
    }

    @Test
    fun `test NodeID with very long string`() {
        // Arrange
        val longString = "a".repeat(1000)

        // Act
        val nodeId = NodeID(longString)

        // Assert
        assertEquals(1000, nodeId.name.length)
        assertEquals(longString, nodeId.serialize.core)
    }

    // ============================================================================
    // INTERNAL STATE CONSISTENCY
    // ============================================================================

    @Test
    fun `test NodeID name and serialize consistency`() {
        // Arrange
        val testName = "testNode"

        // Act
        val nodeId = NodeID(testName)

        // Assert
        assertEquals(testName, nodeId.name)
        assertEquals(testName, (nodeId.serialize as StrVal).core)
        assertTrue(nodeId.name == nodeId.serialize.core)
    }

    @Test
    fun `test NodeID toString consistency with name`() {
        // Arrange
        val testName = "testNode"

        // Act
        val nodeId = NodeID(testName)

        // Assert
        assertEquals(testName, nodeId.toString())
        assertEquals(nodeId.name, nodeId.toString())
    }
}

