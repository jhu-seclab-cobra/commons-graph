package edu.jhu.cobra.commons.graph.storage

import java.util.concurrent.atomic.AtomicInteger

/**
 * Test utilities for storage tests providing shared test data.
 */
object StorageTestUtils {
    const val EDGE_TAG_1 = "edge1"
    const val EDGE_TAG_2 = "edge2"
    const val EDGE_TAG_3 = "edge3"

    private val nodeCounter = AtomicInteger(0)
    private val edgeCounter = AtomicInteger(0)

    /**
     * Generates a unique node ID for testing.
     */
    fun genNodeId(): String = "test_node_${nodeCounter.getAndIncrement()}"

    /**
     * Generates a unique edge ID for testing.
     */
    fun genEdgeId(): String = "test_edge_${edgeCounter.getAndIncrement()}"

    /**
     * Resets all counters. Call at the start of each test class.
     */
    fun resetCounters() {
        nodeCounter.set(0)
        edgeCounter.set(0)
    }

    /**
     * Adds 3 test nodes to the storage and returns their IDs.
     */
    fun addTestNodes(storage: IStorage): Triple<String, String, String> {
        val n1 = storage.addNode(genNodeId())
        val n2 = storage.addNode(genNodeId())
        val n3 = storage.addNode(genNodeId())
        return Triple(n1, n2, n3)
    }
}
