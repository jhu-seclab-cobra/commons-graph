package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for AbcNode: identity, property access, and equality contract.
 *
 * - `id returns nodeId injected via bind` -- verifies id delegates to nodeId
 * - `type returns subclass-defined type` -- verifies abstract type override
 * - `get returns value for user property` -- verifies normal property read
 * - `set stores user property` -- verifies normal property write
 * - `set null removes user property` -- verifies null removes property
 * - `contains returns true for existing user property` -- verifies contains on present key
 * - `contains returns false for absent property` -- verifies contains on missing key
 * - `asMap returns all user properties` -- verifies complete map minus internal
 * - `asMap returns snapshot unaffected by later writes` -- verifies snapshot semantics
 * - `update sets multiple user properties` -- verifies bulk update
 * - `equals returns true for same id` -- verifies equality by id
 * - `equals returns false for different id` -- verifies inequality by id
 * - `equals returns false for non-node object` -- verifies type guard
 * - `hashCode uses id` -- verifies hashCode consistency with equals
 * - `toString includes id and type` -- verifies string representation
 * - `AbcNode hashCode equals contract - same id different storageId` -- hashCode must match equals
 * - `AbcNode in HashSet - same id different storageId treated as duplicates` -- collection dedup
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

    // region Property access

    @Test
    fun `get returns value for user property`() {
        node["name"] = "alice".strVal

        assertEquals("alice", (node["name"] as StrVal).core)
    }

    @Test
    fun `set stores user property`() {
        node["count"] = 42.intVal

        assertEquals(42L, (node["count"] as IntVal).core)
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
        node["b"] = 1.intVal

        val map = node.asMap()

        assertEquals(2, map.size)
        assertEquals("x", (map["a"] as StrVal).core)
        assertEquals(1L, (map["b"] as IntVal).core)
    }

    @Test
    fun `asMap returns snapshot unaffected by later writes`() {
        node["a"] = "x".strVal

        val map = node.asMap()
        node["b"] = "y".strVal

        assertEquals(1, map.size)
    }

    @Test
    fun `update sets multiple user properties`() {
        node.update(mapOf("a" to "x".strVal, "b" to 2.intVal))

        assertEquals("x", (node["a"] as StrVal).core)
        assertEquals(2L, (node["b"] as IntVal).core)
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

    @Test
    fun `AbcNode hashCode equals contract - same id different storageId`() {
        val storage1 = NativeStorageImpl()
        val storage2 = NativeStorageImpl()
        val node1 = TestNode()
        node1.bind(storage1, storage1.addNode(), "shared-id")
        val node2 = TestNode()
        storage2.addNode()
        node2.bind(storage2, storage2.addNode(), "shared-id")

        assertEquals(node1, node2)
        assertEquals(node1.hashCode(), node2.hashCode())
    }

    @Test
    fun `AbcNode in HashSet - same id different storageId treated as duplicates`() {
        val storage = NativeStorageImpl()
        val node1 = TestNode()
        node1.bind(storage, storage.addNode(), "same-id")
        val node2 = TestNode()
        node2.bind(storage, storage.addNode(), "same-id")

        val set = hashSetOf(node1, node2)

        assertEquals(1, set.size)
    }
}
