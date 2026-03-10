package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.AbcEdge
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class AbcEdgeTest {

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

    // region EdgeID construction

    @Test
    fun `test edgeID_constructFromComponents_returnsCorrectProperties`() {
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        assertEquals(srcNode, edgeId.srcNid)
        assertEquals(dstNode, edgeId.dstNid)
        assertEquals("relation", edgeId.eType)
    }

    @Test
    fun `test edgeID_nameFormat_srcTypeDst`() {
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        assertEquals("src-relation-dst", edgeId.asString)
    }

    @Test
    fun `test edgeID_constructFromListVal_returnsCorrectProperties`() {
        val listVal = ListVal(srcNode.serialize, dstNode.serialize, "relation".strVal)
        val edgeId = EdgeID(listVal)

        assertEquals(srcNode, edgeId.srcNid)
        assertEquals(dstNode, edgeId.dstNid)
        assertEquals("relation", edgeId.eType)
    }

    @Test
    fun `test edgeID_emptyEdgeType_accepted`() {
        val edgeId = EdgeID(srcNode, dstNode, "")

        assertEquals("", edgeId.eType)
        assertEquals("src--dst", edgeId.asString)
    }

    @Test
    fun `test edgeID_specialCharacters_accepted`() {
        val edgeId = EdgeID(srcNode, dstNode, "edge-123_test")

        assertEquals("edge-123_test", edgeId.eType)
    }

    @Test
    fun `test edgeID_unicodeCharacters_accepted`() {
        val edgeId = EdgeID(srcNode, dstNode, "关系_类型")

        assertEquals("关系_类型", edgeId.eType)
        assertTrue(edgeId.asString.contains("关系_类型"))
    }

    @Test
    fun `test edgeID_veryLongEdgeType_accepted`() {
        val longType = "a".repeat(100)
        val edgeId = EdgeID(srcNode, dstNode, longType)

        assertEquals(100, edgeId.eType.length)
    }

    @Test
    fun `test edgeID_selfLoop_accepted`() {
        val edgeId = EdgeID(srcNode, srcNode, "self-loop")

        assertEquals(srcNode, edgeId.srcNid)
        assertEquals(srcNode, edgeId.dstNid)
        assertEquals("src-self-loop-src", edgeId.asString)
    }

    // endregion

    // region EdgeID serialization

    @Test
    fun `test edgeID_serialize_returnsListVal`() {
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        val serialized = edgeId.serialize

        assertTrue(serialized is ListVal)
        assertEquals(3, serialized.size)
    }

    @Test
    fun `test edgeID_serialize_containsCorrectComponents`() {
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        val serialized = edgeId.serialize as ListVal

        assertEquals(srcNode.serialize, serialized[0])
        assertEquals(dstNode.serialize, serialized[1])
        assertEquals("relation".strVal, serialized[2])
    }

    @Test
    fun `test edgeID_roundTrip_reconstructsCorrectly`() {
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")

        val edgeId2 = EdgeID(edgeId1.serialize as ListVal)

        assertEquals(edgeId1, edgeId2)
        assertEquals(edgeId1.srcNid, edgeId2.srcNid)
        assertEquals(edgeId1.dstNid, edgeId2.dstNid)
        assertEquals(edgeId1.eType, edgeId2.eType)
    }

    @Test
    fun `test edgeID_lazyInitialization_consistent`() {
        val edgeId = EdgeID(srcNode, dstNode, "relation")

        assertEquals(edgeId.asString, edgeId.asString)
        assertEquals(edgeId.serialize, edgeId.serialize)
    }

    // endregion

    // region EdgeID equality

    @Test
    fun `test edgeID_sameComponents_equal`() {
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")
        val edgeId2 = EdgeID(srcNode, dstNode, "relation")

        assertEquals(edgeId1, edgeId2)
        assertEquals(edgeId1.hashCode(), edgeId2.hashCode())
    }

    @Test
    fun `test edgeID_differentSource_notEqual`() {
        assertNotEquals(
            EdgeID(srcNode, dstNode, "relation"),
            EdgeID(NodeID("other"), dstNode, "relation")
        )
    }

    @Test
    fun `test edgeID_differentDestination_notEqual`() {
        assertNotEquals(
            EdgeID(srcNode, dstNode, "relation"),
            EdgeID(srcNode, NodeID("other"), "relation")
        )
    }

    @Test
    fun `test edgeID_differentEdgeType_notEqual`() {
        assertNotEquals(
            EdgeID(srcNode, dstNode, "relation"),
            EdgeID(srcNode, dstNode, "other")
        )
    }

    @Test
    fun `test edgeID_listValConstructor_equalToComponentConstructor`() {
        val edgeId1 = EdgeID(srcNode, dstNode, "relation")
        val edgeId2 = EdgeID(ListVal(srcNode.serialize, dstNode.serialize, "relation".strVal))

        assertEquals(edgeId1, edgeId2)
    }

    // endregion

    // region EdgeID IEntity.ID contract

    @Test
    fun `test edgeID_implementsIEntityID`() {
        assertTrue(EdgeID(srcNode, dstNode, "relation") is IEntity.ID)
    }

    @Test
    fun `test edgeID_asIEntityID_nameAccessible`() {
        val edgeId: IEntity.ID = EdgeID(srcNode, dstNode, "relation")

        assertEquals("src-relation-dst", edgeId.asString)
    }

    @Test
    fun `test edgeID_asIEntityID_serializeReturnsListVal`() {
        val edgeId: IEntity.ID = EdgeID(srcNode, dstNode, "relation")

        assertTrue(edgeId.serialize is ListVal)
    }

    // endregion

    // region AbcEdge property operations

    @Test
    fun `test setProp_value_setsProperty`() {
        testEdge.setProp("weight", 1.5.numVal)

        assertEquals(1.5, (testEdge.getProp("weight") as NumVal).core)
    }

    @Test
    fun `test setProp_null_removesProperty`() {
        testEdge.setProp("weight", 1.5.numVal)

        testEdge.setProp("weight", null)

        assertNull(testEdge.getProp("weight"))
        assertFalse(testEdge.containProp("weight"))
    }

    @Test
    fun `test getProp_absent_returnsNull`() {
        assertNull(testEdge.getProp("nonexistent"))
    }

    @Test
    fun `test setProps_multipleProperties_setsAll`() {
        testEdge.setProps(mapOf(
            "weight" to 1.5.numVal,
            "label" to "test".strVal,
            "active" to true.boolVal
        ))

        assertEquals(1.5, (testEdge.getProp("weight") as NumVal).core)
        assertEquals("test", (testEdge.getProp("label") as StrVal).core)
        assertEquals(true, (testEdge.getProp("active") as BoolVal).core)
    }

    @Test
    fun `test setProps_nullValues_removesProperties`() {
        testEdge.setProps(mapOf("weight" to 1.5.numVal, "label" to "test".strVal))

        testEdge.setProps(mapOf("weight" to null, "label" to "updated".strVal))

        assertNull(testEdge.getProp("weight"))
        assertEquals("updated", (testEdge.getProp("label") as StrVal).core)
    }

    @Test
    fun `test setProps_emptyMap_noChange`() {
        testEdge.setProps(mapOf("weight" to 1.5.numVal, "label" to "test".strVal))

        testEdge.setProps(emptyMap())

        assertEquals(2, testEdge.getAllProps().size)
    }

    @Test
    fun `test setProps_largeNumberOfProperties_setsAll`() {
        val largeProps = (1..100).associate { "prop$it" to it.numVal }

        testEdge.setProps(largeProps)

        assertEquals(100, testEdge.getAllProps().size)
    }

    @Test
    fun `test setProps_mixedValueTypes_setsAll`() {
        testEdge.setProps(mapOf(
            "str" to "test".strVal,
            "num" to 25.numVal,
            "bool" to true.boolVal
        ))

        assertEquals(3, testEdge.getAllProps().size)
    }

    @Test
    fun `test getAllProps_noProperties_returnsEmptyMap`() {
        assertTrue(testEdge.getAllProps().isEmpty())
    }

    @Test
    fun `test getAllProps_withProperties_returnsAll`() {
        testEdge.setProps(mapOf("weight" to 1.5.numVal, "label" to "test".strVal))

        val props = testEdge.getAllProps()

        assertEquals(2, props.size)
    }

    @Test
    fun `test containProp_existing_returnsTrue`() {
        testEdge.setProp("weight", 1.5.numVal)

        assertTrue(testEdge.containProp("weight"))
    }

    @Test
    fun `test containProp_absent_returnsFalse`() {
        assertFalse(testEdge.containProp("nonexistent"))
    }

    // endregion

    // region AbcEdge operator syntax

    @Test
    fun `test operatorSet_value_setsProperty`() {
        testEdge["weight"] = 1.5.numVal

        assertEquals(1.5, (testEdge["weight"] as? NumVal)?.core)
    }

    @Test
    fun `test operatorGet_existing_returnsValue`() {
        testEdge.setProp("weight", 1.5.numVal)

        assertNotNull(testEdge["weight"])
    }

    @Test
    fun `test operatorContains_existing_returnsTrue`() {
        testEdge.setProp("weight", 1.5.numVal)

        assertTrue("weight" in testEdge)
    }

    @Test
    fun `test operatorContains_absent_returnsFalse`() {
        assertFalse("nonexistent" in testEdge)
    }

    // endregion

    // region AbcEdge identity properties

    @Test
    fun `test srcNid_returnsSourceNodeID`() {
        assertEquals(srcNode, testEdge.srcNid)
    }

    @Test
    fun `test dstNid_returnsDestinationNodeID`() {
        assertEquals(dstNode, testEdge.dstNid)
    }

    @Test
    fun `test eType_returnsEdgeType`() {
        assertEquals("relation", testEdge.eType)
    }

    @Test
    fun `test id_returnsCorrectEdgeID`() {
        assertEquals(EdgeID(srcNode, dstNode, "relation"), testEdge.id)
    }

    @Test
    fun `test type_returnsCorrectType`() {
        assertEquals("TestEdge", testEdge.type.name)
    }

    @Test
    fun `test identityProperties_consistentWithId`() {
        assertEquals(testEdge.id.srcNid, testEdge.srcNid)
        assertEquals(testEdge.id.dstNid, testEdge.dstNid)
        assertEquals(testEdge.id.eType, testEdge.eType)
    }

    // endregion

    // region AbcEdge boundary conditions

    @Test
    fun `test setProp_emptyPropertyName_accepted`() {
        testEdge.setProp("", "value".strVal)

        assertTrue(testEdge.containProp(""))
        assertEquals("value", (testEdge.getProp("") as StrVal).core)
    }

    // endregion

    // region AbcEdge storage integration

    @Test
    fun `test propertiesStoredInStorage`() {
        testEdge.setProp("weight", 1.5.numVal)

        val props = storage.getEdgeProperties(testEdge.id)

        assertTrue(props.containsKey("weight"))
        assertEquals(1.5, (props["weight"] as NumVal).core)
    }

    // endregion

    // region AbcEdge equality and toString

    @Test
    fun `test equals_sameID_returnsTrue`() {
        val edge1 = TestEdge(storage, EdgeID(srcNode, dstNode, "relation"))
        val edge2 = TestEdge(storage, EdgeID(srcNode, dstNode, "relation"))

        assertEquals(edge1, edge2)
    }

    @Test
    fun `test equals_differentID_returnsFalse`() {
        assertNotEquals(
            TestEdge(storage, EdgeID(srcNode, dstNode, "relation")),
            TestEdge(storage, EdgeID(srcNode, dstNode, "other"))
        )
    }

    @Test
    fun `test equals_nonEdgeObject_returnsFalse`() {
        assertNotEquals<Any>(testEdge, "not an edge")
    }

    @Test
    fun `test toString_includesEdgeInfo`() {
        val str = testEdge.toString()

        assertTrue(str.contains("relation"))
        assertTrue(str.contains("TestEdge"))
    }

    // endregion
}
