package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class TransferToTest {
    @Test
    fun `test transferTo copies nodes and properties`() {
        val source = NativeStorageImpl()
        val node1 = source.addNode(mapOf("name" to "A".strVal))
        val node2 = source.addNode(mapOf("name" to "B".strVal))

        val target = NativeStorageImpl()
        source.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        // Node IDs are re-generated in target, so check properties via iteration
        val targetProps = target.nodeIDs.map { target.getNodeProperties(it) }
        val names = targetProps.mapNotNull { (it["name"] as? StrVal)?.core }.toSet()
        assertEquals(setOf("A", "B"), names)

        source.close()
        target.close()
    }

    @Test
    fun `test transferTo copies edges and properties`() {
        val source = NativeStorageImpl()
        val node1 = source.addNode()
        val node2 = source.addNode()
        val node3 = source.addNode()
        val edge1 = source.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1, mapOf("weight" to 1.5.numVal))
        val edge2 = source.addEdge(node2, node3, StorageTestUtils.EDGE_TYPE_2, mapOf("label" to "x".strVal))

        val target = NativeStorageImpl()
        source.transferTo(target)

        assertEquals(3, target.nodeIDs.size)
        assertEquals(2, target.edgeIDs.size)

        // Verify edge properties were transferred
        val edgeWeights = target.edgeIDs.map { target.getEdgeProperties(it) }
        val weights = edgeWeights.mapNotNull { (it["weight"] as? NumVal)?.core }
        assertTrue(weights.contains(1.5))
        val labels = edgeWeights.mapNotNull { (it["label"] as? StrVal)?.core }
        assertTrue(labels.contains("x"))

        source.close()
        target.close()
    }

    @Test
    fun `test transferTo empty storage`() {
        val source = NativeStorageImpl()
        val target = NativeStorageImpl()
        source.transferTo(target)

        assertTrue(target.nodeIDs.isEmpty())
        assertTrue(target.edgeIDs.isEmpty())

        source.close()
        target.close()
    }

    @Test
    fun `test transferTo does not modify source`() {
        val source = NativeStorageImpl()
        val node1 = source.addNode(mapOf("k" to "v".strVal))
        val node2 = source.addNode()
        val edge1 = source.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)

        val target = NativeStorageImpl()
        source.transferTo(target)

        assertTrue(source.containsNode(node1))
        assertTrue(source.containsNode(node2))
        assertTrue(source.containsEdge(edge1))
        assertEquals("v", (source.getNodeProperties(node1)["k"] as StrVal).core)

        source.close()
        target.close()
    }
}
