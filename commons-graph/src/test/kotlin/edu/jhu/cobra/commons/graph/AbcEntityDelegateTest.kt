package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for AbcEntity property delegates: EntityProperty and EntityType.
 *
 * - `EntityProperty delegate returns default when unset` -- verifies default fallback
 * - `EntityProperty delegate set and get round-trips` -- verifies delegate write-read cycle
 * - `EntityProperty delegate custom name uses custom storage key` -- verifies optName mapping
 * - `EntityProperty nullable delegate returns null initially` -- verifies nullable default
 * - `EntityProperty nullable delegate set and get round-trips` -- verifies nullable write-read
 * - `EntityProperty nullable set to null does not write` -- verifies null assignment is no-op
 * - `EntityType delegate returns default when unset` -- verifies enum type default
 * - `EntityType delegate set and get round-trips` -- verifies enum type write-read
 * - `EntityType delegate custom name uses custom storage key` -- verifies optName mapping
 * - `EntityType delegate auto name uses entity class prefix` -- verifies auto-generated key is
 *   prefixed with the entity class name, not the anonymous delegate class
 * - `EntityType delegate returns default on unknown stored value` -- verifies fallback for bad data
 * - `nullable EntityProperty delegate set null should remove property` -- B3 null propagation
 */
internal class AbcEntityDelegateTest {
    private lateinit var storage: NativeStorageImpl

    private lateinit var testNode: GraphFixtures.TestNode

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        val sid = storage.addNode()
        testNode = GraphFixtures.TestNode()
        testNode.bind(storage, sid, "entity-test")
    }

    // region EntityProperty delegate

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
    fun `EntityType delegate auto name uses entity class prefix`() {
        val sid = storage.addNode()
        val node = TypeNode().also { it.bind(storage, sid, "t") }

        node.kind = Kind.SINK

        assertEquals("SINK", (node["typenode_kind"] as? StrVal)?.core)
    }

    @Test
    fun `EntityType delegate returns default on unknown stored value`() {
        val sid = storage.addNode()
        val node = TypeNode().also { it.bind(storage, sid, "t") }
        node["myKind"] = "INVALID_VALUE".strVal

        assertEquals(Kind.SOURCE, node.namedKind)
    }

    // endregion

    class NullableTestNode : AbcNode() {
        override val type =
            object : AbcNode.Type {
                override val name = "NullableTest"
            }
        var optProp: IValue? by EntityProperty<IValue>("opt_prop")
    }

    @Test
    fun `nullable EntityProperty delegate set null should remove property`() {
        val storage = NativeStorageImpl()
        val node = NullableTestNode()
        node.bind(storage, storage.addNode(), "test-node")
        node.optProp = StrVal("hello")
        assertTrue("opt_prop" in node)

        node.optProp = null

        assertFalse("opt_prop" in node)
        assertNull(node.optProp)
    }
}
