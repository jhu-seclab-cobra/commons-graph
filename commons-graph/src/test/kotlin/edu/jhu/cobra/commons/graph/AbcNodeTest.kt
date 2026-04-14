package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.TestNode
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
 * Black-box tests for AbcNode: bind, id, type, property access with PROP_NODE_ID filtering,
 * equals/hashCode.
 *
 * - `id returns nodeId injected via bind` — verifies id delegates to nodeId
 * - `type returns subclass-defined type` — verifies abstract type override
 * - `get filters PROP_NODE_ID` — verifies internal property hidden from user
 * - `set rejects PROP_NODE_ID` — verifies require guard on reserved property
 * - `contains filters PROP_NODE_ID` — verifies internal property excluded
 * - `asMap filters PROP_NODE_ID` — verifies internal property excluded from map
 * - `update rejects PROP_NODE_ID` — verifies require guard on bulk update
 * - `get returns value for user property` — verifies normal property read
 * - `set stores user property` — verifies normal property write
 * - `set null removes user property` — verifies null removes property
 * - `contains returns true for existing user property` — verifies contains on present key
 * - `contains returns false for absent property` — verifies contains on missing key
 * - `asMap returns all user properties` — verifies complete map minus internal
 * - `update sets multiple user properties` — verifies bulk update
 * - `equals returns true for same id` — verifies equality by id
 * - `equals returns false for different id` — verifies inequality by id
 * - `equals returns false for non-node object` — verifies type guard
 * - `hashCode uses id` — verifies hashCode consistency with equals
 * - `toString includes id and type` — verifies string representation
 */
internal class AbcNodeTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var node: TestNode

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        val sid = storage.addNode()
        node = TestNode()
        node.bind(storage, sid, NODE_ID_1)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // region Identity

    @Test
    fun `id returns nodeId injected via bind`() {
        assertEquals(NODE_ID_1, node.id)
    }

    @Test
    fun `type returns subclass-defined type`() {
        assertEquals("TestNode", node.type.name)
    }

    // endregion

    // region PROP_NODE_ID filtering

    @Test
    fun `get filters PROP_NODE_ID`() {
        assertNull(node[AbcMultipleGraph.PROP_NODE_ID])
    }

    @Test
    fun `set rejects PROP_NODE_ID`() {
        assertFailsWith<IllegalArgumentException> {
            node[AbcMultipleGraph.PROP_NODE_ID] = "bad".strVal
        }
    }

    @Test
    fun `contains filters PROP_NODE_ID`() {
        assertFalse(AbcMultipleGraph.PROP_NODE_ID in node)
    }

    @Test
    fun `asMap filters PROP_NODE_ID`() {
        val map = node.asMap()

        assertFalse(map.containsKey(AbcMultipleGraph.PROP_NODE_ID))
    }

    @Test
    fun `update rejects PROP_NODE_ID`() {
        assertFailsWith<IllegalArgumentException> {
            node.update(mapOf(AbcMultipleGraph.PROP_NODE_ID to "bad".strVal))
        }
    }

    // endregion

    // region Property access

    @Test
    fun `get returns value for user property`() {
        node["name"] = "alice".strVal

        assertEquals("alice", (node["name"] as StrVal).core)
    }

    @Test
    fun `set stores user property`() {
        node["count"] = 42.numVal

        assertEquals(42, (node["count"] as NumVal).core)
    }

    @Test
    fun `set null removes user property`() {
        node["name"] = "alice".strVal

        node["name"] = null

        assertNull(node["name"])
        assertFalse("name" in node)
    }

    @Test
    fun `contains returns true for existing user property`() {
        node["name"] = "alice".strVal

        assertTrue("name" in node)
    }

    @Test
    fun `contains returns false for absent property`() {
        assertFalse("missing" in node)
    }

    @Test
    fun `asMap returns all user properties`() {
        node["a"] = "x".strVal
        node["b"] = 1.numVal

        val map = node.asMap()

        assertEquals(2, map.size)
        assertEquals("x", (map["a"] as StrVal).core)
        assertEquals(1, (map["b"] as NumVal).core)
    }

    @Test
    fun `update sets multiple user properties`() {
        node.update(mapOf("a" to "x".strVal, "b" to 2.numVal))

        assertEquals("x", (node["a"] as StrVal).core)
        assertEquals(2, (node["b"] as NumVal).core)
    }

    // endregion

    // region Equals / hashCode / toString

    @Test
    fun `equals returns true for same id`() {
        val other = TestNode()
        other.bind(storage, storage.addNode(), NODE_ID_1)

        assertEquals(node, other)
    }

    @Test
    fun `equals returns false for different id`() {
        val other = TestNode()
        other.bind(storage, storage.addNode(), "different")

        assertNotEquals(node, other)
    }

    @Test
    fun `equals returns false for non-node object`() {
        assertNotEquals<Any>(node, "not a node")
    }

    @Test
    fun `hashCode uses id`() {
        val other = TestNode()
        other.bind(storage, storage.addNode(), NODE_ID_1)

        assertEquals(node.hashCode(), other.hashCode())
        assertEquals(NODE_ID_1.hashCode(), node.hashCode())
    }

    @Test
    fun `toString includes id and type`() {
        val str = node.toString()

        assertTrue(str.contains(NODE_ID_1))
        assertTrue(str.contains("TestNode"))
    }

    // endregion

    // region Utility: assertFailsWith (inline for kotlin.test)

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName} but no exception was thrown")
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
    }

    // endregion
}
