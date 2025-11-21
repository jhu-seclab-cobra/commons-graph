package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

/**
 * Black-box tests for EdgeID focusing on public API contracts, inputs, outputs, and documented behaviors.
 * Tests from user perspective without knowledge of internal implementation.
 */
class EdgeIDBlackBoxTest {

    private val srcNode = NodeID("src")
    private val dstNode = NodeID("dst")

    // ============================================================================
    // CONSTRUCTION TESTS - Public API
    // ============================================================================

    @Test
    fun `test EdgeID construction from components`() {
        // Act
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        // Assert
        assertEquals(srcNode, edgeId.srcNid)
        assertEquals(dstNode, edgeId.dstNid)
        assertEquals("relation", edgeId.eType)
    }

    @Test
    fun `test EdgeID name format`() {
        // Act
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        // Assert
        assertEquals("src-relation-dst", edgeId.name)
    }

    @Test
    fun `test EdgeID construction from ListVal`() {
        // Arrange
        val listVal = ListVal(srcNode.serialize, dstNode.serialize, "relation".strVal)

        // Act
        val edgeId = EdgeID(listVal)

        // Assert
        assertEquals(srcNode, edgeId.srcNid)
        assertEquals(dstNode, edgeId.dstNid)
        assertEquals("relation", edgeId.eType)
    }

    // ============================================================================
    // SERIALIZATION TESTS - Public API
    // ============================================================================

    @Test
    fun `test EdgeID serialize returns ListVal`() {
        // Arrange
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        // Act
        val serialized = edgeId.serialize

        // Assert
        assertTrue(serialized is ListVal)
        assertEquals(3, serialized.size)
    }

    @Test
    fun `test EdgeID serialize contains source destination and type`() {
        // Arrange
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        // Act
        val serialized = edgeId.serialize as ListVal

        // Assert
        assertEquals(srcNode.serialize, serialized[0])
        assertEquals(dstNode.serialize, serialized[1])
        assertEquals("relation".strVal, serialized[2])
    }

    @Test
    fun `test EdgeID can be reconstructed from serialized value`() {
        // Arrange
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")

        // Act
        val serialized = edgeId1.serialize as ListVal
        val edgeId2 = EdgeID(serialized)

        // Assert
        assertEquals(edgeId1, edgeId2)
        assertEquals(edgeId1.srcNid, edgeId2.srcNid)
        assertEquals(edgeId1.dstNid, edgeId2.dstNid)
        assertEquals(edgeId1.eType, edgeId2.eType)
    }

    // ============================================================================
    // EQUALITY TESTS - Public API
    // ============================================================================

    @Test
    fun `test EdgeID equality with same components`() {
        // Arrange
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")
        val edgeId2 = EdgeID(srcNode, dstNode, "relation")

        // Assert
        assertEquals(edgeId1, edgeId2)
        assertEquals(edgeId1.hashCode(), edgeId2.hashCode())
    }

    @Test
    fun `test EdgeID inequality with different source`() {
        // Arrange
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")
        val edgeId2 = EdgeID(NodeID("other"), dstNode, "relation")

        // Assert
        assertNotEquals(edgeId1, edgeId2)
    }

    @Test
    fun `test EdgeID inequality with different destination`() {
        // Arrange
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")
        val edgeId2 = EdgeID(srcNode, NodeID("other"), "relation")

        // Assert
        assertNotEquals(edgeId1, edgeId2)
    }

    @Test
    fun `test EdgeID inequality with different edge type`() {
        // Arrange
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")
        val edgeId2 = EdgeID(srcNode, dstNode, "other")

        // Assert
        assertNotEquals(edgeId1, edgeId2)
    }

    @Test
    fun `test EdgeID equality with ListVal constructor`() {
        // Arrange
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")
        val listVal = ListVal(srcNode.serialize, dstNode.serialize, "relation".strVal)
        val edgeId2 = EdgeID(listVal)

        // Assert
        assertEquals(edgeId1, edgeId2)
    }

    // ============================================================================
    // IEntity.ID INTERFACE TESTS - Public API Contract
    // ============================================================================

    @Test
    fun `test EdgeID implements IEntity ID`() {
        // Arrange
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        // Assert
        assertTrue(edgeId is IEntity.ID)
    }

    @Test
    fun `test EdgeID name property from IEntity ID`() {
        // Arrange
        val edgeId: IEntity.ID = EdgeID(srcNode, dstNode, "relation")

        // Assert
        assertEquals("src-relation-dst", edgeId.name)
    }

    @Test
    fun `test EdgeID serialize property from IEntity ID`() {
        // Arrange
        val edgeId: IEntity.ID = EdgeID(srcNode, dstNode, "relation")

        // Act
        val serialized = edgeId.serialize

        // Assert
        assertTrue(serialized is ListVal)
    }
}

