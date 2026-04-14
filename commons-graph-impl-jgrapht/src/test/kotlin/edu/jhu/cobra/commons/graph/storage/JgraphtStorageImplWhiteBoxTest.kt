/**
 * White-box tests for JGraphT-specific internal behavior of [JgraphtStorageImpl].
 *
 * - `nodeIDs preserves insertion order via linkedMapOf`
 * - `edgeIDs preserves insertion order via linkedMapOf`
 * - `getNodeProperties returns mutable reference to internal map`
 * - `nodeIDs returns copy not live view`
 * - `edgeIDs returns copy not live view`
 * - `pseudograph allows multiple edges between same node pair`
 * - `deleteNode cascading deletes self loop edge`
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.numVal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class JgraphtStorageImplWhiteBoxTest {
    private lateinit var storage: JgraphtStorageImpl

    @BeforeTest
    fun setUp() {
        storage = JgraphtStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // -- LinkedHashMap insertion order preservation --

    @Test
    fun `nodeIDs preserves insertion order via linkedMapOf`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        assertEquals(listOf(n1, n2, n3), storage.nodeIDs.toList())
    }

    @Test
    fun `edgeIDs preserves insertion order via linkedMapOf`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "e1")
        val e2 = storage.addEdge(n2, n3, "e2")
        val e3 = storage.addEdge(n1, n3, "e3")
        assertEquals(listOf(e1, e2, e3), storage.edgeIDs.toList())
    }

    // -- Internal map reference behavior --

    @Test
    fun `getNodeProperties returns mutable reference to internal map`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))
        val props = storage.getNodeProperties(node1)
        assertTrue(props is MutableMap)
    }

    // -- Snapshot behavior --

    @Test
    fun `nodeIDs returns copy not live view`() {
        storage.addNode()
        val snapshot = storage.nodeIDs
        storage.addNode()
        assertEquals(1, snapshot.size)
        assertEquals(2, storage.nodeIDs.size)
    }

    @Test
    fun `edgeIDs returns copy not live view`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "e")
        val snapshot = storage.edgeIDs
        val n3 = storage.addNode()
        storage.addEdge(n2, n3, "e2")
        assertEquals(1, snapshot.size)
        assertEquals(2, storage.edgeIDs.size)
    }

    // -- DirectedPseudograph behavior --

    @Test
    fun `pseudograph allows multiple edges between same node pair`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "type_a")
        val e2 = storage.addEdge(n1, n2, "type_b")
        assertEquals(2, storage.getOutgoingEdges(n1).size)
        assertEquals(2, storage.getIncomingEdges(n2).size)
        assertTrue(storage.containsEdge(e1))
        assertTrue(storage.containsEdge(e2))
    }

    // -- Self-loop cascade --

    @Test
    fun `deleteNode cascading deletes self loop edge`() {
        val node1 = storage.addNode()
        val selfEdge = storage.addEdge(node1, node1, "self")
        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(selfEdge))
    }
}
