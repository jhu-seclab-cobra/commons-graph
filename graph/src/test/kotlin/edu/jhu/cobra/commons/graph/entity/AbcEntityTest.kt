package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class AbcEntityTest {

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

    // region getTypeProp

    @Test
    fun `test getTypeProp_strVal_returnsTypedValue`() {
        testNode.setProp("name", "test".strVal)

        val value: StrVal? = testNode.getTypeProp("name")

        assertNotNull(value)
        assertEquals("test", value.core)
    }

    @Test
    fun `test getTypeProp_absent_returnsNull`() {
        val value: StrVal? = testNode.getTypeProp("nonexistent")

        assertNull(value)
    }

    @Test
    fun `test getTypeProp_numVal_returnsTypedValue`() {
        testNode.setProp("age", 25.numVal)

        val value: NumVal? = testNode.getTypeProp("age")

        assertNotNull(value)
        assertEquals(25, value.core)
    }

    @Test
    fun `test getTypeProp_typeMismatch_returnsNull`() {
        testNode.setProp("age", 25.numVal)

        val value: StrVal? = testNode.getTypeProp<StrVal>("age")

        assertNull(value)
    }

    @Test
    fun `test getTypeProp_wrongGenericType_returnsNull`() {
        testNode.setProp("name", "test".strVal)

        val value: NumVal? = testNode.getTypeProp<NumVal>("name")

        assertNull(value)
    }

    @Test
    fun `test getTypeProp_emptyPropertyName_returnsNull`() {
        val value: StrVal? = testNode.getTypeProp("")

        assertNull(value)
    }

    // endregion

    // region EntityProperty delegate

    @Test
    fun `test entityProperty_defaultValue_returnsDefault`() {
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var name: StrVal by EntityProperty(default = "default".strVal)
        }
        storage.addNode(NodeID("entity1"))
        val entity = TestEntity(storage, NodeID("entity1"))

        assertEquals("default", entity.name.core)
    }

    @Test
    fun `test entityProperty_setAndGet_returnsNewValue`() {
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var name: StrVal by EntityProperty(default = "default".strVal)
        }
        storage.addNode(NodeID("entity2"))
        val entity = TestEntity(storage, NodeID("entity2"))

        entity.name = "newValue".strVal

        assertEquals("newValue", entity.name.core)
    }

    @Test
    fun `test entityProperty_customName_usesCustomStorageKey`() {
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var customProp: StrVal by EntityProperty("customName", default = "default".strVal)
        }
        storage.addNode(NodeID("entity3"))
        val entity = TestEntity(storage, NodeID("entity3"))

        entity.customProp = "value".strVal

        assertEquals("value", entity.getProp("customName")?.let { (it as StrVal).core })
    }

    @Test
    fun `test entityProperty_nullable_returnsNullInitially`() {
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var description: StrVal? by EntityProperty()
        }
        storage.addNode(NodeID("entity4"))
        val entity = TestEntity(storage, NodeID("entity4"))

        assertNull(entity.description)
    }

    @Test
    fun `test entityProperty_nullableSetAndGet_returnsValue`() {
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var description: StrVal? by EntityProperty()
        }
        storage.addNode(NodeID("entity5"))
        val entity = TestEntity(storage, NodeID("entity5"))

        entity.description = "test".strVal

        assertEquals("test", entity.description?.core)
    }

    @Test
    fun `test entityProperty_nullableSetToNull_doesNotRemoveProperty`() {
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var description: StrVal? by EntityProperty()
        }
        storage.addNode(NodeID("entity6"))
        val entity = TestEntity(storage, NodeID("entity6"))
        entity.description = "test".strVal

        entity.description = null

        assertNotNull(entity.description)
        assertEquals("test", entity.description?.core)
    }

    @Test
    fun `test entityProperty_emptyName_returnsDefault`() {
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var emptyProp: StrVal by EntityProperty("", default = "default".strVal)
        }
        storage.addNode(NodeID("entity10"))
        val entity = TestEntity(storage, NodeID("entity10"))

        assertEquals("default", entity.emptyProp.core)
    }

    // endregion

    // region EntityType delegate

    @Test
    fun `test entityType_defaultValue_returnsDefault`() {
        val personType = object : IEntity.Type {
            override val name = "PERSON"
        }
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var nodeType: IEntity.Type by EntityType(default = personType)
        }
        storage.addNode(NodeID("entity7"))
        val entity = TestEntity(storage, NodeID("entity7"))

        assertEquals("PERSON", entity.nodeType.name)
    }

    @Test
    fun `test entityType_customName_usesCustomStorageKey`() {
        val personType = object : IEntity.Type {
            override val name = "PERSON"
        }
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var customType: IEntity.Type by EntityType("customTypeName", default = personType)
        }
        storage.addNode(NodeID("entity9"))
        val entity = TestEntity(storage, NodeID("entity9"))

        entity.customType = personType

        val propValue = entity.getProp("customTypeName") as? StrVal
        assertEquals("PERSON", propValue?.core)
    }

    @Test
    fun `test entityType_objectTypeFallback_returnsDefault`() {
        val personType = object : IEntity.Type {
            override val name = "PERSON"
        }
        class TestEntity(storage: NativeStorageImpl, override val id: NodeID) : AbcNode(storage) {
            override val type: AbcNode.Type = object : AbcNode.Type {
                override val name = "TestEntity"
            }
            var nodeType: IEntity.Type by EntityType(default = personType)
        }
        storage.addNode(NodeID("entity8"))
        val entity = TestEntity(storage, NodeID("entity8"))

        entity.setProp("testentity_nodeType", "COMPANY".strVal)

        assertEquals("PERSON", entity.nodeType.name)
    }

    // endregion
}
