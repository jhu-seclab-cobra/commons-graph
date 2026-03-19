package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.BoolVal
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

class AbcNodeTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var otherStorage: NativeStorageImpl
    private lateinit var testNode: TestNode
    private var testNodeId: String = ""

    private class TestNode : AbcNode() {
        override val type: AbcNode.Type =
            object : AbcNode.Type {
                override val name = "TestNode"
            }
    }

    private fun createTestNode(
        storage: NativeStorageImpl,
        nodeId: String,
    ): TestNode {
        val node = TestNode()
        node.bind(storage, nodeId)
        return node
    }

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        otherStorage = NativeStorageImpl()
        testNodeId = storage.addNode("testNode")
        testNode = createTestNode(storage, testNodeId)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        otherStorage.close()
    }

    // region AbcNode property operations

    @Test
    fun `test setProp_value_setsProperty`() {
        testNode["name"] = "test".strVal

        assertEquals("test", (testNode["name"] as StrVal).core)
    }

    @Test
    fun `test setProp_null_removesProperty`() {
        testNode["name"] = "test".strVal

        testNode["name"] = null

        assertNull(testNode["name"])
        assertFalse("name" in testNode)
    }

    @Test
    fun `test getProp_absent_returnsNull`() {
        assertNull(testNode["nonexistent"])
    }

    @Test
    fun `test setProps_multipleProperties_setsAll`() {
        testNode.update(
            mapOf(
                "name" to "test".strVal,
                "age" to 25.numVal,
                "active" to true.boolVal,
            ),
        )

        assertEquals("test", (testNode["name"] as StrVal).core)
        assertEquals(25, (testNode["age"] as NumVal).core)
        assertEquals(true, (testNode["active"] as BoolVal).core)
    }

    @Test
    fun `test setProps_nullValues_removesProperties`() {
        testNode.update(mapOf("name" to "test".strVal, "age" to 25.numVal))

        testNode.update(mapOf("name" to null, "age" to 30.numVal))

        assertNull(testNode["name"])
        assertEquals(30, (testNode["age"] as NumVal).core)
    }

    @Test
    fun `test setProps_emptyMap_noChange`() {
        testNode.update(mapOf("name" to "test".strVal, "age" to 25.numVal))

        testNode.update(emptyMap())

        assertEquals(2, testNode.asMap().size)
    }

    @Test
    fun `test setProps_largeNumberOfProperties_setsAll`() {
        val largeProps = (1..100).associate { "prop$it" to it.numVal }

        testNode.update(largeProps)

        assertEquals(100, testNode.asMap().size)
    }

    @Test
    fun `test setProps_mixedValueTypes_setsAll`() {
        testNode.update(
            mapOf(
                "str" to "test".strVal,
                "num" to 25.numVal,
                "bool" to true.boolVal,
            ),
        )

        assertEquals(3, testNode.asMap().size)
        assertTrue("str" in testNode)
        assertTrue("num" in testNode)
        assertTrue("bool" in testNode)
    }

    @Test
    fun `test getAllProps_noProperties_returnsEmptyMap`() {
        assertTrue(testNode.asMap().isEmpty())
    }

    @Test
    fun `test getAllProps_withProperties_returnsAll`() {
        testNode.update(mapOf("name" to "test".strVal, "age" to 25.numVal))

        val props = testNode.asMap()

        assertEquals(2, props.size)
        assertEquals("test", (props["name"] as StrVal).core)
        assertEquals(25, (props["age"] as NumVal).core)
    }

    @Test
    fun `test containProp_existing_returnsTrue`() {
        testNode["name"] = "test".strVal

        assertTrue("name" in testNode)
    }

    @Test
    fun `test containProp_absent_returnsFalse`() {
        assertFalse("nonexistent" in testNode)
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
        testNode["age"] = 25.numVal

        assertEquals(25, (testNode["age"] as NumVal).core)
    }

    @Test
    fun `test operatorContains_existing_returnsTrue`() {
        testNode["name"] = "test".strVal

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
        testNode[""] = "value".strVal

        assertTrue("" in testNode)
        assertEquals("value", (testNode[""] as StrVal).core)
    }

    @Test
    fun `test getProp_emptyPropertyName_returnsValue`() {
        testNode[""] = "value".strVal

        assertNotNull(testNode[""])
    }

    // endregion

    // region AbcNode storage integration

    @Test
    fun `test propertiesStoredInStorage`() {
        testNode["name"] = "test".strVal

        val props = storage.getNodeProperties(testNodeId)

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
        assertEquals("testNode", testNode.id)
    }

    @Test
    fun `test type_returnsCorrectType`() {
        assertEquals("TestNode", testNode.type.name)
    }

    @Test
    fun `test equals_sameID_returnsTrue`() {
        val node1 = createTestNode(storage, testNodeId)
        val node2 = createTestNode(storage, testNodeId)

        assertEquals(node1, node2)
    }

    @Test
    fun `test equals_differentID_returnsFalse`() {
        val sid2 = storage.addNode("other")
        assertNotEquals(createTestNode(storage, testNodeId), createTestNode(storage, sid2))
    }

    @Test
    fun `test equals_nonNodeObject_returnsFalse`() {
        assertNotEquals<Any>(createTestNode(storage, testNodeId), "not a node")
    }

    @Test
    fun `test toString_includesIdAndType`() {
        val str = testNode.toString()

        assertTrue(str.contains("testNode"))
        assertTrue(str.contains("TestNode"))
    }

    // endregion
}
