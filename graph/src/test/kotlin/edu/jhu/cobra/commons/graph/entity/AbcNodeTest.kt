package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.AbcNode
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class AbcNodeTest {

    private lateinit var storage: NativeStorageImpl
    private lateinit var otherStorage: NativeStorageImpl
    private lateinit var testNode: TestNode

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

    // region NodeID construction

    @Test
    fun `test nodeID_constructFromString_returnsCorrectName`() {
        val nodeId = NodeID("node1")

        assertEquals("node1", nodeId.asString)
        assertEquals("node1", nodeId.toString())
    }

    @Test
    fun `test nodeID_constructFromStrVal_returnsCorrectName`() {
        val nodeId = NodeID("node2".strVal)

        assertEquals("node2", nodeId.asString)
        assertEquals("node2", nodeId.toString())
    }

    @Test
    fun `test nodeID_emptyString_accepted`() {
        val nodeId = NodeID("")

        assertEquals("", nodeId.asString)
        assertEquals("", nodeId.toString())
        assertEquals("", nodeId.serialize.core)
    }

    @Test
    fun `test nodeID_specialCharacters_accepted`() {
        val nodeId = NodeID("node-123_test")

        assertEquals("node-123_test", nodeId.asString)
    }

    @Test
    fun `test nodeID_unicodeCharacters_accepted`() {
        val nodeId = NodeID("节点_123")

        assertEquals("节点_123", nodeId.asString)
        assertEquals("节点_123", nodeId.serialize.core)
    }

    @Test
    fun `test nodeID_veryLongString_accepted`() {
        val longString = "a".repeat(1000)
        val nodeId = NodeID(longString)

        assertEquals(1000, nodeId.asString.length)
        assertEquals(longString, nodeId.serialize.core)
    }

    // endregion

    // region NodeID serialization

    @Test
    fun `test nodeID_serialize_returnsStrVal`() {
        val nodeId = NodeID("node1")

        val serialized = nodeId.serialize

        assertTrue(serialized is StrVal)
        assertEquals("node1", serialized.core)
    }

    @Test
    fun `test nodeID_serialize_matchesName`() {
        val nodeId = NodeID("testNode")

        assertEquals(nodeId.asString, nodeId.serialize.core)
    }

    @Test
    fun `test nodeID_nameAndSerialize_consistent`() {
        val nodeId = NodeID("testNode")

        assertEquals(nodeId.asString, (nodeId.serialize as StrVal).core)
        assertEquals(nodeId.asString, nodeId.toString())
    }

    // endregion

    // region NodeID equality

    @Test
    fun `test nodeID_sameName_equal`() {
        val nodeId1 = NodeID("node1")
        val nodeId2 = NodeID("node1")

        assertEquals(nodeId1, nodeId2)
        assertEquals(nodeId1.hashCode(), nodeId2.hashCode())
    }

    @Test
    fun `test nodeID_differentNames_notEqual`() {
        assertNotEquals(NodeID("node1"), NodeID("node2"))
    }

    @Test
    fun `test nodeID_strValConstructor_equalToStringConstructor`() {
        assertEquals(NodeID("node1"), NodeID("node1".strVal))
    }

    // endregion

    // region NodeID IEntity.ID contract

    @Test
    fun `test nodeID_implementsIEntityID`() {
        assertTrue(NodeID("node1") is IEntity.ID)
    }

    @Test
    fun `test nodeID_asIEntityID_nameAccessible`() {
        val nodeId: IEntity.ID = NodeID("test")

        assertEquals("test", nodeId.asString)
    }

    @Test
    fun `test nodeID_asIEntityID_serializeReturnsStrVal`() {
        val nodeId: IEntity.ID = NodeID("test")

        assertTrue(nodeId.serialize is StrVal)
        assertEquals("test", (nodeId.serialize as StrVal).core)
    }

    // endregion

    // region AbcNode property operations

    @Test
    fun `test setProp_value_setsProperty`() {
        testNode.setProp("name", "test".strVal)

        assertEquals("test", (testNode.getProp("name") as StrVal).core)
    }

    @Test
    fun `test setProp_null_removesProperty`() {
        testNode.setProp("name", "test".strVal)

        testNode.setProp("name", null)

        assertNull(testNode.getProp("name"))
        assertFalse(testNode.containProp("name"))
    }

    @Test
    fun `test getProp_absent_returnsNull`() {
        assertNull(testNode.getProp("nonexistent"))
    }

    @Test
    fun `test setProps_multipleProperties_setsAll`() {
        testNode.setProps(mapOf(
            "name" to "test".strVal,
            "age" to 25.numVal,
            "active" to true.boolVal
        ))

        assertEquals("test", (testNode.getProp("name") as StrVal).core)
        assertEquals(25, (testNode.getProp("age") as NumVal).core)
        assertEquals(true, (testNode.getProp("active") as BoolVal).core)
    }

    @Test
    fun `test setProps_nullValues_removesProperties`() {
        testNode.setProps(mapOf("name" to "test".strVal, "age" to 25.numVal))

        testNode.setProps(mapOf("name" to null, "age" to 30.numVal))

        assertNull(testNode.getProp("name"))
        assertEquals(30, (testNode.getProp("age") as NumVal).core)
    }

    @Test
    fun `test setProps_emptyMap_noChange`() {
        testNode.setProps(mapOf("name" to "test".strVal, "age" to 25.numVal))

        testNode.setProps(emptyMap())

        assertEquals(2, testNode.getAllProps().size)
    }

    @Test
    fun `test setProps_largeNumberOfProperties_setsAll`() {
        val largeProps = (1..100).associate { "prop$it" to it.numVal }

        testNode.setProps(largeProps)

        assertEquals(100, testNode.getAllProps().size)
    }

    @Test
    fun `test setProps_mixedValueTypes_setsAll`() {
        testNode.setProps(mapOf(
            "str" to "test".strVal,
            "num" to 25.numVal,
            "bool" to true.boolVal
        ))

        assertEquals(3, testNode.getAllProps().size)
        assertTrue(testNode.containProp("str"))
        assertTrue(testNode.containProp("num"))
        assertTrue(testNode.containProp("bool"))
    }

    @Test
    fun `test getAllProps_noProperties_returnsEmptyMap`() {
        assertTrue(testNode.getAllProps().isEmpty())
    }

    @Test
    fun `test getAllProps_withProperties_returnsAll`() {
        testNode.setProps(mapOf("name" to "test".strVal, "age" to 25.numVal))

        val props = testNode.getAllProps()

        assertEquals(2, props.size)
        assertEquals("test", (props["name"] as StrVal).core)
        assertEquals(25, (props["age"] as NumVal).core)
    }

    @Test
    fun `test containProp_existing_returnsTrue`() {
        testNode.setProp("name", "test".strVal)

        assertTrue(testNode.containProp("name"))
    }

    @Test
    fun `test containProp_absent_returnsFalse`() {
        assertFalse(testNode.containProp("nonexistent"))
    }

    // endregion

    // region AbcNode operator syntax

    @Test
    fun `test operatorSet_value_setsProperty`() {
        testNode["name"] = "test".strVal

        assertEquals("test", (testNode["name"] as? StrVal)?.core)
    }

    @Test
    fun `test operatorGet_existing_returnsValue`() {
        testNode.setProp("age", 25.numVal)

        assertEquals(25, (testNode["age"] as NumVal).core)
    }

    @Test
    fun `test operatorContains_existing_returnsTrue`() {
        testNode.setProp("name", "test".strVal)

        assertTrue("name" in testNode)
    }

    @Test
    fun `test operatorContains_absent_returnsFalse`() {
        assertFalse("nonexistent" in testNode)
    }

    // endregion

    // region AbcNode boundary conditions

    @Test
    fun `test setProp_emptyPropertyName_accepted`() {
        testNode.setProp("", "value".strVal)

        assertTrue(testNode.containProp(""))
        assertEquals("value", (testNode.getProp("") as StrVal).core)
    }

    @Test
    fun `test getProp_emptyPropertyName_returnsValue`() {
        testNode.setProp("", "value".strVal)

        assertNotNull(testNode.getProp(""))
    }

    // endregion

    // region AbcNode storage integration

    @Test
    fun `test propertiesStoredInStorage`() {
        testNode.setProp("name", "test".strVal)

        val props = storage.getNodeProperties(testNode.id)

        assertTrue(props.containsKey("name"))
        assertEquals("test", (props["name"] as StrVal).core)
    }

    @Test
    fun `test doUseStorage_matchingStorage_returnsTrue`() {
        assertTrue(testNode.doUseStorage(storage))
    }

    @Test
    fun `test doUseStorage_differentStorage_returnsFalse`() {
        assertFalse(testNode.doUseStorage(otherStorage))
    }

    // endregion

    // region AbcNode identity and equality

    @Test
    fun `test id_returnsCorrectNodeID`() {
        assertEquals(NodeID("test"), testNode.id)
    }

    @Test
    fun `test type_returnsCorrectType`() {
        assertEquals("TestNode", testNode.type.name)
    }

    @Test
    fun `test equals_sameID_returnsTrue`() {
        val node1 = TestNode(storage, NodeID("test"))
        val node2 = TestNode(storage, NodeID("test"))

        assertEquals(node1, node2)
    }

    @Test
    fun `test equals_differentID_returnsFalse`() {
        assertNotEquals(TestNode(storage, NodeID("test")), TestNode(storage, NodeID("other")))
    }

    @Test
    fun `test equals_nonNodeObject_returnsFalse`() {
        assertNotEquals<Any>(TestNode(storage, NodeID("test")), "not a node")
    }

    @Test
    fun `test toString_includesIdAndType`() {
        val str = testNode.toString()

        assertTrue(str.contains("test"))
        assertTrue(str.contains("TestNode"))
    }

    // endregion
}
