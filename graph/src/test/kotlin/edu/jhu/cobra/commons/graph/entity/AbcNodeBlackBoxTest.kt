package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * Black-box tests for AbcNode focusing on public API contracts, inputs, outputs, and documented behaviors.
 * Tests from user perspective without knowledge of internal implementation.
 */
class AbcNodeBlackBoxTest {

    private lateinit var storage: NativeStorageImpl
    private lateinit var testNode: TestNode
    private lateinit var otherStorage: NativeStorageImpl

    private class TestNode(storage: IStorage, override val id: NodeID) : AbcNode(storage) {
        override val type: AbcNode.Type = object : AbcNode.Type {
            override val name = "TestNode"
        }
    }

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        otherStorage = NativeStorageImpl()
        testNode = TestNode(storage, NodeID("test"))
        storage.addNode(testNode.id)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        otherStorage.close()
    }

    // ============================================================================
    // PROPERTY OPERATIONS TESTS - Public API
    // ============================================================================

    @Test
    fun `test setProp sets property value`() {
        // Act
        testNode.setProp("name", "test".strVal)

        // Assert
        val value = testNode.getProp("name")
        assertNotNull(value)
        assertEquals("test", (value as StrVal).core)
    }

    @Test
    fun `test setProp with null removes property`() {
        // Arrange
        testNode.setProp("name", "test".strVal)

        // Act
        testNode.setProp("name", null)

        // Assert
        assertNull(testNode.getProp("name"))
        assertFalse(testNode.containProp("name"))
    }

    @Test
    fun `test getProp returns null for absent property`() {
        // Act
        val value = testNode.getProp("nonexistent")

        // Assert
        assertNull(value)
    }

    @Test
    fun `test setProps sets multiple properties`() {
        // Act
        testNode.setProps(mapOf(
            "name" to "test".strVal,
            "age" to 25.numVal,
            "active" to true.boolVal
        ))

        // Assert
        assertEquals("test", (testNode.getProp("name") as StrVal).core)
        assertEquals(25, (testNode.getProp("age") as NumVal).core)
        assertEquals(true, (testNode.getProp("active") as BoolVal).core)
    }

    @Test
    fun `test setProps with null values removes properties`() {
        // Arrange
        testNode.setProps(mapOf(
            "name" to "test".strVal,
            "age" to 25.numVal
        ))

        // Act
        testNode.setProps(mapOf(
            "name" to null,
            "age" to 30.numVal
        ))

        // Assert
        assertNull(testNode.getProp("name"))
        assertEquals(30, (testNode.getProp("age") as NumVal).core)
    }

    @Test
    fun `test getAllProps returns all properties`() {
        // Arrange
        testNode.setProps(mapOf(
            "name" to "test".strVal,
            "age" to 25.numVal
        ))

        // Act
        val props = testNode.getAllProps()

        // Assert
        assertEquals(2, props.size)
        assertEquals("test", (props["name"] as StrVal).core)
        assertEquals(25, (props["age"] as NumVal).core)
    }

    @Test
    fun `test getAllProps returns empty map when no properties`() {
        // Act
        val props = testNode.getAllProps()

        // Assert
        assertTrue(props.isEmpty())
    }

    @Test
    fun `test containProp returns true for existing property`() {
        // Arrange
        testNode.setProp("name", "test".strVal)

        // Act & Assert
        assertTrue(testNode.containProp("name"))
    }

    @Test
    fun `test containProp returns false for absent property`() {
        // Act & Assert
        assertFalse(testNode.containProp("nonexistent"))
    }

    // ============================================================================
    // OPERATOR SYNTAX TESTS - Public API
    // ============================================================================

    @Test
    fun `test operator set sets primitive property`() {
        // Act
        testNode["name"] = "test".strVal

        // Assert
        assertEquals("test", (testNode["name"] as? StrVal)?.core)
    }

    @Test
    fun `test operator get returns primitive property`() {
        // Arrange
        testNode.setProp("age", 25.numVal)

        // Act
        val value = testNode["age"]

        // Assert
        assertNotNull(value)
        assertEquals(25, (value as NumVal).core)
    }

    @Test
    fun `test operator contains returns true for existing property`() {
        // Arrange
        testNode.setProp("name", "test".strVal)

        // Act & Assert
        assertTrue("name" in testNode)
    }

    @Test
    fun `test operator contains returns false for absent property`() {
        // Act & Assert
        assertFalse("nonexistent" in testNode)
    }

    // ============================================================================
    // STORAGE INTEGRATION TESTS - Public API
    // ============================================================================

    @Test
    fun `test doUseStorage returns true for matching storage`() {
        // Act & Assert
        assertTrue(testNode.doUseStorage(storage))
    }

    @Test
    fun `test doUseStorage returns false for different storage`() {
        // Act & Assert
        assertFalse(testNode.doUseStorage(otherStorage))
    }

    // ============================================================================
    // IDENTITY AND TYPE TESTS - Public API
    // ============================================================================

    @Test
    fun `test id property returns correct NodeID`() {
        // Assert
        assertEquals(NodeID("test"), testNode.id)
    }

    @Test
    fun `test type property returns correct type`() {
        // Assert
        assertEquals("TestNode", testNode.type.name)
    }

    // ============================================================================
    // EQUALITY TESTS - Public API
    // ============================================================================

    @Test
    fun `test equals returns true for nodes with same ID`() {
        // Arrange
        val node1 = TestNode(storage, NodeID("test"))
        val node2 = TestNode(storage, NodeID("test"))

        // Assert
        assertEquals(node1, node2)
    }

    @Test
    fun `test equals returns false for nodes with different IDs`() {
        // Arrange
        val node1 = TestNode(storage, NodeID("test"))
        val node2 = TestNode(storage, NodeID("other"))

        // Assert
        assertNotEquals(node1, node2)
    }

    @Test
    fun `test equals returns false for non-node object`() {
        // Arrange
        val node = TestNode(storage, NodeID("test"))

        // Assert
        assertNotEquals<Any>(node, "not a node")
    }

    // ============================================================================
    // TOSTRING TESTS - Public API
    // ============================================================================

    @Test
    fun `test toString includes id and type`() {
        // Act
        val str = testNode.toString()

        // Assert
        assertTrue(str.contains("test"))
        assertTrue(str.contains("TestNode"))
    }
}

