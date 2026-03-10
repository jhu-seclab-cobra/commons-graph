package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class MapDBStorageImplWhiteBoxTest {
    private lateinit var storage: MapDBStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge12 = EdgeID(node1, node2, "e12")
    private val edge23 = EdgeID(node2, node3, "e23")
    private val edge13 = EdgeID(node1, node3, "e13")

    @BeforeTest
    fun setup() {
        storage = MapDBStorageImpl { memoryDB() }
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // -- graphStructure SetVal consistency after addEdge --

    @Test
    fun `test addEdge stores serialized edge in graphStructure for both src and dst nodes`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)

        val outgoing = storage.getOutgoingEdges(node1)
        val incoming = storage.getIncomingEdges(node2)

        assertEquals(setOf(edge12), outgoing)
        assertEquals(setOf(edge12), incoming)
    }

    @Test
    fun `test addEdge accumulates multiple edges in graphStructure for same node`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge12)
        storage.addEdge(edge13)

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(edge12))
        assertTrue(outgoing.contains(edge13))
    }

    // -- graphStructure cleanup after deleteEdge --

    @Test
    fun `test deleteEdge removes serialized edge from graphStructure for both endpoints`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)

        storage.deleteEdge(edge12)

        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    @Test
    fun `test deleteEdge preserves other edges in graphStructure`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge12)
        storage.addEdge(edge13)

        storage.deleteEdge(edge12)

        val remaining = storage.getOutgoingEdges(node1)
        assertEquals(1, remaining.size)
        assertTrue(remaining.contains(edge13))
    }

    // -- graphStructure cleanup after deleteNode --

    @Test
    fun `test deleteNode removes graphStructure entry and cascades edge deletion`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge12)
        storage.addEdge(edge13)
        storage.addEdge(edge23)

        storage.deleteNode(node1)

        assertFalse(storage.containsEdge(edge12))
        assertFalse(storage.containsEdge(edge13))
        assertTrue(storage.containsEdge(edge23))

        assertTrue(storage.getIncomingEdges(node2).isEmpty())
        assertEquals(setOf(edge23), storage.getOutgoingEdges(node2))
    }

    // -- setProperties merge+filterValues pattern --

    @Test
    fun `test setNodeProperties merges and null removes via filterValues`() {
        storage.addNode(node1, mapOf("a" to 1.numVal, "b" to 2.numVal))

        storage.setNodeProperties(node1, mapOf("a" to null, "c" to 3.numVal))

        val props = storage.getNodeProperties(node1)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
        assertEquals(3, (props["c"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties merges and null removes via filterValues`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12, mapOf("x" to "old".strVal, "y" to "keep".strVal))

        storage.setEdgeProperties(edge12, mapOf("x" to null, "z" to "new".strVal))

        val props = storage.getEdgeProperties(edge12)
        assertNull(props["x"])
        assertEquals("keep", (props["y"] as StrVal).core)
        assertEquals("new", (props["z"] as StrVal).core)
    }

    // -- EntityPropertyMap backed by MapDB --

    @Test
    fun `test node properties persist across reads via EntityPropertyMap`() {
        storage.addNode(node1, mapOf("key" to "value".strVal))

        val props1 = storage.getNodeProperties(node1)
        val props2 = storage.getNodeProperties(node1)
        assertEquals(props1, props2)
    }

    // -- getIncomingEdges/getOutgoingEdges filter logic --

    @Test
    fun `test getIncomingEdges filters by dstNid from graphStructure`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge12)
        storage.addEdge(edge13)

        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(setOf(edge12), incoming2)

        val incoming3 = storage.getIncomingEdges(node3)
        assertEquals(setOf(edge13), incoming3)
    }

    @Test
    fun `test getOutgoingEdges filters by srcNid from graphStructure`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge12)
        storage.addEdge(edge23)

        val outgoing1 = storage.getOutgoingEdges(node1)
        assertEquals(setOf(edge12), outgoing1)

        val outgoing2 = storage.getOutgoingEdges(node2)
        assertEquals(setOf(edge23), outgoing2)
    }

    @Test
    fun `test getIncomingEdges returns empty when no edges in graphStructure`() {
        storage.addNode(node1)
        assertTrue(storage.getIncomingEdges(node1).isEmpty())
    }

    // -- Self-loop edge in graphStructure --

    @Test
    fun `test self loop edge stored in graphStructure under single node name`() {
        storage.addNode(node1)
        val selfEdge = EdgeID(node1, node1, "self")
        storage.addEdge(selfEdge)

        assertTrue(selfEdge in storage.getOutgoingEdges(node1))
        assertTrue(selfEdge in storage.getIncomingEdges(node1))
    }

    @Test
    fun `test deleteNode removes self loop edge`() {
        storage.addNode(node1)
        val selfEdge = EdgeID(node1, node1, "self")
        storage.addEdge(selfEdge)

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- clear() handles DBException.VolumeIOError by returning false --

    @Test
    fun `test clear returns true on fresh storage`() {
        assertTrue(storage.clear())
    }

    @Test
    fun `test clear returns true after adding and clearing data`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)
        storage.setMeta("key", "val".strVal)

        assertTrue(storage.clear())
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    // -- close() uses dbManager.isClosed() for state tracking --

    @Test
    fun `test close sets dbManager closed then operations throw`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(edge12) }
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperties(edge12) }
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(node1, emptyMap()) }
        assertFailsWith<AccessClosedStorageException> { storage.setEdgeProperties(edge12, emptyMap()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteEdge(edge12) }
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("x") }
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("x", "v".strVal) }
    }

    @Test
    fun `test double close does not throw`() {
        storage.close()
        storage.close()
    }

    // -- Metadata operations --

    @Test
    fun `test meta operations store and retrieve`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertTrue("version" in storage.metaNames)
    }

    @Test
    fun `test setMeta null removes entry`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)

        assertNull(storage.getMeta("key"))
        assertFalse("key" in storage.metaNames)
    }

    @Test
    fun `test getMeta returns null for nonexistent`() {
        assertNull(storage.getMeta("nonexistent"))
    }

    // -- Config: memoryDB() vs default tempFileDB --

    @Test
    fun `test memoryDB config creates working storage`() {
        val memStorage = MapDBStorageImpl { memoryDB() }
        memStorage.addNode(NodeID("test"))
        assertTrue(memStorage.containsNode(NodeID("test")))
        memStorage.close()
    }

    // -- Edge existence contract --

    @Test
    fun `test deleteEdge nonexistent does not throw but removes from graphStructure`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)

        storage.deleteEdge(edge12)
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
    }

    @Test
    fun `test getIncomingEdges throws for nonexistent node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(node1) }
    }

    @Test
    fun `test getOutgoingEdges throws for nonexistent node`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(node1) }
    }
}
