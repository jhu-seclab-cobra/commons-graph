package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

/**
 * White-box tests for EdgeID focusing on boundary conditions, edge cases, and internal implementation details.
 */
class EdgeIDWhiteBoxTest {

    private val srcNode = NodeID("src")
    private val dstNode = NodeID("dst")

    // ============================================================================
    // BOUNDARY CONDITIONS - Empty and Special Characters
    // ============================================================================

    @Test
    fun `test EdgeID with empty edge type`() {
        // Act
        val edgeId = EdgeID(srcNode, dstNode, "")

        // Assert
        assertEquals("", edgeId.eType)
        assertEquals("src--dst", edgeId.name)
    }

    @Test
    fun `test EdgeID with special characters in edge type`() {
        // Act
        val edgeId = EdgeID(srcNode, dstNode, "edge-123_test")

        // Assert
        assertEquals("edge-123_test", edgeId.eType)
        assertEquals("src-edge-123_test-dst", edgeId.name)
    }

    @Test
    fun `test EdgeID with unicode characters in edge type`() {
        // Act
        val edgeId = EdgeID(srcNode, dstNode, "关系_类型")

        // Assert
        assertEquals("关系_类型", edgeId.eType)
        assertTrue(edgeId.name.contains("关系_类型"))
    }

    @Test
    fun `test EdgeID with very long edge type`() {
        // Arrange
        val longType = "a".repeat(100)

        // Act
        val edgeId = EdgeID(srcNode, dstNode, longType)

        // Assert
        assertEquals(100, edgeId.eType.length)
        assertTrue(edgeId.name.contains(longType))
    }

    @Test
    fun `test EdgeID with same source and destination`() {
        // Act
        val edgeId = EdgeID(srcNode, srcNode, "self-loop")

        // Assert
        assertEquals(srcNode, edgeId.srcNid)
        assertEquals(srcNode, edgeId.dstNid)
        assertEquals("self-loop", edgeId.eType)
        assertEquals("src-self-loop-src", edgeId.name)
    }

    // ============================================================================
    // INTERNAL STATE CONSISTENCY
    // ============================================================================

    @Test
    fun `test EdgeID name format consistency`() {
        // Arrange
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        // Assert
        val expectedName = "${srcNode.name}-${edgeId.eType}-${dstNode.name}"
        assertEquals(expectedName, edgeId.name)
    }

    @Test
    fun `test EdgeID serialize structure consistency`() {
        // Arrange
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        // Act
        val serialized = edgeId.serialize as ListVal

        // Assert
        assertEquals(3, serialized.size)
        assertEquals(srcNode.serialize, serialized[0])
        assertEquals(dstNode.serialize, serialized[1])
        assertEquals("relation".strVal, serialized[2])
    }

    @Test
    fun `test EdgeID lazy initialization of name and serialize`() {
        // Arrange
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        // Act - Access multiple times
        val name1 = edgeId.name
        val name2 = edgeId.name
        val serialize1 = edgeId.serialize
        val serialize2 = edgeId.serialize

        // Assert - Should be consistent
        assertEquals(name1, name2)
        assertEquals(serialize1, serialize2)
    }
}

