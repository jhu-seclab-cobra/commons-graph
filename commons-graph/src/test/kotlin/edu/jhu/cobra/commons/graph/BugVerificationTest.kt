package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.TestNode
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Minimal reproduction tests for suspected bugs.
 * Each test isolates one defect.
 *
 * - `AbcNode hashCode equals contract - same id different storageId` — hashCode must match equals
 * - `AbcNode in HashSet - same id different storageId treated as duplicates` — collection dedup
 * - `nullable EntityProperty delegate set null should remove property` — B3 null propagation
 */
internal class BugVerificationTest {

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

    class NullableTestNode : AbcNode() {
        override val type = object : AbcNode.Type { override val name = "NullableTest" }
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
