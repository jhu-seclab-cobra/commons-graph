package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * White-box tests for AbcBasicEntity focusing on boundary conditions, internal state consistency, and edge cases.
 */
class AbcBasicEntityWhiteBoxTest {

    private lateinit var storage: NativeStorageImpl
    private lateinit var testNode: TestNode

    private class TestNode(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
        override val type: AbcNode.Type = object : AbcNode.Type {
            override val name = "TestNode"
        }
    }

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        testNode = TestNode(storage, NodeID("test"))
        storage.addNode(testNode.id)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // ============================================================================
    // BOUNDARY CONDITIONS - Type Conversion Edge Cases
    // ============================================================================

    @Test
    fun `test getTypeProp returns null for type mismatch`() {
        // Arrange
        testNode.setProp("age", 25.numVal)

        // Act
        val value: StrVal? = testNode.getTypeProp<StrVal>("age")

        // Assert
        assertNull(value)
    }

    @Test
    fun `test getTypeProp with wrong generic type parameter`() {
        // Arrange
        testNode.setProp("name", "test".strVal)

        // Act
        val value: NumVal? = testNode.getTypeProp<NumVal>("name")

        // Assert
        assertNull(value)
    }

    // ============================================================================
    // INTERNAL STATE CONSISTENCY - Delegate Behavior
    // ============================================================================

    @Test
    fun `test EntityProperty nullable delegate set to null does not remove property`() {
        // Arrange
        class TestEntityWithProperty(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var description: StrVal? by EntityProperty()
        }
        storage.addNode(NodeID("entity6"))
        val entity = TestEntityWithProperty(storage, NodeID("entity6"))
        entity.description = "test".strVal

        // Act
        entity.description = null

        // Assert
        // Note: nullable delegate does not remove property when set to null
        // The value?.let block doesn't execute for null, so setProp is never called
        // getValue still reads from storage, so it returns the existing value
        assertNotNull(entity.description)
        assertEquals("test", entity.description?.core)
        // Property still exists in storage
        assertNotNull(entity.getProp("description"))
    }

    @Test
    fun `test EntityType delegate set and get with object types`() {
        // Note: EntityType delegate is designed for enum types with enumConstants
        // This test verifies internal behavior with object types
        // Arrange
        val personType = object : IEntity.Type {
            override val name = "PERSON"
        }
        val companyType = object : IEntity.Type {
            override val name = "COMPANY"
        }

        class TestEntityWithType(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var nodeType: IEntity.Type by EntityType(default = personType)
        }
        storage.addNode(NodeID("entity8"))
        val entity = TestEntityWithType(storage, NodeID("entity8"))

        // Act - Setting to companyType may not work as expected with object types
        // EntityType expects enum types, so this test verifies the property is set
        entity.setProp("testentity_nodeType", "COMPANY".strVal)
        val value = entity.nodeType

        // Assert - Since EntityType uses enumConstants which is null for objects,
        // it will fall back to default
        assertEquals("PERSON", value.name)
    }

    // ============================================================================
    // PROPERTY UPDATE EDGE CASES
    // ============================================================================

    @Test
    fun `test getTypeProp with empty property name`() {
        // Act
        val value: StrVal? = testNode.getTypeProp("")

        // Assert
        assertNull(value)
    }

    @Test
    fun `test EntityProperty delegate with empty property name`() {
        // Arrange
        class TestEntityWithProperty(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var emptyProp: StrVal by EntityProperty("", default = "default".strVal)
        }
        storage.addNode(NodeID("entity10"))
        val entity = TestEntityWithProperty(storage, NodeID("entity10"))

        // Act
        val value = entity.emptyProp

        // Assert
        assertEquals("default", value.core)
    }
}

