package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.TestEdge
import edu.jhu.cobra.commons.graph.GraphTestUtils.TestNode
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Minimal reproduction tests for suspected bugs.
 * Each test isolates one defect. Run before fixing to confirm the bug exists.
 *
 * - `AbcNode hashCode equals contract` — hashCode uses storageId but equals uses id
 * - `MapDB getOutgoingEdges returns mutable internal set` — caller can corrupt adjacency
 */
internal class BugVerificationTest {

    // --- Bug #1: AbcNode hashCode/equals contract violation ---

    @Test
    fun `AbcNode hashCode equals contract - same id different storageId`() {
        val storage1 = NativeStorageImpl()
        val storage2 = NativeStorageImpl()

        val node1 = TestNode()
        val sid1 = storage1.addNode()
        node1.bind(storage1, sid1, "shared-id")

        val node2 = TestNode()
        val sid2 = storage2.addNode()
        // Ensure different storageId by adding an extra node first
        storage2.addNode()
        val sid3 = storage2.addNode()
        node2.bind(storage2, sid3, "shared-id")

        // equals should be true (same id)
        assertEquals(node1, node2, "Nodes with same id should be equal")

        // hashCode contract: equal objects must have equal hashCode
        assertEquals(
            node1.hashCode(), node2.hashCode(),
            "Equal nodes must have equal hashCode (contract violation: hashCode uses storageId but equals uses id)"
        )
    }

    @Test
    fun `AbcNode in HashSet - same id different storageId treated as duplicates`() {
        val storage = NativeStorageImpl()

        val node1 = TestNode()
        node1.bind(storage, storage.addNode(), "same-id")

        val node2 = TestNode()
        node2.bind(storage, storage.addNode(), "same-id")

        // Two nodes with same id should be deduplicated in a HashSet
        val set = hashSetOf(node1, node2)
        assertEquals(1, set.size, "HashSet should treat equal nodes as one (broken if hashCode differs)")
    }
}
