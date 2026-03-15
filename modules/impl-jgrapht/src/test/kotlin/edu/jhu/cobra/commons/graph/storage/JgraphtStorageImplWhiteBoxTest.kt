package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

class JgraphtStorageImplWhiteBoxTest {
    private lateinit var storage: JgraphtStorageImpl

    @Before
    fun setup() {
        storage = JgraphtStorageImpl()
    }

    @After
    fun cleanup() {
        storage.close()
    }

    // -- LinkedHashMap insertion order preservation --

    @Test
    fun `test nodeIDs preserves insertion order via linkedMapOf`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()

        val ids = storage.nodeIDs.toList()
        assertEquals(listOf(n1, n2, n3), ids)
    }

    @Test
    fun `test edgeIDs preserves insertion order via linkedMapOf`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()

        val e1 = storage.addEdge(n1, n2, "e1")
        val e2 = storage.addEdge(n2, n3, "e2")
        val e3 = storage.addEdge(n1, n3, "e3")

        val ids = storage.edgeIDs.toList()
        assertEquals(listOf(e1, e2, e3), ids)
    }

    // -- nodeProperties and jgtGraph consistency --

    @Test
    fun `test addNode syncs jgtGraph and nodeProperties`() {
        val node1 = storage.addNode(mapOf("k" to "v".strVal))

        assertTrue(storage.containsNode(node1))
        assertEquals(1, storage.nodeIDs.size)
        assertEquals("v", (storage.getNodeProperties(node1)["k"] as StrVal).core)
    }

    @Test
    fun `test addEdge syncs jgtGraph and edgeProperties`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12", mapOf("k" to "v".strVal))

        assertTrue(storage.containsEdge(edge12))
        assertEquals(setOf(edge12), storage.getOutgoingEdges(node1))
        assertEquals(setOf(edge12), storage.getIncomingEdges(node2))
        assertEquals("v", (storage.getEdgeProperties(edge12)["k"] as StrVal).core)
    }

    @Test
    fun `test deleteNode removes associated edges from both jgtGraph and edgeProperties`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge13 = storage.addEdge(node1, node3, "e13")
        val edge23 = storage.addEdge(node2, node3, "e23")

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge12))
        assertFalse(storage.containsEdge(edge13))
        assertTrue(storage.containsEdge(edge23))
        assertEquals(setOf(edge23), storage.getOutgoingEdges(node2))
    }

    @Test
    fun `test deleteEdge removes from both jgtGraph and edgeProperties`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")

        storage.deleteEdge(edge12)

        assertFalse(storage.containsEdge(edge12))
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    // -- setProperties merge semantics --

    @Test
    fun `test setNodeProperties null removes existing property`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal, "b" to 2.numVal))

        storage.setNodeProperties(node1, mapOf("a" to null))

        val props = storage.getNodeProperties(node1)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties merges new and updates existing`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))

        storage.setNodeProperties(node1, mapOf("a" to 10.numVal, "b" to 20.numVal))

        val props = storage.getNodeProperties(node1)
        assertEquals(10, (props["a"] as NumVal).core)
        assertEquals(20, (props["b"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties null removes existing property`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12", mapOf("x" to "y".strVal, "z" to "w".strVal))

        storage.setEdgeProperties(edge12, mapOf("x" to null))

        val props = storage.getEdgeProperties(edge12)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    // -- getNodeProperties returns mutable reference (white-box: no defensive copy in non-concurrent) --

    @Test
    fun `test getNodeProperties returns reference to internal map`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))

        val props = storage.getNodeProperties(node1)
        assertTrue(props is MutableMap)
    }

    // -- clear() verifies both jgtGraph and property maps are empty --

    @Test
    fun `test clear empties all internal structures`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "e12")

        storage.clear()

        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `test clear on already closed storage throws`() {
        storage.close()

        val newStorage = JgraphtStorageImpl()
        newStorage.close()
        assertFailsWith<AccessClosedStorageException> { newStorage.clear() }
    }

    // -- close() sets isClosed via clear() --

    @Test
    fun `test close sets isClosed then all operations throw`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(edge12) }
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.getIncomingEdges(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.getOutgoingEdges(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(node1, emptyMap()) }
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("x") }
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("x", "v".strVal) }
    }

    // -- DirectedPseudograph allows parallel edges --

    @Test
    fun `test pseudograph allows multiple edges between same node pair`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()

        val e1 = storage.addEdge(node1, node2, "type_a")
        val e2 = storage.addEdge(node1, node2, "type_b")

        assertEquals(2, storage.getOutgoingEdges(node1).size)
        assertEquals(2, storage.getIncomingEdges(node2).size)
        assertTrue(storage.containsEdge(e1))
        assertTrue(storage.containsEdge(e2))
    }

    // -- Self-loop edges --

    @Test
    fun `test self loop edge appears in both incoming and outgoing`() {
        val node1 = storage.addNode()
        val selfEdge = storage.addEdge(node1, node1, "self")

        assertTrue(selfEdge in storage.getOutgoingEdges(node1))
        assertTrue(selfEdge in storage.getIncomingEdges(node1))
    }

    // -- deleteNode cascades edges via deleteEdge calls --

    @Test
    fun `test deleteNode cascading deletes self loop edge`() {
        val node1 = storage.addNode()
        val selfEdge = storage.addEdge(node1, node1, "self")

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- Metadata operations --

    @Test
    fun `test meta operations store and retrieve`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertEquals(setOf("version"), storage.metaNames)
    }

    @Test
    fun `test setMeta null removes metadata entry`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)

        assertNull(storage.getMeta("key"))
        assertFalse("key" in storage.metaNames)
    }

    @Test
    fun `test getMeta returns null for nonexistent key`() {
        assertNull(storage.getMeta("nonexistent"))
    }

    @Test
    fun `test clear also clears metadata`() {
        storage.setMeta("key", "val".strVal)
        storage.clear()

        assertTrue(storage.metaNames.isEmpty())
        assertNull(storage.getMeta("key"))
    }

    // -- Entity existence contract checks --

    @Test
    fun `test addEdge throws when src node missing`() {
        val node2 = storage.addNode()

        assertFailsWith<EntityNotExistException> { storage.addEdge(-1, node2, "e12") }
    }

    @Test
    fun `test addEdge throws when dst node missing`() {
        val node1 = storage.addNode()

        assertFailsWith<EntityNotExistException> { storage.addEdge(node1, -1, "e12") }
    }

    @Test
    fun `test addEdge duplicate same type allowed with auto-increment IDs`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val e1 = storage.addEdge(node1, node2, "e12")
        val e2 = storage.addEdge(node1, node2, "e12")

        assertNotEquals(e1, e2)
        assertEquals(2, storage.getOutgoingEdges(node1).size)
    }

    @Test
    fun `test deleteNode nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }

    @Test
    fun `test deleteEdge nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(-1) }
    }

    @Test
    fun `test getIncomingEdges nonexistent node throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(-1) }
    }

    @Test
    fun `test getOutgoingEdges nonexistent node throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(-1) }
    }

    // -- nodeIDs and edgeIDs return snapshot copies --

    @Test
    fun `test nodeIDs returns copy not live view`() {
        val node1 = storage.addNode()
        val snapshot = storage.nodeIDs

        storage.addNode()

        assertEquals(1, snapshot.size)
        assertEquals(2, storage.nodeIDs.size)
    }

    @Test
    fun `test edgeIDs returns copy not live view`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "e12")
        val snapshot = storage.edgeIDs

        val node3 = storage.addNode()
        storage.addEdge(node2, node3, "e23")

        assertEquals(1, snapshot.size)
        assertEquals(2, storage.edgeIDs.size)
    }

    // -- addNode with empty properties creates entry with empty map --

    @Test
    fun `test addNode with default empty properties`() {
        val node1 = storage.addNode()

        assertTrue(storage.getNodeProperties(node1).isEmpty())
    }

    // -- Comprehensive deleteNode with multiple edge types --

    @Test
    fun `test deleteNode removes incoming and outgoing edges but preserves other edges`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()

        val e12 = storage.addEdge(node1, node2, "out")
        val e32 = storage.addEdge(node3, node2, "in")
        val e13 = storage.addEdge(node1, node3, "other")

        storage.deleteNode(node2)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e32))
        assertTrue(storage.containsEdge(e13))
    }
}
