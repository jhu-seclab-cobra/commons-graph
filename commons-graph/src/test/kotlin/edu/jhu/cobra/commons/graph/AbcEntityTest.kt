package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for AbcEntity: property access, type, and getTypeProp.
 *
 * - `get returns value when property exists` -- verifies operator get returns stored IValue
 * - `get returns null when property absent` -- verifies absent property yields null
 * - `set stores value` -- verifies operator set writes property
 * - `set null removes property` -- verifies null value deletes the property
 * - `contains returns true when property exists` -- verifies operator contains for present key
 * - `contains returns false when property absent` -- verifies operator contains for missing key
 * - `asMap returns empty map when no properties` -- verifies empty initial state
 * - `asMap returns all properties as snapshot` -- verifies complete property map
 * - `update sets multiple properties` -- verifies bulk set of name-value pairs
 * - `update null values remove properties` -- verifies null entries in update map remove keys
 * - `update with empty map is no-op` -- verifies empty update preserves state
 * - `IEntity Type exposes name` -- verifies Type.name contract
 * - `getTypeProp returns typed value when type matches` -- verifies reified cast on match
 * - `getTypeProp returns null when type mismatches` -- verifies reified cast returns null on mismatch
 * - `getTypeProp returns null when property absent` -- verifies absent property returns null
 * - `EntityProperty nullable delegate returns null when storage value is null` -- nullable delegate null in storage
 * - `EntityType delegate returns default when stored value is non-StrVal type` -- wrong type in storage
 * - `getTypeProp returns null when property exists but wrong type` -- type mismatch returns null
 * - `EntityType delegate set same value is no-op` -- skip write when value unchanged
 */
internal class AbcEntityTest {
    private lateinit var storage: NativeStorageImpl

    private lateinit var testNode: GraphFixtures.TestNode

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        val sid = storage.addNode()
        testNode = GraphFixtures.TestNode()
        testNode.bind(storage, sid, "entity-test")
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
        testNode["age"] = 30.intVal

        assertEquals(30L, (testNode["age"] as IntVal).core)
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
        testNode["b"] = 1.intVal

        val map = testNode.asMap()

        assertEquals(2, map.size)
        assertEquals("x", (map["a"] as StrVal).core)
        assertEquals(1L, (map["b"] as IntVal).core)
    }

    @Test
    fun `update sets multiple properties`() {
        testNode.update(
            mapOf(
                "name" to "alice".strVal,
                "age" to 25.intVal,
            ),
        )

        assertEquals("alice", (testNode["name"] as StrVal).core)
        assertEquals(25L, (testNode["age"] as IntVal).core)
    }

    @Test
    fun `update null values remove properties`() {
        testNode["name"] = "alice".strVal
        testNode["age"] = 25.intVal

        testNode.update(mapOf("name" to null, "age" to 30.intVal))

        assertNull(testNode["name"])
        assertEquals(30L, (testNode["age"] as IntVal).core)
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
        val type =
            object : IEntity.Type {
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
        testNode["age"] = 25.intVal

        val result: StrVal? = testNode.getTypeProp<StrVal>("age")

        assertNull(result)
    }

    @Test
    fun `getTypeProp returns null when property absent`() {
        val result: StrVal? = testNode.getTypeProp("missing")

        assertNull(result)
    }

    // endregion

    // region Branch coverage

    @Test
    fun `EntityProperty nullable delegate returns null when storage value is null`() {
        val sid = storage.addNode()
        val node = PropNode().also { it.bind(storage, sid, "p") }
        node.opt = "temp".strVal
        node["opt"] = null

        assertNull(node.opt)
    }

    @Test
    fun `EntityType delegate returns default when stored value is non-StrVal type`() {
        val sid = storage.addNode()
        val node = TypeNode().also { it.bind(storage, sid, "t") }
        node["myKind"] = 42.intVal

        assertEquals(Kind.SOURCE, node.namedKind)
    }

    @Test
    fun `getTypeProp returns null when property exists but wrong type`() {
        testNode["count"] = 100.intVal

        val result: StrVal? = testNode.getTypeProp("count")

        assertNull(result)
    }

    @Test
    fun `EntityType delegate set same value is no-op`() {
        val sid = storage.addNode()
        val node = TypeNode().also { it.bind(storage, sid, "t") }
        node.namedKind = Kind.SINK

        node.namedKind = Kind.SINK

        assertEquals(Kind.SINK, node.namedKind)
    }

    // endregion
}
