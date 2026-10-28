/**
 * Existing node identities are read without claiming their rows.
 * - `reading a foreign identity preserves graph membership and owners`: read only the persisted source row.
 * - `reading a registered identity reuses its wrapper`: the owning registry remains authoritative.
 * - `reading an identity rejects a different persisted id`: a supplied row cannot name another node.
 * - `reading an identity rejects a missing row`: an absent identity fails at acquisition.
 */
package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

internal class NodeAcquisitionTest {
    private val storage = NativeStorageImpl()
    private val source = GraphFixtures.TestMultipleGraph(storage, "source")
    private val receiver = GraphFixtures.TestMultipleGraph(storage, "receiver")

    @Test
    fun `reading a foreign identity preserves graph membership and owners`() {
        val node = source.addNode("node").also { it["value"] = "selected".strVal }
        val before = storage.getNodeProperties(node.storageId)
        val read = receiver.readNode(identityOf(node))
        assertEquals("selected".strVal, read["value"])
        assertEquals(node.id, read.id)
        assertEquals(node.storageId, read.storageId)
        assertEquals(emptySet(), receiver.nodeIDs)
        assertEquals(before, storage.getNodeProperties(node.storageId))
    }

    @Test
    fun `reading a registered identity reuses its wrapper`() {
        val node = receiver.addNode("node")
        assertSame(node, receiver.readNode(identityOf(node)))
    }

    @Test
    fun `reading an identity rejects a different persisted id`() {
        val node = source.addNode("node")
        val identity = object : NodeIdentity {
            override val id = "other"
            override val storageId = node.storageId
        }
        assertFailsWith<IllegalArgumentException> { receiver.readNode(identity) }
    }

    @Test
    fun `reading an identity rejects a missing row`() {
        val node = source.addNode("node")
        val identity = identityOf(node)
        source.delNode(node.id)
        assertFailsWith<IllegalArgumentException> { receiver.readNode(identity) }
    }

    private fun identityOf(node: AbcNode): NodeIdentity =
        object : NodeIdentity {
            override val id = node.id
            override val storageId = node.storageId
        }
}
