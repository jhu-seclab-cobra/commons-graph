package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.TestEdge
import edu.jhu.cobra.commons.graph.poset.Label
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-box tests for AbcEdge: bind, id computation, labels property, property access,
 * equals/hashCode, toString.
 *
 * - `id returns src-tag-dst format` — verifies derived edge ID
 * - `id differs when tag differs` — verifies tag contribution to ID
 * - `srcNid returns source node ID` — verifies bind injection
 * - `dstNid returns destination node ID` — verifies bind injection
 * - `eTag returns edge tag` — verifies bind injection
 * - `type returns subclass-defined type` — verifies abstract type override
 * - `labels returns empty set when no labels assigned` — verifies default labels
 * - `labels set and get round-trips` — verifies labels write-read
 * - `get returns value for existing property` — verifies property read
 * - `get returns null for absent property` — verifies absent returns null
 * - `set stores property` — verifies property write
 * - `set null removes property` — verifies null removes
 * - `contains returns true for existing property` — verifies present key
 * - `contains returns false for absent property` — verifies missing key
 * - `asMap returns empty map when no properties` — verifies empty initial state
 * - `asMap returns all properties` — verifies complete map
 * - `update sets multiple properties` — verifies bulk update
 * - `update null values remove properties` — verifies null entries remove keys
 * - `equals returns true for same storageId` — verifies equality by storageId
 * - `equals returns false for different storageId` — verifies inequality
 * - `equals returns false for non-edge object` — verifies type guard
 * - `toString includes src-tag-dst and type` — verifies string format
 */
internal class AbcEdgeTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var edge: TestEdge
    private var srcSid: Int = -1
    private var dstSid: Int = -1

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        srcSid = storage.addNode()
        dstSid = storage.addNode()
        val eid = storage.addEdge(srcSid, dstSid, "calls")
        edge = TestEdge()
        edge.bind(storage, eid, "srcNode", "dstNode", "calls")
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // region Identity

    @Test
    fun `id returns src-tag-dst format`() {
        assertEquals("srcNode-calls-dstNode", edge.id)
    }

    @Test
    fun `id differs when tag differs`() {
        val eid2 = storage.addEdge(srcSid, dstSid, "reads")
        val edge2 = TestEdge()
        edge2.bind(storage, eid2, "srcNode", "dstNode", "reads")

        assertNotEquals(edge.id, edge2.id)
    }

    @Test
    fun `srcNid returns source node ID`() {
        assertEquals("srcNode", edge.srcNid)
    }

    @Test
    fun `dstNid returns destination node ID`() {
        assertEquals("dstNode", edge.dstNid)
    }

    @Test
    fun `eTag returns edge tag`() {
        assertEquals("calls", edge.eTag)
    }

    @Test
    fun `type returns subclass-defined type`() {
        assertEquals("TestEdge", edge.type.name)
    }

    // endregion

    // region Labels

    @Test
    fun `labels returns empty set when no labels assigned`() {
        assertTrue(edge.labels.isEmpty())
    }

    @Test
    fun `labels set and get round-trips`() {
        val labels = setOf(Label("v1"), Label("v2"))

        edge.labels = labels

        assertEquals(labels, edge.labels)
    }

    // endregion

    // region Property access

    @Test
    fun `get returns value for existing property`() {
        edge["weight"] = 3.14.numVal

        assertEquals(3.14, (edge["weight"] as NumVal).core)
    }

    @Test
    fun `get returns null for absent property`() {
        assertNull(edge["missing"])
    }

    @Test
    fun `set stores property`() {
        edge["tag"] = "important".strVal

        assertEquals("important", (edge["tag"] as StrVal).core)
    }

    @Test
    fun `set null removes property`() {
        edge["tag"] = "important".strVal

        edge["tag"] = null

        assertNull(edge["tag"])
        assertFalse("tag" in edge)
    }

    @Test
    fun `contains returns true for existing property`() {
        edge["weight"] = 1.0.numVal

        assertTrue("weight" in edge)
    }

    @Test
    fun `contains returns false for absent property`() {
        assertFalse("missing" in edge)
    }

    @Test
    fun `asMap returns empty map when no properties`() {
        assertTrue(edge.asMap().isEmpty())
    }

    @Test
    fun `asMap returns all properties`() {
        edge["a"] = "x".strVal
        edge["b"] = 2.numVal

        val map = edge.asMap()

        assertEquals(2, map.size)
    }

    @Test
    fun `update sets multiple properties`() {
        edge.update(mapOf("a" to "x".strVal, "b" to 1.numVal))

        assertEquals("x", (edge["a"] as StrVal).core)
        assertEquals(1, (edge["b"] as NumVal).core)
    }

    @Test
    fun `update null values remove properties`() {
        edge["a"] = "x".strVal
        edge["b"] = 1.numVal

        edge.update(mapOf("a" to null, "b" to 2.numVal))

        assertNull(edge["a"])
        assertEquals(2, (edge["b"] as NumVal).core)
    }

    // endregion

    // region Equals / hashCode / toString

    @Test
    fun `equals returns true for same storageId`() {
        val other = TestEdge()
        other.bind(storage, edge.storageId, "srcNode", "dstNode", "calls")

        assertEquals(edge, other)
    }

    @Test
    fun `equals returns false for different storageId`() {
        val eid2 = storage.addEdge(srcSid, dstSid, "other")
        val other = TestEdge()
        other.bind(storage, eid2, "srcNode", "dstNode", "other")

        assertNotEquals(edge, other)
    }

    @Test
    fun `equals returns false for non-edge object`() {
        assertNotEquals<Any>(edge, "not an edge")
    }

    @Test
    fun `toString includes src-tag-dst and type`() {
        val str = edge.toString()

        assertTrue(str.contains("calls"))
        assertTrue(str.contains("TestEdge"))
    }

    // endregion
}
