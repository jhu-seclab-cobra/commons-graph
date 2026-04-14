/**
 * White-box tests for MapDB-specific internal behavior of [MapDBStorageImpl].
 *
 * - `graphStructure stores serialized edges for both src and dst nodes`
 * - `graphStructure accumulates multiple edges for same node`
 * - `deleteEdge removes from graphStructure for both endpoints`
 * - `deleteEdge preserves other edges in graphStructure`
 * - `deleteNode removes graphStructure entry and cascades edge deletion`
 * - `setNodeProperties merges and null removes via filterValues`
 * - `setEdgeProperties merges and null removes via filterValues`
 * - `node properties persist across reads via EntityPropertyMap`
 * - `self loop edge stored under single node in graphStructure`
 * - `deleteNode removes self loop edge`
 * - `clear succeeds on fresh storage`
 * - `clear succeeds after adding and clearing data`
 * - `close sets dbManager closed then operations throw`
 * - `double close does not throw`
 * - `memoryDB config creates working storage`
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MapDBStorageImplWhiteBoxTest {
    private lateinit var storage: MapDBStorageImpl

    @BeforeTest
    fun setUp() {
        storage = MapDBStorageImpl { memoryDB() }
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // -- graphStructure consistency after addEdge --

    @Test
    fun `graphStructure stores serialized edges for both src and dst nodes`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e12")
        assertEquals(setOf(e), storage.getOutgoingEdges(n1))
        assertEquals(setOf(e), storage.getIncomingEdges(n2))
    }

    @Test
    fun `graphStructure accumulates multiple edges for same node`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "e12")
        val e13 = storage.addEdge(n1, n3, "e13")
        val outgoing = storage.getOutgoingEdges(n1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(e12))
        assertTrue(outgoing.contains(e13))
    }

    // -- graphStructure cleanup after deleteEdge --

    @Test
    fun `deleteEdge removes from graphStructure for both endpoints`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e12")
        storage.deleteEdge(e)
        assertTrue(storage.getOutgoingEdges(n1).isEmpty())
        assertTrue(storage.getIncomingEdges(n2).isEmpty())
    }

    @Test
    fun `deleteEdge preserves other edges in graphStructure`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "e12")
        val e13 = storage.addEdge(n1, n3, "e13")
        storage.deleteEdge(e12)
        assertEquals(setOf(e13), storage.getOutgoingEdges(n1))
    }

    // -- graphStructure cleanup after deleteNode --

    @Test
    fun `deleteNode removes graphStructure entry and cascades edge deletion`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "e12")
        val e13 = storage.addEdge(n1, n3, "e13")
        val e23 = storage.addEdge(n2, n3, "e23")

        storage.deleteNode(n1)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e13))
        assertTrue(storage.containsEdge(e23))
        assertTrue(storage.getIncomingEdges(n2).isEmpty())
        assertEquals(setOf(e23), storage.getOutgoingEdges(n2))
    }

    // -- setProperties merge+filterValues pattern --

    @Test
    fun `setNodeProperties merges and null removes via filterValues`() {
        val n = storage.addNode(mapOf("a" to 1.numVal, "b" to 2.numVal))
        storage.setNodeProperties(n, mapOf("a" to null, "c" to 3.numVal))
        val props = storage.getNodeProperties(n)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
        assertEquals(3, (props["c"] as NumVal).core)
    }

    @Test
    fun `setEdgeProperties merges and null removes via filterValues`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e", mapOf("x" to "old".strVal, "y" to "keep".strVal))
        storage.setEdgeProperties(e, mapOf("x" to null, "z" to "new".strVal))
        val props = storage.getEdgeProperties(e)
        assertNull(props["x"])
        assertEquals("keep", (props["y"] as StrVal).core)
        assertEquals("new", (props["z"] as StrVal).core)
    }

    // -- EntityPropertyMap backed by MapDB --

    @Test
    fun `node properties persist across reads via EntityPropertyMap`() {
        val n = storage.addNode(mapOf("key" to "value".strVal))
        val props1 = storage.getNodeProperties(n)
        val props2 = storage.getNodeProperties(n)
        assertEquals(props1, props2)
    }

    // -- Self-loop edge --

    @Test
    fun `self loop edge stored under single node in graphStructure`() {
        val n = storage.addNode()
        val selfEdge = storage.addEdge(n, n, "self")
        assertTrue(selfEdge in storage.getOutgoingEdges(n))
        assertTrue(selfEdge in storage.getIncomingEdges(n))
    }

    @Test
    fun `deleteNode removes self loop edge`() {
        val n = storage.addNode()
        val selfEdge = storage.addEdge(n, n, "self")
        storage.deleteNode(n)
        assertFalse(storage.containsNode(n))
        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- clear --

    @Test
    fun `clear succeeds on fresh storage`() {
        storage.clear()
    }

    @Test
    fun `clear succeeds after adding and clearing data`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "e12")
        storage.setMeta("key", "val".strVal)
        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    // -- close --

    @Test
    fun `close sets dbManager closed then operations throw`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(0) }
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(0) }
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(0) }
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperties(0) }
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(0, emptyMap()) }
        assertFailsWith<AccessClosedStorageException> { storage.setEdgeProperties(0, emptyMap()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(0) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteEdge(0) }
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("x") }
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("x", "v".strVal) }
    }

    @Test
    fun `double close does not throw`() {
        storage.close()
        storage.close()
    }

    // -- Config --

    @Test
    fun `memoryDB config creates working storage`() {
        val memStorage = MapDBStorageImpl { memoryDB() }
        memStorage.addNode()
        assertEquals(1, memStorage.nodeIDs.size)
        memStorage.close()
    }
}
