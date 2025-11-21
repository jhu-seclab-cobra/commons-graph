package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * Black-box tests for AbcEdge focusing on public API contracts, inputs, outputs, and documented behaviors.
 * Tests from user perspective without knowledge of internal implementation.
 */
class AbcEdgeBlackBoxTest {

    private lateinit var storage: NativeStorageImpl
    private lateinit var srcNode: NodeID
    private lateinit var dstNode: NodeID
    private lateinit var testEdge: TestEdge

    private class TestEdge(storage: IStorage, override val id: EdgeID) : AbcEdge(storage) {
        override val type: AbcEdge.Type = object : AbcEdge.Type {
            override val name = "TestEdge"
        }
    }

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        srcNode = NodeID("src")
        dstNode = NodeID("dst")
        storage.addNode(srcNode)
        storage.addNode(dstNode)
        testEdge = TestEdge(storage, EdgeID(srcNode, dstNode, "relation"))
        storage.addEdge(testEdge.id)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // PROPERTY OPERATIONS TESTS - Public API
    // ============================================================================

    @Test
    fun `test setProp sets property value`() {
        // Act
        testEdge.setProp("weight", 1.5.numVal)

        // Assert
        val value = testEdge.getProp("weight")
        assertNotNull(value)
        assertEquals(1.5, (value as NumVal).core)
    }

    @Test
    fun `test setProp with null removes property`() {
        // Arrange
        testEdge.setProp("weight", 1.5.numVal)

        // Act
        testEdge.setProp("weight", null)

        // Assert
        assertNull(testEdge.getProp("weight"))
        assertFalse(testEdge.containProp("weight"))
    }

    @Test
    fun `test getProp returns null for absent property`() {
        // Act
        val value = testEdge.getProp("nonexistent")

        // Assert
        assertNull(value)
    }

    @Test
    fun `test setProps sets multiple properties`() {
        // Act
        testEdge.setProps(mapOf(
            "weight" to 1.5.numVal,
            "label" to "test".strVal,
            "active" to true.boolVal
        ))

        // Assert
        assertEquals(1.5, (testEdge.getProp("weight") as NumVal).core)
        assertEquals("test", (testEdge.getProp("label") as StrVal).core)
        assertEquals(true, (testEdge.getProp("active") as BoolVal).core)
    }

    @Test
    fun `test setProps with null values removes properties`() {
        // Arrange
        testEdge.setProps(mapOf(
            "weight" to 1.5.numVal,
            "label" to "test".strVal
        ))

        // Act
        testEdge.setProps(mapOf(
            "weight" to null,
            "label" to "updated".strVal
        ))

        // Assert
        assertNull(testEdge.getProp("weight"))
        assertEquals("updated", (testEdge.getProp("label") as StrVal).core)
    }

    @Test
    fun `test getAllProps returns all properties`() {
        // Arrange
        testEdge.setProps(mapOf(
            "weight" to 1.5.numVal,
            "label" to "test".strVal
        ))

        // Act
        val props = testEdge.getAllProps()

        // Assert
        assertEquals(2, props.size)
        assertEquals(1.5, (props["weight"] as NumVal).core)
        assertEquals("test", (props["label"] as StrVal).core)
    }

    @Test
    fun `test getAllProps returns empty map when no properties`() {
        // Act
        val props = testEdge.getAllProps()

        // Assert
        assertTrue(props.isEmpty())
    }

    @Test
    fun `test containProp returns true for existing property`() {
        // Arrange
        testEdge.setProp("weight", 1.5.numVal)

        // Act & Assert
        assertTrue(testEdge.containProp("weight"))
    }

    @Test
    fun `test containProp returns false for absent property`() {
        // Act & Assert
        assertFalse(testEdge.containProp("nonexistent"))
    }

    // ============================================================================
    // OPERATOR SYNTAX TESTS - Public API
    // ============================================================================

    @Test
    fun `test operator set sets primitive property`() {
        // Act
        testEdge["weight"] = 1.5.numVal

        // Assert
        assertEquals(1.5, (testEdge["weight"] as? NumVal)?.core)
    }

    @Test
    fun `test operator get returns primitive property`() {
        // Arrange
        testEdge.setProp("weight", 1.5.numVal)

        // Act
        val value = testEdge["weight"]

        // Assert
        assertNotNull(value)
        assertEquals(1.5, (value as NumVal).core)
    }

    @Test
    fun `test operator contains returns true for existing property`() {
        // Arrange
        testEdge.setProp("weight", 1.5.numVal)

        // Act & Assert
        assertTrue("weight" in testEdge)
    }

    @Test
    fun `test operator contains returns false for absent property`() {
        // Act & Assert
        assertFalse("nonexistent" in testEdge)
    }

    // ============================================================================
    // EDGE IDENTITY PROPERTIES TESTS - Public API
    // ============================================================================

    @Test
    fun `test srcNid returns source node ID`() {
        // Assert
        assertEquals(srcNode, testEdge.srcNid)
    }

    @Test
    fun `test dstNid returns destination node ID`() {
        // Assert
        assertEquals(dstNode, testEdge.dstNid)
    }

    @Test
    fun `test eType returns edge type`() {
        // Assert
        assertEquals("relation", testEdge.eType)
    }

    @Test
    fun `test id property returns correct EdgeID`() {
        // Assert
        assertEquals(EdgeID(srcNode, dstNode, "relation"), testEdge.id)
    }

    @Test
    fun `test type property returns correct type`() {
        // Assert
        assertEquals("TestEdge", testEdge.type.name)
    }

    // ============================================================================
    // EQUALITY TESTS - Public API
    // ============================================================================

    @Test
    fun `test equals returns true for edges with same ID`() {
        // Arrange
        val edge1 = TestEdge(storage, EdgeID(srcNode, dstNode, "relation"))
        val edge2 = TestEdge(storage, EdgeID(srcNode, dstNode, "relation"))

        // Assert
        assertEquals(edge1, edge2)
    }

    @Test
    fun `test equals returns false for edges with different IDs`() {
        // Arrange
        val edge1 = TestEdge(storage, EdgeID(srcNode, dstNode, "relation"))
        val edge2 = TestEdge(storage, EdgeID(srcNode, dstNode, "other"))

        // Assert
        assertNotEquals(edge1, edge2)
    }

    @Test
    fun `test equals returns false for non-edge object`() {
        // Arrange
        val edge = TestEdge(storage, EdgeID(srcNode, dstNode, "relation"))

        // Assert
        assertNotEquals<Any>(edge, "not an edge")
    }

    // ============================================================================
    // TOSTRING TESTS - Public API
    // ============================================================================

    @Test
    fun `test toString includes edge information`() {
        // Act
        val str = testEdge.toString()

        // Assert
        assertTrue(str.contains("relation"))
        assertTrue(str.contains("TestEdge"))
    }
}

