package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

class JgraphtStorageImplWhiteBoxTest {
    private lateinit var storage: JgraphtStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge12 = EdgeID(node1, node2, "e12")
    private val edge23 = EdgeID(node2, node3, "e23")
    private val edge13 = EdgeID(node1, node3, "e13")

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
        val nodes = listOf(NodeID("c"), NodeID("a"), NodeID("b"))
        nodes.forEach { storage.addNode(it) }

        val ids = storage.nodeIDs.toList()
        assertEquals(nodes, ids)
    }

    @Test
    fun `test edgeIDs preserves insertion order via linkedMapOf`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        val n3 = NodeID("n3")
        storage.addNode(n1)
        storage.addNode(n2)
        storage.addNode(n3)

        val edges = listOf(
            EdgeID(n1, n2, "e1"),
            EdgeID(n2, n3, "e2"),
            EdgeID(n1, n3, "e3"),
        )
        edges.forEach { storage.addEdge(it) }

        val ids = storage.edgeIDs.toList()
        assertEquals(edges, ids)
    }

    // -- nodeProperties and jgtGraph consistency --

    @Test
    fun `test addNode syncs jgtGraph and nodeProperties`() {
        storage.addNode(node1, mapOf("k" to "v".strVal))

        assertTrue(storage.containsNode(node1))
        assertEquals(1, storage.nodeIDs.size)
        assertEquals("v", (storage.getNodeProperties(node1)["k"] as StrVal).core)
    }

    @Test
    fun `test addEdge syncs jgtGraph and edgeProperties`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12, mapOf("k" to "v".strVal))

        assertTrue(storage.containsEdge(edge12))
        assertEquals(setOf(edge12), storage.getOutgoingEdges(node1))
        assertEquals(setOf(edge12), storage.getIncomingEdges(node2))
        assertEquals("v", (storage.getEdgeProperties(edge12)["k"] as StrVal).core)
    }

    @Test
    fun `test deleteNode removes associated edges from both jgtGraph and edgeProperties`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge12)
        storage.addEdge(edge13)
        storage.addEdge(edge23)

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge12))
        assertFalse(storage.containsEdge(edge13))
        assertTrue(storage.containsEdge(edge23))
        assertEquals(setOf(edge23), storage.getOutgoingEdges(node2))
    }

    @Test
    fun `test deleteEdge removes from both jgtGraph and edgeProperties`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)

        storage.deleteEdge(edge12)

        assertFalse(storage.containsEdge(edge12))
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    // -- setProperties merge semantics --

    @Test
    fun `test setNodeProperties null removes existing property`() {
        storage.addNode(node1, mapOf("a" to 1.numVal, "b" to 2.numVal))

        storage.setNodeProperties(node1, mapOf("a" to null))

        val props = storage.getNodeProperties(node1)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties merges new and updates existing`() {
        storage.addNode(node1, mapOf("a" to 1.numVal))

        storage.setNodeProperties(node1, mapOf("a" to 10.numVal, "b" to 20.numVal))

        val props = storage.getNodeProperties(node1)
        assertEquals(10, (props["a"] as NumVal).core)
        assertEquals(20, (props["b"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties null removes existing property`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12, mapOf("x" to "y".strVal, "z" to "w".strVal))

        storage.setEdgeProperties(edge12, mapOf("x" to null))

        val props = storage.getEdgeProperties(edge12)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    // -- getNodeProperties returns mutable reference (white-box: no defensive copy in non-concurrent) --

    @Test
    fun `test getNodeProperties returns reference to internal map`() {
        storage.addNode(node1, mapOf("a" to 1.numVal))

        val props = storage.getNodeProperties(node1)
        assertTrue(props is MutableMap)
    }

    // -- clear() verifies both jgtGraph and property maps are empty --

    @Test
    fun `test clear empties all internal structures and returns true`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)

        val result = storage.clear()

        assertTrue(result)
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `test clear on already closed storage returns false`() {
        storage.close()

        val newStorage = JgraphtStorageImpl()
        newStorage.close()
        assertFalse(newStorage.clear())
    }

    // -- close() sets isClosed via clear() --

    @Test
    fun `test close sets isClosed then all operations throw`() {
        storage.addNode(node1)
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
        storage.addNode(node1)
        storage.addNode(node2)

        val e1 = EdgeID(node1, node2, "type_a")
        val e2 = EdgeID(node1, node2, "type_b")
        storage.addEdge(e1)
        storage.addEdge(e2)

        assertEquals(2, storage.getOutgoingEdges(node1).size)
        assertEquals(2, storage.getIncomingEdges(node2).size)
        assertTrue(storage.containsEdge(e1))
        assertTrue(storage.containsEdge(e2))
    }

    // -- Self-loop edges --

    @Test
    fun `test self loop edge appears in both incoming and outgoing`() {
        storage.addNode(node1)
        val selfEdge = EdgeID(node1, node1, "self")
        storage.addEdge(selfEdge)

        assertTrue(selfEdge in storage.getOutgoingEdges(node1))
        assertTrue(selfEdge in storage.getIncomingEdges(node1))
    }

    // -- deleteNode cascades edges via deleteEdge calls --

    @Test
    fun `test deleteNode cascading deletes self loop edge`() {
        storage.addNode(node1)
        val selfEdge = EdgeID(node1, node1, "self")
        storage.addEdge(selfEdge)

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
        storage.addNode(node2)

        assertFailsWith<EntityNotExistException> { storage.addEdge(edge12) }
    }

    @Test
    fun `test addEdge throws when dst node missing`() {
        storage.addNode(node1)

        assertFailsWith<EntityNotExistException> { storage.addEdge(edge12) }
    }

    @Test
    fun `test addNode duplicate throws EntityAlreadyExistException`() {
        storage.addNode(node1)

        assertFailsWith<EntityAlreadyExistException> { storage.addNode(node1) }
    }

    @Test
    fun `test addEdge duplicate throws EntityAlreadyExistException`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)

        assertFailsWith<EntityAlreadyExistException> { storage.addEdge(edge12) }
    }

    @Test
    fun `test deleteNode nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(node1) }
    }

    @Test
    fun `test deleteEdge nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(edge12) }
    }

    @Test
    fun `test getIncomingEdges nonexistent node throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(node1) }
    }

    @Test
    fun `test getOutgoingEdges nonexistent node throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(node1) }
    }

    // -- nodeIDs and edgeIDs return snapshot copies --

    @Test
    fun `test nodeIDs returns copy not live view`() {
        storage.addNode(node1)
        val snapshot = storage.nodeIDs

        storage.addNode(node2)

        assertEquals(1, snapshot.size)
        assertEquals(2, storage.nodeIDs.size)
    }

    @Test
    fun `test edgeIDs returns copy not live view`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)
        val snapshot = storage.edgeIDs

        storage.addNode(node3)
        storage.addEdge(edge23)

        assertEquals(1, snapshot.size)
        assertEquals(2, storage.edgeIDs.size)
    }

    // -- addNode with empty properties creates entry with empty map --

    @Test
    fun `test addNode with default empty properties`() {
        storage.addNode(node1)

        assertTrue(storage.getNodeProperties(node1).isEmpty())
    }

    // -- Comprehensive deleteNode with multiple edge types --

    @Test
    fun `test deleteNode removes incoming and outgoing edges but preserves other edges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)

        val e12 = EdgeID(node1, node2, "out")
        val e32 = EdgeID(node3, node2, "in")
        val e13 = EdgeID(node1, node3, "other")
        storage.addEdge(e12)
        storage.addEdge(e32)
        storage.addEdge(e13)

        storage.deleteNode(node2)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e32))
        assertTrue(storage.containsEdge(e13))
    }
}
