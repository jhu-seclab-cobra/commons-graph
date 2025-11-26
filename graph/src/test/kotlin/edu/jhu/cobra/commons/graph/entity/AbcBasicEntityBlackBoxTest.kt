package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * Black-box tests for AbcBasicEntity focusing on public API contracts, inputs, outputs, and documented behaviors.
 * Tests from user perspective without knowledge of internal implementation.
 */
class AbcBasicEntityBlackBoxTest {

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
    // GET TYPE PROP TESTS - Public API
    // ============================================================================

    @Test
    fun `test getTypeProp returns typed value`() {
        // Arrange
        testNode.setProp("name", "test".strVal)

        // Act
        val value: StrVal? = testNode.getTypeProp("name")

        // Assert
        assertNotNull(value)
        assertEquals("test", value.core)
    }

    @Test
    fun `test getTypeProp returns null for absent property`() {
        // Act
        val value: StrVal? = testNode.getTypeProp("nonexistent")

        // Assert
        assertNull(value)
    }

    @Test
    fun `test getTypeProp with NumVal`() {
        // Arrange
        testNode.setProp("age", 25.numVal)

        // Act
        val value: NumVal? = testNode.getTypeProp("age")

        // Assert
        assertNotNull(value)
        assertEquals(25, value.core)
    }

    // ============================================================================
    // ENTITY PROPERTY DELEGATE TESTS - Public API
    // ============================================================================

    @Test
    fun `test EntityProperty delegate with default value`() {
        // Arrange
        class TestEntityWithProperty(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var name: StrVal by EntityProperty(default = "default".strVal)
        }
        storage.addNode(NodeID("entity1"))
        val entity = TestEntityWithProperty(storage, NodeID("entity1"))

        // Act
        val value = entity.name

        // Assert
        assertEquals("default", value.core)
    }

    @Test
    fun `test EntityProperty delegate set and get`() {
        // Arrange
        class TestEntityWithProperty(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var name: StrVal by EntityProperty(default = "default".strVal)
        }
        storage.addNode(NodeID("entity2"))
        val entity = TestEntityWithProperty(storage, NodeID("entity2"))

        // Act
        entity.name = "newValue".strVal
        val value = entity.name

        // Assert
        assertEquals("newValue", value.core)
    }

    @Test
    fun `test EntityProperty delegate with custom name`() {
        // Arrange
        class TestEntityWithProperty(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var customProp: StrVal by EntityProperty("customName", default = "default".strVal)
        }
        storage.addNode(NodeID("entity3"))
        val entity = TestEntityWithProperty(storage, NodeID("entity3"))

        // Act
        entity.customProp = "value".strVal

        // Assert
        assertEquals("value", entity.getProp("customName")?.let { (it as StrVal).core })
    }

    @Test
    fun `test EntityProperty nullable delegate returns null initially`() {
        // Arrange
        class TestEntityWithProperty(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var description: StrVal? by EntityProperty()
        }
        storage.addNode(NodeID("entity4"))
        val entity = TestEntityWithProperty(storage, NodeID("entity4"))

        // Act
        val value = entity.description

        // Assert
        assertNull(value)
    }

    @Test
    fun `test EntityProperty nullable delegate set and get`() {
        // Arrange
        class TestEntityWithProperty(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var description: StrVal? by EntityProperty()
        }
        storage.addNode(NodeID("entity5"))
        val entity = TestEntityWithProperty(storage, NodeID("entity5"))

        // Act
        entity.description = "test".strVal
        val value = entity.description

        // Assert
        assertEquals("test", value?.core)
    }

    // ============================================================================
    // ENTITY TYPE DELEGATE TESTS - Public API
    // ============================================================================

    @Test
    fun `test EntityType delegate with default value`() {
        // Arrange
        val personType = object : IEntity.Type {
            override val name = "PERSON"
        }

        class TestEntityWithType(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var nodeType: IEntity.Type by EntityType(default = personType)
        }
        storage.addNode(NodeID("entity7"))
        val entity = TestEntityWithType(storage, NodeID("entity7"))

        // Act
        val value = entity.nodeType

        // Assert
        assertEquals("PERSON", value.name)
    }

    @Test
    fun `test EntityType delegate with custom name`() {
        // Arrange
        val personType = object : IEntity.Type {
            override val name = "PERSON"
        }

        class TestEntityWithType(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var customType: IEntity.Type by EntityType("customTypeName", default = personType)
        }
        storage.addNode(NodeID("entity9"))
        val entity = TestEntityWithType(storage, NodeID("entity9"))

        // Act
        entity.customType = personType

        // Assert
        val propValue = entity.getProp("customTypeName") as? StrVal
        assertEquals("PERSON", propValue?.core)
    }
}

