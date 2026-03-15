package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcEdge.Companion.META_DST
import edu.jhu.cobra.commons.graph.AbcEdge.Companion.META_SRC
import edu.jhu.cobra.commons.graph.AbcEdge.Companion.META_TAG
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.BoolVal
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

class AbcEdgeTest {
    private lateinit var storage: NativeStorageImpl
    private var srcStorageId: InternalID = 0
    private var dstStorageId: InternalID = 0
    private lateinit var testEdge: TestEdge

    private class TestEdge(
        storage: IStorage,
        internalId: InternalID,
    ) : AbcEdge(storage, internalId) {
        override val type: AbcEdge.Type =
            object : AbcEdge.Type {
                override val name = "TestEdge"
            }
    }

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        srcStorageId = storage.addNode()
        dstStorageId = storage.addNode()
        val metaProps =
            mapOf(
                META_SRC to "src".strVal,
                META_DST to "dst".strVal,
                META_TAG to "relation".strVal,
            )
        val eid = storage.addEdge(srcStorageId, dstStorageId, "relation", metaProps)
        testEdge = TestEdge(storage, eid)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // region InternalID via storage

    @Test
    fun `test edgeID_differentType_differentId`() {
        val eid2 =
            storage.addEdge(
                srcStorageId,
                dstStorageId,
                "other",
                mapOf(META_SRC to "src".strVal, META_DST to "dst".strVal, META_TAG to "other".strVal),
            )
        assertNotEquals(testEdge.internalId, eid2)
    }

    // endregion

    // region AbcEdge property operations

    @Test
    fun `test setProp_value_setsProperty`() {
        testEdge["weight"] = 1.5.numVal

        assertEquals(1.5, (testEdge["weight"] as NumVal).core)
    }

    @Test
    fun `test setProp_null_removesProperty`() {
        testEdge["weight"] = 1.5.numVal

        testEdge["weight"] = null

        assertNull(testEdge["weight"])
        assertFalse("weight" in testEdge)
    }

    @Test
    fun `test getProp_absent_returnsNull`() {
        assertNull(testEdge["nonexistent"])
    }

    @Test
    fun `test setProps_multipleProperties_setsAll`() {
        testEdge.update(
            mapOf(
                "weight" to 1.5.numVal,
                "label" to "test".strVal,
                "active" to true.boolVal,
            ),
        )

        assertEquals(1.5, (testEdge["weight"] as NumVal).core)
        assertEquals("test", (testEdge["label"] as StrVal).core)
        assertEquals(true, (testEdge["active"] as BoolVal).core)
    }

    @Test
    fun `test setProps_nullValues_removesProperties`() {
        testEdge.update(mapOf("weight" to 1.5.numVal, "label" to "test".strVal))

        testEdge.update(mapOf("weight" to null, "label" to "updated".strVal))

        assertNull(testEdge["weight"])
        assertEquals("updated", (testEdge["label"] as StrVal).core)
    }

    @Test
    fun `test setProps_emptyMap_noChange`() {
        testEdge.update(mapOf("weight" to 1.5.numVal, "label" to "test".strVal))

        testEdge.update(emptyMap())

        assertEquals(2, testEdge.asMap().size)
    }

    @Test
    fun `test setProps_largeNumberOfProperties_setsAll`() {
        val largeProps = (1..100).associate { "prop$it" to it.numVal }

        testEdge.update(largeProps)

        assertEquals(100, testEdge.asMap().size)
    }

    @Test
    fun `test setProps_mixedValueTypes_setsAll`() {
        testEdge.update(
            mapOf(
                "str" to "test".strVal,
                "num" to 25.numVal,
                "bool" to true.boolVal,
            ),
        )

        assertEquals(3, testEdge.asMap().size)
    }

    @Test
    fun `test getAllProps_noProperties_returnsEmptyMap`() {
        assertTrue(testEdge.asMap().isEmpty())
    }

    @Test
    fun `test getAllProps_withProperties_returnsAll`() {
        testEdge.update(mapOf("weight" to 1.5.numVal, "label" to "test".strVal))

        val props = testEdge.asMap()

        assertEquals(2, props.size)
    }

    @Test
    fun `test containProp_existing_returnsTrue`() {
        testEdge["weight"] = 1.5.numVal

        assertTrue("weight" in testEdge)
    }

    @Test
    fun `test containProp_absent_returnsFalse`() {
        assertFalse("nonexistent" in testEdge)
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
        testEdge["weight"] = 1.5.numVal

        assertNotNull(testEdge["weight"])
    }

    @Test
    fun `test operatorContains_existing_returnsTrue`() {
        testEdge["weight"] = 1.5.numVal

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
        assertEquals("src", testEdge.srcNid)
    }

    @Test
    fun `test dstNid_returnsDestinationNodeID`() {
        assertEquals("dst", testEdge.dstNid)
    }

    @Test
    fun `test eType_returnsEdgeType`() {
        assertEquals("relation", testEdge.eType)
    }

    @Test
    fun `test id_returnsGraphLayerIdentity`() {
        assertEquals("src-relation-dst", testEdge.id)
    }

    @Test
    fun `test type_returnsCorrectType`() {
        assertEquals("TestEdge", testEdge.type.name)
    }

    // endregion

    // region AbcEdge boundary conditions

    @Test
    fun `test setProp_emptyPropertyName_accepted`() {
        testEdge[""] = "value".strVal

        assertTrue("" in testEdge)
        assertEquals("value", (testEdge[""] as StrVal).core)
    }

    // endregion

    // region AbcEdge storage integration

    @Test
    fun `test propertiesStoredInStorage`() {
        testEdge["weight"] = 1.5.numVal

        val props = storage.getEdgeProperties(testEdge.internalId)

        assertTrue(props.containsKey("weight"))
        assertEquals(1.5, (props["weight"] as NumVal).core)
    }

    // endregion

    // region AbcEdge equality and toString

    @Test
    fun `test equals_sameID_returnsTrue`() {
        val eid = testEdge.internalId
        val edge1 = TestEdge(storage, eid)
        val edge2 = TestEdge(storage, eid)

        assertEquals(edge1, edge2)
    }

    @Test
    fun `test equals_differentID_returnsFalse`() {
        val eid2 =
            storage.addEdge(
                srcStorageId,
                dstStorageId,
                "other",
                mapOf(META_SRC to "src".strVal, META_DST to "dst".strVal, META_TAG to "other".strVal),
            )
        assertNotEquals(
            TestEdge(storage, testEdge.internalId),
            TestEdge(storage, eid2),
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
