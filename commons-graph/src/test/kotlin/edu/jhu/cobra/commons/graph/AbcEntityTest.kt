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
import kotlin.test.assertTrue

/**
 * Black-box tests for IEntity sealed interface and AbcEntity delegate utilities.
 *
 * - `get returns value when property exists` — verifies operator get returns stored IValue
 * - `get returns null when property absent` — verifies absent property yields null
 * - `set stores value` — verifies operator set writes property
 * - `set null removes property` — verifies null value deletes the property
 * - `contains returns true when property exists` — verifies operator contains for present key
 * - `contains returns false when property absent` — verifies operator contains for missing key
 * - `asMap returns empty map when no properties` — verifies empty initial state
 * - `asMap returns all properties as snapshot` — verifies complete property map
 * - `update sets multiple properties` — verifies bulk set of name-value pairs
 * - `update null values remove properties` — verifies null entries in update map remove keys
 * - `update with empty map is no-op` — verifies empty update preserves state
 * - `IEntity Type exposes name` — verifies Type.name contract
 * - `getTypeProp returns typed value when type matches` — verifies reified cast on match
 * - `getTypeProp returns null when type mismatches` — verifies reified cast returns null on mismatch
 * - `getTypeProp returns null when property absent` — verifies absent property returns null
 * - `EntityProperty delegate returns default when unset` — verifies default fallback
 * - `EntityProperty delegate set and get round-trips` — verifies delegate write-read cycle
 * - `EntityProperty delegate custom name uses custom storage key` — verifies optName mapping
 * - `EntityProperty nullable delegate returns null initially` — verifies nullable default
 * - `EntityProperty nullable delegate set and get round-trips` — verifies nullable write-read
 * - `EntityProperty nullable set to null does not write` — verifies null assignment is no-op
 * - `EntityType delegate returns default when unset` — verifies enum type default
 * - `EntityType delegate set and get round-trips` — verifies enum type write-read
 * - `EntityType delegate custom name uses custom storage key` — verifies optName mapping
 * - `EntityType delegate returns default on unknown stored value` — verifies fallback for bad data
 */
internal class AbcEntityTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var testNode: GraphTestUtils.TestNode

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        val sid = storage.addNode()
        testNode = GraphTestUtils.TestNode()
        testNode.bind(storage, sid, "entity-test")
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // region IEntity get/set/contains/asMap/update

    @Test
    fun `get returns value when property exists`() {
        testNode["name"] = "alice".strVal

        val result = testNode["name"]

        assertNotNull(result)
        assertEquals("alice", (result as StrVal).core)
    }

    @Test
    fun `get returns null when property absent`() {
        assertNull(testNode["nonexistent"])
    }

    @Test
    fun `set stores value`() {
        testNode["age"] = 30.numVal

        assertEquals(30, (testNode["age"] as NumVal).core)
    }

    @Test
    fun `set null removes property`() {
        testNode["name"] = "alice".strVal

        testNode["name"] = null

        assertNull(testNode["name"])
        assertFalse("name" in testNode)
    }

    @Test
    fun `contains returns true when property exists`() {
        testNode["name"] = "alice".strVal

        assertTrue("name" in testNode)
    }

    @Test
    fun `contains returns false when property absent`() {
        assertFalse("missing" in testNode)
    }

    @Test
    fun `asMap returns empty map when no properties`() {
        assertTrue(testNode.asMap().isEmpty())
    }

    @Test
    fun `asMap returns all properties as snapshot`() {
        testNode["a"] = "x".strVal
        testNode["b"] = 1.numVal

        val map = testNode.asMap()

        assertEquals(2, map.size)
        assertEquals("x", (map["a"] as StrVal).core)
        assertEquals(1, (map["b"] as NumVal).core)
    }

    @Test
    fun `update sets multiple properties`() {
        testNode.update(
            mapOf(
                "name" to "alice".strVal,
                "age" to 25.numVal,
            ),
        )

        assertEquals("alice", (testNode["name"] as StrVal).core)
        assertEquals(25, (testNode["age"] as NumVal).core)
    }

    @Test
    fun `update null values remove properties`() {
        testNode["name"] = "alice".strVal
        testNode["age"] = 25.numVal

        testNode.update(mapOf("name" to null, "age" to 30.numVal))

        assertNull(testNode["name"])
        assertEquals(30, (testNode["age"] as NumVal).core)
    }

    @Test
    fun `update with empty map is no-op`() {
        testNode["name"] = "alice".strVal

        testNode.update(emptyMap())

        assertEquals(1, testNode.asMap().size)
    }

    // endregion

    // region IEntity.Type

    @Test
    fun `IEntity Type exposes name`() {
        val type = object : IEntity.Type {
            override val name = "CustomType"
        }

        assertEquals("CustomType", type.name)
    }

    // endregion

    // region getTypeProp

    @Test
    fun `getTypeProp returns typed value when type matches`() {
        testNode["name"] = "alice".strVal

        val result: StrVal? = testNode.getTypeProp("name")

        assertNotNull(result)
        assertEquals("alice", result.core)
    }

    @Test
    fun `getTypeProp returns null when type mismatches`() {
        testNode["age"] = 25.numVal

        val result: StrVal? = testNode.getTypeProp<StrVal>("age")

        assertNull(result)
    }

    @Test
    fun `getTypeProp returns null when property absent`() {
        val result: StrVal? = testNode.getTypeProp("missing")

        assertNull(result)
    }

    // endregion

    // region EntityProperty delegate

    private class PropNode : AbcNode() {
        override val type: AbcNode.Type = object : AbcNode.Type {
            override val name = "PropNode"
        }
        var label: StrVal by EntityProperty(default = "default".strVal)
        var custom: StrVal by EntityProperty("customKey", default = "d".strVal)
        var opt: StrVal? by EntityProperty()
    }

    @Test
    fun `EntityProperty delegate returns default when unset`() {
        val sid = storage.addNode()
        val node = PropNode().also { it.bind(storage, sid, "p") }

        assertEquals("default", node.label.core)
    }

    @Test
    fun `EntityProperty delegate set and get round-trips`() {
        val sid = storage.addNode()
        val node = PropNode().also { it.bind(storage, sid, "p") }

        node.label = "updated".strVal

        assertEquals("updated", node.label.core)
    }

    @Test
    fun `EntityProperty delegate custom name uses custom storage key`() {
        val sid = storage.addNode()
        val node = PropNode().also { it.bind(storage, sid, "p") }

        node.custom = "val".strVal

        assertEquals("val", (node["customKey"] as? StrVal)?.core)
    }

    @Test
    fun `EntityProperty nullable delegate returns null initially`() {
        val sid = storage.addNode()
        val node = PropNode().also { it.bind(storage, sid, "p") }

        assertNull(node.opt)
    }

    @Test
    fun `EntityProperty nullable delegate set and get round-trips`() {
        val sid = storage.addNode()
        val node = PropNode().also { it.bind(storage, sid, "p") }

        node.opt = "hello".strVal

        assertEquals("hello", node.opt?.core)
    }

    @Test
    fun `EntityProperty nullable set to null does not write`() {
        val sid = storage.addNode()
        val node = PropNode().also { it.bind(storage, sid, "p") }

        node.opt = null

        assertNull(node.opt)
        assertFalse("opt" in node)
    }

    // endregion

    // region EntityType delegate

    private enum class Kind : IEntity.Type {
        SOURCE,
        SINK,
    }

    private class TypeNode : AbcNode() {
        override val type: AbcNode.Type = object : AbcNode.Type {
            override val name = "TypeNode"
        }
        var kind: Kind by EntityType(default = Kind.SOURCE)
        var namedKind: Kind by EntityType("myKind", default = Kind.SOURCE)
    }

    @Test
    fun `EntityType delegate returns default when unset`() {
        val sid = storage.addNode()
        val node = TypeNode().also { it.bind(storage, sid, "t") }

        assertEquals(Kind.SOURCE, node.kind)
    }

    @Test
    fun `EntityType delegate set and get round-trips`() {
        val sid = storage.addNode()
        val node = TypeNode().also { it.bind(storage, sid, "t") }

        node.kind = Kind.SINK

        assertEquals(Kind.SINK, node.kind)
    }

    @Test
    fun `EntityType delegate custom name uses custom storage key`() {
        val sid = storage.addNode()
        val node = TypeNode().also { it.bind(storage, sid, "t") }

        node.namedKind = Kind.SINK

        assertEquals("SINK", (node["myKind"] as? StrVal)?.core)
    }

    @Test
    fun `EntityType delegate returns default on unknown stored value`() {
        val sid = storage.addNode()
        val node = TypeNode().also { it.bind(storage, sid, "t") }
        node["myKind"] = "INVALID_VALUE".strVal

        assertEquals(Kind.SOURCE, node.namedKind)
    }

    // endregion
}
