package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.*
import kotlin.test.*

/**
 * Tests for IStorage.transferTo() extension function.
 */
class TransferToExtensionTest {

    private val node1 = StorageTestUtils.node1
    private val node2 = StorageTestUtils.node2
    private val node3 = StorageTestUtils.node3
    private val edge1 = StorageTestUtils.edge1
    private val edge2 = StorageTestUtils.edge2

    @Test
    fun `test transferTo copies nodes and properties`() {
        val source = NativeStorageImpl()
        source.addNode(node1, mapOf("name" to "A".strVal))
        source.addNode(node2, mapOf("name" to "B".strVal))

        val target = NativeStorageImpl()
        source.transferTo(target)

        assertTrue(target.containsNode(node1))
        assertTrue(target.containsNode(node2))
        assertEquals("A", (target.getNodeProperties(node1)["name"] as StrVal).core)
        assertEquals("B", (target.getNodeProperties(node2)["name"] as StrVal).core)

        source.close()
        target.close()
    }

    @Test
    fun `test transferTo copies edges and properties`() {
        val source = NativeStorageImpl()
        source.addNode(node1)
        source.addNode(node2)
        source.addNode(node3)
        source.addEdge(edge1, mapOf("weight" to 1.5.numVal))
        source.addEdge(edge2, mapOf("label" to "x".strVal))

        val target = NativeStorageImpl()
        source.transferTo(target)

        assertTrue(target.containsEdge(edge1))
        assertTrue(target.containsEdge(edge2))
        assertEquals(1.5, (target.getEdgeProperties(edge1)["weight"] as NumVal).core)
        assertEquals("x", (target.getEdgeProperties(edge2)["label"] as StrVal).core)

        // Adjacency should be correct
        val outgoing = target.getOutgoingEdges(node1)
        assertTrue(outgoing.contains(edge1))
        val incoming = target.getIncomingEdges(node2)
        assertTrue(incoming.contains(edge1))

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
        source.addNode(node1, mapOf("k" to "v".strVal))
        source.addNode(node2)
        source.addEdge(edge1)

        val target = NativeStorageImpl()
        source.transferTo(target)

        // Source is unchanged
        assertTrue(source.containsNode(node1))
        assertTrue(source.containsNode(node2))
        assertTrue(source.containsEdge(edge1))
        assertEquals("v", (source.getNodeProperties(node1)["k"] as StrVal).core)

        source.close()
        target.close()
    }
}
