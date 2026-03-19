package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class MapDBStorageImplWhiteBoxTest {
    private lateinit var storage: MapDBStorageImpl

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
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")

        val outgoing = storage.getOutgoingEdges(node1)
        val incoming = storage.getIncomingEdges(node2)

        assertEquals(setOf(edge12), outgoing)
        assertEquals(setOf(edge12), incoming)
    }

    @Test
    fun `test addEdge accumulates multiple edges in graphStructure for same node`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge13 = storage.addEdge(node1, node3, "e13")

        val outgoing = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(edge12))
        assertTrue(outgoing.contains(edge13))
    }

    // -- graphStructure cleanup after deleteEdge --

    @Test
    fun `test deleteEdge removes serialized edge from graphStructure for both endpoints`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")

        storage.deleteEdge(edge12)

        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    @Test
    fun `test deleteEdge preserves other edges in graphStructure`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge13 = storage.addEdge(node1, node3, "e13")

        storage.deleteEdge(edge12)

        val remaining = storage.getOutgoingEdges(node1)
        assertEquals(1, remaining.size)
        assertTrue(remaining.contains(edge13))
    }

    // -- graphStructure cleanup after deleteNode --

    @Test
    fun `test deleteNode removes graphStructure entry and cascades edge deletion`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge13 = storage.addEdge(node1, node3, "e13")
        val edge23 = storage.addEdge(node2, node3, "e23")

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
        val node1 = storage.addNode(mapOf("a" to 1.numVal, "b" to 2.numVal))

        storage.setNodeProperties(node1, mapOf("a" to null, "c" to 3.numVal))

        val props = storage.getNodeProperties(node1)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
        assertEquals(3, (props["c"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties merges and null removes via filterValues`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12", mapOf("x" to "old".strVal, "y" to "keep".strVal))

        storage.setEdgeProperties(edge12, mapOf("x" to null, "z" to "new".strVal))

        val props = storage.getEdgeProperties(edge12)
        assertNull(props["x"])
        assertEquals("keep", (props["y"] as StrVal).core)
        assertEquals("new", (props["z"] as StrVal).core)
    }

    // -- EntityPropertyMap backed by MapDB --

    @Test
    fun `test node properties persist across reads via EntityPropertyMap`() {
        val node1 = storage.addNode(mapOf("key" to "value".strVal))

        val props1 = storage.getNodeProperties(node1)
        val props2 = storage.getNodeProperties(node1)
        assertEquals(props1, props2)
    }

    // -- getIncomingEdges/getOutgoingEdges filter logic --

    @Test
    fun `test getIncomingEdges filters by dstNid from graphStructure`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge13 = storage.addEdge(node1, node3, "e13")

        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(setOf(edge12), incoming2)

        val incoming3 = storage.getIncomingEdges(node3)
        assertEquals(setOf(edge13), incoming3)
    }

    @Test
    fun `test getOutgoingEdges filters by srcNid from graphStructure`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge23 = storage.addEdge(node2, node3, "e23")

        val outgoing1 = storage.getOutgoingEdges(node1)
        assertEquals(setOf(edge12), outgoing1)

        val outgoing2 = storage.getOutgoingEdges(node2)
        assertEquals(setOf(edge23), outgoing2)
    }

    @Test
    fun `test getIncomingEdges returns empty when no edges in graphStructure`() {
        val node1 = storage.addNode()
        assertTrue(storage.getIncomingEdges(node1).isEmpty())
    }

    // -- Self-loop edge in graphStructure --

    @Test
    fun `test self loop edge stored in graphStructure under single node name`() {
        val node1 = storage.addNode()
        val selfEdge = storage.addEdge(node1, node1, "self")

        assertTrue(selfEdge in storage.getOutgoingEdges(node1))
        assertTrue(selfEdge in storage.getIncomingEdges(node1))
    }

    @Test
    fun `test deleteNode removes self loop edge`() {
        val node1 = storage.addNode()
        val selfEdge = storage.addEdge(node1, node1, "self")

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- clear() handles DBException.VolumeIOError by returning false --

    @Test
    fun `test clear succeeds on fresh storage`() {
        storage.clear()
    }

    @Test
    fun `test clear succeeds after adding and clearing data`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "e12")
        storage.setMeta("key", "val".strVal)

        storage.clear()
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
        memStorage.addNode()
        assertEquals(1, memStorage.nodeIDs.size)
        memStorage.close()
    }

    // -- Edge existence contract --

    @Test
    fun `test deleteEdge nonexistent does not throw but removes from graphStructure`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")

        storage.deleteEdge(edge12)
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
    }

    @Test
    fun `test getIncomingEdges throws for nonexistent node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(-1) }
    }

    @Test
    fun `test getOutgoingEdges throws for nonexistent node`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(-1) }
    }

    // -- Single-property access --

    @Test
    fun `test getNodeProperty returns existing property`() {
        val n = storage.addNode(mapOf("name" to "hello".strVal))
        assertEquals("hello", (storage.getNodeProperty(n, "name") as StrVal).core)
    }

    @Test
    fun `test getNodeProperty returns null for absent property`() {
        val n = storage.addNode()
        assertNull(storage.getNodeProperty(n, "missing"))
    }

    @Test
    fun `test getNodeProperty throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(-1, "key") }
    }

    @Test
    fun `test getEdgeProperty returns existing property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("weight" to 1.numVal))
        assertEquals(1, (storage.getEdgeProperty(e, "weight") as NumVal).core)
    }

    @Test
    fun `test getEdgeProperty returns null for absent property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertNull(storage.getEdgeProperty(e, "missing"))
    }

    @Test
    fun `test getEdgeProperty throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperty(-1, "key") }
    }

    // -- TransferTo --

    @Test
    fun `test transferTo copies nodes edges and metadata`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        storage.addEdge(n1, n2, "rel", mapOf("w" to 1.numVal))
        storage.setMeta("version", "1.0".strVal)

        val target = MapDBStorageImpl { memoryDB() }
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("1.0", (target.getMeta("version") as StrVal).core)
        target.close()
    }

    @Test
    fun `test transferTo throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.transferTo(MapDBStorageImpl { memoryDB() }) }
    }

    // -- Edge endpoint access --

    @Test
    fun `test getEdgeSrc returns correct source`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertEquals(n1, storage.getEdgeSrc(e))
    }

    @Test
    fun `test getEdgeDst returns correct destination`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertEquals(n2, storage.getEdgeDst(e))
    }

    @Test
    fun `test getEdgeTag returns correct tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "myType")
        assertEquals("myType", storage.getEdgeTag(e))
    }
}
