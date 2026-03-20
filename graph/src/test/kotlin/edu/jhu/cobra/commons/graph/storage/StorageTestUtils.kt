package edu.jhu.cobra.commons.graph.storage

/**
 * Test utilities for storage tests providing shared test data.
 */
object StorageTestUtils {
    const val EDGE_TAG_1 = "edge1"
    const val EDGE_TAG_2 = "edge2"
    const val EDGE_TAG_3 = "edge3"

    /**
     * Adds 3 test nodes to the storage and returns their IDs.
     */
    fun addTestNodes(storage: IStorage): Triple<Int, Int, Int> {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        return Triple(n1, n2, n3)
    }
}
