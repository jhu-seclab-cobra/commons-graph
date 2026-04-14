package edu.jhu.cobra.commons.graph.storage

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Minimal reproduction tests for suspected MapDB storage bugs.
 *
 * - `getOutgoingEdges returns mutable internal set` — caller mutation corrupts adjacency index
 */
internal class MapDBBugVerificationTest {

    @Test
    fun `getOutgoingEdges returns mutable internal set - caller can corrupt adjacency`() {
        val storage = MapDBStorageImpl()
        val nodeA = storage.addNode()
        val nodeB = storage.addNode()
        val edgeId = storage.addEdge(nodeA, nodeB, "tag")

        val outEdges = storage.getOutgoingEdges(nodeA)
        assertEquals(1, outEdges.size, "Should have 1 outgoing edge")

        // Attempt to mutate the returned set — if mutable internal set is returned, this corrupts the index
        try {
            (outEdges as MutableSet<Int>).clear()
        } catch (_: UnsupportedOperationException) {
            // If this throws, the set is properly unmodifiable — no bug
            storage.close()
            return
        }

        // If we got here, the set was mutable and we corrupted the index
        val outEdgesAfter = storage.getOutgoingEdges(nodeA)
        assertEquals(
            1, outEdgesAfter.size,
            "Adjacency should still show 1 edge — but if mutable set was returned, it was corrupted to 0"
        )
        storage.close()
    }
}
