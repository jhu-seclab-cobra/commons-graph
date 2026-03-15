package edu.jhu.cobra.commons.graph.storage

/**
 * Test utilities for storage tests providing shared test data.
 *
 * Since IStorage now auto-generates node IDs and derives edge IDs,
 * tests should use [addTestNodes] and [addTestEdge] helpers.
 */
object StorageTestUtils {
    const val EDGE_TYPE_1 = "edge1"
    const val EDGE_TYPE_2 = "edge2"
    const val EDGE_TYPE_3 = "edge3"

    /**
     * Adds 3 test nodes to the storage and returns their auto-generated IDs.
     */
    fun addTestNodes(storage: IStorage): Triple<Int, Int, Int> {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        return Triple(n1, n2, n3)
    }
}
