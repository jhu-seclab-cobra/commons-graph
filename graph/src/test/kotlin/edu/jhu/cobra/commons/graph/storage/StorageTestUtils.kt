package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID

/**
 * Test utilities for storage tests providing shared test data.
 */
object StorageTestUtils {
    val node1 = NodeID("node1")
    val node2 = NodeID("node2")
    val node3 = NodeID("node3")
    val edge1 = EdgeID(node1, node2, "edge1")
    val edge2 = EdgeID(node2, node3, "edge2")
    val edge3 = EdgeID(node1, node3, "edge3")
}
