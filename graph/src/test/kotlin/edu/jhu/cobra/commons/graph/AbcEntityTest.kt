package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AbcEntityTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var testNode: TestNode

    private class TestNode(
        storage: NativeStorageImpl,
        internalId: InternalID,
    ) : AbcNode(storage, internalId) {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "TestNode"
            }
    }

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        val storageId = storage.addNode(mapOf(AbcNode.META_ID to "entity_test".strVal))
        testNode = TestNode(storage, storageId)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // region getTypeProp

    @Test
    fun `test getTypeProp_strVal_returnsTypedValue`() {
        testNode["name"] = "test".strVal

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
        testNode["age"] = 25.numVal

        val value: NumVal? = testNode.getTypeProp("age")

        assertNotNull(value)
        assertEquals(25, value.core)
    }

    @Test
    fun `test getTypeProp_typeMismatch_returnsNull`() {
        testNode["age"] = 25.numVal

        val value: StrVal? = testNode.getTypeProp<StrVal>("age")

        assertNull(value)
    }

    @Test
    fun `test getTypeProp_wrongGenericType_returnsNull`() {
        testNode["name"] = "test".strVal

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
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var name: StrVal by EntityProperty(default = "default".strVal)
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        assertEquals("default", entity.name.core)
    }

    @Test
    fun `test entityProperty_setAndGet_returnsNewValue`() {
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var name: StrVal by EntityProperty(default = "default".strVal)
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        entity.name = "newValue".strVal

        assertEquals("newValue", entity.name.core)
    }

    @Test
    fun `test entityProperty_customName_usesCustomStorageKey`() {
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var customProp: StrVal by EntityProperty("customName", default = "default".strVal)
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        entity.customProp = "value".strVal

        assertEquals("value", entity["customName"]?.let { (it as StrVal).core })
    }

    @Test
    fun `test entityProperty_nullable_returnsNullInitially`() {
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var description: StrVal? by EntityProperty()
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        assertNull(entity.description)
    }

    @Test
    fun `test entityProperty_nullableSetAndGet_returnsValue`() {
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var description: StrVal? by EntityProperty()
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        entity.description = "test".strVal

        assertEquals("test", entity.description?.core)
    }

    @Test
    fun `test entityProperty_nullableSetToNull_doesNotRemoveProperty`() {
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var description: StrVal? by EntityProperty()
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)
        entity.description = "test".strVal

        entity.description = null

        assertNotNull(entity.description)
        assertEquals("test", entity.description?.core)
    }

    @Test
    fun `test entityProperty_emptyName_returnsDefault`() {
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var emptyProp: StrVal by EntityProperty("", default = "default".strVal)
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        assertEquals("default", entity.emptyProp.core)
    }

    @Test
    fun `test entityProperty_nullable_wrongType_returnsNull`() {
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var strProp: StrVal? by EntityProperty()
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)
        // Store a NumVal under the same key; the as? StrVal cast should return null
        entity["strProp"] = 42.numVal

        assertNull(entity.strProp)
    }

    @Test
    fun `test entityProperty_nullable_setToNull_doesNotWrite`() {
        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var optProp: StrVal? by EntityProperty()
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        // Setting null on a never-written property must not throw and leave property absent
        entity.optProp = null

        assertNull(entity.optProp)
        assertFalse("optProp" in entity)
    }

    // endregion

    // region EntityType delegate

    @Test
    fun `test entityType_defaultValue_returnsDefault`() {
        val personType =
            object : IEntity.Type {
                override val name = "PERSON"
            }

        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var nodeType: IEntity.Type by EntityType(default = personType)
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        assertEquals("PERSON", entity.nodeType.name)
    }

    @Test
    fun `test entityType_customName_usesCustomStorageKey`() {
        val personType =
            object : IEntity.Type {
                override val name = "PERSON"
            }

        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var customType: IEntity.Type by EntityType("customTypeName", default = personType)
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        entity.customType = personType

        val propValue = entity["customTypeName"] as? StrVal
        assertEquals("PERSON", propValue?.core)
    }

    @Test
    fun `test entityType_objectTypeFallback_returnsDefault`() {
        val personType =
            object : IEntity.Type {
                override val name = "PERSON"
            }

        class TestEntity(
            storage: NativeStorageImpl,
            internalId: InternalID,
        ) : AbcNode(storage, internalId) {
            override val type: AbcNode.Type =
                object : AbcNode.Type {
                    override val name = "TestEntity"
                }
            var nodeType: IEntity.Type by EntityType(default = personType)
        }
        val sid = storage.addNode()
        val entity = TestEntity(storage, sid)

        entity["testentity_nodeType"] = "COMPANY".strVal

        assertEquals("PERSON", entity.nodeType.name)
    }

    @Test
    fun `test entityType_enumType_setAndGet_roundTrips`() {
        val sid = storage.addNode()
        val entity = EnumTypeEntity(storage, sid)

        assertEquals(NodeKind.SOURCE, entity.kind)
        entity.kind = NodeKind.SINK
        assertEquals(NodeKind.SINK, entity.kind)
    }

    @Test
    fun `test entityType_enumType_setValue_sameValue_noOp`() {
        val sid = storage.addNode()
        val entity = EnumTypeEntity(storage, sid)

        entity.kind = NodeKind.SINK
        entity.kind = NodeKind.SINK
        assertEquals(NodeKind.SINK, entity.kind)
    }

    @Test
    fun `test entityType_enumType_customName_usesCustomStorageKey`() {
        val sid = storage.addNode()
        val entity = EnumTypeCustomNameEntity(storage, sid)

        entity.kind = NodeKind.SINK
        val propValue = entity["myKindProp"] as? StrVal
        assertEquals("SINK", propValue?.core)
    }

    @Test
    fun `test entityType_enumType_getValue_fromStorage`() {
        val sid = storage.addNode()
        val entity = EnumTypeCustomNameEntity2(storage, sid)

        entity["myKind"] = "SINK".strVal
        assertEquals(NodeKind.SINK, entity.kind)
    }

    @Test
    fun `test entityType_enumType_unknownStoredValue_returnsDefault`() {
        val sid = storage.addNode()
        val entity = EnumTypeCustomNameEntity2(storage, sid)
        // Store a value that is not a valid enum constant name
        entity["myKind"] = "UNKNOWN_KIND".strVal

        assertEquals(NodeKind.SOURCE, entity.kind)
    }

    // endregion

    enum class NodeKind : IEntity.Type {
        SOURCE,
        SINK,
    }

    private class EnumTypeEntity(
        storage: NativeStorageImpl,
        internalId: InternalID,
    ) : AbcNode(storage, internalId) {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "EnumTypeEntity"
            }
        var kind: NodeKind by EntityType(default = NodeKind.SOURCE)
    }

    private class EnumTypeCustomNameEntity(
        storage: NativeStorageImpl,
        internalId: InternalID,
    ) : AbcNode(storage, internalId) {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "EnumTypeCustomNameEntity"
            }
        var kind: NodeKind by EntityType("myKindProp", default = NodeKind.SOURCE)
    }

    private class EnumTypeCustomNameEntity2(
        storage: NativeStorageImpl,
        internalId: InternalID,
    ) : AbcNode(storage, internalId) {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "EnumTypeCustomNameEntity2"
            }
        var kind: NodeKind by EntityType("myKind", default = NodeKind.SOURCE)
    }
}
